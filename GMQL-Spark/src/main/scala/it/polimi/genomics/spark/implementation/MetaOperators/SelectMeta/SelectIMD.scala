package it.polimi.genomics.spark.implementation.MetaOperators.SelectMeta

/**
 * Created by Abdulrahman Kaitoua on 02/06/15.
 * Email: abdulrahman.kaitoua@polimi.it
 *
 */
import java.io.{File, FileNotFoundException}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import javax.xml.bind.JAXBException

import com.google.common.hash._
import it.polimi.genomics.core.DataStructures.MetadataCondition._
import it.polimi.genomics.core.DataTypes._
import it.polimi.genomics.core.exception.SelectFormatException
import it.polimi.genomics.core.{DataTypes, GMQLLoader}
import it.polimi.genomics.repository.Index.LuceneIndex._
import it.polimi.genomics.repository.Index.SearchIndex
import it.polimi.genomics.repository.datasets.GMQLDataSetCollection
import it.polimi.genomics.repository.util._
import it.polimi.genomics.spark.implementation.GMQLSparkExecutor
import it.polimi.genomics.spark.implementation.loaders.Loaders._
import org.apache.lucene.store.{Directory, FSDirectory}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
object SelectIMD {
  private final val logger = LoggerFactory.getLogger(SelectIMD.getClass);
  var sparkContext :SparkContext = null
  def apply(executor : GMQLSparkExecutor, metaCondition: MetadataCondition, URIs: List[String], loader : GMQLLoader[Any, Any, Any, Any], sc : SparkContext) : RDD[MetaType] = {

    logger.info("----------------SELECTIMD executing..")
    sparkContext = sc

//    println ("dataset input", URIs(0),Utilities.USERNAME,Utilities.getInstance() MODE)
    var indexDir:String = null;
    MetaSelectionIndex.username = Utilities.USERNAME
    //check if there is a directory index, if not create index in memory
    val files = try{
      if(URIs.size == 1 && Utilities.getInstance().checkDSNameinRepo(Utilities.USERNAME,URIs(0))){
        val username = if(Utilities.getInstance().checkDSNameinPublic(URIs(0))) "public" else Utilities.USERNAME
        MetaSelectionIndex.username = username
        var GMQLDSCol = new GMQLDataSetCollection();
        try {
//          println("xml file : " + Utilities.getInstance().RepoDir + username + "/datasets/" + URIs(0) + ".xml")
          GMQLDSCol = GMQLDSCol.parseGMQLDataSetCollection(Paths.get(Utilities.getInstance().RepoDir + username + "/datasets/" + URIs(0) + ".xml"));
          val dataset = GMQLDSCol.getDataSetList.get(0)
          MetaSelectionIndex.index = FSDirectory.open(new File(dataset.getIndexURI))
          dataset.getURLs.asScala.map { d =>
//          println("inside SelectIMD", Utilities.getInstance().MODE)
          if (Utilities.getInstance().MODE.equals(Utilities.HDFS)) {
            val hdfs = Utilities.getInstance().gethdfsConfiguration().get("fs.defaultFS")
//            println("hdfs file: " + hdfs.substring(0,hdfs.size) + Utilities.getInstance().HDFSRepoDir + username + "/regions" + d.geturl)
            hdfs.substring(0,hdfs.size) + Utilities.getInstance().HDFSRepoDir + username + "/regions" + d.geturl
          } else d.geturl
        }
        }catch {
          case ex: JAXBException => logger.error("DataSet is corrupted"); List[String]()
          case ex: FileNotFoundException => logger.error("DataSet is not Found"); List[String]()
          case e:Exception=> logger.error(e.getMessage); List[String]()
        }
      } else {

        val res = URIs.flatMap{dirInput =>
          if(new java.io.File(dirInput).isDirectory&&URIs.size == 1)
            new java.io.File(dirInput).listFiles.filter(!_.getName.endsWith(".meta")).map(x=>x.getPath)
          else if(new java.io.File(dirInput).isDirectory) None
          else List(dirInput)
        }
        MetaSelectionIndex.index = MetaSelectionIndex.buildIndex(res.map(x=>x+".meta").toArray)
        res
      }}catch {
      case e:Exception => {
        logger.error(e.getMessage)
        val res = URIs.flatMap{dirInput =>
          if(new java.io.File(dirInput).isDirectory&&URIs.size == 1)
            new java.io.File(dirInput).listFiles.filter(!_.getName.endsWith(".meta")).map(x=>x.getPath)
          else if(new java.io.File(dirInput).isDirectory) None
          else List(dirInput)
        }
        MetaSelectionIndex.index = MetaSelectionIndex.buildIndex(res.map(x=>x+".meta").toArray)
        res
      }
    }

    // lazly read meta files for operations like greater than and less than, Cache the read for another predicates
    def parser(x: (Long, String)) = loader.asInstanceOf[GMQLLoader[(Long, String),Option[DataTypes.GRECORD], (Long, String), Option[DataTypes.MetaType]]].meta_parser(x)
    //println(files.map(x=>x+".meta").mkString(","))
    val input = sc forPath(files.map(x=>x+".meta").mkString(",")) LoadMetaCombineFiles (parser) cache

    // join the result of the selection with the input
    val ids = sc.broadcast(MetaSelectionIndex.applyMetaSelect(metaCondition, input).collect)
   // println(ids.value.size)
    //ids.value.foreach(x=>println ("IDs: ",x ))
    input.flatMap(x=>if(ids.value.contains(x._1)) Some(x) else None).cache()

  }

  object MetaSelectionIndex extends MetaSelection
  {
    var username =""
    var index: Directory = null
    override def applyContainAttribute(name : String, input : RDD[MetaType]) : RDD[ID] = {
      val query = name+"_*"

      val searchRes = searchIndex(query, index)
      if(searchRes.isDefined)
        sparkContext.parallelize(searchRes.get.split(",").map(x=> Hashing.md5().newHasher().putString(x.replaceAll("/",""),StandardCharsets.UTF_8).hash().asLong()))
      else
        sparkContext.emptyRDD[ID]
    }
    @throws[SelectFormatException]
    override def applyMetaPredicateEQ(predicate: Predicate, input: RDD[MetaType]): RDD[ID] = {
      val query = predicate.attribute_name+"_"+predicate.value

      val searchRes = searchIndex(query, index)
      if(searchRes.isDefined){
        sparkContext.parallelize{ searchRes.get.split(",").flatMap{x=> /*println ("eq",x,getURI(x),Hashing.md5().newHasher().putString(getURI(x),StandardCharsets.UTF_8).hash().asLong());*/
            if(!x.isEmpty)Some(Hashing.md5().newHasher().putString(getURI(x).replaceAll("/",""),StandardCharsets.UTF_8).hash().asLong()) else None}}
      }else
        sparkContext.emptyRDD[ID]
    }

    def getURI (uri:String) ={
      if(Utilities.getInstance().MODE == "MAPREDUCE"){
        val hdfsuri = Utilities.getInstance().gethdfsConfiguration().get("fs.defaultFS")+Utilities.getInstance().HDFSRepoDir+username+"/regions"+uri
        hdfsuri.substring(hdfsuri.indexOf(":")+1,hdfsuri.size )
      } else uri
    }
    @throws[SelectFormatException]
    override def applyMetaPredicateNOTEQ(predicate: Predicate, input: RDD[MetaType]): RDD[ID] = {
      val query = predicate.attribute_name+"* AND NOT "+predicate.attribute_name+"_"+predicate.value

      val searchRes = searchIndex(query, index)
      if(searchRes.isDefined)
        sparkContext.parallelize(searchRes.get.split(",").map(x=>  Hashing.md5().newHasher().putString(getURI(x).replaceAll("/",""),StandardCharsets.UTF_8).hash().asLong()))
      else
        sparkContext.emptyRDD[ID]
    }

    /**
     * Build Index directory of the input files
     * First we have to scan all the files and build in memory index (we can have the index on HDD already built)
     *
     * @param paths array of URIs to the meta files locations (with the file name)
     * @return Directory Index of the meta files
     */
    def buildIndex(uri: Array[String]): Directory = {
      var dir = buildInMemIndex(new File(uri(0)), 1, null, false);
      if (uri.length > 1)
        for (url <- uri.slice(1, uri.length)) {
          dir = buildInMemIndex(new File(url), 1, dir, false);
        }
      dir
    }

    def searchIndex(query: String, dir: Directory): Option[String] = {
      val search = new SearchIndex("/home/abdulrahman/gmql_repository/", "/user/",
        "abdulrahman", "LOCAL", ""); // when it is local that means do not consider any of the input directories except the file meta dir
      // LuceneIndex.printIndex(dir)
      val res =  search.SearchLuceneIndex(query, "abdulrahman", dir)
      if(res ==null)
        None;
      else
        Some(res);
    }
  }

}

