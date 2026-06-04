#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== FlowPlatform Build Script ==="
echo "Project: ${PROJECT_DIR}"

echo ""
echo "[1/4] Building frontend assets..."
cd "${PROJECT_DIR}/frontend"
if [ ! -d "node_modules" ]; then
    echo "  Installing npm dependencies..."
    npm install
fi
npm run build
echo "  ✓ Frontend build complete"

echo ""
echo "[2/4] Running Maven build..."
cd "${PROJECT_DIR}"
./mvnw clean package -DskipTests -B
echo "  ✓ Maven build complete"

echo ""
echo "[3/4] Locating JAR..."
JAR_FILE=$(find "${PROJECT_DIR}/target" -name "flow-platform-*.jar" -not -name "*-sources.jar" | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "  ✗ JAR not found!"
    exit 1
fi
echo "  ✓ JAR: ${JAR_FILE}"

echo ""
echo "[4/4] Build summary"
echo "  JAR: ${JAR_FILE}"
echo "  Size: $(du -h "$JAR_FILE" | cut -f1)"
echo ""
echo "=== Build complete ==="
