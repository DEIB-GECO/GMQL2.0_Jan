package it.polimi.genomics.spark.implementation

import it.polimi.genomics.core.DataStructures.RegionCondition.ChrCondition
import it.polimi.genomics.core.DataTypes._
import it.polimi.genomics.core.{GMQLLoader, DataStructures}
import it.polimi.genomics.repository.util.Utilities
import it.polimi.genomics.spark.implementation.RegionsOperators.{PredicateRD, SelectRD}
import it.polimi.genomics.spark.implementation.loaders.BedScoreParser
import org.apache.hadoop.fs.{PathFilter, Path}
import org.apache.spark.{SparkContext, SparkConf}

/**
  * Created by abdulrahman on 21/06/16.
  */
object testRDSelect {
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

    val meta_con =
      DataStructures.MetadataCondition.AND(
        DataStructures.MetadataCondition.Predicate("cell",DataStructures.MetadataCondition.META_OP.GTE, "11"),
        DataStructures.MetadataCondition.NOT(
          DataStructures.MetadataCondition.Predicate("provider", DataStructures.MetadataCondition.META_OP.NOTEQ, "UCSC")
        )
      )

    val score = DataStructures.RegionCondition.Predicate(0, DataStructures.RegionCondition.REG_OP.GT, 0.9)
    val chr = ChrCondition("chr1")
    val   reg_con = selection match {
      case "score"  => score
      case "chr" => chr
      case "and" =>  DataStructures.RegionCondition.AND(score,chr)
    }

    //        DataStructures.RegionCondition.OR(
    //          DataStructures.RegionCondition.Predicate(3, DataStructures.RegionCondition.REG_OP.GT, 30),
    //DataStructures.RegionCondition.Predicate(0, DataStructures.RegionCondition.REG_OP.EQ, "400")

    //        )

//    val selectedURIs = new java.io.File(dirInput).listFiles.filter(!_.getName.endsWith(".meta")).map(x => x.getPath)
    val fs = Utilities.getInstance().getFileSystem

    val selectedURIs =  fs.listStatus(new Path(dirInput), new PathFilter {
        override def accept(path: Path): Boolean = {
          fs.exists(new Path(path.toString+".meta"))
        }
      }).map(x=>x.getPath.toString).toList

    val optimized_reg_cond = Some(PredicateRD.optimizeConditionTree(reg_con, false, None, sc))


    val startTime= System.currentTimeMillis();
    import it.polimi.genomics.spark.implementation.loaders.Loaders._
    def parser(x: (Long, String)) = BedScoreParser.region_parser(x)
    val data = sc forPath (selectedURIs.mkString(",")) LoadRegionsCombineFiles(parser, PredicateRD.applyRegionSelect, optimized_reg_cond)
    val stopTime = System.currentTimeMillis();
    data.saveAsTextFile(dirInput+"/output.bed")
    val finalTime= System.currentTimeMillis();

    println(stopTime-startTime,finalTime-startTime)

  }
}
