# LLM Gateway - 大语言模型推理网关

## 项目概述

LLM Gateway是一个企业级的大语言模型推理网关平台，提供从特征存储、文档解析、模型管理到推理路由、安全评估的全链路解决方案。

## 模块架构

```
llm-gateway-parent/
├── llm-gateway-common/              # 公共模块 - 实体、DTO、工具类
├── llm-gateway-feature-store/       # 特征存储服务模块
├── llm-gateway-document-pipeline/   # 文档解析管道模块
├── llm-gateway-model-registry/      # 模型注册与版本模块
├── llm-gateway-inference-gateway/   # 推理路由网关模块
├── llm-gateway-adversarial/         # 对抗样本生成模块
├── llm-gateway-gpu-scheduler/       # GPU任务调度模块
├── llm-gateway-prompt-lab/          # Prompt实验管理模块
├── llm-gateway-evaluation/          # 模型评估看板模块
└── llm-gateway-boot/                # 主启动模块
```

## 技术栈

- **语言**: Java 17
- **Web框架**: Spring Boot 3.2 + Spring WebFlux
- **持久层**: MyBatis-Plus 3.5.5
- **数据库迁移**: Flyway 10.0
- **缓存**: Caffeine + Redis
- **监控**: Micrometer + Prometheus
- **构建工具**: Maven

## 功能模块

### 1. 特征存储服务模块
- 特征注册与管理
- 在线特征服务
- 离线回溯与回填
- 线上线下一致性保障

### 2. 文档解析管道模块
- 多格式文档解析 (PDF, Word, Markdown, TXT等)
- 智能切分与分块
- 向量化流水线
- 向量存储与检索

### 3. 模型注册与版本模块
- 模型元数据管理
- 版本生命周期管理
- Stage流转 (Development → Staging → Production → Archived)
- 模型端点管理

### 4. 推理路由网关模块
- 多模型Provider统一接入
- 负载均衡策略 (轮询、加权轮询、最少连接)
- 熔断器与降级策略
- 请求限流与配额管理

### 5. 对抗样本生成模块
- 多种攻击策略 (Prompt注入、越狱、对抗后缀等)
- 对抗Prompt生成
- 模型安全性评估
- 攻击效果分析

### 6. GPU任务调度模块
- GPU资源细粒度分配
- 任务优先级队列
- 抢占式调度策略
- 节点状态监控

### 7. Prompt实验管理模块
- Prompt版本控制
- AB实验配置
- 流量分配与变体路由
- 效果对比评估

### 8. 模型评估看板模块
- 离线评估指标对比
- 在线效果实时监控
- 数据漂移检测
- 概念漂移告警

## 快速开始

### 环境要求
- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.8+

### 数据库初始化

1. 创建数据库
```sql
CREATE DATABASE llm_gateway DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

2. 配置数据库连接
修改 `llm-gateway-boot/src/main/resources/application.yml` 中的数据库配置。

3. 启动应用
Flyway会自动执行数据库迁移脚本。

### 构建项目

```bash
cd session141
mvn clean install -DskipTests
```

### 启动应用

```bash
cd llm-gateway-boot
mvn spring-boot:run
```

应用将在 `http://localhost:8080` 启动。

## API接口概览

### 特征存储服务
```
POST   /api/v1/feature-store/features              # 注册特征
GET    /api/v1/feature-store/features/{id}         # 获取特征
POST   /api/v1/feature-store/values/ingest         # 写入特征值
GET    /api/v1/feature-store/values/latest         # 获取最新特征值
POST   /api/v1/feature-store/backfill-jobs         # 创建回填任务
```

### 文档解析管道
```
POST   /api/v1/documents                           # 上传文档
GET    /api/v1/documents/{id}                      # 获取文档
POST   /api/v1/documents/{id}/parse                # 解析文档
GET    /api/v1/documents/{id}/chunks               # 获取文档切片
```

### 模型注册与版本
```
POST   /api/v1/model-registry/models               # 注册模型
GET    /api/v1/model-registry/models/{id}          # 获取模型
POST   /api/v1/model-registry/versions             # 创建版本
POST   /api/v1/model-registry/versions/transition  # 阶段流转
```

### 推理路由网关
```
POST   /api/v1/inference/chat                      # 推理请求
GET    /api/v1/inference/requests/{id}             # 获取请求详情
```

### 对抗样本生成
```
GET    /api/v1/adversarial/attacks                 # 获取攻击策略列表
POST   /api/v1/adversarial/prompts/generate        # 生成对抗Prompt
POST   /api/v1/adversarial/evaluations             # 执行对抗评估
```

### GPU任务调度
```
POST   /api/v1/gpu/tasks                           # 提交GPU任务
GET    /api/v1/gpu/tasks/{id}                      # 获取任务状态
GET    /api/v1/gpu/nodes                           # 获取GPU节点列表
```

### Prompt实验管理
```
POST   /api/v1/prompt-lab/prompts                  # 创建Prompt模板
POST   /api/v1/prompt-lab/prompts/{id}/render      # 渲染Prompt
POST   /api/v1/prompt-lab/experiments              # 创建AB实验
GET    /api/v1/prompt-lab/experiments/{id}/assign  # 分配变体
```

### 模型评估看板
```
POST   /api/v1/evaluation/runs                     # 创建评估任务
GET    /api/v1/evaluation/runs/{id}                # 获取评估结果
POST   /api/v1/evaluation/runs/compare             # 对比评估结果
GET    /api/v1/evaluation/drift                    # 获取漂移检测结果
GET    /api/v1/evaluation/dashboard/summary        # 获取看板概览
```

## 监控指标

应用通过Actuator暴露Prometheus指标：
- `http://localhost:8080/actuator/prometheus`
- `http://localhost:8080/actuator/health`
- `http://localhost:8080/actuator/metrics`

## 设计原则

1. **显式优于隐式**: 所有配置和行为都应明确声明
2. **简单优于复杂**: 优先选择简单直接的实现方案
3. **容错设计**: 内置熔断、降级、重试机制
4. **可观测性**: 全链路追踪、指标采集、日志记录
5. **可扩展性**: 模块化设计，支持功能扩展

## 风险与缓解

### 性能退化
- 实现分批处理与并发执行
- 引入多级缓存策略
- 数据库读写分离

### 内存泄漏
- 建立内存使用监控
- 自动触发Heap Dump与告警
- 定期GC调优

### 依赖雪崩
- 设置合理的超时与线程池隔离
- 引入熔断器快速失败
- 多Provider降级策略

## License

MIT License
