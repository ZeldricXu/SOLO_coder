#!/bin/bash
# ============================================================
# NWP 预发布环境变量配置
# 用法: source env-staging.sh
# ============================================================

export NWP_ENV="staging"

# ============ JVM ============
export JAVA_HOME="/opt/jdk"
export MAVEN_HOME="/opt/maven"
export PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}"
export MAVEN_OPTS="-Xmx8g -XX:MaxMetaspaceSize=2g"

# ============ Spark ============
export SPARK_HOME="/opt/spark"
export SPARK_MASTER="yarn"
export SPARK_CONF_DIR="/etc/spark/conf"
export PATH="${SPARK_HOME}/bin:${SPARK_HOME}/sbin:${PATH}"

# ============ Conda ============
export CONDA_HOME="/opt/conda"
export PATH="${CONDA_HOME}/bin:${PATH}"

# ============ Hadoop ============
export HADOOP_HOME="/opt/hadoop"
export HADOOP_CONF_DIR="/etc/hadoop/conf"
export YARN_CONF_DIR="/etc/hadoop/conf"
export PATH="${HADOOP_HOME}/bin:${HADOOP_HOME}/sbin:${PATH}"

# ============ 应用配置 ============
export APP_HOME="/app"
export NWP_CONFIG="/data/nwp/staging/config/application-staging.conf"
export NWP_DATA_DIR="/data/nwp/staging"
export NWP_OUTPUT_DIR="/data/nwp/staging/output"
export NWP_LOG_DIR="/data/nwp/staging/logs"

# ============ 网格参数（T42 分辨率） ============
export NWP_GRID_NX="256"
export NWP_GRID_NY="129"
export NWP_GRID_NZ="20"
export NWP_GRID_DX="1.40625"
export NWP_GRID_DY="1.40625"

# ============ 动力求解器 ============
export NWP_DYN_DT="90"
export NWP_DYN_TRUNCATION="128"

# ============ 预报参数 ============
export NWP_FORECAST_HOURS="48"
export NWP_INIT_TIME=""
export NWP_RUN_DOMAIN="china"

# ============ HDFS ============
export NWP_HDFS_NAMENODE="hdfs://hadoop-staging:9000"
export HADOOP_USER_NAME="nwp"

# ============ 数据库 ============
export NWP_DB_URL="jdbc:postgresql://pg-staging:5432/nwp_metadata"
export NWP_DB_USER="nwp_staging"
# 敏感信息 - 请在实际部署时从Vault或环境变量注入
# export NWP_DB_PASSWORD=""

# ============ Kafka ============
export NWP_KAFKA_BOOTSTRAP="kafka-staging-1:9093,kafka-staging-2:9093"
# 敏感信息 - 请在实际部署时从Vault或环境变量注入
# export NWP_KAFKA_USER=""
# export NWP_KAFKA_PASSWORD=""

# ============ Spark 队列 ============
export SPARK_QUEUE="nwp-staging"

echo "NWP STAGING 环境变量已加载"
echo "  环境: ${NWP_ENV}"
echo "  Java: $(java -version 2>&1 | head -1)"
echo "  网格: ${NWP_GRID_NX}x${NWP_GRID_NY}x${NWP_GRID_NZ}"
echo "  HDFS: ${NWP_HDFS_NAMENODE}"
