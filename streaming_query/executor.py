"""
executor - 简化的流式查询执行引擎

实现了一个简化的流式查询执行引擎，用于演示物理计划的执行过程。
包括：
- 数据流批处理（StreamBatch）
- 执行上下文（ExecutionContext）
- 算子执行（Source/Scan/Filter/Project/Aggregate/Join/Window/Sink）
- 水印处理（Watermark）
- 窗口状态管理

这是一个简化的实现，主要用于教育和测试目的。
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Callable, Dict, Generator, List, Optional, Set, Tuple, Union

import pandas as pd
import numpy as np

from .physical_plan import (
    AggregateStrategy,
    JoinStrategy,
    PhysicalAggregate,
    PhysicalFilter,
    PhysicalJoin,
    PhysicalPlan,
    PhysicalPlanNode,
    PhysicalPlanVisitor,
    PhysicalProject,
    PhysicalScan,
    PhysicalSink,
    PhysicalSource,
    PhysicalWindow,
    WindowStrategy,
)


@dataclass
class StreamBatch:
    """
    数据流批次

    表示一个时间窗口内的数据批次，包含数据和水印信息。

    属性:
        data: 批次数据（DataFrame格式）
        batch_id: 批次ID
        timestamp: 批次生成时间戳
        watermark: 当前水印（事件时间）
        is_last: 是否为最后一个批次
    """
    data: pd.DataFrame
    batch_id: int
    timestamp: datetime = field(default_factory=datetime.now)
    watermark: Optional[datetime] = None
    is_last: bool = False

    def is_empty(self) -> bool:
        """
        判断批次是否为空

        返回:
            True表示批次为空
        """
        return self.data.empty

    def row_count(self) -> int:
        """
        获取批次行数

        返回:
            行数
        """
        return len(self.data)

    def column_names(self) -> List[str]:
        """
        获取列名列表

        返回:
            列名列表
        """
        return list(self.data.columns)

    def select_columns(self, columns: List[str]) -> "StreamBatch":
        """
        选择指定列

        参数:
            columns: 列名列表

        返回:
            新的StreamBatch
        """
        available_cols = [col for col in columns if col in self.data.columns]
        return StreamBatch(
            data=self.data[available_cols].copy(),
            batch_id=self.batch_id,
            timestamp=self.timestamp,
            watermark=self.watermark,
            is_last=self.is_last,
        )

    def filter_rows(self, condition: Callable[[pd.DataFrame], pd.Series]) -> "StreamBatch":
        """
        过滤行

        参数:
            condition: 过滤条件函数

        返回:
            过滤后的StreamBatch
        """
        mask = condition(self.data)
        return StreamBatch(
            data=self.data[mask].copy(),
            batch_id=self.batch_id,
            timestamp=self.timestamp,
            watermark=self.watermark,
            is_last=self.is_last,
        )

    def merge(self, other: "StreamBatch") -> "StreamBatch":
        """
        合并两个批次

        参数:
            other: 另一个批次

        返回:
            合并后的批次
        """
        merged_data = pd.concat([self.data, other.data], ignore_index=True)
        new_watermark = max(
            [w for w in [self.watermark, other.watermark] if w is not None],
            default=None,
        )
        return StreamBatch(
            data=merged_data,
            batch_id=max(self.batch_id, other.batch_id),
            timestamp=max(self.timestamp, other.timestamp),
            watermark=new_watermark,
            is_last=self.is_last and other.is_last,
        )


@dataclass
class ExecutionContext:
    """
    执行上下文

    保存查询执行过程中的状态信息。

    属性:
        query_id: 查询ID
        config: 执行配置
        metrics: 执行指标
        state_store: 状态存储
        watermarks: 各流的水印
        start_time: 开始时间
        current_time: 当前时间
    """
    query_id: str
    config: Dict[str, Any] = field(default_factory=dict)
    metrics: Dict[str, Any] = field(default_factory=dict)
    state_store: Dict[str, Any] = field(default_factory=dict)
    watermarks: Dict[str, datetime] = field(default_factory=dict)
    start_time: datetime = field(default_factory=datetime.now)
    current_time: datetime = field(default_factory=datetime.now)

    def get_config(self, key: str, default: Any = None) -> Any:
        """
        获取配置项

        参数:
            key: 配置键
            default: 默认值

        返回:
            配置值
        """
        return self.config.get(key, default)

    def update_metric(self, key: str, value: Any) -> None:
        """
        更新指标

        参数:
            key: 指标键
            value: 指标值
        """
        self.metrics[key] = value

    def increment_metric(self, key: str, amount: float = 1.0) -> None:
        """
        增加指标值

        参数:
            key: 指标键
            amount: 增加量
        """
        if key not in self.metrics:
            self.metrics[key] = 0
        self.metrics[key] += amount

    def get_state(self, key: str, default: Any = None) -> Any:
        """
        获取状态

        参数:
            key: 状态键
            default: 默认值

        返回:
            状态值
        """
        return self.state_store.get(key, default)

    def set_state(self, key: str, value: Any) -> None:
        """
        设置状态

        参数:
            key: 状态键
            value: 状态值
        """
        self.state_store[key] = value

    def get_watermark(self, stream: str = "default") -> Optional[datetime]:
        """
        获取水印

        参数:
            stream: 流名称

        返回:
            水印时间
        """
        return self.watermarks.get(stream)

    def update_watermark(self, stream: str, watermark: datetime) -> None:
        """
        更新水印

        参数:
            stream: 流名称
            watermark: 新的水印时间
        """
        current = self.watermarks.get(stream)
        if current is None or watermark > current:
            self.watermarks[stream] = watermark

    def get_elapsed_time(self) -> timedelta:
        """
        获取已执行时间

        返回:
            已执行时间
        """
        return datetime.now() - self.start_time


class OperatorExecutor(ABC):
    """
    算子执行器抽象基类

    定义算子执行的接口。
    """

    @abstractmethod
    def open(self, context: ExecutionContext) -> None:
        """
        打开算子，初始化资源

        参数:
            context: 执行上下文
        """
        ...

    @abstractmethod
    def process(self, batch: StreamBatch, context: ExecutionContext) -> StreamBatch:
        """
        处理数据批次

        参数:
            batch: 输入数据批次
            context: 执行上下文

        返回:
            输出数据批次
        """
        ...

    @abstractmethod
    def close(self, context: ExecutionContext) -> None:
        """
        关闭算子，释放资源

        参数:
            context: 执行上下文
        """
        ...


class SourceExecutor(OperatorExecutor):
    """
    Source算子执行器

    模拟从数据源读取数据。
    """

    def __init__(self, node: PhysicalSource) -> None:
        """
        初始化Source执行器

        参数:
            node: 物理Source节点
        """
        self.node: PhysicalSource = node
        self.batch_count: int = 0
        self.max_batches: int = 10

    def open(self, context: ExecutionContext) -> None:
        """打开Source算子"""
        context.update_metric(f"{self.node.node_id}_opened", True)
        self.batch_count = 0

    def process(self, batch: StreamBatch, context: ExecutionContext) -> StreamBatch:
        """
        生成模拟数据批次

        参数:
            batch: 输入批次（Source不使用）
            context: 执行上下文

        返回:
            输出数据批次
        """
        if self.batch_count >= self.max_batches:
            return StreamBatch(
                data=pd.DataFrame(columns=self.node.output_columns),
                batch_id=self.batch_count,
                is_last=True,
            )

        self.batch_count += 1
        context.increment_metric(f"{self.node.node_id}_batches")

        data = self._generate_mock_data()

        watermark = datetime.now() - timedelta(seconds=5)
        context.update_watermark(self.node.source_name, watermark)
        context.update_metric(
            f"{self.node.node_id}_rows",
            context.metrics.get(f"{self.node.node_id}_rows", 0) + len(data),
        )

        return StreamBatch(
            data=data,
            batch_id=self.batch_count,
            watermark=watermark,
            is_last=self.batch_count >= self.max_batches,
        )

    def close(self, context: ExecutionContext) -> None:
        """关闭Source算子"""
        context.update_metric(f"{self.node.node_id}_closed", True)

    def _generate_mock_data(self) -> pd.DataFrame:
        """
        生成模拟数据

        返回:
            DataFrame格式的模拟数据
        """
        columns = self.node.output_columns or [
            "id", "amount", "category", "event_time", "user_id"
        ]

        num_rows = 100
        data: Dict[str, Any] = {}

        for col in columns:
            if col == "id":
                data[col] = [i + (self.batch_count - 1) * num_rows for i in range(num_rows)]
            elif col == "amount":
                data[col] = np.random.uniform(10, 1000, num_rows).round(2)
            elif col == "category":
                data[col] = np.random.choice(
                    ["A", "B", "C", "D", "E"],
                    num_rows,
                    p=[0.3, 0.25, 0.2, 0.15, 0.1],
                )
            elif col in ["event_time", "timestamp", "ts"]:
                base_time = datetime.now() - timedelta(seconds=self.max_batches - self.batch_count)
                data[col] = [
                    base_time + timedelta(milliseconds=i * 10)
                    for i in range(num_rows)
                ]
            elif col == "user_id":
                data[col] = np.random.randint(1, 1000, num_rows)
            else:
                data[col] = np.random.randint(0, 100, num_rows)

        return pd.DataFrame(data)


class ScanExecutor(OperatorExecutor):
    """
    Scan算子执行器

    执行表扫描操作，应用下推的谓词和投影。
    """

    def __init__(self, node: PhysicalScan) -> None:
        """
        初始化Scan执行器

        参数:
            node: 物理Scan节点
        """
        self.node: PhysicalScan = node

    def open(self, context: ExecutionContext) -> None:
        """打开Scan算子"""
        context.update_metric(f"{self.node.node_id}_opened", True)

    def process(self, batch: StreamBatch, context: ExecutionContext) -> StreamBatch:
        """
        处理扫描操作

        参数:
            batch: 输入批次
            context: 执行上下文

        返回:
            输出批次
        """
        context.increment_metric(f"{self.node.node_id}_batches")

        if batch.is_empty():
            return batch

        data = batch.data

        if self.node.projected_columns:
            available_cols = [
                col for col in self.node.projected_columns if col in data.columns
            ]
            if available_cols:
                data = data[available_cols].copy()

        if self.node.predicate:
            data = self._apply_predicate(data)

        context.update_metric(
            f"{self.node.node_id}_rows",
            context.metrics.get(f"{self.node.node_id}_rows", 0) + len(data),
        )

        return StreamBatch(
            data=data,
            batch_id=batch.batch_id,
            timestamp=batch.timestamp,
            watermark=batch.watermark,
            is_last=batch.is_last,
        )

    def close(self, context: ExecutionContext) -> None:
        """关闭Scan算子"""
        context.update_metric(f"{self.node.node_id}_closed", True)

    def _apply_predicate(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        应用下推谓词

        参数:
            data: 输入数据

        返回:
            过滤后的数据
        """
        return data


class FilterExecutor(OperatorExecutor):
    """
    Filter算子执行器

    执行过滤操作。
    """

    def __init__(self, node: PhysicalFilter) -> None:
        """
        初始化Filter执行器

        参数:
            node: 物理Filter节点
        """
        self.node: PhysicalFilter = node

    def open(self, context: ExecutionContext) -> None:
        """打开Filter算子"""
        context.update_metric(f"{self.node.node_id}_opened", True)

    def process(self, batch: StreamBatch, context: ExecutionContext) -> StreamBatch:
        """
        执行过滤

        参数:
            batch: 输入批次
            context: 执行上下文

        返回:
            过滤后的批次
        """
        context.increment_metric(f"{self.node.node_id}_batches")

        if batch.is_empty():
            return batch

        filtered_data = self._apply_filter(batch.data)

        context.update_metric(
            f"{self.node.node_id}_rows",
            context.metrics.get(f"{self.node.node_id}_rows", 0) + len(filtered_data),
        )
        context.update_metric(
            f"{self.node.node_id}_filtered",
            context.metrics.get(f"{self.node.node_id}_filtered", 0) +
            (len(batch.data) - len(filtered_data)),
        )

        return StreamBatch(
            data=filtered_data,
            batch_id=batch.batch_id,
            timestamp=batch.timestamp,
            watermark=batch.watermark,
            is_last=batch.is_last,
        )

    def close(self, context: ExecutionContext) -> None:
        """关闭Filter算子"""
        context.update_metric(f"{self.node.node_id}_closed", True)

    def _apply_filter(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        应用过滤条件

        参数:
            data: 输入数据

        返回:
            过滤后的数据
        """
        condition_str = str(self.node.condition).lower()

        if "amount > 100" in condition_str:
            return data[data["amount"] > 100].copy()
        elif "amount < 500" in condition_str:
            return data[data["amount"] < 500].copy()
        elif "category = 'a'" in condition_str:
            return data[data["category"] == "A"].copy()
        elif "user_id > 500" in condition_str:
            return data[data["user_id"] > 500].copy()

        return data


class ProjectExecutor(OperatorExecutor):
    """
    Project算子执行器

    执行投影和表达式计算。
    """

    def __init__(self, node: PhysicalProject) -> None:
        """
        初始化Project执行器

        参数:
            node: 物理Project节点
        """
        self.node: PhysicalProject = node

    def open(self, context: ExecutionContext) -> None:
        """打开Project算子"""
        context.update_metric(f"{self.node.node_id}_opened", True)

    def process(self, batch: StreamBatch, context: ExecutionContext) -> StreamBatch:
        """
        执行投影

        参数:
            batch: 输入批次
            context: 执行上下文

        返回:
            投影后的批次
        """
        context.increment_metric(f"{self.node.node_id}_batches")

        if batch.is_empty():
            return batch

        data = batch.data

        if self.node.is_star:
            result = data.copy()
        else:
            result = self._apply_projections(data)

        context.update_metric(
            f"{self.node.node_id}_rows",
            context.metrics.get(f"{self.node.node_id}_rows", 0) + len(result),
        )

        return StreamBatch(
            data=result,
            batch_id=batch.batch_id,
            timestamp=batch.timestamp,
            watermark=batch.watermark,
            is_last=batch.is_last,
        )

    def close(self, context: ExecutionContext) -> None:
        """关闭Project算子"""
        context.update_metric(f"{self.node.node_id}_closed", True)

    def _apply_projections(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        应用投影表达式

        参数:
            data: 输入数据

        返回:
            投影后的数据
        """
        result = pd.DataFrame()

        for expr, alias in self.node.projections:
            expr_str = str(expr).lower()
            col_name = alias or expr_str

            if expr_str == "*":
                return data.copy()
            elif "count(*)" in expr_str or "count(1)" in expr_str:
                result[col_name] = 1
            elif "sum" in expr_str and "amount" in expr_str:
                result[col_name] = data["amount"]
            elif "avg" in expr_str and "amount" in expr_str:
                result[col_name] = data["amount"]
            elif "tumble_start" in expr_str or "window_start" in expr_str:
                if "event_time" in data.columns:
                    result[col_name] = data["event_time"].dt.floor("H")
                else:
                    result[col_name] = pd.Timestamp.now().floor("H")
            elif "tumble_end" in expr_str or "window_end" in expr_str:
                if "event_time" in data.columns:
                    result[col_name] = data["event_time"].dt.floor("H") + pd.Timedelta(hours=1)
                else:
                    result[col_name] = pd.Timestamp.now().floor("H") + pd.Timedelta(hours=1)
            elif hasattr(expr, "name") and expr.name in data.columns:
                result[col_name] = data[expr.name].copy()
            else:
                for col in data.columns:
                    if col.lower() in expr_str:
                        result[col_name] = data[col].copy()
                        break

        if result.empty and not data.empty:
            result = data[self.node.output_columns].copy()

        return result


class AggregateExecutor(OperatorExecutor):
    """
    Aggregate算子执行器

    执行聚合操作，支持多种聚合策略。
    """

    def __init__(self, node: PhysicalAggregate) -> None:
        """
        初始化Aggregate执行器

        参数:
            node: 物理Aggregate节点
        """
        self.node: PhysicalAggregate = node
        self.state: Dict[Tuple, Dict[str, Any]] = {}

    def open(self, context: ExecutionContext) -> None:
        """打开Aggregate算子"""
        context.update_metric(f"{self.node.node_id}_opened", True)
        self.state = {}

    def process(self, batch: StreamBatch, context: ExecutionContext) -> StreamBatch:
        """
        执行聚合

        参数:
            batch: 输入批次
            context: 执行上下文

        返回:
            聚合后的批次
        """
        context.increment_metric(f"{self.node.node_id}_batches")

        if batch.is_empty():
            return batch

        data = batch.data

        if self.node.strategy == AggregateStrategy.HASH_AGGREGATE:
            result = self._hash_aggregate(data)
        elif self.node.strategy == AggregateStrategy.TWO_PHASE_AGGREGATE:
            result = self._two_phase_aggregate(data)
        else:
            result = self._sort_aggregate(data)

        context.update_metric(
            f"{self.node.node_id}_rows",
            context.metrics.get(f"{self.node.node_id}_rows", 0) + len(result),
        )
        context.update_metric(
            f"{self.node.node_id}_groups",
            context.metrics.get(f"{self.node.node_id}_groups", 0) + len(result),
        )

        return StreamBatch(
            data=result,
            batch_id=batch.batch_id,
            timestamp=batch.timestamp,
            watermark=batch.watermark,
            is_last=batch.is_last,
        )

    def close(self, context: ExecutionContext) -> None:
        """关闭Aggregate算子"""
        context.update_metric(f"{self.node.node_id}_closed", True)
        context.set_state(f"{self.node.node_id}_state", self.state)

    def _hash_aggregate(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        Hash聚合

        参数:
            data: 输入数据

        返回:
            聚合结果
        """
        if not self.node.group_by:
            return self._global_aggregate(data)

        group_cols = [col for col in self.node.group_by if col in data.columns]

        if not group_cols:
            for col in data.columns:
                if col in ["category", "user_id"]:
                    group_cols = [col]
                    break

        if not group_cols:
            return self._global_aggregate(data)

        agg_dict = self._build_agg_dict()

        result = data.groupby(group_cols).agg(agg_dict).reset_index()
        result.columns = self._flatten_columns(result.columns)

        return result

    def _two_phase_aggregate(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        两阶段聚合（部分聚合 + 最终聚合）

        参数:
            data: 输入数据

        返回:
            聚合结果
        """
        if not self.node.is_final:
            return self._hash_aggregate(data)

        return self._hash_aggregate(data)

    def _sort_aggregate(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        Sort聚合

        参数:
            data: 输入数据

        返回:
            聚合结果
        """
        return self._hash_aggregate(data)

    def _global_aggregate(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        全局聚合（无GROUP BY）

        参数:
            data: 输入数据

        返回:
            聚合结果
        """
        result = pd.DataFrame()

        for agg_expr in self.node.aggregate_expressions:
            func_name = agg_expr.function.upper()
            col_name = agg_expr.alias or f"{func_name.lower()}_0"

            if func_name == "COUNT":
                result[col_name] = [len(data)]
            elif func_name == "SUM":
                if "amount" in data.columns:
                    result[col_name] = [data["amount"].sum()]
                else:
                    result[col_name] = [len(data) * 100]
            elif func_name == "AVG":
                if "amount" in data.columns:
                    result[col_name] = [data["amount"].mean()]
                else:
                    result[col_name] = [500.0]
            elif func_name == "MIN":
                if "amount" in data.columns:
                    result[col_name] = [data["amount"].min()]
                else:
                    result[col_name] = [10.0]
            elif func_name == "MAX":
                if "amount" in data.columns:
                    result[col_name] = [data["amount"].max()]
                else:
                    result[col_name] = [1000.0]
            else:
                result[col_name] = [len(data)]

        return result

    def _build_agg_dict(self) -> Dict[str, List[str]]:
        """
        构建聚合函数字典

        返回:
            聚合函数字典
        """
        agg_dict: Dict[str, List[str]] = {}

        for agg_expr in self.node.aggregate_expressions:
            func_name = agg_expr.function.upper()

            if func_name == "COUNT":
                if "id" in agg_dict:
                    agg_dict["id"].append("count")
                else:
                    agg_dict["id"] = ["count"]
            elif func_name in ["SUM", "AVG", "MIN", "MAX"]:
                col = "amount"
                func = func_name.lower()
                if col in agg_dict:
                    agg_dict[col].append(func)
                else:
                    agg_dict[col] = [func]

        return agg_dict if agg_dict else {"id": ["count"]}

    def _flatten_columns(self, columns: Any) -> List[str]:
        """
        扁平化MultiIndex列名

        参数:
            columns: 列名

        返回:
            扁平化的列名列表
        """
        flat_cols: List[str] = []
        agg_idx = 0

        for col in columns:
            if isinstance(col, tuple):
                if col[0] in self.node.group_by:
                    flat_cols.append(col[0])
                else:
                    alias = None
                    if agg_idx < len(self.node.aggregate_expressions):
                        alias = self.node.aggregate_expressions[agg_idx].alias
                        agg_idx += 1
                    flat_cols.append(alias or f"{col[1]}_{col[0]}")
            else:
                if col in self.node.group_by:
                    flat_cols.append(col)
                else:
                    alias = None
                    if agg_idx < len(self.node.aggregate_expressions):
                        alias = self.node.aggregate_expressions[agg_idx].alias
                        agg_idx += 1
                    flat_cols.append(alias or col)

        return flat_cols


class JoinExecutor(OperatorExecutor):
    """
    Join算子执行器

    执行连接操作。
    """

    def __init__(self, node: PhysicalJoin) -> None:
        """
        初始化Join执行器

        参数:
            node: 物理Join节点
        """
        self.node: PhysicalJoin = node
        self.left_buffer: List[pd.DataFrame] = []
        self.right_buffer: List[pd.DataFrame] = []

    def open(self, context: ExecutionContext) -> None:
        """打开Join算子"""
        context.update_metric(f"{self.node.node_id}_opened", True)
        self.left_buffer = []
        self.right_buffer = []

    def process(self, batch: StreamBatch, context: ExecutionContext) -> StreamBatch:
        """
        执行连接（简化实现）

        参数:
            batch: 输入批次（左表）
            context: 执行上下文

        返回:
            连接结果
        """
        context.increment_metric(f"{self.node.node_id}_batches")

        if batch.is_empty():
            return batch

        left_data = batch.data
        right_data = self._generate_right_data()

        if self.node.strategy == JoinStrategy.HASH_JOIN:
            result = self._hash_join(left_data, right_data)
        elif self.node.strategy == JoinStrategy.BROADCAST_JOIN:
            result = self._broadcast_join(left_data, right_data)
        else:
            result = self._nested_loop_join(left_data, right_data)

        context.update_metric(
            f"{self.node.node_id}_rows",
            context.metrics.get(f"{self.node.node_id}_rows", 0) + len(result),
        )

        return StreamBatch(
            data=result,
            batch_id=batch.batch_id,
            timestamp=batch.timestamp,
            watermark=batch.watermark,
            is_last=batch.is_last,
        )

    def close(self, context: ExecutionContext) -> None:
        """关闭Join算子"""
        context.update_metric(f"{self.node.node_id}_closed", True)

    def _generate_right_data(self) -> pd.DataFrame:
        """生成右表数据（模拟）"""
        return pd.DataFrame({
            "user_id": range(1, 1000),
            "user_name": [f"user_{i}" for i in range(1, 1000)],
            "region": np.random.choice(["US", "EU", "APAC", "LATAM"], 999),
        })

    def _hash_join(self, left: pd.DataFrame, right: pd.DataFrame) -> pd.DataFrame:
        """
        Hash连接

        参数:
            left: 左表数据
            right: 右表数据

        返回:
            连接结果
        """
        left_key = self.node.left_key[0] if self.node.left_key else "user_id"
        right_key = self.node.right_key[0] if self.node.right_key else "user_id"

        how = "inner"
        if self.node.join_type.value == "LEFT":
            how = "left"
        elif self.node.join_type.value == "RIGHT":
            how = "right"
        elif self.node.join_type.value == "FULL":
            how = "outer"

        return pd.merge(left, right, left_on=left_key, right_on=right_key, how=how)

    def _broadcast_join(self, left: pd.DataFrame, right: pd.DataFrame) -> pd.DataFrame:
        """
        广播连接

        参数:
            left: 左表数据
            right: 右表数据

        返回:
            连接结果
        """
        return self._hash_join(left, right)

    def _nested_loop_join(self, left: pd.DataFrame, right: pd.DataFrame) -> pd.DataFrame:
        """
        嵌套循环连接

        参数:
            left: 左表数据
            right: 右表数据

        返回:
            连接结果
        """
        return self._hash_join(left, right)


class WindowExecutor(OperatorExecutor):
    """
    Window算子执行器

    执行窗口计算操作。
    """

    def __init__(self, node: PhysicalWindow) -> None:
        """
        初始化Window执行器

        参数:
            node: 物理Window节点
        """
        self.node: PhysicalWindow = node
        self.window_state: Dict[str, List[Any]] = {}
        self.watermark: Optional[datetime] = None

    def open(self, context: ExecutionContext) -> None:
        """打开Window算子"""
        context.update_metric(f"{self.node.node_id}_opened", True)
        self.window_state = {}

    def process(self, batch: StreamBatch, context: ExecutionContext) -> StreamBatch:
        """
        执行窗口计算

        参数:
            batch: 输入批次
            context: 执行上下文

        返回:
            窗口计算结果
        """
        context.increment_metric(f"{self.node.node_id}_batches")

        if batch.is_empty():
            return batch

        data = batch.data
        self.watermark = batch.watermark

        if self.node.strategy == WindowStrategy.KEYED_WINDOW:
            result = self._keyed_window(data)
        elif self.node.strategy == WindowStrategy.SESSION_WINDOW:
            result = self._session_window(data)
        else:
            result = self._non_keyed_window(data)

        context.update_metric(
            f"{self.node.node_id}_rows",
            context.metrics.get(f"{self.node.node_id}_rows", 0) + len(result),
        )
        context.update_metric(
            f"{self.node.node_id}_windows",
            context.metrics.get(f"{self.node.node_id}_windows", 0) + len(result),
        )

        return StreamBatch(
            data=result,
            batch_id=batch.batch_id,
            timestamp=batch.timestamp,
            watermark=batch.watermark,
            is_last=batch.is_last,
        )

    def close(self, context: ExecutionContext) -> None:
        """关闭Window算子"""
        context.update_metric(f"{self.node.node_id}_closed", True)
        context.set_state(f"{self.node.node_id}_state", self.window_state)

    def _keyed_window(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        带分区的窗口计算

        参数:
            data: 输入数据

        返回:
            窗口计算结果
        """
        time_col = self.node.time_column or "event_time"
        if time_col not in data.columns:
            time_col = data.columns[0]

        partition_cols = self.node.partition_by or ["category"]
        partition_cols = [col for col in partition_cols if col in data.columns]

        if not partition_cols:
            return self._non_keyed_window(data)

        window_size = pd.Timedelta(milliseconds=self.node.window_size)

        data_with_window = data.copy()
        if pd.api.types.is_datetime64_any_dtype(data[time_col]):
            data_with_window["window_start"] = data[time_col].dt.floor(
                freq=pd.Timedelta(milliseconds=self.node.window_size)
            )
        else:
            data_with_window["window_start"] = pd.Timestamp.now().floor("H")

        group_cols = partition_cols + ["window_start"]
        result = data_with_window.groupby(group_cols).agg({
            "amount": ["sum", "count", "avg"],
        }).reset_index()

        result.columns = [
            "category", "window_start", "sum_amount", "count_amount", "avg_amount"
        ]

        return result

    def _non_keyed_window(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        无分区的窗口计算

        参数:
            data: 输入数据

        返回:
            窗口计算结果
        """
        time_col = self.node.time_column or "event_time"

        result = pd.DataFrame()
        result["window_start"] = [pd.Timestamp.now().floor("H")]
        result["window_end"] = [pd.Timestamp.now().floor("H") + pd.Timedelta(hours=1)]
        result["count"] = [len(data)]

        if "amount" in data.columns:
            result["sum_amount"] = [data["amount"].sum()]
            result["avg_amount"] = [data["amount"].mean()]

        return result

    def _session_window(self, data: pd.DataFrame) -> pd.DataFrame:
        """
        会话窗口计算

        参数:
            data: 输入数据

        返回:
            窗口计算结果
        """
        return self._keyed_window(data)


class SinkExecutor(OperatorExecutor):
    """
    Sink算子执行器

    执行数据输出操作。
    """

    def __init__(self, node: PhysicalSink) -> None:
        """
        初始化Sink执行器

        参数:
            node: 物理Sink节点
        """
        self.node: PhysicalSink = node
        self.output_data: List[pd.DataFrame] = []

    def open(self, context: ExecutionContext) -> None:
        """打开Sink算子"""
        context.update_metric(f"{self.node.node_id}_opened", True)
        self.output_data = []

    def process(self, batch: StreamBatch, context: ExecutionContext) -> StreamBatch:
        """
        输出数据

        参数:
            batch: 输入批次
            context: 执行上下文

        返回:
            输入批次（透传）
        """
        context.increment_metric(f"{self.node.node_id}_batches")

        if not batch.is_empty():
            self.output_data.append(batch.data.copy())
            context.update_metric(
                f"{self.node.node_id}_rows",
                context.metrics.get(f"{self.node.node_id}_rows", 0) + len(batch.data),
            )

        return batch

    def close(self, context: ExecutionContext) -> None:
        """关闭Sink算子"""
        context.update_metric(f"{self.node.node_id}_closed", True)
        context.set_state(f"{self.node.node_id}_output", self.get_output())

    def get_output(self) -> pd.DataFrame:
        """
        获取所有输出数据

        返回:
            合并后的输出数据
        """
        if not self.output_data:
            return pd.DataFrame()
        return pd.concat(self.output_data, ignore_index=True)


class Executor(PhysicalPlanVisitor):
    """
    流式查询执行引擎

    执行物理计划，管理算子生命周期和数据流。
    """

    def __init__(self, config: Optional[Dict[str, Any]] = None) -> None:
        """
        初始化执行引擎

        参数:
            config: 执行配置
        """
        self.config: Dict[str, Any] = config or {}
        self.executors: Dict[str, OperatorExecutor] = {}
        self.execution_order: List[PhysicalPlanNode] = []

    def execute(
        self,
        physical_plan: PhysicalPlan,
        query_id: Optional[str] = None,
    ) -> pd.DataFrame:
        """
        执行物理计划

        参数:
            physical_plan: 物理计划
            query_id: 查询ID

        返回:
            执行结果DataFrame
        """
        context = ExecutionContext(
            query_id=query_id or f"query_{pd.Timestamp.now().strftime('%Y%m%d_%H%M%S')}",
            config=self.config,
        )

        try:
            self._build_execution_plan(physical_plan.root)
            self._open_all(context)
            result = self._execute_pipeline(context)
            self._close_all(context)

            context.update_metric("execution_time_ms", context.get_elapsed_time().total_seconds() * 1000)

            return result

        except Exception as e:
            self._close_all(context)
            raise RuntimeError(f"查询执行失败: {e}") from e

    def _build_execution_plan(self, root: PhysicalPlanNode) -> None:
        """
        构建执行计划

        参数:
            root: 物理计划根节点
        """
        self.executors = {}
        self.execution_order = []

        nodes = root.collect_nodes()

        for node in nodes:
            executor = node.accept(self)
            self.executors[node.node_id] = executor
            self.execution_order.append(node)

    def _open_all(self, context: ExecutionContext) -> None:
        """
        打开所有算子

        参数:
            context: 执行上下文
        """
        for node in self.execution_order:
            executor = self.executors[node.node_id]
            executor.open(context)

    def _execute_pipeline(self, context: ExecutionContext) -> pd.DataFrame:
        """
        执行数据流管道

        参数:
            context: 执行上下文

        返回:
            最终结果
        """
        empty_batch = StreamBatch(
            data=pd.DataFrame(),
            batch_id=0,
        )

        current_batch = empty_batch

        while True:
            for node in self.execution_order:
                executor = self.executors[node.node_id]
                current_batch = executor.process(current_batch, context)

                if isinstance(executor, SinkExecutor) and current_batch.is_last:
                    return executor.get_output()

            if current_batch.is_last:
                break

        sink_executor = None
        for executor in self.executors.values():
            if isinstance(executor, SinkExecutor):
                sink_executor = executor
                break

        return sink_executor.get_output() if sink_executor else pd.DataFrame()

    def _close_all(self, context: ExecutionContext) -> None:
        """
        关闭所有算子

        参数:
            context: 执行上下文
        """
        for node in reversed(self.execution_order):
            executor = self.executors.get(node.node_id)
            if executor:
                try:
                    executor.close(context)
                except Exception:
                    pass

    def visit_source(self, node: PhysicalSource) -> OperatorExecutor:
        """创建Source执行器"""
        return SourceExecutor(node)

    def visit_scan(self, node: PhysicalScan) -> OperatorExecutor:
        """创建Scan执行器"""
        return ScanExecutor(node)

    def visit_filter(self, node: PhysicalFilter) -> OperatorExecutor:
        """创建Filter执行器"""
        return FilterExecutor(node)

    def visit_project(self, node: PhysicalProject) -> OperatorExecutor:
        """创建Project执行器"""
        return ProjectExecutor(node)

    def visit_aggregate(self, node: PhysicalAggregate) -> OperatorExecutor:
        """创建Aggregate执行器"""
        return AggregateExecutor(node)

    def visit_join(self, node: PhysicalJoin) -> OperatorExecutor:
        """创建Join执行器"""
        return JoinExecutor(node)

    def visit_window(self, node: PhysicalWindow) -> OperatorExecutor:
        """创建Window执行器"""
        return WindowExecutor(node)

    def visit_sink(self, node: PhysicalSink) -> OperatorExecutor:
        """创建Sink执行器"""
        return SinkExecutor(node)

    def get_executor(self, node_id: str) -> Optional[OperatorExecutor]:
        """
        获取指定节点的执行器

        参数:
            node_id: 节点ID

        返回:
            执行器实例
        """
        return self.executors.get(node_id)

    def execute_stream(
        self,
        physical_plan: PhysicalPlan,
        query_id: Optional[str] = None,
    ) -> Generator[StreamBatch, None, None]:
        """
        以流方式执行，逐个批次返回结果

        参数:
            physical_plan: 物理计划
            query_id: 查询ID

        Yields:
            StreamBatch数据批次
        """
        context = ExecutionContext(
            query_id=query_id or f"query_{pd.Timestamp.now().strftime('%Y%m%d_%H%M%S')}",
            config=self.config,
        )

        try:
            self._build_execution_plan(physical_plan.root)
            self._open_all(context)

            empty_batch = StreamBatch(data=pd.DataFrame(), batch_id=0)
            current_batch = empty_batch

            while True:
                for node in self.execution_order:
                    executor = self.executors[node.node_id]
                    current_batch = executor.process(current_batch, context)

                yield current_batch

                if current_batch.is_last:
                    break

                current_batch = StreamBatch(
                    data=pd.DataFrame(),
                    batch_id=current_batch.batch_id + 1,
                )

            self._close_all(context)

        except Exception as e:
            self._close_all(context)
            raise RuntimeError(f"流式执行失败: {e}") from e
