import copy
import logging
from typing import Dict, List, Optional, Set

from src.domain.query.logical_plan import LogicalPlan, LogicalNode, LogicalNodeType

logger = logging.getLogger(__name__)


class OptimizationRule:
    def name(self) -> str:
        return self.__class__.__name__

    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        raise NotImplementedError


class PredicatePushdownRule(OptimizationRule):
    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        if plan.root is None:
            return plan
        plan.root = self._push_down(plan.root)
        return plan

    def _push_down(self, node: LogicalNode) -> LogicalNode:
        if node.node_type == LogicalNodeType.FILTER and node.children:
            child = node.children[0]
            if child.node_type == LogicalNodeType.JOIN:
                return self._push_filter_through_join(node, child)
            elif child.node_type == LogicalNodeType.PROJECT:
                return self._push_filter_through_project(node, child)
            elif child.node_type == LogicalNodeType.AGGREGATE:
                return node
            else:
                node.children = [self._push_down(c) for c in node.children]
                return node
        else:
            node.children = [self._push_down(c) for c in node.children]
            return node

    def _push_filter_through_join(self, filter_node: LogicalNode, join_node: LogicalNode) -> LogicalNode:
        condition = filter_node.properties.get("condition", "")
        join_condition = join_node.properties.get("condition", "")
        left_source = join_node.children[0] if join_node.children else None
        right_source = join_node.children[1] if len(join_node.children) > 1 else None

        left_filter = LogicalNode(
            node_type=LogicalNodeType.FILTER,
            node_id=filter_node.node_id + "_left",
            properties={"condition": condition},
            estimated_rows=filter_node.estimated_rows * 0.5,
            estimated_cost=50.0,
        )
        right_filter = LogicalNode(
            node_type=LogicalNodeType.FILTER,
            node_id=filter_node.node_id + "_right",
            properties={"condition": condition},
            estimated_rows=filter_node.estimated_rows * 0.5,
            estimated_cost=50.0,
        )

        if left_source:
            left_filter.add_child(left_source)
            join_node.children[0] = left_filter
            left_filter.parent = join_node
        if right_source:
            right_filter.add_child(right_source)
            join_node.children[1] = right_filter
            right_filter.parent = join_node

        join_node.estimated_rows = min(join_node.estimated_rows, filter_node.estimated_rows)
        return join_node

    def _push_filter_through_project(self, filter_node: LogicalNode, project_node: LogicalNode) -> LogicalNode:
        project_node.children = [self._push_down(filter_node) if c == project_node.children[0] else c for c in project_node.children]
        return project_node


class ColumnPruningRule(OptimizationRule):
    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        if plan.root is None:
            return plan
        required = self._collect_required_columns(plan.root)
        self._prune(plan.root, required)
        return plan

    def _collect_required_columns(self, node: LogicalNode) -> Set[str]:
        columns = set()
        if node.node_type == LogicalNodeType.PROJECT:
            for col in node.properties.get("projected_columns", []):
                columns.add(col)
        elif node.node_type == LogicalNodeType.FILTER:
            for col in node.properties.get("condition", "").split():
                if not col.upper() in ("AND", "OR", "NOT", "=", ">", "<", ">=", "<=", "!=", "IS", "NULL", "IN", "LIKE"):
                    columns.add(col.strip("(),."))
        elif node.node_type == LogicalNodeType.AGGREGATE:
            for col in node.properties.get("group_by", []):
                columns.add(col)
            for col in node.properties.get("aggregations", []):
                columns.add(col)
        elif node.node_type == LogicalNodeType.JOIN:
            for col in node.properties.get("condition", "").split("="):
                columns.add(col.strip())
        elif node.node_type == LogicalNodeType.SORT:
            for col in node.properties.get("order_by", []):
                col_name = col.split()[0] if col.split() else col
                columns.add(col_name)

        for child in node.children:
            child_cols = self._collect_required_columns(child)
            if node.node_type not in (LogicalNodeType.PROJECT,):
                columns.update(child_cols)

        return columns

    def _prune(self, node: LogicalNode, required: Set[str]) -> None:
        if node.node_type == LogicalNodeType.SCAN:
            all_cols = node.properties.get("columns", [])
            if all_cols and required:
                pruned = [c for c in all_cols if c in required]
                if pruned:
                    node.properties["columns"] = pruned
        for child in node.children:
            self._prune(child, required)


class JoinReorderRule(OptimizationRule):
    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        if plan.root is None:
            return plan
        self._reorder(plan.root)
        return plan

    def _reorder(self, node: LogicalNode) -> None:
        if node.node_type == LogicalNodeType.JOIN and len(node.children) == 2:
            left = node.children[0]
            right = node.children[1]
            if left.estimated_rows > right.estimated_rows:
                node.children[0] = right
                node.children[1] = left
                logger.debug(f"Reordered join: swapped children for better performance")
        for child in node.children:
            self._reorder(child)


class ConstantFoldingRule(OptimizationRule):
    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        if plan.root is None:
            return plan
        self._fold(plan.root)
        return plan

    def _fold(self, node: LogicalNode) -> None:
        if node.node_type == LogicalNodeType.FILTER:
            condition = node.properties.get("condition", "")
            folded = self._fold_expression(condition)
            node.properties["condition"] = folded
        for child in node.children:
            self._fold(child)

    def _fold_expression(self, expr: str) -> str:
        import re
        pattern = re.compile(r"(\d+)\s*\+\s*(\d+)")
        while True:
            match = pattern.search(expr)
            if not match:
                break
            result = int(match.group(1)) + int(match.group(2))
            expr = expr[:match.start()] + str(result) + expr[match.end():]

        pattern = re.compile(r"(\d+)\s*\*\s*(\d+)")
        while True:
            match = pattern.search(expr)
            if not match:
                break
            result = int(match.group(1)) * int(match.group(2))
            expr = expr[:match.start()] + str(result) + expr[match.end():]

        return expr


class LimitPushdownRule(OptimizationRule):
    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        if plan.root is None:
            return plan
        self._push_limit(plan.root)
        return plan

    def _push_limit(self, node: LogicalNode) -> None:
        if node.node_type == LogicalNodeType.LIMIT and node.children:
            limit_val = node.properties.get("limit", 0)
            child = node.children[0]
            if child.node_type == LogicalNodeType.SORT:
                child.properties["limit"] = limit_val
                logger.debug(f"Pushed limit {limit_val} into sort node")
        for child in node.children:
            self._push_limit(child)


class WindowMergeRule(OptimizationRule):
    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        if plan.root is None:
            return plan
        plan.root = self._merge_windows(plan.root)
        return plan

    def _merge_windows(self, node: LogicalNode) -> LogicalNode:
        if node.node_type == LogicalNodeType.WINDOW and node.children:
            child = node.children[0]
            if child.node_type == LogicalNodeType.WINDOW:
                parent_size = node.properties.get("size")
                child_size = child.properties.get("size")
                if parent_size == child_size:
                    node.children = child.children
                    for gc in child.children:
                        gc.parent = node
                    logger.debug("Merged consecutive windows with same size")
        node.children = [self._merge_windows(c) for c in node.children]
        return node


class PlanOptimizer:
    DEFAULT_RULES = [
        PredicatePushdownRule,
        ColumnPruningRule,
        JoinReorderRule,
        ConstantFoldingRule,
        LimitPushdownRule,
        WindowMergeRule,
    ]

    def __init__(self, rules: Optional[List[type]] = None):
        self._rules = [rule_cls() for rule_cls in (rules or self.DEFAULT_RULES)]

    def optimize(self, plan: LogicalPlan, max_iterations: int = 3) -> LogicalPlan:
        current_plan = plan
        for iteration in range(max_iterations):
            prev_cost = current_plan.estimate_cost()
            for rule in self._rules:
                current_plan = rule.apply(current_plan)
                logger.debug(f"Applied optimization rule: {rule.name()}")
            new_cost = current_plan.estimate_cost()
            if new_cost >= prev_cost:
                break
        return current_plan

    def explain(self, plan: LogicalPlan) -> str:
        if plan.root is None:
            return "Empty plan"
        lines = []
        self._explain_node(plan.root, lines, 0)
        return "\n".join(lines)

    def _explain_node(self, node: LogicalNode, lines: List[str], depth: int) -> None:
        indent = "  " * depth
        cost_info = f"(rows={node.estimated_rows:.0f}, cost={node.estimated_cost:.1f})"
        props_str = ", ".join(f"{k}={v}" for k, v in node.properties.items() if v)
        line = f"{indent}{node.node_type.value} {cost_info}"
        if props_str:
            line += f" [{props_str}]"
        lines.append(line)
        for child in node.children:
            self._explain_node(child, lines, depth + 1)
