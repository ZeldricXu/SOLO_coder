# DF1-96 分布式计算实验平台

## 项目简介

DF1-96 是一个高性能的分布式计算实验平台，用于大规模并行计算任务的调度、执行和结果分析。平台采用微服务架构，支持多种优化算法，提供完整的实验生命周期管理。

## 核心功能

- **实验管理**：创建、启动、暂停、恢复、取消实验
- **任务调度**：智能任务分配、负载均衡、失败重试、超时处理
- **工作节点管理**：节点注册、心跳监控、资源状态收集
- **计算引擎**：矩阵运算、梯度下降、Adam、LBFGS 优化算法
- **结果分析**：统计分析、敏感性分析、数据可视化
- **数据导出**：支持 CSV 和 Parquet 格式导出
- **监控指标**：Prometheus 指标集成、Grafana 仪表板

## 系统架构

```
┌───────────────────────────────────────────────────────────┐
│                     API Server (Gin)                      │
│  ┌─────────┬─────────┬─────────┬─────────┬──────────┐    │
│  │ 实验    │ 任务    │ 工作节点│ 结果    │ 监控     │    │
│  └─────────┴─────────┴─────────┴─────────┴──────────┘    │
└─────────────────────────────┬─────────────────────────────┘
                              │
┌─────────────────────────────▼─────────────────────────────┐
│                      Task Scheduler                       │
│  ┌─────────┬─────────┬─────────┬─────────┬──────────┐    │
│  │ 优先级队列 │ 分片器 │ 任务追踪│ 工作监控│ 事件回调 │    │
│  └─────────┴─────────┴─────────┴─────────┴──────────┘    │
└─────────────────────────────┬─────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌───────────────┐     ┌───────────────┐     ┌───────────────┐
│   Worker 1    │     │   Worker 2    │     │   Worker N    │
│  ┌─────────┐  │     │  ┌─────────┐  │     │  ┌─────────┐  │
│  │执行器   │  │     │  │执行器   │  │     │  │执行器   │  │
│  ├─────────┤  │     │  ├─────────┤  │     │  ├─────────┤  │
│  │缓存     │  │     │  │缓存     │  │     │  │缓存     │  │
│  ├─────────┤  │     │  ├─────────┤  │     │  ├─────────┤  │
│  │心跳     │  │     │  │心跳     │  │     │  │心跳     │  │
│  ├─────────┤  │     │  ├─────────┤  │     │  ├─────────┤  │
│  │监控采集 │  │     │  │监控采集 │  │     │  │监控采集 │  │
│  └─────────┘  │     │  └─────────┘  │     │  └─────────┘  │
└───────────────┘     └───────────────┘     └───────────────┘
                              │
┌─────────────────────────────▼─────────────────────────────┐
│                   Compute Engine                          │
│  ┌─────────┬─────────┬─────────┬─────────┬──────────┐    │
│  │矩阵运算 │ 梯度下降│ Adam    │ LBFGS   │ 自动微分  │    │
│  └─────────┴─────────┴─────────┴─────────┴──────────┘    │
└─────────────────────────────┬─────────────────────────────┘
                              │
┌─────────────────────────────▼─────────────────────────────┐
│                   Storage (PostgreSQL)                    │
│  ┌─────────┬─────────┬─────────┬─────────┬──────────┐    │
│  │实验库   │ 任务库   │ 结果库  │检查点库 │ 工作节点库│    │
│  └─────────┴─────────┴─────────┴─────────┴──────────┘    │
└───────────────────────────────────────────────────────────┘
```

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Go | 1.24+ |
| Web 框架 | Gin | 1.10.0 |
| RPC 框架 | gRPC | 1.59.0 |
| ORM | GORM | 1.25.10 |
| 数据库 | PostgreSQL | 15+ |
| 配置管理 | Viper | 1.18.2 |
| 日志 | Zap + Lumberjack | 1.27.0 |
| ID 生成 | Snowflake | 0.3.0 |
| 科学计算 | Gonum | 0.17.0 |
| 监控 | Prometheus | 1.19.0 |
| 数据格式 | Parquet | 1.6.2 |
| 哈希 | xxhash | 2.3.0 |
| 限流 | golang.org/x/time | 0.5.0 |

## 快速开始

### 环境要求

- Go 1.24+
- PostgreSQL 15+
- 可选：Prometheus + Grafana 用于监控

### 构建项目

```bash
cd DF1-96
go mod tidy
go build ./...
```

### 配置

创建 `config.yaml` 配置文件：

```yaml
server:
  grpc_port: 50051
  http_port: 8080

scheduler:
  heartbeat_timeout: 30s
  sharding_strategy: by_hash
  max_retries: 3
  task_timeout: 10m
  chunk_size: 1000

worker:
  cache_size: 10000
  heartbeat_interval: 5s
  concurrent_tasks: 10
  worker_id: 1

database:
  host: localhost
  port: 5432
  user: postgres
  password: password
  dbname: experiment
  sslmode: disable
  timezone: UTC

log:
  level: info
  format: json
  output_path: stdout
  max_size: 100
  max_backups: 3
  max_age: 28
  compress: true
```

也可以使用环境变量配置（前缀 `EXP_`）：

```bash
export EXP_DATABASE_HOST=localhost
export EXP_DATABASE_PORT=5432
export EXP_DATABASE_USER=postgres
```

### 数据库初始化

```go
import (
    "gorm.io/gorm"
    "gorm.io/driver/postgres"
    "github.com/df1-96/experiment/internal/models"
)

db, _ := gorm.Open(postgres.Open(dsn), &gorm.Config{})
models.AutoMigrate(db)
```

## API 接口

### 实验管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/experiments` | 获取实验列表 |
| POST | `/api/v1/experiments` | 创建实验 |
| GET | `/api/v1/experiments/:id` | 获取实验详情 |
| PUT | `/api/v1/experiments/:id` | 更新实验 |
| DELETE | `/api/v1/experiments/:id` | 删除实验 |
| POST | `/api/v1/experiments/:id/start` | 启动实验 |
| POST | `/api/v1/experiments/:id/pause` | 暂停实验 |
| POST | `/api/v1/experiments/:id/resume` | 恢复实验 |
| POST | `/api/v1/experiments/:id/cancel` | 取消实验 |

### 任务管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/experiments/:expId/tasks` | 获取实验任务列表 |
| GET | `/api/v1/tasks/:id` | 获取任务详情 |
| GET | `/api/v1/tasks/:id/checkpoints` | 获取任务检查点 |
| GET | `/api/v1/tasks/:id/results` | 获取任务结果 |

### 工作节点管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/workers` | 获取工作节点列表 |
| GET | `/api/v1/workers/:id` | 获取工作节点详情 |
| GET | `/api/v1/workers/:id/history` | 获取工作节点历史 |

### 结果分析

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/experiments/:expId/results` | 获取结果列表 |
| GET | `/api/v1/experiments/:expId/results/statistics` | 获取统计信息 |
| GET | `/api/v1/experiments/:expId/results/sensitivity` | 获取敏感性分析 |
| GET | `/api/v1/experiments/:expId/results/export/csv` | 导出 CSV |
| GET | `/api/v1/experiments/:expId/results/export/parquet` | 导出 Parquet |

### 其他接口

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/health` | 健康检查 |
| GET | `/metrics` | Prometheus 指标 |

## 核心组件说明

### 任务调度器 (Scheduler)

- **优先级队列**：基于任务优先级和截止时间的优先级队列
- **智能分配**：支持负载均衡、能力匹配、位置感知、最佳适配四种分配策略
- **任务追踪**：实时追踪任务进度、检查点、重试次数
- **健康监控**：自动检测离线节点，重新分配任务
- **分片处理**：支持任务分片，提高并行度

### 工作节点 (Worker)

- **资源采集**：实时采集 CPU、内存、磁盘等资源使用情况
- **本地缓存**：基于 LRU 的本地缓存，提高数据访问效率
- **心跳机制**：定期向调度器发送心跳，上报状态和负载
- **任务执行**：支持并发执行多个任务，自动重试失败任务
- **命令处理**：支持暂停、恢复、排空、关闭等远程命令

### 计算引擎 (Compute Engine)

- **矩阵运算**：基于 Gonum 的高效矩阵运算
- **优化算法**：
  - 梯度下降 (Gradient Descent)
  - Adam 自适应矩估计
  - LBFGS 拟牛顿法
- **自动微分**：支持自动梯度计算
- **目标函数插件**：可扩展的目标函数注册机制

### 分析模块 (Analysis)

- **统计分析**：均值、方差、分位数、相关性等统计指标
- **聚合分析**：按维度聚合、分组统计
- **敏感性分析**：参数敏感性分析、重要性排序
- **可视化数据**：生成图表所需的数据结构

## 监控

### Prometheus 指标

平台内置以下监控指标：

- `http_request_duration_seconds`：HTTP 请求耗时分布
- `http_request_total`：HTTP 请求总数
- `worker_cpu_usage`：工作节点 CPU 使用率
- `worker_memory_usage`：工作节点内存使用率
- `task_duration_seconds`：任务执行耗时分布
- `task_queue_length`：任务队列长度
- `scheduler_assigned_tasks_total`：调度器分配任务总数

### Grafana 仪表板

项目包含两个预配置的 Grafana 仪表板：

- `dashboards/grafana-experiment-dashboard.json`：实验监控仪表板
- `dashboards/grafana-worker-dashboard.json`：工作节点监控仪表板

## gRPC 接口

平台同时提供 gRPC 接口用于内部服务通信，定义在 `api/proto/` 目录：

- `compute.proto`：计算服务接口
- `objective.proto`：目标函数接口
- `parameter.proto`：参数定义
- `task.proto`：任务服务接口
- `worker.proto`：工作节点服务接口

生成代码：

```bash
cd api/proto
./generate.sh
```

## 目录结构

```
DF1-96/
├── api/
│   └── proto/              # Protocol Buffers 定义
├── dashboards/             # Grafana 仪表板配置
├── internal/
│   ├── analysis/           # 数据分析模块
│   ├── apiserver/          # API 服务器
│   ├── compute/            # 计算引擎
│   │   └── objective/      # 目标函数
│   ├── config/             # 配置管理
│   ├── models/             # 数据模型
│   ├── scheduler/          # 任务调度器
│   ├── storage/            # 数据存储
│   └── worker/             # 工作节点
├── pkg/
│   ├── grpcapi/            # gRPC API 实现
│   │   ├── client/         # gRPC 客户端
│   │   ├── distcomp/v1/    # 生成的 Protobuf 代码
│   │   └── server/         # gRPC 服务端
│   └── util/               # 工具函数
├── go.mod
├── go.sum
└── README.md
```

## 数据模型

### Experiment (实验)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | int64 | 主键 |
| name | string | 实验名称 |
| description | string | 实验描述 |
| status | string | 状态 (pending/running/completed/failed/canceled) |
| params | jsonb | 实验参数 |
| config | jsonb | 实验配置 |
| created_by | int64 | 创建者 ID |
| start_time | time | 开始时间 |
| end_time | time | 结束时间 |

### Task (任务)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | int64 | 主键 |
| experiment_id | int64 | 实验 ID |
| name | string | 任务名称 |
| status | string | 状态 |
| params_hash | string | 参数哈希 |
| params | jsonb | 任务参数 |
| priority | int | 优先级 |
| retry_count | int | 重试次数 |
| max_retries | int | 最大重试次数 |
| worker_id | int64 | 分配的工作节点 ID |
| timeout_seconds | int | 超时时间（秒） |

### Worker (工作节点)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | int64 | 主键 |
| name | string | 节点名称（唯一） |
| status | string | 状态 (idle/running/offline/disabled) |
| host | string | 主机地址 |
| port | int | 端口 |
| cpu_cores | int | CPU 核心数 |
| memory_gb | int | 内存大小（GB） |
| last_heartbeat_at | time | 最后心跳时间 |
| tasks_completed | int64 | 完成任务数 |
| tasks_failed | int64 | 失败任务数 |

### Result (结果)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | int64 | 主键 |
| task_id | int64 | 任务 ID |
| worker_id | int64 | 工作节点 ID |
| data | jsonb | 结果数据 |
| checksum | string | 数据校验和 |
| file_path | string | 文件路径 |
| duration_ms | int64 | 执行耗时（毫秒） |
| iteration | int64 | 迭代次数 |

### Checkpoint (检查点)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | int64 | 主键 |
| task_id | int64 | 任务 ID |
| worker_id | int64 | 工作节点 ID |
| step | int64 | 步骤 |
| data | jsonb | 检查点数据 |
| checksum | string | 数据校验和 |
| file_path | string | 文件路径 |

## 开发指南

### 添加新的目标函数

在 `internal/compute/objective/builtin.go` 中注册：

```go
func init() {
    RegisterObjective("my_function", &MyObjective{})
}

type MyObjective struct{}

func (o *MyObjective) Evaluate(params map[string]interface{}) (float64, error) {
    // 实现目标函数逻辑
    return value, nil
}
```

### 自定义优化器

实现 `Optimizer` 接口：

```go
type CustomOptimizer struct{}

func (o *CustomOptimizer) Optimize(
    objective ObjectiveFunction,
    gradient GradientFunction,
    initial []float64,
    config OptimizerConfig,
) ([]float64, float64, error) {
    // 实现优化算法
    return result, fval, nil
}
```

## 许可证

本项目采用 MIT 许可证。

## 贡献

欢迎提交 Issue 和 Pull Request。
