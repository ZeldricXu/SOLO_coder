#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

check_env_file() {
    if [ ! -f .env ]; then
        warn ".env file not found, copying from .env.example..."
        cp .env.example .env
        log "Please edit .env with your actual configuration before proceeding"
        return 1
    fi
    return 0
}

generate_secret() {
    python3 -c "import secrets; print(secrets.token_urlsafe(32))"
}

generate_keys() {
    log "Generating secure keys..."
    SECRET_KEY=$(generate_secret)
    ENCRYPTION_KEY=$(python3 -c "import secrets, base64; key = secrets.token_bytes(32); print(base64.urlsafe_b64encode(key).decode())")
    log "SECRET_KEY: $SECRET_KEY"
    log "ENCRYPTION_KEY: $ENCRYPTION_KEY"
    log "Add these to your .env file"
}

cmd_setup() {
    log "Setting up local development environment..."

    check_env_file || true

    log "Creating data directories..."
    mkdir -p instance data data/uploads data/exports data/snapshots

    if [ ! -d "venv" ]; then
        log "Creating Python virtual environment..."
        python3 -m venv venv
    fi

    log "Activating virtual environment and installing dependencies..."
    source venv/bin/activate
    pip install --upgrade pip
    pip install -r requirements.txt

    log "Installing Playwright browsers..."
    playwright install chromium

    log "Initializing database..."
    export FLASK_ENV=development
    source .env 2>/dev/null || true
    python -c "
from app import create_app, db
app = create_app('development')
with app.app_context():
    db.create_all()
print('Database initialized')
"

    log "Setup complete!"
    log "Run: source venv/bin/activate && ./start.sh dev"
}

cmd_start() {
    log "Starting all services with Docker Compose..."
    check_env_file
    docker compose up -d --build
    log "Services started"
    log "Web UI: http://localhost:5000"
    log "Check status: ./deploy.sh status"
}

cmd_stop() {
    log "Stopping all services..."
    docker compose down
    log "All services stopped"
}

cmd_restart() {
    cmd_stop
    sleep 3
    cmd_start
}

cmd_status() {
    echo ""
    echo "Service Status:"
    echo "==============="
    docker compose ps
    echo ""
    echo "Health Checks:"
    echo "=============="
    curl -sf http://localhost:5000/health | python3 -m json.tool 2>/dev/null || warn "Web service not reachable"
}

cmd_logs() {
    local service=${1:-}
    if [ -z "$service" ]; then
        docker compose logs -f --tail=100
    else
        docker compose logs -f --tail=100 "$service"
    fi
}

cmd_build() {
    log "Building Docker image..."
    local tag=${1:-latest}
    docker build -t ghcr.io/dataflow-dashboard:$tag .
    log "Build complete: ghcr.io/dataflow-dashboard:$tag"
}

cmd_test() {
    log "Running tests..."
    if [ ! -f ".env" ]; then
        export TESTING=true
        export FLASK_ENV=testing
    else
        set -a
        source .env
        set +a
        export FLASK_ENV=testing
        export TESTING=true
    fi
    mkdir -p instance data data/uploads data/exports data/snapshots
    pytest tests/unit/ -v ${2:-}
}

cmd_migrate() {
    log "Running database migrations..."
    export FLASK_ENV=${FLASK_ENV:-production}
    source .env 2>/dev/null || true

    if [ ! -d "venv" ]; then
        python3 -c "
from app import create_app, db
app = create_app('$FLASK_ENV')
with app.app_context():
    db.create_all()
print('Database migrated')
"
    else
        source venv/bin/activate
        python3 -c "
from app import create_app, db
app = create_app('$FLASK_ENV')
with app.app_context():
    db.create_all()
print('Database migrated')
"
    fi
    log "Migration complete"
}

cmd_clean() {
    warn "This will stop services and remove all data volumes!"
    read -p "Are you sure? [y/N] " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker compose down -v
        rm -rf instance data __pycache__ .pytest_cache htmlcov .coverage
        log "Cleanup complete"
    fi
}

cmd_help() {
    cat << EOF
看板搭建器部署管理脚本

用法: $0 <command> [options]

命令:
  setup          初始化本地开发环境（虚拟环境、依赖、数据库）
  start          Docker Compose 启动全部服务
  stop           停止全部服务
  restart        重启全部服务
  status         查看服务状态和健康检查
  logs [service] 查看日志（不指定service则查看全部）
  build [tag]    构建Docker镜像
  test [opts]    运行单元测试
  migrate        执行数据库迁移
  keys           生成SECRET_KEY和ENCRYPTION_KEY
  clean          停止服务并清理所有数据（危险！）
  help           显示此帮助

示例:
  $0 setup
  $0 start
  $0 logs web
  $0 test -v
EOF
}

case "${1:-help}" in
    setup)      cmd_setup ;;
    start)      cmd_start ;;
    stop)       cmd_stop ;;
    restart)    cmd_restart ;;
    status)     cmd_status ;;
    logs)       cmd_logs "$2" ;;
    build)      cmd_build "${2:-latest}" ;;
    test)       cmd_test "$2" ;;
    migrate)    cmd_migrate ;;
    keys)       generate_keys ;;
    clean)      cmd_clean ;;
    help|*)     cmd_help ;;
esac
