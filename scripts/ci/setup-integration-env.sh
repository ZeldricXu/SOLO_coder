#!/bin/bash
# ============================================================
# 集成测试环境启动脚本 - HDFS + Kafka 单点本地部署
# 用于 Jenkins 集成测试阶段，在测试机上启动临时 HDFS 和 Kafka
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
INTEGRATION_DIR="${PROJECT_ROOT}/integration-env"
PID_FILE="${INTEGRATION_DIR}/pids.log"
HADOOP_VERSION="3.3.6"
KAFKA_VERSION="2.13-3.6.1"
HADOOP_HOME="${INTEGRATION_DIR}/hadoop-${HADOOP_VERSION}"
KAFKA_HOME="${INTEGRATION_DIR}/kafka_${KAFKA_VERSION}"

HDFS_NAMENODE_PORT="${HDFS_NAMENODE_PORT:-9000}"
HDFS_WEB_UI_PORT="${HDFS_WEB_UI_PORT:-50070}"
KAFKA_BROKER_PORT="${KAFKA_BROKER_PORT:-9092}"
KAFKA_ZK_PORT="${KAFKA_ZK_PORT:-2181}"

mkdir -p "${INTEGRATION_DIR}/data" "${INTEGRATION_DIR}/logs"
rm -f "${PID_FILE}"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [INTEGRATION-ENV] $*"
}

check_ports() {
    local ports=($HDFS_NAMENODE_PORT $KAFKA_BROKER_PORT $KAFKA_ZK_PORT)
    for port in "${ports[@]}"; do
        if lsof -Pi ":${port}" -sTCP:LISTEN -t >/dev/null 2>&1; then
            log "端口 ${port} 已被占用，请先释放..."
            return 1
        fi
    done
    return 0
}

download_dependencies() {
    log "检查并下载依赖..."

    if [ ! -d "${HADOOP_HOME}" ]; then
        log "下载 Hadoop ${HADOOP_VERSION}..."
        (cd "${INTEGRATION_DIR}" && curl -sL \
            "https://archive.apache.org/dist/hadoop/common/hadoop-${HADOOP_VERSION}/hadoop-${HADOOP_VERSION}.tar.gz" \
            -o hadoop.tar.gz)
        (cd "${INTEGRATION_DIR}" && tar -xzf hadoop.tar.gz && rm hadoop.tar.gz)
    fi

    if [ ! -d "${KAFKA_HOME}" ]; then
        log "下载 Kafka ${KAFKA_VERSION}..."
        (cd "${INTEGRATION_DIR}" && curl -sL \
            "https://archive.apache.org/dist/kafka/3.6.1/kafka_${KAFKA_VERSION}.tgz" \
            -o kafka.tgz)
        (cd "${INTEGRATION_DIR}" && tar -xzf kafka.tgz && rm kafka.tgz)
    fi
}

configure_hadoop() {
    log "配置 Hadoop 单点..."

    cat > "${HADOOP_HOME}/etc/hadoop/core-site.xml" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property>
        <name>fs.defaultFS</name>
        <value>hdfs://localhost:9000</value>
    </property>
    <property>
        <name>hadoop.tmp.dir</name>
        <value>INTEGRATION_DIR_HERE/data/hadoop</value>
    </property>
</configuration>
EOF
    sed -i "s|INTEGRATION_DIR_HERE|${INTEGRATION_DIR}|g" "${HADOOP_HOME}/etc/hadoop/core-site.xml"

    cat > "${HADOOP_HOME}/etc/hadoop/hdfs-site.xml" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property>
        <name>dfs.replication</name>
        <value>1</value>
    </property>
    <property>
        <name>dfs.namenode.name.dir</name>
        <value>INTEGRATION_DIR_HERE/data/hdfs/namenode</value>
    </property>
    <property>
        <name>dfs.datanode.data.dir</name>
        <value>INTEGRATION_DIR_HERE/data/hdfs/datanode</value>
    </property>
    <property>
        <name>dfs.namenode.http-address</name>
        <value>localhost:50070</value>
    </property>
    <property>
        <name>dfs.permissions.enabled</name>
        <value>false</value>
    </property>
</configuration>
EOF
    sed -i "s|INTEGRATION_DIR_HERE|${INTEGRATION_DIR}|g" "${HADOOP_HOME}/etc/hadoop/hdfs-site.xml"

    mkdir -p "${INTEGRATION_DIR}/data/hdfs/namenode" "${INTEGRATION_DIR}/data/hdfs/datanode"

    export JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 | grep java.home | awk '{print $3}')"
    if [ ! -f "${INTEGRATION_DIR}/data/hdfs/namenode/current/VERSION" ]; then
        log "初始化 HDFS NameNode..."
        "${HADOOP_HOME}/bin/hdfs" namenode -format -force -nonInteractive
    fi
}

start_hdfs() {
    log "启动 HDFS..."
    export HADOOP_HOME
    export JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 | grep java.home | awk '{print $3}')"
    export PATH="${HADOOP_HOME}/bin:${HADOOP_HOME}/sbin:$PATH"

    nohup "${HADOOP_HOME}/bin/hdfs" namenode > "${INTEGRATION_DIR}/logs/hdfs-namenode.log" 2>&1 &
    echo $! >> "${PID_FILE}"

    sleep 3

    nohup "${HADOOP_HOME}/bin/hdfs" datanode > "${INTEGRATION_DIR}/logs/hdfs-datanode.log" 2>&1 &
    echo $! >> "${PID_FILE}"

    sleep 5

    "${HADOOP_HOME}/bin/hdfs" dfs -mkdir -p /nwp/data /nwp/output
    "${HADOOP_HOME}/bin/hdfs" dfs -chmod 777 /nwp /nwp/data /nwp/output
    log "HDFS 启动完成，Web UI: http://localhost:${HDFS_WEB_UI_PORT}"
}

configure_kafka() {
    log "配置 Kafka..."

    cat > "${KAFKA_HOME}/config/zookeeper.properties" << EOF
dataDir=${INTEGRATION_DIR}/data/zookeeper
clientPort=${KAFKA_ZK_PORT}
maxClientCnxns=0
tickTime=2000
initLimit=10
syncLimit=5
EOF

    cat > "${KAFKA_HOME}/config/server.properties" << EOF
broker.id=0
listeners=PLAINTEXT://localhost:${KAFKA_BROKER_PORT}
advertised.listeners=PLAINTEXT://localhost:${KAFKA_BROKER_PORT}
num.network.threads=3
num.io.threads=8
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600
log.dirs=${INTEGRATION_DIR}/data/kafka-logs
num.partitions=4
num.recovery.threads.per.data.dir=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
log.retention.hours=168
log.segment.bytes=1073741824
log.retention.check.interval.ms=300000
zookeeper.connect=localhost:${KAFKA_ZK_PORT}
zookeeper.connection.timeout.ms=18000
group.initial.rebalance.delay.ms=0
auto.create.topics.enable=true
EOF
}

start_kafka() {
    log "启动 Kafka..."
    export JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 | grep java.home | awk '{print $3}')"

    nohup "${KAFKA_HOME}/bin/zookeeper-server-start.sh" \
        "${KAFKA_HOME}/config/zookeeper.properties" \
        > "${INTEGRATION_DIR}/logs/zookeeper.log" 2>&1 &
    echo $! >> "${PID_FILE}"

    sleep 5

    nohup "${KAFKA_HOME}/bin/kafka-server-start.sh" \
        "${KAFKA_HOME}/config/server.properties" \
        > "${INTEGRATION_DIR}/logs/kafka.log" 2>&1 &
    echo $! >> "${PID_FILE}"

    sleep 8

    "${KAFKA_HOME}/bin/kafka-topics.sh" --create --bootstrap-server "localhost:${KAFKA_BROKER_PORT}" \
        --topic nwp_tasks --partitions 4 --replication-factor 1 2>/dev/null || true
    "${KAFKA_HOME}/bin/kafka-topics.sh" --create --bootstrap-server "localhost:${KAFKA_BROKER_PORT}" \
        --topic nwp_results --partitions 4 --replication-factor 1 2>/dev/null || true
    "${KAFKA_HOME}/bin/kafka-topics.sh" --create --bootstrap-server "localhost:${KAFKA_BROKER_PORT}" \
        --topic nwp_status --partitions 2 --replication-factor 1 2>/dev/null || true

    log "Kafka 启动完成，Broker: localhost:${KAFKA_BROKER_PORT}"
}

wait_for_services() {
    log "等待服务就绪..."
    local retries=20
    while [ $retries -gt 0 ]; do
        if "${HADOOP_HOME}/bin/hdfs" dfs -ls / >/dev/null 2>&1 && \
           "${KAFKA_HOME}/bin/kafka-topics.sh" --list --bootstrap-server "localhost:${KAFKA_BROKER_PORT}" >/dev/null 2>&1; then
            log "所有服务已就绪!"
            return 0
        fi
        sleep 3
        retries=$((retries - 1))
    done
    log "ERROR: 服务启动超时"
    return 1
}

print_env() {
    echo "========================================"
    echo "集成测试环境变量:"
    echo "  HDFS_NAMENODE: hdfs://localhost:${HDFS_NAMENODE_PORT}"
    echo "  HDFS_WEB_UI:   http://localhost:${HDFS_WEB_UI_PORT}"
    echo "  KAFKA_BROKER:  localhost:${KAFKA_BROKER_PORT}"
    echo "  ZOOKEEPER:     localhost:${KAFKA_ZK_PORT}"
    echo "========================================"
    echo "导出环境变量:"
    echo "  export NWP_STORAGE_HDFS_NAMENODE=\"hdfs://localhost:${HDFS_NAMENODE_PORT}\""
    echo "  export NWP_STORAGE_KAFKA_BOOTSTRAP_SERVERS=\"localhost:${KAFKA_BROKER_PORT}\""
    echo "  export NWP_STORAGE_HDFS_BASE_PATH=\"/nwp/data\""
    echo "========================================"
}

stop_services() {
    log "停止所有服务..."
    if [ -f "${PID_FILE}" ]; then
        while read -r pid; do
            if kill -0 "$pid" 2>/dev/null; then
                log "终止进程 $pid"
                kill -TERM "$pid" 2>/dev/null || true
            fi
        done < "${PID_FILE}"
        sleep 3
        while read -r pid; do
            if kill -0 "$pid" 2>/dev/null; then
                log "强制终止进程 $pid"
                kill -9 "$pid" 2>/dev/null || true
            fi
        done < "${PID_FILE}"
        rm -f "${PID_FILE}"
    fi

    log "清理临时数据（保留日志）..."
    rm -rf "${INTEGRATION_DIR}/data/hadoop"
    rm -rf "${INTEGRATION_DIR}/data/hdfs"
    rm -rf "${INTEGRATION_DIR}/data/zookeeper"
    rm -rf "${INTEGRATION_DIR}/data/kafka-logs"

    log "服务已停止"
}

case "${1:-start}" in
    start)
        check_ports
        download_dependencies
        configure_hadoop
        configure_kafka
        start_hdfs
        start_kafka
        wait_for_services
        print_env
        log "集成测试环境启动完成!"
        ;;
    stop)
        stop_services
        ;;
    restart)
        stop_services
        sleep 2
        check_ports
        start_hdfs
        start_kafka
        wait_for_services
        print_env
        ;;
    env)
        print_env
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|env}"
        exit 1
        ;;
esac
