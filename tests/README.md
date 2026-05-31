# 零信任网络访问控制器 - 测试套件

本目录包含零信任网络访问控制器的完整测试体系，使用 Jest + Supertest 进行黑盒API集成测试。

## 📁 目录结构

```
tests/
├── __tests__/                  # 测试用例目录
│   ├── tee.consistency.test.js      # TEE模块 - 数据一致性保障测试
│   ├── masking.isolation.test.js    # 数据脱敏模块 - 并发隔离级别测试
│   └── federated.timeout.test.js    # 联邦学习模块 - 超时降级行为测试
├── factories/                  # 测试数据工厂
│   ├── index.js                      # 工厂入口
│   ├── common.factory.js             # 通用数据生成器
│   ├── tee.factory.js                # TEE模块数据生成器
│   ├── masking.factory.js            # 脱敏模块数据生成器
│   └── federated.factory.js          # 联邦学习数据生成器
├── utils/                      # 测试工具类
│   └── api.client.js                 # API客户端封装
├── jest.config.js              # Jest配置
├── jest.setup.js               # 测试环境初始化
├── package.json                # 依赖配置
└── README.md                   # 本文档
```

## 🚀 快速开始

### 安装依赖

```bash
cd tests
npm install
```

### 配置环境变量

```bash
export API_BASE_URL=http://localhost:8080
```

### 运行测试

```bash
# 运行所有测试
npm test

# 运行单个模块测试
npm run test:tee           # TEE模块测试
npm run test:masking       # 数据脱敏模块测试
npm run test:federated     # 联邦学习模块测试

# CI环境运行
npm run test:ci
```

## 🧪 测试覆盖范围

### 1. 可信执行环境模块 - 数据一致性保障

**测试文件**: `__tests__/tee.consistency.test.js`

| 测试场景 | 描述 |
|---------|------|
| Enclave数据一致性 | 同一enclave多次查询结果一致性验证 |
| 并发创建一致性 | 并发enclave创建状态一致性 |
| 状态流转一致性 | enclave生命周期状态流转正确性 |
| 远程证明一致性 | 同一挑战多次证明结果一致性 |
| 安全函数执行一致性 | 相同输入多次执行结果一致性 |
| 并发执行数据隔离 | 并发安全函数执行数据互不干扰 |
| 心跳更新一致性 | 心跳更新后状态持久化一致性 |
| 二进制响应完整性 | 二进制响应校验和验证 |
| 签名防篡改 | 篡改数据后签名验证失败 |

### 2. 动态数据脱敏模块 - 并发隔离级别

**测试文件**: `__tests__/masking.isolation.test.js`

| 测试场景 | 描述 |
|---------|------|
| 权限级别隔离 | 4种权限级别(Admin/Full/Restricted/ReadOnly)脱敏策略验证 |
| 多用户并发隔离 | 不同权限用户同时访问同一数据结果互不干扰 |
| 单用户多数据隔离 | 同一用户并发访问不同数据隔离性 |
| 高并发规则一致性 | 高并发下脱敏规则应用一致性 |
| 批量处理隔离 | 批量数据处理并发隔离 |
| 字段级隔离 | 不同字段类型脱敏规则正确应用 |
| 部分字段脱敏 | 部分字段脱敏不影响其他字段 |
| 请求上下文隔离 | 不同请求上下文数据隔离 |
| 边缘场景 | 空数据、超大字段、特殊字符处理 |
| 压力测试 | 1000次请求脱敏结果一致性 |

### 3. 联邦学习协调模块 - 超时降级行为

**测试文件**: `__tests__/federated.timeout.test.js`

| 测试场景 | 描述 |
|---------|------|
| 参与者超时降级 | 部分参与者超时触发降级模式 |
| 全部超时失败 | 关键参与者全部超时任务失败 |
| 备用参与者激活 | 超时后自动启用备用参与者 |
| 渐进式超时调整 | 超时阈值动态调整策略 |
| 降级模式配置 | 降级模式下参数自动调整 |
| 聚合策略调整 | 降级模式下聚合策略自动选择 |
| 性能对比 | 正常模式vs降级模式性能指标对比 |
| 超时恢复 | 单轮超时后任务可继续执行 |
| 计数器重置 | 成功提交后超时计数器正确重置 |
| 审计日志 | 超时事件正确记录到审计日志 |
| 边界场景 | 零秒超时、超大超时值处理 |

## 🏭 测试数据工厂

所有测试数据通过工厂模式生成，避免在测试代码中硬编码：

### 通用工厂 (`common.factory.js`)
- `PermissionLevel` - 权限级别枚举
- `generateAuthContext()` - 生成认证上下文
- `generateSignedRequest()` - 生成签名请求
- 各种随机数据生成器

### TEE工厂 (`tee.factory.js`)
- `EnclaveStatus` - Enclave状态枚举
- `generateCreateEnclaveRequest()` - 生成enclave创建请求
- `generateAttestationRequest()` - 生成证明请求
- `generateTEETestCases()` - 生成测试场景数据

### 脱敏工厂 (`masking.factory.js`)
- `MaskingRuleType` - 脱敏规则类型
- `generateSensitiveDataRecord()` - 生成敏感数据记录
- `generateMaskingRequest()` - 生成脱敏请求
- `generateConcurrentTestScenarios()` - 生成并发测试场景

### 联邦学习工厂 (`federated.factory.js`)
- `TaskStatus` - 任务状态枚举
- `AggregationStrategy` - 聚合策略枚举
- `generateCreateTaskRequest()` - 生成任务创建请求
- `generateParticipantRegistration()` - 生成参与者注册
- `generateGradientSubmission()` - 生成梯度提交
- `generateTimeoutTestScenarios()` - 生成超时测试场景

## 🔧 工具类

### API客户端 (`utils/api.client.js`)

封装了所有API端点调用，支持：
- 自动添加请求ID和时间戳
- 请求/响应拦截器
- 错误日志记录
- 所有模块API方法封装

## 📊 测试报告

运行 `npm run test:ci` 会在 `reports/` 目录生成JUnit格式测试报告：

```
reports/
└── junit.xml
```

## ⚙️ 配置说明

### Jest配置 (`jest.config.js`)

- 测试超时: 30秒（联邦学习测试单独设置为300秒）
- 并发执行: 最大10个并发
- 测试匹配: `*.test.js` 和 `*.spec.js`
- 自定义匹配器:
  - `toBeValidApiResponse()` - 验证API响应格式
  - `toBeBetween(min, max)` - 验证数值范围
  - `toBeValidUuid()` - 验证UUID格式

## 💡 最佳实践

1. **数据驱动测试**: 使用 `test.each` 运行多组测试用例
2. **工厂模式**: 所有测试数据通过工厂生成，不硬编码
3. **清晰断言**: 每个测试有明确的预期结果
4. **详细日志**: 关键步骤输出日志便于调试
5. **错误处理**: 预期的错误场景也要测试覆盖
6. **并发安全**: 测试用例之间避免共享状态

## 🔍 调试技巧

1. 设置 `DEBUG=true` 查看详细请求日志
2. 使用 `.only` 运行单个测试用例
3. 使用 `.skip` 跳过暂时不运行的测试
4. 检查 `X-Request-Id` 进行请求追踪

## 📝 测试执行示例

```bash
# 安装依赖
cd tests && npm install

# 运行TEE模块测试
npm run test:tee

# 运行脱敏模块测试
npm run test:masking

# 运行联邦学习模块测试（需要较长时间）
npm run test:federated
```

## 🎯 覆盖率目标

| 模块 | 接口覆盖率 | 场景覆盖率 |
|------|-----------|-----------|
| TEE模块 | 90% | 100% |
| 脱敏模块 | 95% | 100% |
| 联邦学习模块 | 85% | 100% |
