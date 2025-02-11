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

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hive.service.cli.thrift.{ThriftBinaryCLIService, ThriftHttpCLIService}
import org.apache.hive.service.server.HiveServer2
import org.apache.spark.SparkContext
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.UI.UI_ENABLED
import org.apache.spark.scheduler.{SparkListener, SparkListenerApplicationEnd, SparkListenerJobStart}
import org.apache.spark.sql.GeohikerSqlSupport
import org.apache.spark.sql.hive.{HiveContext, HiveUtils}
import org.apache.spark.sql.hive.thriftserver.GeohikerReflectionUtils._
import org.apache.spark.sql.hive.thriftserver.ui.GeohikerThriftServerTab
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.{ShutdownHookManager, Utils}

/**
 * The main entry point for the Spark SQL port of HiveServer2.  Starts up a `SparkHiveContext` and a
 * `GeohikerThriftServer` thrift server.
 */
object GeohikerThriftServer extends Logging {
  var uiTab: Option[GeohikerThriftServerTab] = None
  var listener: GeohikerThriftServerListener = _

  /**
   * :: DeveloperApi ::
   * Starts a new thrift server with the given context.
   */
  @DeveloperApi
  def startWithContext(hiveContext: HiveContext): Unit = {
    GeohikerSqlSupport.init(hiveContext)

    val server = new GeohikerThriftServer(hiveContext)

    val executionHive = HiveUtils.newClientForExecution(
      hiveContext.sparkContext.conf,
      hiveContext.sessionState.newHadoopConf())
    server.init(executionHive.conf)
    server.start()
    listener = new GeohikerThriftServerListener(server, hiveContext.conf)
    hiveContext.sparkContext.addSparkListener(listener)
    uiTab = if (hiveContext.sparkContext.getConf.get(UI_ENABLED)) {
      Some(new GeohikerThriftServerTab(hiveContext.sparkContext))
    } else {
      None
    }
  }

  def main(args: Array[String]) {
    // If the arguments contains "-h" or "--help", print out the usage and exit.
    if (args.contains("-h") || args.contains("--help")) {
      HiveServer2.main(args)
      // The following code should not be reachable. It is added to ensure the main function exits.
      return
    }

    Utils.initDaemon(log)
    val optionsProcessor = new HiveServer2.ServerOptionsProcessor("GeohikerThriftServer")
    optionsProcessor.parse(args)

    logInfo("Starting SparkContext")
    GeohikerSparkSQLEnv.init()

    ShutdownHookManager.addShutdownHook { () =>
      GeohikerSparkSQLEnv.stop()
      uiTab.foreach(_.detach())
    }

    val executionHive = HiveUtils.newClientForExecution(
      GeohikerSparkSQLEnv.hiveContext.sparkContext.conf,
      GeohikerSparkSQLEnv.hiveContext.sessionState.newHadoopConf())

    try {
      val server = new GeohikerThriftServer(GeohikerSparkSQLEnv.hiveContext)
      server.init(executionHive.conf)
      server.start()
      logInfo("GeohikerThriftServer started")
      listener = new GeohikerThriftServerListener(server, GeohikerSparkSQLEnv.hiveContext.conf)
      GeohikerSparkSQLEnv.sparkContext.addSparkListener(listener)
      uiTab = if (GeohikerSparkSQLEnv.sparkContext.getConf.get(UI_ENABLED)) {
        Some(new GeohikerThriftServerTab(GeohikerSparkSQLEnv.sparkContext))
      } else {
        None
      }
      // If application was killed before GeohikerThriftServer start successfully then SparkSubmit
      // process can not exit, so check whether if SparkContext was stopped.
      if (GeohikerSparkSQLEnv.sparkContext.stopped.get()) {
        logError("SparkContext has stopped even if HiveServer2 has started, so exit")
        System.exit(-1)
      }
    } catch {
      case e: Exception =>
        logError("Error starting GeohikerThriftServer", e)
        System.exit(-1)
    }
  }

  class GeohikerSessionInfo(
                                           val sessionId: String,
                                           val startTimestamp: Long,
                                           val ip: String,
                                           val userName: String) {
    var finishTimestamp: Long = 0L
    var totalExecution: Int = 0
    def totalTime: Long = {
      if (finishTimestamp == 0L) {
        System.currentTimeMillis - startTimestamp
      } else {
        finishTimestamp - startTimestamp
      }
    }
  }

  object GeohikerExecutionState extends Enumeration {
    val STARTED, COMPILED, FAILED, FINISHED, CLOSED = Value
    type ExecutionState = Value
  }

  class GeohikerExecutionInfo(
                                             val statement: String,
                                             val sessionId: String,
                                             val startTimestamp: Long,
                                             val userName: String) {
    var finishTimestamp: Long = 0L
    var closeTimestamp: Long = 0L
    var executePlan: String = ""
    var detail: String = ""
    var state: GeohikerExecutionState.Value = GeohikerExecutionState.STARTED
    val jobId: ArrayBuffer[String] = ArrayBuffer[String]()
    var groupId: String = ""
    def totalTime(endTime: Long): Long = {
      if (endTime == 0L) {
        System.currentTimeMillis - startTimestamp
      } else {
        endTime - startTimestamp
      }
    }
  }


  /**
   * An inner sparkListener called in sc.stop to clean up the GeohikerThriftServer
   */
  private[thriftserver] class GeohikerThriftServerListener(
                                                         val server: HiveServer2,
                                                         val conf: SQLConf) extends SparkListener {

    override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd): Unit = {
      server.stop()
    }
    private var onlineSessionNum: Int = 0
    private val sessionList = new mutable.LinkedHashMap[String, GeohikerSessionInfo]
    private val executionList = new mutable.LinkedHashMap[String, GeohikerExecutionInfo]
    private val retainedStatements = conf.getConf(SQLConf.THRIFTSERVER_UI_STATEMENT_LIMIT)
    private val retainedSessions = conf.getConf(SQLConf.THRIFTSERVER_UI_SESSION_LIMIT)
    private var totalRunning = 0

    def getOnlineSessionNum: Int = synchronized { onlineSessionNum }

    def getTotalRunning: Int = synchronized { totalRunning }

    def getSessionList: Seq[GeohikerSessionInfo] = synchronized { sessionList.values.toSeq }

    def getSession(sessionId: String): Option[GeohikerSessionInfo] = synchronized {
      sessionList.get(sessionId)
    }

    def getExecutionList: Seq[GeohikerExecutionInfo] = synchronized { executionList.values.toSeq }

    override def onJobStart(jobStart: SparkListenerJobStart): Unit = synchronized {
      for {
        props <- Option(jobStart.properties)
        groupId <- Option(props.getProperty(SparkContext.SPARK_JOB_GROUP_ID))
        (_, info) <- executionList if info.groupId == groupId
      } {
        info.jobId += jobStart.jobId.toString
        info.groupId = groupId
      }
    }

    def onSessionCreated(ip: String, sessionId: String, userName: String = "UNKNOWN"): Unit = {
      synchronized {
        val info = new GeohikerSessionInfo(sessionId, System.currentTimeMillis, ip, userName)
        sessionList.put(sessionId, info)
        onlineSessionNum += 1
        trimSessionIfNecessary()
      }
    }

    def onSessionClosed(sessionId: String): Unit = synchronized {
      sessionList(sessionId).finishTimestamp = System.currentTimeMillis
      onlineSessionNum -= 1
      trimSessionIfNecessary()
    }

    def onStatementStart(
                          id: String,
                          sessionId: String,
                          statement: String,
                          groupId: String,
                          userName: String = "UNKNOWN"): Unit = synchronized {
      val info = new GeohikerExecutionInfo(statement, sessionId, System.currentTimeMillis, userName)
      info.state = GeohikerExecutionState.STARTED
      executionList.put(id, info)
      trimExecutionIfNecessary()
      sessionList(sessionId).totalExecution += 1
      executionList(id).groupId = groupId
      totalRunning += 1
    }

    def onStatementParsed(id: String, executionPlan: String): Unit = synchronized {
      executionList(id).executePlan = executionPlan
      executionList(id).state = GeohikerExecutionState.COMPILED
    }

    def onStatementError(id: String, errorMessage: String, errorTrace: String): Unit = {
      synchronized {
        executionList(id).finishTimestamp = System.currentTimeMillis
        executionList(id).detail = errorMessage
        executionList(id).state = GeohikerExecutionState.FAILED
        totalRunning -= 1
        trimExecutionIfNecessary()
      }
    }

    def onStatementFinish(id: String): Unit = synchronized {
      executionList(id).finishTimestamp = System.currentTimeMillis
      executionList(id).state = GeohikerExecutionState.FINISHED
      totalRunning -= 1
      trimExecutionIfNecessary()
    }

    def onOperationClosed(id: String): Unit = synchronized {
      executionList(id).closeTimestamp = System.currentTimeMillis
      executionList(id).state = GeohikerExecutionState.CLOSED
    }

    private def trimExecutionIfNecessary() = {
      if (executionList.size > retainedStatements) {
        val toRemove = math.max(retainedStatements / 10, 1)
        executionList.filter(_._2.finishTimestamp != 0).take(toRemove).foreach { s =>
          executionList.remove(s._1)
        }
      }
    }

    private def trimSessionIfNecessary() = {
      if (sessionList.size > retainedSessions) {
        val toRemove = math.max(retainedSessions / 10, 1)
        sessionList.filter(_._2.finishTimestamp != 0).take(toRemove).foreach { s =>
          sessionList.remove(s._1)
        }
      }

    }
  }
}

private[hive] class GeohikerThriftServer(hiveContext: HiveContext)
  extends HiveServer2
    with GeohikerReflectedCompositeService {
  // state is tracked internally so that the server only attempts to shut down if it successfully
  // started, and then once only.
  private val started = new AtomicBoolean(false)

  override def init(hiveConf: HiveConf) {
    val sparkSqlCliService = new GeohikerSparkSQLCLIService(this, hiveContext)
    setSuperField(this, "cliService", sparkSqlCliService)
    addService(sparkSqlCliService)

    val thriftCliService = if (isHTTPTransportMode(hiveConf)) {
      new ThriftHttpCLIService(sparkSqlCliService)
    } else {
      new ThriftBinaryCLIService(sparkSqlCliService)
    }

    setSuperField(this, "thriftCLIService", thriftCliService)
    addService(thriftCliService)
    initCompositeService(hiveConf)
  }

  private def isHTTPTransportMode(hiveConf: HiveConf): Boolean = {
    val transportMode = hiveConf.getVar(ConfVars.HIVE_SERVER2_TRANSPORT_MODE)
    transportMode.toLowerCase(Locale.ROOT).equals("http")
  }


  override def start(): Unit = {
    super.start()
    started.set(true)
  }

  override def stop(): Unit = {
    if (started.getAndSet(false)) {
      super.stop()
    }
  }
}
