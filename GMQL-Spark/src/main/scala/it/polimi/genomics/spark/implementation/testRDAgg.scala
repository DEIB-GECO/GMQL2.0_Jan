package it.polimi.genomics.spark.implementation

import it.polimi.genomics.core.{GDouble, DataStructures}
import it.polimi.genomics.core.DataStructures.RegionCondition.ChrCondition
import it.polimi.genomics.repository.util.Utilities
import it.polimi.genomics.spark.implementation.RegionsOperators.PredicateRD
import it.polimi.genomics.spark.implementation.loaders.BedScoreParser
import org.apache.hadoop.fs.{Path, PathFilter}
import org.apache.spark.{SparkConf, SparkContext}

/**
  * Created by abdulrahman on 21/06/16.
  */
object testRDAgg {
  def main(args: Array[String]) {

    val dirInput = args(0)//"/Users/abdulrahman/Desktop/datasets for SciDB testing/DS1/beds/"
    val selection = args(1)//"score"

    val conf = new SparkConf()
      .setAppName("GMQL V2 Spark")
      //    .setSparkHome("/usr/local/Cellar/spark-1.5.2/")
//      .setMaster("local[*]")
//          .setMaster("yarn-client")
      //    .set("spark.executor.memory", "1g")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryoserializer.buffer", "64")
      .set("spark.driver.allowMultipleContexts","true")
      .set("spark.sql.tungsten.enabled", "true")

    val sc:SparkContext =new SparkContext(conf)


    val fs = Utilities.getInstance().getFileSystem

    val selectedURIs =  fs.listStatus(new Path(dirInput), new PathFilter {
        override def accept(path: Path): Boolean = {
          fs.exists(new Path(path.toString+".meta"))
        }
      }).map(x=>x.getPath.toString).toList


    val startTime= System.currentTimeMillis();

    import it.polimi.genomics.spark.implementation.loaders.Loaders._
    def parser(x: (Long, String)) = BedScoreParser.region_parser(x)
    val data = sc forPath (selectedURIs.mkString(",")) LoadRegionsCombineFiles(parser)

    val stopTime = System.currentTimeMillis();

    val dd =selection match {
      case "count" => data.groupBy(x=>x._1._1).mapValues(x=>x.size)
      case "sum" => data.groupBy(x=>x._1._1).mapValues(x=>x.foldLeft(0.0)((z,a)=>z+a._2(0).asInstanceOf[GDouble].v))
      case "avg" => data.groupBy(x=>x._1._1).mapValues(x=>(x.foldLeft(0.0)((z,a)=>z+a._2(0).asInstanceOf[GDouble].v),x.size)).mapValues(x=>x._1/x._2)
    }

    dd.saveAsTextFile(dirInput+"/output")

    val finalTime= System.currentTimeMillis();

    println(stopTime-startTime,finalTime-startTime)

  }
}
