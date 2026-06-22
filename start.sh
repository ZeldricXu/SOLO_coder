#!/bin/bash

set -e

echo "=============================================="
echo "📝 周报自动汇总系统 - 一键启动"
echo "=============================================="

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$PROJECT_DIR/backend"
FRONTEND_DIR="$PROJECT_DIR/frontend"

PYTHON_CMD=""
if command -v python3 &> /dev/null; then
    PYTHON_CMD="python3"
elif command -v python &> /dev/null; then
    PYTHON_CMD="python"
else
    echo "❌ 找不到 Python，请先安装 Python 3.9+"
    exit 1
fi

echo ""
echo "👉 [1/4] 检查后端依赖..."
cd "$BACKEND_DIR"
if [ ! -d "venv" ]; then
    echo "   创建虚拟环境..."
    $PYTHON_CMD -m venv venv
fi
source venv/bin/activate
if ! pip show fastapi > /dev/null 2>&1; then
    echo "   安装 Python 依赖（首次可能较慢）..."
    pip install --upgrade pip > /dev/null
    pip install -r requirements.txt
fi
echo "   ✅ 后端依赖 OK"

echo ""
echo "👉 [2/4] 检查前端依赖..."
cd "$FRONTEND_DIR"
if [ ! -d "node_modules" ]; then
    echo "   安装 npm 依赖（首次可能较慢）..."
    if command -v pnpm &> /dev/null; then
        pnpm install
    elif command -v yarn &> /dev/null; then
        yarn
    else
        npm install --registry=https://registry.npmmirror.com
    fi
fi
echo "   ✅ 前端依赖 OK"

echo ""
echo "👉 [3/4] 启动后端 (端口 8000)..."
cd "$BACKEND_DIR"
if [ -f "venv/bin/python" ]; then
    VENV_PY="venv/bin/python"
else
    VENV_PY="$PYTHON_CMD"
fi

$VENV_PY main.py &
BACKEND_PID=$!
echo "   后端 PID: $BACKEND_PID"

sleep 3
if ! kill -0 $BACKEND_PID 2>/dev/null; then
    echo "   ❌ 后端启动失败，请手动执行：$BACKEND_DIR/venv/bin/python $BACKEND_DIR/main.py"
    exit 1
fi
echo "   ✅ 后端已启动: http://localhost:8000"
echo "   📖 API 文档: http://localhost:8000/docs"

echo ""
echo "👉 [4/4] 启动前端 (端口 5173)..."
cd "$FRONTEND_DIR"

if command -v pnpm &> /dev/null; then
    pnpm run dev --host &
elif command -v yarn &> /dev/null; then
    yarn dev --host &
else
    npm run dev -- --host &
fi
FRONTEND_PID=$!
echo "   前端 PID: $FRONTEND_PID"

sleep 3

echo ""
echo "=============================================="
echo "✅ 启动完成！"
echo "=============================================="
echo "🌐 前端访问: http://localhost:5173"
echo "🔧 后端 API: http://localhost:8000"
echo "📖 Swagger : http://localhost:8000/docs"
echo ""
echo "👤 默认账号:"
echo "   管理员: admin  /  admin123"
echo "   普通成员: zhangsan ~ zhoujiu  /  123456"
echo ""
echo "🛑 停止服务请按 Ctrl+C 两次，或执行 kill $BACKEND_PID $FRONTEND_PID"
echo "=============================================="

trap "echo ''; echo '🛑 正在停止服务...'; kill $BACKEND_PID $FRONTEND_PID 2>/dev/null; exit 0" SIGINT SIGTERM

wait
