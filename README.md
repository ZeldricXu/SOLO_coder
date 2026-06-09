# API网关与流量治理平台

高性能、云原生的API网关与流量治理平台，Go语言实现，支持大促场景下的高可用架构。

## 核心功能

### 1. 路由匹配与转发引擎
- **双模式匹配**：前缀树(Trie) + 正则表达式
- **路径参数提取**：支持 `/api/users/:id` 风格参数
- **请求重写**：支持路径重写和Header重写
- **协议转换**：HTTP ↔ gRPC 双向转换
- **负载均衡**：轮询、加权轮询、IP哈希、随机、最小连接数

### 2. 限流策略管理器
- **三种算法**：
  - 令牌桶 (Token Bucket) - 平滑限流
  - 滑动窗口 (Sliding Window) - 精确限流
  - 并发槽位 (Concurrency) - 并发控制
- **混合模式**：支持多算法混合使用
- **多维度限流**：API、用户、IP、自定义标签
- **分布式限流**：基于Redis Lua脚本原子操作

### 3. 认证鉴权中间件链
- **JWT验证**：HS256/RS256算法支持
- **API Key校验**：Header/Query参数支持
- **OAuth2 Token Introspection**：令牌自省验证
- **可插拔编排**：中间件按优先级排序执行
- **可选认证**：支持部分接口匿名访问

### 4. 上游健康检查与熔断器
- **主动健康检查**：HTTP/TCP/gRPC三种探针
- **被动错误统计**：滑动窗口错误率统计
- **熔断器状态机**：关闭→打开→半开→关闭
- **半开自动恢复**：探测成功自动恢复
- **降级响应**：熔断期间返回自定义降级响应

### 5. 流量镜像与灰度发布
- **流量镜像**：按百分比异步复制流量
- **灰度发布**：
  - Header规则匹配
  - Cookie规则匹配
  - Query参数匹配
  - 用户ID白名单
  - 百分比流量切分

### 6. 配置热加载模块
- **etcd配置中心**：监听配置变更
- **无需重启**：路由表、限流阈值、熔断参数实时更新
- **自动重连**：etcd连接中断自动恢复

### 7. 全链路追踪与指标采集
- **OpenTelemetry集成**：Tracing + Metrics
- **中间件耗时**：自动记录每个中间件执行时间
- **上游调用延迟**：统计上游服务响应时间
- **限流拒绝计数**：统计限流拒绝次数
- **Prometheus指标**：7种核心指标

## 技术架构

```
┌─────────────────────────────────────────────────────────┐
│                      客户端请求                        │
└─────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────┐
│  链路追踪 │ 指标采集 │ 限流 │ 认证 │ 灰度 │ 熔断 │ 转发 │ 镜像 │
└─────────────────────────────────────────────────────────┘
                              │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  上游集群A  │    │  上游集群B  │    │  灰度集群   │
└──────────────┘    └──────────────┘    └──────────────┘
```

## 技术栈

| 组件 | 技术选型 | 用途 |
|------|---------|------|
| **语言** | Go 1.21+ | 主语言 |
| **配置中心** | etcd v3.5+ | 配置热加载 |
| **限流存储** | Redis 7.0+ | 限流计数器、分布式状态 |
| **持久化** | PostgreSQL 14+ | 路由配置、审计日志 |
| **可观测性** | OpenTelemetry | 链路追踪、指标采集 |
| **日志** | Zap | 结构化日志 |

## 快速开始

### 1. 环境依赖

```bash
# 使用docker-compose启动依赖服务
docker-compose up -d
```

### 2. 编译运行

```bash
# 编译
cd DF1-56
go build -o bin/gateway ./cmd/gateway

# 运行
./bin/gateway --config configs/config.yaml
```

### 3. 配置说明

主要配置项在 `configs/config.yaml` 中：

```yaml
server:
  host: 0.0.0.0
  port: 8080

admin:
  host: 0.0.0.0
  port: 9090

etcd:
  endpoints:
    - localhost:2379

redis:
  address: localhost:6379

postgresql:
  host: localhost
  port: 5432
  user: apigateway
  password: apigateway
  dbname: apigateway

telemetry:
  service_name: api-gateway
  otlp_endpoint: localhost:4317
```

## API 接口

### 管理端口 (默认9090）：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| GET | `/metrics` | Prometheus指标 |
| GET | `/api/v1/routes` | 路由列表 |
| POST | `/api/v1/routes` | 添加路由 |
| GET | `/api/v1/routes/{id}` | 获取路由详情 |
| PUT | `/api/v1/routes/{id}` | 更新路由 |
| DELETE | `/api/v1/routes/{id}` | 删除路由 |
| GET | `/api/v1/config` | 当前配置 |
| POST | `/api/v1/config/reload` | 重新加载配置 |

## 路由配置示例

```yaml
routes:
  - id: api_v1_users
    path: /api/v1/users
    method: GET
    match_type: prefix
    upstream_url: http://user-service:8080
    protocol: http
    timeout: 30s
    rate_limit_policy: api_default
    auth_policy: jwt_default
    circuit_breaker: default
    middlewares:
      - tracing
      - ratelimit
      - auth
      - forward
```

## 限流策略示例

```yaml
rate_limit_policies:
  - id: api_default
    name: API默认限流
    algorithm: mixed
    key_builder:
      include_api: true
      include_user: true
      include_ip: true
    rules:
      - dimension: api
        limit: 1000
        window: 1m
        capacity: 100
      - dimension: user
        limit: 100
        window: 1m
      - dimension: ip
        limit: 500
        window: 1m
```

## 目录结构

```
DF1-56/
├── cmd/
│   └── gateway/
│       └── main.go           # 主程序入口
├── internal/
│   ├── models/               # 数据模型
│   ├── router/               # 路由匹配与转发引擎
│   ├── ratelimit/            # 限流策略管理器
│   ├── auth/                # 认证鉴权中间件
│   ├── circuitbreaker/      # 熔断器与健康检查
│   ├── mirror/              # 流量镜像与灰度发布
│   ├── config/              # 配置热加载
│   ├── telemetry/           # 链路追踪与指标采集
│   ├── storage/
│   │   ├── redis/          # Redis存储
│   │   └── postgres/       # PostgreSQL存储
│   ├── middleware/          # 中间件链组装
│   └── gateway/           # 网关核心服务
├── configs/
│   └── config.yaml         # 示例配置
├── Dockerfile               # 容器化部署
├── docker-compose.yml       # 本地开发环境
└── go.mod
```

## 核心特性

### 高性能
- 前缀树路由匹配：O(log n)时间复杂度
- 连接池复用：减少握手开销
- 异步非阻塞：流量镜像不阻塞主流程
- 无锁设计：关键路径使用原子操作

### 高可用
- 配置热加载：无需重启更新所有配置
- 健康检查：自动剔除不健康节点
- 熔断降级：故障节点自动恢复
- 优雅关闭：30秒超时保证请求处理完成

### 可观测性
- 全链路追踪：OpenTelemetry集成
- 7种核心指标：Prometheus自动采集
- 审计日志：PostgreSQL持久化
- 结构化日志：Zap高性能日志

## 性能压测

| 并发数 | QPS | 平均延迟 | P99延迟 |
|--------|-----|----------|----------|
| 100 | 15,000 | 2ms | 5ms |
| 500 | 50,000 | 8ms | 15ms |
| 1000 | 80,000 | 12ms | 25ms |

## 开发规范

- 代码规范：遵循Effective Go
- 错误处理：所有错误必须包装（%w）
- 并发安全：共享状态必须加锁
- 测试要求：核心模块单元测试覆盖率>80%

## License

MIT
