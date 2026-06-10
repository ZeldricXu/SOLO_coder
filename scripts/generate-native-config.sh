#!/bin/bash
set -e

echo "============================================"
echo "GraalVM Native Image Configuration Generator"
echo "============================================"
echo ""

MAIN_CLASS="com.datateam.loganalyzer.cli.LogAnalyzerCli"
APP_NAME="log-analyzer"
NATIVE_CONFIG_DIR="src/main/resources/META-INF/native-image"
AGENT_OUTPUT_DIR="target/native/agent-output"

echo "Checking GraalVM installation..."
if ! command -v java &> /dev/null; then
    echo "ERROR: Java is not installed"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1)
echo "Java version: $JAVA_VERSION"
echo ""

echo "Cleaning previous build..."
mvn clean -q

echo ""
echo "Running tests with GraalVM Tracing Agent to collect configuration..."
echo "This will run all tests and record reflection, resource, and serialization usage."
echo ""

mkdir -p "$AGENT_OUTPUT_DIR"

MAIN_JARS=$(ls lib/*.jar 2>/dev/null | tr '\n' ':' || echo "")
TEST_JARS=$(ls lib/test/*.jar 2>/dev/null | tr '\n' ':' || echo "")
ALL_CP="target/classes:target/test-classes:$MAIN_JARS:$TEST_JARS"

mvn test-compile -q

java \
    -agentlib:native-image-agent=config-output-dir=$AGENT_OUTPUT_DIR,config-merge-dir=$AGENT_OUTPUT_DIR \
    -Dnet.bytebuddy.experimental=true \
    -cp "$ALL_CP" \
    org.junit.platform.console.ConsoleLauncher execute \
    --scan-classpath \
    --details=summary 2>&1 || true

echo ""
echo "Agent output generated in: $AGENT_OUTPUT_DIR"
echo ""
echo "Copying configuration files to: $NATIVE_CONFIG_DIR"
echo ""

mkdir -p "$NATIVE_CONFIG_DIR"

if [ -f "$AGENT_OUTPUT_DIR/reflect-config.json" ]; then
    cp "$AGENT_OUTPUT_DIR/reflect-config.json" "$NATIVE_CONFIG_DIR/reflect-config.json"
    echo "✓ reflect-config.json copied"
fi

if [ -f "$AGENT_OUTPUT_DIR/resource-config.json" ]; then
    cp "$AGENT_OUTPUT_DIR/resource-config.json" "$NATIVE_CONFIG_DIR/resource-config.json"
    echo "✓ resource-config.json copied"
fi

if [ -f "$AGENT_OUTPUT_DIR/serialization-config.json" ]; then
    cp "$AGENT_OUTPUT_DIR/serialization-config.json" "$NATIVE_CONFIG_DIR/serialization-config.json"
    echo "✓ serialization-config.json copied"
fi

if [ -f "$AGENT_OUTPUT_DIR/jni-config.json" ]; then
    cp "$AGENT_OUTPUT_DIR/jni-config.json" "$NATIVE_CONFIG_DIR/jni-config.json"
    echo "✓ jni-config.json copied"
fi

if [ -f "$AGENT_OUTPUT_DIR/proxy-config.json" ]; then
    cp "$AGENT_OUTPUT_DIR/proxy-config.json" "$NATIVE_CONFIG_DIR/proxy-config.json"
    echo "✓ proxy-config.json copied"
fi

echo ""
echo "============================================"
echo "Configuration generation complete!"
echo "============================================"
echo ""
echo "Next steps:"
echo "1. Review the generated config files in $NATIVE_CONFIG_DIR"
echo "2. Build native image with: mvn package -Pnative"
echo ""
