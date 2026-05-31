#!/usr/bin/env bash

set -e

ENV=${1:-dev}

echo "=========================================="
echo "  启动 SmartFlow 应用"
echo "=========================================="
echo "环境: $ENV"
echo ""

# 检查 JAR 文件
JAR_FILE=$(ls smartflow-boot/target/smartflow-boot-*.jar | head -1)

if [ ! -f "$JAR_FILE" ]; then
    echo "❌ 未找到 JAR 文件，请先运行: ./scripts/build.sh $ENV"
    exit 1
fi

# 设置 JVM 参数
JVM_OPTS="
    -Xms512m
    -Xmx1024m
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:HeapDumpPath=logs/
    -Djava.security.egd=file:/dev/./urandom
    -Dspring.profiles.active=$ENV
    -Dcom.sun.management.jmxremote
    -Dcom.sun.management.jmxremote.port=9010
    -Dcom.sun.management.jmxremote.rmi.port=9010
    -Dcom.sun.management.jmxremote.authenticate=false
    -Dcom.sun.management.jmxremote.ssl=false
"

# 创建日志目录
mkdir -p logs

echo "🚀 启动应用..."
echo "JVM 参数: $JVM_OPTS"
echo ""

# 启动应用
java $JVM_OPTS -jar "$JAR_FILE"
