# Solo Platform - 企业级研发平台

## 架构概述

本项目采用**依赖倒置原则**（Dependency Inversion Principle）进行设计，解决了原代码中"高层模块直接依赖低层实现"的问题。

### 分层架构

```
┌─────────────────────────────────────────┐
│         业务模块层 (modules)            │
│  高层业务逻辑，只依赖抽象协议            │
├─────────────────────────────────────────┤
│         核心抽象层 (core)               │
│  Protocol接口定义、领域模型、异常        │
├─────────────────────────────────────────┤
│         基础设施层 (infrastructure)     │
│  协议的具体实现，可替换                  │
└─────────────────────────────────────────┘
```

### 核心设计原则

1. **高层模块只依赖抽象**：所有业务模块（modules）只导入 `src.core` 中的协议接口
2. **依赖注入**：运行时通过构造函数注入具体的基础设施实现
3. **可测试性**：使用内存实现（如 `InMemoryFileSystem`、`MemoryStorage`）进行单元测试
4. **可替换性**：可以轻松替换基础设施实现（如切换模板引擎、存储后端）

## 模块说明

| 模块 | 功能 | 关键抽象协议 |
|------|------|-------------|
| **日志模块** | 结构化日志输出，支持链路追踪 | `LoggerProtocol` |
| **API网关模块** | 请求日志、链路追踪、中间件 | `LoggerProtocol` |
| **存储管理模块** | 对象存储多后端适配、元数据索引 | `StorageProtocol` |
| **通知模块** | 多通道通知、优先级、抑制策略 | `NotificationProtocol` |
| **项目脚手架** | 基于模板生成项目骨架、交互式问答 | `TemplateEngineProtocol`, `FileSystemProtocol` |
| **代码质量门禁** | 多语言静态分析、质量检查报告 | `CodeAnalyzerProtocol` |
| **服务目录发现** | 服务元数据注册、依赖分析 | - |
| **文档索引模块** | 多源文档聚合、全文搜索、权限过滤 | `SearchBackend` |
| **API契约测试** | OpenAPI/GraphQL校验、Mock Server | - |

## 快速开始

### 安装依赖

```bash
pip install -e ".[full,dev]"
```

### 运行示例

```bash
# 日志示例
python examples/01_logging_example.py

# 脚手架示例 (展示内存文件系统测试)
python examples/02_scaffold_example.py

# API网关示例
python examples/03_gateway_example.py

# 通知示例
python examples/04_notification_example.py

# 质量门禁示例
python examples/05_quality_gate_example.py

# 服务发现示例
python examples/06_service_discovery_example.py

# API契约测试示例
python examples/07_api_testing_example.py

# 存储管理示例
python examples/08_storage_example.py

# 文档索引示例
python examples/09_document_index_example.py
```

### 运行测试

```bash
pytest tests/unit/ -v
```

## 架构改进对比

### ❌ 原问题
```python
# 高层模块直接依赖低层实现
class ProjectScaffold:
    def __init__(self):
        self.template_engine = Jinja2Engine()  # 直接依赖具体实现
        self.file_system = RealFileSystem()    # 无法进行单元测试
```

### ✅ 改进后
```python
# 高层模块只依赖抽象协议
class ProjectScaffold:
    def __init__(
        self,
        template_engine: TemplateEngineProtocol,  # 依赖抽象
        file_system: FileSystemProtocol,          # 依赖抽象
        template_registry: TemplateRegistry,
    ):
        self._template_engine = template_engine
        self._fs = file_system

# 运行时注入具体实现
scaffold = ProjectScaffold(
    template_engine=Jinja2TemplateEngine(),
    file_system=FileSystemAdapter(),  # 生产环境使用真实文件系统
    template_registry=registry,
)

# 测试时注入内存实现
scaffold = ProjectScaffold(
    template_engine=Jinja2TemplateEngine(),
    file_system=InMemoryFileSystem(),  # 测试使用内存文件系统
    template_registry=registry,
)
```

## 目录结构

```
session286/
├── src/
│   ├── core/                    # 核心抽象层
│   │   ├── __init__.py
│   │   ├── protocols.py         # Protocol接口定义
│   │   ├── models.py            # 领域模型
│   │   └── exceptions.py        # 异常定义
│   ├── infrastructure/          # 基础设施实现
│   │   ├── logging/             # 结构化日志
│   │   ├── storage/             # 存储后端
│   │   ├── notification/        # 通知渠道
│   │   └── template/            # 模板引擎
│   └── modules/                 # 业务模块
│       ├── api_gateway/         # API网关
│       ├── code_quality/        # 代码质量门禁
│       ├── scaffold/            # 项目脚手架
│       ├── service_discovery/   # 服务目录发现
│       ├── document_index/      # 文档索引
│       ├── api_testing/         # API契约测试
│       └── storage_manager/     # 存储管理
├── tests/                       # 测试
│   └── unit/
├── examples/                    # 示例代码
└── pyproject.toml
```

## 核心协议定义

### LoggerProtocol
```python
class LoggerProtocol(Protocol):
    def debug(self, message: str, **kwargs: Any) -> None: ...
    def info(self, message: str, **kwargs: Any) -> None: ...
    def warning(self, message: str, **kwargs: Any) -> None: ...
    def error(self, message: str, **kwargs: Any) -> None: ...
    def critical(self, message: str, **kwargs: Any) -> None: ...
    def with_trace(self, trace_ctx: TraceContext) -> "LoggerProtocol": ...
    def with_context(self, **kwargs: Any) -> "LoggerProtocol": ...
```

### StorageProtocol
```python
class StorageProtocol(Protocol):
    async def upload(self, bucket: str, key: str, data: bytes, metadata=None) -> str: ...
    async def download(self, bucket: str, key: str) -> bytes: ...
    async def delete(self, bucket: str, key: str) -> None: ...
    async def exists(self, bucket: str, key: str) -> bool: ...
    async def list(self, bucket: str, prefix=None) -> List[Dict]: ...
    async def get_metadata(self, bucket: str, key: str) -> Dict: ...
```

### TemplateEngineProtocol & FileSystemProtocol
这两个协议的引入使脚手架模块可以完全独立测试，无需依赖真实文件系统。

## 技术栈

- **Python 3.10+**
- **typing.Protocol** - 运行时协议检查
- **Jinja2** (可选) - 模板引擎
- **boto3** (可选) - S3存储
- **aiohttp** (可选) - 异步HTTP
- **pytest** - 测试框架

## 扩展性

### 添加新的存储后端
1. 实现 `StorageProtocol` 协议
2. 在 `StorageManager` 中添加工厂方法

### 添加新的通知渠道
1. 实现 `NotificationProtocol` 协议
2. 注册到 `NotificationManager`

### 添加新的代码分析器
1. 继承 `BaseAnalyzer` 或实现 `CodeAnalyzerProtocol`
2. 注册到 `AnalyzerDispatcher`

### 添加新的模板引擎
1. 实现 `TemplateEngineProtocol` 协议
2. 注入到 `ProjectScaffold`
