#!/bin/bash

echo "========================================"
echo "  会议室预约系统 - 一键启动脚本"
echo "========================================"
echo ""

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/backend"
FRONTEND_DIR="$SCRIPT_DIR/frontend"

check_go() {
    if ! command -v go &> /dev/null; then
        echo -e "${YELLOW}警告: 未检测到 Go 语言环境，请先安装 Go 1.21+${NC}"
        return 1
    fi
    echo -e "${GREEN}✓ Go 版本: $(go version)${NC}"
    return 0
}

check_node() {
    if ! command -v node &> /dev/null; then
        echo -e "${YELLOW}警告: 未检测到 Node.js，请先安装 Node.js 18+${NC}"
        return 1
    fi
    echo -e "${GREEN}✓ Node.js 版本: $(node -v)${NC}"
    return 0
}

check_psql() {
    if ! command -v psql &> /dev/null; then
        echo -e "${YELLOW}警告: 未检测到 PostgreSQL 客户端${NC}"
        return 1
    fi
    echo -e "${GREEN}✓ PostgreSQL 客户端已安装${NC}"
    return 0
}

setup_backend() {
    echo ""
    echo "=== 设置后端 ==="
    cd "$BACKEND_DIR"

    if [ ! -f ".env" ]; then
        cp .env.example .env
        echo "已创建 .env 配置文件"
    fi

    echo "下载 Go 依赖..."
    go mod download
    echo -e "${GREEN}✓ 后端依赖安装完成${NC}"
}

setup_frontend() {
    echo ""
    echo "=== 设置前端 ==="
    cd "$FRONTEND_DIR"

    if [ ! -f ".env" ]; then
        cp .env.example .env
        echo "已创建 .env 配置文件"
    fi

    echo "安装 npm 依赖..."
    if command -v pnpm &> /dev/null; then
        pnpm install
    elif command -v yarn &> /dev/null; then
        yarn install
    else
        npm install
    fi
    echo -e "${GREEN}✓ 前端依赖安装完成${NC}"
}

init_database() {
    echo ""
    echo "=== 初始化数据库 ==="
    read -p "是否初始化数据库？(需要本地 PostgreSQL 服务) [y/N]: " init_db
    if [[ "$init_db" =~ ^[Yy]$ ]]; then
        read -p "数据库用户 (默认 postgres): " db_user
        db_user=${db_user:-postgres}
        read -p "数据库名称 (默认 meeting_system): " db_name
        db_name=${db_name:-meeting_system}

        echo "创建数据库..."
        createdb -U "$db_user" "$db_name" 2>/dev/null || echo "数据库已存在"

        echo "执行数据库迁移..."
        psql -U "$db_user" -d "$db_name" -f "$BACKEND_DIR/migrations/001_init_schema.sql"
        echo -e "${GREEN}✓ 数据库初始化完成${NC}"
    fi
}

start_backend() {
    echo ""
    echo "=== 启动后端服务 ==="
    cd "$BACKEND_DIR"

    if [ -f ".env" ]; then
        export $(cat .env | grep -v '^#' | xargs)
    fi

    echo "后端服务启动中，端口: ${SERVER_PORT:-8080}"
    go run main.go &
    BACKEND_PID=$!
    echo "后端进程 PID: $BACKEND_PID"
}

start_frontend() {
    echo ""
    echo "=== 启动前端服务 ==="
    cd "$FRONTEND_DIR"

    echo "前端服务启动中，端口: 3000"
    if command -v pnpm &> /dev/null; then
        pnpm run dev &
    elif command -v yarn &> /dev/null; then
        yarn dev &
    else
        npm run dev &
    fi
    FRONTEND_PID=$!
    echo "前端进程 PID: $FRONTEND_PID"
}

cleanup() {
    echo ""
    echo "正在停止服务..."
    if [ ! -z "$BACKEND_PID" ]; then
        kill $BACKEND_PID 2>/dev/null
        echo "后端服务已停止"
    fi
    if [ ! -z "$FRONTEND_PID" ]; then
        kill $FRONTEND_PID 2>/dev/null
        echo "前端服务已停止"
    fi
    exit 0
}

trap cleanup SIGINT SIGTERM

echo "环境检查:"
check_go
check_node
check_psql

echo ""
read -p "是否执行依赖安装？(首次运行需要) [Y/n]: " install_deps
if [[ "$install_deps" =~ ^[Yy]$ ]] || [ -z "$install_deps" ]; then
    setup_backend
    setup_frontend
    init_database
fi

echo ""
echo "=== 启动服务 ==="
start_backend
sleep 3
start_frontend

echo ""
echo "========================================"
echo -e "${GREEN}  系统启动完成！${NC}"
echo "========================================"
echo ""
echo "  前端地址: http://localhost:3000"
echo "  后端地址: http://localhost:8080"
echo "  API文档:  http://localhost:8080/api"
echo ""
echo "  测试账号: admin@company.com"
echo "  测试密码: (任意密码)"
echo ""
echo "  按 Ctrl+C 停止服务"
echo "========================================"

wait
