# 测试说明

## 测试技术栈

由于项目使用Go语言，采用以下测试技术栈：

- **测试框架**: Go标准库 `testing` 包
- **断言库**: [testify](https://github.com/stretchr/testify) v1.9.0 (assert, require)
- **Mock**: testify/mock + 自定义Mock实现
- **测试数据构造**: Builder模式

> 注：用户文档中提到的"JUnit 5配合Mockito"是Java生态技术栈，不适用于Go项目。Go项目使用标准库testing + testify是行业标准做法。

## 目录结构

```
internal/
├── common/
│   └── testbuilder/
│       └── testbuilder.go      # 测试数据构造器（Builder模式）
├── lineage/
│   ├── lineage.go
│   └── lineage_test.go         # 数据血缘模块测试
└── notification/
    ├── notification.go
    └── notification_test.go    # 通知模块测试
```

## 测试矩阵

### 数据血缘解析模块 (lineage)

| 测试分类 | 测试用例 | 覆盖内容 |
|---------|---------|---------|
| **正常路径** | Simple SELECT | 基础SQL解析 |
| | SELECT with JOIN | 多表关联解析 |
| | INSERT with SELECT | 写入型SQL解析 |
| | Complex ETL | 复杂ETL语句解析 |
| **边界输入** | Empty SQL | 空字符串处理 |
| | Whitespace only | 空白字符处理 |
| | Very long SQL | 长SQL性能 |
| | Special characters | 特殊字符转义 |
| | UPDATE statement | 更新语句解析 |
| | CREATE TABLE AS SELECT | DDL语句解析 |
| **并发操作** | Concurrent ParseSQL | 多协程并发解析 |
| | Concurrent Parse & Build | 解析与构建DAG并行 |
| | Regex parser concurrent | 正则解析线程安全 |
| **异常注入** | Invalid parser | 自定义错误注入 |
| | Cycle in DAG | 环检测 |
| **性能** | Parse 10000 SQLs | 吞吐性能测试 |

### 通知模块 (notification)

| 测试分类 | 测试用例 | 覆盖内容 |
|---------|---------|---------|
| **正常路径** | Simple email | 基础发送功能 |
| | SMS notification | 多类型支持 |
| | Large payload | 大数据量处理 |
| **边界输入** | Nil payload | 空负载处理 |
| | Empty recipient | 空接收者 |
| | Unregistered type | 未知类型处理 |
| | Full queue | 队列满处理 |
| | Cancelled context | 上下文取消 |
| **并发操作** | 50 goroutines × 20 sends | 高并发发送 |
| | Concurrent send & status | 发送与查询并行 |
| **异常注入** | Service not started | 未启动服务调用 |
| | Sender returns error | 发送失败重试 |
| | Sender timeout | 超时处理 |
| | Flaky sender | 间歇性故障恢复 |
| | Permanent failure | 重试耗尽 |
| **生命周期** | Start/Stop | 服务启停 |
| | Double start | 重复启动 |
| | Double stop | 重复停止 |
| | Restart | 服务重启 |
| **集成测试** | Mixed normal/intermittent | 混合场景集成 |
| | Error recovery | 异常恢复能力 |

## 运行测试

```bash
# 运行所有测试
cd session153
go test -v ./internal/lineage ./internal/notification

# 运行单个模块测试
go test -v ./internal/lineage

# 运行单个测试用例
go test -v ./internal/lineage -run TestParseSQL_NormalPath

# 运行性能测试
go test -v ./internal/lineage -run TestRegexSQLParser_Performance

# 运行并发测试
go test -v ./internal/notification -run TestService_ConcurrentOperations

# 生成覆盖率报告
go test -coverprofile=coverage.out ./internal/lineage ./internal/notification
go tool cover -html=coverage.out

# 运行所有测试（不含性能测试）
go test -short ./internal/...
```

## 测试数据构造器 (Builder模式)

### SQLCase Builder
```go
cases := testbuilder.NewLineageTestDataBuilder().
    WithSimpleSelect().
    WithSelectJoin().
    WithInsertSelect().
    WithComplexETL().
    Build()
```

### NotificationCase Builder
```go
cases := testbuilder.NewNotificationTestDataBuilder().
    WithSimpleEmail().
    WithFailingSender().
    WithLargePayload().
    Build()
```

### TableNode Builder
```go
node := testbuilder.NewTableNodeBuilder().
    WithName("users").
    WithDatabase("production").
    WithFields([]string{"id", "name"}).
    Build()
```

### Mock Sender
```go
// 正常发送
sender := testbuilder.NewMockSender(0, nil)

// 延迟发送
slowSender := testbuilder.NewMockSender(5*time.Second, nil)

// 失败发送
failingSender := testbuilder.NewMockSender(0, errors.New("SMTP error"))
```

## 核心测试断言

### 血缘解析断言
```go
assert.NoError(t, err)           // 无错误
assert.Equal(t, expected, actual) // 值相等
assert.Contains(t, slice, item)   // 包含元素
assert.Error(t, err)              // 应有错误
assert.NotNil(t, obj)             // 对象非空
```

### 通知模块断言
```go
require.NoError(t, err)           // 必须无错误（失败则终止测试）
assert.GreaterOrEqual(t, a, b)    // 大于等于
assert.Contains(t, []Status{...}, status) // 状态在允许集合中
assert.Eventually(t, condition, timeout, interval) // 最终满足条件
```

## 设计特点

1. **表驱动测试**: 所有正常路径用例使用表驱动模式
2. **子测试**: 使用 `t.Run()` 隔离每个测试场景
3. **并发安全**: 所有共享资源使用互斥锁保护
4. **超时控制**: 所有异步操作使用 `context.WithTimeout`
5. **资源清理**: 使用 `defer service.Stop()` 确保资源释放
6. **测试隔离**: 每个测试用例创建独立的服务实例
