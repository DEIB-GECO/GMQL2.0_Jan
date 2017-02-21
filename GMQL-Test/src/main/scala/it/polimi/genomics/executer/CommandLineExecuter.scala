package it.polimi.genomics.executer

import org.slf4j.LoggerFactory

/**
  * Created by canakoglu on 2/15/17.
  */
object CommandLineExecuter extends Executer {
  final val logger = LoggerFactory.getLogger(this.getClass)

  override def execute(query: String): Boolean = {
    import sys.process._
    //        val exitCode = Seq("/home/canakoglu/datasetRepository/GMQL/bin/GMQL-Submit", "-script", query) !
    //
    //    println("exitCode: " + exitCode)

    val process = Process("/home/canakoglu/datasetRepository/GMQL/bin/GMQL-Submit", Seq("-script", query))
    val processLines = process.lines
    var hasError = false
    processLines.foreach { t =>
      if (t.contains("ERROR")) hasError = true;
      logger.info(t)
    }

    if (hasError){
      logger.error("In the output of execution, there is error, please check it")

    }
    hasError
  }
}


object TestCommandLineExecuter extends App {
  CommandLineExecuter.execute("R = SELECT() /home/canakoglu/datasetRepository//datasets/beds_in/; \nMATERIALIZE R into /tmp/gmql_test_arif//select_test2/beds_out3/;")

}