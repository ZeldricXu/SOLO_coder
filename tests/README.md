# StreamSQL 单元测试

## 测试结构

```
tests/
├── conftest.py              # pytest 配置与插件加载
├── fixtures/
│   ├── __init__.py
│   └── test_data_builder.py  # 测试数据构建器（与测试逻辑分离）
└── unit/
    ├── __init__.py
    ├── test_query_module.py      # 流式查询解析模块测试
    ├── test_lineage_module.py    # 数据血缘解析模块测试
    └── test_lifecycle_module.py  # 数据生命周期管理模块测试
```

## 运行测试

```bash
cd session288
pip install -r requirements.txt
pip install pytest

# 运行全部测试
pytest tests/ -v

# 运行特定模块测试
pytest tests/unit/test_query_module.py -v
pytest tests/unit/test_lineage_module.py -v
pytest tests/unit/test_lifecycle_module.py -v

# 生成覆盖率报告
pytest tests/ -v --cov=src --cov-report=html

# 运行并发测试（可能需要更长时间）
pytest tests/unit/test_lineage_module.py::TestLineageConcurrency -v
```

## 测试重点

### 1. 流式查询解析模块（`test_query_module.py`）

**正常流程测试：**
- 简单 SELECT 查询解析
- 含窗口函数的查询（TUMBLING/HOPPING/SLIDING/SESSION）
- 含 JOIN 的查询解析
- INSERT/CTAS/SELECT 语句支持
- 聚合函数、GROUP BY、ORDER BY、LIMIT
- 逻辑计划构建
- 物理计划翻译
- 查询优化器规则应用
- 完整查询管道执行

**异常流程测试：**
- 无效 SQL 语法处理
- 空 SQL 字符串
- None SQL 输入
- 窗口定义缺少 SIZE 参数
- 查询缺少数据源

### 2. 数据血缘解析模块（`test_lineage_module.py`）

**并发操作安全性测试：**
- 多线程并发 SQL 解析
- 多线程并发 DAG 构建
- 共享 Parser 实例线程安全
- 共享 DAG Builder 实例线程安全
- 并发上下游查询
- 并发影响分析
- 并发导出操作（DOT/JSON）
- 无竞态条件的图操作
- 多 schema 并发解析

**功能测试：**
- 单 SQL 血缘解析
- 多 SQL 链式血缘构建
- 列级血缘提取
- DAG 有向无环图构建
- 上下游节点追踪
- 影响分析
- 拓扑排序
- DOT/JSON 导出

### 3. 数据生命周期管理模块（`test_lifecycle_module.py`）

**资源释放完整性测试：**
- Tiering Manager 内存清理验证（weakref 验证）
- Archiver 内存清理验证
- Cleanup Manager 内存清理验证
- Task 对象垃圾回收验证
- 错误场景下的文件句柄释放
- 并发归档的资源管理
- Hook 回调资源清理
- 循环引用垃圾回收

**分层迁移测试：**
- Hot → Cold 数据迁移策略
- Cold → Archive 数据迁移策略
- 自定义策略添加/删除
- 策略条件过滤（最小尺寸、最大行数等）
- 回调机制

**归档清理测试：**
- 数据归档流程
- 归档数据恢复
- 过期数据清理
- 钩子执行（前置/后置）
- 钩子异常容错
- 幂等性验证

## 测试数据构建器

测试数据构建器（`TestDataBuilder`）将测试数据构造与测试逻辑完全分离：

```python
# 使用示例
def test_something(data_builder):
    # 构造查询测试数据
    query_data = data_builder.build_valid_select_query(with_window=True)
    
    # 构造生命周期测试数据
    table_stats = data_builder.build_lifecycle_table_stats(age_days=45)
    
    # 构造血缘测试数据
    lineage_data = data_builder.build_lineage_multi_sql()
```

支持的构造方法：
- `build_valid_select_query()` - 构建有效 SELECT 查询
- `build_valid_insert_query()` - 构建 INSERT 查询
- `build_valid_create_stream_query()` - 构建 CREATE STREAM
- `build_invalid_syntax_queries()` - 构建无效 SQL
- `build_window_queries_with_variations()` - 各种窗口类型
- `build_lineage_single_sql()` / `build_lineage_multi_sql()` - 血缘 SQL
- `build_lifecycle_table_stats()` - 生命周期表统计
- `build_sample_records()` - 示例记录生成
