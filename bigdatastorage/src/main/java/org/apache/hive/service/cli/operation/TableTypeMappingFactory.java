/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.service.cli.operation;

import org.apache.hive.service.cli.operation.ClassicTableTypeMapping;
import org.apache.hive.service.cli.operation.HiveTableTypeMapping;
import org.apache.hive.service.cli.operation.TableTypeMapping;

public class TableTypeMappingFactory {

  public enum TableTypeMappings {
    HIVE,
    CLASSIC
  }
  private static TableTypeMapping hiveTableTypeMapping = new HiveTableTypeMapping();
  private static TableTypeMapping classicTableTypeMapping = new ClassicTableTypeMapping();

  public static TableTypeMapping getTableTypeMapping(String mappingType) {
    if (TableTypeMappings.CLASSIC.toString().equalsIgnoreCase(mappingType)) {
      return classicTableTypeMapping;
    } else {
      return hiveTableTypeMapping;
    }
  }
}
