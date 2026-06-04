# 推理平台 (Inference Platform)

企业级机器学习推理服务平台，支持大规模、高可用、低延迟的模型推理服务。

## 架构特性

- 🚀 **高性能推理**: 基于NVIDIA Triton Inference Server，支持动态批处理和多模型并行
- 🐳 **容器化部署**: Docker + Kubernetes，支持GPU资源调度
- ⚖️ **客户端负载均衡**: SDK直连推理实例，消除中心化路由瓶颈
- 📊 **可观测性**: Prometheus + Grafana 完整监控链路
- 🔄 **自动扩缩容**: 基于负载的智能扩缩容
- 📦 **多模型队列**: 每模型独立攒批队列，互不干扰

## 快速开始

### 本地开发 (docker-compose)

```bash
# 1. 克隆项目
git clone <repo-url>
cd DF1-51

# 2. 创建环境配置
cp .env.example .env.development

# 3. 启动所有服务
docker-compose up -d

# 4. 查看服务状态
docker-compose ps

# 5. 访问服务
# 推理平台API: http://localhost:8080
# Grafana监控: http://localhost:3000 (admin/admin123)
# Prometheus: http://localhost:9090
```

### 本地开发 (Go 原生)

```bash
# 1. 配置环境变量
export APP_ENV=development
export DB_HOST=localhost
export REDIS_HOST=localhost

# 2. 运行
go run cmd/server/main.go

# 3. 运行测试
go test ./... -v
```

## 项目结构

```
.
├── cmd/
│   └── server/
│       └── main.go              # 服务入口
├── internal/
│   ├── abtest/                   # A/B测试模块
│   ├── batcher/                  # 批量推理模块
│   ├── model/                    # 模型管理
│   ├── monitoring/               # 监控与漂移检测
│   ├── notification/             # 通知模块
│   ├── orchestrator/             # 推理服务编排器
│   ├── pkg/
│   │   ├── config/              # 配置管理
│   │   ├── container/           # 容器管理
│   │   ├── database/            # 数据库接口
│   │   ├── redis/               # Redis接口
│   │   └── triton/              # Triton客户端
│   ├── router/                   # 路由表管理
│   ├── sdk/                      # 客户端SDK
│   ├── tenant/                   # 多租户
│   └── webhook/                  # Webhook接收器
├── helm/
│   └── inference-platform/       # Helm Chart
├── docker/
│   ├── grafana/                  # Grafana配置
│   └── prometheus.yml            # Prometheus配置
├── docs/                         # 运维文档
├── scripts/
│   └── init_db.sql               # 数据库初始化脚本
├── .github/
│   └── workflows/
│       └── ci.yml                # GitHub Actions CI/CD
├── Dockerfile                    # Docker镜像构建
├── docker-compose.yml            # 本地开发编排
├── config.yaml                   # 基础配置
└── .env.*                        # 环境变量配置
```

## 配置管理

### 配置优先级 (从高到低)

1. 环境变量
2. `.env.{env}` 文件
3. `config.yaml` 文件

### 环境变量说明

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| APP_ENV | 运行环境 (development/staging/production) | development |
| DB_HOST | 数据库地址 | localhost |
| DB_PORT | 数据库端口 | 5432 |
| DB_USER | 数据库用户 | postgres |
| DB_PASSWORD | 数据库密码 | postgres |
| DB_NAME | 数据库名 | inference_platform |
| REDIS_HOST | Redis地址 | localhost |
| REDIS_PORT | Redis端口 | 6379 |
| REDIS_PASSWORD | Redis密码 | - |
| TRITON_GRPC_HOST | Triton gRPC地址 | localhost |
| TRITON_GRPC_PORT | Triton gRPC端口 | 8001 |

### 配置文件

```bash
# 开发环境
.env.development

# 预发布环境
.env.staging

# 生产环境
.env.production

# 配置模板
.env.example
```

## 部署指南

### Docker 构建

```bash
# 构建镜像
docker build -t inference-platform:latest .

# 运行容器
docker run -p 8080:8080 \
  -e DB_HOST=postgres \
  -e REDIS_HOST=redis \
  inference-platform:latest
```

### Kubernetes 部署 (Helm)

```bash
# 添加 Helm仓库 (如果需要)
# helm repo add ...

# 部署到预发布环境
helm upgrade --install inference-platform ./helm/inference-platform \
  -f ./helm/inference-platform/values-staging.yaml \
  -n inference-platform \
  --create-namespace

# 部署到生产环境
helm upgrade --install inference-platform ./helm/inference-platform \
  -f ./helm/inference-platform/values-production.yaml \
  -n inference-platform \
  --create-namespace

# 查看部署状态
helm list -n inference-platform
kubectl get pods -n inference-platform
```

### CI/CD 流水线

本项目使用 GitHub Actions 实现自动化 CI/CD：

1. **Lint**: golangci-lint 代码检查
2. **Test**: 单元测试 + 集成测试 (PostgreSQL/Redis/Triton service containers)
3. **Build**: 构建Docker镜像并推送到 GHCR
4. **Deploy**: 自动部署到对应环境

触发条件:
- PR 到 main/develop: 运行 Lint + Test
- Push 到 develop: Lint + Test + Build + 部署到 Staging
- Push tag v*: Lint + Test + Build + 部署到 Production

## 运维文档

详细运维文档请查看 [docs/](./docs/) 目录:

| 文档 | 说明 |
|------|------|
| [01-model-onboarding-guide.md](./docs/01-model-onboarding-guide.md) | 新模型接入完整流程 |
| [02-triton-config-guide.md](./docs/02-triton-config-guide.md) | Triton模型配置详解 |
| [03-gpu-fault-handling.md](./docs/03-gpu-fault-handling.md) | GPU故障应急处理 |

### 常用命令

```bash
# 查看平台状态
curl http://localhost:8080/health

# 查看所有模型
curl http://localhost:8080/api/v1/models

# 查看推理实例
curl http://localhost:8080/api/v1/instances

# 查看监控指标
curl http://localhost:9090/metrics
```

## 监控大盘

Grafana监控大盘包含以下面板:

**平台总览**
- 总 QPS
- 平均延迟 (P50/P95)
- 错误率
- 各模型 QPS/延迟

**GPU 监控**
- GPU 利用率
- GPU 显存使用率
- GPU 温度
- GPU 功耗

**实例与资源**
- 各模型实例数
- 各实例负载

**业务指标**
- 模型准确率
- 概念漂移检测

## API 示例

### 注册模型

```bash
curl -X POST http://localhost:8080/api/v1/models \
  -H "Content-Type: application/json" \
  -d '{
    "name": "resnet50",
    "description": "图像分类模型",
    "framework": "pytorch",
    "task_type": "classification"
  }'
```

### 部署模型

```bash
curl -X POST http://localhost:8080/api/v1/models/resnet50/versions/1/deploy \
  -H "Content-Type: application/json" \
  -d '{
    "min_replicas": 2,
    "max_replicas": 8,
    "gpu_count": 1
  }'
```

### 推理请求

```bash
curl -X POST http://localhost:8080/v2/models/resnet50/infer \
  -H "Content-Type: application/json" \
  -d '{
    "id": "request-001",
    "inputs": [{
      "name": "input_0",
      "shape": [1, 3, 224, 224],
      "datatype": "FP32",
      "data": [...]
    }]
  }'
```

## 开发指南

### 代码规范

```bash
# 运行 linter
golangci-lint run

# 运行测试
go test ./... -v -race

# 查看测试覆盖率
go test -coverprofile=coverage.out ./...
go tool cover -html=coverage.out
```

### 添加新的依赖

```bash
go get <package-name>
go mod tidy
```

## 故障排查

### 常见问题

**Q: 模型部署失败，状态一直是 STARTING?**

A: 检查 Triton 容器日志和模型配置文件:
```bash
kubectl logs -l app=triton-server -n inference-platform
```

**Q: 推理请求超时?**

A: 检查以下几点:
1. GPU 利用率是否过高
2. 批量大小是否合适
3. 实例数量是否足够

**Q: 服务启动失败?**

A: 检查配置和依赖服务:
- 数据库连接是否正常
- Redis 连接是否正常
- 环境变量是否正确设置

## 联系方式

- MLOps 团队: mlops@your-company.com
- 项目仓库: <internal-repo-url>

## License

Internal use only.
