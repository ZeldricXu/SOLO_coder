# 定时任务管理平台 - 功能增强迭代说明

## 迭代概述

本次迭代对三个核心模块进行了架构级别的功能增强，所有改动内聚在各模块内部，模块间接口保持稳定，完全向后兼容。

---

## 1. 调度模块 - 配置动态化

### 核心改动文件: [internal/scheduler/scheduler.go](internal/scheduler/scheduler.go)

### 新增特性:

#### 1.1 可插拔调度策略接口
```go
type SchedulingStrategy interface {
    GetName() string
    GetCronExpr(task *models.Task) string
    ShouldExecute(task *models.Task, now time.Time) bool
    GetTimeout(task *models.Task) time.Duration
    GetMaxRetries(task *models.Task) int
    GetRetryDelay(task *models.Task, attempt int) time.Duration
}
```

#### 1.2 预置四种内置策略

| 策略名称 | 适用场景 | 超时 | 重试次数 | 重试间隔 |
|---------|---------|------|---------|---------|
| `default` | 默认场景 | 5分钟 | 3次 | 指数退避 (2^n秒 |
| `batch_processing` | 批量处理任务 | 30分钟 | 1次 | 5分钟 |
| `realtime` | 实时任务 | 30秒 | 5次 | 线性增长 (500ms * n) |
| `idempotent` | 幂等任务 | 10分钟 | 0次 | 不重试 |

#### 1.3 动态配置管理

```go
type SchedulerConfig struct {
    DefaultStrategy     string            // 默认策略
    ConcurrencyLimit   int               // 并发限制
    QueueSize        int               // 队列大小
    DefaultTimeout   time.Duration     // 默认超时
    EnableMetrics    bool              // 指标开关
}
```

**配置热更新API:
- `UpdateConfig(config)` - 全量更新配置
- `UpdateConfigPartial(updates)` - 部分更新配置
- 配置变更事件订阅机制
- 配置版本号追踪

#### 1.4 运行时策略管理
- RegisterStrategy(name, strategy) - 动态注册新策略
- UnregisterStrategy(name) - 注销策略
- ListStrategies() - 列出所有可用策略
- GetStrategy(name) - 获取指定策略

#### 1.5 配置变更监听

```go
type ConfigChangeListener func(event ConfigUpdateEvent)
```

支持多个监听器订阅配置变更事件，用于扩展功能如:
- 配置审计日志
- 配置同步到其他节点
- 告警通知

---

## 2. SLO燃尽监控模块 - 策略可插拔

### 核心改动文件: [internal/slomonitor/slomonitor.go](internal/slomonitor/slomonitor.go)

### 新增特性:

#### 2.1 燃尽率计算策略接口

```go
type BurnRateCalculationStrategy interface {
    GetName() string
    Calculate(sli *models.SLI, window time.Duration, slo *models.SLO) float64
    Threshold() float64
}
```

#### 2.2 四种燃尽率策略

| 策略名称 | 算法 | 阈值 | 适用场景 |
|---------|------|------|---------|
| `default` | 标准计算 | 1.0 | 通用场景 |
| `conservative` | 标准值 × 1.2 | 0.8 | 稳定性要求高的服务 |
| `aggressive` | 标准值 × 0.8 | 1.5 | 容忍度高的服务 |
| `multi_window` | (1h速率 + 6h速率)/2 | 1.0 | 需要平滑波动 |

#### 2.3 告警策略接口

```go
type AlertingStrategy interface {
    GetName() string
    ShouldAlert(burnRate float64, slo *models.SLO) bool
    AlertSeverity(burnRate float64) string
}
```

#### 2.4 运行时策略切换
- SetBurnRateStrategy(name) - 切换燃尽率策略
- SetAlertingStrategy(name) - 切换告警策略
- 策略变更事件通道
- 策略变更监听器

#### 2.5 策略变更事件

```go
type StrategyChangeEvent struct {
    OldStrategy string
    NewStrategy string
    Timestamp   time.Time
}
```

---

## 3. 存储管理模块 - 异步化改造

### 核心改动文件: [internal/storage/storage.go](internal/storage/storage.go)

### 新增特性:

#### 3.1 异步操作架构

**工作池模式:
- 可配置工作线程数 (默认4个)
- 无界任务队列 (容量10000)
- 优雅关闭机制
- 任务排队监控

#### 3.2 异步操作类型

```go
type AsyncOperationType string

const (
    OpTypeStore      AsyncOperationType = "store"
    OpTypeDelete     AsyncOperationType = "delete"
    OpTypeTransition AsyncOperationType = "transition"
    OpTypeGC         AsyncOperationType = "gc"
)
```

#### 3.3 异步结果回调

```go
type AsyncCallback func(result AsyncResult)

type AsyncResult struct {
    OperationID string
    Type        AsyncOperationType
    FileID      string
    Success     bool
    Error       string
    Result      interface{}
    CompletedAt time.Time
}
```

#### 3.4 异步API

| 方法 | 说明 |
|------|------|
| `StoreFileAsync(...)` | 异步存储文件 |
| `DeleteFileAsync(...)` | 异步删除文件 |
| `CollectExpiredAsync(...)` | 异步GC过期文件 |
| `TransitionStorageClassesAsync(...)` | 异步迁移存储分级 |

#### 3.5 事件通知机制

```go
type StorageEvent struct {
    EventType string
    Timestamp time.Time
    FileID    string
    Data      interface{}
}
```

支持的事件类型:
- `file_storing` - 文件开始存储
- `file_stored` - 文件存储完成
- `file_accessed` - 文件被访问
- `file_deleting` - 文件开始删除
- `file_deleted` - 文件删除完成
- `files_expired` - 批量文件过期
- `storage_class_updated` - 存储分级更新

#### 3.6 回调注册机制

- 按文件ID注册回调
- 全局回调注册
- 事件监听器注册

---

## 4. API 扩展

### 调度模块配置API

```
GET    /api/v1/scheduler/config          获取当前配置
PUT    /api/v1/scheduler/config        全量更新配置
PATCH  /api/v1/scheduler/config       部分更新配置
```

### SLO策略管理API

```
GET    /api/v1/slo/strategies/burn-rate        列出燃尽率策略
PUT    /api/v1/slo/strategies/burn-rate/:name 切换燃尽率策略
GET    /api/v1/slo/strategies/alerting       列出告警策略
PUT    /api/v1/slo/strategies/alerting/:name 切换告警策略
```

### 存储异步API

所有写操作支持`async`参数:

```
POST   /api/v1/files?async=true
DELETE /api/v1/files/:id?async=true
POST   /api/v1/storage/gc?async=true
POST   /api/v1/storage/transition?async=true
```

---

## 5. 架构优势

1. **内聚性**: 所有改动封装在模块内部，外部接口保持不变

2. **向后兼容**: 原有同步API完全保留，新增的异步API是补充

3. **扩展性**: 策略接口、配置、事件机制都是可扩展的

4. **可观测性**:
   - 队列长度监控
   - 工作线程状态
   - 配置版本追踪
   - 事件审计日志

5. **容错性**:
   - 异步操作队列溢出保护
   - 优雅关闭机制
   - 配置校验机制

---

## 6. 使用示例

### 6.1 动态切换调度策略

```go
scheduler.UpdateConfigPartial(map[string]interface{}{
    "default_strategy": "realtime",
    "concurrency_limit": 20,
})
```

### 6.2 切换SLO策略

```go
sloMonitor.SetBurnRateStrategy("conservative")
```

### 6.3 异步存储文件

```go
fileID, _ := storageMgr.StoreFileAsync(ctx, name, contentType, content, ttl, class,
    func(result storage.AsyncResult) {
        if result.Success {
            fmt.Println("文件存储成功:", result.FileID)
        }
    })
```

### 6.4 监听存储事件

```go
storageMgr.AddEventListener(func(event storage.StorageEvent) {
    log.Printf("事件:%s 文件:%s", event.EventType, event.FileID)
})
```
