#!/bin/bash

set -e

echo "============================================="
echo "  城市交通流量三维可视化平台 - 快速启动脚本"
echo "============================================="

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$PROJECT_DIR/backend"

cd "$BACKEND_DIR"

echo ""
echo "[1/5] 检查 Python 环境..."
if ! command -v python3 &> /dev/null; then
    echo "❌ 未找到 Python3，请先安装 Python 3.10+"
    exit 1
fi
PYTHON_VERSION=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
echo "   Python 版本: $PYTHON_VERSION"

echo ""
echo "[2/5] 创建虚拟环境..."
if [ ! -d "venv" ]; then
    python3 -m venv venv
    echo "   虚拟环境已创建"
else
    echo "   虚拟环境已存在，跳过"
fi

source venv/bin/activate

echo ""
echo "[3/5] 安装依赖..."
pip install --upgrade pip
pip install -r requirements.txt

echo ""
echo "[4/5] 复制环境变量配置..."
if [ ! -f ".env" ]; then
    cp .env.example .env
    echo "   .env 文件已创建，请根据需要修改配置"
else
    echo "   .env 文件已存在，跳过"
fi

echo ""
echo "[5/5] 初始化数据库..."
PYTHONPATH=. python3 -c "
from app.database import init_db
print('数据库表初始化完成')
"

echo ""
echo "============================================="
echo "  安装完成！"
echo "============================================="
echo ""
echo "启动服务命令："
echo "  cd backend && source venv/bin/activate"
echo "  python main.py"
echo ""
echo "生成示例数据（可选）："
echo "  python scripts/generate_sample_data.py"
echo ""
echo "启动 Celery Worker（可选）："
echo "  celery -A celery_worker.celery_app worker --loglevel=info"
echo ""
echo "访问地址："
echo "  前端页面: http://localhost:8000"
echo "  API 文档: http://localhost:8000/docs"
echo ""
