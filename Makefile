.PHONY: all build dev test test-coverage lint fmt audit check clean docker-build docker-run help

PROJECT_NAME := edge-scheduler
CARGO := cargo
DOCKER := docker
DOCKER_COMPOSE := docker compose

VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "0.1.0")
BUILD_DATE := $(shell date -u +"%Y-%m-%dT%H:%M:%SZ")
GIT_COMMIT := $(shell git rev-parse --short HEAD 2>/dev/null || echo "unknown")

RUSTFLAGS := -D warnings
export RUSTFLAGS

all: check build test

help:
	@echo "EdgeScheduler - 边缘计算调度平台"
	@echo ""
	@echo "可用目标:"
	@echo "  build           - 构建项目 (release 模式)"
	@echo "  dev             - 开发模式构建 (debug 模式)"
	@echo "  run             - 运行项目"
	@echo "  test            - 运行所有测试"
	@echo "  test-coverage   - 运行测试并生成覆盖率报告"
	@echo "  lint            - 运行 clippy 代码检查"
	@echo "  fmt             - 格式化代码"
	@echo "  fmt-check       - 检查代码格式"
	@echo "  audit           - 运行安全审计"
	@echo "  check           - 运行所有质量检查 (fmt, lint, test)"
	@echo "  clean           - 清理构建产物"
	@echo "  docker-build    - 构建 Docker 镜像"
	@echo "  docker-run      - 运行 Docker 容器"
	@echo "  docker-logs     - 查看 Docker 容器日志"
	@echo "  docker-stop     - 停止 Docker 容器"
	@echo "  db-migrate      - 运行数据库迁移"
	@echo "  docs            - 构建文档"

build:
	@echo "构建项目 (release 模式)..."
	$(CARGO) build --release --all-features

dev:
	@echo "开发模式构建..."
	$(CARGO) build --all-features

run:
	@echo "运行 EdgeScheduler..."
	$(CARGO) run --all-features

test:
	@echo "运行测试..."
	$(CARGO) test --all-features --all-targets -- --nocapture

test-coverage:
	@echo "运行测试并生成覆盖率报告..."
	$(CARGO) tarpaulin --all-features --out Html --output-dir coverage --config coverage/tarpaulin.toml
	@echo "覆盖率报告已生成: coverage/tarpaulin-report.html"

lint:
	@echo "运行 clippy 检查..."
	$(CARGO) clippy --all-features --all-targets -- -D warnings -D clippy::all -D clippy::pedantic -A clippy::module_name_repetitions

fmt:
	@echo "格式化代码..."
	$(CARGO) fmt --all

fmt-check:
	@echo "检查代码格式..."
	$(CARGO) fmt --all --check

audit:
	@echo "运行安全审计..."
	@if ! command -v cargo-audit &> /dev/null; then \
		echo "安装 cargo-audit..."; \
		$(CARGO) install cargo-audit; \
	fi
	$(CARGO) audit

deny:
	@echo "运行 cargo deny 检查..."
	@if ! command -v cargo-deny &> /dev/null; then \
		echo "安装 cargo-deny..."; \
		$(CARGO) install cargo-deny; \
	fi
	$(CARGO) deny check

check: fmt-check lint audit test
	@echo "所有质量检查通过!"

clean:
	@echo "清理构建产物..."
	$(CARGO) clean
	rm -rf coverage

docker-build:
	@echo "构建 Docker 镜像..."
	$(DOCKER) build -t $(PROJECT_NAME):$(VERSION) -t $(PROJECT_NAME):latest .

docker-run:
	@echo "运行 Docker 容器..."
	$(DOCKER_COMPOSE) up -d

docker-logs:
	@echo "查看 Docker 容器日志..."
	$(DOCKER_COMPOSE) logs -f

docker-stop:
	@echo "停止 Docker 容器..."
	$(DOCKER_COMPOSE) down

db-migrate:
	@echo "运行数据库迁移..."
	@if ! command -v sqlx &> /dev/null; then \
		echo "安装 sqlx-cli..."; \
		$(CARGO) install sqlx-cli --no-default-features --features postgres; \
	fi
	sqlx migrate run

docs:
	@echo "构建文档..."
	$(CARGO) doc --all-features --no-deps --open

pre-commit: fmt lint test
	@echo "pre-commit 检查完成"

release:
	@echo "发布版本 $(VERSION)..."
	@echo "更新版本号..."
	@echo "构建发布版本..."
	$(CARGO) build --profile production --all-features
	@echo "发布版本 $(VERSION) 构建完成"

bench:
	@echo "运行基准测试..."
	$(CARGO) bench --all-features

outdated:
	@echo "检查过时的依赖..."
	@if ! command -v cargo-outdated &> /dev/null; then \
		echo "安装 cargo-outdated..."; \
		$(CARGO) install cargo-outdated; \
	fi
	$(CARGO) outdated

update:
	@echo "更新依赖..."
	$(CARGO) update
