# WalletHub 测试体系
====================

## 测试概览
--------

本测试体系为数字资产钱包管理服务（WalletHub）提供完整的测试覆盖，确保线上变更风险。

## 测试框架与版本要求
- **测试框架**：pytest + pytest-asyncio
- **断言库**：pytest 内置断言（Pythonic 语法简洁）
- **Mock 库**：unittest.mock
- **代码覆盖率**：pytest-cov

## 测试目录结构
------------

```
tests/
├── __init__.py
├── conftest.py              # 共享配置与fixtures
├── test_factories.py         # 测试数据构造模块（TestFactory模式）
├── test_transaction_module.py  # 交易构造与签名模块测试
├── test_cross_chain_module.py  # 资产跨链桥接模块测试
└── test_storage_module.py    # 去中心化存储适配模块测试
└── README.md             # 本文档
```

## 核心测试数据构造模块（test_factories.py）
----------------------------------

### 设计理念
所有测试数据集中管理，避免测试函数硬编码测试数据，实现数据与逻辑分离。

### 核心工厂类：
1. **TestAddresses**：预定义测试地址集合
2. **TestPrivateKeys**：预定义测试私钥
3. **TestChainIds**：测试用链ID
4. **TransactionFactory**：交易测试数据工厂
5. **CrossChainFactory**：跨链测试数据工厂
6. **StorageFactory**：存储测试数据工厂
7. **EventFactory**：事件测试数据工厂
8. **WalletFactory**：钱包测试数据工厂
9. **TestDataGenerator**：通用随机数据生成器
10. **ScenarioLibrary**：复杂测试场景库

### 使用示例
```python
from tests.test_factories import TransactionFactory, CrossChainFactory

# 创建正常交易数据
tx_data = TransactionFactory.create_simple_transfer()

# 创建异常交易数据（用于参数化测试）
invalid_cases = TransactionFactory.create_invalid_transactions()

# 创建并发测试数据
concurrent_transfers = CrossChainFactory.create_concurrent_transfers(count=20)
```

## 1. 交易构造与签名模块测试（test_transaction_module.py）
--------------------------------------------------

### 测试覆盖：25个测试用例，4个测试类

#### TestTransactionBuilder（8个测试）
- ✅ 正常流程：ETH转账、EIP-1559转账、合约部署
- ✅ 异常流程：5种异常交易参数化测试

#### TestMultiSigManager（9个测试）
- ✅ 多签钱包创建、阈值验证、提案签名
- ✅ 异常场景：阈值>所有者数、阈值=0、重复签名、非所有者签名
- ✅ 状态流转：达到阈值状态流转

#### TestSigningService（8个测试）
- ✅ 私钥导入、交易签名、签名验证

#### TestGasOptimizer（7个测试）
- ✅ Gas优化、4档速度预估、波动率分析

## 2. 资产跨链桥接模块测试（test_cross_chain_module.py）
--------------------------------------------------

### 测试覆盖：33个测试用例，4个测试类

#### TestCrossChainBridge（13个测试）
- ✅ 跨链转账发起、状态机流转
- ✅ 完整生命周期：INITIATED → LOCKED → VERIFIED → MINTED → COMPLETED
- ✅ 异常场景：负金额、零金额、未知链、错误状态流转
- ✅ 列表过滤、不存在转账查询

#### TestCrossChainConcurrency（5个测试）
- ✅ 并发创建跨链转账（20个并发）
- ✅ 并发状态流转原子性（10个并发锁定同一转账）
- ✅ 并发混合操作
- ✅ 多线程安全性（10线程并发创建）
- ✅ 并发列表查询与修改

#### TestMessageVerifier（10个测试）
- ✅ 验证者管理：添加、移除、重复验证者
- ✅ 消息签名与验证
- ✅ 签名阈值验证
- ✅ Merkle证明验证
- ✅ 完整消息验证流程

#### TestAtomicSwapManager（5个测试）
- ✅ 密钥生成与验证
- ✅ 原子交换发起、锁定、赎回、退款
- ✅ 过期检测
- ✅ 列表过滤
- ✅ 并发交换操作

## 3. 去中心化存储适配模块测试（test_storage_module.py）
--------------------------------------------------

### 测试覆盖：47个测试用例，6个测试类

#### TestIPFSClient（15个测试）
- ✅ 内容添加（字节、JSON、字符串）
- ✅ 内容获取与JSON解析
- ✅ Pin/Unpin操作
- ✅ Pinata集成
- ✅ DAG操作
- ✅ 网关URL生成

#### TestIPFSClientResourceManagement（7个测试）
- ✅ HTTP会话关闭验证（add/get/pin/unpin后会话正确关闭）
- ✅ 多操作独立会话管理
- ✅ 错误发生时会话关闭
- ✅ 连接泄漏检测（10个并发操作无泄漏）

#### TestArweaveClient（12个测试）
- ✅ 数据上传（带标签、JSON）
- ✅ 数据获取
- ✅ 交易查询、状态查询
- ✅ 价格预估
- ✅ GraphQL查询
- ✅ 钱包加载

#### TestArweaveClientResourceManagement（3个测试）
- ✅ 上传后会话关闭
- ✅ 获取数据后会话关闭
- ✅ 上传错误时会话关闭

#### TestStorageManager（10个测试）
- ✅ IPFS/Arweave存储
- ✅ 内容检索（字节、JSON）
- ✅ Pin/Unpin
- ✅ 缓存管理
- ✅ 列表过滤

#### TestStorageManagerResourceManagement（4个测试）
- ✅ 客户端懒加载
- ✅ 客户端复用
- ✅ 并发存储操作
- ✅ 混合网络操作

## 运行测试
--------

### 安装依赖
```bash
cd session167
pip install -r requirements.txt
```

### 运行所有测试
```bash
pytest tests/ -v
```

### 运行特定模块测试
```bash
# 交易模块
pytest tests/test_transaction_module.py -v

# 跨链模块
pytest tests/test_cross_chain_module.py -v

# 存储模块
pytest tests/test_storage_module.py -v
```

### 运行带覆盖率报告
```bash
pytest tests/ -v --cov=wallethub --cov-report=html --cov-report=term-missing
```

### 运行异步测试
```bash
pytest tests/ -v -m asyncio
```

### 运行特定测试类/方法
```bash
# 运行特定测试类
pytest tests/test_transaction_module.py::TestTransactionBuilder -v

# 运行特定测试方法
pytest tests/test_transaction_module.py::TestTransactionBuilder::test_create_legacy_transfer_transaction -v
```

## Fixture 说明
-------------

### conftest.py 中定义的共享fixtures：

1. **event_loop**（session级别）：
   - 作用：提供全局事件循环
   - 范围：整个测试会话

2. **mock_settings**（function级别）：
   - 作用：Mock配置，避免依赖真实环境配置
   - 包含：数据库、Redis、IPFS、Arweave等配置

3. **sample_addresses**（function级别）：
   - 作用：提供预定义测试地址
   - 包含：alice、bob、charlie、dave、multisig

4. **sample_private_keys**（function级别）：
   - 作用：提供预定义测试私钥
   - 包含：alice、bob、charlie对应的私钥

## 测试设计原则
------------

1. **测试数据与测试逻辑分离**：所有测试数据通过 test_factories.py 统一管理
2. **参数化测试**：使用 @pytest.mark.parametrize 批量测试异常场景
3. **Mock外部依赖**：使用 unittest.mock 模拟外部服务（RPC节点、IPFS节点等）
4. **并发测试**：使用 asyncio.gather 验证线程安全和并发正确性
5. **资源释放测试**：验证HTTP会话、连接池等资源正确释放
6. **Fixture复用**：通过 conftest.py 共享配置和依赖

## 添加新测试
------------

### 步骤：
1. 在 test_factories.py 中添加测试数据工厂方法（如需要）
2. 创建 test_*.py 文件
3. 使用 Test* 类
4. 编写 test_* 方法
5. 使用 assert 进行断言
6. 对于异步测试添加 @pytest.mark.asyncio 装饰器

### 示例：
```python
import pytest
from tests.test_factories import YourFactory

class TestYourModule:
    @pytest.fixture
    def your_fixture(self):
        return YourClass()

    def test_your_function(self, your_fixture):
        test_data = YourFactory.create_test_data()
        result = your_fixture.your_function(test_data)
        assert result == expected_result
```

## 注意事项
--------

1. **私钥安全**：测试用私钥仅用于测试，切勿在生产环境使用
2. **异步测试**：异步测试必须添加 @pytest.mark.asyncio
3. **Mock外部依赖**：所有外部网络请求、RPC调用都需要Mock，避免测试依赖外部服务
4. **资源清理**：确保测试中创建的资源在测试结束后正确清理
5. **并发测试**：并发测试注意线程安全，避免测试之间相互影响
