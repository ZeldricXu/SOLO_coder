"""Physical plan and translator for streaming queries."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager
from .logical_plan import LogicalPlan, LogicalOperatorType
from .sql_parser import AggregateSpec, WindowSpec, JoinCondition, WhereCondition, SelectField


class PhysicalOperatorType(Enum):
    SOURCE = "source"
    MAP = "map"
    FILTER = "filter"
    REDUCE = "reduce"
    WINDOW = "window"
    JOIN = "join"
    SORT = "sort"
    LIMIT = "limit"
    DISTINCT = "distinct"
    SINK = "sink"
    BROADCAST = "broadcast"
    PARTITION = "partition"
    CO_GROUP = "co_group"


class ExecutionMode(Enum):
    STREAMING = "streaming"
    BATCH = "batch"
    MICRO_BATCH = "micro_batch"


class PartitionStrategy(Enum):
    HASH = "hash"
    ROUND_ROBIN = "round_robin"
    RANGE = "range"
    KEY = "key"
    BROADCAST = "broadcast"


@dataclass
class PhysicalPlan:
    operator: PhysicalOperatorType
    children: List["PhysicalPlan"] = field(default_factory=list)
    properties: Dict[str, Any] = field(default_factory=dict)
    id: UUID = field(default_factory=uuid4)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": str(self.id),
            "operator": self.operator.value,
            "properties": self._serialize_properties(self.properties),
            "children": [child.to_dict() for child in self.children],
        }

    def _serialize_properties(self, props: Dict[str, Any]) -> Dict[str, Any]:
        serialized = {}
        for key, value in props.items():
            if isinstance(value, Enum):
                serialized[key] = value.value
            elif isinstance(value, list):
                serialized[key] = [
                    v.value if isinstance(v, Enum) else v for v in value
                ]
            elif isinstance(value, dict):
                serialized[key] = self._serialize_properties(value)
            elif hasattr(value, "__dict__"):
                serialized[key] = self._serialize_properties(value.__dict__)
            else:
                serialized[key] = value
        return serialized

    def get_all_operators(self) -> List["PhysicalPlan"]:
        operators = [self]
        for child in self.children:
            operators.extend(child.get_all_operators())
        return operators

    def get_operator_count(self) -> int:
        return len(self.get_all_operators())

    def estimate_cost(self) -> Dict[str, float]:
        cpu_cost = 0.0
        memory_cost = 0.0
        io_cost = 0.0
        network_cost = 0.0

        if self.operator == PhysicalOperatorType.SOURCE:
            io_cost += 100.0
        elif self.operator == PhysicalOperatorType.FILTER:
            cpu_cost += 10.0
        elif self.operator == PhysicalOperatorType.MAP:
            cpu_cost += 5.0
        elif self.operator == PhysicalOperatorType.REDUCE:
            cpu_cost += 50.0
            memory_cost += 100.0
        elif self.operator == PhysicalOperatorType.WINDOW:
            cpu_cost += 100.0
            memory_cost += 200.0
        elif self.operator == PhysicalOperatorType.JOIN:
            cpu_cost += 200.0
            memory_cost += 300.0
            network_cost += 50.0
        elif self.operator == PhysicalOperatorType.SORT:
            cpu_cost += 150.0
            memory_cost += 250.0
        elif self.operator == PhysicalOperatorType.SINK:
            io_cost += 100.0

        for child in self.children:
            child_cost = child.estimate_cost()
            cpu_cost += child_cost["cpu_cost"]
            memory_cost += child_cost["memory_cost"]
            io_cost += child_cost["io_cost"]
            network_cost += child_cost["network_cost"]

        return {
            "cpu_cost": cpu_cost,
            "memory_cost": memory_cost,
            "io_cost": io_cost,
            "network_cost": network_cost,
            "total_cost": cpu_cost + memory_cost + io_cost + network_cost,
        }


@dataclass
class ExecutionConfig:
    mode: ExecutionMode = ExecutionMode.STREAMING
    parallelism: int = 1
    checkpoint_interval: Optional[str] = None
    checkpoint_dir: Optional[str] = None
    idle_timeout: Optional[str] = None
    watermark_interval: Optional[str] = None
    allowed_lateness: Optional[str] = None
    partition_strategy: PartitionStrategy = PartitionStrategy.HASH
    buffer_size: int = 1000
    batch_size: int = 100
    retry_attempts: int = 3
    retry_delay: str = "1s"


class PhysicalPlanTranslator:
    def __init__(self, config: Optional[ExecutionConfig] = None) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._config = config or ExecutionConfig()
        self._translations: List[str] = []

    def translate(
        self,
        logical_plan: LogicalPlan,
        config: Optional[ExecutionConfig] = None,
    ) -> PhysicalPlan:
        self._logger.info("Translating logical plan to physical plan")
        self._translations.clear()

        if config:
            self._config = config

        try:
            physical_plan = self._translate_plan(logical_plan)

            sink_plan = PhysicalPlan(
                operator=PhysicalOperatorType.SINK,
                children=[physical_plan],
                properties={"sink_type": "collect"},
            )

            self._logger.info(
                "Physical plan translation completed",
                operator_count=sink_plan.get_operator_count(),
            )

            return sink_plan
        except Exception as e:
            self._logger.error(
                "Failed to translate logical plan",
                error=str(e),
            )
            raise ValidationError(
                message=f"Failed to translate logical plan: {str(e)}",
                suggestion="Check the logical plan structure and ensure it's valid.",
            )

    def _translate_plan(self, logical_plan: LogicalPlan) -> PhysicalPlan:
        children = [self._translate_plan(child) for child in logical_plan.children]

        operator_map = {
            LogicalOperatorType.READ: self._translate_read,
            LogicalOperatorType.FILTER: self._translate_filter,
            LogicalOperatorType.PROJECT: self._translate_project,
            LogicalOperatorType.AGGREGATE: self._translate_aggregate,
            LogicalOperatorType.WINDOW_AGGREGATE: self._translate_window_aggregate,
            LogicalOperatorType.JOIN: self._translate_join,
            LogicalOperatorType.SORT: self._translate_sort,
            LogicalOperatorType.LIMIT: self._translate_limit,
            LogicalOperatorType.DISTINCT: self._translate_distinct,
        }

        translator = operator_map.get(logical_plan.operator)
        if not translator:
            raise ValidationError(
                message=f"Unsupported logical operator: {logical_plan.operator}",
                suggestion="Ensure the query uses supported operations.",
            )

        return translator(logical_plan, children)

    def _translate_read(
        self,
        logical_plan: LogicalPlan,
        children: List[PhysicalPlan],
    ) -> PhysicalPlan:
        table = logical_plan.properties.get("table")
        alias = logical_plan.properties.get("alias")
        required_fields = logical_plan.properties.get("required_fields")
        push_down_limit = logical_plan.properties.get("push_down_limit")

        properties = {
            "table": table,
            "alias": alias,
            "mode": self._config.mode,
            "parallelism": self._config.parallelism,
        }

        if required_fields:
            properties["required_fields"] = required_fields
        if push_down_limit:
            properties["push_down_limit"] = push_down_limit

        self._translations.append(f"read_{table}")

        return PhysicalPlan(
            operator=PhysicalOperatorType.SOURCE,
            children=children,
            properties=properties,
        )

    def _translate_filter(
        self,
        logical_plan: LogicalPlan,
        children: List[PhysicalPlan],
    ) -> PhysicalPlan:
        conditions = logical_plan.properties.get("conditions", [])
        is_having = logical_plan.properties.get("is_having", False)

        filter_expressions = []
        for cond in conditions:
            if isinstance(cond, WhereCondition):
                filter_expressions.append({
                    "field": cond.field,
                    "operator": cond.operator,
                    "value": cond.value,
                    "logical_op": cond.logical_op,
                })
            else:
                filter_expressions.append(cond)

        self._translations.append(f"filter_{len(filter_expressions)}_conds")

        return PhysicalPlan(
            operator=PhysicalOperatorType.FILTER,
            children=children,
            properties={
                "conditions": filter_expressions,
                "is_having": is_having,
            },
        )

    def _translate_project(
        self,
        logical_plan: LogicalPlan,
        children: List[PhysicalPlan],
    ) -> PhysicalPlan:
        projections = logical_plan.properties.get("projections", [])

        proj_list = []
        for proj in projections:
            if isinstance(proj, SelectField):
                proj_list.append({
                    "field": proj.field,
                    "alias": proj.alias,
                    "expression": proj.expression,
                })
            else:
                proj_list.append({"field": str(proj)})

        self._translations.append(f"project_{len(proj_list)}_fields")

        return PhysicalPlan(
            operator=PhysicalOperatorType.MAP,
            children=children,
            properties={
                "projections": proj_list,
                "operation": "project",
            },
        )

    def _translate_aggregate(
        self,
        logical_plan: LogicalPlan,
        children: List[PhysicalPlan],
    ) -> PhysicalPlan:
        aggregates = logical_plan.properties.get("aggregates", [])
        group_by = logical_plan.properties.get("group_by", [])

        agg_list = []
        for agg in aggregates:
            if isinstance(agg, AggregateSpec):
                agg_list.append({
                    "function": agg.function.value,
                    "field": agg.field,
                    "alias": agg.alias,
                    "distinct": agg.distinct,
                })
            else:
                agg_list.append(agg)

        self._translations.append(
            f"aggregate_{len(agg_list)}_funcs_{len(group_by)}_groupby"
        )

        return PhysicalPlan(
            operator=PhysicalOperatorType.REDUCE,
            children=children,
            properties={
                "aggregates": agg_list,
                "group_by": group_by,
                "partition_strategy": self._config.partition_strategy,
                "partition_keys": group_by if group_by else None,
            },
        )

    def _translate_window_aggregate(
        self,
        logical_plan: LogicalPlan,
        children: List[PhysicalPlan],
    ) -> PhysicalPlan:
        aggregates = logical_plan.properties.get("aggregates", [])

        window_list = []
        agg_list = []
        for agg in aggregates:
            if isinstance(agg, AggregateSpec):
                agg_list.append({
                    "function": agg.function.value,
                    "field": agg.field,
                    "alias": agg.alias,
                })

                if agg.window:
                    window = agg.window
                    window_list.append({
                        "window_type": window.window_type.value,
                        "duration": window.duration,
                        "slide": window.slide,
                        "session_gap": window.session_gap,
                        "time_field": window.time_field,
                    })

        self._translations.append(
            f"window_aggregate_{len(agg_list)}_funcs_{len(window_list)}_windows"
        )

        properties = {
            "aggregates": agg_list,
            "windows": window_list,
            "watermark_interval": self._config.watermark_interval,
            "allowed_lateness": self._config.allowed_lateness,
            "mode": self._config.mode,
        }

        if self._config.checkpoint_interval:
            properties["checkpoint_interval"] = self._config.checkpoint_interval
            properties["checkpoint_dir"] = self._config.checkpoint_dir

        return PhysicalPlan(
            operator=PhysicalOperatorType.WINDOW,
            children=children,
            properties=properties,
        )

    def _translate_join(
        self,
        logical_plan: LogicalPlan,
        children: List[PhysicalPlan],
    ) -> PhysicalPlan:
        join_type = logical_plan.properties.get("join_type")
        conditions = logical_plan.properties.get("conditions", [])

        cond_list = []
        for cond in conditions:
            if isinstance(cond, JoinCondition):
                cond_list.append({
                    "left_field": cond.left_field,
                    "right_field": cond.right_field,
                    "operator": cond.operator,
                })
            else:
                cond_list.append(cond)

        left_size = self._estimate_logical_size(logical_plan.children[0])
        right_size = self._estimate_logical_size(logical_plan.children[1])

        join_strategy = "shuffle_hash"
        if min(left_size, right_size) < 1000:
            join_strategy = "broadcast_hash"
            broadcast_side = "left" if left_size < right_size else "right"

            broadcast_plan = PhysicalPlan(
                operator=PhysicalOperatorType.BROADCAST,
                children=[children[0] if broadcast_side == "left" else children[1]],
                properties={"broadcast_side": broadcast_side},
            )

            if broadcast_side == "left":
                children = [broadcast_plan, children[1]]
            else:
                children = [children[0], broadcast_plan]

        self._translations.append(f"join_{join_type.value}_{join_strategy}")

        return PhysicalPlan(
            operator=PhysicalOperatorType.JOIN,
            children=children,
            properties={
                "join_type": join_type.value if hasattr(join_type, "value") else join_type,
                "conditions": cond_list,
                "join_strategy": join_strategy,
            },
        )

    def _estimate_logical_size(self, plan: LogicalPlan) -> int:
        if plan.operator == LogicalOperatorType.READ:
            return 10000
        if plan.operator == LogicalOperatorType.FILTER:
            return int(self._estimate_logical_size(plan.children[0]) * 0.3)
        if plan.operator == LogicalOperatorType.AGGREGATE:
            return int(self._estimate_logical_size(plan.children[0]) * 0.1)
        return self._estimate_logical_size(plan.children[0]) if plan.children else 1000

    def _translate_sort(
        self,
        logical_plan: LogicalPlan,
        children: List[PhysicalPlan],
    ) -> PhysicalPlan:
        sort_fields = logical_plan.properties.get("sort_fields", [])

        fields_list = []
        for sf in sort_fields:
            if isinstance(sf, dict):
                fields_list.append(sf)
            else:
                fields_list.append({"field": str(sf), "direction": "ASC"})

        self._translations.append(f"sort_{len(fields_list)}_fields")

        return PhysicalPlan(
            operator=PhysicalOperatorType.SORT,
            children=children,
            properties={
                "sort_fields": fields_list,
                "buffer_size": self._config.buffer_size,
                "spill_enabled": True,
            },
        )

    def _translate_limit(
        self,
        logical_plan: LogicalPlan,
        children: List[PhysicalPlan],
    ) -> PhysicalPlan:
        limit = logical_plan.properties.get("limit")
        offset = logical_plan.properties.get("offset")

        self._translations.append(f"limit_{limit}_offset_{offset}")

        return PhysicalPlan(
            operator=PhysicalOperatorType.LIMIT,
            children=children,
            properties={
                "limit": limit,
                "offset": offset,
            },
        )

    def _translate_distinct(
        self,
        logical_plan: LogicalPlan,
        children: List[PhysicalPlan],
    ) -> PhysicalPlan:
        self._translations.append("distinct")

        return PhysicalPlan(
            operator=PhysicalOperatorType.DISTINCT,
            children=children,
            properties={
                "partition_strategy": self._config.partition_strategy,
            },
        )

    def get_translation_summary(self) -> Dict[str, Any]:
        return {
            "translations": self._translations,
            "execution_mode": self._config.mode.value,
            "parallelism": self._config.parallelism,
            "total_translations": len(self._translations),
        }

    def validate_physical_plan(self, plan: PhysicalPlan) -> Dict[str, Any]:
        errors: List[str] = []
        warnings: List[str] = []

        operators = plan.get_all_operators()

        has_source = any(op.operator == PhysicalOperatorType.SOURCE for op in operators)
        if not has_source:
            errors.append("Physical plan must have at least one source operator")

        has_sink = any(op.operator == PhysicalOperatorType.SINK for op in operators)
        if not has_sink:
            errors.append("Physical plan must have at least one sink operator")

        for op in operators:
            if op.operator == PhysicalOperatorType.JOIN:
                if len(op.children) != 2:
                    errors.append(f"Join operator must have exactly 2 children, got {len(op.children)}")
                join_strategy = op.properties.get("join_strategy")
                if join_strategy == "broadcast_hash":
                    has_broadcast = any(
                        child.operator == PhysicalOperatorType.BROADCAST
                        for child in op.children
                    )
                    if not has_broadcast:
                        warnings.append("Broadcast join strategy used without broadcast operator")

            if op.operator == PhysicalOperatorType.WINDOW:
                windows = op.properties.get("windows", [])
                if not windows:
                    errors.append("Window operator must have at least one window specification")

            if op.operator == PhysicalOperatorType.SOURCE:
                if not op.properties.get("table"):
                    errors.append("Source operator must specify table name")

        cost_estimate = plan.estimate_cost()

        return {
            "valid": len(errors) == 0,
            "errors": errors,
            "warnings": warnings,
            "operator_count": len(operators),
            "cost_estimate": cost_estimate,
        }

    def generate_execution_graph(self, plan: PhysicalPlan) -> Dict[str, Any]:
        nodes = []
        edges = []

        def traverse(node: PhysicalPlan, parent_id: Optional[str] = None) -> None:
            node_id = str(node.id)
            nodes.append({
                "id": node_id,
                "operator": node.operator.value,
                "properties": {
                    k: v for k, v in node.properties.items()
                    if not isinstance(v, (list, dict))
                },
            })

            if parent_id:
                edges.append({
                    "source": parent_id,
                    "target": node_id,
                })

            for child in node.children:
                traverse(child, node_id)

        traverse(plan)

        return {
            "nodes": nodes,
            "edges": edges,
            "node_count": len(nodes),
            "edge_count": len(edges),
        }
