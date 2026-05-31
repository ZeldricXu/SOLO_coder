.PHONY: help install build dev test lint format clean docker-up docker-down deploy

help:
	@echo "可用的 make 命令:"
	@echo "  install     - 安装项目依赖"
	@echo "  build       - 编译 TypeScript 代码"
	@echo "  dev         - 启动开发服务器"
	@echo "  start       - 启动生产服务器"
	@echo "  test        - 运行测试"
	@echo "  test:cov    - 运行测试并生成覆盖率报告"
	@echo "  lint        - 运行 ESLint 代码检查"
	@echo "  lint:fix    - 自动修复 ESLint 错误"
	@echo "  format      - 使用 Prettier 格式化代码"
	@echo "  format:check- 检查代码格式"
	@echo "  typecheck   - 运行 TypeScript 类型检查"
	@echo "  clean       - 清理构建产物"
	@echo "  docker-up   - 启动 Docker 服务"
	@echo "  docker-down - 停止 Docker 服务"
	@echo "  deploy      - 部署到指定环境 (staging/production)"
	@echo "  health      - 健康检查"

install:
	npm ci

build:
	npm run build

dev:
	npm run dev

start:
	npm start

test:
	npm test

test:cov:
	npm run test:cov

lint:
	npm run lint

lint:fix:
	npm run lint:fix

format:
	npm run format

format:check:
	npm run format:check

typecheck:
	npm run typecheck

clean:
	npm run clean

docker-up:
	docker compose up -d

docker-down:
	docker compose down

deploy:
	@read -p "部署环境 (staging/production): " env; \
	bash scripts/deploy.sh $$env

health:
	bash scripts/health-check.sh
