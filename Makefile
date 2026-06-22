.PHONY: help build backend frontend clean test db-init docker-up docker-down k8s-deploy k8s-undeploy

# 显示帮助信息
help:
	@echo "FeatureFlag Platform - Makefile"
	@echo ""
	@echo "可用命令:"
	@echo "  make dev              启动本地开发环境（Docker）"
	@echo "  make docker-up        启动所有服务（Docker Compose）"
	@echo "  make docker-down      停止所有服务（Docker Compose）"
	@echo "  make build-backend    构建后端"
	@echo "  make build-frontend   构建前端"
	@echo "  make build            构建所有组件"
	@echo "  make run-backend      运行后端"
	@echo "  make run-frontend     运行前端"
	@echo "  make test-backend     运行后端测试"
	@echo "  make db-init          初始化数据库"
	@echo "  make k8s-deploy       部署到Kubernetes"
	@echo "  make k8s-undeploy     从Kubernetes卸载"
	@echo "  make clean            清理构建产物"

# 启动本地开发环境
dev: docker-up
	@echo "开发环境已启动"
	@echo "前端: http://localhost:3000"
	@echo "后端API: http://localhost:8080"
	@echo "Kafka UI: http://localhost:8081"

# Docker Compose 启动所有服务
docker-up:
	@echo "启动 Docker Compose 服务..."
	docker-compose up -d

# Docker Compose 停止所有服务
docker-down:
	@echo "停止 Docker Compose 服务..."
	docker-compose down

# 构建后端
build-backend:
	@echo "构建后端..."
	cd backend && go build -o bin/server ./cmd/server

# 构建前端
build-frontend:
	@echo "构建前端..."
	cd frontend && npm install && npm run build

# 构建所有组件
build: build-backend build-frontend

# 运行后端
run-backend:
	@echo "运行后端..."
	cd backend && go run ./cmd/server

# 运行前端
run-frontend:
	@echo "运行前端..."
	cd frontend && npm run dev

# 运行后端测试
test-backend:
	@echo "运行后端测试..."
	cd backend && go test -v ./...

# 初始化数据库
db-init:
	@echo "初始化数据库..."
	psql -h localhost -U postgres -d featureflag -f scripts/init.sql

# 部署到Kubernetes
k8s-deploy:
	@echo "部署到 Kubernetes..."
	kubectl apply -f deploy/k8s/

# 从Kubernetes卸载
k8s-undeploy:
	@echo "从 Kubernetes 卸载..."
	kubectl delete -f deploy/k8s/

# 清理构建产物
clean:
	@echo "清理构建产物..."
	rm -rf backend/bin
	rm -rf frontend/dist
	rm -rf backend/*.out
	rm -rf backend/coverage.html
