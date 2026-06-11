#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# 全量生产构建脚本：WASM → 前端 Vite 构建 → gzip 预压缩
# ============================================================================

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "🚀 Full Production Build"
echo "   Project: $PROJECT_ROOT"
echo ""

# Step 1: 构建 WASM
echo "═══════════════════════════════════════════════"
echo "  Phase 1: WASM Build"
echo "═══════════════════════════════════════════════"
cd "$PROJECT_ROOT"
bash scripts/build-wasm.sh

# Step 2: 安装前端依赖（如需要）
echo ""
echo "═══════════════════════════════════════════════"
echo "  Phase 2: Frontend Build"
echo "═══════════════════════════════════════════════"
cd "$PROJECT_ROOT/web"
if [[ ! -d "node_modules" ]]; then
    echo "📦 Installing frontend dependencies..."
    npm ci
fi

echo "🔨 Building production bundle with Vite..."
npm run build
cd "$PROJECT_ROOT"

# Step 3: gzip 预压缩静态资源
echo ""
echo "═══════════════════════════════════════════════"
echo "  Phase 3: gzip Pre-compression"
echo "═══════════════════════════════════════════════"
DIST_DIR="$PROJECT_ROOT/web/dist"
if [[ -d "$DIST_DIR" ]]; then
    echo "🗜️  Pre-compressing static assets with gzip..."
    COMPRESSED=0
    TOTAL_BEFORE=0
    TOTAL_AFTER=0

    for f in $(find "$DIST_DIR" -type f \( -name "*.js" -o -name "*.css" -o -name "*.html" -o -name "*.wasm" -o -name "*.svg" -o -name "*.json" \)); do
        if [[ ! -f "${f}.gz" ]]; then
            SIZE_BEFORE=$(wc -c < "$f" | tr -d ' ')
            gzip -9 -k "$f"
            if [[ -f "${f}.gz" ]]; then
                SIZE_AFTER=$(wc -c < "${f}.gz" | tr -d ' ')
                COMPRESSED=$((COMPRESSED + 1))
                TOTAL_BEFORE=$((TOTAL_BEFORE + SIZE_BEFORE))
                TOTAL_AFTER=$((TOTAL_AFTER + SIZE_AFTER))
            fi
        fi
    done

    if [[ $COMPRESSED -gt 0 ]]; then
        SAVED=$((TOTAL_BEFORE - TOTAL_AFTER))
        SAVED_PCT=$((SAVED * 100 / TOTAL_BEFORE))
        echo "   Compressed $COMPRESSED files"
        echo "   Before: $(numfmt --to=iec $TOTAL_BEFORE)"
        echo "   After:  $(numfmt --to=iec $TOTAL_AFTER) (saved ${SAVED_PCT}%)"
    fi
    echo "✅ Pre-compression complete"
else
    echo "⚠️  dist directory not found, skipping pre-compression"
fi

echo ""
echo "🎉 Full production build complete!"
echo "   Output: $DIST_DIR"
