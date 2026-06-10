#!/usr/bin/env bash
# =============================================================================
# dev.sh - 开发模式启动脚本
# 功能：并发启动 WASM 文件监听、前端开发服务器、后端开发服务器
# 用法：./scripts/dev.sh [--no-wasm|--no-web|--no-server] [--port <port>]
# =============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# 颜色定义
# -----------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
NC='\033[0m'

# -----------------------------------------------------------------------------
# 脚本目录与项目根目录
# -----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CRATES_DIR="${PROJECT_ROOT}/crates"
WEB_DIR="${PROJECT_ROOT}/web"
SERVER_DIR="${PROJECT_ROOT}/server"
PKG_DIR="${PROJECT_ROOT}/pkg"

# -----------------------------------------------------------------------------
# 配置
# -----------------------------------------------------------------------------
ENABLE_WASM=true
ENABLE_WEB=true
ENABLE_SERVER=true
WEB_PORT=8080
SERVER_PORT=3000

# 存储子进程 PID
PID_WASM=""
PID_WEB=""
PID_SERVER=""

# -----------------------------------------------------------------------------
# 辅助函数
# -----------------------------------------------------------------------------
log_info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo ""
    echo -e "${BOLD}${CYAN}========== $1 ==========${NC}"
}

log_wasm() {
    echo -e "${MAGENTA}[WASM]${NC} $1"
}

log_web() {
    echo -e "${CYAN}[WEB ]${NC} $1"
}

log_server() {
    echo -e "${GREEN}[SRV ]${NC} $1"
}

usage() {
    cat <<EOF
用法: $0 [OPTIONS]

选项:
  --no-wasm        禁用 WASM 文件监听
  --no-web         禁用前端开发服务器
  --no-server      禁用后端开发服务器
  --web-port <n>   前端服务器端口 (默认: 8080)
  --server-port <n> 后端服务器端口 (默认: 3000)
  -h, --help       显示帮助信息

示例:
  $0                      # 启动所有服务
  $0 --no-server          # 仅启动 WASM 监听和前端服务
  $0 --web-port 3000      # 指定前端端口
EOF
}

# -----------------------------------------------------------------------------
# 参数解析
# -----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-wasm)
            ENABLE_WASM=false
            shift
            ;;
        --no-web)
            ENABLE_WEB=false
            shift
            ;;
        --no-server)
            ENABLE_SERVER=false
            shift
            ;;
        --web-port)
            if [[ -z "${2:-}" ]]; then
                log_error "--web-port 需要指定端口号"
                exit 1
            fi
            WEB_PORT="$2"
            shift 2
            ;;
        --server-port)
            if [[ -z "${2:-}" ]]; then
                log_error "--server-port 需要指定端口号"
                exit 1
            fi
            SERVER_PORT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            log_error "未知参数: $1"
            usage
            exit 1
            ;;
    esac
done

# -----------------------------------------------------------------------------
# 清理函数 - 终止所有子进程
# -----------------------------------------------------------------------------
cleanup() {
    echo ""
    log_step "正在停止所有服务..."

    if [[ -n "${PID_WASM}" ]] && kill -0 "${PID_WASM}" 2>/dev/null; then
        log_wasm "停止 WASM 监听服务 (PID: ${PID_WASM})"
        kill "${PID_WASM}" 2>/dev/null || true
        wait "${PID_WASM}" 2>/dev/null || true
    fi

    if [[ -n "${PID_WEB}" ]] && kill -0 "${PID_WEB}" 2>/dev/null; then
        log_web "停止前端开发服务器 (PID: ${PID_WEB})"
        kill "${PID_WEB}" 2>/dev/null || true
        wait "${PID_WEB}" 2>/dev/null || true
    fi

    if [[ -n "${PID_SERVER}" ]] && kill -0 "${PID_SERVER}" 2>/dev/null; then
        log_server "停止后端开发服务器 (PID: ${PID_SERVER})"
        kill "${PID_SERVER}" 2>/dev/null || true
        wait "${PID_SERVER}" 2>/dev/null || true
    fi

    log_success "所有服务已停止"
    exit 0
}

# 注册信号处理
trap cleanup SIGINT SIGTERM EXIT

# -----------------------------------------------------------------------------
# 首次 WASM 构建
# -----------------------------------------------------------------------------
initial_wasm_build() {
    if [[ "${ENABLE_WASM}" != "true" ]]; then
        return 0
    fi

    log_step "首次构建 WebAssembly 模块"

    if [[ ! -x "${SCRIPT_DIR}/build-wasm.sh" ]]; then
        chmod +x "${SCRIPT_DIR}/build-wasm.sh"
    fi

    if ! "${SCRIPT_DIR}/build-wasm.sh" --dev; then
        log_warning "首次 WASM 构建失败，将继续启动其他服务"
    fi
}

# -----------------------------------------------------------------------------
# 启动 WASM 文件监听
# -----------------------------------------------------------------------------
start_wasm_watcher() {
    if [[ "${ENABLE_WASM}" != "true" ]]; then
        log_warning "WASM 监听已禁用"
        return 0
    fi

    log_step "启动 WASM 文件监听"

    if ! command -v cargo-watch &> /dev/null; then
        log_error "未安装 cargo-watch"
        log_info "安装命令: cargo install cargo-watch"
        log_warning "WASM 文件监听将不可用"
        return 1
    fi

    log_wasm "监听目录: ${CRATES_DIR}"
    log_wasm "文件变化时自动重新构建 WASM"

    # 使用 cargo-watch 监听 crates 目录
    (
        cargo-watch \
            --watch "${CRATES_DIR}" \
            --shell "${SCRIPT_DIR}/build-wasm.sh --dev" \
            --ignore "${PKG_DIR}/*" \
            --postpone
    ) &
    PID_WASM=$!

    log_wasm "已启动 (PID: ${PID_WASM})"
}

# -----------------------------------------------------------------------------
# 启动前端开发服务器
# -----------------------------------------------------------------------------
start_web_server() {
    if [[ "${ENABLE_WEB}" != "true" ]]; then
        log_warning "前端开发服务器已禁用"
        return 0
    fi

    if [[ ! -d "${WEB_DIR}" ]]; then
        log_warning "前端目录不存在: ${WEB_DIR}，跳过启动前端服务器"
        return 0
    fi

    if [[ ! -f "${WEB_DIR}/package.json" ]]; then
        log_warning "前端缺少 package.json，跳过启动前端服务器"
        return 0
    fi

    log_step "启动前端开发服务器"
    log_web "工作目录: ${WEB_DIR}"
    log_web "监听端口: ${WEB_PORT}"

    # 安装依赖（首次或 package.json 变化时）
    if [[ ! -d "${WEB_DIR}/node_modules" ]]; then
        log_web "安装前端依赖..."
        (cd "${WEB_DIR}" && npm install) || {
            log_error "前端依赖安装失败"
            return 1
        }
    fi

    # 检测可用的 dev 脚本
    local dev_script="dev"
    if (cd "${WEB_DIR}" && npm run | grep -q "vite"); then
        dev_script="dev"
    elif (cd "${WEB_DIR}" && npm run | grep -q "start"); then
        dev_script="start"
    fi

    # 启动开发服务器
    (
        cd "${WEB_DIR}"
        export PORT="${WEB_PORT}"
        npm run "${dev_script}" -- --port "${WEB_PORT}" 2>&1 | while IFS= read -r line; do
            log_web "${line}"
        done
    ) &
    PID_WEB=$!

    log_web "已启动 (PID: ${PID_WEB})"
}

# -----------------------------------------------------------------------------
# 启动后端开发服务器
# -----------------------------------------------------------------------------
start_server() {
    if [[ "${ENABLE_SERVER}" != "true" ]]; then
        log_warning "后端开发服务器已禁用"
        return 0
    fi

    if [[ ! -d "${SERVER_DIR}" ]]; then
        log_warning "后端目录不存在: ${SERVER_DIR}，跳过启动后端服务器"
        return 0
    fi

    log_step "启动后端开发服务器"
    log_server "工作目录: ${SERVER_DIR}"
    log_server "监听端口: ${SERVER_PORT}"

    # 检测后端项目类型并启动
    if [[ -f "${SERVER_DIR}/Cargo.toml" ]]; then
        log_server "检测到 Rust (Cargo) 项目"
        (
            cd "${SERVER_DIR}"
            export PORT="${SERVER_PORT}"
            cargo run 2>&1 | while IFS= read -r line; do
                log_server "${line}"
            done
        ) &
        PID_SERVER=$!

    elif [[ -f "${SERVER_DIR}/go.mod" ]]; then
        log_server "检测到 Go 项目"
        (
            cd "${SERVER_DIR}"
            export PORT="${SERVER_PORT}"
            go run . 2>&1 | while IFS= read -r line; do
                log_server "${line}"
            done
        ) &
        PID_SERVER=$!

    elif [[ -f "${SERVER_DIR}/package.json" ]]; then
        log_server "检测到 Node.js 项目"

        if [[ ! -d "${SERVER_DIR}/node_modules" ]]; then
            log_server "安装后端依赖..."
            (cd "${SERVER_DIR}" && npm install) || {
                log_error "后端依赖安装失败"
                return 1
            }
        fi

        (
            cd "${SERVER_DIR}"
            export PORT="${SERVER_PORT}"
            npm run dev 2>&1 | while IFS= read -r line; do
                log_server "${line}"
            done
        ) &
        PID_SERVER=$!

    else
        log_warning "未识别后端项目类型，跳过启动后端服务器"
        return 0
    fi

    log_server "已启动 (PID: ${PID_SERVER})"
}

# -----------------------------------------------------------------------------
# 打印服务状态
# -----------------------------------------------------------------------------
print_status() {
    echo ""
    log_step "开发服务已启动"
    echo ""
    echo -e "${BOLD}运行中的服务:${NC}"

    if [[ -n "${PID_WASM}" ]] && kill -0 "${PID_WASM}" 2>/dev/null; then
        echo -e "  ${MAGENTA}●${NC} WASM 监听服务    (PID: ${PID_WASM})"
    fi

    if [[ -n "${PID_WEB}" ]] && kill -0 "${PID_WEB}" 2>/dev/null; then
        echo -e "  ${CYAN}●${NC} 前端开发服务器  (PID: ${PID_WEB}) -> http://localhost:${WEB_PORT}"
    fi

    if [[ -n "${PID_SERVER}" ]] && kill -0 "${PID_SERVER}" 2>/dev/null; then
        echo -e "  ${GREEN}●${NC} 后端开发服务器  (PID: ${PID_SERVER}) -> http://localhost:${SERVER_PORT}"
    fi

    echo ""
    echo -e "按 ${BOLD}Ctrl+C${NC} 停止所有服务"
    echo ""
}

# -----------------------------------------------------------------------------
# 等待子进程
# -----------------------------------------------------------------------------
wait_for_processes() {
    local any_running=true

    while ${any_running}; do
        any_running=false

        if [[ -n "${PID_WASM}" ]] && kill -0 "${PID_WASM}" 2>/dev/null; then
            any_running=true
        fi
        if [[ -n "${PID_WEB}" ]] && kill -0 "${PID_WEB}" 2>/dev/null; then
            any_running=true
        fi
        if [[ -n "${PID_SERVER}" ]] && kill -0 "${PID_SERVER}" 2>/dev/null; then
            any_running=true
        fi

        if ${any_running}; then
            sleep 1
        fi
    done
}

# -----------------------------------------------------------------------------
# 主流程
# -----------------------------------------------------------------------------
main() {
    log_step "DF1-79 开发模式"
    log_info "项目根目录: ${PROJECT_ROOT}"

    # 首次构建 WASM
    initial_wasm_build

    # 启动各服务
    start_wasm_watcher
    start_web_server
    start_server

    # 打印状态
    print_status

    # 等待服务运行
    wait_for_processes
}

main "$@"
