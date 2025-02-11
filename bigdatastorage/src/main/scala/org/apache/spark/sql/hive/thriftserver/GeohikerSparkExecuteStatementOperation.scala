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

import org.apache.hadoop.hive.conf.HiveConf

import java.io.{BufferedWriter, File, FileWriter, IOException}
import java.security.PrivilegedExceptionAction
import java.sql.{Date, Timestamp}
import java.util.concurrent.RejectedExecutionException
import java.util.{Arrays, UUID, Map => JMap}
import org.apache.hadoop.hive.metastore.api.FieldSchema
import org.apache.hadoop.hive.shims.Utils
import org.apache.hive.service.cli._
import org.apache.hive.service.cli.operation.ExecuteStatementOperation
import org.apache.hive.service.cli.session.HiveSession
import org.apache.spark.SparkContext
import org.apache.spark.internal.Logging
import org.apache.spark.sql.execution.HiveResult
import org.apache.spark.sql.execution.command.SetCommand
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.sql.internal.{SQLConf, SharedState}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, GeometryUDT, Row => SparkRow}
import org.apache.spark.util.{Utils => SparkUtils}
import org.apache.spark.sql.GeohikerSqlSupport
import org.apache.spark.sql.SQLGeometryAnalysisFunctions

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.sys.process._
import scala.util.control.NonFatal


private[hive] class GeohikerSparkExecuteStatementOperation(
    parentSession: HiveSession,
    statement: String,
    confOverlay: JMap[String, String],
    runInBackground: Boolean = true)
    (hiveContext: HiveContext, sessionToActivePool: JMap[SessionHandle, String])
  extends ExecuteStatementOperation(parentSession, statement, confOverlay, runInBackground)
  with Logging {

  import hiveContext.sparkSession.implicits._

  GeohikerSqlSupport.init(hiveContext)

  private var result: DataFrame = _

  // We cache the returned rows to get iterators again in case the user wants to use FETCH_FIRST.
  // This is only used when `spark.sql.thriftServer.incrementalCollect` is set to `false`.
  // In case of `true`, this will be `None` and FETCH_FIRST will trigger re-execution.
  private var resultList: Option[Array[SparkRow]] = _

  private var iter: Iterator[SparkRow] = _
  private var dataTypes: Array[DataType] = _
  private var statementId: String = _

  private lazy val resultSchema: TableSchema = {
    if (result == null || result.schema.isEmpty) {
      new TableSchema(Arrays.asList(new FieldSchema("Result", "string", "")))
    } else {
      logInfo(s"Result Schema: ${result.schema}")
      GeohikerSparkExecuteStatementOperation.getTableSchema(result.schema)
    }
  }

  override def close(): Unit = {
    // RDDs will be cleaned automatically upon garbage collection.
    logDebug(s"CLOSING $statementId")
    cleanup(OperationState.CLOSED)
    hiveContext.sparkContext.clearJobGroup()
    GeohikerThriftServer.listener.onOperationClosed(statementId)
  }

  def addNonNullColumnValue(from: SparkRow, to: ArrayBuffer[Any], ordinal: Int) {
    dataTypes(ordinal) match {
      case StringType =>
        to += from.getString(ordinal)
      case IntegerType =>
        to += from.getInt(ordinal)
      case BooleanType =>
        to += from.getBoolean(ordinal)
      case DoubleType =>
        to += from.getDouble(ordinal)
      case FloatType =>
        to += from.getFloat(ordinal)
      case DecimalType() =>
        to += from.getDecimal(ordinal)
      case LongType =>
        to += from.getLong(ordinal)
      case ByteType =>
        to += from.getByte(ordinal)
      case ShortType =>
        to += from.getShort(ordinal)
      case DateType =>
        to += from.getAs[Date](ordinal)
      case TimestampType =>
        to += from.getAs[Timestamp](ordinal)
      case BinaryType =>
        to += from.getAs[Array[Byte]](ordinal)
      case _: ArrayType | _: StructType | _: MapType | _: UserDefinedType[_] =>
        val hiveString = HiveResult.toHiveString((from.get(ordinal), dataTypes(ordinal)))
        to += hiveString
    }
  }

  def getNextRowSet(order: FetchOrientation, maxRowsL: Long): RowSet = withSchedulerPool {
    validateDefaultFetchOrientation(order)
    assertState(OperationState.FINISHED)
    setHasResultSet(true)
    val resultRowSet: RowSet =
      ThriftserverShimUtils.resultRowSet(getResultSetSchema, getProtocolVersion)

    // Reset iter to header when fetching start from first row
    if (order.equals(FetchOrientation.FETCH_FIRST)) {
      iter = if (hiveContext.getConf(SQLConf.THRIFTSERVER_INCREMENTAL_COLLECT.key).toBoolean) {
        resultList = None
        result.toLocalIterator.asScala
      } else {
        if (resultList.isEmpty) {
          resultList = Some(result.collect())
        }
        resultList.get.iterator
      }
    }

    if (!iter.hasNext) {
      resultRowSet
    } else {
      // maxRowsL here typically maps to java.sql.Statement.getFetchSize, which is an int
      val maxRows = maxRowsL.toInt
      var curRow = 0
      while (curRow < maxRows && iter.hasNext) {
        val sparkRow = iter.next()
        val row = ArrayBuffer[Any]()
        var curCol = 0
        while (curCol < sparkRow.length) {
          if (sparkRow.isNullAt(curCol)) {
            row += null
          } else {
            addNonNullColumnValue(sparkRow, row, curCol)
          }
          curCol += 1
        }
        resultRowSet.addRow(row.toArray.asInstanceOf[Array[Object]])
        curRow += 1
      }
      resultRowSet
    }
  }

  def getResultSetSchema: TableSchema = resultSchema

  override def runInternal(): Unit = {
    setState(OperationState.PENDING)
    setHasResultSet(true) // avoid no resultset for async run

    if (!runInBackground) {
      execute()
    } else {
      val sparkServiceUGI = Utils.getUGI()

      // Runnable impl to call runInternal asynchronously,
      // from a different thread
      val backgroundOperation = new Runnable() {

        override def run(): Unit = {
          val doAsAction = new PrivilegedExceptionAction[Unit]() {
            override def run(): Unit = {
              registerCurrentOperationLog()
              try {
                execute()
              } catch {
                case e: HiveSQLException =>
                  setOperationException(e)
                  log.error("Error running hive query: ", e)
              }
            }
          }

          try {
            sparkServiceUGI.doAs(doAsAction)
          } catch {
            case e: Exception =>
              setOperationException(new HiveSQLException(e))
              logError("Error running hive query as user : " +
                sparkServiceUGI.getShortUserName(), e)
          }
        }
      }
      try {
        // This submit blocks if no background threads are available to run this operation
        val backgroundHandle =
          parentSession.getSessionManager().submitBackgroundOperation(backgroundOperation)
        setBackgroundHandle(backgroundHandle)
      } catch {
        case rejected: RejectedExecutionException =>
          setState(OperationState.ERROR)
          throw new HiveSQLException("The background threadpool cannot accept" +
            " new task for execution, please retry the operation", rejected)
        case NonFatal(e) =>
          logError(s"Error executing query in background", e)
          setState(OperationState.ERROR)
          throw new HiveSQLException(e)
      }
    }
  }

  private def execute(): Unit = withSchedulerPool {
    statementId = UUID.randomUUID().toString
    logInfo(s"Running query '$statement' with $statementId")

    try {

      logInfo(
        s"""
          |!!!! Dtonic test point 4 !!!!
          |query: $statement
          |""".stripMargin)

      setState(OperationState.RUNNING)

      // Always use the latest class loader provided by executionHive's state.
      val executionHiveClassLoader = hiveContext.sharedState.jarClassLoader
      Thread.currentThread().setContextClassLoader(executionHiveClassLoader)

      GeohikerThriftServer.listener.onStatementStart(
        statementId,
        parentSession.getSessionHandle.getSessionId.toString,
        statement,
        statementId,
        parentSession.getUsername)

      hiveContext.sparkContext.setJobGroup(statementId, statement)

      logInfo(statement)

      val tmp = hiveContext.sql(statement)

      result = tmp.select(tmp.schema.fields.map{ c =>
        c.dataType match {
          case _: ArrayType =>
            col(c.name).cast(StringType)
          case _: GeometryUDT =>
            SQLGeometryAnalysisFunctions.ST_ASJTSWKT(col(c.name))
          case _ =>
            col(c.name)
        }
      }: _*)


      logDebug(result.queryExecution.toString())
      result.queryExecution.logical match {
        case SetCommand(Some((SQLConf.THRIFTSERVER_POOL.key, Some(value)))) =>
          sessionToActivePool.put(parentSession.getSessionHandle, value)
          logInfo(s"Setting ${SparkContext.SPARK_SCHEDULER_POOL}=$value for future statements " +
            "in this session.")
        case _ =>
      }
      GeohikerThriftServer.listener.onStatementParsed(statementId, result.queryExecution.toString())
      iter = {
        if (hiveContext.getConf(SQLConf.THRIFTSERVER_INCREMENTAL_COLLECT.key).toBoolean) {
          resultList = None
          result.toLocalIterator.asScala
        } else {
          resultList = Some(result.collect())
          resultList.get.iterator
        }
      }
      dataTypes = result.queryExecution.analyzed.output.map(_.dataType).toArray
    } catch {
      case e: HiveSQLException =>
        if (getStatus().getState() == OperationState.CANCELED) {
          return
        } else {
          setState(OperationState.ERROR)
          GeohikerThriftServer.listener.onStatementError(
            statementId, e.getMessage, SparkUtils.exceptionString(e))
//          super.run()
          throw new HiveSQLException(e.toString)
        }
      // Actually do need to catch Throwable as some failures don't inherit from Exception and
      // HiveServer will silently swallow them.
      case e: Throwable =>
        val currentState = getStatus().getState()
        logError(s"Error executing query, currentState $currentState, ", e)
        setState(OperationState.ERROR)
        GeohikerThriftServer.listener.onStatementError(
          statementId, e.getMessage, SparkUtils.exceptionString(e))
//        super.run()
        throw new HiveSQLException(e.toString)
    }
    setState(OperationState.FINISHED)
    GeohikerThriftServer.listener.onStatementFinish(statementId)
  }

  override def cancel(): Unit = {
    logInfo(s"Cancel '$statement' with $statementId")
    cleanup(OperationState.CANCELED)
  }

  private def cleanup(state: OperationState) {
    setState(state)
    if (runInBackground) {
      val backgroundHandle = getBackgroundHandle()
      if (backgroundHandle != null) {
        backgroundHandle.cancel(true)
      }
    }
    if (statementId != null) {
      hiveContext.sparkContext.cancelJobGroup(statementId)
    }
  }

  private def withSchedulerPool[T](body: => T): T = {
    val pool = sessionToActivePool.get(parentSession.getSessionHandle)
    if (pool != null) {
      hiveContext.sparkContext.setLocalProperty(SparkContext.SPARK_SCHEDULER_POOL, pool)
    }
    try {
      body
    } finally {
      if (pool != null) {
        hiveContext.sparkContext.setLocalProperty(SparkContext.SPARK_SCHEDULER_POOL, null)
      }
    }
  }
}

object GeohikerSparkExecuteStatementOperation {
  def getTableSchema(structType: StructType): TableSchema = {
    val schema = structType.map { field =>
      val attrTypeString = if (field.dataType == NullType) "void" else field.dataType.catalogString
      new FieldSchema(field.name, attrTypeString, field.getComment.getOrElse(""))
    }
    new TableSchema(schema.asJava)
  }
}
