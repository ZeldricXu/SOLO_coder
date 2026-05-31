# 依赖任务编排中间件 (Task Orchestrator)

轻量高效的技术中间件，解决核心业务流程中的效率与可靠性问题。

## 功能模块

### MVP 必实现
- **调度模块** - 实现依赖任务编排
- **项目脚手架生成模块** - 基于模板生成项目骨架，参数化配置与交互式问答
- **通知模块** - 实现送达追踪与失败重试
- **日志模块** - 实现日志轮转与归档
- **代码质量门禁模块** - 多语言静态分析规则配置、质量门禁检查与报告
- **数据访问模块** - 实现数据库连接池管理与查询优化
- **存储管理模块** - 实现对象存储适配与元数据索引

### 核心模块
- **核心处理模块** - 实现任务调度与执行管理的核心逻辑
- **软件目录与发现模块** - 服务/库的元数据注册、检索与依赖关系展示

## 技术栈

- **Web框架**: FastAPI + Pydantic
- **ORM**: SQLAlchemy 2.0 + Alembic
- **任务队列**: Celery + Redis/RabbitMQ
- **测试**: pytest + pytest-asyncio

## 快速开始

```bash
# 安装依赖
pip install -r requirements.txt

# 配置环境变量
cp .env.example .env

# 启动服务
uvicorn src.main:app --reload
```

## API 文档

启动服务后访问:
- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc

## 项目结构

```
session273/
├── src/
│   ├── __init__.py
│   ├── main.py                 # FastAPI主入口
│   ├── config.py               # 配置管理
│   ├── utils/                  # 工具函数
│   ├── models.py               # 数据模型
│   ├── api/                    # API路由
│   ├── core/                   # 核心处理模块
│   ├── scheduler/              # 调度模块
│   ├── scaffolder/             # 脚手架生成模块
│   ├── notification/           # 通知模块
│   ├── logging_/               # 日志模块
│   ├── quality_gate/           # 代码质量门禁模块
│   ├── data_access/            # 数据访问模块
│   ├── storage/                # 存储管理模块
│   └── registry/               # 软件目录与发现模块
├── tests/                      # 测试用例
└── configs/                    # 配置文件
```
