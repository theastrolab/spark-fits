package com.sparkfits

import scala.util.{Try, Success, Failure}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.apache.spark.sql.types._


/**
  * This is the beginning of a FITS library in Scala.
  * You will find a large number of methodes to manipulate Binary Table HDUs.
  * There is no support for image HDU for the moment.
  */
object FitsBintableLib {
  case class BintableInfos() extends FitsLib.Infos {

    def implemented: Boolean = {true}

    /**
      * Get the number of row of a HDU.
      * We rely on what's written in the header, meaning
      * here we do not access the data directly.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @return (Long), the number of rows as written in KEYWORD=NAXIS2.
      *
      */
    def getNRows(keyValues : Map[String, String]) : Long = {
      keyValues("NAXIS2").toLong
    }

    /**
      * Get the size (bytes) of each row of a HDU.
      * We rely on what's written in the header, meaning
      * here we do not access the data directly.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @return (Int), the size (bytes) of one row as written in KEYWORD=NAXIS1.
      *
      */
    def getSizeRowBytes(keyValues: Map[String, String]) : Int = {
      keyValues("NAXIS1").toInt
    }

    /**
      * Get the number of column of a HDU.
      * We rely on what's written in the header, meaning
      * here we do not access the data directly.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @return (Long), the number of rows as written in KEYWORD=TFIELDS.
      *
      */
    def getNCols(keyValues : Map[String, String]) : Long = {
      // println(s"getNCols> keyValues=${keyValues.toString}")
      if (keyValues.contains("TFIELDS")) keyValues("TFIELDS").toLong
      else 0L
    }

    /**
      * Return the types of elements for each column as a list.
      *
      * @param col : (Int)
      *   Column index used for the recursion.
      * @return (List[String]), list with the types of elements for each column
      *   as given by the header.
      *
      */
    def getColTypes(keyValues: Map[String, String]): List[String] = {
      val colTypes = List.newBuilder[String]

      val ncols = getNCols(keyValues).toInt

      for (col <- 0 to ncols-1) {
        colTypes += getColumnType(keyValues, col)
      }
      colTypes.result
    }

    def listOfStruct : List[StructField] = {
      // Grab max number of column
      // Get the list of StructField.
      val lStruct = List.newBuilder[StructField]

      val cns = List.newBuilder[String]
      for (colIndex <- colPositions) {
        cns += colNames("TTYPE" + (colIndex + 1).toString)
      }

      // println(s"BintableInfos.listOfStruct> rowTypes=${rowTypes.toString} colPositions=${colPositions.toString} cns=${cns.toString}")

      for (colIndex <- colPositions) {
        val colName = FitsLib.shortStringValue(colNames("TTYPE" + (colIndex + 1).toString))

        // println(s"listOfStruct> colname=${colName} rowType=${rowTypes(colIndex)}")

        lStruct += readMyType(colName, rowTypes(colIndex))
      }

      lStruct.result
    }

    def getRow(buf: Array[Byte]): List[Any] = {
      var row = List.newBuilder[Any]

      for (col <- colPositions) {
        row += getElementFromBuffer(
          buf.slice(splitLocations(col), splitLocations(col+1)), rowTypes(col))
      }
      row.result
    }

    /**
      * Companion to readLineFromBuffer. Convert one array of bytes
      * corresponding to one element of the table into its primitive type.
      *
      * @param subbuf : (Array[Byte])
      *   Array of byte describing one element of the table.
      * @param fitstype : (String)
      *   The type of this table element according to the header.
      * @return the table element converted from binary.
      *
      */
    def getElementFromBuffer(subbuf : Array[Byte], fitstype : String) : Any = {
      val shortType = FitsLib.shortStringValue{fitstype}

      shortType match {
        // 16-bit Integer
        case x if shortType.contains("I") => {
          ByteBuffer.wrap(subbuf, 0, 2).getShort()
        }
        // 32-bit Integer
        case x if shortType.contains("J") => {
          ByteBuffer.wrap(subbuf, 0, 4).getInt()
        }
        // 64-bit Integer
        case x if shortType.contains("K") => {
          ByteBuffer.wrap(subbuf, 0, 8).getLong()
        }
        // Single precision floating-point
        case x if shortType.contains("E") => {
          ByteBuffer.wrap(subbuf, 0, 4).getFloat()
        }
        // Double precision floating-point
        case x if shortType.contains("D") => {
          ByteBuffer.wrap(subbuf, 0, 8).getDouble()
        }
        // Boolean
        case x if shortType.contains("L") => {
          // 1 Byte containing the ASCII char T(rue) or F(alse).
          subbuf(0).toChar == 'T'
        }
        // Chain of characters
        case x if shortType.endsWith("A") => {
          // Example 20A means string on 20 bytes
          new String(subbuf, StandardCharsets.UTF_8).trim()
        }
        case _ => {
          println(s"""FitsLib.getElementFromBuffer> Cannot infer size of type $shortType from the header!
              See getElementFromBuffer
              """)
          0
        }
      }
    }

    var rowTypes: List[String] = List()
    var colNames: Map[String, String] = Map()
    var selectedColNames: List[String] = List()
    var colPositions: List[Int] = List()
    var splitLocations: List[Int] = List()

    /**
      * Description of a row in terms of bytes indices.
      * rowSplitLocations returns an array containing the position of elements
      * (byte index) in a row. Example if we have a row with [20A, E, E], one
      * will have rowSplitLocations -> [0, 20, 24, 28] that is a string
      * on 20 Bytes, followed by 2 floats on 4 bytes each.
      *
      * @param col : (Int)
      *   Column position used for the recursion. Should be left at 0.
      * @return (List[Int]), the position of elements (byte index) in a row.
      *
      */
    def rowSplitLocations(rowTypes: List[String], col : Int = 0) : List[Int] = {
      val ncols = rowTypes.size

      if (col == ncols) {
        Nil
      } else {
        getSplitLocation(rowTypes(col)) :: rowSplitLocations(rowTypes, col + 1)
      }
    }

    /**
      * Companion routine to rowSplitLocations. Returns the size of a primitive
      * according to its type from the FITS header.
      *
      * @param fitstype : (String)
      *   Element type according to FITS standards (I, J, K, E, D, L, A, etc)
      * @return (Int), the size (bytes) of the element.
      *
      */
    def getSplitLocation(fitstype : String) : Int = {
      val shortType = FitsLib.shortStringValue(fitstype)

      shortType match {
        case x if shortType.contains("I") => 2
        case x if shortType.contains("J") => 4
        case x if shortType.contains("K") => 8
        case x if shortType.contains("E") => 4
        case x if shortType.contains("D") => 8
        case x if shortType.contains("L") => 1
        case x if shortType.endsWith("A") => {
          // Example 20A means string on 20 bytes
          x.slice(0, x.length - 1).toInt
        }
        case _ => {
          println(s"""FitsLib.getSplitLocation> Cannot infer size of type $shortType from the header!
              See com.sparkfits.FitsLib.getSplitLocation
              """)
          0
        }
      }
    }

    /**
      * Get the position (zero based) of a column with name `colName` of a HDU.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @param colName : (String)
      *   The name of the column
      * @return (Int), position (zero-based) of the column.
      *
      */
    def getColumnPos(keyValues : Map[String, String], colName : String) : Int = {
      // Get the position of the column. Header names are TTYPE#
      val pos = Try {
        keyValues.
          filter(x => x._1.contains("TTYPE")).
          map(x => (x._1, x._2.split("'")(1).trim())).
          filter(x => x._2.toLowerCase == colName.toLowerCase).
          keys.head.substring(5).toInt
      }.getOrElse(-1)

      val isCol = pos >= 0
      isCol match {
        case true => isCol
        case false => throw new AssertionError(s"""
          $colName is not a valid column name!
          """)
      }

      // Zero based
      pos - 1
    }

    /**
      * Get the type of the elements of a column with index `colIndex` of a HDU.
      *
      * @param header : (Array[String])
      *   The header of the HDU.
      * @param colIndex : (Int)
      *   Index (zero-based) of a column.
      * @return (String), the type (FITS convention) of the elements of the column.
      *
      */
    def getColumnType(keyValues : Map[String, String], colIndex : Int) : String = {
      // Zero-based index
      FitsLib.shortStringValue(keyValues("TFORM" + (colIndex + 1).toString))
    }

    def initialize(empty_hdu: Boolean, header : Array[String], selectedColumns: List[String] = null) = {

      val keyValues = FitsLib.parseHeader(header)

      // Check if the user specifies columns to select
      this.colNames = keyValues.
        filter(x => x._1.contains("TTYPE")).
        map(x => (x._1, x._2.split("'")(1).trim()))

      this.selectedColNames = if (selectedColumns != null) {
        selectedColumns
      } else {
        this.colNames.values.toList.asInstanceOf[List[String]]
      }

      // println(s"Selected columns = ${selectedColNames.toString}")

      this.colPositions = selectedColNames.map(
        x => getColumnPos(keyValues, x)).toList.sorted

      this.rowTypes = if (empty_hdu) {
        List[String]()
      } else getColTypes(keyValues)
      val ncols = rowTypes.size

      // println(s"rowTypes = ${rowTypes.toString}")

      // splitLocations is an array containing the location of elements
      // (byte index) in a row. Example if we have a row with [20A, E, E], one
      // will have splitLocations = [0, 20, 24, 28] that is a string on 20 Bytes,
      // followed by 2 floats on 4 bytes each.
      this.splitLocations = if (empty_hdu) {
        List[Int]()
      } else {
        (0 :: rowSplitLocations(rowTypes, 0)).scan(0)(_ +_).tail
      }
      // println(s"handleBintable> rowTypes=${rowTypes.toString} colNames=${colNames.toString} colPositions=${colPositions.toString}")
      this
    }

    def readMyType(name : String, fitstype : String, isNullable : Boolean = true): StructField = {
      fitstype match {
        case x if fitstype.contains("I") => StructField(name, ShortType, isNullable)
        case x if fitstype.contains("J") => StructField(name, IntegerType, isNullable)
        case x if fitstype.contains("K") => StructField(name, LongType, isNullable)
        case x if fitstype.contains("E") => StructField(name, FloatType, isNullable)
        case x if fitstype.contains("D") => StructField(name, DoubleType, isNullable)
        case x if fitstype.contains("L") => StructField(name, BooleanType, isNullable)
        case x if fitstype.contains("A") => StructField(name, StringType, isNullable)
        case _ => {
          println(s"""FitsBintable.readMyType> Cannot infer type $fitstype from the header!
            See com.sparkfits.FitsSchema.scala
            """)
          StructField(name, StringType, isNullable)
        }
      }
    }
  }
}