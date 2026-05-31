# 自动化测试套件

本测试套件为可观测性平台的三个核心模块提供自动化测试保障：

## 测试模块

### 1. 分布式追踪采集模块 (`test_tracing_module.py`) - 28个测试用例
**聚焦：数据一致性保障**

- **Span收集测试**：单个Span、多Span同Trace、父子关系、错误状态、事件、高容量
- **数据一致性测试**：Trace检索一致性、Span ID唯一性、时长计算、空Trace处理、并发收集一致性、部分收集
- **头部采样测试**：全采样、不采样、服务过滤、Span名称过滤、禁用策略、概率采样
- **尾部采样测试**：仅错误采样、无错误不采样、最小时长阈值、组合条件、概率采样、多策略
- **Trace流水线测试**：完整生命周期、混合采样决策、最终化数据完整性、多Trace最终化

### 2. 通知模块 (`test_notification_module.py`) - 27个测试用例
**聚焦：并发隔离级别**

- **优先级测试**：优先级排序、高/中/低优先级路由、批量优先级排序
- **抑制策略测试**：按标签抑制、标签不匹配不抑制、按来源抑制、按优先级抑制、禁用规则、多规则
- **通道管理测试**：多通道添加、禁用通道不使用、无通道配置
- **并发隔离测试**：并发发送隔离、并发抑制隔离、通道计数准确性、并发批量处理、并发通道修改隔离
- **批量处理测试**：保留优先级顺序、空批量、大批次、混合抑制
- **边界情况测试**：无标签、空消息、部分标签匹配、ID唯一性

### 3. 持续性能剖析模块 (`test_profiling_module.py`) - 35个测试用例
**聚焦：超时降级行为**

- **会话管理测试**：CPU/Memory/Wall会话启动、停止、获取信息、不存在会话、类型持久化
- **超时降级测试**：超时长降级、超时拒绝、正常时长不降级、并发降级、并发拒绝、会话释放、行为切换
- **火焰图测试**：完成后生成、运行中不可用、不存在会话、格式验证、多图唯一
- **会话对比测试**：同类型、不同类型、运行与完成、时长差异、不存在会话
- **并发测试**：并发启动、并发停止、并发启停、并发访问火焰图
- **边界情况测试**：零时长、负时长、极短会话、ID唯一性、最大会话恢复、降级会话处理

## 测试数据构建器 (`builders.py`)

统一的测试数据构建模块，使用Builder模式：

- `TraceSpanBuilder` - 构建Trace Span数据
- `SamplingStrategyBuilder` - 构建采样策略
- `NotificationBuilder` - 构建通知
- `NotificationChannelBuilder` - 构建通知通道
- `SuppressionRuleBuilder` - 构建抑制规则
- `ProfilingSessionBuilder` - 构建性能剖析会话
- `MetricPointBuilder` - 构建指标数据点
- `AlertRuleBuilder` - 构建告警规则
- `TestDataGenerator` - 批量数据生成器

## Mock服务 (`conftest.py`)

提供三个核心模块的Mock实现：

- `MockTracingService` - 模拟追踪服务
- `MockNotificationService` - 模拟通知服务
- `MockProfilingService` - 模拟性能剖析服务

## 运行测试

```bash
# 安装依赖
cd tests
pip3 install -r requirements.txt

# 运行所有测试
python3 -m pytest -v

# 运行特定模块测试
python3 -m pytest test_tracing_module.py -v
python3 -m pytest test_notification_module.py -v
python3 -m pytest test_profiling_module.py -v

# 运行带覆盖率报告
python3 -m pytest --cov=. --cov-report=term-missing

# 运行特定标记的测试
python3 -m pytest -m tracing -v
python3 -m pytest -m notification -v
python3 -m pytest -m profiling -v
```

## 测试覆盖率

- 总代码行数：1,447
- 覆盖行数：1,380
- 覆盖率：95%
