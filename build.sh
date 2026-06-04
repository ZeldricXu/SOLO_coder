#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== DataExplorer WASM Build ==="

if ! command -v go &> /dev/null; then
    echo "Error: Go is not installed"
    exit 1
fi

GOOS=js GOARCH=wasm go build -o main.wasm .

if [ $? -eq 0 ]; then
    echo "✓ WASM build successful: main.wasm"
    WASM_SIZE=$(du -h main.wasm | cut -f1)
    echo "  Size: $WASM_SIZE"
else
    echo "✗ WASM build failed"
    exit 1
fi

WASM_EXEC=$(find "$(go env GOROOT)" -name "wasm_exec.js" -print -quit 2>/dev/null)
if [ -n "$WASM_EXEC" ] && [ -f "$WASM_EXEC" ]; then
    cp "$WASM_EXEC" wasm_exec.js
    echo "✓ Copied wasm_exec.js from $WASM_EXEC"
else
    echo "⚠ wasm_exec.js not found in GOROOT"
    echo "  Try: find \"\$(go env GOROOT)\" -name wasm_exec.js"
fi

echo ""
echo "=== Build complete ==="
echo "Open index.html in a browser (requires HTTP server)"
echo "Example: python3 -m http.server 8080"
echo "Then visit: http://localhost:8080"
