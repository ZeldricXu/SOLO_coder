#!/bin/bash
set -euo pipefail

echo "========================================="
echo "ChaosLab 测试套件运行脚本"
echo "========================================="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 检查 Python
if ! command -v python3 &> /dev/null; then
    echo "❌ Python 3 未找到，请先安装 Python 3.8+"
    exit 1
fi

# 检查虚拟环境
if [ ! -d "venv" ]; then
    echo "📦 创建虚拟环境..."
    python3 -m venv venv
fi

# 激活虚拟环境
source venv/bin/activate

# 安装依赖
echo "📦 安装依赖..."
pip install --quiet --upgrade pip
pip install --quiet -r requirements.txt

# 解析参数
TEST_MODE="${1:-integration}"
TEST_MARKER="${2:-}"
TEST_FILE="${3:-}"

echo "🔬 测试模式: $TEST_MODE"
if [ -n "$TEST_MARKER" ]; then
    echo "🏷️  测试标记: $TEST_MARKER"
fi
if [ -n "$TEST_FILE" ]; then
    echo "📄 测试文件: $TEST_FILE"
fi

# 构建 pytest 参数
PYTEST_ARGS=()

if [ "$TEST_MODE" = "unit" ]; then
    PYTEST_ARGS+=("-m" "unit")
elif [ "$TEST_MODE" = "integration" ]; then
    PYTEST_ARGS+=("-m" "integration")
elif [ "$TEST_MODE" = "all" ]; then
    PYTEST_ARGS+=()
else
    echo "❌ 无效的测试模式: $TEST_MODE"
    echo "可用模式: unit, integration, all"
    exit 1
fi

if [ -n "$TEST_MARKER" ]; then
    PYTEST_ARGS+=("-m" "$TEST_MARKER")
fi

if [ -n "$TEST_FILE" ]; then
    PYTEST_ARGS+=("$TEST_FILE")
fi

# 添加默认参数
PYTEST_ARGS+=("-v" "--tb=short" "--strict-markers")

# 运行测试
echo ""
echo "🚀 运行测试..."
echo "命令: pytest ${PYTEST_ARGS[*]}"
echo ""

if pytest "${PYTEST_ARGS[@]}"; then
    echo ""
    echo "✅ 所有测试通过！"
    exit 0
else
    echo ""
    echo "❌ 部分测试失败"
    exit 1
fi
