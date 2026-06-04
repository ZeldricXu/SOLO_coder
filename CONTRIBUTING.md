# Contributing to 运维监控大盘

感谢你对运维监控大盘项目的贡献！本文档将帮助你快速搭建本地开发环境并了解项目的代码规范。

## 本地开发环境搭建

### 1. 前置要求

- **Python**: 3.12+（推荐 3.12.x）
- **Docker**: 24.0+（用于运行 MySQL、Elasticsearch 等依赖服务）
- **Git**: 2.30+

### 2. 克隆仓库

```bash
git clone git@gitlab.example.com:ops-monitor/monitor-dashboard.git
cd monitor-dashboard
```

### 3. 创建虚拟环境

```bash
python3.12 -m venv .venv
source .venv/bin/activate
```

### 4. 安装依赖

```bash
pip install -r requirements.txt
pip install -r tests/requirements-test.txt
```

### 5. 启动依赖服务

**方式一：Docker Compose（推荐）**

```bash
docker compose up -d mysql elasticsearch prometheus
```

**方式二：本地安装**

- MySQL 8.0：端口 3306，创建 `monitor` 数据库
- Elasticsearch 8.11：端口 9200，关闭安全认证
- Prometheus 2.48：端口 9090

### 6. 配置环境变量

```bash
cp .env.example .env
# 根据实际情况修改 .env 中的配置
```

关键配置项：

| 变量名 | 开发环境默认值 | 说明 |
|--------|----------------|------|
| `ENVIRONMENT` | `development` | 环境标识 |
| `DATABASE_URL` | `sqlite:///./monitor.db` | 数据库连接串 |
| `PROMETHEUS_URL` | `http://localhost:9090` | Prometheus 地址 |
| `ELASTICSEARCH_URL` | `http://localhost:9200` | Elasticsearch 地址 |

### 7. 初始化数据库

```bash
python init_db.py
```

### 8. 启动开发服务器

```bash
python -m app.main
# 或
uvicorn app.main:app --reload --port 8000
```

访问 http://localhost:8000 查看监控大盘。

## 项目结构

```
app/
├── config.py           # 多环境配置（pydantic-settings）
├── database.py         # 数据库连接和会话管理
├── main.py             # FastAPI 应用入口
├── metrics.py          # Prometheus 指标采集
├── logging_config.py   # 日志配置
├── models/             # ORM 模型定义
├── routes/             # API 路由层（参数校验 + 响应格式化）
├── schemas/            # Pydantic 请求/响应模型
├── services/           # 业务逻辑层（核心）
└── templates/          # Jinja2 HTML 模板
```

### 分层架构

```
请求 → Route（参数校验） → Service（业务逻辑） → Model（数据持久化）
                                    ↓
                            NotificationService（通知分发）
                            EmailService（邮件发送）
                            httpx（外部API调用）
```

**核心原则**：
- Route 层不写业务逻辑，只做参数校验和调用 Service
- Service 层之间通过明确的接口交互，不直接跨模块调用私有方法
- 通知逻辑统一走 NotificationService，不在其他 Service 中直接发送通知

## 代码规范

### Lint 和格式化

项目使用 [ruff](https://docs.astral.sh/ruff/) 作为代码检查和格式化工具，配置见 `ruff.toml`。

```bash
# 检查代码风格
ruff check app/ tests/

# 自动修复
ruff check --fix app/ tests/

# 格式化
ruff format app/ tests/
```

### 类型注解

- 所有函数必须添加参数和返回值的类型注解
- 使用 Sphinx 风格的 docstring 说明参数含义
- 运行 mypy 检查：

```bash
mypy app/ --ignore-missing-imports
```

### Docstring 规范

使用 Sphinx 风格：

```python
def evaluate_rules(self, window_seconds: int = 300) -> List[AlertHistory]:
    """评估所有启用的告警规则。

    时间窗口为 [now - window_seconds, now]，左闭右闭。

    :param window_seconds: 评估窗口秒数，默认300
    :return: 本次评估新触发的 AlertHistory 对象列表
    :raises ValueError: 规则配置无效时抛出
    """
```

**关键点**：
- 每个模块的 `__init__.py` 必须包含模块级 docstring，说明职责、对外接口和依赖
- 容易踩坑的地方必须在 docstring 中明确说明（如时间窗口边界、null 处理等）
- 类级 docstring 必须说明：主要职责、对外接口、依赖的外部服务

## 测试

### 运行测试

```bash
# 全部测试
pytest tests/ -v

# 单个模块
pytest tests/test_alert_service.py -v

# 带覆盖率
pytest tests/ --cov=app --cov-report=term
```

### 测试规范

- 单元测试放在 `tests/` 目录，文件命名 `test_<module>.py`
- 测试数据使用 `factory_boy` 构造，不硬编码
- 每个 Service 的测试类按场景分组：正常场景、异常场景、边界场景

```bash
# 测试数据工厂
tests/factories.py
```

## PR 流程

### 1. 创建分支

```bash
git checkout -b feature/your-feature-name
# 或
git checkout -b fix/your-bug-fix
```

### 2. 开发和测试

确保所有测试通过：

```bash
pytest tests/ -v
ruff check app/ tests/
```

### 3. 提交代码

#### Commit Message 格式

使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

**type 类型**：

| type | 说明 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `refactor` | 代码重构（不改变功能） |
| `docs` | 文档变更 |
| `test` | 测试相关 |
| `chore` | 构建/工具/依赖变更 |
| `perf` | 性能优化 |

**scope 范围**：

| scope | 说明 |
|-------|------|
| `health` | 健康检查模块 |
| `alert` | 告警引擎模块 |
| `slowsql` | 慢SQL模块 |
| `log` | 日志搜索模块 |
| `metrics` | 指标监控模块 |
| `notify` | 通知模块 |
| `infra` | 基础设施（Docker/CI/配置） |

**示例**：

```
feat(alert): 添加告警收敛逻辑，同一规则5分钟内不重复通知
fix(health): 修复Prometheus返回500时面板崩溃的问题
refactor(notify): 将通知逻辑从AlertService抽取到NotificationService
docs(services): 补充核心模块的docstring和模块说明
```

### 4. 创建 Merge Request

- MR 标题遵循 commit message 格式
- 关联相关 Issue
- CI 流水线必须全部绿灯
- 至少一位 Code Reviewer 批准后才能合并

### 5. CI 检查项

每次 MR 自动运行：

1. **lint**: ruff + mypy
2. **test**: pytest 全量测试 + 覆盖率报告
3. **build**: Docker 镜像构建验证

所有检查通过后才允许合并到 main 分支。
