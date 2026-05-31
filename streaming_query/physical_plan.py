"""
physical_plan - 物理计划节点、代价模型、翻译

将逻辑计划转换为可执行的物理计划，包括：
- 物理计划节点定义（与具体执行引擎相关）
- 代价模型（CPU、内存、IO、网络开销估算）
- 物理计划翻译器（逻辑计划到物理计划的转换）
- 多物理计划选择（基于代价模型选择最优执行计划）
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set, Tuple, Union

from .logical_plan import (
    Aggregate as LogicalAggregate,
    Filter as LogicalFilter,
    Join as LogicalJoin,
    JoinType,
    LogicalPlan,
    LogicalPlanNode,
    LogicalPlanVisitor,
    NodeType,
    Project as LogicalProject,
    Scan as LogicalScan,
    Sink as LogicalSink,
    Source as LogicalSource,
    Window as LogicalWindow,
)


class PhysicalNodeType(Enum):
    """物理计划节点类型枚举"""
    SOURCE = "PHYSICAL_SOURCE"
    SCAN = "PHYSICAL_SCAN"
    FILTER = "PHYSICAL_FILTER"
    PROJECT = "PHYSICAL_PROJECT"
    AGGREGATE = "PHYSICAL_AGGREGATE"
    JOIN = "PHYSICAL_JOIN"
    WINDOW = "PHYSICAL_WINDOW"
    SINK = "PHYSICAL_SINK"


class AggregateStrategy(Enum):
    """聚合执行策略枚举"""
    HASH_AGGREGATE = "HASH_AGGREGATE"
    SORT_AGGREGATE = "SORT_AGGREGATE"
    TWO_PHASE_AGGREGATE = "TWO_PHASE_AGGREGATE"


class JoinStrategy(Enum):
    """连接执行策略枚举"""
    HASH_JOIN = "HASH_JOIN"
    SORT_MERGE_JOIN = "SORT_MERGE_JOIN"
    BROADCAST_JOIN = "BROADCAST_JOIN"
    NESTED_LOOP_JOIN = "NESTED_LOOP_JOIN"
    WINDOW_JOIN = "WINDOW_JOIN"


class WindowStrategy(Enum):
    """窗口执行策略枚举"""
    KEYED_WINDOW = "KEYED_WINDOW"
    NON_KEYED_WINDOW = "NON_KEYED_WINDOW"
    SESSION_WINDOW = "SESSION_WINDOW"


@dataclass
class Cost:
    """
    代价估计

    属性:
        cpu: CPU开销（单位：百万指令数）
        memory: 内存开销（单位：MB）
        io: IO开销（单位：MB）
        network: 网络开销（单位：MB）
        latency: 预估延迟（单位：毫秒）
    """
    cpu: float = 0.0
    memory: float = 0.0
    io: float = 0.0
    network: float = 0.0
    latency: float = 0.0

    def __add__(self, other: "Cost") -> "Cost":
        """代价叠加"""
        return Cost(
            cpu=self.cpu + other.cpu,
            memory=self.memory + other.memory,
            io=self.io + other.io,
            network=self.network + other.network,
            latency=self.latency + other.latency,
        )

    def __mul__(self, factor: float) -> "Cost":
        """代价缩放"""
        return Cost(
            cpu=self.cpu * factor,
            memory=self.memory * factor,
            io=self.io * factor,
            network=self.network * factor,
            latency=self.latency * factor,
        )

    def total(self) -> float:
        """
        计算总代价（加权求和）

        返回:
            总代价值
        """
        return (
            self.cpu * 1.0
            + self.memory * 0.5
            + self.io * 2.0
            + self.network * 3.0
            + self.latency * 0.1
        )

    def to_dict(self) -> Dict[str, float]:
        """转换为字典"""
        return {
            "cpu": self.cpu,
            "memory": self.memory,
            "io": self.io,
            "network": self.network,
            "latency": self.latency,
            "total": self.total(),
        }


@dataclass
class Statistics:
    """
    数据统计信息

    属性:
        row_count: 预估行数
        avg_row_size: 平均行大小（字节）
        distinct_values: 各列的去重值计数
        null_count: 各列的空值计数
        min_values: 各列的最小值
        max_values: 各列的最大值
    """
    row_count: int = 0
    avg_row_size: int = 1024
    distinct_values: Dict[str, int] = field(default_factory=dict)
    null_count: Dict[str, int] = field(default_factory=dict)
    min_values: Dict[str, Any] = field(default_factory=dict)
    max_values: Dict[str, Any] = field(default_factory=dict)

    def data_size(self) -> int:
        """
        计算总数据大小

        返回:
            数据大小（字节）
        """
        return self.row_count * self.avg_row_size


@dataclass
class PhysicalPlanNode(ABC):
    """
    物理计划节点抽象基类

    属性:
        node_id: 节点唯一标识
        node_type: 节点类型
        children: 子节点列表
        output_columns: 输出列名列表
        statistics: 数据统计信息
        estimated_cost: 预估代价
        parallelism: 并行度
    """
    node_id: str
    node_type: PhysicalNodeType
    children: List["PhysicalPlanNode"] = field(default_factory=list)
    output_columns: List[str] = field(default_factory=list)
    statistics: Statistics = field(default_factory=Statistics)
    estimated_cost: Cost = field(default_factory=Cost)
    parallelism: int = 1

    @abstractmethod
    def accept(self, visitor: "PhysicalPlanVisitor") -> Any:
        """
        接受访问者访问

        参数:
            visitor: 访问者对象

        返回:
            访问结果
        """
        ...

    def pretty_print(self, indent: int = 0) -> str:
        """
        格式化打印物理计划树

        参数:
            indent: 缩进级别

        返回:
            格式化的字符串表示
        """
        prefix = "  " * indent
        result = f"{prefix}{self.node_type.value}"

        details = self._node_details()
        if details:
            result += f" [{details}]"

        result += f" (cost={self.estimated_cost.total():.2f}, rows={self.statistics.row_count})"

        if self.output_columns:
            result += f" -> {', '.join(self.output_columns[:3])}"
            if len(self.output_columns) > 3:
                result += "..."

        result += "\n"

        for child in self.children:
            result += child.pretty_print(indent + 1)

        return result

    def _node_details(self) -> str:
        """
        返回节点详细信息，用于pretty_print

        返回:
            节点详细信息字符串
        """
        return ""

    def collect_nodes(self) -> List["PhysicalPlanNode"]:
        """
        收集所有节点（后序遍历）

        返回:
            节点列表
        """
        nodes: List[PhysicalPlanNode] = []
        for child in self.children:
            nodes.extend(child.collect_nodes())
        nodes.append(self)
        return nodes

    def get_total_cost(self) -> Cost:
        """
        计算从该节点到叶子节点的总代价

        返回:
            总代价
        """
        total = self.estimated_cost
        for child in self.children:
            total = total + child.get_total_cost()
        return total


class PhysicalPlanVisitor(ABC):
    """
    物理计划访问者抽象基类
    """

    @abstractmethod
    def visit_source(self, node: "PhysicalSource") -> Any: ...

    @abstractmethod
    def visit_scan(self, node: "PhysicalScan") -> Any: ...

    @abstractmethod
    def visit_filter(self, node: "PhysicalFilter") -> Any: ...

    @abstractmethod
    def visit_project(self, node: "PhysicalProject") -> Any: ...

    @abstractmethod
    def visit_aggregate(self, node: "PhysicalAggregate") -> Any: ...

    @abstractmethod
    def visit_join(self, node: "PhysicalJoin") -> Any: ...

    @abstractmethod
    def visit_window(self, node: "PhysicalWindow") -> Any: ...

    @abstractmethod
    def visit_sink(self, node: "PhysicalSink") -> Any: ...


@dataclass
class PhysicalSource(PhysicalPlanNode):
    """
    物理数据源节点

    属性:
        source_name: 数据源名称
        source_type: 数据源类型（KAFKA, PULSAR, FILE等）
        topic: 主题/文件路径
        start_offset: 起始偏移量
        end_offset: 结束偏移量
        consumer_group: 消费者组
    """
    source_name: str = ""
    source_type: str = "KAFKA"
    topic: str = ""
    start_offset: str = "earliest"
    end_offset: Optional[str] = None
    consumer_group: str = ""

    def __init__(
        self,
        node_id: str,
        source_name: str,
        source_type: str = "KAFKA",
        topic: str = "",
        **kwargs: Any,
    ) -> None:
        super().__init__(
            node_id=node_id,
            node_type=PhysicalNodeType.SOURCE,
            children=[],
        )
        self.source_name = source_name
        self.source_type = source_type
        self.topic = topic or source_name

    def accept(self, visitor: PhysicalPlanVisitor) -> Any:
        return visitor.visit_source(self)

    def _node_details(self) -> str:
        return (
            f"name={self.source_name}, type={self.source_type}, "
            f"topic={self.topic}, parallelism={self.parallelism}"
        )


@dataclass
class PhysicalScan(PhysicalPlanNode):
    """
    物理扫描节点

    属性:
        table_name: 表名
        projected_columns: 投影列列表
        predicate: 下推谓词
        scan_type: 扫描类型（FULL_SCAN, RANGE_SCAN, INDEX_SCAN）
        batch_size: 批处理大小
    """
    table_name: str = ""
    projected_columns: List[str] = field(default_factory=list)
    predicate: Optional[Any] = None
    scan_type: str = "FULL_SCAN"
    batch_size: int = 10000

    def __init__(
        self,
        node_id: str,
        table_name: str,
        children: Optional[List[PhysicalPlanNode]] = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(
            node_id=node_id,
            node_type=PhysicalNodeType.SCAN,
            children=children or [],
        )
        self.table_name = table_name

    def accept(self, visitor: PhysicalPlanVisitor) -> Any:
        return visitor.visit_scan(self)

    def _node_details(self) -> str:
        details = f"table={self.table_name}, scan_type={self.scan_type}"
        if self.predicate:
            details += f", predicate=yes"
        if self.projected_columns:
            details += f", cols={len(self.projected_columns)}"
        return details


@dataclass
class PhysicalFilter(PhysicalPlanNode):
    """
    物理过滤节点

    属性:
        condition: 过滤条件
        filter_type: 过滤类型（NORMAL, BLOOM_FILTER, SKIP_SCAN）
        selectivity: 过滤选择率（0-1）
    """
    condition: Optional[Any] = None
    filter_type: str = "NORMAL"
    selectivity: float = 1.0

    def __init__(
        self,
        node_id: str,
        condition: Any,
        child: Optional[PhysicalPlanNode] = None,
        **kwargs: Any,
    ) -> None:
        children = [child] if child else []
        super().__init__(
            node_id=node_id,
            node_type=PhysicalNodeType.FILTER,
            children=children,
        )
        self.condition = condition

    def accept(self, visitor: PhysicalPlanVisitor) -> Any:
        return visitor.visit_filter(self)

    def _node_details(self) -> str:
        return (
            f"type={self.filter_type}, selectivity={self.selectivity:.3f}, "
            f"parallelism={self.parallelism}"
        )


@dataclass
class PhysicalProject(PhysicalPlanNode):
    """
    物理投影节点

    属性:
        projections: 投影表达式列表
        is_star: 是否为SELECT *
        code_generated: 是否已生成代码
    """
    projections: List[Tuple[Any, Optional[str]]] = field(default_factory=list)
    is_star: bool = False
    code_generated: bool = False

    def __init__(
        self,
        node_id: str,
        projections: List[Tuple[Any, Optional[str]]],
        child: Optional[PhysicalPlanNode] = None,
        **kwargs: Any,
    ) -> None:
        children = [child] if child else []
        super().__init__(
            node_id=node_id,
            node_type=PhysicalNodeType.PROJECT,
            children=children,
        )
        self.projections = projections

    def accept(self, visitor: PhysicalPlanVisitor) -> Any:
        return visitor.visit_project(self)

    def _node_details(self) -> str:
        return (
            f"cols={len(self.projections)}, star={self.is_star}, "
            f"code_gen={self.code_generated}"
        )


@dataclass
class PhysicalAggregate(PhysicalPlanNode):
    """
    物理聚合节点

    属性:
        group_by: GROUP BY列名列表
        aggregate_expressions: 聚合表达式列表
        strategy: 聚合策略
        is_partial: 是否为部分聚合（两阶段聚合）
        is_final: 是否为最终聚合（两阶段聚合）
        state_ttl: 状态过期时间（毫秒）
    """
    group_by: List[str] = field(default_factory=list)
    aggregate_expressions: List[Any] = field(default_factory=list)
    strategy: AggregateStrategy = AggregateStrategy.HASH_AGGREGATE
    is_partial: bool = False
    is_final: bool = False
    state_ttl: int = 3600000

    def __init__(
        self,
        node_id: str,
        group_by: List[str],
        aggregate_expressions: List[Any],
        child: Optional[PhysicalPlanNode] = None,
        **kwargs: Any,
    ) -> None:
        children = [child] if child else []
        super().__init__(
            node_id=node_id,
            node_type=PhysicalNodeType.AGGREGATE,
            children=children,
        )
        self.group_by = group_by
        self.aggregate_expressions = aggregate_expressions

    def accept(self, visitor: PhysicalPlanVisitor) -> Any:
        return visitor.visit_aggregate(self)

    def _node_details(self) -> str:
        return (
            f"strategy={self.strategy.value}, group_keys={len(self.group_by)}, "
            f"aggs={len(self.aggregate_expressions)}, partial={self.is_partial}"
        )


@dataclass
class PhysicalJoin(PhysicalPlanNode):
    """
    物理连接节点

    属性:
        join_type: 连接类型
        left_key: 左表连接键
        right_key: 右表连接键
        condition: 连接条件
        strategy: 连接策略
        broadcast_side: 广播端（left/right）
        window_bound: 窗口连接时间边界
        state_ttl: 状态过期时间（毫秒）
    """
    join_type: JoinType = JoinType.INNER
    left_key: List[str] = field(default_factory=list)
    right_key: List[str] = field(default_factory=list)
    condition: Optional[Any] = None
    strategy: JoinStrategy = JoinStrategy.HASH_JOIN
    broadcast_side: Optional[str] = None
    window_bound: Optional[str] = None
    state_ttl: int = 3600000

    def __init__(
        self,
        node_id: str,
        join_type: JoinType,
        left: PhysicalPlanNode,
        right: PhysicalPlanNode,
        left_key: List[str],
        right_key: List[str],
        **kwargs: Any,
    ) -> None:
        super().__init__(
            node_id=node_id,
            node_type=PhysicalNodeType.JOIN,
            children=[left, right],
        )
        self.join_type = join_type
        self.left_key = left_key
        self.right_key = right_key

    def accept(self, visitor: PhysicalPlanVisitor) -> Any:
        return visitor.visit_join(self)

    def _node_details(self) -> str:
        details = [
            f"type={self.join_type.value}",
            f"strategy={self.strategy.value}",
            f"left_key={self.left_key}",
            f"right_key={self.right_key}",
        ]
        if self.broadcast_side:
            details.append(f"broadcast={self.broadcast_side}")
        return ", ".join(details)


@dataclass
class PhysicalWindow(PhysicalPlanNode):
    """
    物理窗口节点

    属性:
        window_type: 窗口类型
        time_column: 时间列名
        window_size: 窗口大小（毫秒）
        slide_interval: 滑动间隔（毫秒）
        session_gap: 会话间隔（毫秒）
        partition_by: 分区列列表
        order_by: 排序列列表
        window_functions: 窗口函数列表
        strategy: 窗口执行策略
        emit_strategy: 输出策略
        allow_lateness: 允许延迟时间（毫秒）
    """
    window_type: str = "TUMBLE"
    time_column: str = ""
    window_size: int = 3600000
    slide_interval: Optional[int] = None
    session_gap: Optional[int] = None
    partition_by: List[str] = field(default_factory=list)
    order_by: List[str] = field(default_factory=list)
    window_functions: List[Any] = field(default_factory=list)
    strategy: WindowStrategy = WindowStrategy.KEYED_WINDOW
    emit_strategy: str = "ON_WATERMARK"
    allow_lateness: int = 0

    def __init__(
        self,
        node_id: str,
        window_type: str,
        time_column: str,
        window_size: int,
        child: Optional[PhysicalPlanNode] = None,
        **kwargs: Any,
    ) -> None:
        children = [child] if child else []
        super().__init__(
            node_id=node_id,
            node_type=PhysicalNodeType.WINDOW,
            children=children,
        )
        self.window_type = window_type
        self.time_column = time_column
        self.window_size = window_size

    def accept(self, visitor: PhysicalPlanVisitor) -> Any:
        return visitor.visit_window(self)

    def _node_details(self) -> str:
        details = [
            f"type={self.window_type}",
            f"size={self.window_size}ms",
            f"strategy={self.strategy.value}",
            f"emit={self.emit_strategy}",
        ]
        if self.partition_by:
            details.append(f"partition_by={len(self.partition_by)}")
        if self.allow_lateness:
            details.append(f"lateness={self.allow_lateness}ms")
        return ", ".join(details)


@dataclass
class PhysicalSink(PhysicalPlanNode):
    """
    物理输出节点

    属性:
        sink_name: 输出名称
        sink_type: 输出类型（KAFKA, FILE, DATABASE等）
        topic: 主题/表名
        mode: 输出模式（APPEND, UPSERT, RETRACT）
        batch_size: 批处理大小
        checkpoint_interval: 检查点间隔（毫秒）
    """
    sink_name: str = ""
    sink_type: str = "KAFKA"
    topic: str = ""
    mode: str = "APPEND"
    batch_size: int = 1000
    checkpoint_interval: int = 10000

    def __init__(
        self,
        node_id: str,
        sink_name: str,
        sink_type: str = "KAFKA",
        child: Optional[PhysicalPlanNode] = None,
        **kwargs: Any,
    ) -> None:
        children = [child] if child else []
        super().__init__(
            node_id=node_id,
            node_type=PhysicalNodeType.SINK,
            children=children,
        )
        self.sink_name = sink_name
        self.sink_type = sink_type

    def accept(self, visitor: PhysicalPlanVisitor) -> Any:
        return visitor.visit_sink(self)

    def _node_details(self) -> str:
        return (
            f"name={self.sink_name}, type={self.sink_type}, "
            f"mode={self.mode}, batch={self.batch_size}"
        )


@dataclass
class PhysicalPlan:
    """
    物理计划封装

    属性:
        root: 物理计划根节点
        logical_plan: 对应的逻辑计划
        total_cost: 总代价
        alternatives: 备选物理计划列表
    """
    root: PhysicalPlanNode
    logical_plan: Optional[LogicalPlan] = None
    total_cost: Cost = field(default_factory=Cost)
    alternatives: List["PhysicalPlan"] = field(default_factory=list)

    def pretty_print(self) -> str:
        """
        格式化打印物理计划

        返回:
            格式化的物理计划字符串
        """
        result = "=== Physical Plan ===\n"
        result += f"Total Cost: {self.total_cost.to_dict()}\n\n"
        result += self.root.pretty_print()
        return result

    def get_total_cost(self) -> Cost:
        """
        获取总代价

        返回:
            总代价
        """
        return self.root.get_total_cost()

    def collect_nodes(self) -> List[PhysicalPlanNode]:
        """
        收集所有节点

        返回:
            节点列表
        """
        return self.root.collect_nodes()


class CostModel:
    """
    代价模型

    基于统计信息和节点类型，估算物理计划的执行代价。
    """

    def __init__(
        self,
        cpu_speed: float = 3.0,
        memory_cost_per_mb: float = 0.01,
        io_cost_per_mb: float = 0.1,
        network_cost_per_mb: float = 0.5,
    ) -> None:
        """
        初始化代价模型

        参数:
            cpu_speed: CPU速度（GHz）
            memory_cost_per_mb: 每MB内存成本
            io_cost_per_mb: 每MB IO成本
            network_cost_per_mb: 每MB网络成本
        """
        self.cpu_speed: float = cpu_speed
        self.memory_cost_per_mb: float = memory_cost_per_mb
        self.io_cost_per_mb: float = io_cost_per_mb
        self.network_cost_per_mb: float = network_cost_per_mb

    def estimate_cost(
        self,
        node: PhysicalPlanNode,
        children_stats: List[Statistics],
    ) -> Cost:
        """
        估算节点代价

        参数:
            node: 物理计划节点
            children_stats: 子节点统计信息列表

        返回:
            估算的代价
        """
        estimator = CostEstimator(self)
        return node.accept(estimator, children_stats)

    def estimate_output_stats(
        self,
        node: PhysicalPlanNode,
        children_stats: List[Statistics],
    ) -> Statistics:
        """
        估算输出统计信息

        参数:
            node: 物理计划节点
            children_stats: 子节点统计信息列表

        返回:
            输出统计信息
        """
        return node.accept(StatsEstimator(), children_stats)


class CostEstimator(PhysicalPlanVisitor):
    """
    代价估算访问者
    """

    def __init__(self, cost_model: CostModel) -> None:
        self.cost_model = cost_model

    def visit_source(
        self,
        node: PhysicalSource,
        children_stats: List[Statistics],
    ) -> Cost:
        """估算Source节点代价"""
        stats = node.statistics
        data_size = stats.data_size() / (1024 * 1024)

        return Cost(
            cpu=stats.row_count * 0.001,
            memory=data_size * 0.1,
            io=data_size * 1.0,
            network=data_size * 0.5,
            latency=stats.row_count * 0.0001,
        )

    def visit_scan(
        self,
        node: PhysicalScan,
        children_stats: List[Statistics],
    ) -> Cost:
        """估算Scan节点代价"""
        input_stats = children_stats[0] if children_stats else Statistics()
        data_size = input_stats.data_size() / (1024 * 1024)
        selectivity = 0.8

        if node.predicate:
            selectivity = 0.3

        return Cost(
            cpu=input_stats.row_count * 0.01,
            memory=data_size * selectivity * 0.5,
            io=data_size * selectivity,
            network=0,
            latency=input_stats.row_count * 0.0001,
        )

    def visit_filter(
        self,
        node: PhysicalFilter,
        children_stats: List[Statistics],
    ) -> Cost:
        """估算Filter节点代价"""
        input_stats = children_stats[0] if children_stats else Statistics()

        return Cost(
            cpu=input_stats.row_count * 0.05,
            memory=input_stats.data_size() / (1024 * 1024) * 0.1,
            io=0,
            network=0,
            latency=input_stats.row_count * 0.00005,
        )

    def visit_project(
        self,
        node: PhysicalProject,
        children_stats: List[Statistics],
    ) -> Cost:
        """估算Project节点代价"""
        input_stats = children_stats[0] if children_stats else Statistics()
        ratio = len(node.output_columns) / max(len(input_stats.distinct_values), 1)

        return Cost(
            cpu=input_stats.row_count * 0.02,
            memory=input_stats.data_size() / (1024 * 1024) * ratio * 0.3,
            io=0,
            network=0,
            latency=input_stats.row_count * 0.00002,
        )

    def visit_aggregate(
        self,
        node: PhysicalAggregate,
        children_stats: List[Statistics],
    ) -> Cost:
        """估算Aggregate节点代价"""
        input_stats = children_stats[0] if children_stats else Statistics()
        group_keys = len(node.group_by)
        num_aggs = len(node.aggregate_expressions)

        cpu_factor = 0.1
        memory_factor = 2.0

        if node.strategy == AggregateStrategy.HASH_AGGREGATE:
            cpu_factor = 0.15
            memory_factor = 2.5
        elif node.strategy == AggregateStrategy.SORT_AGGREGATE:
            cpu_factor = 0.2
            memory_factor = 3.0

        return Cost(
            cpu=input_stats.row_count * cpu_factor * (1 + group_keys * 0.1) * (1 + num_aggs * 0.05),
            memory=input_stats.data_size() / (1024 * 1024) * memory_factor,
            io=0,
            network=input_stats.data_size() / (1024 * 1024) * 0.5,
            latency=input_stats.row_count * 0.001,
        )

    def visit_join(
        self,
        node: PhysicalJoin,
        children_stats: List[Statistics],
    ) -> Cost:
        """估算Join节点代价"""
        left_stats = children_stats[0] if len(children_stats) > 0 else Statistics()
        right_stats = children_stats[1] if len(children_stats) > 1 else Statistics()

        left_size = left_stats.data_size() / (1024 * 1024)
        right_size = right_stats.data_size() / (1024 * 1024)

        cpu_factor = 0.5
        memory_factor = 3.0
        network_factor = 1.0

        if node.strategy == JoinStrategy.HASH_JOIN:
            cpu_factor = 0.3
            memory_factor = 2.0
        elif node.strategy == JoinStrategy.BROADCAST_JOIN:
            cpu_factor = 0.2
            memory_factor = 1.5
            network_factor = 0.5 if node.broadcast_side == "right" else 0.5
        elif node.strategy == JoinStrategy.SORT_MERGE_JOIN:
            cpu_factor = 0.4
            memory_factor = 2.5

        return Cost(
            cpu=(left_stats.row_count + right_stats.row_count) * cpu_factor,
            memory=max(left_size, right_size) * memory_factor,
            io=0,
            network=min(left_size, right_size) * network_factor,
            latency=(left_stats.row_count + right_stats.row_count) * 0.002,
        )

    def visit_window(
        self,
        node: PhysicalWindow,
        children_stats: List[Statistics],
    ) -> Cost:
        """估算Window节点代价"""
        input_stats = children_stats[0] if children_stats else Statistics()
        data_size = input_stats.data_size() / (1024 * 1024)
        num_partitions = max(len(node.partition_by), 1)
        num_functions = max(len(node.window_functions), 1)

        cpu_factor = 0.2
        memory_factor = 3.0

        if node.strategy == WindowStrategy.KEYED_WINDOW:
            memory_factor = 2.5
        elif node.strategy == WindowStrategy.SESSION_WINDOW:
            cpu_factor = 0.3
            memory_factor = 4.0

        return Cost(
            cpu=input_stats.row_count * cpu_factor * (1 + num_partitions * 0.1) * (1 + num_functions * 0.1),
            memory=data_size * memory_factor,
            io=data_size * 0.1,
            network=data_size * 0.3,
            latency=input_stats.row_count * 0.0015,
        )

    def visit_sink(
        self,
        node: PhysicalSink,
        children_stats: List[Statistics],
    ) -> Cost:
        """估算Sink节点代价"""
        input_stats = children_stats[0] if children_stats else Statistics()
        data_size = input_stats.data_size() / (1024 * 1024)

        io_factor = 1.0
        network_factor = 1.0

        if node.sink_type == "FILE":
            io_factor = 1.5
            network_factor = 0
        elif node.sink_type == "DATABASE":
            io_factor = 2.0
            network_factor = 0.5

        return Cost(
            cpu=input_stats.row_count * 0.05,
            memory=data_size * 0.5,
            io=data_size * io_factor,
            network=data_size * network_factor,
            latency=input_stats.row_count * 0.0005,
        )


class StatsEstimator(PhysicalPlanVisitor):
    """
    输出统计信息估算访问者
    """

    def visit_source(
        self,
        node: PhysicalSource,
        children_stats: List[Statistics],
    ) -> Statistics:
        """估算Source节点输出统计"""
        return node.statistics

    def visit_scan(
        self,
        node: PhysicalScan,
        children_stats: List[Statistics],
    ) -> Statistics:
        """估算Scan节点输出统计"""
        input_stats = children_stats[0] if children_stats else Statistics()
        selectivity = 0.8
        if node.predicate:
            selectivity = 0.3

        stats = Statistics(
            row_count=int(input_stats.row_count * selectivity),
            avg_row_size=input_stats.avg_row_size,
        )
        if node.projected_columns:
            stats.avg_row_size = int(input_stats.avg_row_size * 0.6)
            for col in node.projected_columns:
                if col in input_stats.distinct_values:
                    stats.distinct_values[col] = input_stats.distinct_values[col]
        return stats

    def visit_filter(
        self,
        node: PhysicalFilter,
        children_stats: List[Statistics],
    ) -> Statistics:
        """估算Filter节点输出统计"""
        input_stats = children_stats[0] if children_stats else Statistics()
        return Statistics(
            row_count=int(input_stats.row_count * node.selectivity),
            avg_row_size=input_stats.avg_row_size,
            distinct_values=input_stats.distinct_values.copy(),
        )

    def visit_project(
        self,
        node: PhysicalProject,
        children_stats: List[Statistics],
    ) -> Statistics:
        """估算Project节点输出统计"""
        input_stats = children_stats[0] if children_stats else Statistics()
        ratio = len(node.output_columns) / max(len(input_stats.distinct_values), 1)
        return Statistics(
            row_count=input_stats.row_count,
            avg_row_size=int(input_stats.avg_row_size * ratio),
        )

    def visit_aggregate(
        self,
        node: PhysicalAggregate,
        children_stats: List[Statistics],
    ) -> Statistics:
        """估算Aggregate节点输出统计"""
        input_stats = children_stats[0] if children_stats else Statistics()

        if node.group_by:
            distinct_product = 1
            for col in node.group_by:
                distinct_product *= input_stats.distinct_values.get(col, 100)
            output_rows = min(distinct_product, input_stats.row_count)
        else:
            output_rows = 1

        return Statistics(
            row_count=output_rows,
            avg_row_size=input_stats.avg_row_size * 2,
        )

    def visit_join(
        self,
        node: PhysicalJoin,
        children_stats: List[Statistics],
    ) -> Statistics:
        """估算Join节点输出统计"""
        left_stats = children_stats[0] if len(children_stats) > 0 else Statistics()
        right_stats = children_stats[1] if len(children_stats) > 1 else Statistics()

        join_selectivity = 0.5

        left_distinct = 1
        for key in node.left_key:
            left_distinct *= left_stats.distinct_values.get(key, 100)

        right_distinct = 1
        for key in node.right_key:
            right_distinct *= right_stats.distinct_values.get(key, 100)

        if left_distinct > right_distinct:
            join_selectivity = right_distinct / max(left_distinct, 1)
        else:
            join_selectivity = left_distinct / max(right_distinct, 1)

        output_rows = int(left_stats.row_count * right_stats.row_count * join_selectivity)
        output_rows = min(output_rows, left_stats.row_count * 10)

        return Statistics(
            row_count=output_rows,
            avg_row_size=left_stats.avg_row_size + right_stats.avg_row_size,
        )

    def visit_window(
        self,
        node: PhysicalWindow,
        children_stats: List[Statistics],
    ) -> Statistics:
        """估算Window节点输出统计"""
        input_stats = children_stats[0] if children_stats else Statistics()

        if node.partition_by:
            num_partitions = 1
            for col in node.partition_by:
                num_partitions *= input_stats.distinct_values.get(col, 10)
            windows_per_partition = max(1, int(node.window_size / max(node.slide_interval or node.window_size, 1)))
            output_rows = min(input_stats.row_count, num_partitions * windows_per_partition * 2)
        else:
            output_rows = input_stats.row_count

        return Statistics(
            row_count=output_rows,
            avg_row_size=input_stats.avg_row_size * 1.5,
        )

    def visit_sink(
        self,
        node: PhysicalSink,
        children_stats: List[Statistics],
    ) -> Statistics:
        """估算Sink节点输出统计"""
        return children_stats[0] if children_stats else Statistics()


class PhysicalPlanner(LogicalPlanVisitor):
    """
    物理计划翻译器

    将逻辑计划转换为物理计划，并基于代价模型选择最优执行计划。
    """

    def __init__(
        self,
        cost_model: Optional[CostModel] = None,
        default_parallelism: int = 4,
    ) -> None:
        """
        初始化物理计划翻译器

        参数:
            cost_model: 代价模型，默认创建新的
            default_parallelism: 默认并行度
        """
        self.cost_model: CostModel = cost_model or CostModel()
        self.default_parallelism: int = default_parallelism
        self._node_counter: int = 0

    def _next_id(self, prefix: str) -> str:
        """生成下一个节点ID"""
        self._node_counter += 1
        return f"physical_{prefix}_{self._node_counter}"

    def plan(self, logical_plan: LogicalPlan) -> PhysicalPlan:
        """
        将逻辑计划转换为物理计划

        参数:
            logical_plan: 逻辑计划

        返回:
            最优物理计划
        """
        self._node_counter = 0

        physical_root = self._visit(logical_plan.root)
        self._compute_costs(physical_root)

        plan = PhysicalPlan(
            root=physical_root,
            logical_plan=logical_plan,
            total_cost=physical_root.get_total_cost(),
        )

        plan.alternatives = self._generate_alternatives(logical_plan)

        return plan

    def _visit(self, node: LogicalPlanNode) -> PhysicalPlanNode:
        """
        后序遍历访问逻辑节点

        参数:
            node: 逻辑计划节点

        返回:
            物理计划节点
        """
        physical_children = [self._visit(child) for child in node.children]
        return node.accept(self, physical_children)

    def visit_source(
        self,
        node: LogicalSource,
        children: List[PhysicalPlanNode],
    ) -> PhysicalSource:
        """转换Source节点"""
        physical = PhysicalSource(
            node_id=self._next_id("source"),
            source_name=node.source_name,
            source_type=node.source_type,
            topic=node.source_name,
        )
        physical.output_columns = node.output_columns.copy()
        physical.parallelism = self.default_parallelism
        physical.statistics = Statistics(
            row_count=1000000,
            avg_row_size=512,
            distinct_values={col: 10000 for col in node.output_columns},
        )
        return physical

    def visit_scan(
        self,
        node: LogicalScan,
        children: List[PhysicalPlanNode],
    ) -> PhysicalScan:
        """转换Scan节点"""
        physical = PhysicalScan(
            node_id=self._next_id("scan"),
            table_name=node.table_name,
            children=children,
        )
        physical.output_columns = node.projected_columns or node.output_columns.copy()
        physical.projected_columns = node.projected_columns.copy()
        physical.predicate = node.predicate
        physical.parallelism = self.default_parallelism

        if node.predicate:
            physical.scan_type = "RANGE_SCAN"

        return physical

    def visit_filter(
        self,
        node: LogicalFilter,
        children: List[PhysicalPlanNode],
    ) -> PhysicalFilter:
        """转换Filter节点"""
        physical = PhysicalFilter(
            node_id=self._next_id("filter"),
            condition=node.condition,
            child=children[0] if children else None,
        )
        physical.output_columns = node.output_columns.copy()
        physical.parallelism = children[0].parallelism if children else self.default_parallelism
        physical.selectivity = self._estimate_selectivity(node.condition)
        return physical

    def visit_project(
        self,
        node: LogicalProject,
        children: List[PhysicalPlanNode],
    ) -> PhysicalProject:
        """转换Project节点"""
        physical = PhysicalProject(
            node_id=self._next_id("project"),
            projections=node.projections,
            child=children[0] if children else None,
            is_star=node.is_star,
        )
        physical.output_columns = node.output_columns.copy()
        physical.parallelism = children[0].parallelism if children else self.default_parallelism
        physical.code_generated = True
        return physical

    def visit_aggregate(
        self,
        node: LogicalAggregate,
        children: List[PhysicalPlanNode],
    ) -> PhysicalAggregate:
        """转换Aggregate节点"""
        group_by_cols = [str(g) for g in node.group_by]

        physical = PhysicalAggregate(
            node_id=self._next_id("aggregate"),
            group_by=group_by_cols,
            aggregate_expressions=node.aggregate_exprs,
            child=children[0] if children else None,
        )
        physical.output_columns = node.output_columns.copy()
        physical.parallelism = self.default_parallelism

        if len(group_by_cols) > 3:
            physical.strategy = AggregateStrategy.TWO_PHASE_AGGREGATE
        elif len(group_by_cols) > 0:
            physical.strategy = AggregateStrategy.HASH_AGGREGATE
        else:
            physical.strategy = AggregateStrategy.SORT_AGGREGATE

        if node.window_spec:
            physical.state_ttl = self._parse_interval_ms(node.window_spec.size) * 2

        return physical

    def visit_join(
        self,
        node: LogicalJoin,
        children: List[PhysicalPlanNode],
    ) -> PhysicalJoin:
        """转换Join节点"""
        left = children[0] if len(children) > 0 else None
        right = children[1] if len(children) > 1 else None

        physical = PhysicalJoin(
            node_id=self._next_id("join"),
            join_type=node.join_type,
            left=left,
            right=right,
            left_key=node.left_key,
            right_key=node.right_key,
            condition=node.condition,
        )
        physical.output_columns = node.output_columns.copy()
        physical.parallelism = self.default_parallelism

        if right and right.statistics.row_count < 10000:
            physical.strategy = JoinStrategy.BROADCAST_JOIN
            physical.broadcast_side = "right"
        elif node.join_type_streaming == "STREAM_STREAM":
            physical.strategy = JoinStrategy.WINDOW_JOIN
        elif len(node.left_key) > 0 and len(node.right_key) > 0:
            physical.strategy = JoinStrategy.HASH_JOIN
        else:
            physical.strategy = JoinStrategy.NESTED_LOOP_JOIN

        return physical

    def visit_window(
        self,
        node: LogicalWindow,
        children: List[PhysicalPlanNode],
    ) -> PhysicalWindow:
        """转换Window节点"""
        spec = node.window_spec
        window_size_ms = self._parse_interval_ms(spec.size) if spec else 3600000
        slide_ms = self._parse_interval_ms(spec.slide) if spec and spec.slide else None
        gap_ms = self._parse_interval_ms(spec.gap) if spec and spec.gap else None

        physical = PhysicalWindow(
            node_id=self._next_id("window"),
            window_type=spec.window_type.value if spec else "TUMBLE",
            time_column=spec.time_column if spec else "event_time",
            window_size=window_size_ms,
            child=children[0] if children else None,
        )
        physical.output_columns = node.output_columns.copy()
        physical.slide_interval = slide_ms
        physical.session_gap = gap_ms
        physical.partition_by = node.partition_by.copy()
        physical.order_by = node.order_by.copy()
        physical.window_functions = node.window_functions.copy()
        physical.parallelism = self.default_parallelism

        if node.partition_by:
            physical.strategy = WindowStrategy.KEYED_WINDOW
        else:
            physical.strategy = WindowStrategy.NON_KEYED_WINDOW

        if spec and spec.window_type.value == "SESSION":
            physical.strategy = WindowStrategy.SESSION_WINDOW

        return physical

    def visit_sink(
        self,
        node: LogicalSink,
        children: List[PhysicalPlanNode],
    ) -> PhysicalSink:
        """转换Sink节点"""
        physical = PhysicalSink(
            node_id=self._next_id("sink"),
            sink_name=node.sink_name,
            sink_type=node.sink_type,
            child=children[0] if children else None,
            mode=node.mode,
        )
        physical.output_columns = node.output_columns.copy()
        physical.parallelism = children[0].parallelism if children else self.default_parallelism
        return physical

    def _compute_costs(self, node: PhysicalPlanNode) -> Tuple[Cost, Statistics]:
        """
        递归计算节点代价和输出统计

        参数:
            node: 物理计划节点

        返回:
            (节点代价, 输出统计)
        """
        children_costs = []
        children_stats = []

        for child in node.children:
            cost, stats = self._compute_costs(child)
            children_costs.append(cost)
            children_stats.append(stats)

        node.statistics = self.cost_model.estimate_output_stats(node, children_stats)
        node.estimated_cost = self.cost_model.estimate_cost(node, children_stats)

        return node.estimated_cost, node.statistics

    def _generate_alternatives(self, logical_plan: LogicalPlan) -> List[PhysicalPlan]:
        """
        生成备选物理计划

        参数:
            logical_plan: 逻辑计划

        返回:
            备选物理计划列表
        """
        alternatives: List[PhysicalPlan] = []

        try:
            original_parallelism = self.default_parallelism

            self.default_parallelism = original_parallelism * 2
            self._node_counter = 0
            alt_root = self._visit(logical_plan.root)
            self._compute_costs(alt_root)
            alternatives.append(
                PhysicalPlan(
                    root=alt_root,
                    logical_plan=logical_plan,
                    total_cost=alt_root.get_total_cost(),
                )
            )

            self.default_parallelism = max(1, original_parallelism // 2)
            self._node_counter = 0
            alt_root2 = self._visit(logical_plan.root)
            self._compute_costs(alt_root2)
            alternatives.append(
                PhysicalPlan(
                    root=alt_root2,
                    logical_plan=logical_plan,
                    total_cost=alt_root2.get_total_cost(),
                )
            )

            self.default_parallelism = original_parallelism

        except Exception:
            pass

        return alternatives

    def _estimate_selectivity(self, condition: Any) -> float:
        """
        估算过滤条件选择率

        参数:
            condition: 过滤条件

        返回:
            选择率（0-1）
        """
        condition_str = str(condition).lower()

        if "=" in condition_str and "and" not in condition_str:
            return 0.01
        elif "<" in condition_str or ">" in condition_str:
            return 0.3
        elif "like" in condition_str:
            return 0.1
        elif "and" in condition_str:
            return 0.09
        elif "or" in condition_str:
            return 0.5

        return 0.5

    def _parse_interval_ms(self, interval: str) -> int:
        """
        解析时间间隔为毫秒

        参数:
            interval: 时间间隔字符串，如"INTERVAL '1' HOUR"

        返回:
            毫秒数
        """
        import re

        match = re.search(r"'(\d+)'", str(interval))
        if not match:
            return 3600000

        value = int(match.group(1))
        interval_upper = str(interval).upper()

        if "SECOND" in interval_upper:
            return value * 1000
        elif "MINUTE" in interval_upper:
            return value * 60 * 1000
        elif "HOUR" in interval_upper:
            return value * 60 * 60 * 1000
        elif "DAY" in interval_upper:
            return value * 24 * 60 * 60 * 1000

        return value * 1000
