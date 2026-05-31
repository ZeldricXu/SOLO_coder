.PHONY: help build test clean package docker run code-quality deploy

.DEFAULT_GOAL := help

ENV ?= dev

help: ## 显示帮助信息
	@awk 'BEGIN {FS = ":.*##"; printf "\n用法:\n  make \033[36m<目标>\033[0m\n\n可用目标:\n"} /^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

build: ## 编译项目
	mvn clean compile -P$(ENV)

test: ## 运行单元测试
	mvn test -P$(ENV)

test-coverage: ## 运行测试并生成覆盖率报告
	mvn test jacoco:report -P$(ENV)

code-quality: ## 运行代码质量检查
	mvn clean verify -Pcode-quality

checkstyle: ## 运行 Checkstyle 检查
	mvn checkstyle:check

pmd: ## 运行 PMD 分析
	mvn pmd:check pmd:pmd

spotbugs: ## 运行 SpotBugs 分析
	mvn spotbugs:check spotbugs:spotbugs

clean: ## 清理构建产物
	mvn clean

package: ## 打包项目 (跳过测试)
	mvn clean package -P$(ENV) -DskipTests -pl smartflow-boot -am

package-all: ## 打包所有模块
	mvn clean package -P$(ENV) -DskipTests

docker-build: ## 构建 Docker 镜像
	docker build -t smartflow:$(ENV) .

docker-push: ## 推送 Docker 镜像
	docker tag smartflow:$(ENV) $(REGISTRY)/smartflow:$(ENV)
	docker push $(REGISTRY)/smartflow:$(ENV)

up: ## 启动开发环境
	docker-compose -f docker-compose.dev.yml up -d

down: ## 停止开发环境
	docker-compose -f docker-compose.dev.yml down

logs: ## 查看应用日志
	docker-compose -f docker-compose.dev.yml logs -f app

run: ## 本地运行应用
	mvn spring-boot:run -pl smartflow-boot -P$(ENV)

deploy-staging: ## 部署到预发布环境
	@echo "Deploying to staging..."
	@ssh $(STAGING_SERVER) "cd /opt/smartflow && docker-compose pull app && docker-compose up -d app"

deploy-prod: ## 部署到生产环境
	@echo "Deploying to production..."
	@ssh $(PROD_SERVER) "cd /opt/smartflow && docker-compose pull app && docker-compose up -d app"

version-bump: ## 升级版本号 (使用: make version-bump NEW_VERSION=1.1.0)
	mvn versions:set -DnewVersion=$(NEW_VERSION) -DgenerateBackupPoms=false

outdated: ## 检查过时的依赖
	mvn versions:display-dependency-updates
