#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "========================================"
echo "  日志分析工具 - 测试执行脚本"
echo "========================================"
echo ""

# 配置目录
MAIN_SRC="src/main/java"
TEST_SRC="src/test/java"
MAIN_OUT="target/classes"
TEST_OUT="target/test-classes"
LIB_DIR="lib"
REPORT_DIR="target/test-reports"

# 创建输出目录
mkdir -p "$TEST_OUT"
mkdir -p "$REPORT_DIR"

# 构建类路径 - 包含所有依赖jar
ALL_JARS=""
for jar in "$LIB_DIR"/*.jar; do
    # 排除junit-platform-console-standalone，因为我们用它来执行
    if [[ "$jar" != *junit-platform-console-standalone* ]]; then
        ALL_JARS="$ALL_JARS:$jar"
    fi
done
FULL_CLASSPATH="$MAIN_OUT:$TEST_OUT$ALL_JARS"

echo "[1/4] 编译主代码..."
find "$MAIN_SRC" -name "*.java" > sources.txt
javac -d "$MAIN_OUT" -cp "$FULL_CLASSPATH" @sources.txt 2>&1 | head -50
if [ ${PIPESTATUS[0]} -ne 0 ]; then
    echo "❌ 主代码编译失败"
    exit 1
fi
echo "✅ 主代码编译完成"
echo ""

echo "[2/4] 编译测试代码..."
find "$TEST_SRC" -name "*.java" > test_sources.txt
javac -d "$TEST_OUT" -cp "$FULL_CLASSPATH" @test_sources.txt 2>&1
if [ ${PIPESTATUS[0]} -ne 0 ]; then
    echo "❌ 测试代码编译失败"
    exit 1
fi
echo "✅ 测试代码编译完成"
echo ""

echo "[3/4] 运行单元测试..."
echo ""
java -Dnet.bytebuddy.experimental=true \
    -jar "$LIB_DIR/junit-platform-console-standalone-1.10.0.jar" \
    --class-path "$FULL_CLASSPATH" \
    --scan-class-path \
    --include-classname ".*Test.*" \
    --exclude-classname ".*Abstract.*" \
    --reports-dir "$REPORT_DIR" \
    --details=summary 2>&1 | tee test_output.log

TEST_EXIT_CODE=${PIPESTATUS[0]}
echo ""

echo "[4/4] 生成测试报告摘要..."
echo ""
echo "========================================"
echo "  测试报告摘要"
echo "========================================"
echo ""

# 解析测试结果
if [ -f "$REPORT_DIR/TEST-junit-jupiter.xml" ]; then
    TOTAL_TESTS=$(grep -o 'tests="[0-9]*"' "$REPORT_DIR"/TEST-*.xml | grep -o '[0-9]*' | awk '{sum+=$1} END {print sum}')
    TOTAL_FAILED=$(grep -o 'failed="[0-9]*"' "$REPORT_DIR"/TEST-*.xml | grep -o '[0-9]*' | awk '{sum+=$1} END {print sum}')
    TOTAL_ERRORS=$(grep -o 'errors="[0-9]*"' "$REPORT_DIR"/TEST-*.xml | grep -o '[0-9]*' | awk '{sum+=$1} END {print sum}')
    TOTAL_SKIPPED=$(grep -o 'skipped="[0-9]*"' "$REPORT_DIR"/TEST-*.xml | grep -o '[0-9]*' | awk '{sum+=$1} END {print sum}')

    echo "📊 测试统计："
    echo "  总测试数: ${TOTAL_TESTS:-0}"
    echo "  ✅ 通过:     $((TOTAL_TESTS - TOTAL_FAILED - TOTAL_ERRORS - TOTAL_SKIPPED))"
    echo "  ❌ 失败:     ${TOTAL_FAILED:-0}"
    echo "  💥 错误:     ${TOTAL_ERRORS:-0}"
    echo "  ⏭️  跳过:     ${TOTAL_SKIPPED:-0}"
    echo ""

    PASS_RATE=$(( (TOTAL_TESTS - TOTAL_FAILED - TOTAL_ERRORS) * 100 / TOTAL_TESTS )) 2>/dev/null
    echo "📈 通过率: ${PASS_RATE:-0}%"
    echo ""

    if [ "$TEST_EXIT_CODE" -eq 0 ] && [ "$TOTAL_FAILED" -eq 0 ] && [ "$TOTAL_ERRORS" -eq 0 ]; then
        echo "🎉 所有测试通过！"
    else
        echo "⚠️  部分测试失败，请查看详细报告"
    fi
else
    echo "❌ 未找到测试报告文件"
fi

echo ""
echo "========================================"
echo "  详细报告位置"
echo "========================================"
echo "  XML报告: $REPORT_DIR/"
echo "  控制台日志: $(pwd)/test_output.log"
echo ""

# 清理临时文件
rm -f sources.txt test_sources.txt

exit $TEST_EXIT_CODE
