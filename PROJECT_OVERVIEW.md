# 定时任务管理系统 - 项目概览

## 项目概述

基于端口适配器架构的企业级分布式定时任务管理平台，采用 Spring Boot 3.x + WebFlux 响应式技术栈。

## 技术栈

- **语言**: Java 17
- **框架**: Spring Boot 3.2.5 + Spring WebFlux
- **持久层**: MyBatis-Plus 3.5.5 + Flyway 10.10.0
- **缓存**: Caffeine (L1) + Redis (L2) 多级缓存
- **调度**: Quartz Scheduler
- **监控**: Micrometer + Prometheus
- **API文档**: SpringDoc OpenAPI

## 模块结构

```
session125/
├── pom.xml                              # 父POM，多模块管理
├── scheduler-common/                    # 公共模块
│   ├── BaseEntity                       # 基础实体类（乐观锁、软删除）
│   ├── ApiResponse                      # 标准API响应
│   ├── BusinessException                # 业务异常
│   └── EventPublisher                   # 事件发布器
├── scheduler-persistence/               # 持久层模块
│   ├── entity/                          # 6个核心实体
│   ├── mapper/                          # 6个Mapper接口
│   ├── config/MyBatisPlusConfig         # MyBatis-Plus配置
│   └── resources/db/migration/          # Flyway迁移脚本
├── scheduler-data-access/               # 数据访问模块
│   ├── cache/CacheManager               # 多级缓存管理器
│   └── repository/                      # 5个Repository类
├── scheduler-core/                      # 核心处理模块
│   ├── TaskExecutorService              # 任务执行核心服务
│   └── WALService                       # 预写日志服务
├── scheduler-scheduler/                 # 调度模块
│   ├── ScheduleManagerService           # 任务生命周期管理
│   ├── TaskExecutionJob                 # Quartz作业实现
│   └── config/QuartzConfig              # Quartz配置
├── scheduler-anomaly-detection/         # 异常检测模块
│   ├── AnomalyDetector                  # 检测算法接口
│   └── algorithm/                       # 3种检测算法
├── scheduler-log-pipeline/              # 日志管道模块
│   ├── model/LogEntry                   # 日志条目模型
│   ├── processor/                       # 解析、过滤、路由处理器
│   └── LogPipelineService               # 管道服务
├── scheduler-notification/              # 通知模块
│   ├── channel/NotificationChannel      # 通知渠道接口
│   ├── channel/impl/                    # Email、Webhook、SMS渠道
│   └── NotificationService              # 通知服务（失败重试）
├── scheduler-tracing/                   # 分布式追踪模块
│   ├── sampling/SamplingStrategy        # 采样策略接口
│   ├── sampling/impl/                   # 4种采样器（概率、错误、延迟、尾部）
│   └── TracingCollectorService          # 追踪采集服务
├── scheduler-gateway/                   # API网关模块
│   ├── filter/                          # 请求、认证、限流过滤器
│   └── ProtocolConverter                # 协议转换
├── scheduler-topology/                  # 服务拓扑模块
│   └── TopologyBuilder                  # 依赖图构建
├── scheduler-logging/                   # 日志模块
│   └── DynamicLoggingService            # 动态日志级别调整
└── scheduler-api/                       # API模块
    ├── controller/                      # 9个REST控制器
    ├── exception/GlobalExceptionHandler # 全局异常处理
    ├── TaskSchedulerApplication         # 启动类
    └── resources/                       # 配置文件
```

## 核心功能

### 1. 调度模块
- 支持 Cron、固定速率、固定延迟三种调度方式
- 任务生命周期管理：创建、更新、删除、暂停、恢复、手动触发
- Quartz Scheduler 集成，支持集群部署

### 2. 异常检测算法
- **阈值检测**: 简单上下限阈值比对
- **统计检测**: Z-Score 算法，基于均值和标准差
- **季节性检测**: 基于历史同期数据比对

### 3. 日志管道处理
- 日志收集、结构化解析
- 级别过滤、关键字过滤
- 多目的地路由（控制台、文件、远程服务）

### 4. 通知模块
- 多渠道支持：Email、Webhook、SMS
- 送达追踪与状态管理
- 失败自动重试（指数退避）

### 5. 分布式追踪
- OpenTelemetry 兼容的 Span 数据模型
- 多种采样策略：概率、错误捕获、延迟阈值、尾部采样
- Span 持久化与查询

### 6. 数据访问模块
- Caffeine L1 本地缓存 + Redis L2 分布式缓存
- Cache-Aside 模式，自动缓存失效
- 缓存统计与监控

### 7. API网关
- 请求路由与负载均衡
- 协议转换（REST ↔ gRPC）
- 认证鉴权（API Key + 签名）
- 限流熔断（令牌桶算法）

### 8. 核心处理模块
- 任务执行引擎，实现伪代码逻辑
- 资源池管理
- 预写日志(WAL)机制，确保数据不丢失
- 指标收集与事件发布

### 9. 服务依赖拓扑
- 基于 Trace 数据构建服务调用图
- JGraphT 图算法支持
- 依赖关系可视化数据输出

### 10. 日志模块
- 运行时动态调整日志级别
- 级别变更历史追踪
- 细粒度到包级别的控制

## API 契约

### 资源管理
```
POST   /api/v1/resources              # 创建资源
GET    /api/v1/resources/{id}/status  # 查询状态
POST   /api/v1/resources/batch        # 批量操作
```

### 任务管理
```
POST   /api/v1/tasks                  # 创建任务
GET    /api/v1/tasks                  # 任务列表
GET    /api/v1/tasks/{id}             # 任务详情
PUT    /api/v1/tasks/{id}             # 更新任务
DELETE /api/v1/tasks/{id}             # 删除任务
POST   /api/v1/tasks/{id}/pause       # 暂停任务
POST   /api/v1/tasks/{id}/resume      # 恢复任务
POST   /api/v1/tasks/{id}/trigger     # 手动触发
```

### 其他接口
- `/api/v1/executions` - 执行记录查询
- `/api/v1/notifications` - 通知管理
- `/api/v1/tracing` - 追踪数据查询
- `/api/v1/anomaly` - 异常检测
- `/api/v1/logs` - 日志管理
- `/api/v1/topology` - 服务拓扑

## 数据模型

### 核心实体
- **ScheduledTask**: 定时任务配置
- **TaskExecution**: 任务执行记录
- **ConfigDefinition**: 配置定义（版本化）
- **MetricsSnapshot**: 指标快照
- **Notification**: 通知记录
- **TraceSpan**: 追踪Span

## 风险缓解措施

1. **数据丢失**: 预写日志(WAL)机制，崩溃后可恢复
2. **并发冲突**: 乐观锁(@Version) + 重试机制
3. **配置漂移**: 配置版本化，定期Diff对比告警
4. **系统过载**: 限流熔断，资源池隔离

## 运行说明

### 前置依赖
- MySQL 8.0+
- Redis 6.0+
- Java 17+

### 启动步骤
1. 创建数据库：`CREATE DATABASE scheduler;`
2. 修改 `application.yml` 中的数据库和Redis配置
3. 构建项目：`mvn clean package -DskipTests`
4. 启动应用：`java -jar scheduler-api/target/scheduler-api-1.0.0.jar`

### 访问地址
- API服务: http://localhost:8080
- Swagger文档: http://localhost:8080/swagger-ui.html
- Prometheus指标: http://localhost:8080/actuator/prometheus
