#!/bin/bash

export SPARK_DIST_CLASSPATH=$SPARK_DIST_CLASSPATH:~/.ivy2/jars/*
sparkMaster="local"
thriftMode="$1"
ivyDir=<working directory of ivy.settings>/ivy.settings
geohikerVersion=1.2.53

help()
{
    echo "Start Thrift Server : $0 [start|stop] -master [spark-master] -port [thrift-port] -url [thrift-url]"
    echo " -m set spark master"
    echo " -p set port of spart thrift server"
    echo " -u set url of spark thrift server"
}


# 옵션이름 뒤에 :이 붙은 것은 값을 필요로 함을 의미합니다.
while getopts opt
do
    case $opt in

        m)
            sparkMaster=$OPTARG
            ;;
        p)
            hivePort=$OPTARG
            ;;
        u)
            hiveHost=$OPTARG
            ;;
        # 상단의  옵션이 아니면 도움말을 출력하고 종료합니다.
        *)
            echo "Invalid option ${opt}"
            help
            exit 0
            ;;
    esac
done

if [ -z "${GEOHIKER_HOME}" ]; then
  export GEOHIKER_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

echo "${thriftMode} Thrift Server"

shopt -s nocasematch
if [ "start" == "${thriftMode}" ]; then
  $GEOHIKER_HOME/sbin/datacore-start-thriftserver.sh \
  --master $sparkMaster \
  --packages io.dtonic.geohiker:geohiker-spark:${geohikerVersion},io.dtonic.geohiker:geohiker-datastore:${geohikerVersion} \
  --conf spark.jars.ivySettings=${ivyDir} \
  --conf spark.sql.extensions=io.dtonic.geohiker.spark.GeohikerSparkExtensions,io.delta.sql.DeltaSparkSessionExtension \
  --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog \
  $GEOHIKER_HOME/libs/Thrift-Server-1.0.jar
elif [ "stop" == "${thriftMode}" ]; then
  $GEOHIKER_HOME/sbin/datacore-stop-thriftserver.sh
else
  echo "Invalid mode ${thriftMode}"
  help
fi