from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple
from collections import deque

from src.domain.query.sql_parser import (
    ParsedStreamSQL,
    StreamSQLType,
    WindowType,
    JoinType,
    StreamColumn,
    StreamSource,
    StreamWindow,
    StreamJoin,
)


class LogicalNodeType(Enum):
    SCAN = "SCAN"
    FILTER = "FILTER"
    PROJECT = "PROJECT"
    AGGREGATE = "AGGREGATE"
    JOIN = "JOIN"
    SORT = "SORT"
    WINDOW = "WINDOW"
    UNION = "UNION"
    SINK = "SINK"
    LIMIT = "LIMIT"


@dataclass
class LogicalNode:
    node_type: LogicalNodeType
    node_id: str = ""
    properties: Dict[str, Any] = field(default_factory=dict)
    children: List["LogicalNode"] = field(default_factory=list)
    parent: Optional["LogicalNode"] = field(default=None, repr=False)
    estimated_rows: float = 0.0
    estimated_cost: float = 0.0

    def add_child(self, child: "LogicalNode") -> None:
        child.parent = self
        self.children.append(child)

    def remove_child(self, child: "LogicalNode") -> None:
        if child in self.children:
            self.children.remove(child)
            child.parent = None

    def replace_child(self, old: "LogicalNode", new: "LogicalNode") -> None:
        for i, c in enumerate(self.children):
            if c is old:
                self.children[i] = new
                new.parent = self
                old.parent = None
                return

    def get_output_fields(self) -> List[str]:
        if self.node_type == LogicalNodeType.SCAN:
            return self.properties.get("columns", [])
        elif self.node_type == LogicalNodeType.PROJECT:
            return self.properties.get("projected_columns", [])
        elif self.node_type == LogicalNodeType.AGGREGATE:
            return self.properties.get("output_columns", [])
        elif self.node_type == LogicalNodeType.JOIN:
            left_fields = self.children[0].get_output_fields() if self.children else []
            right_fields = self.children[1].get_output_fields() if len(self.children) > 1 else []
            return left_fields + right_fields
        elif self.children:
            return self.children[0].get_output_fields()
        return []

    def walk(self) -> List["LogicalNode"]:
        nodes = []
        queue = deque([self])
        while queue:
            node = queue.popleft()
            nodes.append(node)
            for child in node.children:
                queue.append(child)
        return nodes

    def deep_copy(self) -> "LogicalNode":
        import copy
        return copy.deepcopy(self)


@dataclass
class LogicalPlan:
    root: Optional[LogicalNode] = None
    source_sql: str = ""

    def get_nodes(self) -> List[LogicalNode]:
        if self.root is None:
            return []
        return self.root.walk()

    def get_scans(self) -> List[LogicalNode]:
        return [n for n in self.get_nodes() if n.node_type == LogicalNodeType.SCAN]

    def get_joins(self) -> List[LogicalNode]:
        return [n for n in self.get_nodes() if n.node_type == LogicalNodeType.JOIN]

    def get_aggregates(self) -> List[LogicalNode]:
        return [n for n in self.get_nodes() if n.node_type == LogicalNodeType.AGGREGATE]

    def estimate_cost(self) -> float:
        total = 0.0
        for node in self.get_nodes():
            total += node.estimated_cost
        return total


class LogicalPlanBuilder:
    _node_counter = 0

    @classmethod
    def _next_id(cls) -> str:
        cls._node_counter += 1
        return f"ln_{cls._node_counter}"

    def build(self, parsed: ParsedStreamSQL) -> LogicalPlan:
        plan = LogicalPlan(source_sql=parsed.original_sql)

        if parsed.sql_type in (StreamSQLType.SELECT, StreamSQLType.INSERT):
            root = self._build_select_plan(parsed)
            plan.root = root

            if parsed.sql_type == StreamSQLType.INSERT:
                sink = LogicalNode(
                    node_type=LogicalNodeType.SINK,
                    node_id=self._next_id(),
                    properties={"target_table": parsed.target_table},
                    estimated_cost=1.0,
                )
                sink.add_child(root)
                plan.root = sink

        elif parsed.sql_type == StreamSQLType.CREATE_STREAM:
            if parsed.columns or parsed.sources:
                root = self._build_select_plan(parsed)
                sink = LogicalNode(
                    node_type=LogicalNodeType.SINK,
                    node_id=self._next_id(),
                    properties={"target_stream": parsed.stream_name},
                    estimated_cost=1.0,
                )
                sink.add_child(root)
                plan.root = sink

        if plan.root:
            self._estimate_cardinalities(plan.root)

        return plan

    def _build_select_plan(self, parsed: ParsedStreamSQL) -> LogicalNode:
        current: Optional[LogicalNode] = None

        for src in parsed.sources:
            scan = LogicalNode(
                node_type=LogicalNodeType.SCAN,
                node_id=self._next_id(),
                properties={
                    "source_name": src.name,
                    "alias": src.alias,
                    "is_stream": src.is_stream,
                    "columns": [],
                },
                estimated_rows=10000.0,
                estimated_cost=100.0,
            )
            if current is None:
                current = scan
            else:
                if parsed.join.join_type != JoinType.NONE:
                    current = self._build_join(current, scan, parsed.join)
                else:
                    union = LogicalNode(
                        node_type=LogicalNodeType.UNION,
                        node_id=self._next_id(),
                        estimated_cost=10.0,
                    )
                    union.add_child(current)
                    union.add_child(scan)
                    current = union

        if current is None:
            current = LogicalNode(
                node_type=LogicalNodeType.SCAN,
                node_id=self._next_id(),
                properties={"source_name": "__unknown__", "is_stream": False, "columns": []},
                estimated_rows=0.0,
                estimated_cost=0.0,
            )

        if parsed.join.join_type != JoinType.NONE and parsed.join.right_source:
            right_scan = LogicalNode(
                node_type=LogicalNodeType.SCAN,
                node_id=self._next_id(),
                properties={
                    "source_name": parsed.join.right_source,
                    "is_stream": False,
                    "columns": [],
                },
                estimated_rows=10000.0,
                estimated_cost=100.0,
            )
            current = self._build_join(current, right_scan, parsed.join)

        if parsed.where_clause:
            filter_node = LogicalNode(
                node_type=LogicalNodeType.FILTER,
                node_id=self._next_id(),
                properties={"condition": parsed.where_clause},
                estimated_cost=50.0,
            )
            filter_node.add_child(current)
            current = filter_node

        if parsed.window.window_type != WindowType.NONE:
            window_node = LogicalNode(
                node_type=LogicalNodeType.WINDOW,
                node_id=self._next_id(),
                properties={
                    "window_type": parsed.window.window_type.value,
                    "size": parsed.window.size,
                    "slide": parsed.window.slide,
                    "gap": parsed.window.gap,
                    "time_field": parsed.window.time_field,
                },
                estimated_cost=200.0,
            )
            window_node.add_child(current)
            current = window_node

        if parsed.group_by:
            agg_columns = [c.name for c in parsed.columns if c.aggregation]
            output_columns = [c.alias or c.name for c in parsed.columns]
            agg_node = LogicalNode(
                node_type=LogicalNodeType.AGGREGATE,
                node_id=self._next_id(),
                properties={
                    "group_by": parsed.group_by,
                    "aggregations": agg_columns,
                    "output_columns": output_columns,
                },
                estimated_cost=300.0,
            )
            agg_node.add_child(current)
            current = agg_node

        projected_columns = [c.alias or c.name for c in parsed.columns]
        if projected_columns:
            project_node = LogicalNode(
                node_type=LogicalNodeType.PROJECT,
                node_id=self._next_id(),
                properties={"projected_columns": projected_columns},
                estimated_cost=10.0,
            )
            project_node.add_child(current)
            current = project_node

        if parsed.order_by:
            sort_node = LogicalNode(
                node_type=LogicalNodeType.SORT,
                node_id=self._next_id(),
                properties={
                    "order_by": parsed.order_by,
                },
                estimated_cost=500.0,
            )
            sort_node.add_child(current)
            current = sort_node

        if parsed.limit is not None:
            limit_node = LogicalNode(
                node_type=LogicalNodeType.LIMIT,
                node_id=self._next_id(),
                properties={"limit": parsed.limit},
                estimated_cost=1.0,
            )
            limit_node.add_child(current)
            current = limit_node

        return current

    def _build_join(self, left: LogicalNode, right: LogicalNode, join: StreamJoin) -> LogicalNode:
        join_node = LogicalNode(
            node_type=LogicalNodeType.JOIN,
            node_id=self._next_id(),
            properties={
                "join_type": join.join_type.value,
                "condition": join.condition,
            },
            estimated_rows=left.estimated_rows * right.estimated_rows * 0.1,
            estimated_cost=1000.0,
        )
        join_node.add_child(left)
        join_node.add_child(right)
        return join_node

    def _estimate_cardinalities(self, node: LogicalNode) -> None:
        for child in node.children:
            self._estimate_cardinalities(child)

        if node.node_type == LogicalNodeType.FILTER:
            node.estimated_rows = node.children[0].estimated_rows * 0.3 if node.children else 0
        elif node.node_type == LogicalNodeType.AGGREGATE:
            node.estimated_rows = node.children[0].estimated_rows * 0.1 if node.children else 0
        elif node.node_type == LogicalNodeType.JOIN:
            left_rows = node.children[0].estimated_rows if node.children else 0
            right_rows = node.children[1].estimated_rows if len(node.children) > 1 else 0
            node.estimated_rows = left_rows * right_rows * 0.01
        elif node.node_type == LogicalNodeType.LIMIT:
            limit = node.properties.get("limit", 0)
            node.estimated_rows = min(float(limit), node.children[0].estimated_rows if node.children else 0)
        elif node.node_type == LogicalNodeType.PROJECT:
            node.estimated_rows = node.children[0].estimated_rows if node.children else 0
        elif node.node_type == LogicalNodeType.SORT:
            node.estimated_rows = node.children[0].estimated_rows if node.children else 0
