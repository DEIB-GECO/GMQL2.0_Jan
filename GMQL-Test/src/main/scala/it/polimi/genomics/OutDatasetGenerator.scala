package it.polimi.genomics

import java.io.File
import javax.management.modelmbean.XMLParseException

import grizzled.slf4j.Logging
import it.polimi.genomics.DatasetType._
import it.polimi.genomics.MainTest.OutDataset
import it.polimi.genomics.executer.{Executer, SparkExecuter}
import org.apache.commons.io.FileUtils

import scala.collection.mutable
import scala.xml._


/**
  * Created by canakoglu on 1/26/17.
  */
object OutDatasetGenerator extends App with Logging {


  //TODO usage
  private final lazy val usage = ???
  private final val ALL = "ALL"
  private final val DEFAULT_CONF_FILE = "/test.xml"

  private final val SEPARATOR = "#"
  private final val INNER_SEPARATOR = "%"

  val (configFile, time, target, tag, deleteFiles) = parseArgs(args)
  val configXml = XML.loadFile(configFile.getOrElse(getClass.getResource(DEFAULT_CONF_FILE).getPath))
  val execution = (configXml \ "general_config" \ "default_location_type").text
  val rootFolder = (configXml \ "general_config" \ "root_folder").find(loc => (loc \ "@type").text == execution).get.text
  val tempFolder = (configXml \ "general_config" \ "temp_folder").find(loc => (loc \ "@type").text == execution).get.text
  val databaseMap: Map[String, String] = loadDatabase
  val queries = (configXml \ "queries" \ "query")
    .filter(query => time.isEmpty || ((query \ "@time").text.toUpperCase == time.get))
    .filter(query => target.isEmpty || ((query \ "@target").text.toUpperCase == target.get))
    .filter(query => tag.isEmpty || (query \ "tags" \ "tag").map(_.text.toUpperCase).contains(tag.get))

  FileUtils.deleteDirectory(new File(tempFolder))

  val executer: Executer = SparkExecuter

  val createdDs = mutable.Set[String]()
  queries.foreach { query =>
    val queryName = (query \ "@name").text
    val queryDescription = (query \ "description").text
    val queryText = (query \ "text").text
    info("Checking query: " + queryName)
    try {
      val (parsedQueryText, outDatasets) = queryReplace(queryName, queryText)
      var executed = false

      for (dataset <- outDatasets) {
        val name = dataset.name
        val copyFrom = dataset.datasetOutPath
        val copyTo = dataset.datasetInPath
        //
        if (createdDs(name)) {
          info("Dataset has been created in this run")
        } else if (!deleteFiles && new File(copyTo).exists()) {
          info("File has been created in the previous run")
        } else {
          if (deleteFiles && new File(copyTo).exists()) {
            FileUtils.deleteDirectory(new File(copyTo))
          }

          if (!executed) {
            info("Execution of query started: " + queryName)
            executer.execute(parsedQueryText)
            executed = true
          }

          import scala.collection.JavaConversions._
          FileUtils.listFiles(new File(copyFrom), Array("crc"), true).foreach(FileUtils.deleteQuietly)
          //move directory
          FileUtils.deleteDirectory(new File(copyTo))
          FileUtils.moveDirectory(new File(copyFrom + "/exp"), new File(copyTo))
          FileUtils.moveFileToDirectory(new File(copyFrom + "/test.schema"), new File(copyTo), false)
        }
      }
    } catch {
      case e: Exception => error("Error", e)
    }
  }
  org.apache.hadoop.fs.FileUtil.fullyDelete(new File(tempFolder))

  /**
    *
    * @param queryName
    * @param query
    * @return tuple where the first is query, second is out dataset list as tuple3. Tuple3: database name, in location, out location
    */
  def queryReplace(queryName: String, query: String) = {
    val queryList = query.split(SEPARATOR, -1).toList
    val n = queryList.size
    if (n % 2 != 1) //should be odd
      throw new Exception(s"Query error, please check the number of ${
        SEPARATOR
      }")
    val inputPattern = s"INPUT${
      INNER_SEPARATOR
    }(.*)".r
    val outputPattern = s"OUTPUT${
      INNER_SEPARATOR
    }(.*)".r
    val datasetOutSet = mutable.Set.empty[String]
    //result
    val (resultQuery, resultDatasetLocations) = (queryList grouped 2).map {
      group =>
        group match {
          case first :: Nil => (first, None) // at the end of the query (there are 2n + 1 elements)
          case first :: second :: Nil => //first is the part before the separator and second is the one between the separator
            second match {
              case inputPattern(databaseName) => (first + getLocation(queryName, DatasetType.IN, databaseName), None) //if it is INPUT{sep}XX then name of the dataset is XX
              case outputPattern(databaseName) => //if it is OUTPUT{INNER_SEPARATOR}XX then name of the dataset is XX
                //find the next available out database name
                val tempDatabaseName = {
                  var tempDatabaseName = databaseName
                  var i = 0
                  while (datasetOutSet.contains(tempDatabaseName))
                    tempDatabaseName = databaseName + "_" + {
                      i += 1;
                      i
                    }
                  datasetOutSet += tempDatabaseName
                  tempDatabaseName
                }
                val inLocation = getLocation(queryName, DatasetType.IN, databaseName)
                val outLocation = getLocation(queryName, DatasetType.OUT, databaseName, Some(tempDatabaseName))
                (first + outLocation, Some(OutDataset(tempDatabaseName, inLocation, outLocation)))
              case _ => throw new Exception(s"Query part(${second}) error, please check the definition of variable between the ${SEPARATOR}")
            }
          case _ => ("", None) //NO WAY but for maven warning added this line
        }
    }.toList.unzip
    (resultQuery.mkString(""), resultDatasetLocations.flatten)
  }


  def getLocation(queryName: String, dsType: DatasetType, databaseName: String, databaseAlies: Option[String] = None): String = {
    //MAP CHECK
    dsType match {
      case DatasetType.IN => s"$rootFolder/${
        databaseMap(databaseName)
      }/"
      case DatasetType.OUT =>
        if (!databaseMap.contains(databaseName))
          throw new Exception("Output DS is not available")
        s"$tempFolder/$queryName/${
          databaseAlies.getOrElse(databaseName)
        }/"
    }
  }


  def loadDatabase = {
    (configXml \ "datasets" \ "dataset").flatMap {
      dataset =>
        val name = (dataset \ "@name").text
        val location = (dataset \ "location").find(loc => (loc \ "@type").text == execution)
        if (name isEmpty)
          throw new XMLParseException("Dataset without name!!")
        //      logger.info("name: " + name)
        //      logger.info("location: " + location.get.toString)
        location match {
          case Some(loc) => Some(name, loc.text)
          case None => None
        }
    }.toMap
  }

  //TODO correct args
  def parseArgs(args: Array[String]) = {
    var configFile: Option[String] = None
    var time: Option[String] = None
    var target: Option[String] = None
    var tag: Option[String] = None
    var deleteFiles = false

    //    for (i <- 0 until args.length if (i % 2 == 0)) {
    var i = 0;
    while (i < args.length) {
      if ("-h".equals(args(i)) || "-help".equals(args(i))) {
        println(usage)
        sys.exit()
      } else if ("-time".equals(args(i))) {
        i += 1
        time = Some(args(i).toUpperCase())
        info(s"Time is set to: ${time.get}")
      } else if ("-target".equals(args(i))) {
        i += 1
        target = Some(args(i).toUpperCase())
        info(s"Target is set to: ${target.get}")
      } else if ("-tag".equals(args(i))) {
        i += 1
        tag = Some(args(i).toUpperCase())
        info(s"Tag is set to: ${tag.get}")
      } else if ("-conf".equals(args(i))) {
        i += 1
        if (new java.io.File(args(i)).exists()) {
          configFile = Some(args(i))
          info(s"Input File set to: ${configFile}")
        } else {
          error("GMQL input script path is not valid")
          sys.exit()
        }
        } else if ("-recreate".equals(args(i))) {
        deleteFiles = true
        info(s"recreate is set to: ${deleteFiles}")
      }
      i += 1
    }
    (configFile, time, target, tag, deleteFiles)
  }


}