# StreamSQL - 流式SQL计算执行引擎

> 轻量高效的流式SQL计算执行引擎，专注于时序数据处理、质量校验、生命周期管理与实时分析。

[![Go Version](https://img.shields.io/badge/go-1.21+-blue.svg)](https://golang.org/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Build Status](https://github.com/streamsql/streamsql/workflows/CI/badge.svg)](https://github.com/streamsql/streamsql/actions)

## 🌟 核心特性

| 模块 | 核心能力 |
|------|----------|
| **时序数据压缩** | Gorilla XOR压缩、Delta差值压缩、Simple8b整数压缩；LTTB/平均值/最大最小值降采样；5级多分辨率存储 |
| **数据质量校验** | 规则CRUD、Cron定时调度、异常标记与记录、重试机制 |
| **数据生命周期管理** | 年龄分层策略、冷热数据迁移、自动归档清理、完整CRUD |
| **CDC增量捕获** | MySQL Binlog、PostgreSQL WAL、MongoDB Oplog解析；多格式序列化；多端输出适配 |
| **向量索引构建** | Flat精确匹配、HNSW高性能ANN、IVF聚类索引；向量CRUD、最近邻搜索 |
| **数据血缘解析** | SQL解析提取表/字段血缘、DAG图谱构建、上下游追踪、DFS全链路分析 |
| **流式查询解析** | SQL语法解析、逻辑计划优化（谓词下推、列裁剪）、物理计划生成 |
| **元数据采集爬虫** | 多数据源扫描、Schema提取、统计信息、样例数据生成 |
| **网关层** | 请求追踪、Bearer Token鉴权、IP限流、全局日志中间件 |
| **核心引擎** | 事件总线（Pub/Sub）、内存状态存储、模块协调、统计聚合 |

## 🏗️ 系统架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                              API Gateway                            │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐   │
│  │  鉴权中间件│  │  限流中间件│  │  日志中间件│  │  追踪中间件│   │
│  └────────────┘  └────────────┘  └────────────┘  └────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                           Core Engine                                │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐   │
│  │  事件总线  │  │  状态管理  │  │  查询调度  │  │  统计聚合  │   │
│  └────────────┘  └────────────┘  └────────────┘  └────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                    │
            ┌───────────────────────┼───────────────────────┐
            ▼                       ▼                       ▼
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│  时序数据压缩模块   │  │  数据质量校验模块   │  │  生命周期管理模块   │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
            ▼                       ▼                       ▼
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│  CDC增量捕获模块    │  │  向量索引构建模块   │  │  数据血缘解析模块   │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
            ▼                       ▼                       ▼
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│  流式查询解析模块   │  │  元数据采集爬虫     │  │    基础设施层       │
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘
```

## 🚀 快速开始

### 环境要求

- Go 1.21+
- Docker & Docker Compose (推荐)
- PostgreSQL 14+
- Redis 7+

### 方式一：Docker Compose (推荐)

```bash
# 克隆项目
git clone https://github.com/streamsql/streamsql.git
cd streamsql

# 复制环境变量文件
cp .env.example .env

# 启动所有服务
docker-compose up -d

# 验证服务
curl http://localhost:8080/api/v1/health
```

### 方式二：本地构建

```bash
# 克隆项目
git clone https://github.com/streamsql/streamsql.git
cd streamsql

# 安装依赖
make dependencies

# 安装开发工具
make install-tools

# 格式化并检查代码
make fmt lint

# 运行测试
make test

# 构建
make build

# 运行
APP_ENV=development ./bin/streamsql
```

## 📚 API 文档

### 健康检查
```bash
GET /api/v1/health
```

### 查询执行
```bash
POST /api/v1/query/execute
Content-Type: application/json

{
  "sql": "SELECT * FROM users WHERE created_at > '2024-01-01'"
}
```

### 质量规则
```bash
# 列出规则
GET /api/v1/quality/rules

# 创建规则
POST /api/v1/quality/rules
Content-Type: application/json

{
  "name": "年龄范围检查",
  "description": "确保用户年龄在合理范围内",
  "type": "range",
  "severity": "warning",
  "expression": "age >= 0 AND age <= 150"
}
```

### 数据血缘
```bash
# 解析SQL血缘
POST /api/v1/lineage/parse
Content-Type: application/json

{
  "sql": "INSERT INTO result_table SELECT a.id, b.name FROM source_a a JOIN source_b b ON a.id = b.id"
}

# 获取DAG图谱
GET /api/v1/lineage/dag
```

### 向量索引
```bash
# 创建索引
POST /api/v1/vector/indexes
Content-Type: application/json

{
  "name": "product_embeddings",
  "dim": 1536,
  "type": "hnsw"
}

# 搜索最近邻
POST /api/v1/vector/indexes/product_embeddings/search
Content-Type: application/json

{
  "vector": [0.1, 0.2, 0.3, ...],
  "top_k": 10
}
```

完整的API文档请参考 [API.md](docs/API.md)

## ⚙️ 配置

### 多环境配置

项目支持多环境配置，配置文件位于 `config/` 目录：

```
config/
├── development.json     # 开发环境
├── testing.json         # 测试环境
├── staging.json         # 预发布环境
└── production.json      # 生产环境
```

### 环境变量

支持通过环境变量覆盖配置：

```bash
# 应用环境
APP_ENV=development
GIN_MODE=debug

# 服务器配置
SERVER_HOST=0.0.0.0
SERVER_PORT=8080

# 数据库配置
DB_HOST=localhost
DB_PORT=5432
DB_USER=postgres
DB_PASSWORD=postgres
DB_NAME=streamsql_dev

# Redis配置
REDIS_ADDR=localhost:6379
REDIS_PASSWORD=

# 日志配置
LOG_LEVEL=debug
```

## 🛠️ 开发指南

### 常用命令

```bash
make help              # 查看所有可用命令
make fmt               # 格式化代码
make lint              # 运行代码检查
make test              # 运行单元测试
make test-coverage     # 运行测试并生成覆盖率报告
make build             # 构建二进制
make docker-build      # 构建Docker镜像
make quality-gate      # 运行完整质量门禁
```

### 代码规范

- 遵循 [Effective Go](https://golang.org/doc/effective_go) 规范
- 使用 `gofmt` 和 `goimports` 格式化代码
- 所有公共API必须有完整的文档注释
- 新增功能必须包含单元测试，测试覆盖率不低于70%
- 提交前必须通过质量门禁 (`make quality-gate`)

### 目录结构

```
streamsql/
├── cmd/
│   └── streamsql/          # 应用入口
│       └── main.go
├── internal/
│   ├── common/             # 公共模块
│   │   ├── config/         # 配置管理
│   │   ├── errors/         # 错误处理
│   │   ├── logger/         # 日志模块
│   │   └── models/         # 数据模型
│   ├── compression/        # 时序数据压缩模块
│   ├── quality/            # 数据质量校验模块
│   ├── lifecycle/          # 数据生命周期管理模块
│   ├── cdc/                # CDC增量捕获模块
│   ├── vectorindex/        # 向量索引构建模块
│   ├── lineage/            # 数据血缘解析模块
│   ├── streamparser/       # 流式查询解析模块
│   ├── metacrawler/        # 元数据采集爬虫模块
│   ├── gateway/            # 网关层
│   ├── engine/             # 核心引擎
│   └── api/                # API接口层
├── config/                 # 配置文件
├── deploy/                 # 部署相关
├── docs/                   # 文档
├── .github/workflows/      # CI/CD流水线
├── go.mod
├── go.sum
├── Makefile
├── Dockerfile
├── docker-compose.yml
└── README.md
```

## 📦 部署

### Kubernetes 部署

```bash
# 1. 构建并推送镜像
make docker-build docker-push

# 2. 部署到Kubernetes
kubectl apply -f deploy/k8s/

# 3. 验证部署
kubectl get pods -n streamsql
kubectl get svc -n streamsql
```

### Helm Chart

```bash
# 安装Helm Chart
helm repo add streamsql https://charts.streamsql.io
helm install streamsql streamsql/streamsql \
  --namespace streamsql \
  --create-namespace \
  --values values.yaml
```

详细部署指南请参考 [DEPLOYMENT.md](docs/DEPLOYMENT.md)

## 🧪 测试

```bash
# 运行单元测试
make test

# 运行测试并生成覆盖率报告
make test-coverage

# 运行集成测试
go test -v ./test/integration/...

# 运行性能测试
go test -bench=. ./test/benchmark/...
```

## 📈 监控

项目内置Prometheus指标，可通过 `/metrics` 端点访问：

```bash
# 查看指标
curl http://localhost:8080/metrics
```

支持的监控指标包括：
- HTTP请求统计（请求数、延迟、错误率）
- SQL查询统计（执行数、成功率、平均耗时）
- 模块性能指标（压缩率、索引构建速度等）
- 系统资源使用（CPU、内存、GC等）

可使用提供的Grafana仪表盘进行可视化监控。

## 🤝 贡献

我们欢迎所有形式的贡献！请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解如何参与项目开发。

### 贡献流程

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

## 📮 联系方式

- 项目主页: [https://github.com/streamsql/streamsql](https://github.com/streamsql/streamsql)
- 问题反馈: [https://github.com/streamsql/streamsql/issues](https://github.com/streamsql/streamsql/issues)
- 邮箱: [dev@streamsql.io](mailto:dev@streamsql.io)

## 🙏 致谢

感谢以下开源项目的支持：

- [Gin](https://github.com/gin-gonic/gin) - HTTP Web框架
- [GORM](https://gorm.io/) - ORM框架
- [Zap](https://github.com/uber-go/zap) - 结构化日志
- [golangci-lint](https://golangci-lint.run/) - Go语言代码检查工具
- [sqlparser](https://github.com/xwb1989/sqlparser) - SQL解析器

---

**StreamSQL** - 让流式数据处理更简单高效 ⚡
