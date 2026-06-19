#!/bin/bash
cd "$(dirname "$0")/.."

echo "============================================="
echo "NWP 项目编译验证"
echo "============================================="

echo ""
echo "[1/4] 检查Java版本..."
if java -version 2>&1 | head -2; then
    echo "✅ Java OK"
else
    echo "❌ Java not found"
    exit 1
fi

echo ""
echo "[2/4] 检查Maven版本..."
if mvn -version 2>&1 | head -3; then
    echo "✅ Maven OK"
else
    echo "❌ Maven not found"
    exit 1
fi

echo ""
echo "[3/4] 编译项目 (JDK 21)..."
echo "  这可能需要几分钟..."

if mvn clean compile -DskipTests \
        -Dmaven.compiler.source=21 \
        -Dmaven.compiler.target=21 \
        -Dmaven.compiler.release=21 \
        -B 2>&1 | tee /tmp/nwp-compile.log | tail -50; then

    EXIT_CODE=${PIPESTATUS[0]}
    if [ ${EXIT_CODE} -eq 0 ]; then
        echo ""
        echo "✅ 编译成功!"
    else
        echo ""
        echo "❌ 编译失败，查看完整日志: /tmp/nwp-compile.log"
        tail -100 /tmp/nwp-compile.log
        exit 1
    fi
else
    echo ""
    echo "❌ 编译失败"
    tail -100 /tmp/nwp-compile.log
    exit 1
fi

echo ""
echo "[4/4] 验证编译产物..."
if ls -la target/classes/com/meteorology/nwp/ 2>/dev/null | head -10; then
    echo ""
    echo "✅ 编译产物存在"
    echo ""
    echo "============================================="
    echo "编译验证全部通过!"
    echo "============================================="
else
    echo "❌ 未找到编译产物"
    exit 1
fi
