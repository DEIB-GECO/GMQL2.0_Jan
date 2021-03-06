package it.polimi.genomics.importer.ENCODEImporter

import java.io.File
import java.net.URL

import com.google.common.hash.Hashing
import com.google.common.io.Files
import it.polimi.genomics.importer.FileDatabase.{FileDatabase,STAGE}
import it.polimi.genomics.importer.GMQLImporter.{GMQLDataset, GMQLDownloader, GMQLSource}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.language.postfixOps
import scala.sys.process._
import scala.xml.{Elem, XML}

/**
  * Created by Nacho on 10/13/16.
  */
class ENCODEDownloader extends GMQLDownloader {
  val logger = LoggerFactory.getLogger(this.getClass)

  /**
    * downloads the files from the source defined in the loader
    * into the folder defined in the loader
    * recursively checks all folders and subfolders matching with the regular expressions defined in the loader
    *
    * @param source contains specific download and sorting info.
    */
  override def download(source: GMQLSource): Unit = {
    logger.info("Starting download for: " + source.name)
    if (!new java.io.File(source.outputFolder).exists) {
      logger.debug("file " + source.outputFolder + " created")
      new java.io.File(source.outputFolder).mkdirs()
    }
    downloadIndexAndMeta(source)
  }

  /**
    * For ENCODE given the parameters, a link for downloading metadata and
    * file index is generated, here, that file is downloaded and then, for everyone
    * downloads all the files linked by it.
    *
    * @param source information needed for downloading ENCODE datasets.
    */
  private def downloadIndexAndMeta(source: GMQLSource): Unit = {
    source.datasets.foreach(dataset => {
      if(dataset.downloadEnabled) {
        val datasetId = FileDatabase.datasetId(FileDatabase.sourceId(source.name),dataset.name)
        val stage = STAGE.DOWNLOAD
        val outputPath = source.outputFolder + File.separator + dataset.outputFolder + File.separator + "Downloads"
        if (!new java.io.File(outputPath).exists) {
          new java.io.File(outputPath).mkdirs()
        }
        val indexAndMetaUrl = generateDownloadIndexAndMetaUrl(source, dataset)
        //ENCODE always provides the last version of this .meta file.
        if (urlExists(indexAndMetaUrl)) {
          /*I will check all the server files against the local ones so i mark as to compare,
            * the files will change their state while I check each one of them. If there is
            * a file deleted from the server will be marked as OUTDATED before saving the table back*/
          FileDatabase.markToCompare(datasetId,stage)

          val metadataCandidateName = "metadata.tsv"
          downloadFileFromURL(
            indexAndMetaUrl,
            outputPath + File.separator + metadataCandidateName)

          val file = new File(outputPath + File.separator + metadataCandidateName)
          if(file.exists()) {
            val fileId = FileDatabase.fileId(datasetId,indexAndMetaUrl,stage,metadataCandidateName)
            val metadataName = FileDatabase.getFileNameAndCopyNumber(fileId)

            val hash = Files.hash(file,Hashing.md5()).toString

            FileDatabase.checkIfUpdateFile(fileId,hash,file.getTotalSpace.toString,DateTime.now.toString)

            FileDatabase.markAsUpdated(fileId,file.getTotalSpace.toString)

            downloadFilesFromMetadataFile(source, dataset)
            logger.info("download for " + dataset.outputFolder + " completed")
          }
          else
            logger.warn("couldn't download metadata.tsv file")
        }
        else {
          logger.error("download link generated by " + dataset.outputFolder + " does not exist")
          logger.debug("download link:" + indexAndMetaUrl)
        }
      }
    })
  }

  /**
    * generates download link for the metadata file
    * generates download link for the metadata file
    *
    * @param source  contains information related for connecting to ENCODE
    * @param dataset contains information for parameters of the url
    * @return full url to download metadata file from encode.
    */
  def generateDownloadIndexAndMetaUrl(source: GMQLSource, dataset: GMQLDataset): String = {
    source.url + source.parameters.filter(_._1.equalsIgnoreCase("metadata_prefix")).head._2 + generateParameterSet(dataset) + source.parameters.filter(_._1 == "metadata_suffix").head._2
  }
  /*def generateReportUrl(source: GMQLSource, dataset: GMQLDataset): String ={
    source.url + source.parameters.filter(_._1.equalsIgnoreCase("report_prefix")).head._2 + generateParameterSet(dataset) + "&" + generateFieldSet(source)
  }*/
  def generateFieldSet(source: GMQLSource): String ={
    val file: Elem = XML.loadFile(source.parameters.filter(_._1.equalsIgnoreCase("encode_metadata_configuration")).head._2)
    var set = ""
    ((file\\"encode_metadata_config"\"parameter_list").filter(list =>
      (list\"@name").text.equalsIgnoreCase("encode_report_tsv"))\"parameter").filter(field =>
      (field\"@include").text.equalsIgnoreCase("true") && (field\"key").text.equalsIgnoreCase("field")).foreach(field =>{
      set = set + (field\\"key").text + "=" + (field\\"value").text+"&"
    })
    if (set.endsWith("&"))
      set.substring(0, set.length - 1)
    else
      set
  }
  /**
    * concatenates all the folder's parameters with & in between them
    * and = inside them
    *
    * @param dataset contains all the parameters information
    * @return string with parameter=value & ....
    */
  private def generateParameterSet(dataset: GMQLDataset): String = {
    var set = ""
    dataset.parameters.foreach(parameter => {
      set = set + parameter._1 + "=" + parameter._2 + "&"
    })
    if (set.endsWith("&"))
      set.substring(0, set.length - 1)
    else
      set
  }

  /**
    * given a url and destination path, downloads that file into the path
    *
    * @param url  source file url.
    * @param path destination file path and name.
    */
  def downloadFileFromURL(url: String, path: String): Unit = {
    try {
      new URL(url) #> new File(path) !!;
      logger.info("Downloading: " + path + " from: " + url + " DONE")
    }
    catch{
      case e: Throwable => logger.error("Downloading: " + path + " from: " + url + " failed: ")
    }
  }

  /**
    * explores the downloaded metadata file with all the urls directing to the files to download,
    * checks if the files have to be updated, downloaded, deleted and performs the actions needed.
    * puts all downloaded files into /information.outputFolder/folder.outputFolder/Downloads
    *
    * @param source  contains information for ENCODE download.
    * @param dataset dataset specific information about its location.
    */
  private def downloadFilesFromMetadataFile(source: GMQLSource, dataset: GMQLDataset): Unit = {
    //attributes that im looking into the line:
    //Experiment date released (22), Size (36), md5sum (38), File download URL(39)
    //maybe this parameters should be entered by xml file
    val path = source.outputFolder + File.separator + dataset.outputFolder + File.separator + "Downloads"
    val file = Source.fromFile(path + File.separator + "metadata" + ".tsv")
    if(file.hasNext) {
      val datasetId = FileDatabase.datasetId(FileDatabase.sourceId(source.name),dataset.name)
      val stage = STAGE.DOWNLOAD

      val header = file.getLines().next().split("\t")

      val originLastUpdate = header.lastIndexOf("Experiment date released")
      val originSize = header.lastIndexOf("Size")
      val experimentAccession = header.lastIndexOf("Experiment accession")
      //to be used
      val md5sum = header.lastIndexOf("md5sum")
      val url = header.lastIndexOf("File download URL")

      Source.fromFile(path + File.separator + "metadata.tsv").getLines().drop(1).foreach(line => {
        val fields = line.split("\t")
        //this is the region data part.
        if (urlExists(fields(url))) {
          val candidateName = fields(url).split(File.separator).last
          val fileId = FileDatabase.fileId(datasetId,fields(url),stage,candidateName)
          val fileNameAndCopyNumber = FileDatabase.getFileNameAndCopyNumber(fileId)

          val filename =
            if(fileNameAndCopyNumber._2==1)fileNameAndCopyNumber._1
            else fileNameAndCopyNumber._1.replaceFirst("\\.","_"+fileNameAndCopyNumber._2+".")

          val filePath = path + File.separator + filename
          if(FileDatabase.checkIfUpdateFile(fileId,fields(md5sum),fields(originSize),fields(originLastUpdate))){
            downloadFileFromURL(fields(url), filePath)
            val file = new File(filePath)
            var hash = Files.hash(file,Hashing.md5()).toString

            var timesTried = 0
            while (hash != fields(md5sum) && timesTried < 4) {
              downloadFileFromURL(fields(url), filePath)
              hash = Files.hash(file, Hashing.md5()).toString
              timesTried += 1
            }
            FileDatabase.markAsUpdated(fileId,file.getTotalSpace.toString)
          }
        }
        else
          logger.error("could not download " + fields(url) + "path does not exist")
        //this is the metadata part.
        //example of json url https://www.encodeproject.org/experiments/ENCSR570HXV/?frame=embedded&format=json
        val urlExperimentJson = source.url + "experiments" + File.separator + fields(experimentAccession) + File.separator + "?frame=embedded&format=json"
        if (urlExists(urlExperimentJson)) {
          val candidateName = fields(url).split(File.separator).last+".json"
          val fileId = FileDatabase.fileId(datasetId,urlExperimentJson,stage,candidateName)
          val fileNameAndCopyNumber = FileDatabase.getFileNameAndCopyNumber(fileId)
          val jsonName =
            if(fileNameAndCopyNumber._2==1)fileNameAndCopyNumber._1
            else fileNameAndCopyNumber._1.replaceFirst("\\.","_"+fileNameAndCopyNumber._2+".")

          val filePath = path + File.separator + jsonName
          //As I dont have the metadata for the json file i use the same as the region data.
          if(FileDatabase.checkIfUpdateFile(fileId,fields(md5sum),fields(originSize),fields(originLastUpdate))){
            downloadFileFromURL(urlExperimentJson, filePath)
            val file = new File(filePath)
            //cannot check the correctness of the download for the json.
            FileDatabase.markAsUpdated(fileId,file.getTotalSpace.toString)
          }
        }
      })
      FileDatabase.markAsOutdated(datasetId,stage)
    }
    else
      logger.debug("metadata.tsv file is empty")
  }

  /**
    * checks if the given URL exists
    *
    * @param path URL to check
    * @return URL exists
    */
  def urlExists(path: String): Boolean = {
    try {
      scala.io.Source.fromURL(path)
      true
    } catch {
      case _: Throwable => false
    }
  }
}
