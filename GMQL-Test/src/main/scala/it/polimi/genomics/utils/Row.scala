package it.polimi.genomics.utils

import it.polimi.genomics.utils.SchemaColumnType.{CHAR, DOUBLE, LONG, STRING}

import scala.collection.mutable

/**
  * Created by canakoglu on 2/3/17.
  */

/**
  * Abstract row class in the region or metadata file.
  *
  * @define lineDefinition      line as a string from the file.
  * @define rowNumberDefinition row number in the file.
  * @constructor Create a row with line as string and row number in the file.
  * @param line       $lineDefinition
  * @param lineNumber $rowNumberDefinition
  */
abstract class Row(val line: String, val lineNumber: Option[Int] = None) extends Ordered[Row] {
  def compare(that: Row): Int = this.line compare that.line

  def canEqual(other: Any): Boolean = other.isInstanceOf[Row]

  override def equals(other: Any): Boolean = other match {
    case that: Row =>
      (that canEqual this) &&
        line == that.line
    case _ => false
  }

  override lazy val hashCode: Int = {
    val state = Seq(line)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def toString = s"Row($line, ${lineNumber.getOrElse("None")})"
}


//TODO possibly implement correctly for CVS, possibly implement for different parsers(TAB DELIMITED OR GTF)!!
/**
  * Region row that is parsed by using schema of dataset.
  *
  * @constructor Creates a region row from a line by using schema
  * @param line       $lineDefinition
  * @param schema     schema of the region row
  * @param lineNumber $rowNumberDefinition
  */
class RegionRow(override val line: String, schema: Schema, override val lineNumber: Option[Int] = None) extends Row(line, lineNumber) {
  val valueMap = Row.parseLineToMap(line, schema)

  //first column is string second and third columns are Long
  val coordinates = Seq(valueMap(0).asInstanceOf[String], valueMap(1).asInstanceOf[Long], valueMap(2).asInstanceOf[Long])

  override def compare(that: Row): Int = {
    //    println("RegionRow->compare")
    that match {
      case that: RegionRow =>
        var compareResult = 0
//        for (i <- valueMap.keys.toList.sorted if compareResult == 0) {
//          compareResult = valueMap(i) match {
//            case (thisValue: String) => thisValue compare that.valueMap(i).asInstanceOf[String]
//            case (thisValue: Long) => thisValue compare that.valueMap(i).asInstanceOf[Long]
//            case (thisValue: Char) => thisValue compare that.valueMap(i).asInstanceOf[Char]
//            case (thisValue: Double) => thisValue compare that.valueMap(i).asInstanceOf[Double]
//          }
//        }
        for (pair <- this.coordinates zip that.coordinates if compareResult == 0) {
          compareResult = pair match {
            case (thisValue: String, thatValue: String) => thisValue compare thatValue
            case (thisValue: Long, thatValue: Long) => thisValue compare thatValue
          }
        }
        compareResult
      case that => super.compare(that)
    }
  }


  override def canEqual(other: Any): Boolean = other.isInstanceOf[RegionRow]

  override def equals(other: Any): Boolean = other match {
    case that: RegionRow =>
      (that canEqual this) &&
        valueMap == that.valueMap
    case that: Row =>
      super.equals(that)
    case _ => false
  }

  override lazy val hashCode: Int = {
    val state = Seq(valueMap)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }


  override def toString = s"RegionRow($line, $schema, ${lineNumber.getOrElse("None")})"
}

/**
  * Error row that cannot be parsed.
  *
  * @constructor Creates an error row that cannot be parsed
  * @param line       $lineDefinition
  * @param lineNumber $rowNumberDefinition
  */
class ErrorRow(override val line: String, val error: Option[Exception] = None, override val lineNumber: Option[Int] = None) extends Row(line, lineNumber) {
  override def toString = s"ErrorRow($line, ${lineNumber.getOrElse("None")})"
}

/**
  * Metadata row that is parsed as metadata
  *
  * @constructor Creates a metadata row from a line
  * @param line       $lineDefinition
  * @param lineNumber $rowNumberDefinition
  */
class MetaRow(override val line: String, override val lineNumber: Option[Int] = None) extends Row(line, lineNumber) {
  val (attribute, value) = {
    val split = line.split("\t", -1)
    if (split.length != 2)
      throw CustomException(s"Metadata row cannot be split, split length is ${split.length}")
    (split.head, split.last)
  }

  override def toString = s"MetaRow($line, ${lineNumber.getOrElse("None")})"
}

/**
  * Parse exception for a row
  *
  * @param message error message
  */
case class CustomException(message: String) extends Exception {
  override def getMessage: String = message
}


object Row {
  val doublePrecision = 10
  val nameMap = mutable.Map[String, Int]()

  def clearNameMap = nameMap.clear

  implicit class ExtendedDouble(n: Double) {
    def rounded(x: Int = doublePrecision) = {
      import scala.math._
      val w = pow(10, x)
      (n * w).toLong.toDouble / w
    }
  }

  def parseLineToMap(line: String, schema: Schema) = {
    val parsed = line.split("\t", -1)
    if (parsed.size != schema.columns.size)
      throw CustomException("Unmatched line and schema size")
    (parsed zip schema.columns) map { case (value, column) =>
      val valueCorrected = column.columnType match {
        //        STRING, LONG, CHAR, DOUBLE
        case STRING | CHAR => value
        case LONG => value.toLong
        //TODO correct this
        //                case CHAR => val chars = value.toCharArray; if (chars.size > 1) throw new Exception(s"Char is not one character ${value}"); chars.head
        case DOUBLE => value.toDouble.rounded()
      }
      val columnName = column.name.toUpperCase
      if (!Row.nameMap.contains(columnName))
        Row.nameMap += ((columnName, Row.nameMap.size))
      (Row.nameMap(columnName), valueCorrected)
    }
  }.toMap


  def apply(line: String, schemaOption: Option[Schema], rowNumber: Option[Int] = None): Row = {
    schemaOption match {
      case None => try {
        new MetaRow(line, rowNumber)
      } catch {
        case e: Exception => new ErrorRow(line, Some(e), rowNumber)
      }
      case Some(schema) =>
        try {
          new RegionRow(line, schema, rowNumber)
        } catch {
          case e: Exception => new ErrorRow(line, Some(e), rowNumber)
        }
    }
  }
}


object TestRow extends App {
  val qwe = try {
    val asd = Row("asd\tqwe\ta", Some(new Schema(List(SchemaColumn("ASD", SchemaColumnType.DOUBLE), SchemaColumn("qwe", SchemaColumnType.STRING)))))
    asd
  } catch {
    case e: NumberFormatException => "NumberFormatException\n" + e.getMessage
    case e: CustomException => "CustomException\n" + e.getMessage //; e.printStackTrace()
    case e: Exception => "Exception\n" + e.getMessage;
  }
  println(qwe)
  qwe match {
    case e: ErrorRow =>
      println("ErrowRow")
    case e: Row =>
      println("Row")
    case _ =>
      println("asdasdasdasd")
  }


  val row1 = Row("chr1\t01\t2", Some(new Schema(List(SchemaColumn("chr", SchemaColumnType.STRING), SchemaColumn("left", SchemaColumnType.LONG), SchemaColumn("right", SchemaColumnType.LONG)))))
  println("row1: " + row1)
  val row2 = Row("chr1\t0001\t3", Some(new Schema(List(SchemaColumn("chr", SchemaColumnType.STRING), SchemaColumn("left", SchemaColumnType.LONG), SchemaColumn("right", SchemaColumnType.LONG)))))
  println("row2: " + row2)
  val row3 = Row("chr1\t001\t1", Some(new Schema(List(SchemaColumn("chr", SchemaColumnType.STRING), SchemaColumn("left", SchemaColumnType.LONG), SchemaColumn("right", SchemaColumnType.LONG)))))
  println("row3: " + row3)

  val list = List(row1, row2, row3)
  println(list)
  println(list.sorted)

}