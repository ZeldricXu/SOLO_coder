# 代码质量门禁检查

$ErrorActionPreference = "Stop"

Write-Host "========================================"
Write-Host "    代码质量门禁检查"
Write-Host "========================================"
Write-Host ""

Write-Host "[1/6] 代码格式化检查 (black)..."
black --check edge_platform/
Write-Host "✓ 代码格式检查通过"

Write-Host ""
Write-Host "[2/6] 导入排序检查 (isort)..."
isort --check-only edge_platform/
Write-Host "✓ 导入排序检查通过"

Write-Host ""
Write-Host "[3/6] 代码规范检查 (ruff)..."
ruff check edge_platform/
Write-Host "✓ 代码规范检查通过"

Write-Host ""
Write-Host "[4/6] 类型检查 (mypy)..."
mypy edge_platform/
Write-Host "✓ 类型检查通过"

Write-Host ""
Write-Host "[5/6] 安全漏洞扫描 (bandit)..."
bandit -c pyproject.toml -r edge_platform/
Write-Host "✓ 安全扫描通过"

Write-Host ""
Write-Host "[6/6] 单元测试与覆盖率检查..."
pytest tests/ --cov=edge_platform --cov-fail-under=80 --cov-report=term-missing
Write-Host "✓ 单元测试与覆盖率检查通过"

Write-Host ""
Write-Host "========================================"
Write-Host "    ✓ 所有质量门禁检查全部通过!"
Write-Host "========================================"
