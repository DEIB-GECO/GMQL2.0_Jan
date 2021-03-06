package it.polimi.genomics.flink.FlinkImplementation.operator.meta

import it.polimi.genomics.core.DataStructures.GroupMDParameters.Direction.Direction
import it.polimi.genomics.core.DataStructures.GroupMDParameters._
import it.polimi.genomics.core.DataStructures.MetaOperator
import it.polimi.genomics.core.DataTypes.FlinkMetaType
import it.polimi.genomics.core.exception.SelectFormatException
import it.polimi.genomics.flink.FlinkImplementation.FlinkImplementation
import org.apache.flink.api.scala._
import org.apache.flink.hadoop.shaded.com.google.common.base.Charsets
import org.apache.flink.hadoop.shaded.com.google.common.hash.Hashing
import org.slf4j.LoggerFactory

/**
 * Created by michelebertoni on 09/06/15.
 */
object OrderMD {

  final val logger = LoggerFactory.getLogger(this.getClass)

  @throws[SelectFormatException]
  def apply(executor : FlinkImplementation, ordering : List[(String,Direction)], newAttribute : String, topParameter : TopParameter, inputDataset : MetaOperator, env : ExecutionEnvironment) : DataSet[FlinkMetaType] = {

    //logger.warn("Executing OrderMD")

    val ds : DataSet[(Long, String, String)] =
      executor.implement_md(inputDataset, env)

    val grouping : Boolean =
      topParameter match {
        case NoTop() => false
        case Top(_) => false
        case TopG(_) => true
      }

    val top : Int =
      topParameter match {
        case NoTop() => 0
        case Top(v) => v
        case TopG(v) => v
      }

    //extract the key of metadata that will be used in grouping/ordering
    val keys : List[String] =
      ordering.map(_._1)

    //meta data that match the grouping/ordering field
    val metaFilteredDs : Seq[(Long, String, String)] =
      ds
        .filter((m) => {
          keys.contains(m._2)
        })
        .collect

    //for each sample for each key take the first value that will be used for grouping/sorting
    //SampleID, List[metavalues to be used in grouping/ordering ordered as requested in input]
    val valueList : Map[Long, List[String]] =
      metaFilteredDs
        .groupBy((v) => v._1)
        .map((g : (Long, Seq[FlinkMetaType])) => {
          keys.foldLeft( (g._1, List() : List[String]) )  ( (z, k) => {

            val matchedValues : Seq[(Long, String, String)] =
              g._2.filter((m : FlinkMetaType) => m._2.equals(k))

            val head : String =
              if(matchedValues.size < 1){
                "ZZ_GMQL_null_element"
              } else {
                ordering.filter((o) => o._1.equals(matchedValues.head._2)).head._2 match{
                  case Direction.ASC => matchedValues.sortWith((a,b) => a._3.compareTo(b._3) < 0).head._3
                  case Direction.DESC => matchedValues.sortWith((a,b) => a._3.compareTo(b._3) > 0).head._3
                }
              }

            (z._1, z._2 :+ head )

          } )
        })

    //list of meta data that will be added
    //if grouping is defined there are 2 meta tuple for each sample
    //they are in the form
    //sampleID newAttribute [ position | positionInGroup ]
    //sampleID newAttribute_group groupID
    val sortedTop : List[(Long, String, String)] =
      if(grouping){

        //(groupId, List[SampleId, attribute used for grouping/ordering])
        val groupedSamples : Map[Long, List[(Long, String)]] =
          valueList
            .groupBy((s : (Long, List[String])) => {
              //drop the last and group by n-1 element
              Hashing.md5().hashString(s._2.init.mkString("§"), Charsets.UTF_8).asLong()
            })
            //take only - groupId, (sampleId, lastElement that will be used for ordering)
            .map((g) => {
              (g._1,
                g._2
                  .toList
                  .map((s) => {
                    (s._1, s._2.last)
                  })
              )
            })

        //(GroupId, OrderedList[SampleId, attribute to be ordered])
        val sortedGroups : Map[Long, List[(Long, String)]] =
          //sort each group by the last value
          ordering.last._2 match{
            case Direction.ASC => groupedSamples.map((g) => {
                (g._1, g._2.sortWith((a,b) => a._2.compareTo(b._2) < 0))
              })
            case Direction.DESC => groupedSamples.map((g) => {
                (g._1, g._2.sortWith((a,b) => a._2.compareTo(b._2) > 0))
              })
          }

        sortedGroups.flatMap((g) => {
          //TOPG
          val gFiltered =
            if(top == 0){
              g._2.map(_._1)
            } else{
              g._2.map(_._1).take(top)
            }
          //create metadata
          assignPosition(Some(g._1), gFiltered, 1, newAttribute, List())
        }).toList


      } else {
        val comparator : ((Long, List[String]), (Long, List[String])) => Boolean =
          metaSampleComparator(ordering)

        //sort the list of sample by all the fields and take only the sampleId
        //OrderedList[SampleId]
        val sortedSamples : List[Long] =
          valueList
            .toList
            .sortWith((a,b) => {
              comparator(a,b)
            })
            .map(_._1)

        //TOP
        val filteredSortedSamples: List[Long] =
          if(top != 0){
            sortedSamples.take(top)
          } else {
            sortedSamples
          }
        //create metadata
        assignPosition(None, filteredSortedSamples, 1, newAttribute, List())
      }

    //extract id of resulting set
    //if top is applied it is a subset of original set
    val filteredId : List[Long] =
      sortedTop
        .map(_._1)
        .distinct

    //filter input dataset by id
    val topDs : DataSet[(Long, String, String)] =
      ds.filter((m) => filteredId.contains(m._1))

    //Create the dataset, merge with input and return as output
    topDs.union(env.fromCollection(sortedTop))

  }

  /**
   * Creates 1 or 2 metadata line for each sample:
   * one with the groupID (if defined)
   * one with the position in the group
   *
   * @param groupId groupId
   * @param list ordered list of sample
   * @param step position
   * @param newAttribute new attribute name for position, group attribute will be called newAttribute_groupId
   * @param acc recursive accumulator
   * @return Seq of meta data
   */
  def assignPosition(groupId : Option[Long], list : List[(Long)], step : Int, newAttribute : String, acc : List[FlinkMetaType]) : List[FlinkMetaType] = {
    if(list.size.equals(0)){
      acc
    } else {
      if (groupId.isDefined) {
        assignPosition(groupId, list.tail, step + 1, newAttribute,
          acc :+
            (list.head, newAttribute, step.toString) :+
            (list.head, newAttribute + "_groupId", groupId.get.toString)
        )
      } else {
        assignPosition(groupId, list.tail, step + 1, newAttribute,
          acc :+
            (list.head, newAttribute, step.toString)
        )
      }
    }
  }

  /////////////////////
  //comparator
  /////////////////////

  //true if a < b
  def metaSampleComparator(ordering : List[(String,Direction)])(a : (Long, List[String]), b : (Long, List[String])) : Boolean = {
    val ord = ordering.toArray
    val size = ord.size

    val res : Option[Boolean] =
      comparatorHelper(ordering, a, b)

    if(res.isDefined){
      res.get
    } else {
      true
    }
  }


  //true if a < b
  def comparatorHelper(ordering : List[(String,Direction)], a : (Long, List[String]), b : (Long, List[String])) : Option[Boolean] = {
    if(ordering.size < 1){
      None
    } else {
      val ord = ordering.head
      val aVal = a._2.head
      val bVal = b._2.head

      if (aVal.equals(bVal)) {
        comparatorHelper(ordering.tail, (a._1, a._2.tail), (b._1, b._2.tail))
      } else {
        ord._2 match{
          case Direction.ASC => Some(aVal.compareTo(bVal) < 0)
          case Direction.DESC => Some(aVal.compareTo(bVal) > 0)
        }
      }
    }
  }

}
