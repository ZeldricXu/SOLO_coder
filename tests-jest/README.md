# LLMGateway 自动化测试套件

基于 Jest + Supertest 的 API 集成测试套件，重点保障三个核心模块的稳定性。

## 测试模块

### 1. 对抗样本生成模块 (`adversarial.test.js`)
**测试重点：边界条件处理**
- ✅ 基础功能验证（攻击策略列表、提示注入、越狱攻击）
- ✅ 输入长度边界测试（空提示词、超长提示词）
- ✅ 特殊字符处理（XSS、Unicode控制字符）
- ✅ 样本数量边界（0、负数、极大值）
- ✅ 攻击类型边界（未知类型、全类型支持验证）
- ✅ 批量攻击边界（空批量、大量提示词）
- ✅ 幂等性与一致性测试
- ✅ 最小请求测试

### 2. 特征存储服务模块 (`feature-store.test.js`)
**测试重点：事务回滚正确性**
- ✅ 基础功能验证（特征注册、查询、数据存储）
- ✅ 事务原子性（批量写入失败完全回滚）
- ✅ 并发写入冲突处理
- ✅ 部分失败时在线存储一致性
- ✅ 离线/在线数据一致性
- ✅ 一致性检查接口验证
- ✅ 边界条件测试（空名称、无效类型、负数TTL）
- ✅ 幂等性测试

### 3. Prompt实验管理模块 (`prompt-experiments.test.js`)
**测试重点：参数校验完备性**
- ✅ 基础功能验证（Prompt CRUD、实验创建）
- ✅ 必填字段校验（name、content、variables）
- ✅ 字段格式校验（空字符串、长度限制、特殊字符）
- ✅ 模板变量校验（未定义变量、重复变量、语法错误）
- ✅ 实验变体校验（数量限制、重复ID、流量权重）
- ✅ 流量分配校验（负数、超限、总和归一化）
- ✅ 时间配置校验（结束时间早于开始时间）
- ✅ 统计参数校验（置信水平、样本大小）
- ✅ 路由请求校验（一致性验证）
- ✅ 指标记录校验（边界值、NaN处理）
- ✅ 复杂场景（50个变量、20个变体）

## 项目结构

```
tests-jest/
├── package.json              # 依赖配置
├── jest.config.js           # Jest配置
├── README.md                 # 本文档
└── tests/
    ├── setup.js              # 全局测试配置
    ├── config.js             # API端点配置
    ├── builders/             # 测试数据Builder模块
    │   ├── index.js
    │   ├── TestDataBuilder.js       # 基础Builder基类
    │   ├── AdversarialDataBuilder.js # 对抗样本测试数据
    │   ├── FeatureStoreDataBuilder.js # 特征存储测试数据
    │   └── PromptExperimentDataBuilder.js # Prompt实验测试数据
    ├── helpers/              # 测试辅助函数
    │   ├── index.js
    │   ├── apiClient.js      # API客户端封装（含重试）
    │   └── testUtils.js      # 通用测试工具函数
    ├── adversarial.test.js     # 对抗样本生成模块测试
    ├── feature-store.test.js   # 特征存储服务模块测试
    └── prompt-experiments.test.js # Prompt实验管理模块测试
```

## 快速开始

### 1. 安装依赖

```bash
cd tests-jest
npm install
```

### 2. 启动后端服务

测试套件通过HTTP调用后端API，需要先启动LLMGateway服务：

```bash
cd ..
pip install -r requirements.txt
python main.py
```

服务默认运行在 `http://localhost:8080`

### 3. 运行测试

```bash
# 运行所有测试
npm test

# 运行指定模块测试
npm run test:adversarial    # 对抗样本生成模块
npm run test:feature-store  # 特征存储服务模块
npm run test:prompt         # Prompt实验管理模块

# 监听模式
npm run test:watch

# 生成覆盖率报告
npm run test:coverage
```

### 4. 环境变量配置

```bash
# 可选：配置API地址
export API_BASE_URL=http://localhost:8080
export API_TIMEOUT=30000
export RETRY_ATTEMPTS=3
export RETRY_DELAY=1000
```

## 测试报告

测试运行后会在 `coverage/` 目录生成：
- `test-report.html` - HTML格式测试报告
- `lcov-report/` - LCOV覆盖率报告
- `clover.xml` - Clover格式报告

## 设计理念

### Builder模式
测试数据构造统一使用Builder模式，便于：
- 数据构造逻辑复用
- 边界条件数据统一管理
- 测试用例更清晰易读

```javascript
// 示例：使用Builder构造测试数据
const request = adversarialBuilder.buildPromptInjectionRequest({
  target_prompt: '测试内容',
  num_samples: 3,
});
```

### 防御性测试
所有测试用例都设计为防御性编程：
- 预期响应状态可能为多个有效值
- 服务不可用时优雅降级
- 资源自动清理

### 性能监控
关键操作自动记录执行时间，便于发现性能退化：

```javascript
const { result, durationMs } = await measureExecutionTime(() =>
  apiClient.adversarial.generate(request)
);
console.log(`耗时: ${durationMs}ms`);
```

## 扩展新测试

### 添加新的测试文件
1. 在 `tests/` 目录创建 `xxx.test.js`
2. 使用 `describe` 组织测试套件
3. 使用 Builder 构造测试数据
4. 使用 `apiClient` 调用API

### 添加新的Builder
1. 在 `tests/builders/` 目录创建 `XxxDataBuilder.js`
2. 继承 `TestDataBuilder`
3. 在 `index.js` 中导出

## 注意事项

1. **服务依赖**：测试需要后端服务运行，建议在CI/CD中配套使用
2. **数据清理**：测试创建的数据可能残留，建议使用独立测试环境
3. **超时设置**：默认30秒超时，复杂场景可按需调整
4. **重试机制**：API客户端内置3次重试，避免网络波动导致的假失败
