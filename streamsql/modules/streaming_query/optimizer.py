from __future__ import annotations

from typing import Any

from streamsql.modules.streaming_query.logical_plan import LogicalNode, LogicalPlan, NodeType


class LogicalPlanOptimizer:
    def __init__(self, enable_all: bool = True):
        self.enable_predicate_pushdown = enable_all
        self.enable_projection_pushdown = enable_all
        self.enable_constant_folding = enable_all
        self.enable_reorder_joins = enable_all
        self.enable_merge_filters = enable_all
        self.enable_remove_redundant = enable_all

    def optimize(self, plan: LogicalPlan) -> LogicalPlan:
        if not plan.root:
            return plan

        optimized_root = self._optimize_node(plan.root)
        plan.root = optimized_root
        plan.optimized = True
        plan.metadata["optimizations_applied"] = self._get_applied_optimizations()

        return plan

    def _get_applied_optimizations(self) -> list[str]:
        optimizations: list[str] = []
        if self.enable_predicate_pushdown:
            optimizations.append("predicate_pushdown")
        if self.enable_projection_pushdown:
            optimizations.append("projection_pushdown")
        if self.enable_constant_folding:
            optimizations.append("constant_folding")
        if self.enable_reorder_joins:
            optimizations.append("join_reordering")
        if self.enable_merge_filters:
            optimizations.append("filter_merging")
        if self.enable_remove_redundant:
            optimizations.append("redundant_removal")
        return optimizations

    def _optimize_node(self, node: LogicalNode) -> LogicalNode:
        node.children = [self._optimize_node(child) for child in node.children]

        if self.enable_predicate_pushdown:
            node = self._push_down_predicates(node)

        if self.enable_projection_pushdown:
            node = self._push_down_projections(node)

        if self.enable_constant_folding:
            node = self._fold_constants(node)

        if self.enable_merge_filters:
            node = self._merge_adjacent_filters(node)

        if self.enable_remove_redundant:
            node = self._remove_redundant_nodes(node)

        return node

    def _push_down_predicates(self, node: LogicalNode) -> LogicalNode:
        if node.node_type != NodeType.FILTER or not node.children:
            return node

        condition = node.properties.get("condition", "")
        child = node.children[0]

        if child.node_type == NodeType.JOIN:
            left_cond, right_cond, common_cond = self._split_join_condition(
                condition, child.properties.get("join_type", "inner")
            )

            new_children = []
            if left_cond and child.children:
                left_filter = LogicalNode(
                    node_type=NodeType.FILTER,
                    properties={"condition": left_cond},
                    children=[child.children[0]],
                )
                new_children.append(left_filter)
            elif child.children:
                new_children.append(child.children[0])

            if right_cond and len(child.children) > 1:
                right_filter = LogicalNode(
                    node_type=NodeType.FILTER,
                    properties={"condition": right_cond},
                    children=[child.children[1]],
                )
                new_children.append(right_filter)
            elif len(child.children) > 1:
                new_children.append(child.children[1])

            child.children = new_children

            if common_cond:
                node.properties["condition"] = common_cond
                node.children = [child]
                return node
            else:
                return child

        if child.node_type in [NodeType.SCAN, NodeType.PROJECT]:
            if child.node_type == NodeType.SCAN:
                child.properties["filter"] = condition
                return child

        return node

    def _split_join_condition(self, condition: str, join_type: str) -> tuple[str, str, str]:
        if "AND" not in condition.upper():
            return "", "", condition

        parts = [p.strip() for p in condition.split("AND", 1)]
        if len(parts) == 2:
            left_table = parts[0].split(".")[0] if "." in parts[0] else ""
            right_table = parts[1].split(".")[0] if "." in parts[1] else ""

            if left_table and not right_table:
                return parts[0], "", parts[1]
            elif right_table and not left_table:
                return "", parts[1], parts[0]

        return "", "", condition

    def _push_down_projections(self, node: LogicalNode) -> LogicalNode:
        if node.node_type != NodeType.PROJECT or not node.children:
            return node

        columns = node.properties.get("columns", [])
        col_names = {c["name"] for c in columns if c.get("name")}
        child = node.children[0]

        if child.node_type == NodeType.SCAN:
            child.properties["columns"] = list(col_names)
            return node

        if child.node_type in [NodeType.FILTER, NodeType.SORT]:
            child.properties["required_columns"] = list(col_names)

        return node

    def _fold_constants(self, node: LogicalNode) -> LogicalNode:
        if node.node_type == NodeType.FILTER:
            condition = node.properties.get("condition", "")
            if condition:
                folded = self._try_fold_expression(condition)
                if folded != condition:
                    node.properties["condition"] = folded

        return node

    def _try_fold_expression(self, expr: str) -> str:
        try:
            if "1=1" in expr:
                return expr.replace("1=1", "TRUE")
            if "1=0" in expr:
                return "FALSE"
        except Exception:
            pass
        return expr

    def _merge_adjacent_filters(self, node: LogicalNode) -> LogicalNode:
        if (
            node.node_type == NodeType.FILTER
            and node.children
            and node.children[0].node_type == NodeType.FILTER
        ):
            parent_cond = node.properties.get("condition", "")
            child_cond = node.children[0].properties.get("condition", "")

            merged_cond = f"({parent_cond}) AND ({child_cond})"
            node.properties["condition"] = merged_cond
            node.children = node.children[0].children

        return node

    def _remove_redundant_nodes(self, node: LogicalNode) -> LogicalNode:
        if (
            node.node_type == NodeType.PROJECT
            and node.children
            and node.children[0].node_type == NodeType.PROJECT
        ):
            return node.children[0]

        if node.node_type == NodeType.FILTER and node.properties.get("condition") in ["TRUE", "1=1"]:
            return node.children[0] if node.children else node

        return node

    def optimize_batch(self, plans: list[LogicalPlan]) -> list[LogicalPlan]:
        return [self.optimize(plan) for plan in plans]

    def explain(self, plan: LogicalPlan) -> str:
        if not plan.root:
            return "Empty plan"

        lines: list[str] = []
        self._explain_node(plan.root, lines, 0)
        return "\n".join(lines)

    def _explain_node(self, node: LogicalNode, lines: list[str], level: int) -> None:
        indent = "  " * level
        props = ", ".join(f"{k}={v}" for k, v in node.properties.items() if k not in ["columns"])
        lines.append(f"{indent}{node.node_type.value} [{props}]")

        for child in node.children:
            self._explain_node(child, lines, level + 1)
