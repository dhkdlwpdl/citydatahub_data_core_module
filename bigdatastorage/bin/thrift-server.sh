#!/bin/bash

export SPARK_DIST_CLASSPATH=$SPARK_DIST_CLASSPATH:~/.ivy2/jars/*
sparkMaster="local"
thriftMode="$1"
ivyDir=/ivy.settings
geohikerVersion=1.2.56

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
  ${GEOHIKER_HOME}/sbin/datacore-start-thriftserver.sh \
  --master ${sparkMaster} \
  --packages io.dtonic.geohiker:geohiker-spark:${geohikerVersion},io.dtonic.geohiker:geohiker-datastore:${geohikerVersion} \
  --conf spark.jars.ivySettings=${ivyDir} \
  ${GEOHIKER_HOME}/libs/Thrift-Server-1.0.jar
elif [ "stop" == "${thriftMode}" ]; then
  ${GEOHIKER_HOME}/sbin/datacore-stop-thriftserver.sh
else
  echo "Invalid mode ${thriftMode}"
fi
