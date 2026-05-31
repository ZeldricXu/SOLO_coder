"""
logical_plan - 逻辑计划节点定义与构建器

定义了流式查询的逻辑计划节点类型，以及从解析后的SQL构建逻辑计划的构建器。
逻辑计划是一种与具体执行引擎无关的抽象表示，描述了查询的逻辑执行流程。

节点类型包括：
- Source: 数据源节点
- Scan: 表扫描节点
- Filter: 过滤节点
- Project: 投影节点（列选择）
- Aggregate: 聚合节点
- Join: 连接节点
- Window: 窗口计算节点
- Sink: 数据输出节点
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set, Tuple, Union

import sqlglot
from sqlglot import exp

from .sql_parser import ParsedStatement, WindowSpec, WatermarkSpec, EmitStrategy


class NodeType(Enum):
    """逻辑计划节点类型枚举"""
    SOURCE = "SOURCE"
    SCAN = "SCAN"
    FILTER = "FILTER"
    PROJECT = "PROJECT"
    AGGREGATE = "AGGREGATE"
    JOIN = "JOIN"
    WINDOW = "WINDOW"
    SINK = "SINK"


class JoinType(Enum):
    """连接类型枚举"""
    INNER = "INNER"
    LEFT = "LEFT"
    RIGHT = "RIGHT"
    FULL = "FULL"
    SEMI = "SEMI"
    ANTI = "ANTI"
    STREAM_STREAM = "STREAM_STREAM"
    STREAM_TABLE = "STREAM_TABLE"


@dataclass
class AggregateExpr:
    """
    聚合表达式

    属性:
        function: 聚合函数名（如COUNT, SUM, AVG等）
        arguments: 函数参数列表
        alias: 别名
        distinct: 是否去重
    """
    function: str
    arguments: List[Any]
    alias: Optional[str] = None
    distinct: bool = False


@dataclass
class LogicalPlanNode(ABC):
    """
    逻辑计划节点抽象基类

    属性:
        node_id: 节点唯一标识
        node_type: 节点类型
        children: 子节点列表
        output_columns: 输出列名列表
        statistics: 节点统计信息
    """
    node_id: str
    node_type: NodeType
    children: List["LogicalPlanNode"] = field(default_factory=list)
    output_columns: List[str] = field(default_factory=list)
    statistics: Dict[str, Any] = field(default_factory=dict)

    @abstractmethod
    def accept(self, visitor: "LogicalPlanVisitor") -> Any:
        """
        接受访问者访问

        参数:
            visitor: 访问者对象

        返回:
            访问结果
        """
        ...

    def get_all_columns(self) -> Set[str]:
        """
        获取节点及其所有子节点涉及的列名集合

        返回:
            列名集合
        """
        columns = set(self.output_columns)
        for child in self.children:
            columns.update(child.get_all_columns())
        return columns

    def clone(self) -> "LogicalPlanNode":
        """
        克隆节点

        返回:
            新的节点实例
        """
        import copy
        return copy.deepcopy(self)

    def pretty_print(self, indent: int = 0) -> str:
        """
        格式化打印逻辑计划树

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

        if self.output_columns:
            result += f" -> {', '.join(self.output_columns)}"

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


class LogicalPlanVisitor(ABC):
    """
    逻辑计划访问者抽象基类

    实现访问者模式，用于遍历和操作逻辑计划树。
    """

    @abstractmethod
    def visit_source(self, node: "Source") -> Any: ...

    @abstractmethod
    def visit_scan(self, node: "Scan") -> Any: ...

    @abstractmethod
    def visit_filter(self, node: "Filter") -> Any: ...

    @abstractmethod
    def visit_project(self, node: "Project") -> Any: ...

    @abstractmethod
    def visit_aggregate(self, node: "Aggregate") -> Any: ...

    @abstractmethod
    def visit_join(self, node: "Join") -> Any: ...

    @abstractmethod
    def visit_window(self, node: "Window") -> Any: ...

    @abstractmethod
    def visit_sink(self, node: "Sink") -> Any: ...


@dataclass
class Source(LogicalPlanNode):
    """
    数据源节点

    属性:
        source_name: 数据源名称
        source_type: 数据源类型（Kafka, Pulsar, File等）
        schema: 数据源Schema
        watermark: 水位线定义
    """
    source_name: str = ""
    source_type: str = "KAFKA"
    schema: Dict[str, str] = field(default_factory=dict)
    watermark: Optional[WatermarkSpec] = None

    def __init__(
        self,
        node_id: str,
        source_name: str,
        source_type: str = "KAFKA",
        schema: Optional[Dict[str, str]] = None,
        watermark: Optional[WatermarkSpec] = None,
    ) -> None:
        super().__init__(
            node_id=node_id,
            node_type=NodeType.SOURCE,
            children=[],
            output_columns=list(schema.keys()) if schema else [],
        )
        self.source_name = source_name
        self.source_type = source_type
        self.schema = schema or {}
        self.watermark = watermark

    def accept(self, visitor: LogicalPlanVisitor) -> Any:
        return visitor.visit_source(self)

    def _node_details(self) -> str:
        return f"name={self.source_name}, type={self.source_type}"


@dataclass
class Scan(LogicalPlanNode):
    """
    表扫描节点

    属性:
        table_name: 表名
        projected_columns: 投影列列表
        predicate: 下推的谓词条件
    """
    table_name: str = ""
    projected_columns: List[str] = field(default_factory=list)
    predicate: Optional[exp.Expression] = None

    def __init__(
        self,
        node_id: str,
        table_name: str,
        children: Optional[List[LogicalPlanNode]] = None,
        projected_columns: Optional[List[str]] = None,
        predicate: Optional[exp.Expression] = None,
    ) -> None:
        super().__init__(
            node_id=node_id,
            node_type=NodeType.SCAN,
            children=children or [],
            output_columns=projected_columns or [],
        )
        self.table_name = table_name
        self.projected_columns = projected_columns or []
        self.predicate = predicate

    def accept(self, visitor: LogicalPlanVisitor) -> Any:
        return visitor.visit_scan(self)

    def _node_details(self) -> str:
        details = f"table={self.table_name}"
        if self.predicate:
            details += f", predicate={str(self.predicate)}"
        return details


@dataclass
class Filter(LogicalPlanNode):
    """
    过滤节点

    属性:
        condition: 过滤条件表达式
        push_down: 是否已下推
    """
    condition: Optional[exp.Expression] = None
    push_down: bool = False

    def __init__(
        self,
        node_id: str,
        condition: exp.Expression,
        child: Optional[LogicalPlanNode] = None,
    ) -> None:
        children = [child] if child else []
        output_columns = child.output_columns if child else []
        super().__init__(
            node_id=node_id,
            node_type=NodeType.FILTER,
            children=children,
            output_columns=output_columns,
        )
        self.condition = condition

    def accept(self, visitor: LogicalPlanVisitor) -> Any:
        return visitor.visit_filter(self)

    def _node_details(self) -> str:
        return f"condition={str(self.condition)}"


@dataclass
class Project(LogicalPlanNode):
    """
    投影节点（列选择/表达式计算）

    属性:
        projections: 投影表达式列表，(表达式, 别名)元组
        is_star: 是否为SELECT *
    """
    projections: List[Tuple[exp.Expression, Optional[str]]] = field(default_factory=list)
    is_star: bool = False

    def __init__(
        self,
        node_id: str,
        projections: List[Tuple[exp.Expression, Optional[str]]],
        child: Optional[LogicalPlanNode] = None,
        is_star: bool = False,
    ) -> None:
        children = [child] if child else []
        output_columns = [alias or str(expr) for expr, alias in projections]
        super().__init__(
            node_id=node_id,
            node_type=NodeType.PROJECT,
            children=children,
            output_columns=output_columns,
        )
        self.projections = projections
        self.is_star = is_star

    def accept(self, visitor: LogicalPlanVisitor) -> Any:
        return visitor.visit_project(self)

    def _node_details(self) -> str:
        if self.is_star:
            return "*"
        cols = [alias or str(expr) for expr, alias in self.projections]
        return f"columns={', '.join(cols[:3])}{'...' if len(cols) > 3 else ''}"


@dataclass
class Aggregate(LogicalPlanNode):
    """
    聚合节点

    属性:
        group_by: GROUP BY表达式列表
        aggregate_exprs: 聚合表达式列表
        having: HAVING条件
        window_spec: 窗口定义（流式聚合）
    """
    group_by: List[exp.Expression] = field(default_factory=list)
    aggregate_exprs: List[AggregateExpr] = field(default_factory=list)
    having: Optional[exp.Expression] = None
    window_spec: Optional[WindowSpec] = None

    def __init__(
        self,
        node_id: str,
        group_by: List[exp.Expression],
        aggregate_exprs: List[AggregateExpr],
        child: Optional[LogicalPlanNode] = None,
        having: Optional[exp.Expression] = None,
        window_spec: Optional[WindowSpec] = None,
    ) -> None:
        children = [child] if child else []
        output_cols = [str(g) for g in group_by]
        output_cols.extend([e.alias or f"{e.function}_{i}" for i, e in enumerate(aggregate_exprs)])
        super().__init__(
            node_id=node_id,
            node_type=NodeType.AGGREGATE,
            children=children,
            output_columns=output_cols,
        )
        self.group_by = group_by
        self.aggregate_exprs = aggregate_exprs
        self.having = having
        self.window_spec = window_spec

    def accept(self, visitor: LogicalPlanVisitor) -> Any:
        return visitor.visit_aggregate(self)

    def _node_details(self) -> str:
        details = []
        if self.group_by:
            details.append(f"group_by={len(self.group_by)} cols")
        details.append(f"aggregates={len(self.aggregate_exprs)}")
        if self.window_spec:
            details.append(f"window={self.window_spec.window_type.value}")
        return ", ".join(details)


@dataclass
class Join(LogicalPlanNode):
    """
    连接节点

    属性:
        join_type: 连接类型
        left_key: 左表连接键
        right_key: 右表连接键
        condition: 连接条件
        join_type_streaming: 流式连接类型
    """
    join_type: JoinType = JoinType.INNER
    left_key: List[str] = field(default_factory=list)
    right_key: List[str] = field(default_factory=list)
    condition: Optional[exp.Expression] = None
    join_type_streaming: str = "STREAM_STREAM"

    def __init__(
        self,
        node_id: str,
        join_type: JoinType,
        left: LogicalPlanNode,
        right: LogicalPlanNode,
        left_key: List[str],
        right_key: List[str],
        condition: Optional[exp.Expression] = None,
    ) -> None:
        output_cols = left.output_columns + right.output_columns
        super().__init__(
            node_id=node_id,
            node_type=NodeType.JOIN,
            children=[left, right],
            output_columns=output_cols,
        )
        self.join_type = join_type
        self.left_key = left_key
        self.right_key = right_key
        self.condition = condition

    def accept(self, visitor: LogicalPlanVisitor) -> Any:
        return visitor.visit_join(self)

    def _node_details(self) -> str:
        return (
            f"type={self.join_type.value}, "
            f"left_key={self.left_key}, right_key={self.right_key}"
        )


@dataclass
class Window(LogicalPlanNode):
    """
    窗口计算节点

    属性:
        window_spec: 窗口定义
        partition_by: 分区列列表
        order_by: 排序列列表
        window_functions: 窗口函数列表
    """
    window_spec: Optional[WindowSpec] = None
    partition_by: List[str] = field(default_factory=list)
    order_by: List[str] = field(default_factory=list)
    window_functions: List[AggregateExpr] = field(default_factory=list)

    def __init__(
        self,
        node_id: str,
        window_spec: WindowSpec,
        child: Optional[LogicalPlanNode] = None,
        partition_by: Optional[List[str]] = None,
        order_by: Optional[List[str]] = None,
        window_functions: Optional[List[AggregateExpr]] = None,
    ) -> None:
        children = [child] if child else []
        output_cols = child.output_columns.copy() if child else []
        if window_functions:
            output_cols.extend([wf.alias or f"wf_{i}" for i, wf in enumerate(window_functions)])
        super().__init__(
            node_id=node_id,
            node_type=NodeType.WINDOW,
            children=children,
            output_columns=output_cols,
        )
        self.window_spec = window_spec
        self.partition_by = partition_by or []
        self.order_by = order_by or []
        self.window_functions = window_functions or []

    def accept(self, visitor: LogicalPlanVisitor) -> Any:
        return visitor.visit_window(self)

    def _node_details(self) -> str:
        details = [f"type={self.window_spec.window_type.value}"]
        if self.partition_by:
            details.append(f"partition_by={self.partition_by}")
        if self.window_functions:
            details.append(f"functions={len(self.window_functions)}")
        return ", ".join(details)


@dataclass
class Sink(LogicalPlanNode):
    """
    数据输出节点

    属性:
        sink_name: 输出名称
        sink_type: 输出类型（Kafka, File, Database等）
        emit_strategy: 输出策略
        mode: 输出模式（APPEND, UPSERT, RETRACT）
    """
    sink_name: str = ""
    sink_type: str = "KAFKA"
    emit_strategy: Optional[EmitStrategy] = None
    mode: str = "APPEND"

    def __init__(
        self,
        node_id: str,
        sink_name: str,
        sink_type: str = "KAFKA",
        child: Optional[LogicalPlanNode] = None,
        emit_strategy: Optional[EmitStrategy] = None,
        mode: str = "APPEND",
    ) -> None:
        children = [child] if child else []
        output_columns = child.output_columns if child else []
        super().__init__(
            node_id=node_id,
            node_type=NodeType.SINK,
            children=children,
            output_columns=output_columns,
        )
        self.sink_name = sink_name
        self.sink_type = sink_type
        self.emit_strategy = emit_strategy
        self.mode = mode

    def accept(self, visitor: LogicalPlanVisitor) -> Any:
        return visitor.visit_sink(self)

    def _node_details(self) -> str:
        return f"name={self.sink_name}, type={self.sink_type}, mode={self.mode}"


@dataclass
class LogicalPlan:
    """
    逻辑计划封装

    属性:
        root: 逻辑计划根节点
        original_sql: 原始SQL语句
        metadata: 元数据信息
    """
    root: LogicalPlanNode
    original_sql: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)

    def pretty_print(self) -> str:
        """
        格式化打印逻辑计划

        返回:
            格式化的逻辑计划字符串
        """
        return self.root.pretty_print()

    def collect_nodes(self) -> List[LogicalPlanNode]:
        """
        收集所有节点

        返回:
            节点列表（后序遍历）
        """
        nodes: List[LogicalPlanNode] = []

        def collect(node: LogicalPlanNode) -> None:
            for child in node.children:
                collect(child)
            nodes.append(node)

        collect(self.root)
        return nodes

    def replace_node(self, old_node: LogicalPlanNode, new_node: LogicalPlanNode) -> None:
        """
        替换计划中的节点

        参数:
            old_node: 要替换的旧节点
            new_node: 新节点
        """

        def replace(node: LogicalPlanNode) -> LogicalPlanNode:
            if node is old_node:
                return new_node
            node.children = [replace(child) for child in node.children]
            return node

        self.root = replace(self.root)

    def validate(self) -> Tuple[bool, List[str]]:
        """
        验证逻辑计划的正确性

        返回:
            (是否有效, 错误信息列表)
        """
        errors: List[str] = []

        nodes = self.collect_nodes()

        for node in nodes:
            if node.node_type == NodeType.SOURCE and len(node.children) > 0:
                errors.append(f"SOURCE节点{node.node_id}不能有子节点")
            if node.node_type == NodeType.SINK and len(node.children) != 1:
                errors.append(f"SINK节点{node.node_id}必须有且仅有一个子节点")
            if node.node_type == NodeType.JOIN and len(node.children) != 2:
                errors.append(f"JOIN节点{node.node_id}必须有两个子节点")

        return len(errors) == 0, errors


class LogicalPlanBuilder:
    """
    逻辑计划构建器

    从解析后的SQL语句构建逻辑计划树。
    """

    def __init__(self) -> None:
        """初始化逻辑计划构建器"""
        self._node_counter: int = 0

    def _next_id(self, prefix: str) -> str:
        """
        生成下一个节点ID

        参数:
            prefix: 节点ID前缀

        返回:
            节点ID
        """
        self._node_counter += 1
        return f"{prefix}_{self._node_counter}"

    def build(self, parsed_stmt: ParsedStatement) -> LogicalPlan:
        """
        从解析后的SQL语句构建逻辑计划

        参数:
            parsed_stmt: 解析后的SQL语句

        返回:
            构建好的逻辑计划
        """
        self._node_counter = 0

        current_node: LogicalPlanNode = self._build_source(parsed_stmt)

        if parsed_stmt.where_condition:
            current_node = self._build_filter(current_node, parsed_stmt.where_condition)

        if parsed_stmt.group_by or parsed_stmt.window_specs:
            current_node = self._build_aggregate(current_node, parsed_stmt)
        elif parsed_stmt.select_items:
            current_node = self._build_project(current_node, parsed_stmt)

        current_node = self._build_window(current_node, parsed_stmt)

        current_node = self._build_sink(current_node, parsed_stmt)

        plan = LogicalPlan(
            root=current_node,
            original_sql=parsed_stmt.original_sql,
            metadata={
                "is_streaming": parsed_stmt.is_streaming,
                "window_count": len(parsed_stmt.window_specs),
                "has_watermark": parsed_stmt.watermark is not None,
            },
        )

        return plan

    def _build_source(self, parsed_stmt: ParsedStatement) -> LogicalPlanNode:
        """
        构建Source节点

        参数:
            parsed_stmt: 解析后的SQL语句

        返回:
            Source节点
        """
        table_name = parsed_stmt.from_table or "unknown_table"

        source = Source(
            node_id=self._next_id("source"),
            source_name=table_name,
            source_type="KAFKA",
            schema={},
            watermark=parsed_stmt.watermark,
        )

        scan = Scan(
            node_id=self._next_id("scan"),
            table_name=table_name,
            children=[source],
        )

        return scan

    def _build_filter(
        self,
        child: LogicalPlanNode,
        condition: exp.Expression,
    ) -> LogicalPlanNode:
        """
        构建Filter节点

        参数:
            child: 子节点
            condition: 过滤条件

        返回:
            Filter节点
        """
        return Filter(
            node_id=self._next_id("filter"),
            condition=condition,
            child=child,
        )

    def _build_project(
        self,
        child: LogicalPlanNode,
        parsed_stmt: ParsedStatement,
    ) -> LogicalPlanNode:
        """
        构建Project节点

        参数:
            child: 子节点
            parsed_stmt: 解析后的SQL语句

        返回:
            Project节点
        """
        projections: List[Tuple[exp.Expression, Optional[str]]] = []
        is_star = False

        for item in parsed_stmt.select_items:
            if isinstance(item, exp.Star):
                is_star = True
                break
            if isinstance(item, exp.Alias):
                projections.append((item.this, item.alias))
            else:
                alias = item.name if hasattr(item, "name") else None
                projections.append((item, alias))

        return Project(
            node_id=self._next_id("project"),
            projections=projections,
            child=child,
            is_star=is_star,
        )

    def _build_aggregate(
        self,
        child: LogicalPlanNode,
        parsed_stmt: ParsedStatement,
    ) -> LogicalPlanNode:
        """
        构建Aggregate节点

        参数:
            child: 子节点
            parsed_stmt: 解析后的SQL语句

        返回:
            Aggregate节点
        """
        aggregate_exprs: List[AggregateExpr] = []
        projections: List[Tuple[exp.Expression, Optional[str]]] = []

        for item in parsed_stmt.select_items:
            expr = item.this if isinstance(item, exp.Alias) else item
            alias = item.alias if isinstance(item, exp.Alias) else None

            if isinstance(expr, exp.Func):
                agg_expr = self._parse_aggregate_expr(expr, alias)
                if agg_expr:
                    aggregate_exprs.append(agg_expr)
                    continue

            projections.append((item.this if isinstance(item, exp.Alias) else item, alias))

        window_spec = parsed_stmt.window_specs[0] if parsed_stmt.window_specs else None

        aggregate = Aggregate(
            node_id=self._next_id("aggregate"),
            group_by=parsed_stmt.group_by,
            aggregate_exprs=aggregate_exprs,
            child=child,
            having=parsed_stmt.having_condition,
            window_spec=window_spec,
        )

        if projections:
            return Project(
                node_id=self._next_id("project"),
                projections=projections,
                child=aggregate,
            )

        return aggregate

    def _parse_aggregate_expr(
        self,
        func: exp.Func,
        alias: Optional[str],
    ) -> Optional[AggregateExpr]:
        """
        解析聚合表达式

        参数:
            func: 函数表达式
            alias: 别名

        返回:
            AggregateExpr对象，如果不是聚合函数则返回None
        """
        agg_functions = {"COUNT", "SUM", "AVG", "MIN", "MAX", "FIRST_VALUE", "LAST_VALUE"}
        func_name = func.name.upper()

        if func_name not in agg_functions:
            return None

        args = list(func.args.values())
        distinct = func.find(exp.Distinct) is not None

        return AggregateExpr(
            function=func_name,
            arguments=args,
            alias=alias,
            distinct=distinct,
        )

    def _build_window(
        self,
        child: LogicalPlanNode,
        parsed_stmt: ParsedStatement,
    ) -> LogicalPlanNode:
        """
        构建Window节点

        参数:
            child: 子节点
            parsed_stmt: 解析后的SQL语句

        返回:
            Window节点（如果有窗口定义），否则返回原节点
        """
        current = child

        for spec in parsed_stmt.window_specs:
            window = Window(
                node_id=self._next_id("window"),
                window_spec=spec,
                child=current,
            )
            current = window

        return current

    def _build_sink(
        self,
        child: LogicalPlanNode,
        parsed_stmt: ParsedStatement,
    ) -> LogicalPlanNode:
        """
        构建Sink节点

        参数:
            child: 子节点
            parsed_stmt: 解析后的SQL语句

        返回:
            Sink节点
        """
        return Sink(
            node_id=self._next_id("sink"),
            sink_name="output",
            sink_type="KAFKA",
            child=child,
            emit_strategy=parsed_stmt.emit_strategy,
        )
