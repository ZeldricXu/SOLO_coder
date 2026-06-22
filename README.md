# FeatureFlag Platform - 功能开关管理平台

企业级功能开关管理平台，支持灰度发布、白名单控制、审批流程、自动回滚等功能。

## 项目背景

在微服务架构中，功能开关（Feature Flag）是实现灰度发布、金丝雀测试、风险控制的核心工具。本平台解决了以下痛点：

- **开关分散**：各服务各自维护开关配置，无法统一管理
- **手动操作**：依赖运维手动修改Apollo配置，效率低且易出错
- **缺乏审计**：开关变更无审批流程，风险不可控
- **无灰度能力**：简单的布尔开关无法满足复杂的灰度策略
- **无法监控**：开关开启后缺乏监控联动，出现问题无法及时回滚

## 核心功能

### 1. 开关管理
- **开关类型**：
  - 布尔开关（Boolean）：简单的开/关控制
  - 百分比灰度（Percentage）：按用户ID哈希分流，保证同一用户始终在同一组
  - 白名单开关（Whitelist）：按用户ID/部门/标签精确控制

- **作用域**：
  - 全局（GLOBAL）：所有环境、所有租户生效
  - 按环境（ENVIRONMENT）：指定环境生效
  - 按租户（TENANT）：指定环境+租户生效

- **完整的变更历史记录**：所有操作均有审计日志

### 2. 灰度策略
- **策略组合**：支持多策略叠加，如"10%的流量中仅限白名单用户"
- **策略运算符**：AND（且）/ OR（或）
- **白名单条件**：
  - 按用户ID筛选
  - 按部门筛选
  - 按标签筛选
  - 支持IN/NOT_IN/CONTAINS/NOT_CONTAINS操作符

### 3. Go SDK
- **本地缓存**：启动时拉取全量配置到本地，支持内存/Redis两种缓存后端
- **长轮询更新**：定期长轮询更新配置，实时性有保障
- **断路器**：平台不可用时自动熔断，使用本地缓存快照不影响业务
- **Fallback机制**：
  1. 本地缓存
  2. 平台查询
  3. 本地快照文件
- **自定义开关源**：支持从K8s ConfigMap、Apollo等读取配置
- **统计上报**：自动上报开关使用情况、延迟、错误率

### 4. 管理后台
- **开关搜索筛选**：按服务/环境/状态/负责人/类型/作用域过滤
- **批量操作**：
  - 一键全量开/关某服务的所有开关
  - 批量开/关选中的开关
- **审批流程**：重要开关变更需leader审批后才生效
- **定时开关**：设置开关在指定时间自动开启/关闭

### 5. 监控联动
- **Kafka事件推送**：开关变更事件实时推送Kafka
- **自动回滚**：新开关打开后15分钟内错误率突增则自动回滚关闭
- **使用统计**：
  - 每个开关的评估次数
  - 各分支流量占比
  - 平均延迟、P99延迟
  - 哪些服务集成了SDK

## 技术栈

### 后端
- **语言**：Go 1.21
- **框架**：Gin v1.9
- **数据库**：PostgreSQL 14+
- **缓存**：Redis 7+（可选）
- **消息队列**：Kafka 3.6
- **ORM**：pgx v5（原生SQL，无ORM）
- **配置**：Viper

### 前端
- **语言**：TypeScript 5
- **框架**：React 18
- **UI库**：Ant Design 5
- **构建工具**：Vite 5
- **图表**：ECharts 5
- **路由**：React Router v6
- **状态管理**：Zustand

## 项目结构

```
DF1-107/
├── backend/                    # 后端Go服务
│   ├── cmd/
│   │   └── server/            # 主程序入口
│   ├── internal/
│   │   ├── api/               # API层（Handler）
│   │   ├── config/            # 配置
│   │   ├── dao/               # 数据访问层
│   │   ├── engine/            # 策略引擎
│   │   ├── middleware/        # 中间件
│   │   ├── model/             # 数据模型
│   │   └── service/           # 业务逻辑层
│   ├── pkg/
│   │   ├── logger/            # 日志
│   │   └── utils/             # 工具函数
│   ├── config.yaml            # 配置文件
│   └── go.mod
├── frontend/                   # 前端React应用
│   ├── src/
│   │   ├── api/               # API客户端
│   │   ├── components/        # 公共组件
│   │   ├── pages/             # 页面
│   │   ├── router/            # 路由
│   │   ├── types/             # TypeScript类型定义
│   │   └── utils/             # 工具函数
│   ├── index.html
│   ├── package.json
│   ├── tsconfig.json
│   └── vite.config.ts
├── sdk/
│   └── go/                    # Go SDK
│       ├── breaker/           # 断路器
│       ├── cache/             # 缓存后端
│       ├── source/            # 开关源
│       ├── examples/          # 使用示例
│       ├── client.go          # SDK客户端
│       ├── types.go           # 类型定义
│       └── go.mod
├── deploy/
│   ├── docker/                # Docker配置
│   └── k8s/                   # K8s配置
├── scripts/
│   └── init.sql               # 数据库初始化脚本
├── docker-compose.yml          # 本地开发环境
└── README.md
```

## 快速开始

### 环境要求
- Docker & Docker Compose
- Go 1.21+
- Node.js 18+

### 一键启动（推荐）

```bash
cd DF1-107
docker-compose up -d
```

访问：
- 管理后台：http://localhost:3000
- API文档：http://localhost:8080/health
- Kafka UI：http://localhost:8081

### 本地开发

#### 1. 启动基础设施

```bash
docker-compose up -d postgres redis zookeeper kafka
```

#### 2. 初始化数据库

```bash
psql -h localhost -U postgres -d featureflag -f scripts/init.sql
```

#### 3. 启动后端

```bash
cd backend
go mod download
go run ./cmd/server
```

#### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

## Go SDK 使用指南

### 安装

```bash
go get github.com/featureflag/sdk
```

### 基本使用

```go
package main

import (
    "fmt"
    "time"

    "github.com/featureflag/sdk"
)

func main() {
    opts := &featureflag.SDKOptions{
        ServerURL:    "http://localhost:8080",
        PollInterval: 30 * time.Second,
        ServiceName:  "order-service",
    }

    client, err := featureflag.NewClient(opts)
    if err != nil {
        panic(err)
    }
    defer client.Close()

    ctx := featureflag.NewContextBuilder().
        WithUserID("user-12345").
        WithDepartment("engineering").
        WithTags("vip", "beta-tester").
        WithEnvironment("production").
        WithTenantID("tenant-001").
        Build()

    if client.IsEnabled("new_checkout_flow", ctx) {
        fmt.Println("使用新结算流程")
    } else {
        fmt.Println("使用旧结算流程")
    }

    buttonColor := client.GetString("button_color", ctx, "blue")
    fmt.Printf("按钮颜色: %s\n", buttonColor)
}
```

### 高级配置

#### 使用Redis缓存

```go
import "github.com/featureflag/sdk/cache"

redisCache := cache.NewRedisCache(&cache.RedisCacheOptions{
    Addr: "localhost:6379",
    TTL:  5 * time.Minute,
})

client, _ := featureflag.NewClient(opts)
client.WithCustomCache(redisCache)
```

#### 使用K8s ConfigMap作为开关源

```go
import "github.com/featureflag/sdk/source"

cmSource := source.NewConfigMapSource(&source.ConfigMapSourceOptions{
    Namespace: "default",
    ConfigMap: "featureflag-config",
    DataKey:   "switches.json",
})

client, _ := featureflag.NewClient(opts)
client.WithCustomSource(cmSource)
```

## API 文档

### 开关管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/switches` | 获取开关列表 |
| POST | `/api/v1/switches` | 创建开关 |
| GET | `/api/v1/switches/:id` | 获取开关详情 |
| PUT | `/api/v1/switches/:id` | 更新开关 |
| DELETE | `/api/v1/switches/:id` | 删除开关 |
| POST | `/api/v1/switches/:id/enable` | 开启开关 |
| POST | `/api/v1/switches/:id/disable` | 关闭开关 |
| POST | `/api/v1/switches/batch/enable` | 批量开启 |
| POST | `/api/v1/switches/batch/disable` | 批量关闭 |
| POST | `/api/v1/switches/batch/service/enable` | 按服务批量开启 |
| POST | `/api/v1/switches/batch/service/disable` | 按服务批量关闭 |

### 开关评估

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/v1/switches/evaluate` | 评估单个开关 |
| POST | `/api/v1/switches/evaluate/batch` | 批量评估开关 |

### SDK接口

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/sdk/config` | 获取全量配置 |
| POST | `/api/v1/sdk/evaluate` | SDK评估开关 |
| POST | `/api/v1/sdk/stats/report` | SDK上报统计 |

### 审批管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/approvals` | 获取审批列表 |
| POST | `/api/v1/approvals` | 提交审批申请 |
| POST | `/api/v1/approvals/:id/approve` | 审批通过 |
| POST | `/api/v1/approvals/:id/reject` | 审批拒绝 |

## 核心设计

### 百分比哈希算法

```go
func PercentageHash(userID string, salt string) int {
    h := sha256.New()
    h.Write([]byte(userID + "|" + salt))
    hashBytes := h.Sum(nil)
    
    hashInt := new(big.Int).SetBytes(hashBytes[:8])
    mod := new(big.Int).Mod(hashInt, big.NewInt(100))
    return int(mod.Int64())
}
```

保证同一用户始终在同一分组，避免用户体验不一致。

### 断路器状态机

```
          失败次数达到阈值
    Closed ──────────────────→ Open
      ↑                            │
      │ 半开状态下成功             │ 超时后
      │                            │
      └──────── HalfOpen ←─────────┘
          半开状态下失败
```

### 自动回滚机制

1. 开关开启后，监控系统开始收集错误率
2. 每60秒检查一次近15分钟的错误率
3. 如果错误率超过阈值（默认5%），自动关闭开关
4. 关闭事件推送Kafka，通知相关负责人

## 配置说明

### 后端配置 (config.yaml)

```yaml
server:
  port: 8080
  mode: release

database:
  host: localhost
  port: 5432
  user: postgres
  password: postgres
  dbname: featureflag

redis:
  host: localhost
  port: 6379

kafka:
  brokers:
    - localhost:9092
  topic: featureflag-events

auto_rollback:
  enabled: true
  check_interval: 60       # 检查间隔（秒）
  window_minutes: 15       # 统计窗口（分钟）
```

### 环境变量支持

所有配置都支持环境变量覆盖，前缀为 `FF_`：

```bash
export FF_DATABASE_HOST=postgres
export FF_DATABASE_PASSWORD=your_password
```

## 监控指标

平台暴露以下Prometheus指标（可扩展）：

| 指标名称 | 类型 | 说明 |
|----------|------|------|
| `ff_switch_evaluation_total` | Counter | 开关评估总次数 |
| `ff_switch_evaluation_true` | Counter | 开关返回true次数 |
| `ff_switch_evaluation_false` | Counter | 开关返回false次数 |
| `ff_switch_evaluation_error` | Counter | 开关评估错误次数 |
| `ff_switch_evaluation_duration` | Histogram | 开关评估延迟 |
| `ff_auto_rollback_total` | Counter | 自动回滚次数 |
| `ff_sdk_poll_total` | Counter | SDK拉取次数 |

## 最佳实践

### 1. 开关命名规范

```
{业务域}.{模块}.{功能}.{动作}

例如：
- checkout.payment.new_flow.enabled
- user.profile.avatar.v2.enabled
- marketing.coupon.black_friday.enabled
```

### 2. 开关生命周期管理

1. **创建**：开发人员在开发环境创建开关
2. **测试**：在测试环境验证功能
3. **审批**：提交审批申请，leader审批
4. **灰度**：先1%流量，逐步增加到10%、50%
5. **全量**：验证无误后全量开启
6. **清理**：功能稳定后，代码中移除开关判断，删除开关配置

### 3. 避免开关滥用

- 每个开关应有明确的过期时间
- 定期清理已全量的开关
- 避免嵌套开关判断（最多2层）
- 开关不应影响核心业务逻辑

## 生产环境部署建议

### 高可用配置

```yaml
# K8s HPA配置
minReplicas: 3
maxReplicas: 10
metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

### 数据库优化

- 连接池大小：100
- 读写分离：主库写入，从库查询
- 关键表索引：已在init.sql中创建

### 缓存策略

- 本地缓存TTL：5分钟
- Redis缓存TTL：10分钟
- SDK长轮询间隔：30秒

## License

MIT
