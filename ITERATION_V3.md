# 第三阶段迭代说明：功能增强

## 概述

本阶段在第二阶段重构的基础上，针对三个核心模块进行功能增强：

1. **数据分类分级模块**：配置动态化与热更新
2. **差分隐私注入模块**：策略可插拔与运行时切换
3. **监控统计模块**：异步化改造与事件通知

## 设计原则

- **内聚性**：改动局限在各模块内部，不影响模块间接口
- **向后兼容**：原有API保持稳定，新功能以增量方式提供
- **性能优化**：异步处理提升高并发场景下的系统吞吐量
- **可扩展性**：通过接口抽象支持未来的功能扩展

---

## 一、数据分类分级模块：配置动态化

### 核心改动

新增文件：
- `pkg/classification/config.go` - 动态配置管理器
- `pkg/classification/scenarios.go` - 预设场景配置

修改文件：
- `pkg/classification/classifier.go` - 集动态配置功能

### 设计要点

#### 1. 场景配置模型

```go
type ClassificationScenario struct {
    Name        string
    Description string
    Patterns    []ScenarioPattern  // 敏感模式定义
    Policies    []ScenarioPolicy   // 分级策略定义
    Rules       map[string]interface{}  // 自定义规则
    Enabled     bool
    CreatedAt   time.Time
    UpdatedAt   time.Time
}
```

#### 2. 可插拔配置源

```go
type ConfigSource interface {
    Load(ctx context.Context) (*ClassificationScenario, error)
    Watch(ctx context.Context, onChange func(*ClassificationScenario)) error
    Name() string
}
```

已实现的配置源：
- `MemoryConfigSource` - 内存配置源
- `FileConfigSource` - 文件配置源（支持热更新）

#### 3. 动态配置管理器

```go
type DynamicConfigManager struct {
    sources        map[string]ConfigSource
    activeScenario string
    scenarios      map[string]*ClassificationScenario
    listeners      []func(string, *ClassificationScenario)
    mu             sync.RWMutex
}
```

#### 4. 预设场景

内置5种行业场景配置：
- `default` - 通用场景（默认）
- `financial` - 金融行业（增强财务数据保护）
- `healthcare` - 医疗健康（强化患者隐私保护）
- `government` - 政府机关（严格等级保护）
- `marketing` - 市场营销（平衡隐私与可用性）

### 使用示例

```go
// 创建分类器
classifier := classification.NewDefaultClassifier()

// 列出可用场景
scenarios := classifier.ListScenarios()

// 切换场景
if err := classifier.SwitchScenario("healthcare"); err != nil {
    log.Fatal(err)
}

// 监听场景变化
classifier.OnScenarioChange(func(name string, scenario *ClassificationScenario) {
    log.Printf("Scenario changed to %s", name)
})

// 从文件加载场景（支持热更新）
if err := classifier.LoadScenarioFromFile("/path/to/config.yaml", "yaml", 5*time.Second); err != nil {
    log.Fatal(err)
}
```

### 热更新机制

`FileConfigSource` 通过定时轮询实现配置文件的热更新：
- 定期检查文件修改时间
- 检测到变化时自动重新加载
- 通知所有监听器
- 原子切换，保证一致性

---

## 二、差分隐私注入模块：策略可插拔

### 核心改动

新增文件：
- `pkg/differentialprivacy/privacy_strategy.go` - 策略接口与实现
- `pkg/differentialprivacy/strategy_manager.go` - 策略管理器

修改文件：
- `pkg/differentialprivacy/privacy.go` - 集策略管理器

### 设计要点

#### 1. 策略接口

```go
type PrivacyStrategy interface {
    Name() string
    Description() string
    Process(ctx context.Context, result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error)
    GetBudgetEstimate(query *interfaces.QueryResult) (epsilon, delta float64)
    ValidateConfig() error
}
```

#### 2. 内置策略实现

| 策略名称 | 描述 | 适用场景 |
|---------|------|---------|
| `strict` | 严格隐私策略：epsilon <= 1.0，Laplace噪声 | 高敏感数据、严格合规要求 |
| `balanced` | 平衡策略：epsilon <= 2.0，自适应选择噪声类型 | 大多数业务场景 |
| `relaxed` | 宽松策略：epsilon <= 10.0，Gaussian噪声 | 统计分析、数据挖掘 |
| `adaptive` | 自适应策略：根据数据集大小动态调整参数 | 数据量变化较大的场景 |

#### 3. 策略管理器

```go
type StrategyManager struct {
    strategies  map[string]PrivacyStrategy
    active      string
    listeners   []StrategyChangeListener
    logger      *zap.Logger
    mu          sync.RWMutex
}
```

### 使用示例

```go
// 创建隐私注入器
privacyConfig := &differentialprivacy.BudgetConfig{
    TotalEpsilon: 10.0,
    TotalDelta:   1e-5,
    ResetPeriod:  24 * time.Hour,
}
injector := differentialprivacy.NewDefaultPrivacyInjector(privacyConfig)

// 列出可用策略
strategies := injector.ListStrategies()

// 切换策略
if err := injector.SetPrivacyStrategy("strict"); err != nil {
    log.Fatal(err)
}

// 监听策略变化
injector.OnStrategyChange(func(oldStrategy, newStrategy string) {
    log.Printf("Strategy changed from %s to %s", oldStrategy, newStrategy)
})

// 注册自定义策略
customStrategy := NewCustomPrivacyStrategy()
if err := injector.RegisterStrategy(customStrategy); err != nil {
    log.Fatal(err)
}
```

### 可扩展性

通过实现 `PrivacyStrategy` 接口，可以轻松添加新的隐私保护策略：

```go
type CustomPrivacyStrategy struct{}

func (s *CustomPrivacyStrategy) Name() string {
    return "custom"
}

func (s *CustomPrivacyStrategy) Process(ctx context.Context, result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error) {
    // 自定义处理逻辑
    return result, nil
}

// 实现其他接口方法...
```

---

## 三、监控统计模块：异步化改造

### 核心改动

新增文件：
- `pkg/monitoring/async_task.go` - 异步任务队列与Worker池
- `pkg/monitoring/event_bus.go` - 事件总线

修改文件：
- `pkg/monitoring/monitor.go` - 集异步处理和事件通知

### 设计要点

#### 1. 异步任务队列

采用Worker Pool模式实现异步处理：

```go
type TaskQueue interface {
    Enqueue(task *AsyncTask)
    Start(ctx context.Context)
    Stop()
    Size() int
}
```

#### 2. 任务类型

```go
const (
    TaskTypeRecord    TaskType = iota  // 记录指标
    TaskTypeAggregate                   // 聚合计算
    TaskTypeFlush                       // 刷新导出
    TaskTypeExport                      // 导出数据
    TaskTypeSnapshot                    // 获取快照
)
```

#### 3. 事件总线

实现发布-订阅模式：

```go
type EventBus interface {
    Publish(event *MonitorEvent)
    Subscribe(eventType EventType, handler EventHandler)
    Unsubscribe(eventType EventType, handler EventHandler)
}
```

#### 4. 事件类型

```go
const (
    EventMetricRecorded    EventType = "metric.recorded"
    EventAggregateComplete EventType = "aggregate.completed"
    EventFlushComplete    EventType = "flush.completed"
    EventExportComplete   EventType = "export.completed"
)
```

### 使用示例

```go
// 创建支持异步的监控器
monitor := monitoring.NewDefaultMonitorWithAsync(
    10000,        // 最大指标点数
    5*time.Minute, // 自动刷新间隔
    3,            // Worker数量
    100,          // 队列大小
)

// 启动异步处理
ctx := context.Background()
monitor.StartAsyncProcessing(ctx)

// 订阅事件
monitor.Subscribe(monitoring.EventMetricRecorded, func(event *monitoring.MonitorEvent) {
    data := event.Data.(map[string]interface{})
    log.Printf("Metric recorded: %s = %f", data["metric"], data["value"])
})

// 异步记录指标
monitor.RecordMetricAsync(ctx, "request_latency", 123.45, dimensions, func(err error) {
    if err != nil {
        log.Printf("Record failed: %v", err)
    }
})

// 异步聚合计算
monitor.AggregateAsync(ctx, "request_latency", "avg", startTime, endTime, func(result float64, err error) {
    if err != nil {
        log.Printf("Aggregate failed: %v", err)
        return
    }
    log.Printf("Average latency: %f", result)
})

// 停止异步处理
monitor.StopAsyncProcessing()
```

### 性能优势

1. **非阻塞调用**：异步方法立即返回，不阻塞主流程
2. **削峰填谷**：任务队列缓冲突发流量，保护后端存储
3. **可控并发**：通过Worker数量限制并发处理能力
4. **事件驱动**：通过事件通知实现解耦

### 兼容性

- 原有同步API保持不变，可继续使用
- 异步API作为可选功能，按需启用
- 未启用异步时，异步API降级为同步执行

---

## 四、接口稳定性保证

### 模块间接口

所有模块间的公共接口（定义在 `pkg/common/interfaces/interfaces.go`）保持不变：

- `Classifier` 接口：`Classify()`, `Scan()`
- `PrivacyInjector` 接口：`InjectNoise()`, `ConsumeBudget()` 等
- `Monitor` 接口：`RecordMetric()`, `GetMetrics()`, `Aggregate()`, `Flush()`

### 向后兼容

1. **默认行为**：新增功能默认关闭，不影响原有代码行为
2. **增量API**：新功能通过新增方法提供，不修改原有方法签名
3. **降级机制**：异步API在未启用时自动降级为同步执行

---

## 五、典型使用场景

### 场景1：金融行业数据分类

```go
classifier := classification.NewDefaultClassifier()
classifier.SwitchScenario("financial")

// 分类结果将应用金融行业的敏感数据识别规则和保护策略
results, _ := classifier.Scan(ctx, financialData)
```

### 场景2：动态切换隐私策略

```go
injector := differentialprivacy.NewDefaultPrivacyInjector(config)

// 白天使用平衡策略，保证查询性能
injector.SetPrivacyStrategy("balanced")

// 夜间切换到严格策略，处理敏感报表
injector.SetPrivacyStrategy("strict")
```

### 场景3：高并发指标采集

```go
monitor := monitoring.NewDefaultMonitorWithAsync(100000, 1*time.Minute, 10, 1000)
monitor.StartAsyncProcessing(ctx)

// 在高并发场景下，异步采集不会阻塞请求处理
for _, req := range requests {
    monitor.RecordMetricAsync(ctx, "request_count", 1, req.Dimensions, nil)
}
```

---

## 六、文件清单

### 新增文件

| 文件路径 | 描述 |
|---------|------|
| `pkg/classification/config.go` | 动态配置管理器 |
| `pkg/classification/scenarios.go` | 预设场景配置 |
| `pkg/differentialprivacy/privacy_strategy.go` | 隐私策略接口与实现 |
| `pkg/differentialprivacy/strategy_manager.go` | 策略管理器 |
| `pkg/monitoring/async_task.go` | 异步任务队列与Worker池 |
| `pkg/monitoring/event_bus.go` | 事件总线 |

### 修改文件

| 文件路径 | 改动说明 |
|---------|---------|
| `pkg/classification/classifier.go` | 集动态配置，新增场景管理方法 |
| `pkg/differentialprivacy/privacy.go` | 集策略管理器，新增策略管理方法 |
| `pkg/monitoring/monitor.go` | 集异步处理和事件总线，新增异步API |
| `cmd/main.go` | 新增三个功能增强的演示代码 |

---

## 七、测试建议

### 单元测试

1. 测试场景切换的正确性
2. 测试各隐私策略的参数应用
3. 测试异步任务的执行和回调
4. 测试事件订阅和发布机制

### 集成测试

1. 测试配置文件热更新
2. 测试高并发下异步处理的稳定性
3. 测试策略切换时的查询正确性

### 性能测试

1. 对比同步和异步采集的吞吐量
2. 测试不同Worker数量下的性能表现
3. 测试队列满时的降级行为

---

## 八、后续优化方向

1. **配置中心集成**：支持从Apollo、Nacos等配置中心拉取配置
2. **策略编排**：支持组合多个策略形成策略链
3. **批量处理**：异步任务支持批量执行，进一步提升吞吐量
4. **监控指标**：为异步队列本身提供监控（队列长度、处理延迟等）
5. **持久化队列**：支持任务持久化，防止进程重启丢失数据
