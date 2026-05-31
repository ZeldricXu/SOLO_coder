# ========================================
# 工单智能分配系统 - Makefile
# ========================================

SHELL := /bin/bash

# 默认环境
APP_ENV ?= development

# 颜色定义
BOLD := \033[1m
GREEN := \033[32m
YELLOW := \033[33m
RED := \033[31m
RESET := \033[0m

# Echo with color support
ECHO := echo -e

# Python executable
PYTHON := python3
PIP := pip3

# ========================================
# 帮助信息
# ========================================
.PHONY: help
help:
	@$(ECHO) "$(BOLD)工单智能分配系统 - 可用命令$(RESET)"
	@$(ECHO) ""
	@$(ECHO) "$(BOLD)环境管理$(RESET)"
	@$(ECHO) "  make init                - 初始化项目（安装依赖+配置）"
	@$(ECHO) "  make install             - 安装所有依赖"
	@$(ECHO) "  make install-prod        - 仅安装生产依赖"
	@$(ECHO) "  make update              - 更新所有依赖"
	@$(ECHO) ""
	@$(ECHO) "$(BOLD)代码质量$(RESET)"
	@$(ECHO) "  make lint                - 运行静态代码分析"
	@$(ECHO) "  make format              - 格式化代码"
	@$(ECHO) "  make type-check          - 运行类型检查"
	@$(ECHO) "  make quality             - 运行完整代码质量检查（lint+format+type-check）"
	@$(ECHO) ""
	@$(ECHO) "$(BOLD)测试$(RESET)"
	@$(ECHO) "  make test                - 运行所有测试"
	@$(ECHO) "  make test-cov            - 运行测试并生成覆盖率报告"
	@$(ECHO) "  make test-watch          - 监听模式运行测试"
	@$(ECHO) "  make test-unit           - 仅运行单元测试"
	@$(ECHO) "  make test-integration    - 仅运行集成测试"
	@$(ECHO) ""
	@$(ECHO) "$(BOLD)运行服务$(RESET)"
	@$(ECHO) "  make run                 - 启动开发服务器"
	@$(ECHO) "  make run-prod            - 启动生产服务器"
	@$(ECHO) "  make worker              - 启动 Celery Worker"
	@$(ECHO) "  make beat                - 启动 Celery Beat"
	@$(ECHO) ""
	@$(ECHO) "$(BOLD)Docker 开发环境$(RESET)"
	@$(ECHO) "  make docker-up           - 启动所有 Docker 服务"
	@$(ECHO) "  make docker-down         - 停止所有 Docker 服务"
	@$(ECHO) "  make docker-restart      - 重启所有 Docker 服务"
	@$(ECHO) "  make docker-build        - 构建 Docker 镜像"
	@$(ECHO) "  make docker-logs         - 查看服务日志"
	@$(ECHO) "  make docker-shell        - 进入应用容器 Shell"
	@$(ECHO) ""
	@$(ECHO) "$(BOLD)数据库$(RESET)"
	@$(ECHO) "  make db-init             - 初始化数据库"
	@$(ECHO) "  make db-migrate          - 生成数据库迁移"
	@$(ECHO) "  make db-upgrade          - 应用数据库迁移"
	@$(ECHO) "  make db-downgrade        - 回滚数据库迁移"
	@$(ECHO) ""
	@$(ECHO) "$(BOLD)版本发布$(RESET)"
	@$(ECHO) "  make version-patch       - 发布 Patch 版本"
	@$(ECHO) "  make version-minor       - 发布 Minor 版本"
	@$(ECHO) "  make version-major       - 发布 Major 版本"
	@$(ECHO) "  make changelog           - 生成变更日志"
	@$(ECHO) ""
	@$(ECHO) "$(BOLD)Git 钩子$(RESET)"
	@$(ECHO) "  make pre-commit-install  - 安装 pre-commit 钩子"
	@$(ECHO) "  make pre-commit-run      - 手动运行 pre-commit 钩子"
	@$(ECHO) ""
	@$(ECHO) "$(BOLD)清理$(RESET)"
	@$(ECHO) "  make clean               - 清理临时文件"
	@$(ECHO) "  make clean-all           - 深度清理（含虚拟环境）"

# ========================================
# 环境管理
# ========================================
.PHONY: init
init: install pre-commit-install db-init
	@$(ECHO) "$(GREEN)✅ 项目初始化完成$(RESET)"

.PHONY: install
install:
	@$(ECHO) "$(YELLOW)📦 安装所有依赖...$(RESET)"
	@if command -v poetry >/dev/null 2>&1; then \
		poetry install; \
	else \
		$(PIP) install -r requirements.txt; \
	fi
	@$(ECHO) "$(GREEN)✅ 依赖安装完成$(RESET)"

.PHONY: install-prod
install-prod:
	@$(ECHO) "$(YELLOW)📦 安装生产依赖...$(RESET)"
	@if command -v poetry >/dev/null 2>&1; then \
		poetry install --without dev; \
	else \
		pip install -r requirements.txt; \
	fi
	@$(ECHO) "$(GREEN)✅ 生产依赖安装完成$(RESET)"

.PHONY: update
update:
	@$(ECHO) "$(YELLOW)⬆️  更新所有依赖...$(RESET)"
	@if command -v poetry >/dev/null 2>&1; then \
		poetry update; \
	else \
		pip install --upgrade -r requirements.txt; \
	fi
	@$(ECHO) "$(GREEN)✅ 依赖更新完成$(RESET)"

# ========================================
# 代码质量
# ========================================
.PHONY: lint
lint:
	@$(ECHO) "$(YELLOW)🔍 运行静态代码分析...$(RESET)"
	@$(PYTHON) -m ruff check .
	@$(ECHO) "$(GREEN)✅ 静态代码分析通过$(RESET)"

.PHONY: format
format:
	@$(ECHO) "$(YELLOW)💅 格式化代码...$(RESET)"
	@$(PYTHON) -m ruff format .
	@$(PYTHON) -m ruff check --fix .
	@$(ECHO) "$(GREEN)✅ 代码格式化完成$(RESET)"

.PHONY: type-check
type-check:
	@$(ECHO) "$(YELLOW)📝 运行类型检查...$(RESET)"
	@$(PYTHON) -m mypy .
	@$(ECHO) "$(GREEN)✅ 类型检查通过$(RESET)"

.PHONY: quality
quality: lint format type-check
	@$(ECHO) "$(GREEN)✅ 代码质量检查全部通过$(RESET)"

# ========================================
# 测试
# ========================================
.PHONY: test
test:
	@$(ECHO) "$(YELLOW)🧪 运行所有测试...$(RESET)"
	@$(PYTHON) -m pytest tests/ -v --tb=short --asyncio-mode=auto
	@$(ECHO) "$(GREEN)✅ 所有测试通过$(RESET)"

.PHONY: test-cov
test-cov:
	@$(ECHO) "$(YELLOW)📊 运行测试并生成覆盖率报告...$(RESET)"
	@$(PYTHON) -m pytest tests/ \
		-v \
		--tb=short \
		--asyncio-mode=auto \
		--cov=modules/ticket_assignment \
		--cov=modules/multitenant \
		--cov=modules/approval_engine \
		--cov=core \
		--cov=app \
		--cov-fail-under=80 \
		--cov-report=term-missing \
		--cov-report=xml:coverage.xml \
		--cov-report=html:htmlcov
	@$(ECHO) "$(GREEN)✅ 测试覆盖率报告已生成（htmlcov/index.html）$(RESET)"

.PHONY: test-watch
test-watch:
	@$(ECHO) "$(YELLOW)👀 监听模式运行测试...$(RESET)"
	@$(PYTHON) -m pytest tests/ -v --tb=short --asyncio-mode=auto -f

.PHONY: test-unit
test-unit:
	@$(ECHO) "$(YELLOW)🧪 运行单元测试...$(RESET)"
	@$(PYTHON) -m pytest tests/ -v --tb=short --asyncio-mode=auto -k "unit"

.PHONY: test-integration
test-integration:
	@$(ECHO) "$(YELLOW)🧪 运行集成测试...$(RESET)"
	@$(PYTHON) -m pytest tests/ -v --tb=short --asyncio-mode=auto -k "integration"

# ========================================
# 运行服务
# ========================================
.PHONY: run
run:
	@$(ECHO) "$(YELLOW)🚀 启动开发服务器...$(RESET)"
	@export APP_ENV=$(APP_ENV) && \
	$(PYTHON) -m uvicorn app.main:app \
		--host 0.0.0.0 \
		--port 8000 \
		--reload \
		--log-level debug

.PHONY: run-prod
run-prod:
	@$(ECHO) "$(YELLOW)🚀 启动生产服务器...$(RESET)"
	@export APP_ENV=production && \
	$(PYTHON) -m uvicorn app.main:app \
		--host 0.0.0.0 \
		--port 8000 \
		--workers 4 \
		--log-level warning

.PHONY: worker
worker:
	@$(ECHO) "$(YELLOW)⚡ 启动 Celery Worker...$(RESET)"
	@export APP_ENV=$(APP_ENV) && \
	celery -A app.celery_app worker --loglevel=info --concurrency=4

.PHONY: beat
beat:
	@$(ECHO) "$(YELLOW)⏰ 启动 Celery Beat...$(RESET)"
	@export APP_ENV=$(APP_ENV) && \
	celery -A app.celery_app beat --loglevel=info

# ========================================
# Docker 开发环境
# ========================================
.PHONY: docker-up
docker-up:
	@$(ECHO) "$(YELLOW)🐳 启动 Docker 服务...$(RESET)"
	@docker-compose up -d
	@$(ECHO) "$(GREEN)✅ Docker 服务启动完成$(RESET)"

.PHONY: docker-down
docker-down:
	@$(ECHO) "$(YELLOW)🐳 停止 Docker 服务...$(RESET)"
	@docker-compose down
	@$(ECHO) "$(GREEN)✅ Docker 服务已停止$(RESET)"

.PHONY: docker-restart
docker-restart: docker-down docker-up

.PHONY: docker-build
docker-build:
	@$(ECHO) "$(YELLOW)🐳 构建 Docker 镜像...$(RESET)"
	@docker-compose build --no-cache
	@$(ECHO) "$(GREEN)✅ Docker 镜像构建完成$(RESET)"

.PHONY: docker-logs
docker-logs:
	@$(ECHO) "$(YELLOW)📜 查看服务日志...$(RESET)"
	@docker-compose logs -f

.PHONY: docker-shell
docker-shell:
	@docker-compose exec app bash

# ========================================
# 数据库
# ========================================
.PHONY: db-init
db-init:
	@$(ECHO) "$(YELLOW)🗄️  初始化数据库...$(RESET)"
	@export APP_ENV=$(APP_ENV) && $(PYTHON) scripts/init_db.py

.PHONY: db-migrate
db-migrate:
	@$(ECHO) "$(YELLOW)🗄️  生成数据库迁移...$(RESET)"
	@read -p "输入迁移描述: " desc && \
	alembic revision --autogenerate -m "$$desc"
	@$(ECHO) "$(GREEN)✅ 迁移生成完成$(RESET)"

.PHONY: db-upgrade
db-upgrade:
	@$(ECHO) "$(YELLOW)🗄️  应用数据库迁移...$(RESET)"
	@alembic upgrade head
	@$(ECHO) "$(GREEN)✅ 迁移应用完成$(RESET)"

.PHONY: db-downgrade
db-downgrade:
	@$(ECHO) "$(YELLOW)🗄️  回滚数据库迁移...$(RESET)"
	@alembic downgrade -1
	@$(ECHO) "$(GREEN)✅ 迁移回滚完成$(RESET)"

# ========================================
# 版本发布
# ========================================
.PHONY: version-patch
version-patch:
	@$(ECHO) "$(YELLOW)📦 发布 Patch 版本...$(RESET)"
	@bump2version patch
	@$(ECHO) "$(GREEN)✅ Patch 版本发布完成$(RESET)"

.PHONY: version-minor
version-minor:
	@$(ECHO) "$(YELLOW)📦 发布 Minor 版本...$(RESET)"
	@bump2version minor
	@$(ECHO) "$(GREEN)✅ Minor 版本发布完成$(RESET)"

.PHONY: version-major
version-major:
	@$(ECHO) "$(YELLOW)📦 发布 Major 版本...$(RESET)"
	@bump2version major
	@$(ECHO) "$(GREEN)✅ Major 版本发布完成$(RESET)"

.PHONY: changelog
changelog:
	@$(ECHO) "$(YELLOW)📝 生成变更日志...$(RESET)"
	@if command -v git-chglog >/dev/null 2>&1; then \
		git-chglog -o CHANGELOG.md; \
	else \
		git log --pretty=format:"- %s (%h)" --no-merges > CHANGELOG.md; \
	fi
	@$(ECHO) "$(GREEN)✅ 变更日志生成完成$(RESET)"

# ========================================
# Git 钩子
# ========================================
.PHONY: pre-commit-install
pre-commit-install:
	@$(ECHO) "$(YELLOW)🔗 安装 pre-commit 钩子...$(RESET)"
	@pre-commit install
	@pre-commit install --hook-type pre-push
	@$(ECHO) "$(GREEN)✅ pre-commit 钩子安装完成$(RESET)"

.PHONY: pre-commit-run
pre-commit-run:
	@$(ECHO) "$(YELLOW)🔗 运行 pre-commit 钩子...$(RESET)"
	@pre-commit run --all-files
	@$(ECHO) "$(GREEN)✅ pre-commit 钩子执行完成$(RESET)"

# ========================================
# 清理
# ========================================
.PHONY: clean
clean:
	@$(ECHO) "$(YELLOW)🧹 清理临时文件...$(RESET)"
	@find . -type f -name "*.pyc" -delete
	@find . -type d -name "__pycache__" -delete
	@find . -type d -name "*.egg-info" -exec rm -rf {} + 2>/dev/null || true
	@rm -rf .pytest_cache .mypy_cache .ruff_cache
	@rm -f .coverage coverage.xml
	@rm -rf htmlcov dist build
	@$(ECHO) "$(GREEN)✅ 清理完成$(RESET)"

.PHONY: clean-all
clean-all: clean
	@$(ECHO) "$(YELLOW)🧹 深度清理...$(RESET)"
	@rm -rf .venv venv env
	@$(ECHO) "$(GREEN)✅ 深度清理完成$(RESET)"

# ========================================
# 快捷命令
# ========================================
.PHONY: all
all: quality test

.PHONY: ci
ci: quality test-cov
