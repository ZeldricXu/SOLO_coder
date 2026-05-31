"""
streaming_query - 流式SQL查询处理模块

本模块提供完整的流式SQL处理引擎，包括：
- SQL语法解析（支持TUMBLE/HOP/SESSION窗口、WATERMARK、EMIT等流式语法）
- 逻辑计划构建与优化
- 物理计划翻译与代价模型
- 简化的执行引擎

典型用法：
    >>> from streaming_query import SQLParser, LogicalPlanBuilder, Optimizer, PhysicalPlanner, Executor
    >>> parser = SQLParser()
    >>> ast = parser.parse('''
    ...     SELECT TUMBLE_START(event_time, INTERVAL '1' HOUR), COUNT(*)
    ...     FROM orders
    ...     WHERE amount > 100
    ...     GROUP BY TUMBLE(event_time, INTERVAL '1' HOUR)
    ... ''')
    >>> logical_plan = LogicalPlanBuilder().build(ast)
    >>> optimized_plan = Optimizer().optimize(logical_plan)
    >>> physical_plan = PhysicalPlanner().plan(optimized_plan)
    >>> result = Executor().execute(physical_plan)
"""

from typing import List, Dict, Any, Optional

from .sql_parser import (
    SQLParser,
    WindowType,
    WatermarkSpec,
    EmitStrategy,
    ParsedStatement,
)

from .logical_plan import (
    LogicalPlan,
    LogicalPlanNode,
    Source,
    Scan,
    Filter,
    Project,
    Aggregate,
    Join,
    Window,
    Sink,
    LogicalPlanBuilder,
)

from .optimizer import (
    Optimizer,
    OptimizerRule,
    PredicatePushDown,
    ProjectionPruning,
    ConstantFolding,
    ColumnPruning,
    WindowOptimization,
)

from .physical_plan import (
    PhysicalPlan,
    PhysicalPlanNode,
    PhysicalSource,
    PhysicalScan,
    PhysicalFilter,
    PhysicalProject,
    PhysicalAggregate,
    PhysicalJoin,
    PhysicalWindow,
    PhysicalSink,
    CostModel,
    PhysicalPlanner,
)

from .executor import (
    Executor,
    StreamBatch,
    ExecutionContext,
)

__all__: List[str] = [
    # sql_parser
    "SQLParser",
    "WindowType",
    "WatermarkSpec",
    "EmitStrategy",
    "ParsedStatement",
    # logical_plan
    "LogicalPlan",
    "LogicalPlanNode",
    "Source",
    "Scan",
    "Filter",
    "Project",
    "Aggregate",
    "Join",
    "Window",
    "Sink",
    "LogicalPlanBuilder",
    # optimizer
    "Optimizer",
    "OptimizerRule",
    "PredicatePushDown",
    "ProjectionPruning",
    "ConstantFolding",
    "ColumnPruning",
    "WindowOptimization",
    # physical_plan
    "PhysicalPlan",
    "PhysicalPlanNode",
    "PhysicalSource",
    "PhysicalScan",
    "PhysicalFilter",
    "PhysicalProject",
    "PhysicalAggregate",
    "PhysicalJoin",
    "PhysicalWindow",
    "PhysicalSink",
    "CostModel",
    "PhysicalPlanner",
    # executor
    "Executor",
    "StreamBatch",
    "ExecutionContext",
]

__version__: str = "1.0.0"
__author__: str = "Streaming Query Team"
