#!/bin/bash
# ============================================================
# NWP 生产环境变量配置
# 用法: source env-prod.sh
# 注意: 敏感配置需要从Vault或K8s Secret注入
# ============================================================

export NWP_ENV="prod"

# ============ JVM ============
export JAVA_HOME="/opt/jdk"
export MAVEN_HOME="/opt/maven"
export PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}"
export MAVEN_OPTS="-Xmx16g -XX:MaxMetaspaceSize=4g"

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
export NWP_CONFIG="/data/nwp/prod/config/application-prod.conf"
export NWP_DATA_DIR="/data/nwp/prod"
export NWP_OUTPUT_DIR="/data/nwp/prod/output"
export NWP_LOG_DIR="/data/nwp/prod/logs"

# ============ 网格参数（T213 全分辨率） ============
# 这些参数可以通过SLURM作业提交参数覆盖
export NWP_GRID_NX="720"
export NWP_GRID_NY="361"
export NWP_GRID_NZ="60"
export NWP_GRID_DX="0.5"
export NWP_GRID_DY="0.5"
export NWP_GRID_LAT_MIN="-90.0"
export NWP_GRID_LAT_MAX="90.0"
export NWP_GRID_LON_MIN="0.0"
export NWP_GRID_LON_MAX="360.0"

# ============ 动力求解器 ============
export NWP_DYN_DT="30"
export NWP_DYN_TRUNCATION="213"

# ============ 预报参数 ============
export NWP_FORECAST_HOURS="168"
export NWP_INIT_TIME=""
export NWP_RUN_MODE="forecast"
export NWP_RUN_DOMAIN="china"

# ============ HDFS ============
export NWP_HDFS_NAMENODE="hdfs://hadoop-prod:8020"
export NWP_OUTPUT_HDFS_PATH="/nwp/prod/output"
export HADOOP_USER_NAME="nwp"

# ============ 数据库 (敏感信息 - 从Vault注入) ============
export NWP_DB_URL="jdbc:postgresql://pg-prod:5432/nwp_metadata"
export NWP_DB_USER="nwp_prod"
# 注意: 不要在此文件中明文存储密码
# export NWP_DB_PASSWORD=""

# ============ Kafka (敏感信息 - 从Vault注入) ============
export NWP_KAFKA_BOOTSTRAP="kafka-prod-1:9093,kafka-prod-2:9093,kafka-prod-3:9093"
# 注意: 不要在此文件中明文存储凭证
# export NWP_KAFKA_USER=""
# export NWP_KAFKA_PASSWORD=""

# ============ 资料同化 ============
export NWP_ASSIM_BEC_FILE="/data/nwp/config/bec_t213.nc"

# ============ Spark 生产配置 ============
export SPARK_QUEUE="nwp"
export SPARK_EXECUTOR_CORES="8"
export SPARK_EXECUTOR_MEMORY="16g"
export SPARK_NUM_EXECUTORS="16"
export SPARK_DRIVER_CORES="4"
export SPARK_DRIVER_MEMORY="8g"
export SPARK_DEPLOY_MODE="cluster"

# ============ 日志级别 ============
export NWP_LOG_LEVEL="INFO"
export NWP_LOG_FILE="/data/nwp/prod/logs/nwp-prod.log"

# ============ Singularity 镜像 ============
export SIF_IMAGE="/nwp/images/nwp-solver-latest.sif"

echo "NWP PROD 环境变量已加载"
echo "  环境: ${NWP_ENV}"
echo "  Java: $(java -version 2>&1 | head -1)"
echo "  网格: ${NWP_GRID_NX}x${NWP_GRID_NY}x${NWP_GRID_NZ}"
echo "  HDFS: ${NWP_HDFS_NAMENODE}"
echo "  Spark队列: ${SPARK_QUEUE}"
