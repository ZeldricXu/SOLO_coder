# Gas Estimator Platform

基于历史数据和当前网络状态预估交易Gas费用的企业级区块链平台。

## 项目概述

本平台面向开发工程师，提供完整的区块链基础设施服务，致力于解决核心业务流程中的效率与可靠性问题。作为轻量高效的企业级平台，其核心价值主张在于保障系统可靠性。

## 功能模块

### MVP阶段交付

1. **零知识证明验证模块** - 接收ZKP证明数据，执行电路验证并返回验证结果
2. **链交互适配模块** - 对接多链RPC节点，统一区块数据查询与交易提交接口
3. **交易构造与签名模块** - 构造链上交易数据结构，管理多签策略与Gas优化
4. **Gas费用预估模块** - 基于历史数据和当前网络状态预估交易Gas费用
5. **资产跨链桥接模块** - 跨链消息验证，资产锁定与铸造的原子性保障
6. **合约事件监听模块** - 监听链上合约事件日志，事件触发后执行预定义回调
7. **地址派生与管理模块** - 基于HD钱包标准派生地址，管理地址簿与标签
8. **去中心化存储适配模块** - 对接IPFS/Arweave等存储网络，内容寻址与Pin管理

## 系统架构

自上而下分为四层：

```
┌─────────────────────────────────────────────────┐
│                 接口接入层 (API)                │
│  RESTful API / WebSocket / GraphQL / DTOs       │
├─────────────────────────────────────────────────┤
│                业务处理层 (Services)            │
│  8个核心业务模块 / 业务逻辑 / 工作流编排         │
├─────────────────────────────────────────────────┤
│                领域模型层 (Domain)              │
│  数据模型 / 领域实体 / Schema定义 / 业务规则     │
├─────────────────────────────────────────────────┤
│              基础设施层 (Infrastructure)        │
│  数据库 / 缓存 / RPC客户端 / 存储客户端 / 密码学 │
└─────────────────────────────────────────────────┘
```

### 处理链路

事件经过 `解析 → 路由 → 转换 → 存储 → 通知`

## 技术栈

- **语言**: Python 3.11+
- **Web框架**: FastAPI + Pydantic v2
- **ORM**: SQLAlchemy 2.0 + Alembic
- **任务队列**: Celery + Redis/RabbitMQ
- **数据库**: PostgreSQL (asyncpg)
- **缓存**: Redis
- **区块链**: Web3.py, eth-account
- **测试**: pytest + pytest-asyncio
- **监控**: Prometheus + OpenTelemetry
- **日志**: structlog

## 快速开始

### 环境要求

- Python >= 3.11
- PostgreSQL >= 15
- Redis >= 7
- Node.js >= 18 (可选，用于前端)

### 安装依赖

```bash
pip install -r requirements.txt
# 或使用 poetry
poetry install
```

### 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 文件，填入必要的配置
```

### 数据库初始化

```bash
alembic upgrade head
python scripts/init_db.py
```

### 启动服务

```bash
# 启动 API 服务
uvicorn main:app --host 0.0.0.0 --port 8000 --reload

# 启动 Celery Worker
celery -A app.core.celery worker --loglevel=info

# 启动 Celery Beat (定时任务)
celery -A app.core.celery beat --loglevel=info
```

### 访问服务

- API 文档: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc
- 健康检查: http://localhost:8000/health
- 指标: http://localhost:9090/metrics

## API 契约

### 资源管理 API

```http
POST /api/v1/resources
Content-Type: application/json

{
  "type": "job",
  "config": {},
  "labels": {}
}

Response:
{
  "code": 201,
  "data": {
    "id": "rsc_332",
    "status": "provisioning"
  }
}
```

### 状态查询 API

```http
GET /api/v1/resources/{id}/status

Response:
{
  "code": 200,
  "data": {
    "id": "...",
    "status": "running",
    "progress": 0.8
  }
}
```

### 批量操作 API

```http
POST /api/v1/resources/batch
Content-Type: application/json

{
  "operations": [
    {"action": "start", "id": "rsc_001"}
  ]
}

Response:
{
  "code": 200,
  "data": {
    "batch_id": "batch_484",
    "results": [...]
  }
}
```

## 项目结构

```
session302/
├── app/
│   ├── __init__.py
│   ├── domain/                 # 领域模型层
│   │   ├── models/            # ORM 数据模型
│   │   └── schemas/           # Pydantic Schema
│   ├── infrastructure/       # 基础设施层
│   │   ├── database.py
│   │   ├── cache.py
│   │   ├── rpc_client.py
│   │   ├── storage_client.py
│   │   ├── crypto.py
│   │   └── metrics.py
│   ├── services/             # 业务处理层
│   │   ├── zkp_service.py
│   │   ├── chain_service.py
│   │   ├── transaction_service.py
│   │   ├── gas_service.py
│   │   ├── bridge_service.py
│   │   ├── event_service.py
│   │   ├── address_service.py
│   │   └── storage_service.py
│   ├── api/                  # 接口接入层
│   │   ├── deps.py
│   │   └── v1/
│   └── core/                 # 核心组件
│       ├── config.py
│       ├── exceptions.py
│       ├── logging.py
│       └── middleware.py
├── alembic/                  # 数据库迁移
├── tests/                    # 测试套件
├── scripts/                  # 脚本工具
├── main.py                   # 应用入口
├── pyproject.toml
├── requirements.txt
└── README.md
```

## 测试

```bash
# 运行所有测试
pytest

# 运行单元测试
pytest tests/unit/

# 运行集成测试
pytest tests/integration/

# 生成覆盖率报告
pytest --cov=app --cov-report=html
```

## 开发规范

- 代码风格: ruff (PEP 8)
- 类型检查: mypy (strict mode)
- 提交规范: Conventional Commits
- 分支策略: Git Flow

## 风险与缓解

1. **性能退化**: 实现分批处理与并发执行，控制处理时间
2. **内存泄漏**: 建立内存使用监控，异常时自动触发Heap Dump与告警
3. **依赖雪崩**: 设置超时与线程池隔离，引入熔断器快速失败

## License

MIT
