/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.thriftserver

import java.util.{UUID, List => JList}
import java.util.regex.Pattern

import scala.collection.JavaConverters._
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveOperationType
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObjectUtils
import org.apache.hive.service.cli._
import org.apache.hive.service.cli.operation.GetTablesOperation
import org.apache.hive.service.cli.session.HiveSession
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.catalog.CatalogTableType
import org.apache.spark.sql.catalyst.catalog.CatalogTableType._
import org.apache.spark.sql.hive.{HiveContext, HiveUtils}
import org.apache.spark.util.{Utils => SparkUtils}

/**
 * Spark's own GetTablesOperation
 *
 * @param hiveContext HiveContext to use
 * @param parentSession a HiveSession from SessionManager
 * @param catalogName catalog name. null if not applicable
 * @param schemaName database name, null or a concrete database name
 * @param tableName table name pattern
 * @param tableTypes list of allowed table types, e.g. "TABLE", "VIEW"
 */
private[hive] class GeohikerSparkGetTablesOperation(
    hiveContext: HiveContext,
    parentSession: HiveSession,
    catalogName: String,
    schemaName: String,
    tableName: String,
    tableTypes: JList[String])
  extends GetTablesOperation(parentSession, catalogName, schemaName, tableName, tableTypes)
    with GeohikerSparkMetadataOperationUtils with Logging {

  private var statementId: String = _

  override def close(): Unit = {
    super.close()
    GeohikerThriftServer.listener.onOperationClosed(statementId)
  }

  override def runInternal(): Unit = {
    statementId = UUID.randomUUID().toString
    // Do not change cmdStr. It's used for Hive auditing and authorization.
    val cmdStr = s"catalog : $catalogName, schemaPattern : $schemaName"
    val tableTypesStr = if (tableTypes == null) "null" else tableTypes.asScala.mkString(",")
    val logMsg = s"Listing tables '$cmdStr, tableTypes : $tableTypesStr, tableName : $tableName'"
    logInfo(s"$logMsg with $statementId")
    setState(OperationState.RUNNING)
    // Always use the latest class loader provided by executionHive's state.
    val executionHiveClassLoader = hiveContext.sharedState.jarClassLoader
    Thread.currentThread().setContextClassLoader(executionHiveClassLoader)

    val catalog = hiveContext.sessionState.catalog
    val schemaPattern = convertSchemaPattern(schemaName)
    val tablePattern = convertIdentifierPattern(tableName, true)
    val matchingDbs = catalog.listDatabases(schemaPattern)

    if (isAuthV2Enabled) {
      val privObjs =
        HivePrivilegeObjectUtils.getHivePrivDbObjects(seqAsJavaListConverter(matchingDbs).asJava)
      authorizeMetaGets(HiveOperationType.GET_TABLES, privObjs, cmdStr)
    }

    GeohikerThriftServer.listener.onStatementStart(
      statementId,
      parentSession.getSessionHandle.getSessionId.toString,
      logMsg,
      statementId,
      parentSession.getUsername)

    try {
      // Tables and views
      matchingDbs.foreach { dbName =>
        val tables = catalog.listTables(dbName, tablePattern, includeLocalTempViews = false)
        catalog.getTablesByName(tables).foreach { table =>
          val tableType = tableTypeString(table.tableType)
          if (tableTypes == null || tableTypes.isEmpty || tableTypes.contains(tableType)) {
            addToRowSet(table.database, table.identifier.table, tableType, table.comment)
          }
        }
      }

      // Temporary views and global temporary views
      if (tableTypes == null || tableTypes.isEmpty || tableTypes.contains(VIEW.name)) {
        val globalTempViewDb = catalog.globalTempViewManager.database
        val databasePattern = Pattern.compile(CLIServiceUtils.patternToRegex(schemaName))
        val tempViews = if (databasePattern.matcher(globalTempViewDb).matches()) {
          catalog.listTables(globalTempViewDb, tablePattern, includeLocalTempViews = true)
        } else {
          catalog.listLocalTempViews(tablePattern)
        }
        tempViews.foreach { view =>
          addToRowSet(view.database.orNull, view.table, VIEW.name, None)
        }
      }
      setState(OperationState.FINISHED)
    } catch {
      case e: HiveSQLException =>
        setState(OperationState.ERROR)
        GeohikerThriftServer.listener.onStatementError(
          statementId, e.getMessage, SparkUtils.exceptionString(e))
        throw e
    }
    GeohikerThriftServer.listener.onStatementFinish(statementId)
  }

  private def addToRowSet(
      dbName: String,
      tableName: String,
      tableType: String,
      comment: Option[String]): Unit = {
    val rowData = Array[AnyRef](
      "",
      dbName,
      tableName,
      tableType,
      comment.getOrElse(""))
    // Since HIVE-7575(Hive 2.0.0), adds 5 additional columns to the ResultSet of GetTables.
    if (HiveUtils.isHive23) {
      rowSet.addRow(rowData ++ Array(null, null, null, null, null))
    } else {
      rowSet.addRow(rowData)
    }
  }
}
