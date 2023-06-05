#!/bin/bash

export SPARK_DIST_CLASSPATH=$SPARK_DIST_CLASSPATH:~/.ivy2/jars/*
ivyDir=/usr/local/lib/ivy.settings
geohikerVersion=${GEOHIKER_VERSION}

if [ -z "${SPARK_HOME}" ]; then
  export SPARK_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

${SPARK_HOME}/bin/spark-submit \
--class org.apache.spark.sql.GeohikerExtensionsTest \
--master local \
--packages io.dtonic.geohiker:geohiker-spark:${geohikerVersion},io.dtonic.geohiker:geohiker-datastore:${geohikerVersion} \
--conf spark.jars.ivySettings=${ivyDir} \
${GEOHIKER_HOME}/libs/Thrift-Server-1.0.jar

echo "Dependency installation Succeed !"