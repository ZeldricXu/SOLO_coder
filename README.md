# 日志轮转与归档平台

生产级高可用的平台级产品，提供完整的日志管理、任务调度、API网关、文档处理、监控统计、Prompt实验管理、配置管理和GPU任务调度能力。

## 项目架构

采用六边形架构（Hexagonal Architecture）设计范式，包含以下核心模块：

### 1. 日志模块 (pkg/logger)
- 基于 Zap 实现结构化日志
- 支持日志轮转（按大小、时间）
- 自动归档压缩（gzip）
- 多输出目标（控制台、文件）

### 2. 配置管理模块 (pkg/config)
- 多源配置加载（文件、环境变量、远程配置中心）
- 动态配置更新与热重载
- 配置变更回调通知
- Viper 集成

### 3. 调度模块 (pkg/scheduler)
- 任务执行状态追踪
- Cron 定时任务支持
- 任务优先级队列
- 重试机制与指数退避
- 任务取消与超时控制

### 4. 监控统计模块 (pkg/monitoring)
- 业务指标采集（Counter、Gauge、Histogram、Timer）
- 指标聚合与统计（P50/P95/P99）
- 定期快照采集
- 维度标签支持

### 5. 核心处理模块 (pkg/core)
- 数据转换与标准化
- 可扩展的 Schema 定义
- 内置转换器（类型转换、字符串处理）
- 批量处理支持
- 处理事件总线

### 6. API网关模块 (pkg/gateway)
- 请求日志与链路追踪
- TraceID/SpanID 传递
- CORS 支持
- 认证中间件
- 限流中间件
- 统一响应格式

### 7. 文档解析管道模块 (pkg/document)
- 多格式文档解析（TXT、JSON、Markdown、XML、HTML）
- 智能语义切分
- 向量化支持
- 相似度搜索

### 8. Prompt实验管理模块 (pkg/prompt)
- Prompt 版本控制
- 模板变量渲染
- AB 实验配置与流量分配
- 效果对比评估
- 导入导出支持

### 9. GPU任务调度模块 (pkg/gpu)
- GPU 资源细粒度分配
- 任务优先级队列（基于 Heap）
- 抢占策略支持
- 资源利用率统计

## 快速开始

### 环境要求
- Go 1.21+
- PostgreSQL 13+ (可选)
- Redis 6+ (可选)

### 安装依赖

```bash
cd session129
go mod download
```

### 运行服务

```bash
go run cmd/server/main.go
```

### 配置文件

主要配置位于 `configs/config.yaml`，支持以下配置项：

```yaml
server:
  host: "0.0.0.0"
  port: 8080
  mode: "debug"

log:
  level: "info"
  path: "./logs/app.log"
  max_size: 100        # MB
  max_backups: 5
  max_age: 30          # days
  compress: true
```

## API 接口

### 资源管理
- `POST /api/v1/resources` - 创建资源
- `GET /api/v1/resources/{id}/status` - 查询资源状态
- `POST /api/v1/resources/batch` - 批量操作

### 监控
- `GET /api/v1/metrics` - 获取指标快照
- `GET /api/v1/tasks` - 列出所有任务
- `GET /api/v1/gpu/stats` - GPU 状态统计

### 文档处理
- `POST /api/v1/documents/process` - 处理文档

### Prompt 管理
- `POST /api/v1/prompt/render` - 渲染 Prompt

## 核心数据模型

### Entity (核心实体)
```json
{
  "id": "ent_001",
  "type": "resource",
  "status": "pending",
  "attributes": {"key": "value"},
  "created_at": "2026-05-11T08:00:00Z",
  "updated_at": "2026-05-11T09:00:00Z"
}
```

### Task (任务)
```json
{
  "id": "task_001",
  "name": "data_processing",
  "type": "data_process",
  "status": "running",
  "priority": 1,
  "parameters": {},
  "created_at": "2026-05-11T08:00:00Z"
}
```

## 模块使用示例

### 日志模块

```go
import "github.com/solocoder/logrotate/pkg/logger"

logCfg := logger.Config{
    LogPath:       "./logs/app.log",
    MaxSize:       100,
    MaxBackups:    5,
    MaxAge:        30,
    Compress:      true,
    Level:         "info",
    EnableConsole: true,
    EnableFile:    true,
}

log, _ := logger.New(logCfg)
log.Info("server started", logger.String("host", "0.0.0.0"))
```

### 调度模块

```go
import "github.com/solocoder/logrotate/pkg/scheduler"

s := scheduler.New(scheduler.WithMaxWorkers(20))

s.RegisterHandler("data_process", func(ctx context.Context, task *domain.Task) error {
    // 处理逻辑
    return nil
})

taskID, _ := s.Submit(&scheduler.Task{
    Name:       "process-data",
    Type:       "data_process",
    Parameters: map[string]interface{}{"key": "value"},
    MaxRetries: 3,
})
```

### GPU 调度模块

```go
import "github.com/solocoder/logrotate/pkg/gpu"

s := gpu.NewScheduler(gpu.SchedulerConfig{
    EnablePreemption:    true,
    PreemptionThreshold: 0.85,
    MaxConcurrentTasks:  10,
})

// 添加 GPU 资源
s.AddGPU(&gpu.GPU{
    NodeID:      "node-01",
    DeviceIndex: 0,
    TotalMemory: 24 * 1024 * 1024 * 1024,
    Status:      "available",
})

// 提交任务
taskID, _ := s.SubmitTask(&gpu.Task{
    Name:           "ml-training",
    Priority:       gpu.PriorityHigh,
    RequiredMemory: 8 * 1024 * 1024 * 1024,
    RunFunc: func(ctx context.Context, resources []*gpu.GPU) error {
        // GPU 任务逻辑
        return nil
    },
})
```

### Prompt 实验管理

```go
import "github.com/solocoder/logrotate/pkg/prompt"

m := prompt.NewManager()

// 创建 Prompt
promptA, _ := m.CreatePrompt(
    "summarization-v1",
    "请总结以下内容：{{.content}}",
    "user@example.com",
    []prompt.VariableDef{
        {Name: "content", Type: prompt.TypeString, Required: true},
    },
    []string{"summarization", "v1"},
    "内容总结模板",
)

// 创建 AB 实验
exp, _ := m.CreateExperiment(
    "summarization-ab-test",
    "比较两个总结模板的效果",
    promptA.ID,
    promptB.ID,
    0.5,
    "user@example.com",
)

// 启动实验
m.StartExperiment(exp.ID)

// 获取变体
variant, prompt, _ := m.GetExperimentVariant(exp.ID)
```

## 设计特点

1. **高可用设计**：所有核心模块都支持优雅关闭、资源清理
2. **并发安全**：使用 RWMutex、atomic 等保证并发安全
3. **可扩展性**：基于接口设计，方便扩展新的实现
4. **监控友好**：内置指标采集，便于接入 Prometheus/Grafana
5. **生产就绪**：包含重试、超时、熔断、限流等生产级特性

## 风险预案

- **性能退化**：实现分批处理与并发执行，控制处理时间
- **内存泄漏**：建立内存使用监控，异常时触发告警
- **依赖雪崩**：设置合理超时与线程池隔离，引入熔断器

## License

MIT
