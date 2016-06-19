package com.github.bigwheel.youseibox

import scalaz._
import Scalaz._

package object table {

  case class ChainedTable(name: String, columnOfParentTable: String, columnOfChainedTable: String)
  case class _1toNTable(name: String, columnOfParentTable: String, columnOfChainedTable: String)

  case class Dot[D, L](value: D, lines: Line[L, D]*)
  case class Line[L, D](value: L, dot: Dot[D, L])

  class Table(val name: String, columnNames: String*) {
    require(columnNames.distinct.size == columnNames.size) // カラム名はユニークでなければならない
    val columns: Set[Column] = columnNames.map(new Column(_, this)).toSet
    def getColumn(name: String): Column = columns.find(_.name === name).get
  }

  class Column(val name: String, val table: Table) {
    def toSql = table.name + "." + name
  }
  case class JoinDefinition(columnOfParentTable: Column, _1toNRelation: Boolean, columnOfChildTable: Column)

  case class FullColumnInfo(columnExpression: String, nowColumnName: String, originalColumn: Column) {
    def this(column: Column) = this(s"${column.table.name}.${column.name}",
      s"${column.table.name}__${column.name}", column)

    val toColumnDefinition = s"$columnExpression AS $nowColumnName"
  }

  implicit class RichFullColumnInfoSet(fciSet: Set[FullColumnInfo]) {
    def getSelectSqlBody: String = fciSet.map(_.toColumnDefinition).mkString(", ")
  }

  private def tableToSqlPlusColumnInfo(table: Table): (String, Set[FullColumnInfo]) = {
    val fullColumnInfoSet = for (column <- table.columns) yield new FullColumnInfo(column)
    val columnsDefinition = fullColumnInfoSet.getSelectSqlBody

    (s"SELECT $columnsDefinition FROM ${table.name}", fullColumnInfoSet)
  }

  // 返り値2つ目はカラム名とそのオリジナルのテーブル名・カラム名
  def toSql(tableTree: Dot[Table, JoinDefinition]): (String, Set[FullColumnInfo]) = {
    if (tableTree.lines.isEmpty) {
      tableToSqlPlusColumnInfo(tableTree.value)
    } else if (tableTree.lines.head.dot.lines.isEmpty) {
      val parentTable = tableTree.value
      val joinDefinition = tableTree.lines.head.value
      val childTable = tableTree.lines.head.dot.value

      val parentTableColumns = parentTable.columns.map { column => new FullColumnInfo(column) }
      val childTableColumns = childTable.columns.map { column =>
        val base = s"${childTable.name}.${column.name}"
        if (joinDefinition._1toNRelation)
          FullColumnInfo(s"GROUP_CONCAT($base)", s"${childTable.name}__${column.name}s", column)
        else
          FullColumnInfo(base, s"${childTable.name}__${column.name}", column)
      }

      val columnsDefinition = (parentTableColumns ++ childTableColumns).getSelectSqlBody

      val parentSide = parentTable.name + "." + joinDefinition.columnOfParentTable.name
      val childSide = childTable.name + "." + joinDefinition.columnOfChildTable.name

      val postfix = if (joinDefinition._1toNRelation) s" GROUP BY $parentSide" else ""
      (s"SELECT $columnsDefinition FROM ${parentTable.name} JOIN ${childTable.name} " +
        s"ON $parentSide = $childSide$postfix", parentTableColumns ++ childTableColumns)
    } else {
      // 各種定義 & わかりやすくさのための別名定義
      val temporaryTableName = "A"
      val parentTable = tableTree.value
      val joinDefinition = tableTree.lines.head.value
      val childTable = tableTree.lines.head.dot.value

      // 子テーブル系の処理
      val (sql, childTableFCIs) = toSql(tableTree.lines.head.dot)
      val nestedTableSql = "( " + sql + " ) AS " + temporaryTableName

      // 親テーブルのためのFCIsの算出
      val parentTableFCIs = parentTable.columns.map { column => new FullColumnInfo(column) }
      val ChildTableFCIsForParentSelect = childTableFCIs.map { fci =>
        val base = s"$temporaryTableName.${fci.nowColumnName}"
        if (joinDefinition._1toNRelation)
          FullColumnInfo(s"GROUP_CONCAT($base)", fci.nowColumnName + "s", fci.originalColumn)
        else
          FullColumnInfo(base, fci.nowColumnName, fci.originalColumn)
      }
      val FCIsForParentSelect = parentTableFCIs ++ ChildTableFCIsForParentSelect


      val parentSide = joinDefinition.columnOfParentTable.toSql
      val childSide = temporaryTableName + "." + childTable.name + "__" +
        joinDefinition.columnOfChildTable.name

      val postfix = if (joinDefinition._1toNRelation) s" GROUP BY $parentSide" else ""
      (s"SELECT ${FCIsForParentSelect.getSelectSqlBody} FROM ${parentTable.name} JOIN $nestedTableSql " +
        s"ON $parentSide = $childSide$postfix", FCIsForParentSelect)
    }
  }
}
