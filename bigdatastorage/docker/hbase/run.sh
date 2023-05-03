#!/bin/bash
#while true
#do
#  nc -z hadoop 9000 && break
#  echo "Waiting for namenode to be ready..."
#  sleep 1
#done

/opt/hbase-$HBASE_VERSION/bin/start-hbase.sh
tail -f /opt/hbase-$HBASE_VERSION/logs/*
