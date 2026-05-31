#!/bin/bash

set -e

echo "=========================================="
echo "  ChainETL Platform Starting..."
echo "  Java Version: $(java -version 2>&1 | head -1)"
echo "  Profile: ${SPRING_PROFILES_ACTIVE}"
echo "  JVM Options: ${JVM_OPTS}"
echo "=========================================="

DEFAULT_JVM_OPTS="\
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs/heapdump.hprof \
    -XX:+UseContainerSupport \
    -XX:InitialRAMPercentage=50.0 \
    -XX:MaxRAMPercentage=75.0 \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Shanghai \
    -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} \
    -Dlogging.path=/app/logs"

FINAL_JVM_OPTS="${JVM_OPTS} ${DEFAULT_JVM_OPTS}"

if [ -n "${JAVA_AGENT_OPTS}" ]; then
    FINAL_JVM_OPTS="${FINAL_JVM_OPTS} ${JAVA_AGENT_OPTS}"
fi

if [ "$1" = "java" ]; then
    exec "$@"
fi

exec java ${FINAL_JVM_OPTS} -jar app.jar "$@"
