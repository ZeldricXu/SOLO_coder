from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional

from streamsql.core.models import generate_id


class NodeType(str, Enum):
    SCAN = "scan"
    FILTER = "filter"
    PROJECT = "project"
    JOIN = "join"
    AGGREGATE = "aggregate"
    WINDOW = "window"
    SORT = "sort"
    LIMIT = "limit"
    UNION = "union"
    INSERT = "insert"
    UPDATE = "update"
    DELETE = "delete"
    STREAM = "stream"
    WATERMARK = "watermark"


class JoinType(str, Enum):
    INNER = "inner"
    LEFT = "left"
    RIGHT = "right"
    FULL = "full"
    CROSS = "cross"
    LEFT_OUTER = "left_outer"
    RIGHT_OUTER = "right_outer"


class AggregateFunction(str, Enum):
    SUM = "SUM"
    AVG = "AVG"
    COUNT = "COUNT"
    MIN = "MIN"
    MAX = "MAX"
    COUNT_DISTINCT = "COUNT_DISTINCT"


@dataclass
class LogicalNode:
    node_id: str = field(default_factory=lambda: generate_id("ln"))
    node_type: NodeType = NodeType.SCAN
    children: list["LogicalNode"] = field(default_factory=list)
    properties: dict[str, Any] = field(default_factory=dict)
    estimated_cost: float = 0.0
    estimated_rows: Optional[int] = None

    def add_child(self, child: "LogicalNode") -> None:
        self.children.append(child)

    def get_all_nodes(self) -> list["LogicalNode"]:
        nodes = [self]
        for child in self.children:
            nodes.extend(child.get_all_nodes())
        return nodes

    def to_dict(self) -> dict[str, Any]:
        return {
            "node_id": self.node_id,
            "node_type": self.node_type.value,
            "children": [c.to_dict() for c in self.children],
            "properties": self.properties,
            "estimated_cost": self.estimated_cost,
            "estimated_rows": self.estimated_rows,
        }


@dataclass
class LogicalPlan:
    plan_id: str = field(default_factory=lambda: generate_id("lp"))
    root: Optional[LogicalNode] = None
    source_queries: list[str] = field(default_factory=list)
    optimized: bool = False
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "plan_id": self.plan_id,
            "root": self.root.to_dict() if self.root else None,
            "source_queries": self.source_queries,
            "optimized": self.optimized,
            "metadata": self.metadata,
        }

    def walk(self) -> list[LogicalNode]:
        if not self.root:
            return []
        return self.root.get_all_nodes()


class LogicalPlanner:
    def __init__(self):
        self.plan_id = generate_id("lp")

    def plan(self, parsed_query: Any) -> LogicalPlan:
        plan = LogicalPlan(source_queries=[parsed_query.raw_sql])

        if parsed_query.query_type.value == "SELECT":
            root = self._build_select_plan(parsed_query)
            plan.root = root
        elif parsed_query.query_type.value == "INSERT":
            plan.root = self._build_insert_plan(parsed_query)
        elif parsed_query.query_type.value == "UPDATE":
            plan.root = self._build_update_plan(parsed_query)
        elif parsed_query.query_type.value == "DELETE":
            plan.root = self._build_delete_plan(parsed_query)

        plan.metadata["tables"] = parsed_query.tables
        plan.metadata["is_streaming"] = parsed_query.is_streaming

        return plan

    def _build_select_plan(self, parsed_query: Any) -> LogicalNode:
        current: Optional[LogicalNode] = None

        for table in parsed_query.tables:
            scan_node = LogicalNode(
                node_type=NodeType.SCAN,
                properties={"table": table, "columns": [c["name"] for c in parsed_query.columns]},
            )
            if current is None:
                current = scan_node
            else:
                join_node = LogicalNode(
                    node_type=NodeType.JOIN,
                    properties={"join_type": JoinType.INNER.value},
                    children=[current, scan_node],
                )
                current = join_node

        if parsed_query.where_clause:
            filter_node = LogicalNode(
                node_type=NodeType.FILTER,
                properties={"condition": parsed_query.where_clause},
                children=[current] if current else [],
            )
            current = filter_node

        if parsed_query.group_by:
            agg_exprs = [c for c in parsed_query.columns if self._is_aggregate(c["expression"])]
            agg_node = LogicalNode(
                node_type=NodeType.AGGREGATE,
                properties={
                    "group_by": parsed_query.group_by,
                    "aggregations": agg_exprs,
                },
                children=[current] if current else [],
            )
            current = agg_node

        if parsed_query.window_spec:
            window_node = LogicalNode(
                node_type=NodeType.WINDOW,
                properties=parsed_query.window_spec,
                children=[current] if current else [],
            )
            current = window_node

        if parsed_query.order_by:
            sort_node = LogicalNode(
                node_type=NodeType.SORT,
                properties={"order_by": parsed_query.order_by},
                children=[current] if current else [],
            )
            current = sort_node

        if parsed_query.limit is not None:
            limit_node = LogicalNode(
                node_type=NodeType.LIMIT,
                properties={"limit": parsed_query.limit, "offset": parsed_query.offset or 0},
                children=[current] if current else [],
            )
            current = limit_node

        project_node = LogicalNode(
            node_type=NodeType.PROJECT,
            properties={"columns": parsed_query.columns},
            children=[current] if current else [],
        )
        current = project_node

        if parsed_query.is_streaming:
            stream_node = LogicalNode(
                node_type=NodeType.STREAM,
                properties={},
                children=[current] if current else [],
            )
            current = stream_node

        return current or LogicalNode(node_type=NodeType.SCAN, properties={})

    def _is_aggregate(self, expr: str) -> bool:
        agg_funcs = ["SUM(", "AVG(", "COUNT(", "MIN(", "MAX("]
        return any(f in expr.upper() for f in agg_funcs)

    def _build_insert_plan(self, parsed_query: Any) -> LogicalNode:
        return LogicalNode(
            node_type=NodeType.INSERT,
            properties={"table": parsed_query.tables[0] if parsed_query.tables else "unknown"},
        )

    def _build_update_plan(self, parsed_query: Any) -> LogicalNode:
        return LogicalNode(
            node_type=NodeType.UPDATE,
            properties={
                "table": parsed_query.tables[0] if parsed_query.tables else "unknown",
                "where": parsed_query.where_clause,
            },
        )

    def _build_delete_plan(self, parsed_query: Any) -> LogicalNode:
        return LogicalNode(
            node_type=NodeType.DELETE,
            properties={
                "table": parsed_query.tables[0] if parsed_query.tables else "unknown",
                "where": parsed_query.where_clause,
            },
        )

    def estimate_cost(self, plan: LogicalPlan) -> float:
        if not plan.root:
            return 0.0
        return self._estimate_node_cost(plan.root)

    def _estimate_node_cost(self, node: LogicalNode) -> float:
        cost = 0.0
        for child in node.children:
            cost += self._estimate_node_cost(child)

        base_costs = {
            NodeType.SCAN: 10.0,
            NodeType.FILTER: 5.0,
            NodeType.PROJECT: 2.0,
            NodeType.JOIN: 100.0,
            NodeType.AGGREGATE: 50.0,
            NodeType.WINDOW: 75.0,
            NodeType.SORT: 200.0,
            NodeType.LIMIT: 1.0,
            NodeType.STREAM: 25.0,
        }
        cost += base_costs.get(node.node_type, 10.0)

        node.estimated_cost = cost
        return cost
