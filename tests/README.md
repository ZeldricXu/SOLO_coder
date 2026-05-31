# 测试套件

## 目录结构

```
tests/
├── builders/                     # 测试数据构造模块
│   ├── __init__.py
│   ├── service_builder.py        # 服务目录测试数据构造器
│   ├── scaffold_builder.py       # 脚手架生成测试数据构造器
│   └── vulnerability_builder.py  # 漏洞分析测试数据构造器
├── unit/                         # 单元测试
│   ├── test_catalog_data_consistency.py     # 软件目录与发现模块 - 数据一致性
│   ├── test_scaffold_concurrency.py         # 项目脚手架生成模块 - 并发隔离
│   └── test_vulnerability_timeout.py        # 依赖漏洞分析模块 - 超时降级
├── integration/                  # 集成测试（预留）
├── conftest.py                   # pytest全局配置和fixtures
├── pytest.ini                    # pytest配置文件
├── requirements.txt              # 测试依赖
└── README.md                     # 本文档
```

## Builder模块设计

### Builder模式统一管理测试数据

所有测试数据的构造统一放在 `tests/builders/` 目录下，使用Builder模式：

- **ServiceBuilder**: 构造服务目录相关的测试数据
- **ScaffoldBuilder**: 构造脚手架生成相关的测试数据
- **VulnerabilityBuilder**: 构造漏洞分析相关的测试数据

### Builder使用示例

```python
from tests.builders import ServiceBuilder, ScaffoldBuilder, VulnerabilityBuilder

# 创建默认服务
service = ServiceBuilder.create_default()

# 创建自定义服务
service = (ServiceBuilder()
          .with_name("custom-service")
          .with_type("library")
          .with_labels({"env": "prod"})
          .build())

# 创建多个服务
services = ServiceBuilder.create_many(10)

# 创建带依赖链的服务
services = ServiceBuilder.create_with_dependencies(depth=3)

# 创建脚手架请求
request = ScaffoldBuilder.create_go_service_request()

# 创建并发测试用的请求
requests = ScaffoldBuilder.create_concurrent_requests(10)

# 创建SBOM数据
sbom = VulnerabilityBuilder.create_sbom_with_vulnerabilities(vuln_count=5)

# 创建混合严重级别的SBOM
sbom = VulnerabilityBuilder.create_sbom_with_mixed_severity()
```

## 测试模块说明

### 1. 软件目录与发现模块 - 数据一致性保障

**文件**: `tests/unit/test_catalog_data_consistency.py`

**测试覆盖**:
- 服务注册的幂等性验证
- 并发注册无数据损坏
- 乐观锁更新一致性
- 依赖链完整性验证
- 循环依赖检测
- 批量操作原子性
- 标签修改隔离性
- 端点修改一致性
- 删除后搜索排除
- 并发读写一致性
- 依赖图完整性
- 事务回滚机制
- 必填字段验证
- 重复名称检测
- 数据模式向后兼容性
- 并发依赖更新

### 2. 项目脚手架生成模块 - 并发隔离级别

**文件**: `tests/unit/test_scaffold_concurrency.py`

**测试覆盖**:
- 并发生成输出目录隔离
- 模板渲染无相互干扰
- Builder线程安全使用
- 共享输出目录隔离
- 同模板并发生成
- Builder跨线程不可变性
- 并发文件生成无损坏
- 自定义参数并发构建
- 模板定义线程安全访问
- 临时目录并发清理
- 多模板类型并发生成
- Mutex保护共享资源
- 可扩展性并发级别测试
- 文件权限正确性

### 3. 依赖漏洞分析模块 - 超时降级行为

**文件**: `tests/unit/test_vulnerability_timeout.py`

**测试覆盖**:
- 分析超时返回部分结果
- CVE数据库不可用时降级模式
- 熔断器多次失败后打开
- 单组件超时处理
- 内存压力优雅降级
- 超时预算内并发分析
- 缓存结果回退机制
- SLA违规触发降级
- 下游服务超时传播
- 指数退避重试
- 舱壁隔离模式
- 基于严重级别的超时优先级
- 限流降级处理
- 参数化超时行为

## 运行测试

### 安装测试依赖

```bash
cd session138
pip install -r tests/requirements.txt
```

### 运行所有测试

```bash
pytest
```

### 运行特定模块测试

```bash
# 运行数据一致性测试
pytest tests/unit/test_catalog_data_consistency.py -v

# 运行并发隔离测试
pytest tests/unit/test_scaffold_concurrency.py -v

# 运行超时降级测试
pytest tests/unit/test_vulnerability_timeout.py -v
```

### 按标记运行测试

```bash
# 运行所有并发相关测试
pytest -m concurrency -v

# 运行所有超时相关测试
pytest -m timeout -v

# 运行所有数据一致性测试
pytest -m consistency -v

# 运行单元测试（排除集成测试）
pytest -m "unit" -v
```

### 生成测试覆盖率报告

```bash
pytest --cov=internal --cov-report=html --cov-report=term
```

### 运行特定测试用例

```bash
# 运行单个测试函数
pytest tests/unit/test_catalog_data_consistency.py::TestCatalogDataConsistency::test_service_creation_idempotency -v

# 运行带参数的测试
pytest tests/unit/test_vulnerability_timeout.py::TestVulnerabilityTimeoutDegradation::test_timeout_parametrized_behavior -v
```

## 测试最佳实践

1. **使用Builder构造测试数据**: 所有测试数据统一通过Builder模块创建，便于维护
2. **测试隔离**: 每个测试用例使用独立的数据，避免测试间相互影响
3. **Mock外部依赖**: 使用 `unittest.mock` 模拟数据库、外部API等依赖
4. **参数化测试**: 对多组输入输出场景使用 `@pytest.mark.parametrize`
5. **fixture复用**: 通用测试数据和Mock通过conftest中的fixture共享
6. **标记分类**: 使用pytest markers对测试进行分类，便于选择性运行

## 扩展测试

### 添加新的Builder

在 `tests/builders/` 目录下创建新的Builder类：

```python
class MyModuleBuilder:
    def __init__(self):
        self._field = "default"

    def with_field(self, value):
        self._field = value
        return self

    def build(self):
        return {"field": self._field}
```

在 `tests/builders/__init__.py` 中导出：

```python
from .my_module_builder import MyModuleBuilder
__all__ = [..., "MyModuleBuilder"]
```

### 添加新的测试文件

1. 在 `tests/unit/` 或 `tests/integration/` 下创建 `test_*.py` 文件
2. 编写测试类，使用 `Test*` 前缀
3. 测试函数使用 `test_*` 前缀
4. 使用Builder构造测试数据
