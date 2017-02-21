//package it.polimi.genomics.utils
//
//import java.io.File
//
//import org.slf4j.LoggerFactory
//
//import scala.collection.mutable.ListBuffer
//
///**
//  * Created by canakoglu on 1/30/17.
//  */
//
///**
//  *
//  *
//  * @constructor
//  * @param name
//  * @param regionList
//  * @param metaList
//  */
//class Sample(var name: String, regionList: List[Row], metaList: List[Row]) extends Ordered[Sample] with Equals {
//  lazy val regionSet = regionList.toSet
//  lazy val metaSet = metaList.toSet
//  var basicEqual = false
//
//
//  //TODO remove contains duplicates, check in the loading of the sample
//  lazy val containsDuplicates: Boolean = regionSet.size != regionList.size || metaSet.size != metaList.size
//
//
//  /**
//    * Jaccard similarity of the region data
//    *
//    * @param that another sample to compare
//    * @return Jaccard similarity of the region data of the samples
//    */
//  def regionSimilarity(that: Sample): Double = {
//    if (containsDuplicates || that.containsDuplicates) throw new IllegalStateException("Contains duplicates")
//    else Sample.similarity(this.regionSet, that.regionSet)
//  }
//
//  /**
//    * Jaccard similarity of the metadata
//    *
//    * @param that another sample to compare
//    * @return
//    */
//  def metaSimilarity(that: Sample): Double = {
//    if (containsDuplicates || that.containsDuplicates) throw new IllegalStateException("Contains duplicates")
//    else Sample.similarity(this.metaSet, that.metaSet)
//  }
//
//  /**
//    * Jaccard similarity of the region data and metadata. The result is based on
//    *
//    * @param that another sample to compare
//    * @return
//    */
//  def similarity(that: Sample): Double = {
//    if (containsDuplicates || that.containsDuplicates) throw new IllegalStateException("Contains duplicates")
//    else (regionSimilarity(that) + metaSimilarity(that)) / 2
//  }
//
//  def distance(that: Sample): Double = 1 - similarity(that)
//
//  /**
//    * difference of the region and meta data rows
//    *
//    * @param that another sample to calculate difference
//    * @return new sample which is difference of the previous ones.
//    */
//  def -(that: Sample) = new Sample(s"Diff of ${this.name} and ${that.name}", (this.regionSet -- that.regionSet).toList, (this.metaSet -- that.metaSet).toList)
//
//  //union
//  def +(that: Sample) = new Sample(s"Union of ${this.name} and ${that.name}", (this.regionSet ++ that.regionSet).toList, (this.metaSet ++ that.metaSet).toList)
//
//
//  //AUTO GENERATED CODE,  equivalence is based in the sets of region and meta files
//  def canEqual(other: Any): Boolean = other.isInstanceOf[Sample]
//
//  override def compare(that: Sample): Int = this.name compare that.name
//
//  override def equals(other: Any): Boolean = other match {
//    case that: Sample =>
//      (that canEqual this) &&
//        ## == that.## &&
//        metaSet == that.metaSet &&
//        regionSet == that.regionSet
//    case _ => false
//  }
//
//  override lazy val hashCode: Int = {
//    val state = Seq(regionSet, metaSet)
//    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
//  }
//
//  override def toString = s"Sample(${regionList.sorted}, ${metaList.sorted})"
//
//
//}
//
//object Sample {
//  /**
//    * New sample by list of rows of samples
//    *
//    * @param regionList region row list
//    * @param metaList   meta row list
//    * @return a new sample
//    */
//  def apply(name: String, regionList: List[Row], metaList: List[Row]): Sample = new Sample(name, regionList, metaList)
//
//  /**
//    * New sample from region and metadata files
//    *
//    * @param regionFile path to region file
//    * @param metaFile   path to meta file
//    * @return a new sample
//    */
//  def apply(name: String, regionFile: String, metaFile: String): Sample = Sample(name, readFile(new File(regionFile), None), readFile(new File(metaFile), None))
//
//
//  def apply(name: String, regionFile: String): Sample = Sample(name, regionFile, regionFile + ".meta")
//
//  /**
//    *
//    * @param name
//    * @param regionFile
//    * @param metaFile
//    * @param schema
//    * @return
//    */
//  def apply(name: String, regionFile: File, metaFile: File, schema: Schema): Sample = Sample(name, readFile(regionFile, Some(schema)), readFile(metaFile, None))
//
//
//  /**
//    * Read input files and convert into List of Row
//    *
//    * @param file            path to file
//    * @param trimLine        trim lines or not
//    * @param removeEmptyLine remove the empty lines in the file
//    * @return return the
//    */
//  def readFile(file: File, schema: Option[Schema], trimLine: Boolean = true, removeEmptyLine: Boolean = true) =
//    scala.io.Source.fromFile(file).getLines()
//      //trim line
//      .map(line => if (trimLine) line.trim else line)
//      //filter out empty lines
//      .filterNot(removeEmptyLine && _.isEmpty)
//      //convert to Row list
//      .map(Row(_, schema)).toList
//
//
//  def readFile2(file: File, schema: Option[Schema], trimLine: Boolean = false, removeEmptyLine: Boolean = false) =
//    scala.io.Source.fromFile(file).getLines()
//      //trim line
//      .map(line => if (trimLine) line.trim else line)
//      //filter out empty lines
//      .filterNot(removeEmptyLine && _.isEmpty)
//      //convert to Row list
//      .zipWithIndex
//      .map(indexed => Row(indexed._1, schema, Some(indexed._2)))
//      //TODO UNORDERED!!! .toList.sorted.iterator
//      .iterativeGroupBy {
//        case region: RegionRow => region.coordinates
//        case temp => temp.line
//      }
//
//  def readFile3(file: File, schema: Option[Schema], trimLine: Boolean = true, removeEmptyLine: Boolean = false) =
//    scala.io.Source.fromFile(file).getLines()
//      //trim line
//      .map(line => if (trimLine) line.trim else line)
//      //filter out empty lines
//      .filterNot(removeEmptyLine && _.isEmpty)
//      //convert to Row list
//      .zipWithIndex
//      .map(indexed => Row(indexed._1, schema, Some(indexed._2)))
//      .toList
//
//  //  implicit class ExtendedDouble(n: Iterator[(Row, Int)]) {
//
//
//  //  //http://stackoverflow.com/questions/10642337/is-there-are-iterative-version-of-groupby-in-scala
//  //  // all object are ordered by groupBy field so it seems possible to implement groupBy for sorted iterators
//  //  implicit class IterativeGroup[T](iterO: Iterator[T]) {
//  //    def iterativeGroupBy[B](func: T => B): Iterator[List[T]] = new Iterator[List[T]] {
//  //      private var iter = iterO
//  //
//  //      def hasNext = iter.hasNext
//  //
//  //      def next = {
//  //        val first = iter.next()
//  //        val firstValue = func(first)
//  //        val (i1, i2) = iter.span(el => func(el) == firstValue)
//  //        iter = i2
//  //        first :: i1.toList
//  //      }
//  //    }
//  //
//  //  }
//
//  //http://stackoverflow.com/questions/10642337/is-there-are-iterative-version-of-groupby-in-scala
//  // all object are ordered by groupBy field so it seems possible to implement groupBy for sorted iterators
//  implicit class IterativeGroup[T <: Ordered[T]](iter: Iterator[T]) {
//    final val logger = LoggerFactory.getLogger(this.getClass)
//    private val iterBuffer = iter.buffered
//    def iterativeGroupBy[B](func: T => B): Iterator[List[T]] = new Iterator[List[T]] {
//
//      def hasNext = iterBuffer.hasNext
//
//      def next = {
//        val first: T = iterBuffer.next()
//        val firstValue: B = func(first)
//        val buffer = ListBuffer(first)
//        while (iterBuffer.hasNext && func(iterBuffer.head) == firstValue)
//          buffer += iterBuffer.next
//        if (iterBuffer.hasNext && iterBuffer.head < first)
//          logger.warn("Unordered between: " + iterBuffer.head + first)
//        buffer.toList
//      }
//    }
//
//  }
//
//
//  /**
//    *
//    * Static function to calculate Jaccard similarity between two sets
//    *
//    * @param set1 set1 as input
//    * @param set2 set2 as input
//    * @return jaccard similarity between the set
//    */
//  private def similarity(set1: Set[Row], set2: Set[Row]): Double = {
//    val union = (set1 union set2).size
//    // If both sets are empty, jaccard index is defined as "1".
//    if (union == 0) 1.0
//    else (set1 intersect set2).size.toDouble / union
//  }
//}
