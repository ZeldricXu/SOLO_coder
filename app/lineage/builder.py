"""
Lineage DAG Builder with pluggable strategies.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from app.lineage.base import DAGProvider, SQLParser
from app.lineage.models import EdgeType
from app.lineage.networkx_provider import NetworkXProvider
from app.lineage.sqlglot_adapter import SqlglotAdapter
from app.lineage.strategies import (
    GraphBuildStrategy,
    ParseStrategy,
    StrategyRegistry
)


class LineageDAGBuilder:
    def __init__(
        self,
        parser: Optional[SQLParser] = None,
        dag_provider: Optional[DAGProvider] = None,
        strategy_registry: Optional[StrategyRegistry] = None
    ):
        self._parser = parser or SqlglotAdapter()
        self._dag = dag_provider or NetworkXProvider()
        self._strategy_registry = strategy_registry or StrategyRegistry()
    
    @property
    def graph(self):
        if hasattr(self._dag, 'graph'):
            return self._dag.graph
        raise AttributeError("Underlying DAG provider does not expose graph")
    
    def add_node(
        self,
        node_id: str,
        node_type: str,
        name: str,
        attributes: Optional[Dict[str, Any]] = None
    ):
        self._dag.add_node(node_id, node_type, name, attributes)
    
    def add_edge(
        self,
        source: str,
        target: str,
        edge_type: EdgeType = EdgeType.DEPENDS_ON,
        attributes: Optional[Dict[str, Any]] = None
    ):
        self._dag.add_edge(source, target, edge_type, attributes)
    
    def build_from_sql(
        self,
        sql: str,
        dialect: Optional[str] = None,
        parse_strategy: Optional[str] = None,
        graph_strategy: Optional[str] = None
    ):
        parser = self._parser
        if dialect is not None:
            parser = SqlglotAdapter(dialect)
        
        strategy = self._strategy_registry.get_parse_strategy(parse_strategy)
        table_lineage, column_lineages = strategy.apply(parser, sql)
        
        build_strategy = self._strategy_registry.get_graph_strategy(graph_strategy)
        build_strategy.build(self._dag, table_lineage, column_lineages)
    
    def set_parse_strategy(self, strategy_name: str) -> bool:
        return self._strategy_registry.set_active_parse_strategy(strategy_name)
    
    def set_graph_strategy(self, strategy_name: str) -> bool:
        return self._strategy_registry.set_active_graph_strategy(strategy_name)
    
    def get_parse_strategies(self) -> List[str]:
        return self._strategy_registry.list_parse_strategies()
    
    def get_graph_strategies(self) -> List[str]:
        return self._strategy_registry.list_graph_strategies()
    
    def get_active_parse_strategy(self) -> str:
        return self._strategy_registry.get_active_parse_strategy_name()
    
    def get_active_graph_strategy(self) -> str:
        return self._strategy_registry.get_active_graph_strategy_name()
    
    def register_parse_strategy(self, strategy: ParseStrategy):
        self._strategy_registry.register_parse_strategy(strategy)
    
    def register_graph_strategy(self, strategy: GraphBuildStrategy):
        self._strategy_registry.register_graph_strategy(strategy)
    
    def get_upstream(self, node_id: str) -> List[str]:
        return self._dag.get_upstream(node_id)
    
    def get_downstream(self, node_id: str) -> List[str]:
        return self._dag.get_downstream(node_id)
    
    def has_cycle(self) -> bool:
        return self._dag.has_cycle()
    
    def topological_sort(self) -> List[str]:
        return self._dag.topological_sort()
    
    def export_graph(self) -> Dict[str, Any]:
        return self._dag.export_graph()
    
    def _access_provider_for_merge(self) -> DAGProvider:
        return self._dag
    
    def _access_strategy_registry(self) -> StrategyRegistry:
        return self._strategy_registry
