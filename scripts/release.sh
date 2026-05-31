#!/bin/bash
set -euo pipefail

# ========================================
# 自动化版本发布脚本
# ========================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# ========================================
# 函数定义
# ========================================

print_header() {
    echo ""
    echo "========================================"
    echo "🚀 $1"
    echo "========================================"
    echo ""
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
    exit 1
}

print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

confirm() {
    read -p "$1 (y/n): " choice
    case "$choice" in
        y|Y ) return 0;;
        n|N ) return 1;;
        * ) print_error "无效输入";;
    esac
}

# ========================================
# 检查必要条件
# ========================================

check_prerequisites() {
    print_header "检查发布前置条件"

    # 检查 Git 状态
    if ! git diff-index --quiet HEAD --; then
        print_error "工作目录不干净，请先提交或暂存所有更改"
    fi

    # 检查主分支
    current_branch=$(git rev-parse --abbrev-ref HEAD)
    if [[ "$current_branch" != "main" && "$current_branch" != "master" ]]; then
        print_warning "当前分支为 $current_branch，建议在 main/master 分支发布"
        if ! confirm "是否继续？"; then
            exit 0
        fi
    fi

    # 检查依赖
    if ! command -v bump2version >/dev/null 2>&1; then
        print_error "bump2version 未安装，请先运行: pip install bump2version"
    fi

    # 运行测试
    print_info "运行所有测试..."
    if ! make test-cov; then
        print_error "测试未通过，请修复后再发布"
    fi
    print_success "测试通过"

    # 代码质量检查
    print_info "运行代码质量检查..."
    if ! make quality; then
        print_warning "代码质量检查未完全通过"
        if ! confirm "是否继续？"; then
            exit 0
        fi
    fi
    print_success "代码质量检查通过"
}

# ========================================
# 获取下一个版本号
# ========================================

get_next_version() {
    local version_type=$1
    local current_version

    current_version=$(grep -E '^version = ' pyproject.toml | head -1 | sed 's/version = "\(.*\)"/\1/')
    print_info "当前版本: $current_version"

    IFS='.' read -r major minor patch <<< "$current_version"

    case "$version_type" in
        patch)
            patch=$((patch + 1))
            ;;
        minor)
            minor=$((minor + 1))
            patch=0
            ;;
        major)
            major=$((major + 1))
            minor=0
            patch=0
            ;;
        *)
            print_error "未知的版本类型: $version_type"
            ;;
    esac

    echo "$major.$minor.$patch"
}

# ========================================
# 更新 CHANGELOG
# ========================================

update_changelog() {
    local new_version=$1
    local release_date=$(date +%Y-%m-%d)

    print_info "更新 CHANGELOG..."

    # 读取当前 CHANGELOG
    local changelog_content
    changelog_content=$(cat CHANGELOG.md)

    # 生成新的 CHANGELOG 头部
    local new_entry="## [$new_version] - $release_date\n\n### ✨ Features\n\n- 待补充\n\n### 🐛 Bug Fixes\n\n- 待补充\n\n"

    # 插入新条目（在 --- 之后）
    local updated_changelog
    updated_changelog=$(echo -e "$changelog_content" | sed "s/^---$/---\n\n$new_entry/")

    echo -e "$updated_changelog" > CHANGELOG.md
    print_success "CHANGELOG 已更新"
}

# ========================================
# 主发布流程
# ========================================

main() {
    local version_type=${1:-patch}

    case "$version_type" in
        patch|minor|major)
            ;;
        *)
            echo "用法: $0 [patch|minor|major]"
            echo "  patch  - 发布补丁版本 (x.y.z -> x.y.z+1)"
            echo "  minor  - 发布次版本   (x.y.z -> x.y+1.0)"
            echo "  major  - 发布主版本   (x.y.z -> x+1.0.0)"
            exit 1
            ;;
    esac

    print_header "工单智能分配系统 - 版本发布"

    check_prerequisites

    local next_version
    next_version=$(get_next_version "$version_type")
    print_info "即将发布版本: $next_version"

    if ! confirm "确认发布版本 $next_version？"; then
        print_info "发布已取消"
        exit 0
    fi

    # 更新版本号
    print_header "更新版本号"
    bump2version "$version_type" --no-commit --no-tag
    print_success "版本号已更新为 $next_version"

    # 更新 CHANGELOG
    update_changelog "$next_version"

    # 提交更改
    print_header "提交更改"
    git add pyproject.toml app/main.py CHANGELOG.md
    git commit -m "Bump version: $next_version"

    # 创建标签
    git tag -a "v$next_version" -m "Release v$next_version"

    print_success "版本 $next_version 发布准备完成！"
    echo ""
    print_info "执行以下命令完成发布："
    echo "  git push origin main"
    echo "  git push origin v$next_version"
    echo ""
    print_info "或执行以下命令取消本次发布："
    echo "  git reset --hard HEAD~1"
    echo "  git tag -d v$next_version"
}

# ========================================
# 执行主流程
# ========================================

main "$@"
