# Task Scheduler Platform

企业级任务调度与执行管理平台，基于Spring Boot 3.x构建的微服务架构系统。

## 功能模块

| 模块 | 描述 |
|------|------|
| **common** | 公共模块 - 通用实体、工具类、异常定义、事件机制 |
| **persistence** | 持久层模块 - MyBatis-Plus实体、Mapper、Flyway数据库迁移 |
| **config-manager** | 配置管理模块 - 多源配置加载与动态更新 |
| **core** | 核心处理模块 - 任务调度与执行管理核心逻辑 |
| **adversarial** | 对抗样本生成模块 - 多种攻击策略生成对抗Prompt |
| **prompt-experiment** | Prompt实验管理模块 - Prompt版本控制、AB实验 |
| **storage** | 存储管理模块 - 数据备份与恢复 |
| **document-pipeline** | 文档解析管道模块 - 多格式文档解析、智能切分与向量化 |
| **data-access** | 数据访问模块 - 数据迁移与Schema版本控制 |
| **gpu-scheduler** | GPU任务调度模块 - GPU资源细粒度分配与抢占策略 |
| **model-registry** | 模型注册与版本模块 - 模型元数据管理、版本生命周期 |
| **application** | 主应用模块 - Spring Boot启动类、REST API |

## 技术栈

- **语言**: Java 17
- **框架**: Spring Boot 3.2.x, Spring WebFlux
- **ORM**: MyBatis-Plus 3.5.x
- **数据库迁移**: Flyway 9.x
- **缓存**: Caffeine (L1), Redis (L2)
- **监控**: Micrometer + Prometheus + Spring Actuator
- **数据库**: MySQL 8.0+

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+

### 数据库配置

创建数据库：

```sql
CREATE DATABASE task_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

修改 `application.yml` 中的数据库连接信息。

### 构建项目

```bash
mvn clean package -DskipTests
```

### 运行应用

```bash
java -jar application/target/application-1.0.0-SNAPSHOT.jar
```

或使用Docker：

```bash
docker build -t task-scheduler:latest .
docker run -p 8080:8080 task-scheduler:latest
```

## API接口

### 任务管理

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/v1/tasks` | 创建任务 |
| GET | `/api/v1/tasks/{taskId}` | 获取任务详情 |
| GET | `/api/v1/tasks` | 任务列表 |
| POST | `/api/v1/tasks/{taskId}/execute` | 执行任务 |
| GET | `/api/v1/tasks/{taskId}/status` | 查询任务状态 |
| POST | `/api/v1/tasks/batch` | 批量操作 |

### 配置管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/config/{namespace}/{key}` | 获取配置 |
| POST | `/api/v1/config/{namespace}/{key}` | 设置配置 |

### 模型注册

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/v1/models` | 创建模型 |
| GET | `/api/v1/models/{modelId}` | 获取模型 |
| GET | `/api/v1/models` | 模型列表 |
| POST | `/api/v1/models/{modelId}/versions` | 创建版本 |
| POST | `/api/v1/models/{modelId}/versions/{version}/promote` | 版本晋级 |

### GPU调度

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/v1/gpu/register` | 注册GPU |
| GET | `/api/v1/gpu` | GPU列表 |
| GET | `/api/v1/gpu/scheduler/status` | 调度器状态 |
| POST | `/api/v1/gpu/tasks/submit` | 提交GPU任务 |

### 其他接口

- **对抗样本**: `/api/v1/adversarial/**`
- **Prompt管理**: `/api/v1/prompts/**`
- **文档处理**: `/api/v1/documents/**`
- **数据迁移**: `/api/v1/data/**`
- **存储备份**: `/api/v1/storage/**`

## 监控端点

- 健康检查: `/actuator/health`
- 指标: `/actuator/metrics`
- Prometheus: `/actuator/prometheus`
- 环境信息: `/actuator/env`

## 核心架构特性

### 任务执行流程

1. 建立处理会话上下文 (ContextHolder)
2. 参数校验与配置加载
3. 资源获取 (Semaphore信号量控制并发)
4. 核心业务处理 (可扩展的Handler机制)
5. 结果持久化
6. 事件发布与指标采集
7. 资源释放

### 异常处理

- 参数校验失败: 422 Validation Error
- 资源不存在: 404 Not Found
- 业务异常: 400 Bad Request
- 超时: 504 Gateway Timeout
- 系统错误: 500 Internal Server Error

### 设计模式

- 策略模式: 任务处理、攻击策略、文档解析
- 观察者模式: 事件发布订阅
- 模板方法: 任务执行流程
- 责任链: 配置源优先级
- 享元: 资源池管理

## 项目结构

```
session186/
├── common/                 # 公共模块
│   └── src/main/java/com/taskplatform/common/
│       ├── dto/            # 数据传输对象
│       ├── entity/         # 基础实体
│       ├── enums/          # 枚举定义
│       ├── event/          # 事件机制
│       ├── exception/      # 异常定义
│       ├── response/       # 响应封装
│       └── util/           # 工具类
├── persistence/            # 持久层
│   ├── entity/             # 数据库实体
│   ├── mapper/             # MyBatis Mapper
│   └── resources/db/migration/  # Flyway脚本
├── config-manager/         # 配置管理
├── core/                   # 核心处理
├── adversarial/            # 对抗样本
├── prompt-experiment/      # Prompt实验
├── storage/                # 存储管理
├── document-pipeline/      # 文档管道
├── data-access/            # 数据访问
├── gpu-scheduler/          # GPU调度
├── model-registry/         # 模型注册
└── application/            # 主应用
    ├── controller/         # REST接口
    ├── config/             # 配置类
    ├── exception/          # 全局异常处理
    └── resources/          # 配置文件
```

## 开发规范

- 所有API返回统一格式: `ApiResponse<T>`
- 使用 `ContextHolder` 管理请求上下文
- 异常使用 `BusinessException` 及其子类
- 数据库操作使用MyBatis-Plus LambdaQueryWrapper
- 配置项通过 `ConfigService` 统一访问

## License

MIT License
