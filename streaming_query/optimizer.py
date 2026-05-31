"""
optimizer - 逻辑计划优化器

实现多种逻辑计划优化规则，提高查询执行效率。
优化规则包括：
- 谓词下推（Predicate PushDown）：将过滤条件尽可能下推到数据源
- 投影裁剪（Projection Pruning）：只读取需要的列
- 常量折叠（Constant Folding）：预先计算常量表达式
- 列裁剪（Column Pruning）：移除未使用的列
- 窗口优化（Window Optimization）：优化窗口函数执行

所有优化规则都实现为可插拔的访问者模式，可以灵活组合使用。
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

import sqlglot
from sqlglot import exp

from .logical_plan import (
    Aggregate,
    Filter,
    Join,
    LogicalPlan,
    LogicalPlanNode,
    LogicalPlanVisitor,
    NodeType,
    Project,
    Scan,
    Sink,
    Source,
    Window,
)


@dataclass
class OptimizationResult:
    """
    优化结果

    属性:
        plan: 优化后的逻辑计划
        applied_rules: 应用的优化规则列表
        statistics: 优化统计信息
    """
    plan: LogicalPlan
    applied_rules: List[str] = field(default_factory=list)
    statistics: Dict[str, Any] = field(default_factory=dict)


class OptimizerRule(ABC):
    """
    优化规则抽象基类

    所有具体的优化规则都需要继承此类并实现apply方法。
    """

    def __init__(self, name: str) -> None:
        """
        初始化优化规则

        参数:
            name: 规则名称
        """
        self.name: str = name
        self.applied: bool = False

    @abstractmethod
    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        """
        应用优化规则

        参数:
            plan: 待优化的逻辑计划

        返回:
            优化后的逻辑计划
        """
        ...

    def reset(self) -> None:
        """重置规则状态"""
        self.applied = False


class PredicatePushDown(OptimizerRule, LogicalPlanVisitor):
    """
    谓词下推优化

    将过滤条件尽可能下推到靠近数据源的位置，减少后续处理的数据量。
    对于流式查询，这可以显著减少窗口聚合等操作的数据量。
    """

    def __init__(self) -> None:
        super().__init__("PredicatePushDown")
        self._pushable_predicates: List[exp.Expression] = []

    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        """
        应用谓词下推优化

        参数:
            plan: 待优化的逻辑计划

        返回:
            优化后的逻辑计划
        """
        self.reset()
        self._pushable_predicates = []

        new_root = self._visit(plan.root)
        plan.root = new_root
        return plan

    def _visit(self, node: LogicalPlanNode) -> LogicalPlanNode:
        """
        后序遍历访问节点

        参数:
            node: 当前节点

        返回:
            优化后的节点
        """
        node.children = [self._visit(child) for child in node.children]
        return node.accept(self)

    def visit_source(self, node: Source) -> LogicalPlanNode:
        """处理Source节点，不做处理"""
        return node

    def visit_scan(self, node: Scan) -> LogicalPlanNode:
        """
        处理Scan节点，将可下推的谓词附加到Scan上

        参数:
            node: Scan节点

        返回:
            优化后的Scan节点
        """
        if self._pushable_predicates:
            if node.predicate:
                combined = exp.and_(node.predicate, *self._pushable_predicates)
            else:
                combined = (
                    self._pushable_predicates[0]
                    if len(self._pushable_predicates) == 1
                    else exp.and_(*self._pushable_predicates)
                )
            node.predicate = combined
            node.push_down = True
            self.applied = True
            self._pushable_predicates = []
        return node

    def visit_filter(self, node: Filter) -> LogicalPlanNode:
        """
        处理Filter节点，收集可下推的谓词

        参数:
            node: Filter节点

        返回:
            优化后的节点，如果所有谓词都下推了则返回子节点
        """
        predicates = self._split_predicate(node.condition)
        pushable, non_pushable = self._classify_predicates(predicates, node.children[0])

        if pushable:
            self._pushable_predicates.extend(pushable)
            self.applied = True

        if non_pushable:
            if len(non_pushable) == 1:
                node.condition = non_pushable[0]
            else:
                node.condition = exp.and_(*non_pushable)
            return node
        else:
            return node.children[0]

    def visit_project(self, node: Project) -> LogicalPlanNode:
        """
        处理Project节点，先处理子节点，谓词不能穿过Project（除非是简单列映射）

        参数:
            node: Project节点

        返回:
            优化后的节点
        """
        if self._pushable_predicates:
            if self._is_simple_projection(node):
                pass
            else:
                filter_node = Filter(
                    node_id=f"filter_pushed_{id(node)}",
                    condition=exp.and_(*self._pushable_predicates),
                    child=node.children[0],
                )
                node.children[0] = filter_node
                self._pushable_predicates = []
                self.applied = True
        return node

    def visit_aggregate(self, node: Aggregate) -> LogicalPlanNode:
        """
        处理Aggregate节点，谓词不能穿过聚合（除非是GROUP BY列上的条件）

        参数:
            node: Aggregate节点

        返回:
            优化后的节点
        """
        if self._pushable_predicates:
            group_by_cols = {str(g).lower() for g in node.group_by}
            can_push = []
            cannot_push = []

            for pred in self._pushable_predicates:
                cols = self._get_predicate_columns(pred)
                if all(col.lower() in group_by_cols for col in cols):
                    can_push.append(pred)
                else:
                    cannot_push.append(pred)

            self._pushable_predicates = can_push

            if cannot_push:
                filter_node = Filter(
                    node_id=f"filter_agg_{id(node)}",
                    condition=exp.and_(*cannot_push),
                    child=node,
                )
                self.applied = True
                return filter_node

        return node

    def visit_join(self, node: Join) -> LogicalPlanNode:
        """
        处理Join节点，将谓词下推到左右子节点

        参数:
            node: Join节点

        返回:
            优化后的节点
        """
        if self._pushable_predicates:
            left_cols = set(node.children[0].get_all_columns())
            right_cols = set(node.children[1].get_all_columns())

            left_preds = []
            right_preds = []
            join_preds = []

            for pred in self._pushable_predicates:
                cols = self._get_predicate_columns(pred)
                if cols.issubset(left_cols):
                    left_preds.append(pred)
                elif cols.issubset(right_cols):
                    right_preds.append(pred)
                else:
                    join_preds.append(pred)

            if left_preds:
                self._pushable_predicates = left_preds
                node.children[0] = self._visit(node.children[0])
                self.applied = True

            if right_preds:
                self._pushable_predicates = right_preds
                node.children[1] = self._visit(node.children[1])
                self.applied = True

            self._pushable_predicates = join_preds

        return node

    def visit_window(self, node: Window) -> LogicalPlanNode:
        """
        处理Window节点，谓词可以下推（如果不涉及窗口函数结果）

        参数:
            node: Window节点

        返回:
            优化后的节点
        """
        if self._pushable_predicates:
            window_cols = {wf.alias for wf in node.window_functions}
            can_push = []
            cannot_push = []

            for pred in self._pushable_predicates:
                cols = self._get_predicate_columns(pred)
                if not any(col in window_cols for col in cols):
                    can_push.append(pred)
                else:
                    cannot_push.append(pred)

            self._pushable_predicates = can_push

            if cannot_push:
                filter_node = Filter(
                    node_id=f"filter_window_{id(node)}",
                    condition=exp.and_(*cannot_push),
                    child=node,
                )
                self.applied = True
                return filter_node

        return node

    def visit_sink(self, node: Sink) -> LogicalPlanNode:
        """
        处理Sink节点，如果还有未下推的谓词，创建Filter节点

        参数:
            node: Sink节点

        返回:
            优化后的节点
        """
        if self._pushable_predicates:
            filter_node = Filter(
                node_id=f"filter_final_{id(node)}",
                condition=exp.and_(*self._pushable_predicates),
                child=node.children[0],
            )
            node.children[0] = filter_node
            self._pushable_predicates = []
            self.applied = True
        return node

    def _split_predicate(self, condition: exp.Expression) -> List[exp.Expression]:
        """
        将AND连接的谓词拆分为列表

        参数:
            condition: 条件表达式

        返回:
            谓词列表
        """
        predicates: List[exp.Expression] = []

        def split(expr: exp.Expression) -> None:
            if isinstance(expr, exp.And):
                split(expr.left)
                split(expr.right)
            else:
                predicates.append(expr)

        split(condition)
        return predicates

    def _classify_predicates(
        self,
        predicates: List[exp.Expression],
        child: LogicalPlanNode,
    ) -> Tuple[List[exp.Expression], List[exp.Expression]]:
        """
        分类谓词为可下推和不可下推

        参数:
            predicates: 谓词列表
            child: 子节点

        返回:
            (可下推谓词, 不可下推谓词)
        """
        available_cols = child.get_all_columns()
        pushable: List[exp.Expression] = []
        non_pushable: List[exp.Expression] = []

        for pred in predicates:
            if self._is_predicate_pushable(pred, available_cols):
                pushable.append(pred)
            else:
                non_pushable.append(pred)

        return pushable, non_pushable

    def _is_predicate_pushable(
        self,
        pred: exp.Expression,
        available_cols: Set[str],
    ) -> bool:
        """
        判断谓词是否可下推

        参数:
            pred: 谓词表达式
            available_cols: 可用列集合

        返回:
            True表示可下推
        """
        pred_cols = self._get_predicate_columns(pred)

        if not pred_cols.issubset(available_cols):
            return False

        if self._contains_volatile_function(pred):
            return False

        return True

    def _get_predicate_columns(self, pred: exp.Expression) -> Set[str]:
        """
        获取谓词中引用的所有列

        参数:
            pred: 谓词表达式

        返回:
            列名集合
        """
        columns: Set[str] = set()
        for node in pred.walk():
            if isinstance(node, exp.Column):
                columns.add(node.name)
        return columns

    def _contains_volatile_function(self, expr: exp.Expression) -> bool:
        """
        检查表达式是否包含不稳定函数（如RAND(), NOW()等）

        参数:
            expr: 表达式

        返回:
            True表示包含不稳定函数
        """
        volatile_funcs = {"RAND", "RANDOM", "NOW", "CURRENT_TIMESTAMP", "UUID"}
        for node in expr.walk():
            if isinstance(node, exp.Func) and node.name.upper() in volatile_funcs:
                return True
        return False

    def _is_simple_projection(self, node: Project) -> bool:
        """
        检查是否为简单列映射投影

        参数:
            node: Project节点

        返回:
            True表示是简单投影
        """
        if node.is_star:
            return True

        for expr, _ in node.projections:
            if not isinstance(expr, exp.Column):
                return False
        return True


class ProjectionPruning(OptimizerRule, LogicalPlanVisitor):
    """
    投影裁剪优化

    只读取查询需要的列，减少数据传输和处理量。
    对于列式存储，这可以显著减少IO开销。
    """

    def __init__(self) -> None:
        super().__init__("ProjectionPruning")
        self._required_columns: Set[str] = set()

    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        """
        应用投影裁剪优化

        参数:
            plan: 待优化的逻辑计划

        返回:
            优化后的逻辑计划
        """
        self.reset()

        sink_node = plan.root
        self._required_columns = set(sink_node.output_columns)

        plan.root = self._visit(plan.root)
        return plan

    def _visit(self, node: LogicalPlanNode) -> LogicalPlanNode:
        """
        先序遍历访问节点，传递需要的列信息

        参数:
            node: 当前节点

        返回:
            优化后的节点
        """
        result = node.accept(self)
        return result

    def visit_source(self, node: Source) -> LogicalPlanNode:
        """处理Source节点，更新输出列"""
        if self._required_columns:
            node.output_columns = [
                col for col in node.output_columns if col in self._required_columns
            ]
            node.schema = {
                k: v for k, v in node.schema.items() if k in self._required_columns
            }
            self.applied = True
        return node

    def visit_scan(self, node: Scan) -> LogicalPlanNode:
        """
        处理Scan节点，设置投影列

        参数:
            node: Scan节点

        返回:
            优化后的Scan节点
        """
        if self._required_columns:
            node.projected_columns = list(self._required_columns)
            node.output_columns = list(self._required_columns)
            self.applied = True

        self._visit(node.children[0])
        return node

    def visit_filter(self, node: Filter) -> LogicalPlanNode:
        """
        处理Filter节点，收集需要的列（包括过滤条件中的列）

        参数:
            node: Filter节点

        返回:
            优化后的节点
        """
        filter_cols = self._get_expression_columns(node.condition)
        child_required = self._required_columns | filter_cols

        self._required_columns = child_required
        node.children[0] = self._visit(node.children[0])

        node.output_columns = [
            col for col in node.children[0].output_columns if col in self._required_columns
        ]

        return node

    def visit_project(self, node: Project) -> LogicalPlanNode:
        """
        处理Project节点，收集投影表达式需要的列

        参数:
            node: Project节点

        返回:
            优化后的节点
        """
        child_required: Set[str] = set()
        for expr, _ in node.projections:
            child_required |= self._get_expression_columns(expr)

        self._required_columns = child_required
        node.children[0] = self._visit(node.children[0])

        return node

    def visit_aggregate(self, node: Aggregate) -> LogicalPlanNode:
        """
        处理Aggregate节点，收集GROUP BY和聚合函数需要的列

        参数:
            node: Aggregate节点

        返回:
            优化后的节点
        """
        child_required: Set[str] = set()

        for group_expr in node.group_by:
            child_required |= self._get_expression_columns(group_expr)

        for agg_expr in node.aggregate_exprs:
            for arg in agg_expr.arguments:
                child_required |= self._get_expression_columns(arg)

        if node.having:
            child_required |= self._get_expression_columns(node.having)

        self._required_columns = child_required
        node.children[0] = self._visit(node.children[0])

        return node

    def visit_join(self, node: Join) -> LogicalPlanNode:
        """
        处理Join节点，分别计算左右子节点需要的列

        参数:
            node: Join节点

        返回:
            优化后的节点
        """
        left_cols = set(node.children[0].get_all_columns())
        right_cols = set(node.children[1].get_all_columns())

        left_required = self._required_columns & left_cols
        right_required = self._required_columns & right_cols

        left_required |= set(node.left_key)
        right_required |= set(node.right_key)

        if node.condition:
            cond_cols = self._get_expression_columns(node.condition)
            left_required |= cond_cols & left_cols
            right_required |= cond_cols & right_cols

        self._required_columns = left_required
        node.children[0] = self._visit(node.children[0])

        self._required_columns = right_required
        node.children[1] = self._visit(node.children[1])

        return node

    def visit_window(self, node: Window) -> LogicalPlanNode:
        """
        处理Window节点，收集窗口函数需要的列

        参数:
            node: Window节点

        返回:
            优化后的节点
        """
        child_required = self._required_columns.copy()
        child_required |= set(node.partition_by)
        child_required |= set(node.order_by)

        for wf in node.window_functions:
            for arg in wf.arguments:
                child_required |= self._get_expression_columns(arg)

        if node.window_spec:
            child_required.add(node.window_spec.time_column)

        self._required_columns = child_required
        node.children[0] = self._visit(node.children[0])

        return node

    def visit_sink(self, node: Sink) -> LogicalPlanNode:
        """
        处理Sink节点，传递需要的列

        参数:
            node: Sink节点

        返回:
            优化后的节点
        """
        node.children[0] = self._visit(node.children[0])
        return node

    def _get_expression_columns(self, expr: exp.Expression) -> Set[str]:
        """
        获取表达式中引用的所有列

        参数:
            expr: 表达式

        返回:
            列名集合
        """
        columns: Set[str] = set()
        if expr is None:
            return columns
        for node in expr.walk():
            if isinstance(node, exp.Column):
                columns.add(node.name)
        return columns


class ConstantFolding(OptimizerRule, LogicalPlanVisitor):
    """
    常量折叠优化

    预先计算常量表达式的值，避免在运行时重复计算。
    例如：WHERE amount > 100 * 2 可以优化为 WHERE amount > 200
    """

    def __init__(self) -> None:
        super().__init__("ConstantFolding")

    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        """
        应用常量折叠优化

        参数:
            plan: 待优化的逻辑计划

        返回:
            优化后的逻辑计划
        """
        self.reset()
        plan.root = self._visit(plan.root)
        return plan

    def _visit(self, node: LogicalPlanNode) -> LogicalPlanNode:
        """
        后序遍历访问节点

        参数:
            node: 当前节点

        返回:
            优化后的节点
        """
        node.children = [self._visit(child) for child in node.children]
        return node.accept(self)

    def visit_source(self, node: Source) -> LogicalPlanNode:
        return node

    def visit_scan(self, node: Scan) -> LogicalPlanNode:
        if node.predicate:
            folded = self._fold_constants(node.predicate)
            if folded is not node.predicate:
                node.predicate = folded
                self.applied = True
        return node

    def visit_filter(self, node: Filter) -> LogicalPlanNode:
        if node.condition:
            folded = self._fold_constants(node.condition)
            if folded is not node.condition:
                node.condition = folded
                self.applied = True

            if self._is_always_true(folded):
                return node.children[0]
            if self._is_always_false(folded):
                node.condition = folded
        return node

    def visit_project(self, node: Project) -> LogicalPlanNode:
        new_projections: List[Tuple[exp.Expression, Optional[str]]] = []
        for expr, alias in node.projections:
            folded = self._fold_constants(expr)
            if folded is not expr:
                self.applied = True
            new_projections.append((folded, alias))
        node.projections = new_projections
        return node

    def visit_aggregate(self, node: Aggregate) -> LogicalPlanNode:
        new_group_by = [self._fold_constants(g) for g in node.group_by]
        if any(ng is not og for ng, og in zip(new_group_by, node.group_by)):
            node.group_by = new_group_by
            self.applied = True

        if node.having:
            folded = self._fold_constants(node.having)
            if folded is not node.having:
                node.having = folded
                self.applied = True

        return node

    def visit_join(self, node: Join) -> LogicalPlanNode:
        if node.condition:
            folded = self._fold_constants(node.condition)
            if folded is not node.condition:
                node.condition = folded
                self.applied = True
        return node

    def visit_window(self, node: Window) -> LogicalPlanNode:
        return node

    def visit_sink(self, node: Sink) -> LogicalPlanNode:
        return node

    def _fold_constants(self, expr: exp.Expression) -> exp.Expression:
        """
        折叠表达式中的常量

        参数:
            expr: 原始表达式

        返回:
            折叠后的表达式
        """
        if self._is_constant_expression(expr):
            value = self._evaluate_expression(expr)
            if value is not None:
                self.applied = True
                return self._value_to_expression(value)

        if isinstance(expr, exp.Binary):
            expr.left = self._fold_constants(expr.left)
            expr.right = self._fold_constants(expr.right)

            if self._is_constant_expression(expr.left) and self._is_constant_expression(expr.right):
                value = self._evaluate_expression(expr)
                if value is not None:
                    self.applied = True
                    return self._value_to_expression(value)

        elif isinstance(expr, exp.Unary):
            expr.this = self._fold_constants(expr.this)

        return expr

    def _is_constant_expression(self, expr: exp.Expression) -> bool:
        """
        判断表达式是否为纯常量表达式（不包含列引用）

        参数:
            expr: 表达式

        返回:
            True表示为常量表达式
        """
        for node in expr.walk():
            if isinstance(node, exp.Column):
                return False
            if isinstance(node, exp.Func) and node.name.upper() in {"RAND", "NOW", "UUID"}:
                return False
        return True

    def _evaluate_expression(self, expr: exp.Expression) -> Optional[Any]:
        """
        计算常量表达式的值

        参数:
            expr: 常量表达式

        返回:
            计算结果，无法计算时返回None
        """
        try:
            if isinstance(expr, exp.Literal):
                return expr.value

            if isinstance(expr, exp.Boolean):
                return expr.this

            if isinstance(expr, exp.Number):
                return float(expr.this) if "." in str(expr.this) else int(expr.this)

            if isinstance(expr, exp.Add):
                left = self._evaluate_expression(expr.left)
                right = self._evaluate_expression(expr.right)
                if left is not None and right is not None:
                    return left + right

            if isinstance(expr, exp.Sub):
                left = self._evaluate_expression(expr.left)
                right = self._evaluate_expression(expr.right)
                if left is not None and right is not None:
                    return left - right

            if isinstance(expr, exp.Mul):
                left = self._evaluate_expression(expr.left)
                right = self._evaluate_expression(expr.right)
                if left is not None and right is not None:
                    return left * right

            if isinstance(expr, exp.Div):
                left = self._evaluate_expression(expr.left)
                right = self._evaluate_expression(expr.right)
                if left is not None and right is not None and right != 0:
                    return left / right

            if isinstance(expr, exp.GT):
                left = self._evaluate_expression(expr.left)
                right = self._evaluate_expression(expr.right)
                if left is not None and right is not None:
                    return left > right

            if isinstance(expr, exp.LT):
                left = self._evaluate_expression(expr.left)
                right = self._evaluate_expression(expr.right)
                if left is not None and right is not None:
                    return left < right

            if isinstance(expr, exp.EQ):
                left = self._evaluate_expression(expr.left)
                right = self._evaluate_expression(expr.right)
                if left is not None and right is not None:
                    return left == right

            if isinstance(expr, exp.And):
                left = self._evaluate_expression(expr.left)
                right = self._evaluate_expression(expr.right)
                if left is not None and right is not None:
                    return left and right

            if isinstance(expr, exp.Or):
                left = self._evaluate_expression(expr.left)
                right = self._evaluate_expression(expr.right)
                if left is not None and right is not None:
                    return left or right

        except Exception:
            return None

        return None

    def _value_to_expression(self, value: Any) -> exp.Expression:
        """
        将值转换为表达式

        参数:
            value: 值

        返回:
            表达式对象
        """
        if isinstance(value, bool):
            return exp.Boolean(this=value)
        elif isinstance(value, int):
            return exp.Number(this=str(value))
        elif isinstance(value, float):
            return exp.Number(this=str(value))
        elif isinstance(value, str):
            return exp.Literal(this=value)
        else:
            return exp.Literal(this=str(value))

    def _is_always_true(self, expr: exp.Expression) -> bool:
        """判断表达式是否恒为真"""
        if isinstance(expr, exp.Boolean) and expr.this:
            return True
        return False

    def _is_always_false(self, expr: exp.Expression) -> bool:
        """判断表达式是否恒为假"""
        if isinstance(expr, exp.Boolean) and not expr.this:
            return True
        return False


class ColumnPruning(OptimizerRule, LogicalPlanVisitor):
    """
    列裁剪优化

    移除计划中未使用的列，减少数据传输和内存占用。
    """

    def __init__(self) -> None:
        super().__init__("ColumnPruning")
        self._used_columns: Set[str] = set()

    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        """
        应用列裁剪优化

        参数:
            plan: 待优化的逻辑计划

        返回:
            优化后的逻辑计划
        """
        self.reset()
        self._used_columns = set()

        plan.root = self._visit_post_order(plan.root)
        return plan

    def _visit_post_order(self, node: LogicalPlanNode) -> LogicalPlanNode:
        """
        后序遍历，先收集使用的列

        参数:
            node: 当前节点

        返回:
            优化后的节点
        """
        node.children = [self._visit_post_order(child) for child in node.children]
        return self._prune_node(node)

    def _prune_node(self, node: LogicalPlanNode) -> LogicalPlanNode:
        """
        裁剪节点中的未使用列

        参数:
            node: 当前节点

        返回:
            裁剪后的节点
        """
        return node.accept(self)

    def visit_source(self, node: Source) -> LogicalPlanNode:
        if self._used_columns:
            original_cols = node.output_columns.copy()
            node.output_columns = [
                col for col in node.output_columns if col in self._used_columns
            ]
            if node.output_columns != original_cols:
                self.applied = True
        return node

    def visit_scan(self, node: Scan) -> LogicalPlanNode:
        if self._used_columns:
            original_cols = node.projected_columns.copy()
            node.projected_columns = [
                col for col in node.projected_columns if col in self._used_columns
            ]
            node.output_columns = node.projected_columns.copy()
            if node.projected_columns != original_cols:
                self.applied = True

            self._used_columns |= set(node.projected_columns)
        return node

    def visit_filter(self, node: Filter) -> LogicalPlanNode:
        filter_cols = self._get_columns(node.condition)
        self._used_columns |= filter_cols

        original_output = node.output_columns.copy()
        node.output_columns = [
            col for col in node.output_columns if col in self._used_columns
        ]
        if node.output_columns != original_output:
            self.applied = True

        return node

    def visit_project(self, node: Project) -> LogicalPlanNode:
        child_used: Set[str] = set()
        for expr, _ in node.projections:
            child_used |= self._get_columns(expr)

        self._used_columns = child_used

        output_aliases = {alias for _, alias in node.projections if alias}
        self._used_columns = output_aliases

        return node

    def visit_aggregate(self, node: Aggregate) -> LogicalPlanNode:
        child_used: Set[str] = set()

        for g in node.group_by:
            child_used |= self._get_columns(g)

        for agg in node.aggregate_exprs:
            for arg in agg.arguments:
                child_used |= self._get_columns(arg)

        if node.having:
            child_used |= self._get_columns(node.having)

        self._used_columns = child_used

        group_outputs = {str(g) for g in node.group_by}
        agg_outputs = {agg.alias for agg in node.aggregate_exprs if agg.alias}
        self._used_columns = group_outputs | agg_outputs

        return node

    def visit_join(self, node: Join) -> LogicalPlanNode:
        join_cols = set(node.left_key) | set(node.right_key)
        if node.condition:
            join_cols |= self._get_columns(node.condition)

        left_used = self._used_columns & set(node.children[0].output_columns)
        right_used = self._used_columns & set(node.children[1].output_columns)

        left_used |= join_cols & set(node.children[0].output_columns)
        right_used |= join_cols & set(node.children[1].output_columns)

        original_left = node.children[0].output_columns.copy()
        node.children[0].output_columns = [
            col for col in node.children[0].output_columns if col in left_used
        ]
        if node.children[0].output_columns != original_left:
            self.applied = True

        original_right = node.children[1].output_columns.copy()
        node.children[1].output_columns = [
            col for col in node.children[1].output_columns if col in right_used
        ]
        if node.children[1].output_columns != original_right:
            self.applied = True

        node.output_columns = node.children[0].output_columns + node.children[1].output_columns
        self._used_columns = set(node.output_columns)

        return node

    def visit_window(self, node: Window) -> LogicalPlanNode:
        window_cols = set(node.partition_by) | set(node.order_by)
        if node.window_spec:
            window_cols.add(node.window_spec.time_column)

        for wf in node.window_functions:
            for arg in wf.arguments:
                window_cols |= self._get_columns(arg)

        child_output = set(node.children[0].output_columns)
        self._used_columns |= window_cols & child_output

        return node

    def visit_sink(self, node: Sink) -> LogicalPlanNode:
        self._used_columns = set(node.output_columns)
        return node

    def _get_columns(self, expr: Optional[exp.Expression]) -> Set[str]:
        """获取表达式中的列"""
        columns: Set[str] = set()
        if expr is None:
            return columns
        for node in expr.walk():
            if isinstance(node, exp.Column):
                columns.add(node.name)
        return columns


class WindowOptimization(OptimizerRule, LogicalPlanVisitor):
    """
    窗口优化

    针对流式窗口的特定优化：
    - 合并相邻的相同窗口
    - 窗口函数复用
    - 预聚合优化（TUMBLE/HOP窗口）
    - 延迟数据处理优化
    """

    def __init__(self) -> None:
        super().__init__("WindowOptimization")

    def apply(self, plan: LogicalPlan) -> LogicalPlan:
        """
        应用窗口优化

        参数:
            plan: 待优化的逻辑计划

        返回:
            优化后的逻辑计划
        """
        self.reset()
        plan.root = self._merge_windows(plan.root)
        plan.root = self._optimize_window_order(plan.root)
        return plan

    def _merge_windows(self, node: LogicalPlanNode) -> LogicalPlanNode:
        """
        合并相邻的相同窗口

        参数:
            node: 当前节点

        返回:
            优化后的节点
        """
        if len(node.children) > 0:
            node.children = [self._merge_windows(child) for child in node.children]

        if (
            isinstance(node, Window)
            and len(node.children) > 0
            and isinstance(node.children[0], Window)
        ):
            parent_window = node
            child_window = node.children[0]

            if self._windows_are_compatible(parent_window, child_window):
                merged = self._merge_window_nodes(parent_window, child_window)
                self.applied = True
                return merged

        return node

    def _windows_are_compatible(self, w1: Window, w2: Window) -> bool:
        """
        判断两个窗口是否兼容可合并

        参数:
            w1: 窗口1
            w2: 窗口2

        返回:
            True表示可合并
        """
        if not w1.window_spec or not w2.window_spec:
            return False

        spec1 = w1.window_spec
        spec2 = w2.window_spec

        if spec1.window_type != spec2.window_type:
            return False

        if spec1.time_column != spec2.time_column:
            return False

        if spec1.size != spec2.size:
            return False

        if spec1.slide != spec2.slide:
            return False

        if spec1.gap != spec2.gap:
            return False

        if set(w1.partition_by) != set(w2.partition_by):
            return False

        return True

    def _merge_window_nodes(self, parent: Window, child: Window) -> Window:
        """
        合并两个窗口节点

        参数:
            parent: 父窗口
            child: 子窗口

        返回:
            合并后的窗口节点
        """
        merged = Window(
            node_id=f"window_merged_{id(parent)}_{id(child)}",
            window_spec=parent.window_spec,
            child=child.children[0],
            partition_by=parent.partition_by,
            order_by=parent.order_by,
            window_functions=child.window_functions + parent.window_functions,
        )
        return merged

    def _optimize_window_order(self, node: LogicalPlanNode) -> LogicalPlanNode:
        """
        优化窗口顺序，将过滤下推到窗口前

        参数:
            node: 当前节点

        返回:
            优化后的节点
        """
        if len(node.children) > 0:
            node.children = [
                self._optimize_window_order(child) for child in node.children
            ]

        if (
            isinstance(node, Filter)
            and len(node.children) > 0
            and isinstance(node.children[0], Window)
        ):
            window_node = node.children[0]
            filter_condition = node.condition

            window_cols = {wf.alias for wf in window_node.window_functions}
            filter_cols = self._get_condition_columns(filter_condition)

            if not filter_cols & window_cols:
                window_node.children[0] = Filter(
                    node_id=f"filter_before_window_{id(node)}",
                    condition=filter_condition,
                    child=window_node.children[0],
                )
                self.applied = True
                return window_node

        return node

    def _get_condition_columns(self, condition: exp.Expression) -> Set[str]:
        """获取条件中的列"""
        columns: Set[str] = set()
        for node in condition.walk():
            if isinstance(node, exp.Column):
                columns.add(node.name)
        return columns

    def visit_source(self, node: Source) -> LogicalPlanNode:
        return node

    def visit_scan(self, node: Scan) -> LogicalPlanNode:
        return node

    def visit_filter(self, node: Filter) -> LogicalPlanNode:
        return node

    def visit_project(self, node: Project) -> LogicalPlanNode:
        return node

    def visit_aggregate(self, node: Aggregate) -> LogicalPlanNode:
        return node

    def visit_join(self, node: Join) -> LogicalPlanNode:
        return node

    def visit_window(self, node: Window) -> LogicalPlanNode:
        return node

    def visit_sink(self, node: Sink) -> LogicalPlanNode:
        return node


class Optimizer:
    """
    逻辑计划优化器

    组合多个优化规则，按顺序应用于逻辑计划。
    """

    def __init__(
        self,
        rules: Optional[List[OptimizerRule]] = None,
        max_iterations: int = 3,
    ) -> None:
        """
        初始化优化器

        参数:
            rules: 优化规则列表，默认使用所有内置规则
            max_iterations: 最大迭代次数，用于收敛优化
        """
        self.rules: List[OptimizerRule] = rules or [
            ConstantFolding(),
            PredicatePushDown(),
            ProjectionPruning(),
            ColumnPruning(),
            WindowOptimization(),
        ]
        self.max_iterations: int = max_iterations

    def optimize(self, plan: LogicalPlan) -> OptimizationResult:
        """
        优化逻辑计划

        参数:
            plan: 待优化的逻辑计划

        返回:
            优化结果，包含优化后的计划和统计信息
        """
        applied_rules: List[str] = []
        iterations = 0
        total_rules_applied = 0

        while iterations < self.max_iterations:
            iteration_applied = 0

            for rule in self.rules:
                rule.reset()
                plan = rule.apply(plan)

                if rule.applied:
                    applied_rules.append(f"{rule.name}(iter_{iterations})")
                    iteration_applied += 1
                    total_rules_applied += 1

            if iteration_applied == 0:
                break

            iterations += 1

        valid, errors = plan.validate()
        if not valid:
            raise ValueError(f"优化后的计划无效: {errors}")

        statistics = {
            "iterations": iterations,
            "total_rules_applied": total_rules_applied,
            "node_count": len(plan.collect_nodes()),
        }

        return OptimizationResult(
            plan=plan,
            applied_rules=applied_rules,
            statistics=statistics,
        )

    def add_rule(self, rule: OptimizerRule) -> None:
        """
        添加自定义优化规则

        参数:
            rule: 优化规则
        """
        self.rules.append(rule)

    def remove_rule(self, rule_name: str) -> None:
        """
        移除指定名称的优化规则

        参数:
            rule_name: 规则名称
        """
        self.rules = [r for r in self.rules if r.name != rule_name]
