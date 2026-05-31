# Enterprise Infrastructure Platform

企业级基础设施平台 - 实现缓存策略与失效管理的完整解决方案。

## 项目概述

本项目是一个面向企业级场景的基础设施组件平台，采用模块化单体（Modular Monolith）设计范式，提供9个核心功能模块，覆盖从数据访问到服务治理的全栈能力。

## 技术栈

- **语言**: Python 3.10+
- **Web框架**: FastAPI + Pydantic
- **缓存**: 内存缓存（LRU/LFU/TTL）
- **文档搜索**: Whoosh
- **配置管理**: 多源配置（环境变量/文件/HTTP/Redis）
- **CLI**: Click + Rich
- **模板引擎**: Jinja2
- **测试**: pytest + pytest-asyncio

## 功能模块

### 1. 数据访问模块 (data_access)
- LRU/LFU/TTL 多种缓存策略
- 缓存失效管理（按键、按标签、按模式）
- 工作单元模式（Unit of Work）
- 数据源管理与熔断器模式

### 2. API网关模块 (api_gateway)
- JWT 认证与授权
- 基于角色的访问控制（RBAC）
- 速率限制（固定窗口、令牌桶）
- 请求路由与转发

### 3. 内部文档索引模块 (document_index)
- 多源文档聚合（本地文件、Web）
- Whoosh 全文搜索引擎
- 文档权限过滤
- 增量同步与索引重建

### 4. 监控统计模块 (monitoring)
- 告警规则定义与评估
- 多通道通知（邮件、Slack、Webhook）
- 指标存储与聚合
- 告警事件历史记录

### 5. API契约测试模块 (contract_testing)
- OpenAPI/GraphQL Schema 校验
- Mock Server 自动生成
- 契约测试用例生成
- Schema 版本管理

### 6. 存储管理模块 (storage)
- 对象存储适配（本地文件、内存、S3）
- 元数据索引与搜索
- 预签名URL生成
- 文件版本管理

### 7. 配置管理模块 (config)
- 多源配置加载（环境、文件、HTTP、Redis）
- 动态配置更新与监听
- 配置版本化与快照
- 配置差异对比

### 8. 项目脚手架生成模块 (scaffold)
- 基于模板的项目生成
- 内置 FastAPI、CLI、Library 模板
- 交互式问答式配置
- Jinja2 模板引擎

### 9. 软件目录与发现模块 (service_discovery)
- 服务/库元数据注册
- 多维度服务检索
- 依赖关系图可视化
- 健康状态监控

## 快速开始

### 安装依赖

```bash
cd session146
pip install -r requirements.txt
```

### 启动服务

```bash
python main.py
```

服务将在 http://localhost:8000 启动

### 访问API文档

- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc
- OpenAPI JSON: http://localhost:8000/openapi.json

## CLI 使用

### 项目脚手架

```bash
# 列出可用模板
python -m src.scaffold.cli list

# 交互式创建项目
python -m src.scaffold.cli create

# 指定模板创建
python -m src.scaffold.cli create -t fastapi-basic -n my-project
```

## API 示例

### 服务发现

```bash
# 列出所有服务
curl http://localhost:8000/api/v1/discovery/services

# 搜索服务
curl "http://localhost:8000/api/v1/discovery/search?q=user"

# 获取依赖图
curl http://localhost:8000/api/v1/discovery/graph
```

### 缓存管理

```bash
# 设置缓存
curl -X POST "http://localhost:8000/api/v1/data-access/caches/default/entry?key=foo" \
  -H "Content-Type: application/json" \
  -d '{"value": "bar", "ttl": 3600}'

# 查看缓存
curl http://localhost:8000/api/v1/data-access/caches/default
```

## 运行测试

```bash
pytest tests/ -v
```

## 项目结构

```
session146/
├── main.py                    # 主入口文件
├── pyproject.toml             # 项目配置
├── requirements.txt           # 依赖列表
├── Makefile                   # 构建工具
├── .env.example               # 环境变量示例
├── src/
│   ├── common/                # 公共模块
│   │   ├── models.py          # 基础数据模型
│   │   ├── exceptions.py      # 异常类
│   │   ├── utils.py           # 工具函数
│   │   ├── logging_config.py  # 日志配置
│   │   └── database.py        # 数据库管理
│   ├── data_access/           # 数据访问模块
│   ├── api_gateway/           # API网关模块
│   ├── document_index/        # 文档索引模块
│   ├── monitoring/            # 监控统计模块
│   ├── contract_testing/      # 契约测试模块
│   ├── storage/               # 存储管理模块
│   ├── config/                # 配置管理模块
│   ├── scaffold/              # 脚手架生成模块
│   └── service_discovery/     # 服务发现模块
└── tests/                     # 测试文件
```

## 核心设计模式

1. **依赖注入**: FastAPI 的 Depends 机制实现
2. **工厂模式**: StorageProviderFactory, ConfigSource 等
3. **单例模式**: 各模块 Manager 类
4. **策略模式**: 缓存策略、限速策略
5. **观察者模式**: 配置变更监听、告警通知
6. **工作单元模式**: 数据访问层事务管理
7. **仓库模式**: 数据访问抽象层
8. **熔断器模式**: 下游服务调用保护

## 数据模型

### 核心实体模型
```json
{
  "id": "ent_001",
  "type": "record",
  "status": "failed",
  "attributes": {"key": "value"},
  "created_at": "2026-05-11T08:00:00Z",
  "updated_at": "2026-05-11T09:00:00Z"
}
```

### 配置定义模型
```json
{
  "config_id": "cfg_001",
  "namespace": "development",
  "version": 3,
  "parameters": {"timeout": 30, "retries": 3},
  "enabled": true,
  "applied_at": "2026-05-11T08:30:00Z"
}
```

### 运行实例模型
```json
{
  "run_id": "run_001",
  "entity_id": "ent_001",
  "phase": "initializing",
  "progress": 0.75,
  "started_at": "2026-05-11T08:00:00Z",
  "completed_at": null,
  "error_detail": null
}
```

## 开发指南

### 添加新模块

1. 在 `src/` 下创建模块目录
2. 创建 `models.py` 定义数据模型
3. 创建业务逻辑文件
4. 创建 `router.py` 定义 API 端点
5. 在 `main.py` 中注册 router

### 代码规范

- 所有模型使用 Pydantic v2
- API 响应统一使用 `APIResponse` 格式
- 异常处理使用 `InfrastructureError` 派生类
- 使用类型注解
- 异步操作使用 async/await

## 风险预案

- **数据丢失**: 采用预写日志(WAL)机制，确保崩溃后可恢复
- **并发冲突**: 使用乐观锁配合重试机制，高冲突时自动降级为悲观锁
- **配置漂移**: 建立配置版本化机制，定期Diff对比各环境配置差异

## License

Copyright (c) 2024 Enterprise Infrastructure Platform Team
