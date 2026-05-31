# DataTrace - 送达追踪与失败重试系统

一个为开发者打造的数据中台系统，专注于SQL解析提取表/字段级血缘关系、送达追踪与失败重试、数据库binlog/WAL解析等核心能力。

## 系统架构

### 分层设计
- **接口层**: API网关，提供RESTful API
- **处理层**: 核心引擎，整合各业务模块
- **存储层**: 对象存储、元数据索引、时序数据库
- **监控层**: 日志、指标、链路追踪

### 模块划分

| 模块 | 描述 | 核心功能 |
|------|------|----------|
| **数据血缘解析模块** | SQL解析提取表/字段级血缘关系，构建DAG图谱 | SQL解析、DAG构建、拓扑排序、血缘查询 |
| **通知模块** | 实现送达追踪与失败重试 | 多渠道发送、重试机制、状态追踪、回调通知 |
| **CDC增量捕获模块** | 数据库binlog/WAL解析、事件序列化与输出适配 | Binlog解析、WAL解析、事件序列化、多输出适配 |
| **存储管理模块** | 实现对象存储适配与元数据索引 | 对象存储、元数据索引、标签查询、流式读写 |
| **调度模块** | 实现任务执行状态追踪 | 任务调度、Cron支持、重试机制、进度追踪 |
| **API网关模块** | 实现请求日志与链路追踪 | 请求日志、链路追踪、CORS、限流 |
| **数据生命周期管理模块** | 冷热数据分层迁移策略、过期数据自动归档与清理 | 冷热分层、自动迁移、过期清理、策略配置 |
| **时序数据压缩模块** | 时序数据压缩编码、降采样策略与多分辨率存储 | 多分辨率存储、Gorilla压缩、Delta压缩、RLE压缩 |
| **数据访问模块** | 实现缓存策略与失效管理 | 多级缓存、LRU/LFU/FIFO/TTL策略、缓存预热 |
| **日志模块** | 实现日志轮转与归档 | 结构化日志、自动轮转、压缩归档、日志解析 |

## 数据流向

```
用户请求 → API网关 → 核心引擎 → 数据归档至存储层 → 更新统计指标
```

## 技术栈

- **语言**: Go 1.22
- **Web框架**: Gin
- **日志**: Zap
- **调度**: robfig/cron
- **唯一ID**: google/uuid

## 项目结构

```
session153/
├── cmd/
│   └── server/
│       └── main.go              # 服务入口
├── internal/
│   ├── models/
│   │   └── models.go            # 通用数据模型
│   ├── lineage/
│   │   └── lineage.go           # 数据血缘解析模块
│   ├── notification/
│   │   └── notification.go      # 通知模块
│   ├── cdc/
│   │   └── cdc.go               # CDC增量捕获模块
│   ├── storage/
│   │   └── storage.go           # 存储管理模块
│   ├── scheduler/
│   │   └── scheduler.go         # 调度模块
│   ├── gateway/
│   │   └── gateway.go           # API网关模块
│   ├── lifecycle/
│   │   └── lifecycle.go         # 数据生命周期管理模块
│   ├── tsdb/
│   │   └── tsdb.go              # 时序数据压缩模块
│   ├── cache/
│   │   └── cache.go             # 数据访问模块
│   ├── logger/
│   │   └── logger.go            # 日志模块
│   └── engine/
│       └── engine.go            # 核心引擎
├── configs/
│   └── config.yaml              # 配置文件
├── go.mod
└── README.md
```

## 快速开始

### 构建

```bash
cd session153
go mod tidy
go build -o datatrace-server ./cmd/server
```

### 运行

```bash
./datatrace-server
```

服务将在 `http://localhost:8080` 启动。

## API接口

### 资源管理API

**POST /api/v1/resources**

创建资源
```json
{
  "type": "task",
  "config": {},
  "labels": {}
}
```

**GET /api/v1/resources/{id}/status**

查询资源状态

**POST /api/v1/resources/batch**

批量操作
```json
{
  "operations": [
    {"action": "start", "id": "rsc_001"}
  ]
}
```

### 核心执行API

**POST /api/v1/execute**

执行操作
```json
{
  "action": "parse_sql",
  "payload": {
    "sql": "SELECT * FROM users JOIN orders ON users.id = orders.user_id"
  }
}
```

支持的actions:
- `parse_sql` - 解析SQL血缘
- `send_notification` - 发送通知
- `ingest_cdc` - 摄入CDC数据
- `store_data` - 存储数据
- `retrieve_data` - 检索数据
- `create_task` - 创建任务
- `execute_task` - 执行任务
- `get_task_status` - 获取任务状态
- `add_metric` - 添加指标
- `query_metrics` - 查询指标
- `get_lineage` - 查询血缘
- `get_stats` - 获取统计信息

### 监控API

**GET /api/v1/stats** - 获取系统统计

**GET /api/v1/handlers** - 获取可用处理器列表

**GET /api/v1/logs** - 获取请求日志

**GET /api/v1/traces/{id}** - 获取链路追踪

**GET /health** - 健康检查

## 核心功能示例

### SQL血缘解析

```go
parser := lineage.NewLineageParser()
tables, lineages, err := parser.ParseSQL(`
    INSERT INTO analytics.user_summary
    SELECT u.id, u.name, COUNT(o.id) as order_count
    FROM production.users u
    JOIN production.orders o ON u.id = o.user_id
    GROUP BY u.id, u.name
`)

graph, err := parser.BuildDAG(tables, lineages)
nodes := graph.GetNodes()
```

### 通知发送

```go
notifService := notification.NewNotificationService(10000)
notifService.RegisterSender("email", emailSender)
notifService.Start()

notif, err := notifService.Send(ctx, "email", "user@example.com", map[string]interface{}{
    "subject": "订单通知",
    "body": "您的订单已发货",
})
```

### 任务调度

```go
scheduler := scheduler.NewScheduler(50)
scheduler.Start()

task, err := scheduler.CreateTask("daily_report", scheduler.TaskTypeCron, handler, payload,
    scheduler.WithCronExpression("0 0 1 * * *"),
    scheduler.WithMaxRetries(3),
)
```

## 配置说明

配置文件位于 `configs/config.yaml`，支持以下配置项：

- `server` - 服务器配置
- `engine` - 核心引擎配置
- `logger` - 日志配置
- `cache` - 缓存配置
- `storage` - 存储配置
- `notification` - 通知配置
- `scheduler` - 调度配置
- `tsdb` - 时序数据库配置
- `lifecycle` - 生命周期管理配置

## 设计特点

1. **高并发**: 使用goroutine和channel实现高并发处理
2. **线程安全**: 所有共享资源使用sync.RWMutex保护
3. **优雅关闭**: 支持SIGINT/SIGTERM信号的优雅关闭
4. **可扩展**: 模块化设计，易于扩展新功能
5. **可观测**: 内置日志、指标、链路追踪
6. **自动恢复**: 关键模块内置重试和恢复机制

## License

MIT
