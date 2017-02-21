package it.polimi.genomics.utils

import java.io.File

import it.polimi.genomics.utils.SchemaColumnType.SchemaColumnType

import scala.collection.immutable.Seq
import scala.xml.{NodeSeq, XML}

/**
  * Created by canakoglu on 2/3/17.
  */

/**
  * Defines a schema
  *
  * A schema contains list of columns, and optional schema name.
  *
  * @constructor constructs the schema with its parameters
  * @param columns    columns of the schema
  * @param schemaType optional type of the schema
  */
case class Schema(val columns: List[SchemaColumn], val schemaType: Option[String] = None) {
  /**
    * Computes the multiset difference between this schema columns and another schema columns.
    *
    * @param that other schema to calculate differences.
    * @return a new schema with columns which contains all elements of this schema columns
    *         except some of occurrences of elements that also appear in `that`.
    *         Result schema type is [[None]].
    *
    */
  def diff(that: Schema) = new Schema(this.columns diff that.columns)

  /**
    * Computes the intersection of columns between this schema and another schema.
    *
    * @param that other schema to calculate intersection.
    * @return a new schema with columns, which contains all elements of this schema
    *         which also appear in `that`.
    *         Result schema type is of this if it is equal to other, [[None]], otherwise.
    *
    */
  def intersect(that: Schema) = new Schema(this.columns intersect that.columns, if (this.schemaType != that.schemaType) None else this.schemaType)
}

/**
  * Factory for [[Schema]] instances.
  *
  */
object Schema {
  /**
    * Creates a schema from a file.
    * @param file file to read schema XML
    * @return a schema loaded from the given path.
    */
  def apply(file: File): Schema = {
    val schema = XML.loadFile(file)
    val schemaType = schema \ "gmqlSchema" \ "@type" text
    val fields: NodeSeq = schema \ "gmqlSchema" \ "field"
    val columns: Seq[SchemaColumn] = fields map { field =>
      SchemaColumn(field.text, SchemaColumnType.withName(field \ "@type" text))
    }
    new Schema(columns.toList, if (schemaType.isEmpty) None else Some(schemaType))
  }

  /**
    * Creates an empty schema
    * @return  a schema with empty column list.
    */
  def apply(): Schema = new Schema(List.empty)

  /**
    * Creates a schema from a file.
    * @param path path to file to read schema XML
    * @return a schema loaded from the given path.
    */
  def apply(path: String): Schema = Schema(new File(path))



  /**
    * Creates a schema from a file
    * @param fileOption a new schema from file if exists, otherwise an empty schema
    * @return a schema loaded from the given path.
    */
  def apply(fileOption: Option[File]): Schema = fileOption match {
    case Some(file) => Schema(file)
    case None => Schema()
  }
}

/**
  * A column to define in the schema.
  * @param name column name
  * @param columnType column type
  */
case class SchemaColumn(name: String, columnType: SchemaColumnType)

/**
  * Column type enumerator to define schema column.
  */
object SchemaColumnType extends Enumeration {
  type SchemaColumnType = Value
  //FOR now there are these
  val STRING, LONG, CHAR, DOUBLE = Value
}

