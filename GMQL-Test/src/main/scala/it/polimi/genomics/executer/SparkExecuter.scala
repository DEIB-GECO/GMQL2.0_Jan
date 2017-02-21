package it.polimi.genomics.executer

import java.io.File

import it.polimi.genomics.GMQLServer.GmqlServer
import it.polimi.genomics.compiler.{MaterializeOperator, Translator}
import it.polimi.genomics.repository.util.Utilities
import it.polimi.genomics.spark.implementation.GMQLSparkExecutor
import org.apache.spark.util.Utils
import org.apache.spark.{SparkConf, SparkContext}

/**
  * Created by canakoglu on 1/26/17.
  */
object SparkExecuter extends Executer {
  val conf = new SparkConf()
    .setAppName("GMQL V2 Spark")
    //    .setSparkHome("/usr/local/Cellar/spark-1.5.2/")
    .setMaster("local[*]")
    //    .setMaster("yarn-client")
    //    .set("spark.executor.memory", "1g")
    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .set("spark.kryoserializer.buffer", "64")
    .set("spark.driver.allowMultipleContexts", "true")
    .set("spark.sql.tungsten.enabled", "true")
//    .set("akka.remote.log-received-messages", "false")
//    .set("akka.remote.log-sent-messages", "false")
//    .set("akka.remote.untrusted-mode", "false")
  conf.getAll.foreach(println)


  var sc: SparkContext = null
  var maxdis = 5000

  override def execute(query_spark: String): Boolean = {
    Utilities.getInstance.MODE = "LOCAL"
    val bin_size: Int = 10
    val spark_output_couple = {
      try {
        sc = new SparkContext(conf)
        val spark_server = new GmqlServer(new GMQLSparkExecutor(testingIOFormats = false, maxBinDistance = maxdis, sc = sc),
          binning_size = Some(bin_size))
        val spark_translator = new Translator(spark_server, System.getProperty("java.io.tmpdir") + File.separator + "gmql_testing" + File.separator + "OUTS" + File.separator)
        val dd = spark_translator.phase1(query_spark)

        //extract the outpath
        val outputs = dd.flatMap(x => x match {
          case d: MaterializeOperator =>
            Some(d.store_path)
          case _ => None
        })

        spark_translator.phase2(dd)
        val spark_start: Long = System.currentTimeMillis
        spark_server.run()
        val spark_stop: Long = System.currentTimeMillis


        (true, Some(spark_stop - spark_start))
      } catch {
        case e: Exception => e.printStackTrace(); //logger.error(e.getMessage)
          (false, None)
      } finally {
        sc.stop()
      }

    }
    true
  }


}
