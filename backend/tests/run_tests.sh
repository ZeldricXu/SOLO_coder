#!/bin/bash
set -e

BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$BACKEND_DIR"

echo "=========================================="
echo "  城市交通流量三维可视化平台 - 测试运行器"
echo "=========================================="

usage() {
    echo ""
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  unit         运行单元测试"
    echo "  exception    运行异常场景测试"
    echo "  concurrency  运行并发场景测试"
    echo "  integration  运行集成测试（需要docker-compose）"
    echo "  all          运行所有测试"
    echo "  cov          运行测试并生成覆盖率报告"
    echo ""
}

setup_env() {
    if [ ! -d "venv" ]; then
        echo "未找到虚拟环境，请先运行 setup.sh"
        exit 1
    fi
    source venv/bin/activate
    export PYTHONPATH="${BACKEND_DIR}:${PYTHONPATH}"
    export POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
    export REDIS_HOST="${REDIS_HOST:-localhost}"
    export INFLUXDB_URL="${INFLUXDB_URL:-http://localhost:8086}"
    export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
    export TILE_CACHE_DIR="$(mktemp -d)"
    export HEATMAP_CACHE_DIR="$(mktemp -d)"
    export PREDICTION_MODEL_DIR="$(mktemp -d)"
    export SECRET_KEY="test-secret-key"
}

start_test_services() {
    echo "启动测试用 docker-compose 环境..."
    docker-compose -f tests/docker-compose.test.yml up -d
    echo "等待服务就绪..."
    sleep 15
}

stop_test_services() {
    echo "停止测试用 docker-compose 环境..."
    docker-compose -f tests/docker-compose.test.yml down -v
}

case "${1:-all}" in
    unit)
        setup_env
        echo "运行单元测试..."
        python -m pytest tests/ -m unit -v "$@"
        ;;
    exception)
        setup_env
        echo "运行异常场景测试..."
        python -m pytest tests/ -m exception -v "$@"
        ;;
    concurrency)
        setup_env
        echo "运行并发场景测试..."
        python -m pytest tests/ -m concurrency -v "$@"
        ;;
    integration)
        setup_env
        start_test_services
        echo "运行集成测试..."
        python -m pytest tests/ -m integration -v "$@" || true
        stop_test_services
        ;;
    all)
        setup_env
        echo "运行所有非集成测试..."
        python -m pytest tests/ -m "not integration" -v "$@"
        ;;
    cov)
        setup_env
        echo "运行测试并生成覆盖率报告..."
        python -m pytest tests/ -m "not integration" --cov=app --cov-report=term-missing --cov-report=html "$@"
        ;;
    *)
        usage
        exit 1
        ;;
esac
