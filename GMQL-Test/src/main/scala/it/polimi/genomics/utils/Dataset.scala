package it.polimi.genomics.utils

import java.io.File

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataInputStream, FileSystem, Path}
import org.slf4j.LoggerFactory

import scala.collection.mutable.ListBuffer


/**
  * Created by canakoglu on 1/27/17.
  */

class Sample2(val name: String, regionFile: File, metaFile: File, schema: Schema) {
  lazy val regionList = readFile(false, true).toList
  lazy val metaList = readFile(true, true).toList

  def test = {
    val pt=new Path("hdfs://npvm11.np.wc1.yellowpages.com:9000/user/john/abc.txt")
    val fs = FileSystem.get(new Configuration())
    val asd: FSDataInputStream = fs.open(pt)
    scala.io.Source.fromInputStream(asd)

  }

  def readFile(isMeta: Boolean, sort: Boolean = false, trimLine: Boolean = true, removeEmptyLine: Boolean = true) = {
    var result = scala.io.Source.fromFile(if (isMeta) metaFile else regionFile).getLines()
      //trim line
      .map(line => if (trimLine) line.trim else line)
      //filter out empty lines
      .filterNot(removeEmptyLine && _.isEmpty)
      //add file number
      .zipWithIndex
      //convert to Row list
      .map(indexed => Row(indexed._1, if (isMeta) None else Some(schema), Some(indexed._2)))
    if (sort)
      result = result.toList.sorted.iterator
    result
  }


  def readFileGroupped(isMeta: Boolean, sort: Boolean = false, trimLine: Boolean = false, removeEmptyLine: Boolean = false) = {
    implicit val filePath = (if (isMeta) metaFile else regionFile).getAbsolutePath
    readFile(isMeta, sort, trimLine, removeEmptyLine)
      .iterativeGroupBy {
        case region: RegionRow => region.coordinates
        case temp => temp.line
      }
  }


  def regionSimilarity(that: Sample2): Double = similarity(regionList, that.regionList)


  def metaSimilarity(that: Sample2): Double = similarity(metaList, that.metaList)


  def similarity(that: Sample2): Double = (regionSimilarity(that) + metaSimilarity(that)) / 2


  private def similarity(set1: List[Row], set2: List[Row]): Double = {
    val union = set1.size + set2.size
    // If both sets are empty, jaccard index is defined as "1".
    if (union == 0) 1.0
    else {
      val intersect = (set1 intersect set2).size.toDouble
      intersect / (union - intersect)
    }
  }

  implicit class IterativeGroup[T <: Ordered[T]](iter: Iterator[T])(implicit val filePath: String = "Unknown path") {
    final val logger = LoggerFactory.getLogger(this.getClass)
    private val iterBuffer = iter.buffered

    def iterativeGroupBy[B](func: T => B): Iterator[List[T]] = new Iterator[List[T]] {
      def hasNext = iterBuffer.hasNext

      def next = {
        val first: T = iterBuffer.next()
        val firstValue: B = func(first)
        val buffer = ListBuffer(first)
        while (iterBuffer.hasNext && func(iterBuffer.head) == firstValue)
          buffer += iterBuffer.next
        if (iterBuffer.hasNext && iterBuffer.head < first)
          logger.warn(s"Unordered of lines(SampleName ${name} - ${filePath}): " + iterBuffer.head + first)
        buffer.toList
      }
    }

  }


  def canEqual(other: Any): Boolean = other.isInstanceOf[Sample2]

  override def equals(other: Any): Boolean = other match {
    case that: Sample2 =>
      (that canEqual this) &&
        hashCode == other.hashCode &&
        regionList == that.regionList &&
        metaList == that.metaList
    case _ => false
  }

  override lazy val hashCode: Int = {
    val state = Seq(regionList, metaList)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}


class Dataset2(val name: String, val schema: Schema, val regionDataFolder: String, val metadataFolder: String) {


  def getFileMap(directory: String, isMeta: Boolean) = {
    new File(directory).listFiles().toIterable.filter(_.isFile).filter(_.getName.endsWith(".meta") == isMeta).map(file => (file.getName, file))
  }


  val sampleList: List[Sample2] = {
    val regionFiles = getFileMap(regionDataFolder, false)
    val metaFiles = getFileMap(metadataFolder, true).toMap
    /*val fileTriples = */ regionFiles.flatMap { (regionFile: (String, File)) =>
      val metaFile = metaFiles.get(regionFile._1 + ".meta")
      if (!metaFile.isEmpty)
        Some(new Sample2(regionFile._1, regionFile._2, metaFile.get, schema))
      else
        None
    }.toList
  }

  def hasSameSampleNames(other: Dataset2): Boolean = {
    val thisNameList = this.sampleList.map(_.name).sorted
    val thatNameList = other.sampleList.map(_.name).sorted
    thisNameList == thatNameList
  }


  def getSameNameSamples(other: Dataset2): List[(Sample2, Sample2)] = {
    val thisSampleMap = this.sampleList.map(sample => (sample.name, sample)).toMap
    val thatSampleMap = other.sampleList.map(sample => (sample.name, sample)).toMap
    val commonKeys = thisSampleMap.keySet.intersect(thatSampleMap.keySet)
    commonKeys.toList.sorted.map(commonKey => (thisSampleMap(commonKey), thatSampleMap(commonKey)))
  }


}


//class Dataset(val name: String, val schema: Schema, val sampleList: List[Sample]) extends Equals {
//  val sampleSet = sampleList.toSet
//
//  def -(that: Dataset): Dataset = Dataset(s"Diff of ${this.name} and ${that.name}", schema diff that.schema, (this.sampleSet diff that.sampleSet).toList)
//
//  def intersect(that: Dataset) = Dataset(s"Intersect of ${this.name} and ${that.name}", schema intersect that.schema, (this.sampleSet intersect that.sampleSet).toList)
//
//  //TODO remove contains duplicates, check in the loading of the sample
//  lazy val containsDuplicates: Boolean = sampleList.size != sampleSet.size
//
//
//  //AUTO GENERATED CODE,  equivalence is based in the sets of region and meta files
//  def canEqual(other: Any): Boolean = other.isInstanceOf[Dataset]
//
//  override def equals(other: Any): Boolean = other match {
//    case that: Dataset =>
//      (that canEqual this) &&
//        schema == that.schema &&
//        sampleSet == that.sampleSet
//    case _ => false
//  }
//
//  override lazy val hashCode: Int = {
//    val state = Seq(schema, sampleSet)
//    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
//  }
//
//  override def toString = s"Dataset($schema, $sampleSet)"
//
//
//  def hasSameSampleNames(other: Dataset): Boolean = {
//    val thisNameList = this.sampleList.map(_.name).sorted
//    val thatNameList = other.sampleList.map(_.name).sorted
//    thisNameList == thatNameList
//  }
//
//
//  def getSameNameSamples(other: Dataset) = {
//    val thisSampleMap = this.sampleList.map(sample => (sample.name, sample)).toMap
//    val thatSampleMap = other.sampleList.map(sample => (sample.name, sample)).toMap
//    val commonKeys = thisSampleMap.keySet.intersect(thatSampleMap.keySet)
//    commonKeys.toList.sorted.map(commonKey => (thisSampleMap(commonKey), thatSampleMap(commonKey)))
//  }
//
//}
//
//object Dataset {
//  def apply(name: String, schema: Schema, samples: List[Sample]): Dataset = new Dataset(name, schema, samples)
//
//  def apply(name: String, schemaPath: String, regionDataFolder: String, metadataFolder: String): Dataset = Dataset(name, Schema(schemaPath), regionDataFolder, metadataFolder)
//
//  def apply(name: String, schema: Schema, regionDataFolder: String, metadataFolder: String): Dataset = {
//    def getFileMap(directory: String, isMeta: Boolean) = {
//      new File(directory).listFiles().toIterable.filter(_.isFile).filter(_.getName.endsWith(".meta") == isMeta).map(file => (file.getName, file))
//    }
//
//    val regionFiles = getFileMap(regionDataFolder, false)
//    val metaFiles = getFileMap(metadataFolder, true).toMap
//    val fileTriples = regionFiles.flatMap { (regionFile: (String, File)) =>
//      val metaFile = metaFiles.get(regionFile._1 + ".meta")
//      if (!metaFile.isEmpty)
//        Some(regionFile._1, regionFile._2, metaFile.get)
//      else
//        None
//    }
//    //    val schema = Schema(schemaPath)
//
//    val samples = fileTriples.map(fileTriple => Sample(fileTriple._1, fileTriple._2, fileTriple._3, schema))
//
//    new Dataset(name, schema, samples.toList)
//  }
//
//}


//class MappedDataset(val ds1: Dataset, val ds2: Dataset) {
//  private val DEBUG = true
//  val sameMap = (ds1 intersect ds2).sampleSet.map { sampleDs1 =>
//    //TODO use find
//    (sampleDs1, ds2.sampleSet.find(_ == sampleDs1).get)
//  }.toMap
//  if (DEBUG)
//    println(sameMap.map(a => a._1.name + "-" + a._2.name).mkString("\n"))
//
//  var ds1Only = (ds1 - ds2).sampleSet
//  var ds2Only = (ds2 - ds1).sampleSet
//
//
//  val otherMap = {
//    //    val dsDiff1 = (ds1).sampleSet.toArray.sorted
//    val dsDiff1 = ds1Only.toArray
//    //    val dsDiff2 = (ds2).sampleSet.toArray.sorted
//    val dsDiff2 = ds2Only.toArray
//
//    if (dsDiff1.isEmpty || dsDiff2.isEmpty)
//      Map.empty[Sample, Sample]
//    else {
//      val matrix = dsDiff1 map { first =>
//        dsDiff2 map { second =>
//          first.similarity(second)
//        }
//      }
//      if (DEBUG) {
//        println("-" * 20 + "INPUT" + "-" * 20)
//        println(matrix.map(_.map(_.formatted("%3.3f")).mkString(", ")).mkString("\n"))
//        println("-" * 18 + "END INPUT" + "-" * 18)
//      }
//
//      if (dsDiff1.size <= dsDiff2.size) {
//        val h = Hungarian(matrix)
//        println("Hungarian value" + h.solve())
//        dsDiff1.zip(dsDiff2.zip(h.yx).filterNot(_._2 < 0).sortBy(_._2).map(_._1)).toMap
//      } else {
//        val h = Hungarian(matrix.transpose)
//        h.solve()
//        //NOT SURE
//        dsDiff1.zip(h.yx).filterNot(_._2 < 0).sortBy(_._2).map(_._1).zip(dsDiff2).toMap
//      }
//    }
//
//  }
//  if (DEBUG) {
//    println("-" * 100)
//    println(otherMap.toList.sortBy(_._1.name).map(a => a._1.name + "-" + a._2.name + " => similarity: " + ds1.sampleSet.find(_.name == a._1.name).get.similarity(ds2.sampleSet.find(_.name == a._2.name).get)).mkString("\n"))
//  }
//
//
//  //can be optimized
//  ds1Only = (ds1 - ds2).sampleSet -- otherMap.unzip._1
//  ds2Only = (ds2 - ds1).sampleSet -- otherMap.unzip._2
//
//  println("TEST")
//}


//object Test {
//  final val logger = LoggerFactory.getLogger(Test.getClass)
//
//  var id = "1"
//  val sample1 = Sample("sample1", s"/home/canakoglu/datasetRepository/outputDs/${id}")
//  id = "1_1"
//  val sample1_1 = Sample("sample1_1", s"/home/canakoglu/datasetRepository/outputDs/${id}")
//
//  id = "2"
//  val sample2 = Sample("sample2", s"/home/canakoglu/datasetRepository/outputDs/${id}")
//  id = "2_1"
//  val sample2_1 = Sample("sample2_1", s"/home/canakoglu/datasetRepository/outputDs/${id}")
//
//  id = "3"
//  val sample3 = Sample("sample3", s"/home/canakoglu/datasetRepository/outputDs/${id}")
//  id = "3_1"
//  val sample3_1 = Sample("sample3_1", s"/home/canakoglu/datasetRepository/outputDs/${id}")
//
//
//  val sampleExtra11 = Sample("sampleExtra11", List(Row("extra-region-11", None)), List(Row("extra-meta-11", None)))
//  val sampleExtra12 = Sample("sampleExtra12", List(Row("extra-region-12", None)), List(Row("extra-meta-12", None)))
//  val sampleExtra21 = Sample("sampleExtra21", List(Row("extra-region-11", None), Row("extra-region-21", None), Row("extra-region-21-1", None)), List(Row("extra-meta-21", None)))
//  val sampleExtra22 = Sample("sampleExtra22", List(Row("extra-region-22", None), Row("extra-region-11", None)), List(Row("extra-meta-22", None)))
//
//  val ds1 = Dataset("ds1", Schema(), List(sample1, sample2, sample3, sampleExtra11, sampleExtra12))
//  val ds2 = Dataset("ds2", Schema(), List(sample1_1, sample2_1, sample3_1, sampleExtra21, sampleExtra22))
//
//
//  def main(args: Array[String]): Unit = {
//    //    println("Equals1: " + (sample1 == sample1_1))
//    //    println("Equals2: " + (sample2 == sample3_1))
//    //    println("Equals3: " + (sample3 == sample2_1))
//    //
//    //
//    //    println("containsDuplicates: " + sample1.containsDuplicates)
//    //    println("containsDuplicates: " + sample2.containsDuplicates)
//    //
//    //
//    //    println("metaset:  " + sample1.metaSet.size)
//    //
//    //
//    //    val dsIntersect = (ds1 intersect ds2).sampleSet.size
//    //    val dsDiff1 = ds1 - ds2
//    //    val dsDiff2 = ds2 - ds1
//    //
//    //    println("dsIntersect: " + dsIntersect)
//    //
//    //    println("dsDiff1: " + dsDiff1)
//    //    println("dsDiff2: " + dsDiff2)
//    //
//    //
//    //    for (first <- dsDiff1.sampleSet) {
//    //      //      logger.debug(dsDiff2.sampleSet.map(second => "%3.3f".format(first.distance(second))).mkString("Distance: ", ", ", ""))
//    //      val str = new StringBuilder("Distance: ")
//    //      for (second <- dsDiff2.sampleSet)
//    //        str ++= f"${first.distance(second)}%2.2f "
//    //      println(str.mkString)
//    //    }
//    //
//    //
//    //    val matrix = dsDiff1.sampleSet.toArray.sorted map { first =>
//    //      dsDiff2.sampleSet.toArray.sorted map { second =>
//    //        first.distance(second)
//    //      }
//    //    }
//    //    println(dsDiff1.sampleSet.toArray.sorted.map(_.name).mkString(", "))
//    //    println(dsDiff2.sampleSet.toArray.sorted.map(_.name).mkString(", "))
//    //
//    //    val h = Hungarian(matrix)
//    //    println(s"h.solve: ${h.solve}")
//    //    println("Solution xy = " + h.xy.mkString(" "))
//
//    val qwe = new MappedDataset(ds1, ds2)
//    //    println(qwe.sameMap.map(a => a._1.name + "-" + a._2.name).mkString("\n"))
//
//
//    val ds_real = Dataset("dsreal", Schema(), "/home/canakoglu/datasetRepository/datasets/annotations", "/home/canakoglu/datasetRepository/datasets/annotations")
//    val ds_calc = Dataset("dscalc", "/tmp/gmql_test_arif/select_test/annotations/test.schema", "/tmp/gmql_test_arif/select_test/annotations/exp", "/tmp/gmql_test_arif/select_test/annotations/meta")
//    val qwe2 = new MappedDataset(ds_real, ds_calc)
//
//
//    println("X" * 100)
//    val ds_real2 = Dataset("dsreal", "/home/canakoglu/datasetRepository/datasets/beds_in/test.schema", "/home/canakoglu/datasetRepository/datasets/beds_in", "/home/canakoglu/datasetRepository/datasets/beds_in")
//    val ds_calc2 = Dataset("dscalc", "/home/canakoglu/datasetRepository/datasets/beds_out/test.schema", "/home/canakoglu/datasetRepository/datasets/beds_out", "/home/canakoglu/datasetRepository/datasets/beds_out")
//    val qwe3 = new MappedDataset(ds_real2, ds_calc2)
//
//
//    val random = new java.security.SecureRandom
//
//    def random2dArray(dim1: Int, dim2: Int, maxValue: Int): Array[Array[Double]] = Array.fill(dim1, dim2) {
//      random.nextInt(maxValue) /*/maxValue.toDouble */
//    }
//
//
//    println("Y" * 100)
//    val matrix = random2dArray(500, 500, 100000)
//    val h = Hungarian(matrix)
//    h.solve()
//
//  }
//}