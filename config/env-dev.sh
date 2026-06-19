#!/bin/bash
# ============================================================
# NWP 开发环境变量配置
# 用法: source env-dev.sh
# ============================================================

export NWP_ENV="dev"

# ============ JVM ============
export JAVA_HOME="/opt/jdk"
export MAVEN_HOME="/opt/maven"
export PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}"
export MAVEN_OPTS="-Xmx4g -XX:MaxMetaspaceSize=1g"

# ============ Spark ============
export SPARK_HOME="/opt/spark"
export SPARK_MASTER="local"
export PATH="${SPARK_HOME}/bin:${SPARK_HOME}/sbin:${PATH}"

# ============ Conda ============
export CONDA_HOME="/opt/conda"
export PATH="${CONDA_HOME}/bin:${PATH}"

# ============ 应用配置 ============
export APP_HOME="/app"
export NWP_CONFIG="${APP_HOME}/config/application-dev.conf"
export NWP_DATA_DIR="/data/nwp/dev"
export NWP_OUTPUT_DIR="./output/dev"
export NWP_LOG_DIR="./logs/dev"

# ============ 网格参数（覆盖配置） ============
export NWP_GRID_NX="180"
export NWP_GRID_NY="91"
export NWP_GRID_NZ="10"
export NWP_GRID_DX="2.0"
export NWP_GRID_DY="2.0"

# ============ 动力求解器 ============
export NWP_DYN_DT="120"
export NWP_DYN_TRUNCATION="85"

# ============ 预报参数 ============
export NWP_FORECAST_HOURS="24"
export NWP_INIT_TIME=""
export NWP_RUN_DOMAIN="global"

# ============ HDFS (开发环境用本地) ============
export NWP_HDFS_NAMENODE="file:///tmp/nwp-dev-hdfs"

# ============ 数据库 (开发环境禁用) ============
export NWP_DB_URL="jdbc:postgresql://localhost:5432/nwp_metadata"
export NWP_DB_USER="nwp"
export NWP_DB_PASSWORD="nwp_password"

# ============ Kafka (开发环境禁用) ============
export NWP_KAFKA_BOOTSTRAP="localhost:9092"
export NWP_KAFKA_USER=""
export NWP_KAFKA_PASSWORD=""

# ============ HDFS 用户 ============
export HADOOP_USER_NAME="nwp"

# ============ 测试 ============
export SKIP_INTEGRATION="1"
export SKIP_SINGULARITY="1"

echo "NWP DEV 环境变量已加载"
echo "  环境: ${NWP_ENV}"
echo "  Java: $(java -version 2>&1 | head -1)"
echo "  网格: ${NWP_GRID_NX}x${NWP_GRID_NY}x${NWP_GRID_NZ}"
