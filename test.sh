#!/bin/bash

cd "$(dirname "$0")"

echo "================================================"
echo "运行单元测试"
echo "================================================"
echo ""

if [ -d "venv" ]; then
    source venv/bin/activate
fi

pytest tests/ -v --tb=short
