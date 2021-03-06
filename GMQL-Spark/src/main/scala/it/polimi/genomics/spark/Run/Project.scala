package it.polimi.genomics.spark.Run

/**
  * Created by abdulrahman on 27/04/16.
  */


import it.polimi.genomics.core.DataStructures.MetaAggregate.MetaAggregateStruct
import it.polimi.genomics.core.DataStructures.RegionAggregate.{RegionExtension, RegionsToMeta}
import it.polimi.genomics.core.DataStructures.RegionCondition.{Predicate, REG_OP}
import it.polimi.genomics.core.{GDouble, GString, GValue}
import it.polimi.genomics.GMQLServer.GmqlServer
import it.polimi.genomics.spark.implementation.GMQLSparkExecutor
import it.polimi.genomics.spark.implementation.loaders.BedScoreParser
import org.apache.spark.{SparkContext, SparkConf}


/**
  * The entry point of the application
  * It initialize the server, call server's methods to build the query and invoke the server's run method to start the execution.
  */
object Project {

  def main(args : Array[String]) {

    val conf = new SparkConf().setMaster("Local[*]");
    val server = new GmqlServer(new GMQLSparkExecutor(testingIOFormats = true,sc = new SparkContext(conf)))
    val mainPath = "/Users/abdulrahman/Desktop/"
    val ex_data_path = List(mainPath + "testInput/")
    val ex_data_path_optional = List(mainPath + "map/exp/")
    val output_path = mainPath + "res/"

    val dataAsTheyAre = server READ ex_data_path USING BedScoreParser
    val optionalDS = server READ ex_data_path_optional USING BedScoreParser

//    val what = 1 // project MD
    // val what = 1 // project MD and aggregate something
     val what = 2 // project MD RD and extends tuple
    // val what = 2 // project MD RD and aggregate something


    val project =
      what match{
        case 0 => {
          //PROJECT MD
          dataAsTheyAre.PROJECT(Some(List("filename","A", "B")), None, None, None)
        }

        case 1 => {
          //PROJECT MD ATTRIBUTE AND AGGREGATE MD
          val fun = new MetaAggregateStruct {
            override val newAttributeName: String = "computed_result_C"
            override val inputAttributeNames: List[String] = List("A","B")
            override val fun: (Array[Traversable[String]]) => String =
            //average of the double
              (l : Array[Traversable[String]]) => {
                val r =
                  l(0)
                    .map((a: String) => (a.toDouble * 2, 1))
                    .reduce((a: (Double, Int), b: (Double, Int)) => (a._1 + b._1, a._2 + b._2))

                (r._1 / r._2).toString
              }
          }

          dataAsTheyAre.PROJECT(Some(List("filename","A", "B")), Some(fun), None, None)
        }

        case 2 => {
          //PROJECT MD/RD ATTRIBUTE AND AGGREGATE RD TUPLE
          val fun = new RegionExtension {
            override val inputIndexes: List[Int] = List(0,1)
            override val fun: (Array[GValue]) => GValue =
            //Concatenation of strand and value
              (l : Array[GValue]) => {
                l.reduce( (a : GValue, b : GValue) => GString ( a.asInstanceOf[GString].v + " " + b.asInstanceOf[GDouble].v.toString ) )
              }
          }

          val projectrd = dataAsTheyAre.PROJECT(Some(List("filename","A", "B", "C")), None, None)
          val projectrd2 = projectrd.PROJECT(None, None, None)
          projectrd2.SELECT(reg_con = Predicate(0, REG_OP.EQ, "+ 1000.0"))
        }

        case 3 => {
          //PROJECT AGGREGATE RD
          val fun = new RegionsToMeta {
            override val newAttributeName: String = "computed_bert_value1_region_aggregate"
            override val inputIndex: Int = 1
            override val associative : Boolean = true
            override val funOut: (GValue,Int) => GValue = {(v1,v2)=>v1}
            override val fun: List[GValue] => GValue =
            //sum of values
              (l : List[GValue]) => {
                l.reduce((in1, in2) => {
                  GDouble(in1.asInstanceOf[GDouble].v + in2.asInstanceOf[GDouble].v)
                })
              }
          }

          dataAsTheyAre.PROJECT(Some(List("filename","A", "B", "C")))
        }

      }

    server setOutputPath output_path MATERIALIZE project

    server.run()
  }

}
