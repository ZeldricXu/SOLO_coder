#!/usr/bin/env bash
# =============================================================================
# build-wasm.sh - WebAssembly 构建脚本
# 功能：使用 wasm-pack 将所有 crate 编译为 WebAssembly，输出到 pkg/ 目录
# 用法：./scripts/build-wasm.sh [--release|--dev] [--crate <name>]
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
CRATES_DIR="${PROJECT_ROOT}/crates"
PKG_DIR="${PROJECT_ROOT}/pkg"

# -----------------------------------------------------------------------------
# 默认配置
# -----------------------------------------------------------------------------
BUILD_MODE="release"
TARGET="web"
SPECIFIC_CRATE=""

# -----------------------------------------------------------------------------
# Crate 列表
# -----------------------------------------------------------------------------
CRATES=(
    "geometry"
    "crdt"
    "renderer"
    "stroke-engine"
    "resource-manager"
    "permission-history"
)

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
  --release        Release 模式构建（默认）
  --dev            Debug 模式构建
  --crate <name>   仅构建指定的 crate
  --target <tgt>   构建目标 (web|bundler|nodejs|no-modules)，默认 web
  -h, --help       显示帮助信息

示例:
  $0                      # 构建所有 crate (release 模式)
  $0 --dev                # 构建所有 crate (debug 模式)
  $0 --crate geometry     # 仅构建 geometry crate
EOF
}

# -----------------------------------------------------------------------------
# 参数解析
# -----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --release)
            BUILD_MODE="release"
            shift
            ;;
        --dev)
            BUILD_MODE="dev"
            shift
            ;;
        --crate)
            if [[ -z "${2:-}" ]]; then
                log_error "--crate 需要指定 crate 名称"
                exit 1
            fi
            SPECIFIC_CRATE="$2"
            shift 2
            ;;
        --target)
            if [[ -z "${2:-}" ]]; then
                log_error "--target 需要指定目标"
                exit 1
            fi
            TARGET="$2"
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
# 环境检查
# -----------------------------------------------------------------------------
check_environment() {
    log_step "环境检查"

    local has_error=0

    if ! command -v rustc &> /dev/null; then
        log_error "未找到 rustc，请先安装 Rust 工具链"
        has_error=1
    else
        log_info "rustc: $(rustc --version)"
    fi

    if ! command -v cargo &> /dev/null; then
        log_error "未找到 cargo，请先安装 Rust 工具链"
        has_error=1
    else
        log_info "cargo: $(cargo --version)"
    fi

    if ! command -v wasm-pack &> /dev/null; then
        log_error "未找到 wasm-pack，请先安装: cargo install wasm-pack"
        has_error=1
    else
        log_info "wasm-pack: $(wasm-pack --version)"
    fi

    if ! rustup target list --installed 2>/dev/null | grep -q wasm32-unknown-unknown; then
        log_error "未安装 wasm32-unknown-unknown 目标"
        log_info "安装命令: rustup target add wasm32-unknown-unknown"
        has_error=1
    else
        log_info "wasm32-unknown-unknown 目标已安装"
    fi

    if [[ $has_error -ne 0 ]]; then
        log_error "环境检查失败，请解决上述问题后重试"
        exit 1
    fi

    log_success "环境检查通过"
}

# -----------------------------------------------------------------------------
# 构建单个 crate
# -----------------------------------------------------------------------------
build_crate() {
    local crate_name="$1"
    local crate_dir="${CRATES_DIR}/${crate_name}"
    local crate_pkg_dir="${PKG_DIR}/${crate_name}"

    log_info "构建 crate: ${crate_name}"
    log_info "  源码目录: ${crate_dir}"
    log_info "  输出目录: ${crate_pkg_dir}"
    log_info "  构建模式: ${BUILD_MODE}"
    log_info "  目标类型: ${TARGET}"

    if [[ ! -d "${crate_dir}" ]]; then
        log_error "Crate 目录不存在: ${crate_dir}"
        return 1
    fi

    if [[ ! -f "${crate_dir}/Cargo.toml" ]]; then
        log_error "Crate Cargo.toml 不存在: ${crate_dir}/Cargo.toml"
        return 1
    fi

    # 构建参数
    local wasm_pack_args=(
        "build"
        "${crate_dir}"
        "--out-dir" "${crate_pkg_dir}"
        "--out-name" "${crate_name}"
        "--target" "${TARGET}"
    )

    if [[ "${BUILD_MODE}" == "release" ]]; then
        wasm_pack_args+=("--release")
    else
        wasm_pack_args+=("--dev")
    fi

    # 执行构建
    log_info "执行: wasm-pack ${wasm_pack_args[*]}"
    if ! wasm-pack "${wasm_pack_args[@]}"; then
        log_error "Crate ${crate_name} 构建失败"
        return 1
    fi

    # 清理 .gitignore 文件（wasm-pack 默认生成）
    if [[ -f "${crate_pkg_dir}/.gitignore" ]]; then
        rm -f "${crate_pkg_dir}/.gitignore"
    fi

    log_success "Crate ${crate_name} 构建成功"
    return 0
}

# -----------------------------------------------------------------------------
# 生成 pkg 汇总 index 文件
# -----------------------------------------------------------------------------
generate_pkg_index() {
    log_step "生成 pkg 汇总文件"

    local index_file="${PKG_DIR}/index.js"
    local index_dts="${PKG_DIR}/index.d.ts"

    cat > "${index_file}" <<'EOF'
// Auto-generated by build-wasm.sh
// 汇总导出所有 WASM 模块
EOF

    cat > "${index_dts}" <<'EOF'
// Auto-generated by build-wasm.sh
// TypeScript 类型声明汇总
EOF

    for crate in "${CRATES[@]}"; do
        local crate_pkg_dir="${PKG_DIR}/${crate}"
        if [[ -d "${crate_pkg_dir}" ]]; then
            echo "export * from './${crate}/${crate}.js';" >> "${index_file}"
            echo "export * from './${crate}/${crate}.d.ts';" >> "${index_dts}"
            log_info "添加模块导出: ${crate}"
        fi
    done

    # 生成 package.json
    local package_json="${PKG_DIR}/package.json"
    cat > "${package_json}" <<EOF
{
  "name": "df1-79-wasm",
  "version": "0.1.0",
  "description": "DF1-79 协作绘图引擎 - WebAssembly 模块",
  "main": "index.js",
  "types": "index.d.ts",
  "files": [
    "*.js",
    "*.d.ts",
    "*.wasm",
    "**/*.js",
    "**/*.d.ts",
    "**/*.wasm"
  ],
  "license": "MIT"
}
EOF

    log_success "pkg 汇总文件生成完成"
}

# -----------------------------------------------------------------------------
# 主流程
# -----------------------------------------------------------------------------
main() {
    log_step "DF1-79 WebAssembly 构建"
    log_info "项目根目录: ${PROJECT_ROOT}"
    log_info "构建模式: ${BUILD_MODE}"
    log_info "输出目录: ${PKG_DIR}"

    # 环境检查
    check_environment

    # 创建输出目录
    log_step "准备输出目录"
    mkdir -p "${PKG_DIR}"
    log_info "输出目录已就绪: ${PKG_DIR}"

    # 确定要构建的 crate 列表
    local crates_to_build=("${CRATES[@]}")
    if [[ -n "${SPECIFIC_CRATE}" ]]; then
        crates_to_build=("${SPECIFIC_CRATE}")
        log_info "仅构建指定 crate: ${SPECIFIC_CRATE}"
    fi

    # 构建每个 crate
    local failed_crates=()
    for crate in "${crates_to_build[@]}"; do
        log_step "构建 Crate: ${crate}"
        if ! build_crate "${crate}"; then
            failed_crates+=("${crate}")
        fi
    done

    # 检查构建结果
    if [[ ${#failed_crates[@]} -gt 0 ]]; then
        log_error "以下 crate 构建失败: ${failed_crates[*]}"
        exit 1
    fi

    # 生成汇总文件
    if [[ -z "${SPECIFIC_CRATE}" ]]; then
        generate_pkg_index
    fi

    # 完成
    log_step "构建完成"
    log_success "所有 WebAssembly 模块已构建到: ${PKG_DIR}"
    echo ""
    log_info "输出内容:"
    if command -v tree &> /dev/null; then
        tree -L 2 "${PKG_DIR}"
    else
        find "${PKG_DIR}" -maxdepth 2 -type f | sort
    fi
}

main "$@"
