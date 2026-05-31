from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional

from src.domain.query.logical_plan import LogicalPlan, LogicalNode, LogicalNodeType


class PhysicalOperatorType(Enum):
    TABLE_SCAN = "TABLE_SCAN"
    STREAM_SCAN = "STREAM_SCAN"
    HASH_FILTER = "HASH_FILTER"
    INDEX_FILTER = "INDEX_FILTER"
    HASH_AGGREGATE = "HASH_AGGREGATE"
    SORT_AGGREGATE = "SORT_AGGREGATE"
    HASH_JOIN = "HASH_JOIN"
    SORT_MERGE_JOIN = "SORT_MERGE_JOIN"
    NESTED_LOOP_JOIN = "NESTED_LOOP_JOIN"
    MERGE_SORT = "MERGE_SORT"
    TUMBLING_WINDOW = "TUMBLING_WINDOW"
    HOPPING_WINDOW = "HOPPING_WINDOW"
    SLIDING_WINDOW = "SLIDING_WINDOW"
    SESSION_WINDOW = "SESSION_WINDOW"
    HASH_PROJECT = "HASH_PROJECT"
    TABLE_SINK = "TABLE_SINK"
    STREAM_SINK = "STREAM_SINK"
    EXCHANGE = "EXCHANGE"
    LIMIT = "LIMIT"


@dataclass
class PhysicalNode:
    operator_type: PhysicalOperatorType
    node_id: str = ""
    properties: Dict[str, Any] = field(default_factory=dict)
    children: List["PhysicalNode"] = field(default_factory=list)
    estimated_rows: float = 0.0
    estimated_cost: float = 0.0
    parallelism: int = 1

    def add_child(self, child: "PhysicalNode") -> None:
        self.children.append(child)

    def walk(self) -> List["PhysicalNode"]:
        from collections import deque
        nodes = []
        queue = deque([self])
        while queue:
            node = queue.popleft()
            nodes.append(node)
            for child in node.children:
                queue.append(child)
        return nodes


@dataclass
class PhysicalPlan:
    root: Optional[PhysicalNode] = None
    source_plan: Optional[LogicalPlan] = None

    def get_operators(self) -> List[PhysicalNode]:
        if self.root is None:
            return []
        return self.root.walk()

    def get_scan_operators(self) -> List[PhysicalNode]:
        return [op for op in self.get_operators() if op.operator_type in (PhysicalOperatorType.TABLE_SCAN, PhysicalOperatorType.STREAM_SCAN)]

    def get_join_operators(self) -> List[PhysicalNode]:
        return [op for op in self.get_operators() if op.operator_type in (PhysicalOperatorType.HASH_JOIN, PhysicalOperatorType.SORT_MERGE_JOIN, PhysicalOperatorType.NESTED_LOOP_JOIN)]

    def total_cost(self) -> float:
        return sum(op.estimated_cost for op in self.get_operators())

    def to_dict(self) -> Dict[str, Any]:
        if self.root is None:
            return {}
        return self._node_to_dict(self.root)

    def _node_to_dict(self, node: PhysicalNode) -> Dict[str, Any]:
        return {
            "operator": node.operator_type.value,
            "node_id": node.node_id,
            "properties": node.properties,
            "estimated_rows": node.estimated_rows,
            "estimated_cost": node.estimated_cost,
            "parallelism": node.parallelism,
            "children": [self._node_to_dict(c) for c in node.children],
        }


class PhysicalPlanTranslator:
    _node_counter = 0

    @classmethod
    def _next_id(cls) -> str:
        cls._node_counter += 1
        return f"pn_{cls._node_counter}"

    def translate(self, logical_plan: LogicalPlan) -> PhysicalPlan:
        physical_plan = PhysicalPlan(source_plan=logical_plan)
        if logical_plan.root is None:
            return physical_plan

        physical_root = self._translate_node(logical_plan.root)
        physical_plan.root = physical_root
        return physical_plan

    def _translate_node(self, logical: LogicalNode) -> PhysicalNode:
        children = [self._translate_node(c) for c in logical.children]

        translator_map = {
            LogicalNodeType.SCAN: self._translate_scan,
            LogicalNodeType.FILTER: self._translate_filter,
            LogicalNodeType.PROJECT: self._translate_project,
            LogicalNodeType.AGGREGATE: self._translate_aggregate,
            LogicalNodeType.JOIN: self._translate_join,
            LogicalNodeType.SORT: self._translate_sort,
            LogicalNodeType.WINDOW: self._translate_window,
            LogicalNodeType.UNION: self._translate_union,
            LogicalNodeType.SINK: self._translate_sink,
            LogicalNodeType.LIMIT: self._translate_limit,
        }

        translator = translator_map.get(logical.node_type)
        if translator:
            return translator(logical, children)

        return PhysicalNode(
            operator_type=PhysicalOperatorType.TABLE_SCAN,
            node_id=self._next_id(),
            properties=logical.properties,
            estimated_rows=logical.estimated_rows,
            estimated_cost=logical.estimated_cost,
        )

    def _translate_scan(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        is_stream = logical.properties.get("is_stream", False)
        op_type = PhysicalOperatorType.STREAM_SCAN if is_stream else PhysicalOperatorType.TABLE_SCAN
        return PhysicalNode(
            operator_type=op_type,
            node_id=self._next_id(),
            properties={
                "source_name": logical.properties.get("source_name", ""),
                "alias": logical.properties.get("alias"),
                "columns": logical.properties.get("columns", []),
            },
            estimated_rows=logical.estimated_rows,
            estimated_cost=logical.estimated_cost,
            parallelism=1,
        )

    def _translate_filter(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        condition = logical.properties.get("condition", "")
        has_index = self._has_index_hint(condition)
        op_type = PhysicalOperatorType.INDEX_FILTER if has_index else PhysicalOperatorType.HASH_FILTER
        node = PhysicalNode(
            operator_type=op_type,
            node_id=self._next_id(),
            properties={"condition": condition},
            estimated_rows=logical.estimated_rows,
            estimated_cost=logical.estimated_cost,
        )
        for c in children:
            node.add_child(c)
        return node

    def _translate_project(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        node = PhysicalNode(
            operator_type=PhysicalOperatorType.HASH_PROJECT,
            node_id=self._next_id(),
            properties={"projected_columns": logical.properties.get("projected_columns", [])},
            estimated_rows=logical.estimated_rows,
            estimated_cost=logical.estimated_cost,
        )
        for c in children:
            node.add_child(c)
        return node

    def _translate_aggregate(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        group_by = logical.properties.get("group_by", [])
        op_type = PhysicalOperatorType.HASH_AGGREGATE if len(group_by) > 0 else PhysicalOperatorType.SORT_AGGREGATE
        node = PhysicalNode(
            operator_type=op_type,
            node_id=self._next_id(),
            properties={
                "group_by": group_by,
                "aggregations": logical.properties.get("aggregations", []),
                "output_columns": logical.properties.get("output_columns", []),
            },
            estimated_rows=logical.estimated_rows,
            estimated_cost=logical.estimated_cost,
            parallelism=2,
        )
        for c in children:
            node.add_child(c)
        return node

    def _translate_join(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        left_rows = children[0].estimated_rows if children else 0
        right_rows = children[1].estimated_rows if len(children) > 1 else 0
        condition = logical.properties.get("condition", "")

        if left_rows > 100000 or right_rows > 100000:
            if self._is_equi_join(condition):
                op_type = PhysicalOperatorType.HASH_JOIN
            else:
                op_type = PhysicalOperatorType.NESTED_LOOP_JOIN
        else:
            op_type = PhysicalOperatorType.HASH_JOIN if self._is_equi_join(condition) else PhysicalOperatorType.NESTED_LOOP_JOIN

        node = PhysicalNode(
            operator_type=op_type,
            node_id=self._next_id(),
            properties={
                "join_type": logical.properties.get("join_type", "INNER"),
                "condition": condition,
            },
            estimated_rows=logical.estimated_rows,
            estimated_cost=logical.estimated_cost,
            parallelism=4,
        )
        for c in children:
            node.add_child(c)
        return node

    def _translate_sort(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        node = PhysicalNode(
            operator_type=PhysicalOperatorType.MERGE_SORT,
            node_id=self._next_id(),
            properties={"order_by": logical.properties.get("order_by", [])},
            estimated_rows=logical.estimated_rows,
            estimated_cost=logical.estimated_cost,
            parallelism=2,
        )
        for c in children:
            node.add_child(c)
        return node

    def _translate_window(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        wtype = logical.properties.get("window_type", "TUMBLING")
        window_op_map = {
            "TUMBLING": PhysicalOperatorType.TUMBLING_WINDOW,
            "HOPPING": PhysicalOperatorType.HOPPING_WINDOW,
            "SLIDING": PhysicalOperatorType.SLIDING_WINDOW,
            "SESSION": PhysicalOperatorType.SESSION_WINDOW,
        }
        op_type = window_op_map.get(wtype, PhysicalOperatorType.TUMBLING_WINDOW)
        node = PhysicalNode(
            operator_type=op_type,
            node_id=self._next_id(),
            properties={
                "window_type": wtype,
                "size": logical.properties.get("size"),
                "slide": logical.properties.get("slide"),
                "gap": logical.properties.get("gap"),
                "time_field": logical.properties.get("time_field"),
            },
            estimated_rows=logical.estimated_rows,
            estimated_cost=logical.estimated_cost,
            parallelism=2,
        )
        for c in children:
            node.add_child(c)
        return node

    def _translate_union(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        node = PhysicalNode(
            operator_type=PhysicalOperatorType.EXCHANGE,
            node_id=self._next_id(),
            properties={"mode": "UNION"},
            estimated_rows=sum(c.estimated_rows for c in children),
            estimated_cost=sum(c.estimated_cost for c in children) + 10,
        )
        for c in children:
            node.add_child(c)
        return node

    def _translate_sink(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        target_table = logical.properties.get("target_table")
        target_stream = logical.properties.get("target_stream")
        if target_stream:
            op_type = PhysicalOperatorType.STREAM_SINK
            props = {"target_stream": target_stream}
        else:
            op_type = PhysicalOperatorType.TABLE_SINK
            props = {"target_table": target_table or ""}
        node = PhysicalNode(
            operator_type=op_type,
            node_id=self._next_id(),
            properties=props,
            estimated_rows=children[0].estimated_rows if children else 0,
            estimated_cost=1.0,
        )
        for c in children:
            node.add_child(c)
        return node

    def _translate_limit(self, logical: LogicalNode, children: List[PhysicalNode]) -> PhysicalNode:
        node = PhysicalNode(
            operator_type=PhysicalOperatorType.LIMIT,
            node_id=self._next_id(),
            properties={"limit": logical.properties.get("limit", 0)},
            estimated_rows=logical.estimated_rows,
            estimated_cost=1.0,
        )
        for c in children:
            node.add_child(c)
        return node

    def _is_equi_join(self, condition: str) -> bool:
        return "=" in condition and "!=" not in condition

    def _has_index_hint(self, condition: str) -> bool:
        return "INDEX" in condition.upper() or "PRIMARY" in condition.upper()
