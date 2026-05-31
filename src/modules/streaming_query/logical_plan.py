"""Logical plan and optimizer for streaming queries."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager
from .sql_parser import (
    ASTNode,
    NodeType,
    AggregateType,
    AggregateSpec,
    WindowSpec,
    SelectField,
    WhereCondition,
    JoinCondition,
)


class LogicalOperatorType(Enum):
    READ = "read"
    FILTER = "filter"
    PROJECT = "project"
    AGGREGATE = "aggregate"
    WINDOW_AGGREGATE = "window_aggregate"
    JOIN = "join"
    SORT = "sort"
    LIMIT = "limit"
    UNION = "union"
    DISTINCT = "distinct"


@dataclass
class LogicalExpression:
    expr_type: str
    operands: List["LogicalExpression"] = field(default_factory=list)
    value: Any = None
    field: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "expr_type": self.expr_type,
            "field": self.field,
            "value": self.value,
            "operands": [op.to_dict() for op in self.operands],
        }


@dataclass
class LogicalPlan:
    operator: LogicalOperatorType
    children: List["LogicalPlan"] = field(default_factory=list)
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
            elif isinstance(value, LogicalExpression):
                serialized[key] = value.to_dict()
            else:
                serialized[key] = value
        return serialized

    def get_all_operators(self) -> List["LogicalPlan"]:
        operators = [self]
        for child in self.children:
            operators.extend(child.get_all_operators())
        return operators

    def get_operator_count(self) -> int:
        return len(self.get_all_operators())

    def get_required_fields(self) -> Set[str]:
        fields: Set[str] = set()
        if self.operator == LogicalOperatorType.PROJECT:
            for proj in self.properties.get("projections", []):
                if isinstance(proj, SelectField):
                    fields.add(proj.field)
                elif isinstance(proj, str):
                    fields.add(proj)
        elif self.operator == LogicalOperatorType.FILTER:
            for cond in self.properties.get("conditions", []):
                if isinstance(cond, WhereCondition):
                    fields.add(cond.field)
        elif self.operator in (LogicalOperatorType.AGGREGATE, LogicalOperatorType.WINDOW_AGGREGATE):
            for agg in self.properties.get("aggregates", []):
                if isinstance(agg, AggregateSpec) and agg.field:
                    fields.add(agg.field)
            for gb in self.properties.get("group_by", []):
                fields.add(gb)
        elif self.operator == LogicalOperatorType.JOIN:
            for cond in self.properties.get("conditions", []):
                if isinstance(cond, JoinCondition):
                    fields.add(cond.left_field)
                    fields.add(cond.right_field)
        elif self.operator == LogicalOperatorType.SORT:
            for sort_field in self.properties.get("sort_fields", []):
                if isinstance(sort_field, dict):
                    fields.add(sort_field.get("field", ""))
                elif isinstance(sort_field, str):
                    fields.add(sort_field)

        for child in self.children:
            fields.update(child.get_required_fields())

        return fields


class LogicalPlanBuilder:
    def __init__(self) -> None:
        self._logger = LogManager().get_logger(__name__)

    def build(self, ast: ASTNode) -> LogicalPlan:
        self._logger.info("Building logical plan from AST")

        try:
            plan = self._build_plan(ast)
            self._logger.info(
                "Logical plan built successfully",
                operator_count=plan.get_operator_count(),
            )
            return plan
        except Exception as e:
            self._logger.error(
                "Failed to build logical plan",
                error=str(e),
            )
            raise ValidationError(
                message=f"Failed to build logical plan: {str(e)}",
                suggestion="Check the AST structure and ensure it's valid.",
            )

    def _build_plan(self, ast: ASTNode) -> LogicalPlan:
        if ast.node_type == NodeType.SELECT:
            return self._build_select_plan(ast)

        raise ValidationError(
            message=f"Unsupported AST node type: {ast.node_type}",
            suggestion="Ensure the SQL query is supported by the streaming query engine.",
        )

    def _build_select_plan(self, ast: ASTNode) -> LogicalPlan:
        read_plan = None
        for child in ast.children:
            if child.node_type == NodeType.FROM:
                read_plan = self._build_read_plan(child)
                break

        if read_plan is None:
            raise ValidationError(
                message="No FROM clause found in SELECT statement",
                suggestion="Add a FROM clause to specify the data source.",
            )

        current_plan = read_plan

        for child in ast.children:
            if child.node_type == NodeType.WHERE:
                current_plan = self._build_filter_plan(current_plan, child)
            elif child.node_type == NodeType.JOIN:
                current_plan = self._build_join_plan(current_plan, child)

        select_node = None
        for child in ast.children:
            if child.node_type == NodeType.SELECT:
                select_node = child
                break

        has_window_aggregates = False
        has_regular_aggregates = False
        aggregates = select_node.properties.get("aggregates", []) if select_node else []

        for agg in aggregates:
            if isinstance(agg, AggregateSpec) and agg.window:
                has_window_aggregates = True
            elif isinstance(agg, AggregateSpec):
                has_regular_aggregates = True

        if has_window_aggregates:
            current_plan = self._build_window_aggregate_plan(current_plan, select_node)
        elif has_regular_aggregates:
            for child in ast.children:
                if child.node_type == NodeType.GROUP_BY:
                    current_plan = self._build_aggregate_plan(current_plan, select_node, child)
                    break
            else:
                current_plan = self._build_aggregate_plan(current_plan, select_node, None)

        for child in ast.children:
            if child.node_type == NodeType.HAVING:
                current_plan = self._build_having_filter_plan(current_plan, child)

        if select_node:
            current_plan = self._build_project_plan(current_plan, select_node)

        for child in ast.children:
            if child.node_type == NodeType.ORDER_BY:
                current_plan = self._build_sort_plan(current_plan, child)
            elif child.node_type == NodeType.LIMIT:
                current_plan = self._build_limit_plan(current_plan, child)

        if select_node and select_node.properties.get("distinct", False):
            current_plan = LogicalPlan(
                operator=LogicalOperatorType.DISTINCT,
                children=[current_plan],
            )

        return current_plan

    def _build_read_plan(self, from_node: ASTNode) -> LogicalPlan:
        table_name = from_node.properties.get("table")
        alias = from_node.properties.get("alias")

        read_plan = LogicalPlan(
            operator=LogicalOperatorType.READ,
            properties={
                "table": table_name,
                "alias": alias,
            },
        )

        for join_child in from_node.children:
            if join_child.node_type == NodeType.JOIN:
                right_read = LogicalPlan(
                    operator=LogicalOperatorType.READ,
                    properties={
                        "table": join_child.properties.get("table"),
                        "alias": join_child.properties.get("alias"),
                    },
                )

                read_plan = LogicalPlan(
                    operator=LogicalOperatorType.JOIN,
                    children=[read_plan, right_read],
                    properties={
                        "join_type": join_child.properties.get("join_type"),
                        "conditions": join_child.properties.get("conditions", []),
                    },
                )

        return read_plan

    def _build_filter_plan(self, input_plan: LogicalPlan, where_node: ASTNode) -> LogicalPlan:
        conditions = where_node.properties.get("conditions", [])

        return LogicalPlan(
            operator=LogicalOperatorType.FILTER,
            children=[input_plan],
            properties={
                "conditions": conditions,
            },
        )

    def _build_having_filter_plan(self, input_plan: LogicalPlan, having_node: ASTNode) -> LogicalPlan:
        conditions = having_node.properties.get("conditions", [])

        return LogicalPlan(
            operator=LogicalOperatorType.FILTER,
            children=[input_plan],
            properties={
                "conditions": conditions,
                "is_having": True,
            },
        )

    def _build_join_plan(self, left_plan: LogicalPlan, join_node: ASTNode) -> LogicalPlan:
        right_table = join_node.properties.get("table")
        right_alias = join_node.properties.get("alias")

        right_plan = LogicalPlan(
            operator=LogicalOperatorType.READ,
            properties={
                "table": right_table,
                "alias": right_alias,
            },
        )

        return LogicalPlan(
            operator=LogicalOperatorType.JOIN,
            children=[left_plan, right_plan],
            properties={
                "join_type": join_node.properties.get("join_type"),
                "conditions": join_node.properties.get("conditions", []),
            },
        )

    def _build_project_plan(self, input_plan: LogicalPlan, select_node: ASTNode) -> LogicalPlan:
        fields = select_node.properties.get("fields", [])
        aggregates = select_node.properties.get("aggregates", [])

        projections: List[Any] = []
        for f in fields:
            if isinstance(f, SelectField):
                if f.alias:
                    projections.append(f)
                else:
                    projections.append(f.field)
            else:
                projections.append(f)

        for agg in aggregates:
            if isinstance(agg, AggregateSpec) and agg.alias:
                projections.append(SelectField(field=agg.alias, alias=agg.alias))

        return LogicalPlan(
            operator=LogicalOperatorType.PROJECT,
            children=[input_plan],
            properties={
                "projections": projections,
            },
        )

    def _build_aggregate_plan(
        self,
        input_plan: LogicalPlan,
        select_node: ASTNode,
        group_by_node: Optional[ASTNode],
    ) -> LogicalPlan:
        aggregates = select_node.properties.get("aggregates", [])
        group_by_fields = group_by_node.properties.get("fields", []) if group_by_node else []

        return LogicalPlan(
            operator=LogicalOperatorType.AGGREGATE,
            children=[input_plan],
            properties={
                "aggregates": aggregates,
                "group_by": group_by_fields,
            },
        )

    def _build_window_aggregate_plan(self, input_plan: LogicalPlan, select_node: ASTNode) -> LogicalPlan:
        aggregates = select_node.properties.get("aggregates", [])
        window_aggs = [
            agg for agg in aggregates
            if isinstance(agg, AggregateSpec) and agg.window
        ]

        return LogicalPlan(
            operator=LogicalOperatorType.WINDOW_AGGREGATE,
            children=[input_plan],
            properties={
                "aggregates": window_aggs,
            },
        )

    def _build_sort_plan(self, input_plan: LogicalPlan, order_by_node: ASTNode) -> LogicalPlan:
        fields = order_by_node.properties.get("fields", [])

        return LogicalPlan(
            operator=LogicalOperatorType.SORT,
            children=[input_plan],
            properties={
                "sort_fields": fields,
            },
        )

    def _build_limit_plan(self, input_plan: LogicalPlan, limit_node: ASTNode) -> LogicalPlan:
        limit = limit_node.properties.get("limit")
        offset = limit_node.properties.get("offset")

        return LogicalPlan(
            operator=LogicalOperatorType.LIMIT,
            children=[input_plan],
            properties={
                "limit": int(limit) if limit else None,
                "offset": int(offset) if offset else None,
            },
        )


class LogicalPlanOptimizer:
    def __init__(self) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._optimizations_applied: List[str] = []

    def optimize(self, plan: LogicalPlan) -> LogicalPlan:
        self._logger.info("Starting logical plan optimization")
        self._optimizations_applied.clear()

        optimized_plan = plan

        optimized_plan = self._push_down_filters(optimized_plan)
        optimized_plan = self._push_down_projections(optimized_plan)
        optimized_plan = self._combine_filters(optimized_plan)
        optimized_plan = self._eliminate_redundant_projections(optimized_plan)
        optimized_plan = self._reorder_joins(optimized_plan)
        optimized_plan = self._push_down_limit(optimized_plan)

        self._logger.info(
            "Logical plan optimization completed",
            optimizations=self._optimizations_applied,
        )

        return optimized_plan

    def _push_down_filters(self, plan: LogicalPlan) -> LogicalPlan:
        if plan.operator != LogicalOperatorType.FILTER:
            plan.children = [self._push_down_filters(child) for child in plan.children]
            return plan

        filter_conditions = plan.properties.get("conditions", [])
        child = plan.children[0]

        if child.operator == LogicalOperatorType.JOIN:
            left_conds, right_conds, common_conds = self._split_join_conditions(
                filter_conditions, child
            )

            left_child = child.children[0]
            right_child = child.children[1]

            if left_conds:
                left_filter = LogicalPlan(
                    operator=LogicalOperatorType.FILTER,
                    children=[left_child],
                    properties={"conditions": left_conds},
                )
                child.children[0] = left_filter
                self._optimizations_applied.append("filter_push_down_to_left_join")

            if right_conds:
                right_filter = LogicalPlan(
                    operator=LogicalOperatorType.FILTER,
                    children=[right_child],
                    properties={"conditions": right_conds},
                )
                child.children[1] = right_filter
                self._optimizations_applied.append("filter_push_down_to_right_join")

            if common_conds:
                plan.properties["conditions"] = common_conds
                return plan
            else:
                return child

        if child.operator == LogicalOperatorType.PROJECT:
            plan.children[0] = child.children[0]
            child.children[0] = plan
            self._optimizations_applied.append("filter_push_down_through_project")
            return child

        if child.operator in (LogicalOperatorType.READ, LogicalOperatorType.FILTER):
            plan.children[0] = self._push_down_filters(child)
            return plan

        return plan

    def _split_join_conditions(
        self,
        conditions: List[WhereCondition],
        join_plan: LogicalPlan,
    ) -> Tuple[List[WhereCondition], List[WhereCondition], List[WhereCondition]]:
        left_conds: List[WhereCondition] = []
        right_conds: List[WhereCondition] = []
        common_conds: List[WhereCondition] = []

        left_table = join_plan.children[0].properties.get("table")
        right_table = join_plan.children[1].properties.get("table")

        left_alias = join_plan.children[0].properties.get("alias") or left_table
        right_alias = join_plan.children[1].properties.get("alias") or right_table

        for cond in conditions:
            field = cond.field
            if "." in field:
                table_ref = field.split(".")[0]
                if table_ref == left_alias:
                    left_conds.append(cond)
                elif table_ref == right_alias:
                    right_conds.append(cond)
                else:
                    common_conds.append(cond)
            else:
                common_conds.append(cond)

        return left_conds, right_conds, common_conds

    def _push_down_projections(self, plan: LogicalPlan) -> LogicalPlan:
        plan.children = [self._push_down_projections(child) for child in plan.children]

        if plan.operator == LogicalOperatorType.PROJECT:
            required_fields = plan.get_required_fields()
            child = plan.children[0]

            if child.operator == LogicalOperatorType.READ:
                child.properties["required_fields"] = list(required_fields)
                self._optimizations_applied.append("projection_push_down_to_read")

        return plan

    def _combine_filters(self, plan: LogicalPlan) -> LogicalPlan:
        plan.children = [self._combine_filters(child) for child in plan.children]

        if (
            plan.operator == LogicalOperatorType.FILTER
            and plan.children[0].operator == LogicalOperatorType.FILTER
        ):
            parent_conds = plan.properties.get("conditions", [])
            child_conds = plan.children[0].properties.get("conditions", [])

            combined_conds = child_conds + parent_conds

            plan.properties["conditions"] = combined_conds
            plan.children = plan.children[0].children

            self._optimizations_applied.append("filter_combination")

        return plan

    def _eliminate_redundant_projections(self, plan: LogicalPlan) -> LogicalPlan:
        plan.children = [self._eliminate_redundant_projections(child) for child in plan.children]

        if plan.operator == LogicalOperatorType.PROJECT:
            child = plan.children[0]

            if child.operator == LogicalOperatorType.PROJECT:
                parent_projs = plan.properties.get("projections", [])
                child_projs = child.properties.get("projections", [])

                parent_fields = {self._get_field_name(p) for p in parent_projs}
                child_fields = {self._get_field_name(p) for p in child_projs}

                if parent_fields == child_fields:
                    plan.children = child.children
                    self._optimizations_applied.append("redundant_projection_elimination")

        return plan

    def _get_field_name(self, proj: Any) -> str:
        if isinstance(proj, SelectField):
            return proj.alias or proj.field
        return str(proj)

    def _reorder_joins(self, plan: LogicalPlan) -> LogicalPlan:
        plan.children = [self._reorder_joins(child) for child in plan.children]

        if plan.operator == LogicalOperatorType.JOIN:
            left = plan.children[0]
            right = plan.children[1]

            left_size = self._estimate_size(left)
            right_size = self._estimate_size(right)

            if left_size > right_size:
                plan.children = [right, left]
                self._optimizations_applied.append("join_reordering")

        return plan

    def _estimate_size(self, plan: LogicalPlan) -> int:
        if plan.operator == LogicalOperatorType.READ:
            return 10000

        if plan.operator == LogicalOperatorType.FILTER:
            return int(self._estimate_size(plan.children[0]) * 0.3)

        if plan.operator == LogicalOperatorType.AGGREGATE:
            return int(self._estimate_size(plan.children[0]) * 0.1)

        if plan.operator == LogicalOperatorType.JOIN:
            left_size = self._estimate_size(plan.children[0])
            right_size = self._estimate_size(plan.children[1])
            return int((left_size + right_size) * 0.5)

        return self._estimate_size(plan.children[0]) if plan.children else 1000

    def _push_down_limit(self, plan: LogicalPlan) -> LogicalPlan:
        plan.children = [self._push_down_limit(child) for child in plan.children]

        if plan.operator == LogicalOperatorType.LIMIT:
            limit = plan.properties.get("limit", 0)
            child = plan.children[0]

            if child.operator == LogicalOperatorType.SORT:
                sort_child = child.children[0]
                if sort_child.operator == LogicalOperatorType.READ:
                    sort_child.properties["push_down_limit"] = limit
                    self._optimizations_applied.append("limit_push_down_to_read")

        return plan

    def get_optimization_summary(self) -> Dict[str, Any]:
        return {
            "optimizations_applied": self._optimizations_applied,
            "total_optimizations": len(self._optimizations_applied),
        }

    def validate_plan(self, plan: LogicalPlan) -> Dict[str, Any]:
        errors: List[str] = []
        warnings: List[str] = []

        operators = plan.get_all_operators()

        for op in operators:
            if op.operator == LogicalOperatorType.JOIN:
                if len(op.children) != 2:
                    errors.append(f"Join operator must have exactly 2 children, got {len(op.children)}")
                if not op.properties.get("conditions"):
                    warnings.append("Join without conditions may result in Cartesian product")

            if op.operator == LogicalOperatorType.AGGREGATE:
                aggs = op.properties.get("aggregates", [])
                if not aggs:
                    errors.append("Aggregate operator must have at least one aggregate function")

            if op.operator == LogicalOperatorType.WINDOW_AGGREGATE:
                aggs = op.properties.get("aggregates", [])
                for agg in aggs:
                    if isinstance(agg, AggregateSpec) and not agg.window:
                        errors.append("Window aggregate must have window specification")

            if op.operator == LogicalOperatorType.READ:
                if not op.properties.get("table"):
                    errors.append("Read operator must specify table name")

        return {
            "valid": len(errors) == 0,
            "errors": errors,
            "warnings": warnings,
            "operator_count": len(operators),
        }
