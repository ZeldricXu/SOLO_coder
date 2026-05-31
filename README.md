# TaskFlow - 企业级任务调度与执行管理平台

## 项目概述

TaskFlow 是一个面向企业级场景的开发效能工具，提供任务调度与执行管理的核心能力。采用模块化单体（Modular Monolith）设计范式，集成了任务调度、技能图谱、API网关、流程设计、用量计费等多个功能模块。

## 技术栈

- **语言**: Java 17
- **Web框架**: Spring Boot 3.2.x + Spring WebFlux
- **持久层**: MyBatis-Plus 3.5.x + Flyway 10.x
- **缓存**: Caffeine + Redis
- **监控**: Micrometer + Prometheus
- **认证**: JWT
- **数据库**: MySQL 8.0

## 模块结构

```
session169/
├── common/                  # 公共模块
│   ├── model/               # 通用模型
│   ├── exception/           # 异常定义
│   ├── utils/               # 工具类
│   └── config/              # 公共配置
├── multi-tenant/            # 多租户模块
│   ├── context/             # 租户上下文
│   ├── filter/              # 租户过滤器
│   ├── service/             # 租户服务
│   └── model/               # 租户模型
├── logging/                 # 日志模块
│   ├── aspect/              # 日志切面
│   ├── context/             # 日志上下文
│   ├── filter/              # 访问日志过滤器
│   └── model/               # 日志模型
├── data-access/             # 数据访问模块
│   ├── entity/              # 实体类
│   ├── mapper/              # MyBatis Mapper
│   ├── service/             # 数据服务
│   ├── config/              # 配置类
│   └── resources/db/migration/  # Flyway迁移脚本
├── core-processing/         # 核心处理模块
│   ├── handler/             # 任务处理器
│   ├── scheduler/           # 任务调度器
│   ├── service/             # 执行服务
│   ├── model/               # 任务模型
│   └── controller/          # REST API
├── skill-graph/             # 技能图谱模块
│   ├── model/               # 技能模型
│   ├── service/             # 技能服务
│   └── controller/          # REST API
├── api-gateway/             # API网关模块
│   ├── filter/              # 网关过滤器
│   ├── security/            # 认证鉴权
│   ├── ratelimit/           # 速率限制
│   └── config/              # 安全配置
├── notification/            # 通知模块
│   ├── service/             # 通知服务
│   ├── model/               # 通知模型
│   └── template/            # 模板引擎
├── billing/                 # 计费模块
│   ├── service/             # 计费服务
│   └── model/               # 计费模型
├── flow-designer/           # 流程设计模块
│   ├── model/               # 流程模型
│   ├── service/             # 流程服务
│   ├── validator/           # 流程校验器
│   └── controller/          # REST API
└── web/                     # Web启动模块
    ├── controller/          # Web控制器
    ├── config/              # Web配置
    └── resources/           # 配置文件
```

## 核心功能

### 1. 核心处理模块
- 任务调度与执行管理
- 定时触发执行（Cron表达式）
- 并发冲突乐观锁重试（最多3次）
- 任务参数校验与权限检查
- 数据库事务操作
- 结果校验与格式化
- 状态更新与事件通知

### 2. 技能图谱模块
- 技能树定义与管理
- 员工能力评估
- 学习路径推荐
- 团队技能矩阵分析

### 3. API网关模块
- JWT认证鉴权
- API速率限制
- CORS跨域支持
- 全局异常处理
- 访问日志记录

### 4. 数据访问模块
- MyBatis-Plus ORM框架
- Flyway数据库迁移版本控制
- 多租户数据隔离
- 自动填充审计字段

### 5. 可视化流程设计模块
- 拖拽式流程设计器
- 节点类型管理（开始、结束、任务、条件、并行等）
- 连线规则校验
- 流程连通性检查
- 循环检测
- 流程版本管理

### 6. 用量计量与计费模块
- 租户资源用量采集
- 按量计费计算
- 账单生成与管理
- 配额强制执行

### 7. 通知模块
- 多渠道消息推送（Email、SMS、钉钉、企业微信）
- FreeMarker模板渲染
- 异步通知发送

### 8. 日志模块
- 结构化日志输出
- MDC上下文追踪
- AOP操作日志切面
- WebFlux访问日志

### 9. 多租户模块
- 租户数据隔离
- 个性化配置管理
- 资源配额管理
- WebFilter租户上下文传递

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Redis 6.0+（可选）

### 数据库配置

1. 创建数据库：
```sql
CREATE DATABASE taskflow CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 修改 `web/src/main/resources/application.yml` 中的数据库连接信息。

### 构建项目

```bash
cd session169
mvn clean package -DskipTests
```

### 启动应用

```bash
cd web
mvn spring-boot:run
```

或者运行打包后的 Jar：

```bash
java -jar web/target/web-1.0.0.jar
```

### 访问应用

- 应用首页: http://localhost:8080/
- 健康检查: http://localhost:8080/health
- Swagger文档: http://localhost:8080/swagger-ui.html
- Actuator监控: http://localhost:8080/actuator
- Prometheus指标: http://localhost:8080/actuator/prometheus

### 默认登录

- 用户名: `admin`
- 密码: `admin123`

## API接口示例

### 任务执行

```bash
# 执行任务
curl -X POST http://localhost:8080/api/v1/tasks/execute \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: default" \
  -d '{
    "taskId": "task_001",
    "namespace": "development",
    "params": {"timeout": 30},
    "triggerType": "manual"
  }'

# 查询任务状态
curl http://localhost:8080/api/v1/tasks/{runId}/status \
  -H "X-Tenant-Id: default"
```

### 资源管理

```bash
# 创建资源
curl -X POST http://localhost:8080/api/v1/resources \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: default" \
  -d '{
    "type": "job",
    "name": "数据同步任务",
    "config": {"source": "mysql", "target": "hive"},
    "labels": {"env": "prod"}
  }'
```

### 技能图谱

```bash
# 获取技能树
curl http://localhost:8080/api/v1/skills/tree \
  -H "X-Tenant-Id: default"

# 获取员工技能档案
curl http://localhost:8080/api/v1/skills/profiles/employees/{employeeId} \
  -H "X-Tenant-Id: default"
```

### 流程设计

```bash
# 创建流程
curl -X POST http://localhost:8080/api/v1/flows \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: default" \
  -d '{
    "name": "数据处理流程",
    "description": "数据抽取、转换、加载流程",
    "nodes": [...],
    "edges": [...]
  }'

# 校验流程
curl -X POST http://localhost:8080/api/v1/flows/{flowId}/validate \
  -H "X-Tenant-Id: default"
```

## 核心处理流程

```
function executeHandler(request):
    ctx = initContext(request.traceId)
    try:
        validateParams(request.params)
        config = loadConfig(request.namespace)
        resource = acquireResource(config.poolSize)
        try:
            result = processCore(request.payload, config.rules)
            persistResult(result)
            emitEvent('task.completed', buildEvent(result))
            return successResponse(result)
        finally:
            releaseResource(resource)
    catch ValidationError as e:
        return errorResponse(422, e.details)
    catch TimeoutError:
        return errorResponse(504, '上游服务响应超时')
    catch Exception as e:
        rollbackTransaction(ctx)
        return errorResponse(500, '内部处理错误')
    finally:
        recordMetrics(ctx)
        ctx.cleanup()
```

## 异常处理策略

- **并发冲突**: 乐观锁重试最多3次后返回409冲突错误
- **参数校验失败**: 返回422参数错误
- **资源未找到**: 返回404
- **权限不足**: 返回403
- **未认证**: 返回401
- **限流触发**: 返回429
- **服务超时**: 返回504
- **系统错误**: 返回500

## 监控指标

应用暴露以下Prometheus指标：

- `task.execution.count`: 任务执行计数
- `task.execution.duration`: 任务执行时长
- `resource.usage`: 资源用量统计
- `http.server.requests`: HTTP请求统计
- `jvm.*`: JVM运行指标

## 风险预案

1. **单点故障**: 核心模块支持无状态水平扩展，配合健康检测自动摘除故障节点
2. **安全漏洞**: 集成依赖扫描工具到CI流程，阻断含高危漏洞的构建
3. **资源耗尽**: 引入信号量控制最大并发数，超额请求排队或快速拒绝

## License

MIT License
