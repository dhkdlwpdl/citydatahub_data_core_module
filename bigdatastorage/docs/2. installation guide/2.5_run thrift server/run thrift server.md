# 2.5 Thrift 서버 실행 전 준비

- /usr/local/lib 하위에 ivy.settings 파일 생성 

  ```bash
  cd /usr/local/lib
  vi ivy.settings
  ```

- 생성한 ivy.settings 파일에 아래의 내용 추가

  ```xml
  <ivysettings>
  <settings defaultBranch="${ivy.deliver.branch}" defaultResolver="default-chain" />
  <property name ="osgeo"  value="https://repo.osgeo.org/repository/release/"/>
  <property name ="jboss" value="https://repository.jboss.org/nexus/congent/repositories/thirdparty-releases/"/>
  <property name="nexus-dtonic" value="https://nexus.dtonic.io/repository/maven-public"/>
  <resolvers>
  <ibiblio name="nexus" m2compatible="true" root="${nexus=dtonioc}"/>
  </resolvers>
  <resolvers>
  <ibiblio name="central" m2compatible="true"/>
  <ibiblio name="nexus" m2compatible="true" root="${nexus-dtonic}"/>
  <ibiblio name="jboss" m2compatible="true" root="${jboss}"/>
  <ibiblio name="os-geo" m2compatible="true" root="${osgeo}"/>
  <chain name="default-chain">
  <resolver ref="nexus" />
  <!--resolver ref="central" /-->
  <!--resolver ref="jboss" /-->
  <!--resolver ref="os-geo" /-->
  </chain>
  </resolvers>
  </ivysettings>
  ```

<br/>

- $THRIFT_HOME/bin/thrift-server.sh 파일에서 ivy.settings 파일 경로 수정

  ```bash
  vi $THRIFT_HOME/bin/thrift-server.sh

  # ivy.settings 파일 내 아래 내용에 대해 수정
  ...
  ivyDir=/usr/local/lib/ivy.settings
  ...
  ```

<br/>


# 2.6 Thrift 서버 실행

  ```bash
  $THRIFT_HOME/bin/thrift-server.sh start
  ```

<br/>

Thrift 서버를 최초로 실행하는 경우에는 아래 명령을 통해 Thrift 서버가 10000포트로 붙은 것을 확인한 후에 Thrift 서버를 재시작해야 합니다. (최초 서버 실행 시)

  ```bash
  # Thrift 서버가 제대로 완전히 올라갔는지 확인
  netstat -tnlp | grep 10000

  # Thrift 서버 재시작
  $THRIFT_HOME/bin/thrift-server.sh stop
  $THRIFT_HOME/bin/thrift-server.sh start
  ```

<br/>

- (선택) Geohiker 버전을 업데이트 하기 위해서는 `$THRIFT_HOME/bin/thrift-server.sh` 파일 내 스크립트의 상단 부분에 있는 geohikerVersion의 설정값을 수정해주시기 바랍니다.
  - Default version: 1.2.56 (Verified version as of May 10, 2023)
  ```aidl
  geohikerVersion=1.2.56
  ```

<br/>

## 2.5.1 Beeline을 통해 Thrift 서버 접속 및 테스트

`$THRIFT_HOME/bin/thrift-server.sh start` 명령을 통해 Thrift 서버를 시작한 후에 완전히 서버가 올라가기까지 시간이 소요되기 때문에 약간의 시간을 두고 Beeline 접속을 해주시기 바랍니다.

<br/>

- Beeline 접속
  ```bash
  $SPARK_HOME/bin/beeline
  ```

<br/>

- 접속한 Beeline에서 Thrift 서버 접속 및 테스트 (테이블 생성 및 데이터 적재)

  아래에서 beeline을 통해 Thrift 서버에 접속 시 입력되는 계정 정보는 HDFS에 데이터를 적재하기 위해 필요한 계정 정보입니다.

  username과 password에 아무 값도 입력되지 않을 경우, anonymous로 데이터가 적재됩니다.
  
<br/>

  ```bash
  beeline> !connect jdbc:hive2://localhost:10000/default
  Connecting to jdbc:hive2://localhost:10000/default
  Enter username for jdbc:hive2://localhost:10000:
  Enter password for jdbc:hive2://localhost:10000:

  # Thrift 서버 접속
  # 테스트 테이블 생성
  0: jdbc:hive2://localhost:10000/default> CREATE TABLE test_table (id INT, name VARCHAR(50));

  # 생성된 test_table 테이블 확인
  0: jdbc:hive2://localhost:10000/default> SHOW TABLES;

  # 생성한 테이블에 데이터 삽입
  0: jdbc:hive2://localhost:10000/default> INSERT INTO test_table VALUES(1, 'lee');

  # 삽입한 데이터 확인
  0: jdbc:hive2://localhost:10000/default> SELECT * FROM test_table;
  ```