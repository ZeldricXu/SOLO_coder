#!/bin/bash
# ============================================================
# NWP 求解器入口脚本
# 处理命令行参数、环境变量、配置文件选择
# Spark on YARN 模式下自动提交到集群
# ============================================================

set -e

# 环境变量默认值
export JAVA_HOME="${JAVA_HOME:-/opt/jdk}"
export SPARK_HOME="${SPARK_HOME:-/opt/spark}"
export CONDA_HOME="${CONDA_HOME:-/opt/conda}"
export APP_HOME="${APP_HOME:-/app}"
export NWP_CONFIG="${NWP_CONFIG:-/config/application.conf}"
export NWP_ENV="${NWP_ENV:-dev}"
export PATH="${JAVA_HOME}/bin:${SPARK_HOME}/bin:${CONDA_HOME}/bin:${PATH}"

# 激活conda环境
if [ -f "${CONDA_HOME}/etc/profile.d/conda.sh" ]; then
    source "${CONDA_HOME}/etc/profile.d/conda.sh"
    conda activate nwp 2>/dev/null || true
fi

# 根据环境选择配置文件
select_config_file() {
    local env="${NWP_ENV}"
    local config_dir="/config"

    if [ -f "/config/application-${env}.conf" ]; then
        export NWP_CONFIG="/config/application-${env}.conf"
    elif [ -f "/config/application.conf" ]; then
        export NWP_CONFIG="/config/application.conf"
    fi
    echo "Using config: ${NWP_CONFIG}"
}

# 打印帮助信息
print_help() {
    cat << EOF
NWP Core Solver v1.0 - 数值天气预报核心求解器

用法:
  entrypoint.sh [--mode MODE] [OPTIONS]
  entrypoint.sh run [SPARK_SUBMIT_OPTIONS]

模式 (--mode):
  forecast        预报模式 (默认)
  assimilation    资料同化模式
  cycle           完整同化+预报循环
  benchmark       性能基准测试
  verify          预报检验
  visualize       后处理可视化
  test            模块自检

选项:
  -h, --help              显示帮助
  -e, --env ENV           运行环境: dev/staging/prod (默认: dev)
  -c, --config FILE       指定配置文件路径
  -i, --init FILE         初始场GRIB文件
  -t, --initTime TIME     起报时间 ISO格式 (默认: 当前时间)
  -f, --hours HOURS       预报小时数 (默认: 72)
  -d, --domain DOMAIN     区域: global/china/east-asia (默认: global)
  -o, --output DIR        输出目录
  -n, --nx NX             网格x方向格点数 (覆盖配置)
  -m, --ny NY             网格y方向格点数 (覆盖配置)
  -z, --nz NZ             网格垂直层数 (覆盖配置)
  --dt SECONDS            积分步长秒 (覆盖配置)
  --truncation T          谱截断波数 (覆盖配置)
  --parallel              启用Spark并行
  --yarn                  以Spark on YARN模式运行
  --hdfs                  启用HDFS存储
  --kafka                 启用Kafka协调
  --postgres              启用PostgreSQL元数据

环境变量:
  NWP_ENV                 运行环境 (dev/staging/prod)
  NWP_CONFIG              配置文件路径
  NWP_HDFS_NAMENODE       HDFS NameNode地址
  NWP_DB_URL              PostgreSQL连接URL
  NWP_DB_USER             PostgreSQL用户名
  NWP_DB_PASSWORD         PostgreSQL密码
  NWP_KAFKA_BOOTSTRAP     Kafka Bootstrap地址
  NWP_KAFKA_USER          Kafka SASL用户名
  NWP_KAFKA_PASSWORD      Kafka SASL密码
  NWP_GRID_NX             覆盖网格X格点数
  NWP_GRID_NY             覆盖网格Y格点数
  NWP_GRID_DX             覆盖网格X分辨率
  NWP_DYN_DT              覆盖积分步长
  NWP_DYN_TRUNCATION      覆盖谱截断波数
  NWP_FORECAST_HOURS      预报小时数
  NWP_INIT_TIME           起报时间
  SPARK_MASTER            Spark Master (yarn/local)
  HADOOP_USER_NAME        HDFS用户名

示例:
  # 开发环境 - 本地单线程24小时预报
  entrypoint.sh --mode forecast --env dev -f 24

  # 预发布环境 - Spark on YARN 48小时smoke test
  entrypoint.sh --mode forecast --env staging --yarn -f 48

  # 生产环境 - 168小时业务化预报，全分辨率
  entrypoint.sh --mode forecast --env prod --yarn \
      --nx 720 --ny 361 --nz 60 --dt 30 --truncation 213 \
      -f 168 --initTime 2024-01-01T00:00:00Z

  # 3D-Var资料同化
  entrypoint.sh --mode assimilation --env prod \
      --initTime 2024-01-01T00:00:00Z
EOF
}

# 解析命令行参数
ARGS=()
MODE="forecast"
RUN_YARN=false
JAVA_ARGS=()
SPARK_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            print_help
            exit 0
            ;;
        -e|--env)
            export NWP_ENV="$2"
            shift 2
            ;;
        -c|--config)
            export NWP_CONFIG="$2"
            shift 2
            ;;
        --mode)
            MODE="$2"
            shift 2
            ;;
        -i|--init)
            JAVA_ARGS+=("-i" "$2")
            shift 2
            ;;
        -t|--initTime)
            JAVA_ARGS+=("-t" "$2")
            export NWP_INIT_TIME="$2"
            shift 2
            ;;
        -f|--hours)
            JAVA_ARGS+=("-f" "$2")
            export NWP_FORECAST_HOURS="$2"
            shift 2
            ;;
        -d|--domain)
            JAVA_ARGS+=("-d" "$2")
            shift 2
            ;;
        -o|--output)
            JAVA_ARGS+=("-o" "$2")
            shift 2
            ;;
        -n|--nx)
            export NWP_GRID_NX="$2"
            shift 2
            ;;
        -m|--ny)
            export NWP_GRID_NY="$2"
            shift 2
            ;;
        -z|--nz)
            export NWP_GRID_NZ="$2"
            shift 2
            ;;
        --dt)
            export NWP_DYN_DT="$2"
            shift 2
            ;;
        --truncation)
            export NWP_DYN_TRUNCATION="$2"
            shift 2
            ;;
        --parallel)
            JAVA_ARGS+=("--parallel")
            shift
            ;;
        --yarn)
            RUN_YARN=true
            JAVA_ARGS+=("--parallel")
            shift
            ;;
        --hdfs)
            JAVA_ARGS+=("--hdfs")
            shift
            ;;
        --kafka)
            JAVA_ARGS+=("--kafka")
            shift
            ;;
        --postgres)
            JAVA_ARGS+=("--postgres")
            shift
            ;;
        --)
            shift
            break
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

select_config_file

echo "========================================================"
echo "NWP Core Solver v1.0"
echo "环境:   ${NWP_ENV}"
echo "模式:   ${MODE}"
echo "配置:   ${NWP_CONFIG}"
echo "Java:   $(java -version 2>&1 | head -1)"
echo "Spark:  ${SPARK_HOME}"
echo "========================================================"

# JVM参数
JVM_OPTS="-Xms4g -Xmx16g"
JVM_OPTS="${JVM_OPTS} -XX:MaxMetaspaceSize=1g"
JVM_OPTS="${JVM_OPTS} -XX:+UseG1GC"
JVM_OPTS="${JVM_OPTS} -XX:MaxGCPauseMillis=200"
JVM_OPTS="${JVM_OPTS} -Dcom.sun.management.jmxremote"
JVM_OPTS="${JVM_OPTS} -Dcom.sun.management.jmxremote.port=9010"
JVM_OPTS="${JVM_OPTS} -Dcom.sun.management.jmxremote.authenticate=false"
JVM_OPTS="${JVM_OPTS} -Dcom.sun.management.jmxremote.ssl=false"
JVM_OPTS="${JVM_OPTS} -Dfile.encoding=UTF-8"

# 环境变量传递给Java
for var in \
    NWP_ENV NWP_CONFIG NWP_HDFS_NAMENODE \
    NWP_DB_URL NWP_DB_USER NWP_DB_PASSWORD \
    NWP_KAFKA_BOOTSTRAP NWP_KAFKA_USER NWP_KAFKA_PASSWORD \
    NWP_GRID_NX NWP_GRID_NY NWP_GRID_NZ NWP_GRID_DX NWP_GRID_DY \
    NWP_DYN_DT NWP_DYN_TRUNCATION \
    NWP_FORECAST_HOURS NWP_INIT_TIME \
    HADOOP_USER_NAME SPARK_MASTER
do
    if [ -n "${!var}" ]; then
        JVM_OPTS="${JVM_OPTS} -D${var}=${!var}"
    fi
done

# 主JAR路径
JAR_FILE="${APP_HOME}/nwp-core-solver.jar"
if [ ! -f "${JAR_FILE}" ]; then
    JAR_FILE="${APP_HOME}/nwp-core-solver-with-deps.jar"
fi
if [ ! -f "${JAR_FILE}" ]; then
    echo "ERROR: JAR file not found in ${APP_HOME}"
    ls -la "${APP_HOME}/"
    exit 1
fi

# 构建完整的Java命令
JAVA_CMD=(
    "java"
    ${JVM_OPTS}
    "-jar" "${JAR_FILE}"
    "--mode" "${MODE}"
    "--config" "${NWP_CONFIG}"
    "${JAVA_ARGS[@]}"
    "${ARGS[@]}"
)

# Spark on YARN 模式
if [ "${RUN_YARN}" = true ]; then
    echo "运行模式: Spark on YARN"

    # Spark 提交参数
    SPARK_MASTER="${SPARK_MASTER:-yarn}"
    SPARK_DEPLOY_MODE="${SPARK_DEPLOY_MODE:-cluster}"
    SPARK_EXECUTOR_MEMORY="${SPARK_EXECUTOR_MEMORY:-16g}"
    SPARK_EXECUTOR_CORES="${SPARK_EXECUTOR_CORES:-8}"
    SPARK_NUM_EXECUTORS="${SPARK_NUM_EXECUTORS:-16}"
    SPARK_DRIVER_MEMORY="${SPARK_DRIVER_MEMORY:-8g}"
    SPARK_DRIVER_CORES="${SPARK_DRIVER_CORES:-4}"
    SPARK_QUEUE="${SPARK_QUEUE:-nwp}"
    SPARK_APP_NAME="${SPARK_APP_NAME:-NWP-${MODE^^}-${NWP_INIT_TIME:-$(date +%Y%m%d%H)}}"

    # Spark 默认配置文件
    if [ -f "/config/spark/spark-defaults.conf" ]; then
        export SPARK_CONF_DIR="/config/spark"
    fi

    # 构建 spark-submit 命令
    SPARK_CMD=(
        "${SPARK_HOME}/bin/spark-submit"
        "--master" "${SPARK_MASTER}"
        "--deploy-mode" "${SPARK_DEPLOY_MODE}"
        "--class" "com.meteorology.nwp.NWPMain"
        "--name" "${SPARK_APP_NAME}"
        "--queue" "${SPARK_QUEUE}"
        "--driver-memory" "${SPARK_DRIVER_MEMORY}"
        "--driver-cores" "${SPARK_DRIVER_CORES}"
        "--executor-memory" "${SPARK_EXECUTOR_MEMORY}"
        "--executor-cores" "${SPARK_EXECUTOR_CORES}"
        "--num-executors" "${SPARK_NUM_EXECUTORS}"
        "--conf" "spark.driver.extraJavaOptions=${JVM_OPTS}"
        "--conf" "spark.executor.extraJavaOptions=${JVM_OPTS}"
        "--conf" "spark.yarn.am.extraJavaOptions=${JVM_OPTS}"
        "--conf" "spark.dynamicAllocation.enabled=true"
        "--conf" "spark.dynamicAllocation.minExecutors=4"
        "--conf" "spark.dynamicAllocation.maxExecutors=64"
        "--conf" "spark.executor.memoryOverhead=4096"
        "--conf" "spark.driver.memoryOverhead=2048"
        "--conf" "spark.network.timeout=600s"
        "--conf" "spark.executor.heartbeatInterval=30s"
        "--conf" "spark.serializer=org.apache.spark.serializer.KryoSerializer"
        "--conf" "spark.kryo.unsafe=true"
        "--conf" "spark.shuffle.compress=true"
        "--conf" "spark.shuffle.spill.compress=true"
        "--files" "${NWP_CONFIG}"
        "--archives" "/opt/conda#conda"
        "--jars" "${JAR_FILE}"
        "${JAR_FILE}"
        "--mode" "${MODE}"
        "--config" "$(basename ${NWP_CONFIG})"
        "${JAVA_ARGS[@]}"
        "${ARGS[@]}"
    )

    echo "========================================================"
    echo "Spark Submit 命令:"
    echo "  App Name:   ${SPARK_APP_NAME}"
    echo "  Queue:      ${SPARK_QUEUE}"
    echo "  Executors:  ${SPARK_NUM_EXECUTORS} x ${SPARK_EXECUTOR_CORES} cores, ${SPARK_EXECUTOR_MEMORY}"
    echo "  Driver:     ${SPARK_DRIVER_CORES} cores, ${SPARK_DRIVER_MEMORY}"
    echo "========================================================"

    # 运行Spark
    echo "Starting Spark application..."
    exec "${SPARK_CMD[@]}"
else
    echo "运行模式: 本地单机"
    echo "========================================================"
    echo "命令:"
    echo "  ${JAVA_CMD[*]}"
    echo "========================================================"

    # 运行Java
    exec "${JAVA_CMD[@]}"
fi
