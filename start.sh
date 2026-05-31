#!/bin/bash

cd "$(dirname "$0")"

echo "================================================"
echo "隐私计算与数据安全服务"
echo "================================================"
echo ""

if [ ! -d "venv" ]; then
    echo "正在创建虚拟环境..."
    python3 -m venv venv
    echo "虚拟环境创建完成"
    echo ""
fi

source venv/bin/activate

echo "正在安装依赖..."
pip install -r requirements.txt
echo "依赖安装完成"
echo ""

echo "正在启动服务..."
echo "API文档: http://localhost:8000/docs"
echo "服务地址: http://localhost:8000"
echo ""

python run.py
