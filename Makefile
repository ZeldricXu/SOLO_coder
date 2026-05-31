# ========================================
# Makefile - 开发与构建自动化
# 常用命令快速参考
# ========================================

# ---------- 环境变量 ----------
SHELL := /bin/bash
CARGO := cargo
DOCKER := docker
DOCKER_COMPOSE := docker compose
RUST_VERSION := 1.75.0
APP_NAME := data-transformer
IMAGE_NAME := data-transformer

# 加载环境变量
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

# ---------- 帮助信息 ----------
.PHONY: help
help: ## 显示帮助信息
	@echo "数据转换与标准化中间件 - 开发构建工具"
	@echo "================================================"
	@echo ""
	@echo "可用命令:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-25s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "环境: $(RUST_ENV)"
	@echo "版本: $(shell grep '^version' Cargo.toml | head -1 | cut -d'"' -f2)"

# ---------- 环境设置 ----------
.PHONY: setup
setup: ## 安装必要的开发工具链
	@echo "🔧 安装 Rust 工具链..."
	rustup toolchain install $(RUST_VERSION)
	rustup default $(RUST_VERSION)
	rustup component add rustfmt clippy llvm-tools-preview
	@echo "📦 安装 Cargo 插件..."
	$(CARGO) install cargo-watch --locked
	$(CARGO) install cargo-tarpaulin --locked
	$(CARGO) install cargo-audit --locked
	$(CARGO) install cargo-outdated --locked
	$(CARGO) install cargo-udeps --locked
	$(CARGO) install cargo-tree --locked
	@echo "✅ 环境设置完成"

.PHONY: check-rust
check-rust: ## 检查 Rust 环境
	@rustc --version
	@cargo --version
	@rustup --version

# ---------- 代码质量 ----------
.PHONY: fmt
fmt: ## 格式化代码
	$(CARGO) fmt --all

.PHONY: fmt-check
fmt-check: ## 检查代码格式
	$(CARGO) fmt --all -- --check

.PHONY: check
check: ## 编译检查
	$(CARGO) check --all-targets --all-features

.PHONY: clippy
clippy: ## Clippy 静态代码检查
	$(CARGO) clippy --all-targets --all-features -- -D warnings -D clippy::all

.PHONY: lint
lint: fmt-check clippy audit ## 运行所有代码质量检查
	@echo "✅ 所有代码质量检查通过"

.PHONY: audit
audit: ## 安全审计依赖
	$(CARGO) audit --deny warnings

.PHONY: outdated
outdated: ## 检查过时的依赖
	$(CARGO) outdated --exit-code 1

.PHONY: udeps
udeps: ## 检查未使用的依赖
	$(CARGO) udeps --all-targets

# ---------- 测试 ----------
.PHONY: test
test: ## 运行所有测试
	$(CARGO) test --all-targets --all-features

.PHONY: test-unit
test-unit: ## 运行单元测试
	$(CARGO) test --lib --bins --tests

.PHONY: test-doc
test-doc: ## 运行文档测试
	$(CARGO) test --doc

.PHONY: test-integration
test-integration: ## 运行集成测试
	$(CARGO) test --test '*'

.PHONY: test-coverage
test-coverage: ## 运行带覆盖率的测试
	$(CARGO) tarpaulin --config tarpaulin.toml

.PHONY: test-watch
test-watch: ## 监听文件变化自动运行测试
	$(CARGO) watch -x test -w src

# ---------- 构建 ----------
.PHONY: build
build: ## 开发构建
	$(CARGO) build

.PHONY: build-release
build-release: ## 生产构建
	$(CARGO) build --release
	@echo "✅ 构建完成: target/release/$(APP_NAME)"

.PHONY: build-debug
build-debug: ## 调试构建
	$(CARGO) build --all-features

.PHONY: clean
clean: ## 清理构建产物
	$(CARGO) clean
	rm -rf coverage/ test-results/

.PHONY: doc
doc: ## 生成文档
	$(CARGO) doc --no-deps --open

.PHONY: doc-build
doc-build: ## 生成文档（不打开）
	$(CARGO) doc --no-deps

# ---------- 运行 ----------
.PHONY: run
run: ## 运行应用
	RUST_ENV=development $(CARGO) run

.PHONY: run-release
run-release: ## 运行生产版本
	RUST_ENV=production $(CARGO) run --release

.PHONY: run-staging
run-staging: ## 运行预发布版本
	RUST_ENV=staging $(CARGO) run

.PHONY: watch
watch: ## 监听文件变化自动重新运行
	$(CARGO) watch -x run -w src -w config

# ---------- Docker ----------
.PHONY: docker-build
docker-build: ## 构建 Docker 镜像
	$(DOCKER) build \
		--target runtime \
		--build-arg BUILD_PROFILE=release \
		--build-arg GIT_SHA=$(shell git rev-parse --short HEAD) \
		--build-arg BUILD_TIME=$(shell date -u +"%Y-%m-%dT%H:%M:%SZ") \
		-t $(IMAGE_NAME):latest \
		.

.PHONY: docker-build-dev
docker-build-dev: ## 构建开发环境 Docker 镜像
	$(DOCKER) build --target development -t $(IMAGE_NAME):dev .

.PHONY: docker-push
docker-push: ## 推送 Docker 镜像到仓库
	$(DOCKER) tag $(IMAGE_NAME):latest $(REGISTRY)/$(IMAGE_NAME):$(VERSION)
	$(DOCKER) push $(REGISTRY)/$(IMAGE_NAME):$(VERSION)
	$(DOCKER) tag $(IMAGE_NAME):latest $(REGISTRY)/$(IMAGE_NAME):latest
	$(DOCKER) push $(REGISTRY)/$(IMAGE_NAME):latest

.PHONY: docker-scan
docker-scan: ## 扫描 Docker 镜像漏洞
	$(DOCKER) scout cves $(IMAGE_NAME):latest

.PHONY: docker-rmi
docker-rmi: ## 删除所有相关 Docker 镜像
	$(DOCKER) rmi -f $(shell docker images | grep $(IMAGE_NAME) | awk '{print $$3}')

# ---------- Docker Compose ----------
.PHONY: up
up: ## 启动所有服务（开发环境）
	$(DOCKER_COMPOSE) up -d postgres redis
	@echo "⏳ 等待服务就绪..."
	@sleep 5
	@echo "✅ 依赖服务已启动"
	@echo "  - PostgreSQL: localhost:5432"
	@echo "  - Redis: localhost:6379"

.PHONY: up-all
up-all: ## 启动所有服务（包含监控）
	$(DOCKER_COMPOSE) up -d
	@echo "✅ 所有服务已启动"
	@echo "  - PostgreSQL: localhost:5432"
	@echo "  - Redis: localhost:6379"
	@echo "  - Prometheus: localhost:9090"
	@echo "  - Grafana: localhost:3000 (admin/admin123)"
	@echo "  - App (Dev): localhost:8080"

.PHONY: up-app
up-app: ## 启动应用服务（开发模式）
	$(DOCKER_COMPOSE) up -d app-dev

.PHONY: down
down: ## 停止所有服务
	$(DOCKER_COMPOSE) down

.PHONY: down-v
down-v: ## 停止所有服务并清理数据卷
	$(DOCKER_COMPOSE) down -v

.PHONY: logs
logs: ## 查看服务日志
	$(DOCKER_COMPOSE) logs -f

.PHONY: ps
ps: ## 查看服务状态
	$(DOCKER_COMPOSE) ps

.PHONY: restart
restart: ## 重启所有服务
	$(DOCKER_COMPOSE) restart

# ---------- 数据库 ----------
.PHONY: db-init
db-init: ## 初始化数据库
	@echo "🔧 初始化数据库..."
	psql $(DATABASE_URL) -f scripts/init-db.sql
	@echo "✅ 数据库初始化完成"

.PHONY: db-migrate
db-migrate: ## 执行数据库迁移
	@echo "🔧 执行数据库迁移..."
	# 这里可以添加 sqlx migrate run 或其他迁移命令
	@echo "✅ 数据库迁移完成"

.PHONY: db-reset
db-reset: ## 重置数据库（谨慎使用！）
	@read -p "⚠️  确定要重置数据库吗？这将删除所有数据! [y/N] " confirm; \
	if [ "$$confirm" = "y" ]; then \
		echo "🔧 重置数据库..."; \
		psql $(DATABASE_URL) -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"; \
		$(MAKE) db-init; \
		echo "✅ 数据库重置完成"; \
	fi

# ---------- 部署 ----------
.PHONY: deploy-staging
deploy-staging: ## 部署到预发布环境
	@echo "🚀 部署到预发布环境..."
	kubectl apply -f k8s/namespace.yaml
	kubectl apply -f k8s/configmap.yaml -n staging
	kubectl apply -f k8s/secret.yaml -n staging
	kubectl apply -f k8s/deployment.yaml -n staging
	kubectl apply -f k8s/service.yaml -n staging
	kubectl apply -f k8s/ingress.yaml -n staging
	@echo "✅ 已部署到预发布环境"

.PHONY: deploy-production
deploy-production: ## 部署到生产环境
	@read -p "⚠️  确定要部署到生产环境吗？ [y/N] " confirm; \
	if [ "$$confirm" = "y" ]; then \
		echo "🚀 部署到生产环境..."; \
		kubectl apply -f k8s/namespace.yaml; \
		kubectl apply -f k8s/configmap.yaml -n production; \
		kubectl apply -f k8s/secret.yaml -n production; \
		kubectl apply -f k8s/deployment.yaml -n production; \
		kubectl apply -f k8s/service.yaml -n production; \
		kubectl apply -f k8s/ingress.yaml -n production; \
		echo "✅ 已部署到生产环境"; \
	fi

# ---------- 性能分析 ----------
.PHONY: bench
bench: ## 运行基准测试
	$(CARGO) bench

.PHONY: flamegraph
flamegraph: ## 生成火焰图（需要 perf）
	$(CARGO) flamegraph --bench main -- --bench

.PHONY: bloat
bloat: ## 分析二进制文件大小
	$(CARGO) bloat --release -n 20

.PHONY: tree
tree: ## 显示依赖树
	$(CARGO) tree

# ---------- 发布准备 ----------
.PHONY: pre-release
pre-release: clean lint test-coverage build-release ## 发布前检查
	@echo "✅ 发布前检查全部通过，可以创建 Release"
	@echo "当前版本: $(shell grep '^version' Cargo.toml | head -1 | cut -d'"' -f2)"

.PHONY: release-patch
release-patch: ## 发布 patch 版本
	$(CARGO) release --patch

.PHONY: release-minor
release-minor: ## 发布 minor 版本
	$(CARGO) release --minor

.PHONY: release-major
release-major: ## 发布 major 版本
	$(CARGO) release --major

# ---------- 快捷命令 ----------
.PHONY: all
all: clean lint test build-release ## 完整流程：清理 -> 检查 -> 测试 -> 构建

.PHONY: quick
quick: fmt test build ## 快速检查并构建

.PHONY: ci
ci: fmt-check check clippy test test-coverage audit ## CI 全流程检查
	@echo "✅ CI 检查全部通过"

# ---------- 默认目标 ----------
.DEFAULT_GOAL := help
