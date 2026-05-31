"""
Data Lineage Parsing Module.
Parses SQL to extract table-level and field-level lineage, builds DAG graphs.
Supports pluggable strategies that can be switched at runtime.

Re-exports for backward compatibility.
"""

from app.lineage.base import SQLParser, DAGProvider
from app.lineage.models import (
    LineageType,
    EdgeType,
    ColumnReference,
    TableLineage,
    ColumnLineage
)
from app.lineage.strategies import (
    LineageStrategyType,
    ParseStrategy,
    GraphBuildStrategy,
    StandardParseStrategy,
    StrictParseStrategy,
    PermissiveParseStrategy,
    TableOnlyParseStrategy,
    ColumnOnlyParseStrategy,
    StandardGraphStrategy,
    CTEAwareGraphStrategy,
    StrategyRegistry
)
from app.lineage.sqlglot_adapter import SqlglotAdapter, SQLLineageParser
from app.lineage.networkx_provider import NetworkXProvider
from app.lineage.builder import LineageDAGBuilder
from app.lineage.registry import LineageRegistry


__all__ = [
    "SQLParser",
    "DAGProvider",
    "LineageType",
    "EdgeType",
    "ColumnReference",
    "TableLineage",
    "ColumnLineage",
    "LineageStrategyType",
    "ParseStrategy",
    "GraphBuildStrategy",
    "StandardParseStrategy",
    "StrictParseStrategy",
    "PermissiveParseStrategy",
    "TableOnlyParseStrategy",
    "ColumnOnlyParseStrategy",
    "StandardGraphStrategy",
    "CTEAwareGraphStrategy",
    "StrategyRegistry",
    "SqlglotAdapter",
    "SQLLineageParser",
    "NetworkXProvider",
    "LineageDAGBuilder",
    "LineageRegistry",
]
