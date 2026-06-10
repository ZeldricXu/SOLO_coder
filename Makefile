# =============================================================================
# DF1-79 协作绘图引擎 - Makefile
# 支持 macOS / Linux
# =============================================================================

SHELL := /bin/bash

# -----------------------------------------------------------------------------
# 颜色定义 (用于终端输出)
# -----------------------------------------------------------------------------
COLOR_RESET  := \033[0m
COLOR_GREEN  := \033[32m
COLOR_YELLOW := \033[33m
COLOR_RED    := \033[31m
COLOR_CYAN   := \033[36m
COLOR_BOLD   := \033[1m

# -----------------------------------------------------------------------------
# 项目路径
# -----------------------------------------------------------------------------
PROJECT_ROOT := $(shell pwd)
CRATES_DIR   := $(PROJECT_ROOT)/crates
PKG_DIR      := $(PROJECT_ROOT)/pkg
SCRIPTS_DIR  := $(PROJECT_ROOT)/scripts
WEB_DIR      := $(PROJECT_ROOT)/web
SERVER_DIR   := $(PROJECT_ROOT)/server

# -----------------------------------------------------------------------------
# Crate 列表
# -----------------------------------------------------------------------------
CRATES := geometry crdt renderer stroke-engine resource-manager permission-history

# -----------------------------------------------------------------------------
# 默认目标
# -----------------------------------------------------------------------------
.PHONY: all
all: help

# -----------------------------------------------------------------------------
# 帮助信息
# -----------------------------------------------------------------------------
.PHONY: help
help:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)DF1-79 协作绘图引擎 - 可用命令$(COLOR_RESET)"
	@echo ""
	@echo -e "$(COLOR_BOLD)构建相关:$(COLOR_RESET)"
	@echo -e "  $(COLOR_GREEN)make build-wasm$(COLOR_RESET)        构建所有 WebAssembly 模块到 pkg/ 目录"
	@echo -e "  $(COLOR_GREEN)make build-web$(COLOR_RESET)         构建前端 Web 应用"
	@echo -e "  $(COLOR_GREEN)make build-server$(COLOR_RESET)      构建后端服务"
	@echo -e "  $(COLOR_GREEN)make build-all$(COLOR_RESET)         一键构建所有组件 (wasm + web + server)"
	@echo -e "  $(COLOR_GREEN)make build-crate CRATE=<name>$(COLOR_RESET)  构建单个 crate"
	@echo ""
	@echo -e "$(COLOR_BOLD)开发相关:$(COLOR_RESET)"
	@echo -e "  $(COLOR_GREEN)make dev$(COLOR_RESET)               启动开发模式 (自动监听文件变化)"
	@echo -e "  $(COLOR_GREEN)make dev-wasm$(COLOR_RESET)           仅监听 WASM 源码变化"
	@echo -e "  $(COLOR_GREEN)make dev-web$(COLOR_RESET)            启动前端开发服务器"
	@echo -e "  $(COLOR_GREEN)make dev-server$(COLOR_RESET)         启动后端开发服务器"
	@echo ""
	@echo -e "$(COLOR_BOLD)测试与检查:$(COLOR_RESET)"
	@echo -e "  $(COLOR_GREEN)make test$(COLOR_RESET)              运行所有测试"
	@echo -e "  $(COLOR_GREEN)make lint$(COLOR_RESET)              运行 Clippy 代码检查"
	@echo -e "  $(COLOR_GREEN)make fmt$(COLOR_RESET)               格式化所有 Rust 代码"
	@echo -e "  $(COLOR_GREEN)make fmt-check$(COLOR_RESET)         检查代码格式"
	@echo ""
	@echo -e "$(COLOR_BOLD)Docker 相关:$(COLOR_RESET)"
	@echo -e "  $(COLOR_GREEN)make docker-up$(COLOR_RESET)          构建并启动所有 Docker 服务"
	@echo -e "  $(COLOR_GREEN)make docker-down$(COLOR_RESET)        停止所有 Docker 服务"
	@echo -e "  $(COLOR_GREEN)make docker-logs$(COLOR_RESET)        查看 Docker 服务日志"
	@echo ""
	@echo -e "$(COLOR_BOLD)工具命令:$(COLOR_RESET)"
	@echo -e "  $(COLOR_GREEN)make chmod$(COLOR_RESET)             为所有脚本添加执行权限"
	@echo -e "  $(COLOR_GREEN)make clean$(COLOR_RESET)             清理所有构建产物"
	@echo -e "  $(COLOR_GREEN)make clean-wasm$(COLOR_RESET)         仅清理 WASM 构建产物"
	@echo -e "  $(COLOR_GREEN)make doctor$(COLOR_RESET)            检查开发环境依赖"
	@echo ""

# =============================================================================
# 构建命令
# =============================================================================

.PHONY: chmod
chmod:
	@echo -e "$(COLOR_YELLOW)→ 为脚本添加执行权限...$(COLOR_RESET)"
	@chmod +x $(SCRIPTS_DIR)/*.sh
	@echo -e "$(COLOR_GREEN)✓ 已完成$(COLOR_RESET)"

.PHONY: build-wasm
build-wasm: chmod
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 构建 WebAssembly 模块 ==========$(COLOR_RESET)"
	@$(SCRIPTS_DIR)/build-wasm.sh

.PHONY: build-crate
build-crate: chmod
	@if [ -z "$(CRATE)" ]; then \
		echo -e "$(COLOR_RED)✗ 请指定 CRATE 参数，例如: make build-crate CRATE=geometry$(COLOR_RESET)"; \
		exit 1; \
	fi
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 构建 Crate: $(CRATE) ==========$(COLOR_RESET)"
	@wasm-pack build $(CRATES_DIR)/$(CRATE) --out-dir $(PKG_DIR)/$(CRATE) --target web --release
	@echo -e "$(COLOR_GREEN)✓ Crate $(CRATE) 构建完成$(COLOR_RESET)"

.PHONY: build-web
build-web: build-wasm
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 构建前端 Web 应用 ==========$(COLOR_RESET)"
	@if [ -d "$(WEB_DIR)" ] && [ -f "$(WEB_DIR)/package.json" ]; then \
		cd $(WEB_DIR) && npm install && npm run build; \
	else \
		echo -e "$(COLOR_YELLOW)⚠ 前端目录 $(WEB_DIR) 不存在或缺少 package.json，跳过前端构建$(COLOR_RESET)"; \
	fi
	@echo -e "$(COLOR_GREEN)✓ 前端构建完成$(COLOR_RESET)"

.PHONY: build-server
build-server:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 构建后端服务 ==========$(COLOR_RESET)"
	@if [ -d "$(SERVER_DIR)" ]; then \
		if [ -f "$(SERVER_DIR)/Cargo.toml" ]; then \
			cd $(SERVER_DIR) && cargo build --release; \
		elif [ -f "$(SERVER_DIR)/go.mod" ]; then \
			cd $(SERVER_DIR) && go build -o bin/server; \
		elif [ -f "$(SERVER_DIR)/package.json" ]; then \
			cd $(SERVER_DIR) && npm install && npm run build; \
		else \
			echo -e "$(COLOR_YELLOW)⚠ 未识别后端项目类型，跳过后端构建$(COLOR_RESET)"; \
		fi; \
	else \
		echo -e "$(COLOR_YELLOW)⚠ 后端目录 $(SERVER_DIR) 不存在，跳过后端构建$(COLOR_RESET)"; \
	fi
	@echo -e "$(COLOR_GREEN)✓ 后端构建完成$(COLOR_RESET)"

.PHONY: build-all
build-all: chmod
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 一键构建所有组件 ==========$(COLOR_RESET)"
	@$(SCRIPTS_DIR)/build-all.sh

# =============================================================================
# 开发命令
# =============================================================================

.PHONY: dev
dev: chmod
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 启动开发模式 ==========$(COLOR_RESET)"
	@$(SCRIPTS_DIR)/dev.sh

.PHONY: dev-wasm
dev-wasm: chmod
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== WASM 开发模式 ==========$(COLOR_RESET)"
	@echo -e "$(COLOR_YELLOW)→ 监听 crates/ 目录变化，自动重新构建 WASM...$(COLOR_RESET)"
	@if command -v cargo-watch &> /dev/null; then \
		cargo watch -x "build --target wasm32-unknown-unknown" -w crates; \
	else \
		echo -e "$(COLOR_RED)✗ 未安装 cargo-watch，请先运行: cargo install cargo-watch$(COLOR_RESET)"; \
		exit 1; \
	fi

.PHONY: dev-web
dev-web: build-wasm
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 前端开发服务器 ==========$(COLOR_RESET)"
	@if [ -d "$(WEB_DIR)" ] && [ -f "$(WEB_DIR)/package.json" ]; then \
		cd $(WEB_DIR) && npm install && npm run dev; \
	else \
		echo -e "$(COLOR_YELLOW)⚠ 前端目录不存在或缺少 package.json$(COLOR_RESET)"; \
	fi

.PHONY: dev-server
dev-server:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 后端开发服务器 ==========$(COLOR_RESET)"
	@if [ -d "$(SERVER_DIR)" ]; then \
		if [ -f "$(SERVER_DIR)/Cargo.toml" ]; then \
			cd $(SERVER_DIR) && cargo run; \
		elif [ -f "$(SERVER_DIR)/go.mod" ]; then \
			cd $(SERVER_DIR) && go run .; \
		elif [ -f "$(SERVER_DIR)/package.json" ]; then \
			cd $(SERVER_DIR) && npm install && npm run dev; \
		else \
			echo -e "$(COLOR_YELLOW)⚠ 未识别后端项目类型$(COLOR_RESET)"; \
		fi; \
	else \
		echo -e "$(COLOR_YELLOW)⚠ 后端目录不存在$(COLOR_RESET)"; \
	fi

# =============================================================================
# 测试与代码检查
# =============================================================================

.PHONY: test
test:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 运行测试 ==========$(COLOR_RESET)"
	@cargo test --all
	@echo -e "$(COLOR_GREEN)✓ 所有测试通过$(COLOR_RESET)"

.PHONY: lint
lint:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== Clippy 代码检查 ==========$(COLOR_RESET)"
	@cargo clippy --all-targets --all-features -- -D warnings
	@echo -e "$(COLOR_GREEN)✓ 代码检查通过$(COLOR_RESET)"

.PHONY: fmt
fmt:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 格式化代码 ==========$(COLOR_RESET)"
	@cargo fmt --all
	@echo -e "$(COLOR_GREEN)✓ 代码格式化完成$(COLOR_RESET)"

.PHONY: fmt-check
fmt-check:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 检查代码格式 ==========$(COLOR_RESET)"
	@cargo fmt --all -- --check
	@echo -e "$(COLOR_GREEN)✓ 代码格式正确$(COLOR_RESET)"

# =============================================================================
# Docker 命令
# =============================================================================

.PHONY: docker-up
docker-up:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 启动 Docker 服务 ==========$(COLOR_RESET)"
	@docker-compose up -d --build
	@echo -e "$(COLOR_GREEN)✓ Docker 服务已启动$(COLOR_RESET)"

.PHONY: docker-down
docker-down:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 停止 Docker 服务 ==========$(COLOR_RESET)"
	@docker-compose down
	@echo -e "$(COLOR_GREEN)✓ Docker 服务已停止$(COLOR_RESET)"

.PHONY: docker-logs
docker-logs:
	@docker-compose logs -f

.PHONY: docker-restart
docker-restart: docker-down docker-up

# =============================================================================
# 清理命令
# =============================================================================

.PHONY: clean
clean: clean-wasm
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 清理所有构建产物 ==========$(COLOR_RESET)"
	@cargo clean
	@if [ -d "$(WEB_DIR)/node_modules" ]; then \
		rm -rf $(WEB_DIR)/node_modules; \
		echo -e "$(COLOR_YELLOW)→ 已清理前端 node_modules$(COLOR_RESET)"; \
	fi
	@if [ -d "$(WEB_DIR)/dist" ]; then \
		rm -rf $(WEB_DIR)/dist; \
		echo -e "$(COLOR_YELLOW)→ 已清理前端 dist$(COLOR_RESET)"; \
	fi
	@echo -e "$(COLOR_GREEN)✓ 清理完成$(COLOR_RESET)"

.PHONY: clean-wasm
clean-wasm:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 清理 WASM 构建产物 ==========$(COLOR_RESET)"
	@rm -rf $(PKG_DIR)
	@echo -e "$(COLOR_YELLOW)→ 已清理 pkg/ 目录$(COLOR_RESET)"

# =============================================================================
# 环境检查
# =============================================================================

.PHONY: doctor
doctor:
	@echo -e "$(COLOR_BOLD)$(COLOR_CYAN)========== 开发环境检查 ==========$(COLOR_RESET)"
	@echo ""
	@echo -e "$(COLOR_BOLD)Rust 工具链:$(COLOR_RESET)"
	@if command -v rustc &> /dev/null; then \
		echo -e "  $(COLOR_GREEN)✓ rustc$(COLOR_RESET)     $(shell rustc --version)"; \
	else \
		echo -e "  $(COLOR_RED)✗ rustc$(COLOR_RESET)     未安装"; \
	fi
	@if command -v cargo &> /dev/null; then \
		echo -e "  $(COLOR_GREEN)✓ cargo$(COLOR_RESET)     $(shell cargo --version)"; \
	else \
		echo -e "  $(COLOR_RED)✗ cargo$(COLOR_RESET)     未安装"; \
	fi
	@echo ""
	@echo -e "$(COLOR_BOLD)WebAssembly 工具链:$(COLOR_RESET)"
	@if rustup target list --installed 2>/dev/null | grep -q wasm32-unknown-unknown; then \
		echo -e "  $(COLOR_GREEN)✓ wasm32-unknown-unknown$(COLOR_RESET)  已安装"; \
	else \
		echo -e "  $(COLOR_RED)✗ wasm32-unknown-unknown$(COLOR_RESET)  未安装 (运行: rustup target add wasm32-unknown-unknown)"; \
	fi
	@if command -v wasm-pack &> /dev/null; then \
		echo -e "  $(COLOR_GREEN)✓ wasm-pack$(COLOR_RESET) $(shell wasm-pack --version)"; \
	else \
		echo -e "  $(COLOR_RED)✗ wasm-pack$(COLOR_RESET) 未安装"; \
	fi
	@echo ""
	@echo -e "$(COLOR_BOLD)前端工具链:$(COLOR_RESET)"
	@if command -v node &> /dev/null; then \
		echo -e "  $(COLOR_GREEN)✓ node$(COLOR_RESET)      $(shell node --version)"; \
	else \
		echo -e "  $(COLOR_YELLOW)⚠ node$(COLOR_RESET)      未安装 (前端开发需要)"; \
	fi
	@if command -v npm &> /dev/null; then \
		echo -e "  $(COLOR_GREEN)✓ npm$(COLOR_RESET)       $(shell npm --version)"; \
	else \
		echo -e "  $(COLOR_YELLOW)⚠ npm$(COLOR_RESET)       未安装 (前端开发需要)"; \
	fi
	@echo ""
	@echo -e "$(COLOR_BOLD)Docker:$(COLOR_RESET)"
	@if command -v docker &> /dev/null; then \
		echo -e "  $(COLOR_GREEN)✓ docker$(COLOR_RESET)    $(shell docker --version)"; \
	else \
		echo -e "  $(COLOR_YELLOW)⚠ docker$(COLOR_RESET)    未安装 (可选，容器化部署需要)"; \
	fi
	@if command -v docker-compose &> /dev/null; then \
		echo -e "  $(COLOR_GREEN)✓ docker-compose$(COLOR_RESET) $(shell docker-compose --version)"; \
	else \
		echo -e "  $(COLOR_YELLOW)⚠ docker-compose$(COLOR_RESET) 未安装 (可选，容器化部署需要)"; \
	fi
	@echo ""
