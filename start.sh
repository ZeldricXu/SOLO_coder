#!/bin/bash

# DataFlow 启动脚本
# 使用方法: ./start.sh [all|flask|celery|beat]

set -e

cd "$(dirname "$0")"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 检查虚拟环境
check_venv() {
    if [ ! -d "venv" ]; then
        log_warning "未检测到虚拟环境，正在创建..."
        python3 -m venv venv
        log_success "虚拟环境创建成功"
    fi
    source venv/bin/activate
    log_info "虚拟环境已激活"
}

# 安装依赖
install_dependencies() {
    log_info "检查依赖..."
    if [ ! -f "venv/.deps_installed" ] || [ requirements.txt -nt venv/.deps_installed ]; then
        log_info "安装Python依赖..."
        pip install -r requirements.txt
        touch venv/.deps_installed
        log_success "依赖安装完成"
    else
        log_success "依赖已是最新"
    fi
}

# 检查环境变量
check_env() {
    if [ ! -f ".env" ]; then
        log_warning ".env 文件不存在，从 .env.example 复制..."
        cp .env.example .env
        log_warning "请编辑 .env 文件配置相关参数"
    fi
    export $(grep -v '^#' .env | xargs)
}

# 检查Redis
check_redis() {
    if ! command -v redis-cli &> /dev/null; then
        log_warning "redis-cli 未找到，请确保Redis已安装并启动"
        return 1
    fi
    if ! redis-cli ping &> /dev/null; then
        log_warning "Redis 连接失败，请启动 Redis 服务"
        return 1
    fi
    log_success "Redis 连接正常"
    return 0
}

# 初始化数据库
init_database() {
    if [ ! -f "instance/dashboard.db" ]; then
        log_info "初始化数据库..."
        flask init-db
        log_info "创建管理员账户..."
        flask create-admin --email admin@example.com --password admin123 --name 管理员
        log_info "预置系统模板..."
        flask seed-templates
        log_success "数据库初始化完成"
    else
        log_success "数据库已存在"
    fi
}

# 启动Flask
start_flask() {
    log_info "启动 Flask 应用 (端口: ${FLASK_PORT:-5000})..."
    export FLASK_APP=run.py
    export FLASK_DEBUG=${FLASK_DEBUG:-1}
    python run.py
}

# 启动Celery Worker
start_celery_worker() {
    log_info "启动 Celery Worker..."
    celery -A app.tasks.celery_app worker \
        --loglevel=info \
        --concurrency=${CELERY_CONCURRENCY:-2} \
        --pool=solo
}

# 启动Celery Beat
start_celery_beat() {
    log_info "启动 Celery Beat (定时任务调度)..."
    celery -A app.tasks.celery_app beat \
        --loglevel=info \
        --schedule=data/celerybeat-schedule
}

# 启动所有服务（使用tmux）
start_all() {
    if ! command -v tmux &> /dev/null; then
        log_error "tmux 未安装，请先安装 tmux 或使用单独启动命令"
        log_info "macOS: brew install tmux"
        log_info "Ubuntu: sudo apt-get install tmux"
        exit 1
    fi

    SESSION_NAME="dataflow"
    
    # 检查会话是否存在
    if tmux has-session -t $SESSION_NAME 2>/dev/null; then
        log_warning "检测到已存在的 tmux 会话: $SESSION_NAME"
        read -p "是否重新创建？(y/N): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            tmux kill-session -t $SESSION_NAME
        else
            log_info "附加到现有会话..."
            tmux attach-session -t $SESSION_NAME
            exit 0
        fi
    fi

    # 创建新会话
    log_info "创建 tmux 会话: $SESSION_NAME"
    tmux new-session -d -s $SESSION_NAME -n "dataflow"
    
    # 分割窗口
    tmux split-window -h
    tmux split-window -v -t 0
    
    # 启动Flask (左上)
    tmux send-keys -t $SESSION_NAME:0.0 "cd $(pwd) && source venv/bin/activate && export \$(grep -v '^#' .env | xargs) && flask run --host=0.0.0.0 --port=${FLASK_PORT:-5000}" C-m
    
    # 启动Celery Worker (右上)
    tmux send-keys -t $SESSION_NAME:0.1 "cd $(pwd) && source venv/bin/activate && export \$(grep -v '^#' .env | xargs) && celery -A app.tasks.celery_app worker --loglevel=info --concurrency=${CELERY_CONCURRENCY:-2}" C-m
    
    # 启动Celery Beat (下)
    tmux send-keys -t $SESSION_NAME:0.2 "cd $(pwd) && source venv/bin/activate && export \$(grep -v '^#' .env | xargs) && celery -A app.tasks.celery_app beat --loglevel=info --schedule=data/celerybeat-schedule" C-m
    
    # 设置窗口布局
    tmux select-layout even-vertical
    
    log_success "所有服务已启动！"
    log_info "Flask:    http://localhost:${FLASK_PORT:-5000}"
    log_info "登录账户: admin@example.com / admin123"
    log_info ""
    log_info "使用以下命令查看/管理会话:"
    log_info "  tmux attach-session -t $SESSION_NAME   # 附加到会话"
    log_info "  tmux kill-session -t $SESSION_NAME     # 停止所有服务"
    log_info ""
    log_info "tmux 快捷键:"
    log_info "  Ctrl+b d    # 分离会话（后台运行）"
    log_info "  Ctrl+b x    # 关闭当前窗格"
    log_info "  Ctrl+b o    # 切换窗格"
    
    # 附加到会话
    read -p "是否立即附加到 tmux 会话？(Y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Nn]$ ]]; then
        tmux attach-session -t $SESSION_NAME
    fi
}

# 显示帮助
show_help() {
    echo "DataFlow 启动脚本"
    echo ""
    echo "使用方法: $0 [命令]"
    echo ""
    echo "命令:"
    echo "  all       启动所有服务 (Flask + Celery Worker + Celery Beat) 需要tmux"
    echo "  flask     仅启动 Flask 应用"
    echo "  celery    仅启动 Celery Worker"
    echo "  beat      仅启动 Celery Beat"
    echo "  init      仅初始化环境（不启动服务）"
    echo "  status    检查服务状态"
    echo "  stop      停止所有服务"
    echo "  help      显示此帮助信息"
    echo ""
    echo "示例:"
    echo "  $0              # 默认启动所有服务"
    echo "  $0 flask        # 仅启动Flask"
    echo "  $0 init         # 初始化环境"
    echo ""
}

# 检查服务状态
check_status() {
    log_info "检查服务状态..."
    
    # 检查Flask
    if curl -s http://localhost:${FLASK_PORT:-5000} > /dev/null 2>&1; then
        log_success "Flask 运行中 (端口 ${FLASK_PORT:-5000})"
    else
        log_warning "Flask 未运行"
    fi
    
    # 检查Redis
    if check_redis; then
        log_success "Redis 运行中"
    fi
    
    # 检查Celery Worker
    if pgrep -f "celery.*worker" > /dev/null; then
        log_success "Celery Worker 运行中"
    else
        log_warning "Celery Worker 未运行"
    fi
    
    # 检查Celery Beat
    if pgrep -f "celery.*beat" > /dev/null; then
        log_success "Celery Beat 运行中"
    else
        log_warning "Celery Beat 未运行"
    fi
}

# 停止服务
stop_services() {
    log_info "停止所有服务..."
    
    # 停止tmux会话
    if tmux has-session -t dataflow 2>/dev/null; then
        tmux kill-session -t dataflow
        log_success "tmux 会话已停止"
    fi
    
    # 停止Flask
    pkill -f "python run.py" 2>/dev/null || true
    pkill -f "flask run" 2>/dev/null || true
    
    # 停止Celery
    pkill -f "celery.*worker" 2>/dev/null || true
    pkill -f "celery.*beat" 2>/dev/null || true
    
    sleep 1
    
    # 确认
    if pgrep -f "(run.py|flask|celery)" > /dev/null; then
        log_warning "部分进程可能仍在运行，强制清理..."
        pkill -9 -f "(run.py|flask|celery)" 2>/dev/null || true
    fi
    
    log_success "所有服务已停止"
}

# 主逻辑
main() {
    COMMAND=${1:-all}
    
    case $COMMAND in
        all)
            check_venv
            install_dependencies
            check_env
            check_redis || true
            init_database
            start_all
            ;;
        flask)
            check_venv
            install_dependencies
            check_env
            check_redis || true
            init_database
            start_flask
            ;;
        celery)
            check_venv
            install_dependencies
            check_env
            check_redis || true
            start_celery_worker
            ;;
        beat)
            check_venv
            install_dependencies
            check_env
            check_redis || true
            start_celery_beat
            ;;
        init)
            check_venv
            install_dependencies
            check_env
            check_redis || true
            init_database
            log_success "环境初始化完成，可以启动服务了"
            ;;
        status)
            check_env
            check_status
            ;;
        stop)
            stop_services
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            log_error "未知命令: $COMMAND"
            show_help
            exit 1
            ;;
    esac
}

# 创建必要的目录
mkdir -p data instance uploads/reports uploads/snapshots

main "$@"
