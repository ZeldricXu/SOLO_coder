# SLO监控平台

SLI指标计算、错误预算消耗追踪与燃尽告警平台。

## 功能特性

### 核心模块

1. **SLO燃尽监控模块** - SLI指标计算、错误预算消耗追踪与燃尽告警
2. **数据访问模块** - 实现缓存策略与失效管理
3. **指标聚合与存储模块** - 时序指标接收、预聚合计算与存储引擎适配
4. **监控统计模块** - 实现性能数据暴露与查询
5. **告警规则评估引擎** - 告警规则解析、定时评估与通知触发
6. **异常检测算法模块** - 基于历史基线检测指标异常，支持多种检测算法
7. **核心处理模块** - 实现请求处理与响应生成的核心逻辑
8. **配置管理模块** - 实现多源配置加载与动态更新
9. **分布式追踪采集模块** - 接收Trace Span数据，采样策略管理与尾部采样

## 技术栈

- **语言**: TypeScript
- **运行时**: Node.js
- **Web框架**: Express
- **缓存**: ioredis + node-cache
- **校验**: Zod
- **日志**: Winston
- **定时任务**: node-cron

## 快速开始

### 安装依赖

```bash
npm install
```

### 构建项目

```bash
npm run build
```

### 启动服务

```bash
npm start
```

### 开发模式

```bash
npm run dev
```

## API 接口

### 健康检查

```
GET /health
```

### 系统状态

```
GET /api/v1/status
```

### 资源管理

#### 创建资源

```
POST /api/v1/resources
Content-Type: application/json

{
  "type": "workflow",
  "config": {
    "timeout": 30,
    "retries": 3
  },
  "labels": {
    "env": "production"
  }
}
```

#### 查询资源状态

```
GET /api/v1/resources/{id}/status
```

#### 批量操作

```
POST /api/v1/resources/batch
Content-Type: application/json

{
  "operations": [
    {
      "action": "start",
      "id": "rsc_001"
    }
  ]
}
```

### SLO/SLI 管理

#### 创建SLO

```
POST /api/v1/slo
Content-Type: application/json

{
  "name": "API可用性",
  "description": "核心API服务可用性SLO",
  "sli_ids": ["sli_001"],
  "target": 0.999,
  "time_window_days": 30,
  "alerting_thresholds": {
    "burn_rate_severe": 2.0,
    "burn_rate_warning": 1.5,
    "error_budget_remaining": 0.1
  }
}
```

#### 创建SLI

```
POST /api/v1/sli
Content-Type: application/json

{
  "name": "API请求成功率",
  "slo_target": 0.999,
  "time_window": "30d",
  "sli_type": "availability",
  "parameters": {
    "metric_name": "api_request_success_rate"
  }
}
```

#### 查询错误预算

```
GET /api/v1/slo/{id}/error-budget
```

#### 查询所有错误预算

```
GET /api/v1/slo/error-budgets
```

#### 记录SLI数据

```
POST /api/v1/sli/{id}/record
Content-Type: application/json

{
  "good_events": 999,
  "total_events": 1000,
  "dimensions": {
    "service": "api-gateway"
  }
}
```

### 告警管理

#### 创建告警规则

```
POST /api/v1/alerts/rules
Content-Type: application/json

{
  "rule_id": "rule_001",
  "name": "高错误率告警",
  "enabled": true,
  "condition": {
    "type": "threshold",
    "metric": "error_rate",
    "threshold": 0.05,
    "operator": "gt",
    "duration": "5m"
  },
  "notification_channels": ["console"],
  "evaluation_interval": "1m"
}
```

#### 查询告警规则

```
GET /api/v1/alerts/rules
```

#### 查询活跃告警

```
GET /api/v1/alerts/active
```

### 分布式追踪

#### 上报Span

```
POST /api/v1/traces/spans
Content-Type: application/json

{
  "trace_id": "trace_001",
  "span_id": "span_001",
  "name": "HTTP GET /api/users",
  "service_name": "api-gateway",
  "start_time": 1716000000000,
  "end_time": 1716000000250,
  "duration_ms": 250,
  "status": "ok"
}
```

#### 查询Trace

```
GET /api/v1/traces/{traceId}
```

### 异常检测

#### 检测异常

```
POST /api/v1/anomaly/detect
Content-Type: application/json

{
  "metric_name": "request_latency",
  "values": [100, 120, 110, 500, 105],
  "config": {
    "algorithm": "z_score",
    "lookback_period": "1h",
    "sensitivity": 3.0
  }
}
```

#### 构建基线

```
POST /api/v1/anomaly/baseline/{metricName}
```

#### 查询可用算法

```
GET /api/v1/anomaly/algorithms
```

### 指标管理

#### 记录指标

```
POST /api/v1/metrics/record
Content-Type: application/json

{
  "metric_name": "request_latency",
  "value": 150,
  "dimensions": {
    "service": "api-gateway",
    "endpoint": "/api/users"
  }
}
```

#### 查询指标

```
GET /api/v1/metrics/query?metric_name=request_latency&start_time=1716000000000&aggregation=avg&granularity=1m
```

### 配置管理

#### 创建配置

```
POST /api/v1/configs
Content-Type: application/json

{
  "config_id": "cfg_001",
  "namespace": "production",
  "version": 1,
  "parameters": {
    "timeout": 30,
    "retries": 3
  },
  "enabled": true,
  "applied_at": "2026-05-11T08:30:00Z"
}
```

#### 查询命名空间配置

```
GET /api/v1/configs/{namespace}
```

#### 对比配置差异

```
GET /api/v1/configs/diff/{ns1}/{ns2}
```

## 核心使用示例

### 基本使用

```typescript
import sloManager from './src/slo';
import alertEngine from './src/alerting';
import metricsService from './src/metrics';

// 创建SLI
const sli = await sloManager.createSLI({
  name: 'API可用性',
  slo_target: 0.999,
  time_window: '30d',
  sli_type: 'availability',
  parameters: { metric_name: 'api_success_rate' },
});

// 创建SLO
const slo = await sloManager.createSLO({
  name: '核心API SLO',
  sli_ids: [sli.sli_id],
  target: 0.999,
  time_window_days: 30,
  alerting_thresholds: {
    burn_rate_severe: 2.0,
    burn_rate_warning: 1.5,
    error_budget_remaining: 0.1,
  },
});

// 记录成功事件
sloManager.recordSuccess(sli.sli_id, { service: 'api' });

// 记录失败事件
sloManager.recordFailure(sli.sli_id, { service: 'api' });

// 获取错误预算状态
const budget = await sloManager.getErrorBudget(slo.slo_id);
console.log(`剩余错误预算: ${budget?.remaining_budget * 100}%`);
```

### 缓存使用

```typescript
import cacheManager from './src/data-access';

// 创建内存缓存
const cache = cacheManager.createInMemoryCache<string>('my_cache', {
  default_ttl: 300000, // 5分钟
  max_size: 10000,
  eviction_policy: 'lru',
});

// 设置缓存
await cache.set('key1', 'value1', 60000); // 1分钟过期

// 获取缓存
const value = await cache.get('key1');

// 删除缓存
await cache.delete('key1');
```

### 异常检测

```typescript
import anomalyDetector from './src/anomaly-detection';

// 添加数据点
for (let i = 0; i < 100; i++) {
  anomalyDetector.addDataPoint('latency', Math.random() * 100 + 100);
}

// 构建基线
const baseline = anomalyDetector.buildBaseline('latency');

// 检测异常
const result = anomalyDetector.detect('latency', {
  algorithm: 'z_score',
  lookback_period: '1h',
  sensitivity: 3.0,
});

if (result?.is_anomaly) {
  console.log(`检测到异常! 分数: ${result.score}`);
}
```

## 项目结构

```
session168/
├── src/
│   ├── alerting/           # 告警规则评估引擎
│   │   └── index.ts
│   ├── anomaly-detection/  # 异常检测算法模块
│   │   └── index.ts
│   ├── config/             # 配置管理模块
│   │   └── index.ts
│   ├── core/               # 核心处理模块
│   │   └── index.ts
│   ├── data-access/        # 数据访问模块（缓存）
│   │   └── index.ts
│   ├── metrics/            # 指标聚合与存储模块
│   │   └── index.ts
│   ├── monitoring/         # 监控统计模块
│   │   └── index.ts
│   ├── slo/                # SLO燃尽监控模块
│   │   ├── index.ts        # 主模块（包含RoutedSLOManager）
│   │   └── read-write-router.ts  # 读写分离路由实现
│   ├── tracing/            # 分布式追踪采集模块
│   │   └── index.ts
│   ├── types/              # 类型定义
│   │   └── index.ts
│   ├── utils/              # 工具函数
│   │   ├── index.ts
│   │   └── logger.ts
│   └── index.ts            # API入口
├── package.json
├── tsconfig.json
└── README.md
```

## 配置说明

### 环境变量

- `PORT`: 服务端口（默认: 3000）
- `LOG_LEVEL`: 日志级别（默认: info）
- `NODE_ENV`: 运行环境（development/production）

### 缓存配置

支持的缓存策略：
- LRU (最近最少使用)
- LFU (最不经常使用)
- FIFO (先进先出)

### 异常检测算法

1. **static_threshold** - 静态阈值检测
2. **moving_average** - 移动平均检测
3. **exponential_smoothing** - 指数平滑检测
4. **z_score** - Z分数检测
5. **isolation_forest** - 孤立森林检测

## 风险缓解策略

### 数据丢失保护
- 采用预写日志(WAL)机制思想，关键数据先写入内存再异步持久化
- 多级缓存架构降低单点故障风险

### 并发冲突处理
- 乐观锁配合重试机制
- 冲突率高于阈值时自动降级

### 配置漂移管理
- 配置版本化机制
- 定期Diff对比各环境配置差异
- 配置变更自动告警

## 迭代功能 (v2.0)

### 1. SLO燃尽监控模块 - 读写分离路由

SLO模块现在支持读写分离路由，可配置主从架构、副本选择策略和故障转移。

#### 新增API接口

```
# 获取路由统计信息
GET /api/v1/slo/routing/stats

# 添加副本节点
POST /api/v1/slo/routing/replicas
Content-Type: application/json

{
  "id": "replica-1",
  "host": "192.168.1.100",
  "port": 5432,
  "priority": 1,
  "tags": { "zone": "us-east-1" }
}

# 移除副本节点
DELETE /api/v1/slo/routing/replicas/{id}

# 获取所有副本节点
GET /api/v1/slo/routing/replicas

# 触发故障转移
POST /api/v1/slo/routing/failover
```

#### 使用示例

```typescript
import { routedSLOManager } from './slo';

// 添加副本
routedSLOManager.addReplica({
  id: 'replica-1',
  host: '192.168.1.100',
  port: 5432,
  priority: 1,
  healthy: true,
  tags: {}
});

// 获取路由统计
const stats = routedSLOManager.getRouterStats();
console.log('读写操作统计:', stats);
```

### 2. 数据访问模块 - 事件驱动通知

数据访问模块现在支持事件驱动通知，通过事件总线(EventBus)实现缓存操作的异步通知。

#### 新增API接口

```
# 获取支持的事件类型
GET /api/v1/cache/events

# 订阅缓存事件（Webhook通知）
POST /api/v1/cache/events/subscribe
Content-Type: application/json

{
  "event": "cache.set",
  "webhook_url": "https://your-service.com/webhook/cache"
}

# 获取事件总线统计
GET /api/v1/cache/eventbus/stats
```

#### 支持的事件类型

- `cache.set` - 缓存写入事件
- `cache.get` - 缓存读取事件
- `cache.delete` - 缓存删除事件
- `cache.evict` - 缓存驱逐事件
- `cache.expire` - 缓存过期事件
- `cache.clear` - 缓存清空事件
- `cache.operation` - 所有缓存操作事件（包含执行时间）
- `cache.invalidation` - 缓存失效事件

#### 使用示例

```typescript
import cacheManager from './data-access';

// 订阅缓存事件
const unsubscribe = cacheManager.on('cache.set', (data) => {
  console.log('缓存已更新:', data.key, data.cacheName);
});

// 获取事件总线
const eventBus = cacheManager.getEventBus();

// 发布自定义事件
eventBus.emit('custom.event', { foo: 'bar' });

// 取消订阅
unsubscribe();
```

### 3. 指标聚合与存储模块 - 插件化扩展

指标模块现在支持插件化扩展，可动态加载自定义聚合函数和存储适配器。

#### 新增API接口

```
# 加载插件
POST /api/v1/metrics/plugins
Content-Type: application/json

{
  "name": "custom-aggregations",
  "version": "1.0.0",
  "description": "自定义聚合函数",
  "aggregations": {
    "geometric_mean": "function code..."
  }
}

# 获取所有已加载插件
GET /api/v1/metrics/plugins

# 获取插件详情
GET /api/v1/metrics/plugins/{pluginId}

# 卸载插件
DELETE /api/v1/metrics/plugins/{pluginId}

# 启用插件
POST /api/v1/metrics/plugins/{pluginId}/enable

# 禁用插件
POST /api/v1/metrics/plugins/{pluginId}/disable

# 获取可用聚合函数
GET /api/v1/metrics/aggregations
```

#### 内置插件

1. **StatisticalAggregationPlugin** - 统计聚合插件
   - `sum_of_squares` - 平方和
   - `variance` - 方差
   - `stddev` - 标准差
   - `median` - 中位数
   - `range` - 极差

2. **InMemoryStoragePlugin** - 内存存储插件

#### 自定义插件示例

```typescript
import { AggregationPlugin, metricsService } from './metrics';

const myPlugin: AggregationPlugin = {
  name: 'my-custom-aggregations',
  version: '1.0.0',
  description: '我的自定义聚合函数',
  aggregations: {
    geometric_mean: (values: number[]) => {
      if (values.length === 0) return 0;
      const product = values.reduce((a, b) => a * b, 1);
      return Math.pow(product, 1 / values.length);
    },
    harmonic_mean: (values: number[]) => {
      if (values.length === 0) return 0;
      const sumOfReciprocals = values.reduce((sum, v) => sum + 1 / v, 0);
      return values.length / sumOfReciprocals;
    }
  },
  init: () => {
    console.log('插件已初始化');
  },
  cleanup: () => {
    console.log('插件已清理');
  }
};

// 加载插件
const pluginId = await metricsService.loadPlugin(myPlugin);

// 使用自定义聚合
metricsService.recordMetric('response_time', 100);
const result = await metricsService.getAggregatedMetric(
  'response_time',
  startTime,
  endTime,
  'geometric_mean'  // 使用自定义聚合函数
);

// 卸载插件
await metricsService.unloadPlugin(pluginId);
```

## License

MIT
