#!/usr/bin/env bash

set -e

echo "=========================================="
echo "  SmartFlow 项目构建脚本"
echo "=========================================="

ENV=${1:-dev}
SKIP_TESTS=${2:-false}

echo ""
echo "环境: $ENV"
echo "跳过测试: $SKIP_TESTS"
echo ""

# 检查 Maven 是否可用
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven 未安装，请先安装 Maven"
    exit 1
fi

# 检查 Java 版本
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "❌ 需要 Java 17 或更高版本，当前版本: $JAVA_VERSION"
    exit 1
fi

echo "✅ 环境检查通过"
echo ""

# 清理之前的构建
echo "🧹 清理旧的构建产物..."
mvn clean -q

# 代码质量检查（仅在非跳过测试时）
if [ "$SKIP_TESTS" = "false" ]; then
    echo "🔍 运行代码质量检查..."
    if ! mvn checkstyle:check pmd:check -q -Pcode-quality; then
        echo "❌ 代码质量检查失败，请修复问题后重试"
        exit 1
    fi
    echo "✅ 代码质量检查通过"
fi

# 编译项目
echo "🔨 编译项目..."
if ! mvn compile -q -P$ENV; then
    echo "❌ 编译失败"
    exit 1
fi
echo "✅ 编译成功"

# 运行测试
if [ "$SKIP_TESTS" = "false" ]; then
    echo "🧪 运行单元测试..."
    if ! mvn test -q -P$ENV; then
        echo "❌ 测试失败"
        exit 1
    fi
    echo "✅ 测试通过"
fi

# 打包
echo "📦 打包项目..."
if ! mvn package -q -P$ENV -DskipTests=$SKIP_TESTS -pl smartflow-boot -am; then
    echo "❌ 打包失败"
    exit 1
fi
echo "✅ 打包成功"

JAR_FILE=$(ls smartflow-boot/target/smartflow-boot-*.jar | head -1)
echo ""
echo "🎉 构建完成!"
echo "可执行文件: $JAR_FILE"
echo ""
echo "运行命令:"
echo "  java -jar $JAR_FILE"
