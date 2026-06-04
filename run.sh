#!/bin/bash

cd "$(dirname "$0")"

echo "=========================================="
echo "  运维监控大盘 - 启动脚本"
echo "=========================================="

echo ""
echo "[1/4] 检查Python虚拟环境..."
if [ ! -d ".venv" ]; then
    echo "    创建虚拟环境..."
    python3 -m venv .venv
fi

echo ""
echo "[2/4] 激活虚拟环境并安装依赖..."
source .venv/bin/activate
pip install -r requirements.txt

echo ""
echo "[3/4] 初始化数据库..."
python init_db.py

echo ""
echo "[4/4] 启动应用服务..."
echo "    服务地址: http://localhost:8000"
echo "    API文档: http://localhost:8000/docs"
echo ""
echo "    按 Ctrl+C 停止服务"
echo ""

python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
