# 混沌工程实验编排平台 - 测试套件

## 测试架构

本测试套件使用 `pytest` + `unittest.mock` 构建，覆盖故障注入编排模块和命令溯源与审计模块。

## 测试分类

### 1. 正常业务流程测试
- `test_chaos_flow.py` - 故障注入模块正常流程
- `test_audit_flow.py` - 命令审计模块正常流程

### 2. 关键边界值测试
- `test_chaos_boundaries.py` - 故障注入模块边界测试
- `test_audit_boundaries.py` - 命令审计模块边界测试

### 3. 并发安全测试
- `test_chaos_concurrent.py` - 故障注入模块并发测试
- `test_audit_concurrent.py` - 命令审计模块并发测试

### 4. 外部依赖超时降级测试
- `test_chaos_timeout.py` - 故障注入模块超时降级
- `test_audit_timeout.py` - 命令审计模块超时降级

## 快速开始

### 安装依赖

```bash
cd tests
pip install -r requirements.txt
```

### 运行测试

```bash
# 运行所有测试
pytest

# 运行特定模块测试
pytest -m chaos
pytest -m audit

# 运行特定类型测试
pytest -m boundary
pytest -m concurrent
pytest -m timeout

# 运行特定文件
pytest test_chaos_flow.py

# 生成测试报告
pytest --cov=../src --cov-report=html

# 并行运行测试
pytest -n auto

# 运行并生成JUnit报告
pytest --junitxml=test-results.xml
```

### 标记说明

| 标记 | 说明 |
|------|------|
| `unit` | 单元测试 |
| `integration` | 集成测试 |
| `e2e` | 端到端测试 |
| `chaos` | 故障注入模块测试 |
| `audit` | 命令审计模块测试 |
| `boundary` | 边界值测试 |
| `concurrent` | 并发安全测试 |
| `timeout` | 超时降级测试 |
| `slow` | 慢速测试 |

## 测试配置

### 环境变量

```bash
# 测试服务地址
export TEST_BASE_URL=http://localhost:3000

# 运行模式
export TEST_MODE=integration  # integration | unit | mock
```

### pytest 配置

配置文件：`pytest.ini`

- 默认超时：30秒
- 并发执行：支持 pytest-xdist
- 覆盖率阈值：70%

## 测试数据

测试使用 `faker` 库生成随机测试数据，确保测试的独立性和可重复性。

### 测试夹具

- `config` - 测试配置
- `api_client` - HTTP 客户端
- `chaos_scenario_data` - 故障场景测试数据
- `command_data` - 命令测试数据
- `audit_log_data` - 审计日志测试数据
- `mock_api_client` - Mock API 客户端

## 测试覆盖范围

### 故障注入编排模块

| 功能 | 正常流程 | 边界值 | 并发 | 超时降级 |
|------|---------|--------|------|---------|
| 创建场景 | ✅ | ✅ | ✅ | ✅ |
| 获取场景 | ✅ | ✅ | ✅ | ✅ |
| 列表场景 | ✅ | ✅ | ✅ | ✅ |
| 更新场景 | ✅ | ✅ | ✅ | ✅ |
| 删除场景 | ✅ | ✅ | ✅ | ✅ |
| 开始注入 | ✅ | ✅ | ✅ | ✅ |
| 获取注入 | ✅ | ✅ | ✅ | ✅ |
| 回滚注入 | ✅ | ✅ | ✅ | ✅ |
| 完整生命周期 | ✅ | ❌ | ❌ | ✅ |

### 命令溯源与审计模块

| 功能 | 正常流程 | 边界值 | 并发 | 超时降级 |
|------|---------|--------|------|---------|
| 创建命令 | ✅ | ✅ | ✅ | ✅ |
| 获取命令 | ✅ | ✅ | ✅ | ✅ |
| 列表命令 | ✅ | ✅ | ✅ | ✅ |
| 聚合命令 | ✅ | ✅ | ✅ | ✅ |
| 创建审计日志 | ✅ | ✅ | ✅ | ✅ |
| 获取审计日志 | ✅ | ✅ | ✅ | ✅ |
| 列表审计日志 | ✅ | ✅ | ✅ | ✅ |
| 生成合规报告 | ✅ | ✅ | ✅ | ✅ |
| 命令审计关联 | ✅ | ❌ | ✅ | ✅ |
| 完整审计追踪 | ✅ | ❌ | ❌ | ✅ |

## 测试模式

### 1. 集成测试模式（默认）

需要启动 ChaosLab 服务：

```bash
# 启动服务
cd ..
npm run start:dev

# 运行测试
cd tests
pytest -m integration
```

### 2. Mock 测试模式

不需要启动服务，使用 Mock 对象：

```bash
pytest -m unit
```

## 测试最佳实践

1. **独立性**：每个测试用例应该独立运行，不依赖其他测试
2. **可重复性**：测试应该在任何环境下都能得到相同结果
3. **原子性**：每个测试只验证一个功能点
4. **清晰性**：测试代码应该易于理解和维护
5. **性能**：测试应该快速运行，避免不必要的等待

## 故障注入测试场景

### 网络故障
- 网络延迟（100ms - 5000ms）
- 数据包丢失（1% - 100%）
- 网络分区

### 资源耗尽
- CPU 压力测试
- 内存泄漏模拟
- 磁盘 IO 延迟

### 服务故障
- 服务进程终止
- 服务无响应
- 数据库连接断开

## 命令溯源测试场景

### 命令生命周期
- 命令创建与持久化
- 命令查询与检索
- 命令版本控制

### 审计追踪
- 操作日志记录
- 操作人追踪
- 操作时间戳
- 操作前后状态对比

### 合规报告
- 指定时间范围报告
- 指定操作人报告
- 指定资源报告
- 多格式导出（JSON/CSV/PDF）

## CI/CD 集成

测试套件已配置为在 GitHub Actions 中自动运行：

- 每次 PR 自动运行单元测试
- 合并到 main 分支运行集成测试
- 每晚运行完整测试套件
- 定期运行安全扫描

## 调试技巧

### 打印测试详情

```bash
pytest -v -s
```

### 运行失败的测试

```bash
pytest --lf  # 运行上次失败的测试
pytest --ff  # 先运行上次失败的测试
```

### 调试测试代码

```bash
pytest --pdb  # 失败时进入调试器
```

### 查看慢测试

```bash
pytest --durations=10  # 显示最慢的10个测试
```
