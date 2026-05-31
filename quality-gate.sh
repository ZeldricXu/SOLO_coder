#!/bin/bash

set -e

echo "========================================"
echo "    代码质量门禁检查"
echo "========================================"

echo ""

echo "[1/6] 代码格式化检查 (black)..."
black --check edge_platform/
echo "✓ 代码格式检查通过"

echo ""
echo "[2/6] 导入排序检查 (isort)..."
isort --check-only edge_platform/
echo "✓ 导入排序检查通过"

echo ""
echo "[3/6] 代码规范检查 (ruff)..."
ruff check edge_platform/
echo "✓ 代码规范检查通过"

echo ""
echo "[4/6] 类型检查 (mypy)..."
mypy edge_platform/
echo "✓ 类型检查通过"

echo ""
echo "[5/6] 安全漏洞扫描 (bandit)..."
bandit -c pyproject.toml -r edge_platform/
echo "✓ 安全扫描通过"

echo ""
echo "[6/6] 单元测试与覆盖率检查..."
pytest tests/ --cov=edge_platform --cov-fail-under=80 --cov-report=term-missing
echo "✓ 单元测试与覆盖率检查通过"

echo ""
echo "========================================"
echo "    ✓ 所有质量门禁检查全部通过!"
echo "========================================"
