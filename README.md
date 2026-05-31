# Cloud Native Engine - 数据迁移与Schema版本控制

一个可扩展的云原生引擎，提供数据迁移、Schema版本控制、故障注入、命令溯源、审计等核心能力。

## 功能模块

### 1. 数据访问模块 (Data Access)
- 实现数据迁移与Schema版本控制
- 基于SQLAlchemy 2.0的异步ORM
- 支持自动迁移和版本追踪
- 实体、配置、运行实例的CRUD操作

### 2. 故障注入编排模块 (Fault Injection)
- 故障场景定义（延迟、错误、数据损坏、CPU尖峰等）
- 注入范围控制（全局、模块、函数、端点、实体、用户）
- 自动回滚机制
- 条件触发和概率控制

### 3. 命令溯源与审计模块 (Audit)
- CQRS命令持久化
- 审计日志关联
- 合规报告生成
- 操作追溯和行为审计

### 4. 日志模块 (Logging)
- 日志轮转与自动压缩归档
- 结构化JSON日志
- 多级别日志输出
- 日志清理和保留策略

### 5. 配置管理模块 (Config)
- 配置校验与默认值管理
- 多环境配置支持
- 配置版本追踪
- 配置差异对比

### 6. 存储管理模块 (Storage)
- 数据备份与恢复
- 多后端支持（本地文件、S3、内存）
- 自动压缩和校验
- 备份生命周期管理

### 7. 核心处理模块 (Core)
- 任务调度与执行管理
- 异步任务队列
- 重试机制和超时控制
- 上下文管理和资源清理

### 8. 通知模块 (Notification)
- 多渠道通知（邮件、Slack、Webhook、短信等）
- 通知优先级管理
- 抑制策略（去重、限流、静默）
- 通知状态追踪

### 9. 事件存储与回放模块 (Event Store)
- 事件日志持久化
- 快照管理
- 投影重建
- 时间旅行查询
- 事件溯源支持

## 技术栈

- **语言**: Python 3.10+
- **Web框架**: FastAPI + Pydantic
- **ORM**: SQLAlchemy 2.0 + Alembic
- **异步**: asyncio
- **日志**: structlog + python-json-logger
- **测试**: pytest + pytest-asyncio
- **重试**: tenacity

## 快速开始

### 安装依赖

```bash
cd session182
pip install -r requirements.txt
```

### 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 文件配置数据库、Redis等
```

### 初始化数据库

```bash
# 创建数据库表
python -c "
import asyncio
from src.modules import get_db_manager
asyncio.run(get_db_manager().create_tables())
"

# 或使用Alembic迁移
alembic upgrade head
```

### 启动服务

```bash
python -m uvicorn src.api.main:app --reload --host 0.0.0.0 --port 8000
```

### 访问API文档

- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc

## API 端点

### 资源管理

- `POST /api/v1/resources` - 创建资源
- `GET /api/v1/resources` - 列出资源
- `GET /api/v1/resources/{id}` - 获取资源详情
- `GET /api/v1/resources/{id}/status` - 获取资源状态
- `DELETE /api/v1/resources/{id}` - 删除资源
- `POST /api/v1/resources/batch` - 批量操作

### 任务管理

- `GET /api/v1/tasks` - 列出任务
- `GET /api/v1/tasks/{id}` - 获取任务详情
- `POST /api/v1/tasks/{id}/cancel` - 取消任务
- `GET /api/v1/tasks/{id}/result` - 获取任务结果

### 配置管理

- `GET /api/v1/configs` - 列出配置
- `POST /api/v1/configs` - 创建配置
- `GET /api/v1/configs/{id}` - 获取配置

### 通知管理

- `GET /api/v1/notifications` - 列出通知
- `POST /api/v1/notifications` - 发送通知
- `GET /api/v1/notifications/stats` - 通知统计
- `POST /api/v1/notifications/silence` - 静默通知

### 故障注入

- `GET /api/v1/faults` - 列出故障
- `POST /api/v1/faults` - 创建故障
- `POST /api/v1/faults/{id}/activate` - 激活故障
- `POST /api/v1/faults/{id}/deactivate` - 停用故障
- `DELETE /api/v1/faults/{id}` - 删除故障
- `GET /api/v1/faults/stats` - 故障统计

### 审计日志

- `GET /api/v1/audit/logs` - 查询审计日志
- `GET /api/v1/audit/reports` - 生成合规报告
- `GET /api/v1/audit/commands` - 列出命令

### 存储管理

- `GET /api/v1/storage/backups` - 列出备份
- `POST /api/v1/storage/backups` - 创建备份
- `POST /api/v1/storage/backups/{id}/restore` - 恢复备份
- `DELETE /api/v1/storage/backups/{id}` - 删除备份
- `GET /api/v1/storage/objects` - 列出存储对象

### 事件存储

- `GET /api/v1/events` - 列出事件
- `GET /api/v1/events/snapshots` - 列出快照

### 系统管理

- `GET /health` - 健康检查
- `GET /api/v1/system/stats` - 系统统计
- `GET /api/v1/system/config` - 系统配置
- `POST /api/v1/system/shutdown` - 关闭系统

## 运行测试

```bash
# 运行所有测试
pytest

# 运行特定模块测试
pytest tests/test_config.py

# 生成覆盖率报告
pytest --cov=src --cov-report=html
```

## 核心架构

```
┌─────────────────────────────────────────────────────────────┐
│                      FastAPI API Layer                       │
├─────────────────────────────────────────────────────────────┤
│  Core Engine  │  Task Scheduler  │  Execution Handler        │
├───────────────┼──────────────────┼───────────────────────────┤
│   Data Access │  Event Store     │  Command / Audit          │
├───────────────┼──────────────────┼───────────────────────────┤
│   Storage     │  Notification    │  Fault Injection          │
├───────────────┼──────────────────┼───────────────────────────┤
│            Config  │  Logging  │  Metrics                    │
└─────────────────────────────────────────────────────────────┘
```

## 设计原则

1. **模块化单体**: 采用模块化单体架构，平衡灵活性与维护成本
2. **异步优先**: 所有IO操作使用异步模式，提高并发性能
3. **可观测性**: 内置日志、指标、追踪能力
4. **容错设计**: 重试机制、熔断、降级、故障注入
5. **可扩展性**: 插件化设计，支持自定义后端和策略

## 许可证

MIT License
