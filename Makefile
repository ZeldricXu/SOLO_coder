# ============================================
# 区块链基础设施平台 - Makefile
# ============================================

# 配置
PYTHON ?= python3
PIP ?= pip
PYTEST ?= pytest
DOCKER ?= docker
DOCKER_COMPOSE ?= docker compose
PROJECT_NAME ?= blockchain-infra

# 颜色定义
GREEN  := $(shell tput -Txterm setaf 2)
YELLOW := $(shell tput -Txterm setaf 3)
WHITE  := $(shell tput -Txterm setaf 7)
CYAN   := $(shell tput -Txterm setaf 6)
RESET  := $(shell tput -Txterm sgr0)

# ============================================
# 帮助信息
# ============================================
.PHONY: help
help: ## 显示帮助信息
	@echo ''
	@echo '${CYAN}区块链基础设施平台 - 开发工具${RESET}'
	@echo ''
	@echo '使用方式:'
	@echo '  ${YELLOW}make${RESET} ${GREEN}<target>${RESET}'
	@echo ''
	@echo '可用目标:'
	@awk 'BEGIN {FS = ":.*##"; printf "\n\033[1m%-20s\033[0m %s\n\n", "目标", "描述"} /^[a-zA-Z_-]+:.*?##/ { printf "  ${YELLOW}%-20s${RESET} ${GREEN}%s${RESET}\n", $$1, $$2 } /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } ' $(MAKEFILE_LIST)
	@echo ''

# ============================================
##@ 环境设置
# ============================================

.PHONY: install
install: ## 安装项目依赖 (默认: dev环境)
	@echo "${CYAN}安装开发环境依赖...${RESET}"
	@$(PIP) install --upgrade pip setuptools wheel
	@$(PIP) install -r requirements/dev.txt
	@$(PIP) install -e .
	@echo "${GREEN}依赖安装完成!${RESET}"

.PHONY: install-prod
install-prod: ## 安装生产环境依赖
	@echo "${CYAN}安装生产环境依赖...${RESET}"
	@$(PIP) install --upgrade pip setuptools wheel
	@$(PIP) install -r requirements/prod.txt
	@$(PIP) install --no-deps -e .
	@echo "${GREEN}生产依赖安装完成!${RESET}"

.PHONY: install-test
install-test: ## 安装测试环境依赖
	@echo "${CYAN}安装测试环境依赖...${RESET}"
	@$(PIP) install --upgrade pip setuptools wheel
	@$(PIP) install -r requirements/test.txt
	@$(PIP) install -e .
	@echo "${GREEN}测试依赖安装完成!${RESET}"

.PHONY: venv
venv: ## 创建Python虚拟环境
	@echo "${CYAN}创建虚拟环境...${RESET}"
	@$(PYTHON) -m venv .venv
	@echo "${GREEN}虚拟环境创建完成!"
	@echo "请执行: ${YELLOW}source .venv/bin/activate${RESET}"

.PHONY: clean-venv
clean-venv: ## 清理虚拟环境
	@echo "${CYAN}清理虚拟环境...${RESET}"
	@rm -rf .venv
	@echo "${GREEN}虚拟环境已清理!${RESET}"

# ============================================
##@ 代码质量
# ============================================

.PHONY: format
format: ## 格式化代码 (black + isort + ruff)
	@echo "${CYAN}格式化代码...${RESET}"
	@black src/ tests/ --line-length=100
	@isort src/ tests/ --profile=black --line-length=100
	@ruff format src/ tests/
	@echo "${GREEN}代码格式化完成!${RESET}"

.PHONY: lint
lint: ## 运行所有代码检查
	@echo "${CYAN}运行代码质量检查...${RESET}"
	@echo "\n${YELLOW}=== Ruff ===${RESET}"
	@ruff check src/ tests/ --fix || true
	@echo "\n${YELLOW}=== Black Check ===${RESET}"
	@black --check src/ tests/ --line-length=100 || true
	@echo "\n${YELLOW}=== isort Check ===${RESET}"
	@isort --check-only src/ tests/ --profile=black --line-length=100 || true
	@echo "\n${YELLOW}=== Flake8 ===${RESET}"
	@flake8 src/ tests/ --count --select=E9,F63,F7,F82 --show-source --statistics || true
	@flake8 src/ tests/ --count --exit-zero --max-complexity=15 --max-line-length=100 --statistics || true
	@echo "\n${GREEN}代码检查完成!${RESET}"

.PHONY: type-check
type-check: ## 运行类型检查 (mypy)
	@echo "${CYAN}运行类型检查...${RESET}"
	@mypy src/ \
		--show-error-codes \
		--show-error-context \
		--ignore-missing-imports \
		--disallow-untyped-defs \
		--check-untyped-defs \
		--warn-return-any \
		--warn-unused-ignores \
		--warn-redundant-casts \
		--no-implicit-optional \
		--strict-equality
	@echo "${GREEN}类型检查完成!${RESET}"

.PHONY: security-scan
security-scan: ## 运行安全扫描 (bandit + safety)
	@echo "${CYAN}运行安全扫描...${RESET}"
	@echo "\n${YELLOW}=== Bandit ===${RESET}"
	@bandit -r src/ -ll -x tests,__pycache__,.venv || true
	@echo "\n${YELLOW}=== Safety ===${RESET}"
	@safety check --full-report -r requirements/base.txt || true
	@echo "\n${GREEN}安全扫描完成!${RESET}"

.PHONY: quality
quality: format lint type-check security-scan ## 运行所有质量检查 (格式化+检查+类型+安全)
	@echo "${GREEN}所有质量检查完成!${RESET}"

# ============================================
##@ 测试
# ============================================

.PHONY: test
test: ## 运行所有测试
	@echo "${CYAN}运行测试...${RESET}"
	@$(PYTEST) tests/ -v --tb=short

.PHONY: test-unit
test-unit: ## 只运行单元测试
	@echo "${CYAN}运行单元测试...${RESET}"
	@$(PYTEST) tests/ -m "unit" -v --tb=short

.PHONY: test-integration
test-integration: ## 只运行集成测试
	@echo "${CYAN}运行集成测试...${RESET}"
	@$(PYTEST) tests/ -m "integration" -v --tb=short

.PHONY: test-coverage
test-coverage: ## 运行测试并生成覆盖率报告
	@echo "${CYAN}运行测试并生成覆盖率报告...${RESET}"
	@$(PYTEST) tests/ \
		--cov=src \
		--cov-report=term-missing \
		--cov-report=xml:coverage.xml \
		--cov-report=html:htmlcov \
		--cov-fail-under=80
	@echo "${GREEN}覆盖率报告已生成在 htmlcov/index.html${RESET}"

.PHONY: test-fast
test-fast: ## 快速测试 (跳过慢速测试)
	@echo "${CYAN}运行快速测试...${RESET}"
	@$(PYTEST) tests/ -m "not slow" -q --tb=short

.PHONY: test-watch
test-watch: ## 监听文件变化自动运行测试
	@echo "${CYAN}启动测试监听器...${RESET}"
	@ptw tests/ -n -v

# ============================================
##@ 预提交
# ============================================

.PHONY: pre-commit-install
pre-commit-install: ## 安装预提交钩子
	@echo "${CYAN}安装预提交钩子...${RESET}"
	@pre-commit install
	@pre-commit install --hook-type commit-msg
	@echo "${GREEN}预提交钩子已安装!${RESET}"

.PHONY: pre-commit-run
pre-commit-run: ## 运行所有预提交钩子
	@echo "${CYAN}运行预提交钩子...${RESET}"
	@pre-commit run --all-files
	@echo "${GREEN}预提交钩子运行完成!${RESET}"

.PHONY: pre-commit-update
pre-commit-update: ## 更新预提交钩子版本
	@echo "${CYAN}更新预提交钩子...${RESET}"
	@pre-commit autoupdate
	@echo "${GREEN}预提交钩子已更新!${RESET}"

# ============================================
##@ Docker
# ============================================

.PHONY: docker-build
docker-build: ## 构建Docker镜像
	@echo "${CYAN}构建Docker镜像...${RESET}"
	@$(DOCKER) build -t $(PROJECT_NAME):latest .
	@echo "${GREEN}Docker镜像构建完成!${RESET}"

.PHONY: docker-build-dev
docker-build-dev: ## 构建开发环境Docker镜像
	@echo "${CYAN}构建开发环境Docker镜像...${RESET}"
	@$(DOCKER) build -t $(PROJECT_NAME):dev --target development .
	@echo "${GREEN}开发环境Docker镜像构建完成!${RESET}"

.PHONY: docker-up
docker-up: ## 启动所有Docker服务
	@echo "${CYAN}启动Docker服务...${RESET}"
	@$(DOCKER_COMPOSE) up -d
	@echo "${GREEN}Docker服务已启动!${RESET}"

.PHONY: docker-up-dev
docker-up-dev: ## 启动开发环境Docker服务
	@echo "${CYAN}启动开发环境Docker服务...${RESET}"
	@$(DOCKER_COMPOSE) -f docker-compose.yml -f docker-compose.dev.yml up -d
	@echo "${GREEN}开发环境Docker服务已启动!${RESET}"

.PHONY: docker-down
docker-down: ## 停止并移除Docker服务
	@echo "${CYAN}停止Docker服务...${RESET}"
	@$(DOCKER_COMPOSE) down
	@echo "${GREEN}Docker服务已停止!${RESET}"

.PHONY: docker-logs
docker-logs: ## 查看Docker服务日志
	@$(DOCKER_COMPOSE) logs -f --tail=100

.PHONY: docker-ps
docker-ps: ## 查看Docker服务状态
	@$(DOCKER_COMPOSE) ps

.PHONY: docker-clean
docker-clean: ## 清理Docker资源
	@echo "${CYAN}清理Docker资源...${RESET}"
	@$(DOCKER_COMPOSE) down -v
	@$(DOCKER) system prune -f
	@echo "${GREEN}Docker资源已清理!${RESET}"

.PHONY: docker-scan
docker-scan: ## 扫描Docker镜像安全漏洞
	@echo "${CYAN}扫描Docker镜像...${RESET}"
	@$(DOCKER) scout cves $(PROJECT_NAME):latest
	@echo "${GREEN}Docker镜像扫描完成!${RESET}"

# ============================================
##@ 数据库
# ============================================

.PHONY: db-init
db-init: ## 初始化数据库
	@echo "${CYAN}初始化数据库...${RESET}"
	@alembic init migrations
	@echo "${GREEN}数据库初始化完成!${RESET}"

.PHONY: db-migrate
db-migrate: ## 创建数据库迁移
	@read -p "迁移描述: " message; \
	alembic revision --autogenerate -m "$$message"
	@echo "${GREEN}数据库迁移已创建!${RESET}"

.PHONY: db-upgrade
db-upgrade: ## 升级数据库到最新版本
	@echo "${CYAN}升级数据库...${RESET}"
	@alembic upgrade head
	@echo "${GREEN}数据库已升级到最新版本!${RESET}"

.PHONY: db-downgrade
db-downgrade: ## 回退到上一个数据库版本
	@echo "${CYAN}回退数据库版本...${RESET}"
	@alembic downgrade -1
	@echo "${GREEN}数据库已回退!${RESET}"

.PHONY: db-history
db-history: ## 查看数据库迁移历史
	@alembic history --verbose

# ============================================
##@ 应用服务
# ============================================

.PHONY: run
run: ## 启动开发服务器
	@echo "${CYAN}启动开发服务器...${RESET}"
	@uvicorn src.main:app \
		--host 0.0.0.0 \
		--port 8000 \
		--reload \
		--reload-dir src \
		--log-level debug

.PHONY: run-prod
run-prod: ## 启动生产服务器
	@echo "${CYAN}启动生产服务器...${RESET}"
	@uvicorn src.main:app \
		--host 0.0.0.0 \
		--port 8000 \
		--workers 4 \
		--loop uvloop \
		--http httptools \
		--proxy-headers \
		--forwarded-allow-ips "*"

.PHONY: run-worker
run-worker: ## 启动Celery Worker
	@echo "${CYAN}启动Celery Worker...${RESET}"
	@celery -A src.infrastructure.celery_app worker \
		--loglevel=info \
		--concurrency=4 \
		--pool=prefork

.PHONY: run-beat
run-beat: ## 启动Celery Beat
	@echo "${CYAN}启动Celery Beat...${RESET}"
	@celery -A src.infrastructure.celery_app beat \
		--loglevel=info \
		--schedule=./data/celerybeat-schedule

.PHONY: run-flower
run-flower: ## 启动Celery Flower监控
	@echo "${CYAN}启动Flower监控...${RESET}"
	@celery -A src.infrastructure.celery_app flower \
		--port=5555 \
		--basic_auth=admin:admin123

# ============================================
##@ 项目构建
# ============================================

.PHONY: build
build: ## 构建Python包
	@echo "${CYAN}构建Python包...${RESET}"
	@$(PYTHON) -m build --outdir dist/
	@echo "${GREEN}Python包已构建在 dist/ 目录!${RESET}"

.PHONY: publish
publish: build ## 发布Python包到PyPI
	@echo "${CYAN}发布Python包...${RESET}"
	@twine check dist/*
	@twine upload dist/*
	@echo "${GREEN}Python包已发布!${RESET}"

.PHONY: version
version: ## 显示项目版本
	@grep -E 'version\s*=' pyproject.toml | head -1 | awk '{print $$3}' | tr -d '"'

# ============================================
##@ 清理
# ============================================

.PHONY: clean
clean: ## 清理所有生成的文件
	@echo "${CYAN}清理生成文件...${RESET}"
	@find . -type d -name "__pycache__" -exec rm -rf {} +
	@find . -type f -name "*.pyc" -delete
	@find . -type f -name "*.pyo" -delete
	@find . -type f -name "*.pyd" -delete
	@find . -type d -name "*.egg-info" -exec rm -rf {} +
	@find . -type d -name ".pytest_cache" -exec rm -rf {} +
	@find . -type d -name ".mypy_cache" -exec rm -rf {} +
	@find . -type d -name ".ruff_cache" -exec rm -rf {} +
	@rm -rf build/ dist/ .tox/ .eggs/ htmlcov/ .coverage coverage.xml pytest-report.xml
	@echo "${GREEN}清理完成!${RESET}"

.PHONY: deep-clean
deep-clean: clean clean-venv ## 深度清理 (包括虚拟环境)
	@echo "${GREEN}深度清理完成!${RESET}"

# ============================================
##@ 帮助工具
# ============================================

.PHONY: docs
docs: ## 生成API文档 (自动打开浏览器)
	@echo "${CYAN}API文档地址:${RESET}"
	@echo "  Swagger UI:   http://localhost:8000/docs"
	@echo "  ReDoc:        http://localhost:8000/redoc"
	@echo "  OpenAPI JSON: http://localhost:8000/openapi.json"

.PHONY: info
info: ## 显示项目信息
	@echo ""
	@echo "${CYAN}=== 项目信息 ===${RESET}"
	@echo "项目名称: $(PROJECT_NAME)"
	@echo "Python版本: $(shell $(PYTHON) --version)"
	@echo "项目版本: $(shell grep -E 'version\s*=' pyproject.toml | head -1 | awk '{print $$3}' | tr -d '"')"
	@echo ""
	@echo "${CYAN}=== 端口映射 ===${RESET}"
	@echo "API服务:     8000"
	@echo "PostgreSQL:  5432"
	@echo "Redis:       6379"
	@echo "Grafana:     3000"
	@echo "Prometheus:  9090"
	@echo "Flower:      5555"
	@echo ""

.PHONY: check
check: ## 检查所有开发环境依赖
	@echo "${CYAN}检查开发环境...${RESET}"
	@command -v $(PYTHON) >/dev/null 2>&1 && echo "${GREEN}✓ Python${RESET}" || echo "${YELLOW}✗ Python${RESET}"
	@command -v $(PIP) >/dev/null 2>&1 && echo "${GREEN}✓ Pip${RESET}" || echo "${YELLOW}✗ Pip${RESET}"
	@command -v $(DOCKER) >/dev/null 2>&1 && echo "${GREEN}✓ Docker${RESET}" || echo "${YELLOW}✗ Docker${RESET}"
	@command -v pre-commit >/dev/null 2>&1 && echo "${GREEN}✓ Pre-commit${RESET}" || echo "${YELLOW}✗ Pre-commit${RESET}"
	@command -v ruff >/dev/null 2>&1 && echo "${GREEN}✓ Ruff${RESET}" || echo "${YELLOW}✗ Ruff${RESET}"
	@command -v mypy >/dev/null 2>&1 && echo "${GREEN}✓ Mypy${RESET}" || echo "${YELLOW}✗ Mypy${RESET}"
	@command -v black >/dev/null 2>&1 && echo "${GREEN}✓ Black${RESET}" || echo "${YELLOW}✗ Black${RESET}"
	@command -v pytest >/dev/null 2>&1 && echo "${GREEN}✓ Pytest${RESET}" || echo "${YELLOW}✗ Pytest${RESET}"

# ============================================
##@ CI/CD
# ============================================

.PHONY: ci
ci: quality test-coverage build docker-build ## 运行完整CI流程
	@echo "${GREEN}CI流程完成!${RESET}"

# 默认目标
.DEFAULT_GOAL := help
