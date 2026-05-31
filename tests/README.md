# LLM Gateway 单元测试套件

## 概述

本测试套件使用 `pytest` + `unittest.mock` 为LLM Gateway的三个核心模块提供完整的单元测试覆盖：

1. **特征存储服务模块** - 数据一致性、并发隔离、超时降级
2. **文档解析管道模块** - 数据一致性、并发隔离、超时降级
3. **模型注册与版本模块** - 数据一致性、并发隔离、超时降级

## 测试架构

```
tests/
├── __init__.py
├── conftest.py                 # pytest配置与fixtures
├── pytest.ini                  # pytest配置文件
├── requirements.txt            # Python依赖
├── .env.test                   # 测试环境配置
├── data_factory.py             # 测试数据工厂
├── http_client.py              # HTTP客户端封装（支持Mock）
├── base_test.py                # 测试基类
├── feature_store/
│   ├── __init__.py
│   └── test_feature_store.py   # 特征存储模块测试
├── document_pipeline/
│   ├── __init__.py
│   └── test_document_pipeline.py # 文档解析模块测试
└── model_registry/
    ├── __init__.py
    └── test_model_registry.py  # 模型注册模块测试
```

## 测试场景覆盖

### 1. 数据一致性保障测试 (@pytest.mark.consistency)

- **特征存储**:
  - 特征注册与查询的数据一致性
  - 特征值写入与读取的数据一致性
  - 批量写入的一致性
  - 时间范围查询的一致性

- **文档解析**:
  - 文档上传与查询的数据一致性
  - 文档解析结果的一致性
  - 大文档解析的一致性
  - 多文件类型解析的一致性

- **模型注册**:
  - 模型注册与查询的数据一致性
  - 版本创建的一致性
  - 阶段流转的一致性
  - 版本列表的一致性

### 2. 并发隔离级别测试 (@pytest.mark.concurrency)

- **特征存储**:
  - 并发特征注册的隔离性
  - 同一特征并发写入的隔离性
  - 读写隔离

- **文档解析**:
  - 并发文档上传的隔离性
  - 并发文档解析的隔离性
  - 并发查询切片的隔离性

- **模型注册**:
  - 并发模型注册的隔离性
  - 同一模型并发创建版本的隔离性
  - 并发阶段流转的隔离性
  - 并发模型更新的隔离性

### 3. 超时降级行为测试 (@pytest.mark.timeout)

- 使用Mock模拟超时场景
- 验证超时响应的正确性
- 验证慢查询的降级行为
- 验证失败时的优雅处理

## 快速开始

### 1. 安装依赖

```bash
cd tests
pip install -r requirements.txt
```

### 2. 配置环境变量

修改 `.env.test` 文件：

```env
BASE_URL=http://localhost:8080
API_PREFIX=/api/v1
DEFAULT_TIMEOUT=30
```

### 3. 运行测试

#### 运行所有测试
```bash
pytest -v
```

#### 运行特定模块的测试
```bash
# 特征存储模块
pytest -v feature_store/

# 文档解析模块
pytest -v document_pipeline/

# 模型注册模块
pytest -v model_registry/
```

#### 运行特定类型的测试
```bash
# 数据一致性测试
pytest -v -m consistency

# 并发测试
pytest -v -m concurrency

# 超时测试
pytest -v -m timeout

# 冒烟测试
pytest -v -m smoke
```

#### 运行带覆盖率的测试
```bash
pytest -v --cov=. --cov-report=html --cov-report=term
```

### 4. 使用Mock模式运行

Mock模式不需要启动后端服务，直接测试客户端逻辑：

```python
# 在测试类中继承 MockBaseTest 而不是 BaseTest
class TestFeatureStoreTimeout(MockBaseTest):
    # 测试将自动使用Mock响应
    ...
```

## 测试数据工厂

`data_factory.py` 提供统一的测试数据生成能力：

```python
from tests.data_factory import get_factory

factory = get_factory()

# 生成特征数据
feature_data = factory.create_feature_data()

# 生成文档数据
document_data = factory.create_document_data(file_type='md')

# 生成模型数据
model_data = factory.create_model_data()

# 批量生成
features = factory.create_batch(factory.create_feature_data, count=10)
```

## HTTP客户端

`http_client.py` 封装了HTTP请求，支持：

- 异步请求
- 超时控制
- 自动重试
- Mock响应
- 请求历史记录
- 并发执行

```python
from tests.http_client import TestHttpClient

client = TestHttpClient(use_mock=True)

# 注册Mock响应
client.register_mock_response(
    path="/feature-store/features/test_id",
    method="GET",
    response=create_mock_response(status_code=200, data={"id": "test_id"})
)

# 发起请求
response = await client.get("/feature-store/features/test_id")
assert response.is_success
```

## 测试基类

`base_test.py` 提供两个基类：

### BaseTest
- 真实API调用测试
- 自动清理测试资源
- 常用断言方法
- 性能测量

### MockBaseTest
- 基于Mock的测试
- 不需要后端服务
- 预设Mock响应
- 超时/错误场景模拟

## 测试标记

| 标记 | 说明 |
|------|------|
| `@pytest.mark.feature_store` | 特征存储服务模块测试 |
| `@pytest.mark.document_pipeline` | 文档解析管道模块测试 |
| `@pytest.mark.model_registry` | 模型注册与版本模块测试 |
| `@pytest.mark.consistency` | 数据一致性测试 |
| `@pytest.mark.concurrency` | 并发隔离测试 |
| `@pytest.mark.timeout` | 超时降级测试 |
| `@pytest.mark.smoke` | 冒烟测试 |
| `@pytest.mark.regression` | 回归测试 |

## 最佳实践

1. **测试隔离**: 每个测试用例独立，不依赖其他测试的状态
2. **资源清理**: 使用 `register_resource` 注册需要清理的资源
3. **数据工厂**: 所有测试数据通过 `TestDataFactory` 生成
4. **断言清晰**: 每个断言都有明确的失败信息
5. **Mock恰当**: 外部依赖使用Mock，重点测试业务逻辑

## 持续集成

在CI环境中运行测试：

```bash
# 安装依赖
pip install -r requirements.txt

# 运行冒烟测试
pytest -v -m smoke

# 运行所有测试并生成报告
pytest -v --junitxml=test-results.xml --cov=. --cov-report=xml
```

## 故障排查

### 测试超时
- 检查后端服务是否正常运行
- 调整 `DEFAULT_TIMEOUT` 配置
- 检查网络连接

### Mock测试不生效
- 确认继承了 `MockBaseTest`
- 检查Mock路径是否与请求路径匹配
- 确认Mock响应已正确注册

### 并发测试不稳定
- 调整 `max_concurrent` 参数
- 检查后端服务的并发处理能力
- 增加重试机制
