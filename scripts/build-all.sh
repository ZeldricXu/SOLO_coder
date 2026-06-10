#!/usr/bin/env bash
# =============================================================================
# build-all.sh - 一键构建所有组件
# 功能：依次构建 WebAssembly 模块、前端 Web 应用、后端服务
# 用法：./scripts/build-all.sh [--skip-wasm|--skip-web|--skip-server]
# =============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# 颜色定义
# -----------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# -----------------------------------------------------------------------------
# 脚本目录与项目根目录
# -----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WEB_DIR="${PROJECT_ROOT}/web"
SERVER_DIR="${PROJECT_ROOT}/server"
PKG_DIR="${PROJECT_ROOT}/pkg"

# -----------------------------------------------------------------------------
# 构建开关
# -----------------------------------------------------------------------------
BUILD_WASM=true
BUILD_WEB=true
BUILD_SERVER=true

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

usage() {
    cat <<EOF
用法: $0 [OPTIONS]

选项:
  --skip-wasm      跳过 WebAssembly 构建
  --skip-web       跳过前端 Web 应用构建
  --skip-server    跳过后端服务构建
  --release        Release 模式（默认）
  --dev            Debug 模式
  -h, --help       显示帮助信息

示例:
  $0                      # 构建所有组件
  $0 --skip-server        # 仅构建 WASM 和前端
  $0 --skip-wasm --dev    # 跳过 WASM，以 Debug 模式构建前端和后端
EOF
}

# -----------------------------------------------------------------------------
# 参数解析
# -----------------------------------------------------------------------------
BUILD_MODE="release"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-wasm)
            BUILD_WASM=false
            shift
            ;;
        --skip-web)
            BUILD_WEB=false
            shift
            ;;
        --skip-server)
            BUILD_SERVER=false
            shift
            ;;
        --release)
            BUILD_MODE="release"
            shift
            ;;
        --dev)
            BUILD_MODE="dev"
            shift
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
# 构建 WebAssembly 模块
# -----------------------------------------------------------------------------
build_wasm() {
    log_step "步骤 1/3: 构建 WebAssembly 模块"

    if [[ "${BUILD_WASM}" != "true" ]]; then
        log_warning "已跳过 WASM 构建"
        return 0
    fi

    local build_script="${SCRIPT_DIR}/build-wasm.sh"

    if [[ ! -x "${build_script}" ]]; then
        log_warning "build-wasm.sh 没有执行权限，正在添加..."
        chmod +x "${build_script}"
    fi

    local build_args=()
    if [[ "${BUILD_MODE}" == "dev" ]]; then
        build_args+=("--dev")
    fi

    if ! "${build_script}" "${build_args[@]}"; then
        log_error "WebAssembly 构建失败"
        return 1
    fi

    log_success "WebAssembly 构建完成"
    return 0
}

# -----------------------------------------------------------------------------
# 构建前端 Web 应用
# -----------------------------------------------------------------------------
build_web() {
    log_step "步骤 2/3: 构建前端 Web 应用"

    if [[ "${BUILD_WEB}" != "true" ]]; then
        log_warning "已跳过前端构建"
        return 0
    fi

    if [[ ! -d "${WEB_DIR}" ]]; then
        log_warning "前端目录不存在: ${WEB_DIR}，跳过前端构建"
        return 0
    fi

    if [[ ! -f "${WEB_DIR}/package.json" ]]; then
        log_warning "前端缺少 package.json: ${WEB_DIR}/package.json，跳过前端构建"
        return 0
    fi

    log_info "前端目录: ${WEB_DIR}"
    log_info "构建模式: ${BUILD_MODE}"

    # 安装依赖
    log_info "安装前端依赖..."
    if ! (cd "${WEB_DIR}" && npm install); then
        log_error "前端依赖安装失败"
        return 1
    fi

    # 执行构建
    log_info "执行前端构建..."
    local npm_script="build"
    if [[ "${BUILD_MODE}" == "dev" ]]; then
        npm_script="build:dev"
    fi

    if ! (cd "${WEB_DIR}" && npm run "${npm_script}"); then
        log_error "前端构建失败"
        return 1
    fi

    log_success "前端构建完成"
    return 0
}

# -----------------------------------------------------------------------------
# 构建后端服务
# -----------------------------------------------------------------------------
build_server() {
    log_step "步骤 3/3: 构建后端服务"

    if [[ "${BUILD_SERVER}" != "true" ]]; then
        log_warning "已跳过后端构建"
        return 0
    fi

    if [[ ! -d "${SERVER_DIR}" ]]; then
        log_warning "后端目录不存在: ${SERVER_DIR}，跳过后端构建"
        return 0
    fi

    log_info "后端目录: ${SERVER_DIR}"
    log_info "构建模式: ${BUILD_MODE}"

    # 检测后端项目类型并执行构建
    if [[ -f "${SERVER_DIR}/Cargo.toml" ]]; then
        log_info "检测到 Rust (Cargo) 项目"

        local cargo_args=("build")
        if [[ "${BUILD_MODE}" == "release" ]]; then
            cargo_args+=("--release")
        fi

        if ! (cd "${SERVER_DIR}" && cargo "${cargo_args[@]}"); then
            log_error "后端 Rust 构建失败"
            return 1
        fi

    elif [[ -f "${SERVER_DIR}/go.mod" ]]; then
        log_info "检测到 Go 项目"

        local output_flag=()
        if [[ "${BUILD_MODE}" == "release" ]]; then
            output_flag=(-o "bin/server")
        else
            output_flag=(-o "bin/server-dev")
        fi

        mkdir -p "${SERVER_DIR}/bin"
        if ! (cd "${SERVER_DIR}" && go build "${output_flag[@]}"); then
            log_error "后端 Go 构建失败"
            return 1
        fi

    elif [[ -f "${SERVER_DIR}/package.json" ]]; then
        log_info "检测到 Node.js 项目"

        log_info "安装后端依赖..."
        if ! (cd "${SERVER_DIR}" && npm install); then
            log_error "后端依赖安装失败"
            return 1
        fi

        local npm_script="build"
        if [[ "${BUILD_MODE}" == "dev" ]]; then
            npm_script="build:dev"
        fi

        if ! (cd "${SERVER_DIR}" && npm run "${npm_script}"); then
            log_error "后端 Node.js 构建失败"
            return 1
        fi

    else
        log_warning "未识别后端项目类型，跳过后端构建"
        return 0
    fi

    log_success "后端构建完成"
    return 0
}

# -----------------------------------------------------------------------------
# 打印构建摘要
# -----------------------------------------------------------------------------
print_summary() {
    log_step "构建摘要"

    echo ""
    echo -e "${BOLD}构建结果:${NC}"
    echo -e "  WebAssembly:  $([[ "${BUILD_WASM}" == "true" ]] && echo -e "${GREEN}已构建${NC}" || echo -e "${YELLOW}已跳过${NC}")"
    echo -e "  前端 Web:     $([[ "${BUILD_WEB}" == "true" ]] && echo -e "${GREEN}已构建${NC}" || echo -e "${YELLOW}已跳过${NC}")"
    echo -e "  后端服务:     $([[ "${BUILD_SERVER}" == "true" ]] && echo -e "${GREEN}已构建${NC}" || echo -e "${YELLOW}已跳过${NC}")"
    echo ""
    echo -e "${BOLD}构建模式:${NC} ${BUILD_MODE}"
    echo ""

    # 显示产物位置
    if [[ "${BUILD_WASM}" == "true" ]] && [[ -d "${PKG_DIR}" ]]; then
        echo -e "${BOLD}WASM 产物:${NC} ${PKG_DIR}"
    fi
    if [[ "${BUILD_WEB}" == "true" ]] && [[ -d "${WEB_DIR}/dist" ]]; then
        echo -e "${BOLD}前端产物:${NC} ${WEB_DIR}/dist"
    fi
    if [[ "${BUILD_SERVER}" == "true" ]] && [[ -d "${SERVER_DIR}" ]]; then
        if [[ -f "${SERVER_DIR}/Cargo.toml" ]]; then
            echo -e "${BOLD}后端产物:${NC} ${SERVER_DIR}/target/${BUILD_MODE}/"
        elif [[ -f "${SERVER_DIR}/go.mod" ]]; then
            echo -e "${BOLD}后端产物:${NC} ${SERVER_DIR}/bin/"
        fi
    fi

    echo ""
}

# -----------------------------------------------------------------------------
# 主流程
# -----------------------------------------------------------------------------
main() {
    local start_time
    start_time=$(date +%s)

    log_step "DF1-79 一键构建"
    log_info "项目根目录: ${PROJECT_ROOT}"
    log_info "构建模式: ${BUILD_MODE}"
    log_info "构建组件:"
    log_info "  - WebAssembly: $([[ "${BUILD_WASM}" == "true" ]] && echo "是" || echo "否")"
    log_info "  - 前端 Web:    $([[ "${BUILD_WEB}" == "true" ]] && echo "是" || echo "否")"
    log_info "  - 后端服务:    $([[ "${BUILD_SERVER}" == "true" ]] && echo "是" || echo "否")"

    # 依次执行构建
    build_wasm
    build_web
    build_server

    # 计算耗时
    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))
    local minutes=$((duration / 60))
    local seconds=$((duration % 60))

    # 打印摘要
    print_summary

    log_success "所有构建已完成！耗时: ${minutes} 分 ${seconds} 秒"
}

main "$@"
