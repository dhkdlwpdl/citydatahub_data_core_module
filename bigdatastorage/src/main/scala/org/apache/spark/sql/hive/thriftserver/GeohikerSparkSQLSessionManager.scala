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

import java.util.concurrent.Executors

import org.apache.commons.logging.Log
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hive.service.cli.SessionHandle
import org.apache.hive.service.cli.session.SessionManager
import org.apache.hive.service.server.HiveServer2

import org.apache.spark.sql.hive.{HiveContext, HiveUtils}
import org.apache.spark.sql.hive.thriftserver.GeohikerReflectionUtils._
import org.apache.spark.sql.hive.thriftserver.server.GeohikerSparkSQLOperationManager


private[hive] class GeohikerSparkSQLSessionManager(hiveServer: HiveServer2, hiveContext: HiveContext)
  extends SessionManager(hiveServer)
  with GeohikerReflectedCompositeService {

  private lazy val sparkSqlOperationManager = new GeohikerSparkSQLOperationManager()

  override def init(hiveConf: HiveConf) {
    setSuperField(this, "operationManager", sparkSqlOperationManager)
    super.init(hiveConf)
  }

  override def openSession(
      protocol: ThriftserverShimUtils.TProtocolVersion,
      username: String,
      passwd: String,
      ipAddress: String,
      sessionConf: java.util.Map[String, String],
      withImpersonation: Boolean,
      delegationToken: String): SessionHandle = {
    val sessionHandle =
      super.openSession(protocol, username, passwd, ipAddress, sessionConf, withImpersonation,
          delegationToken)
    val session = super.getSession(sessionHandle)
    GeohikerThriftServer.listener.onSessionCreated(
      session.getIpAddress, sessionHandle.getSessionId.toString, session.getUsername)
    val ctx = if (hiveContext.conf.hiveThriftServerSingleSession) {
      hiveContext
    } else {
      hiveContext.newSession()
    }
    ctx.setConf(HiveUtils.FAKE_HIVE_VERSION.key, HiveUtils.builtinHiveVersion)
    if (sessionConf != null && sessionConf.containsKey("use:database")) {
      ctx.sql(s"use ${sessionConf.get("use:database")}")
    }
    sparkSqlOperationManager.sessionToContexts.put(sessionHandle, ctx)
    sessionHandle
  }

  override def closeSession(sessionHandle: SessionHandle) {
    GeohikerThriftServer.listener.onSessionClosed(sessionHandle.getSessionId.toString)
    super.closeSession(sessionHandle)
    sparkSqlOperationManager.sessionToActivePool.remove(sessionHandle)
    sparkSqlOperationManager.sessionToContexts.remove(sessionHandle)
  }
}
