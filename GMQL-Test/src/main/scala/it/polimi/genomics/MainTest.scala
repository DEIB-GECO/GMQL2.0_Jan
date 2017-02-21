package it.polimi.genomics

import java.io.{File, PrintWriter}
import javax.management.modelmbean.XMLParseException

import it.polimi.genomics.DatasetType.DatasetType
import it.polimi.genomics.MainTest.DeltaType.DeltaType
import it.polimi.genomics.executer.{CommandLineExecuter, Executer}
import it.polimi.genomics.utils._
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer
import scala.collection.{GenSeq, Seq, mutable}
import scala.xml._


/**
  * Created by canakoglu on 1/26/17.
  */
object MainTest extends App {
  final val logger = LoggerFactory.getLogger(this.getClass)


  //TODO usage
  private final lazy val usage = ???
  private final val ALL = "ALL"
  private final val DEFAULT_CONF_FILE = "/test.xml"

  private final val SEPARATOR = "#"
  private final val INNER_SEPARATOR = "%"

  val (configFile, time, target, tag, unordered, onlyNames) = parseArgs(args)
  val configXml = XML.loadFile(configFile.getOrElse(getClass.getResource(DEFAULT_CONF_FILE).getPath))
  val execution = (configXml \ "general_config" \ "default_location_type").text
  val rootFolder = (configXml \ "general_config" \ "root_folder").find(loc => (loc \ "@type").text == execution).get.text
  val tempFolder = (configXml \ "general_config" \ "temp_folder").find(loc => (loc \ "@type").text == execution).get.text
  val databaseMap: Map[String, String] = loadDatabase
  val queries = (configXml \ "queries" \ "query")
    .filter(query => time.isEmpty || ((query \ "@time").text.toUpperCase == time.get))
    .filter(query => target.isEmpty || ((query \ "@target").text.toUpperCase == target.get))
    .filter(query => tag.isEmpty || (query \ "tags" \ "tag").map(_.text.toUpperCase).contains(tag.get))


  val prettyPrinter = new PrettyPrinter(160, 2)

  val DISABLE_RUN = true
  if (!DISABLE_RUN)
    FileUtils.deleteDirectory(new File(tempFolder))


  {
    //MAIN1
    val queryResultXml =
      queries.map { query =>
        val queryName = (query \ "name").text
        val queryDescription = (query \ "description").text
        val queryText = (query \ "text").text
        logger.info("Execution of query started: " + queryName)
        try {
          val (parsedQueryText, outDatasets) = queryReplace(queryName, queryText)

          //                val executer: Executer = SparkExecuter
          val executer: Executer = CommandLineExecuter
          //Execute
          if (!DISABLE_RUN)
            executer.execute(parsedQueryText)
          //TODO if it has error then stop next step

          val datasetXml = outDatasets.map { outDataset =>
            Row.clearNameMap
            val inSchemaFile = schemaFile(outDataset.datasetInPath)
            val outSchemaFile = schemaFile(outDataset.datasetOutPath + "/exp")

            val schemaCorrect = Schema(inSchemaFile)
            val schemaNew = Schema(outSchemaFile)

            val dsCorrect = new Dataset2(outDataset.name + "-CORRECT", schemaCorrect, outDataset.datasetInPath, outDataset.datasetInPath)
            val dsNew = new Dataset2(outDataset.name + "-NEW", schemaNew, outDataset.datasetOutPath + "/exp", outDataset.datasetOutPath + "/exp")


            //schema
            val schemaResultXml: Elem = getSchemaDelta(dsCorrect.schema, dsNew.schema)


            val datasetResult: Option[List[(String, List[(Row, DeltaType)], List[(Row, DeltaType)])]] =
              if (onlyNames) {
                if (dsCorrect hasSameSampleNames dsNew) {
                  val allDelta = (dsCorrect getSameNameSamples dsNew).map { case (sampleCorrect, sampleNew) =>
                    val sampleRegionCorrect = sampleCorrect.readFileGroupped(false, unordered)
                    val sampleRegionNew = sampleNew.readFileGroupped(false, unordered)

                    val deltaRegion = getDelta(sampleRegionCorrect, sampleRegionNew)
                    //          logger.info(deltaRegion.head.toString())

                    val sampleMetaCorrect = sampleCorrect.readFileGroupped(true, unordered)
                    val sampleMetaNew = sampleNew.readFileGroupped(true, unordered)

                    //
                    val deltaMeta = getDelta(sampleMetaCorrect, sampleMetaNew)
                    //          logger.info(deltaMeta.toString())


                    (sampleCorrect.name, deltaRegion.toList, deltaMeta.toList)
                  }
                  //        val onlyError = allDelta.filter(temp => temp._2.size + temp._3.size > 0)

                  Some(allDelta)
                }
                else {
                  logger.warn("Different sample names")
                  None
                }
              } else {

                implicit class IntersectDouble[A](seqLike: List[A]) {
                  private def occFind(sq: Seq[A]): mutable.Map[A, List[A]] = {
                    val occ = new mutable.HashMap[A, List[A]] {
                      override def default(k: A): List[A] = List.empty
                    }
                    for (y <- sq.seq) occ(y) +:= y
                    occ
                  }

                  protected[this] def newBuilder: mutable.Builder[(A, A), ListBuffer[(A, A)]] = ListBuffer.newBuilder[(A, A)]

                  def intersectDouble(that: GenSeq[A]): List[(A, A)] = {
                    val occ = occFind(that.seq)
                    val b = newBuilder
                    for (x: A <- seqLike.seq) {
                      if (occ(x).nonEmpty) {
                        val c: A = occ(x).head
                        b += ((x, c))
                        occ(x) = occ(x).tail
                      }
                    }
                    b.result.toList
                  }
                }

                val intersectSamples: List[(Sample2, Sample2)] = dsCorrect.sampleList intersectDouble dsNew.sampleList
                var correctSamples = {
                  val nameSet = intersectSamples.map(_._1.name).toSet
                  dsCorrect.sampleList.filterNot(sample => nameSet.contains(sample.name))
                }
                var newSamples = {
                  val nameSet = intersectSamples.map(_._2.name).toSet
                  dsNew.sampleList.filterNot(sample => nameSet.contains(sample.name))
                }

                var result = List.empty[(String, List[(Row, DeltaType)], List[(Row, DeltaType)])]
                result ++= intersectSamples.map { case (sampleCorrect, sampleNew) => (s"${sampleCorrect.name}~${sampleNew.name}", List.empty[(Row, DeltaType)], List.empty[(Row, DeltaType)]) }

                val matrix = correctSamples.toArray map { first =>
                  newSamples.toArray map { second =>
                    first.similarity(second)
                  }
                }
                //              val DEBUG = true
                //              if (DEBUG) {
                //                println("-" * 20 + "INPUT" + "-" * 20)
                //                println(matrix.map(_.map(_.formatted("%3.3f")).mkString(", ")).mkString("\n"))
                //                println("-" * 18 + "END INPUT" + "-" * 18)
                //              }

                if (correctSamples.nonEmpty && newSamples.nonEmpty) {
                  val mapped = if (correctSamples.size <= newSamples.size) {
                    val h = Hungarian(matrix)
                    println("Hungarian value" + h.solve())
                    correctSamples.zip(newSamples.zip(h.yx).filterNot(_._2 < 0).sortBy(_._2).map(_._1))
                  } else {
                    val h = Hungarian(matrix.transpose)
                    h.solve()
                    correctSamples.zip(h.yx).filterNot(_._2 < 0).sortBy(_._2).map(_._1).zip(newSamples)
                  }

                  def getDiff(correctList: List[Row], newList: List[Row]): List[(Row, DeltaType.Value)] = {
                    (correctList diff newList).map((_, DeltaType.DELETED)) ++
                      (newList diff correctList).map((_, DeltaType.ADDED))
                  }

                  result ++= mapped.map { case (sampleCorrect, sampleNew) =>
                    (s"${sampleCorrect.name}~${sampleNew.name}-SIM:${sampleCorrect.similarity(sampleNew)}", getDiff(sampleCorrect.regionList, sampleNew.regionList), getDiff(sampleCorrect.metaList, sampleNew.metaList))
                  }


                  correctSamples = {
                    val nameSet = mapped.map(_._1.name).toSet
                    correctSamples.filterNot(sample => nameSet.contains(sample.name))
                  }
                  newSamples = {
                    val nameSet = mapped.map(_._2.name).toSet
                    newSamples.filterNot(sample => nameSet.contains(sample.name))
                  }

                  result ++= correctSamples.map { sampleCorrect =>
                    (s"${sampleCorrect.name}~NO_CORRESPONDING", getDiff(sampleCorrect.regionList, List.empty), getDiff(sampleCorrect.metaList, List.empty))
                  }

                  result ++= newSamples.map { sampleNew =>
                    (s"NO_CORRESPONDING~${sampleNew.name}", getDiff(List.empty, sampleNew.regionList), getDiff(List.empty, sampleNew.metaList))
                  }

                }

                Some(result)
              }


            val sampleResultXml = getSampleXml(datasetResult)

            //print foreach file
            val datasetXml = <dataset name={outDataset.name}>
              {schemaResultXml}{sampleResultXml}
            </dataset>
            val prettyXml = prettyPrinter.format(datasetXml)
            val file = new File(rootFolder + s"/report/$queryName-${outDataset.name}.xml")
            file.getParentFile.mkdirs()
            new PrintWriter(file) {
              write(prettyXml)
              close()
            }

            val sampleResultLimitedXml = getSampleXml(datasetResult, 10)
            <dataset name={outDataset.name}>
              {schemaResultXml}{sampleResultLimitedXml}
            </dataset>
          }
          <query query_name={queryName}>
            <query_description>
              {queryDescription}
            </query_description>
            <query_text>
              {queryText}
            </query_text>
            <parsed_text>
              {parsedQueryText}
            </parsed_text>{datasetXml}
          </query> //

        } catch {
          case e: NoSuchElementException =>
            <query query_name={queryName}>
              <query_description>
                {queryDescription}
              </query_description>
              <query_text>
                {queryText}
              </query_text>
              <error>please check the dataset names in the configuration -
                {e.getMessage}
              </error>
            </query> //
        }
      }
    val file = new File(rootFolder + s"/all_report.xml")
    file.getParentFile.mkdirs()
    val queriesXml = <queries>
      {queryResultXml}
    </queries>
    val prettyXml = prettyPrinter.format(queriesXml)

    new PrintWriter(file) {
      write(prettyXml)
      close()
    }

  } //MAIN1


  def getSampleXml(allDeltaOption: Option[List[(String, List[(Row, DeltaType)], List[(Row, DeltaType)])]], limitResult: Int = Int.MaxValue) = {
    if (allDeltaOption.isDefined) {
      val allDelta = allDeltaOption.get

      val (sampleListToPrint, errorCount) =
        if (limitResult < Int.MaxValue) {
          val errorSamples = allDelta.filter(temp => temp._2.size + temp._3.size > 0)
          (errorSamples, errorSamples.size)
        } else {
          (allDelta, allDelta.count(temp => temp._2.size + temp._3.size > 0))
        }

      if (sampleListToPrint.nonEmpty)
        <samples total_samples={allDelta.size.toString} error_samples={errorCount.toString}>
          {sampleListToPrint.take(limitResult).map { case (sampleName, deltaRegion, deltaMeta) => getDiffXml2(sampleName, deltaRegion, deltaMeta, limitResult) }}
        </samples>
      else
        <samples total_samples={allDelta.size.toString}>OK: samples are equal</samples>
    }
    else
      <error>Dataset sample names are not equal, please check and run again</error>
  }

  def getDiffXml2(sampleName: String, deltaRegion: Seq[(Row, DeltaType)], deltaMeta: Seq[(Row, DeltaType)], limitResult: Int = Int.MaxValue) = {
    <sample name={sampleName} diff_count={(deltaRegion.size + deltaMeta.size).toString}>
      {if (deltaRegion.nonEmpty) {
      val deletedRegion = deltaRegion.filter(_._2 == DeltaType.DELETED).map(_._1)
      val addedRegion = deltaRegion.filter(_._2 == DeltaType.ADDED).map(_._1)
      <region diff_count={deltaRegion.size.toString} deleted_count={deletedRegion.size.toString} added_count={addedRegion.size.toString}>
        {if (deletedRegion.nonEmpty) <deleted diff_count={deletedRegion.size.toString}>
        {deletedRegion.take(limitResult).map(in => <row line_number={in.lineNumber.getOrElse("").toString}>
          {in.line}
        </row>)}
      </deleted>}{if (addedRegion.nonEmpty) <added diff_count={addedRegion.size.toString}>
        {addedRegion.take(limitResult).map(in => <row line_number={in.lineNumber.getOrElse("").toString}>
          {in.line}
        </row>)}
      </added>}
      </region> //
    }}{if (deltaMeta.nonEmpty) {
      val deletedMeta = deltaMeta.filter(_._2 == DeltaType.DELETED).map(_._1)
      val addedRegion = deltaMeta.filter(_._2 == DeltaType.ADDED).map(_._1)
      <meta diff_count={deltaMeta.size.toString} deleted_count={deletedMeta.size.toString} added_count={addedRegion.size.toString}>
        {if (deletedMeta.nonEmpty) <deleted diff_count={deletedMeta.size.toString}>
        {deletedMeta.take(limitResult).map(in => <row line_number={in.lineNumber.getOrElse("").toString}>
          {in.line}
        </row>)}
      </deleted>}{if (addedRegion.nonEmpty) <added diff_count={addedRegion.size.toString}>
        {addedRegion.take(limitResult).map(in => <row line_number={in.lineNumber.getOrElse("").toString}>
          {in.line}
        </row>)}
      </added>}
      </meta> //
    }}
    </sample>
  }

  object DeltaType extends Enumeration {
    type DeltaType = Value

    val DELETED, ADDED = Value
  }

  def getSchemaDelta(dsCorrectSchema: Schema, dsNewSchema: Schema) = {
    if (dsCorrectSchema == dsNewSchema)
      <schema>OK: schemas are equal</schema>
    else
      <schema>
        {//
        if (dsCorrectSchema.schemaType != dsNewSchema.schemaType)
          <type_error>
            CORRECT:
            {dsCorrectSchema.schemaType}
            NEW:
            {dsNewSchema.schemaType}
          </type_error>}{//
        if (dsCorrectSchema.columns != dsNewSchema.columns) {
          if (dsCorrectSchema.columns.toSet == dsNewSchema.columns.toSet)
            <column_warning>Order has been changed</column_warning>
          else
            <column_error>Colums are not equal</column_error>
        }}

      </schema>
  }


  def getDelta(sampleCorrectIteratorTemp: Iterator[List[Row]], sampleNewIteratorTemp: Iterator[List[Row]]) = {
    val sampleCorrectIteratorBuffered: BufferedIterator[List[Row]] = sampleCorrectIteratorTemp.buffered
    val sampleNewIteratorBuffered = sampleNewIteratorTemp.buffered
    val changed = ListBuffer.empty[(Row, DeltaType)]

    //TODO remove
    //    val deleted = ListBuffer.empty[Row]
    //    val added = ListBuffer.empty[Row]

    while (sampleCorrectIteratorBuffered.hasNext && sampleNewIteratorBuffered.hasNext) {
      val currentCorrectList = sampleCorrectIteratorBuffered.head
      val currentNewList = sampleNewIteratorBuffered.head

      currentCorrectList.head compare currentNewList.head match {
        case 0 =>
          val deletedList = currentCorrectList diff currentNewList
          val addedList = currentNewList diff currentCorrectList
          sampleCorrectIteratorBuffered.next()
          sampleNewIteratorBuffered.next()
          changed ++= deletedList.map((_, DeltaType.DELETED))
          changed ++= addedList.map((_, DeltaType.ADDED))

        //          deleted ++= deletedList
        //          added ++= addedList
        case x if x < 0 =>
          val deletedList = currentCorrectList
          sampleCorrectIteratorBuffered.next()
          changed ++= deletedList.map((_, DeltaType.DELETED))

        //          deleted ++= deletedList
        case x if x > 0 =>
          val addedList = currentNewList
          sampleNewIteratorBuffered.next()
          changed ++= addedList.map((_, DeltaType.ADDED))

        //          added ++= addedList
      }
    }
    //    deleted ++= sampleCorrectIteratorBuffered.flatten
    //    added ++= sampleNewIteratorBuffered.flatten

    changed ++= sampleCorrectIteratorBuffered.flatten.map((_, DeltaType.DELETED))
    changed ++= sampleNewIteratorBuffered.flatten.map((_, DeltaType.ADDED))

    changed
  }


  //  // contains all the queries
  //  val queriesInnerXml =
  //  //for each query with the correct tag or all if the tag is not selected
  //    for (query <- queries if tag.isEmpty || (query \ "tags" \ "tag").map(_.text.toUpperCase).contains(tag))
  //      yield {
  //        val queryName = (query \ "name").text
  //        val queryText = (query \ "text").text
  //        logger.info("queryName: " + queryName)
  //        logger.info("queryText: " + queryText)
  //        val (parsedQueryText, outDatasets) = queryReplace(queryName, queryText)
  //        logger.info(parsedQueryText)
  //        logger.info(outDatasets.toString())
  //        if (!DISABLE_RUN)
  //          SparkExecuter.execute(parsedQueryText)
  //        else {
  //          val queryInnerXml =
  //            for ((datasetName, datasetInPath, datasetOutPath) <- outDatasets) yield {
  //              var inSchemaFile = schemaFile(datasetInPath)
  //              var outSchemaFile = schemaFile(datasetOutPath)
  //
  //              //          if (!(!inSchemaFile.isEmpty && inSchemaFile.get.exists && !outSchemaFile.isEmpty && outSchemaFile.get.exists)) {
  //              //            inSchemaFile = None
  //              //            outSchemaFile = None
  //              //          }
  //
  //              val datasetIn = Dataset(s"queryName: ${queryName}-CORRECT", Schema(inSchemaFile), datasetInPath, datasetInPath)
  //              val datasetOut = Dataset(s"queryName: ${queryName}-OUT", Schema(outSchemaFile), datasetOutPath + "/exp", datasetOutPath + "meta")
  //              println("hasSameSampleNames: " + datasetIn.hasSameSampleNames(datasetOut))
  //
  //              val schemaInnerXml =
  //                <schema>{
  //                  if (datasetIn.schema == datasetOut.schema)
  //                    Text("OK: schemas are equal")
  //                  else {
  //                    if (datasetIn.schema.schemaType != datasetOut.schema.schemaType)
  //                      <type_error>CORRECT:{datasetIn.schema.schemaType} OUT:{datasetOut.schema.schemaType}</type_error>
  //                    if (datasetIn.schema.columns != datasetOut.schema.columns) {
  //                      if (datasetIn.schema.columns.toSet == datasetOut.schema.columns.toSet)
  //                        <column_warning>Order has been changed</column_warning>
  //                      else
  //                        <column_error>Colums are not equal</column_error>
  //                    }
  //                  }
  //                }</schema> //
  //
  //              val datasetXml = {
  //                val innerXml = if (datasetIn.hasSameSampleNames(datasetOut)) {
  //                  val matchedSamples = datasetIn.getSameNameSamples(datasetOut)
  //                  matchedSamples.map { case (in, out) =>
  //                    //TODO can be optimized, not to call two times
  //                    val A = System.nanoTime()
  //                    logger.debug("A: " + A / 1000000.0)
  //                    val diffXmlBig = getDiffXml(in, out) % Attribute(None, "out_sample_name", Text(out.name), Attribute(None, "source_sample_name", Text(in.name), Null))
  //                    val B = System.nanoTime()
  //                    logger.debug("B: " + B / 1000000.0)
  //                    logger.debug("B-A: " + (B - A) / 1000000.0)
  //                    val diffXmlSmall = getDiffXml(in, out, true) % Attribute(None, "out_sample_name", Text(out.name), Attribute(None, "source_sample_name", Text(in.name), Null))
  //                    val C = System.nanoTime()
  //                    logger.debug("C: " + C / 1000000.0)
  //                    logger.debug("C-B: " + (C - B) / 1000000.0)
  //                    (diffXmlBig, diffXmlSmall)
  //                  }
  //                }
  //                else {
  //                  Seq((<error>Dataset sample names are not equal, please check and run again</error>, <error>Dataset sample names are not equal, please check and run again</error>))
  //                }
  //                //dataset tuple2, one for result file the other one for each dataset
  //                ( <dataset name={datasetName}>{schemaInnerXml}{innerXml.map(_._1)}</dataset>,
  //                  <dataset name={datasetName}>{schemaInnerXml}{innerXml.map(_._2)}</dataset>)
  //                //
  //
  //              }
  //              val prettyXml = prettyPrinter.format(datasetXml._1)
  //              val file = new File(rootFolder + s"/report/${queryName}-${datasetName}.xml")
  //              file.getParentFile().mkdirs()
  //              new PrintWriter(file) {
  //                write(prettyXml);
  //                close
  //              }
  //              datasetXml._2
  //            }
  //      <query query_name={queryName}>
  //        <query_text>{queryText}</query_text>
  //        <parsed_text>{parsedQueryText}</parsed_text>
  //        {queryInnerXml}
  //      </query>//
  //        }
  //      }
  //
  //  val file = new File(rootFolder + s"/all_report.xml")
  //  file.getParentFile().mkdirs()
  //  val queriesXml = <queries>{queriesInnerXml}</queries>
  //  val prettyXml = prettyPrinter.format(queriesXml)
  //
  //  new PrintWriter(file) {
  //    write(prettyXml);
  //    close
  //  }


  //  def getDiffXml(sampleBase: Sample, sampleTest: Sample, firstTen: Boolean = false) = {
  //    //    if (sampleBase == sampleTest)
  //    //          <sample diff_count="0"/>
  //    //    else {
  //    val deletedSample = sampleBase - sampleTest
  //    val addedSample = sampleTest - sampleBase
  //    <sample diff_count={(deletedSample.regionSet.size + addedSample.regionSet.size + deletedSample.metaSet.size + addedSample.metaSet.size).toString}>
  //      {if (!deletedSample.regionSet.isEmpty || !addedSample.regionSet.isEmpty) {
  //      <region diff_count={(deletedSample.regionSet.size + addedSample.regionSet.size).toString}>
  //        {if (!deletedSample.regionSet.isEmpty) <deleted diff_count={deletedSample.regionSet.size.toString}>{deletedSample.regionSet.toList.sorted.take(if (firstTen) 10 else Int.MaxValue).map(in => <row>{in.line}</row>)}</deleted>}
  //        {if (!addedSample.regionSet.isEmpty)   <added diff_count={addedSample.regionSet.size.toString}>{addedSample.regionSet.toList.sorted.take(if (firstTen) 10 else Int.MaxValue).map(in => <row>{in.line}</row>)}</added>}
  //      </region>}}
  //      {if (!deletedSample.metaSet.isEmpty || !addedSample.metaSet.isEmpty) {
  //       <meta diff_count={(deletedSample.metaSet.size + addedSample.metaSet.size).toString}>
  //         {if (!deletedSample.metaSet.isEmpty)  <deleted diff_count={deletedSample.metaSet.size.toString}>{deletedSample.metaSet.toList.sorted.take(if (firstTen) 10 else Int.MaxValue).map(in => <row>{in.line}</row>)}</deleted>}
  //         {if (!addedSample.metaSet.isEmpty)    <added diff_count={addedSample.metaSet.size.toString}>{addedSample.metaSet.toList.sorted.take(if (firstTen) 10 else Int.MaxValue).map(in => <row>{in.line}</row>)}</added>}
  //       </meta>}}
  //    </sample>
  ////    }
  //  }

  //TODO TRY CATCH
  def schemaFile(directoryPath: String) = new File(directoryPath).listFiles().filter(_.isFile).find(_.getName.endsWith(".schema"))


  case class OutDataset(val name: String, val datasetInPath: String, val datasetOutPath: String)


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
    var unordered = false
    var onlyNames = true

    //    for (i <- 0 until args.length if (i % 2 == 0)) {
    var i = 0;
    while (i < args.length) {
      if ("-h".equals(args(i)) || "-help".equals(args(i))) {
        println(usage)
        sys.exit()
      } else if ("-time".equals(args(i))) {
        i += 1
        time = Some(args(i).toUpperCase())
        logger.info(s"Time is set to: ${time.get}")
      } else if ("-target".equals(args(i))) {
        i += 1
        target = Some(args(i).toUpperCase())
        logger.info(s"Target is set to: ${target.get}")
      } else if ("-tag".equals(args(i))) {
        i += 1
        tag = Some(args(i).toUpperCase())
        logger.info(s"Tag is set to: ${tag.get}")
      } else if ("-conf".equals(args(i))) {
        i += 1
        if (new java.io.File(args(i)).exists()) {
          configFile = Some(args(i))
          logger.info(s"Input File set to: ${configFile}")
        } else {
          logger.error("GMQL input script path is not valid")
          sys.exit()
        }
      } else if ("-unordered".equals(args(i))) {
        unordered = true
        logger.info(s"unordered is set to: ${unordered}")
      } else if ("-onlynames".equals(args(i))) {
        onlyNames = false
        logger.info(s"onlynames is set to: ${onlyNames}")
      }
      i += 1
    }
    (configFile, time, target, tag, unordered, onlyNames)
  }


}

object DatasetType extends Enumeration {
  type DatasetType = Value
  val IN, OUT = Value
}
