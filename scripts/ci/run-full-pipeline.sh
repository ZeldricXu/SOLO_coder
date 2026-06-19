#!/bin/bash
# ============================================================
# 完整CI流程入口脚本 - 用于无Jenkins环境时手动执行
# 用法: bash scripts/ci/run-full-pipeline.sh
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${PROJECT_ROOT}"

export MAVEN_OPTS="-Xmx4g -XX:MaxMetaspaceSize=1g"
export JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which java))))}"
export PATH="${JAVA_HOME}/bin:${PATH}"

LOG_DIR="${PROJECT_ROOT}/logs"
REPORT_DIR="${PROJECT_ROOT}/test-reports"
mkdir -p "${LOG_DIR}" "${REPORT_DIR}/unit" "${REPORT_DIR}/integration" "${REPORT_DIR}/coverage"

TIMESTAMP="$(date +%Y%m%d%H%M%S)"
PIPELINE_LOG="${LOG_DIR}/pipeline-${TIMESTAMP}.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [PIPELINE] $*" | tee -a "${PIPELINE_LOG}"
}

log "============================================="
log "NWP 核心求解器 CI 流水线启动"
log "时间: ${TIMESTAMP}"
log "============================================="

STAGE_COMPILE_PASS=0
STAGE_UNIT_PASS=0
STAGE_INTEGRATION_PASS=0
STAGE_PACKAGE_PASS=0

# ======================
# Stage 1: Compile
# ======================
log "[STAGE 1/4] 开始编译..."
if mvn clean compile -DskipTests \
        -Dmaven.compiler.source=21 \
        -Dmaven.compiler.target=21 \
        -B 2>&1 | tee "${LOG_DIR}/compile-${TIMESTAMP}.log"; then
    STAGE_COMPILE_PASS=1
    log "[STAGE 1/4] ✅ 编译通过"
else
    log "[STAGE 1/4] ❌ 编译失败"
fi

if [ "${STAGE_COMPILE_PASS}" -ne 1 ]; then
    log "流水线失败，后续阶段跳过"
    exit 1
fi

# ======================
# Stage 2: Unit Tests
# ======================
log "[STAGE 2/4] 开始单元测试..."
if mvn test -P unit-tests \
        -Dtest="*Test,*Tests,!Integration*" \
        -DfailIfNoTests=false \
        -Dmaven.compiler.source=21 \
        -Dmaven.compiler.target=21 \
        -B 2>&1 | tee "${LOG_DIR}/unit-test-${TIMESTAMP}.log"; then
    STAGE_UNIT_PASS=1
    mkdir -p "${REPORT_DIR}/unit"
    cp -r target/surefire-reports/*.xml "${REPORT_DIR}/unit/" 2>/dev/null || true
    log "[STAGE 2/4] ✅ 单元测试通过"
else
    log "[STAGE 2/4] ❌ 单元测试失败"
    mkdir -p "${REPORT_DIR}/unit"
    cp -r target/surefire-reports/*.xml "${REPORT_DIR}/unit/" 2>/dev/null || true
fi

# ======================
# Stage 3: Integration Tests
# ======================
if [ "${STAGE_UNIT_PASS}" -eq 1 ] && [ "${SKIP_INTEGRATION:-0}" -ne 1 ]; then
    log "[STAGE 3/4] 开始集成测试..."

    log "  启动 HDFS + Kafka 测试环境..."
    if bash scripts/ci/setup-integration-env.sh start 2>&1 | tee -a "${PIPELINE_LOG}"; then
        log "  测试环境启动成功"

        if mvn test -P integration-tests \
                -Dtest="Integration*Test,Storage*Test,Parallel*Test" \
                -DfailIfNoTests=false \
                -Dnwp.storage.hdfs.namenode="hdfs://localhost:9000" \
                -Dnwp.storage.kafka.bootstrap-servers="localhost:9092" \
                -Dmaven.compiler.source=21 \
                -Dmaven.compiler.target=21 \
                -B 2>&1 | tee "${LOG_DIR}/integration-test-${TIMESTAMP}.log"; then
            STAGE_INTEGRATION_PASS=1
            mkdir -p "${REPORT_DIR}/integration"
            cp -r target/surefire-reports/*.xml "${REPORT_DIR}/integration/" 2>/dev/null || true
            log "[STAGE 3/4] ✅ 集成测试通过"
        else
            log "[STAGE 3/4] ❌ 集成测试失败"
            mkdir -p "${REPORT_DIR}/integration"
            cp -r target/surefire-reports/*.xml "${REPORT_DIR}/integration/" 2>/dev/null || true
        fi

        log "  停止测试环境..."
        bash scripts/ci/setup-integration-env.sh stop 2>&1 | tee -a "${PIPELINE_LOG}" || true
    else
        log "[STAGE 3/4] ⚠️  测试环境启动失败，跳过集成测试"
    fi
else
    log "[STAGE 3/4] ⏭  跳过集成测试"
    STAGE_INTEGRATION_PASS=1
fi

# ======================
# Stage 4: Package
# ======================
log "[STAGE 4/4] 开始打包..."

BUILD_TAG="nwp-solver-${TIMESTAMP}"

if mvn package -DskipTests \
        -Dmaven.compiler.source=21 \
        -Dmaven.compiler.target=21 \
        -DfinalName="${BUILD_TAG}" \
        -B 2>&1 | tee "${LOG_DIR}/package-${TIMESTAMP}.log"; then

    if mvn assembly:single -DskipTests \
            -DdescriptorId=jar-with-dependencies \
            -DfinalName="${BUILD_TAG}-with-deps" \
            -B 2>&1 | tee -a "${LOG_DIR}/package-${TIMESTAMP}.log"; then
        STAGE_PACKAGE_PASS=1
        log "[STAGE 4/4] ✅ 打包完成"
        log "  JAR (普通): target/${BUILD_TAG}.jar"
        log "  JAR (含依赖): target/${BUILD_TAG}-with-deps.jar"
    fi
fi

# ======================
# Coverage Report
# ======================
if [ "${STAGE_UNIT_PASS}" -eq 1 ]; then
    log "生成覆盖率报告..."
    mvn test -P coverage -DfailIfNoTests=false \
        -Dtest="*Test,*Tests" \
        -Dmaven.compiler.source=21 \
        -Dmaven.compiler.target=21 \
        -B 2>&1 | tee "${LOG_DIR}/coverage-${TIMESTAMP}.log" || true

    if [ -d "target/site/jacoco" ]; then
        mkdir -p "${REPORT_DIR}/coverage"
        cp -r target/site/jacoco/* "${REPORT_DIR}/coverage/" 2>/dev/null || true
        log "  覆盖率报告: ${REPORT_DIR}/coverage/index.html"
    fi
fi

# ======================
# Singularity Build
# ======================
if [ "${STAGE_PACKAGE_PASS}" -eq 1 ] && [ "${SKIP_SINGULARITY:-0}" -ne 1 ]; then
    if command -v singularity &> /dev/null || command -v apptainer &> /dev/null; then
        log "构建 Singularity 镜像..."
        SIF_CMD=$(command -v singularity || command -v apptainer)
        mkdir -p "singularity-images"

        # 复制最新JAR以便Singularity定义文件找到
        cp target/${BUILD_TAG}.jar target/nwp-core-solver-latest.jar 2>/dev/null || true
        cp target/${BUILD_TAG}-with-deps.jar target/nwp-core-solver-latest-with-deps.jar 2>/dev/null || true

        if ${SIF_CMD} build --fakeroot \
                "singularity-images/${BUILD_TAG}.sif" \
                Singularity.def 2>&1 | tee "${LOG_DIR}/singularity-${TIMESTAMP}.log"; then
            ln -sf "${BUILD_TAG}.sif" singularity-images/nwp-solver-latest.sif 2>/dev/null || true
            log "  Singularity 镜像: singularity-images/${BUILD_TAG}.sif"
        else
            log "  ⚠️  Singularity 镜像构建失败，查看日志"
        fi
    else
        log "  ⏭  跳过 Singularity 镜像构建（未安装运行时）"
    fi
fi

# ======================
# Summary
# ======================
log "============================================="
log "流水线执行完成"
log "============================================="
log "编译:    $([ "${STAGE_COMPILE_PASS}" -eq 1 ] && echo "✅ 通过" || echo "❌ 失败")"
log "单元测试: $([ "${STAGE_UNIT_PASS}" -eq 1 ] && echo "✅ 通过" || echo "❌ 失败")"
log "集成测试: $([ "${STAGE_INTEGRATION_PASS}" -eq 1 ] && echo "✅ 通过" || echo "❌ 失败")"
log "打包:    $([ "${STAGE_PACKAGE_PASS}" -eq 1 ] && echo "✅ 通过" || echo "❌ 失败")"
log "============================================="
log "日志目录:   ${LOG_DIR}"
log "报告目录:   ${REPORT_DIR}"
log "构建标签:   ${BUILD_TAG}"
log "============================================="

if [ "${STAGE_COMPILE_PASS}" -eq 1 ] && [ "${STAGE_UNIT_PASS}" -eq 1 ] && [ "${STAGE_PACKAGE_PASS}" -eq 1 ]; then
    log "🎉 流水线执行成功!"
    exit 0
else
    log "❌ 流水线执行失败"
    exit 1
fi
