"""
Pluggable strategies for lineage module.
"""

from abc import ABC, abstractmethod
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

from app.lineage.base import DAGProvider, SQLParser
from app.lineage.models import (
    ColumnLineage,
    ColumnReference,
    EdgeType,
    TableLineage
)


class LineageStrategyType(str, Enum):
    STANDARD = "standard"
    STRICT = "strict"
    PERMISSIVE = "permissive"
    CTE_AWARE = "cte_aware"
    COLUMN_ONLY = "column_only"
    TABLE_ONLY = "table_only"


class ParseStrategy(ABC):
    @property
    @abstractmethod
    def name(self) -> str:
        pass
    
    @property
    @abstractmethod
    def strategy_type(self) -> LineageStrategyType:
        pass
    
    @abstractmethod
    def apply(
        self,
        parser: SQLParser,
        sql: str
    ) -> Tuple[TableLineage, List[ColumnLineage]]:
        pass
    
    @abstractmethod
    def supports_column_lineage(self) -> bool:
        pass
    
    @abstractmethod
    def supports_table_lineage(self) -> bool:
        pass


class GraphBuildStrategy(ABC):
    @property
    @abstractmethod
    def name(self) -> str:
        pass
    
    @property
    @abstractmethod
    def strategy_type(self) -> LineageStrategyType:
        pass
    
    @abstractmethod
    def build(
        self,
        dag: DAGProvider,
        table_lineage: TableLineage,
        column_lineages: List[ColumnLineage],
        **kwargs
    ):
        pass
    
    @abstractmethod
    def get_default_edge_type(self) -> EdgeType:
        pass


class StandardParseStrategy(ParseStrategy):
    def __init__(self):
        self._name = "standard"
        self._type = LineageStrategyType.STANDARD
    
    @property
    def name(self) -> str:
        return self._name
    
    @property
    def strategy_type(self) -> LineageStrategyType:
        return self._type
    
    def apply(
        self,
        parser: SQLParser,
        sql: str
    ) -> Tuple[TableLineage, List[ColumnLineage]]:
        return parser.parse(sql)
    
    def supports_column_lineage(self) -> bool:
        return True
    
    def supports_table_lineage(self) -> bool:
        return True


class StrictParseStrategy(ParseStrategy):
    def __init__(self):
        self._name = "strict"
        self._type = LineageStrategyType.STRICT
    
    @property
    def name(self) -> str:
        return self._name
    
    @property
    def strategy_type(self) -> LineageStrategyType:
        return self._type
    
    def apply(
        self,
        parser: SQLParser,
        sql: str
    ) -> Tuple[TableLineage, List[ColumnLineage]]:
        table_lineage, column_lineages = parser.parse(sql)
        
        filtered_columns = [
            cl for cl in column_lineages
            if cl.target.table is not None and len(cl.sources) > 0
        ]
        
        return table_lineage, filtered_columns
    
    def supports_column_lineage(self) -> bool:
        return True
    
    def supports_table_lineage(self) -> bool:
        return True


class PermissiveParseStrategy(ParseStrategy):
    def __init__(self):
        self._name = "permissive"
        self._type = LineageStrategyType.PERMISSIVE
    
    @property
    def name(self) -> str:
        return self._name
    
    @property
    def strategy_type(self) -> LineageStrategyType:
        return self._type
    
    def apply(
        self,
        parser: SQLParser,
        sql: str
    ) -> Tuple[TableLineage, List[ColumnLineage]]:
        try:
            return parser.parse(sql)
        except Exception:
            return TableLineage(), []
    
    def supports_column_lineage(self) -> bool:
        return True
    
    def supports_table_lineage(self) -> bool:
        return True


class TableOnlyParseStrategy(ParseStrategy):
    def __init__(self):
        self._name = "table_only"
        self._type = LineageStrategyType.TABLE_ONLY
    
    @property
    def name(self) -> str:
        return self._name
    
    @property
    def strategy_type(self) -> LineageStrategyType:
        return self._type
    
    def apply(
        self,
        parser: SQLParser,
        sql: str
    ) -> Tuple[TableLineage, List[ColumnLineage]]:
        table_lineage, _ = parser.parse(sql)
        return table_lineage, []
    
    def supports_column_lineage(self) -> bool:
        return False
    
    def supports_table_lineage(self) -> bool:
        return True


class ColumnOnlyParseStrategy(ParseStrategy):
    def __init__(self):
        self._name = "column_only"
        self._type = LineageStrategyType.COLUMN_ONLY
    
    @property
    def name(self) -> str:
        return self._name
    
    @property
    def strategy_type(self) -> LineageStrategyType:
        return self._type
    
    def apply(
        self,
        parser: SQLParser,
        sql: str
    ) -> Tuple[TableLineage, List[ColumnLineage]]:
        _, column_lineages = parser.parse(sql)
        return TableLineage(), column_lineages
    
    def supports_column_lineage(self) -> bool:
        return True
    
    def supports_table_lineage(self) -> bool:
        return False


class StandardGraphStrategy(GraphBuildStrategy):
    def __init__(self):
        self._name = "standard"
        self._type = LineageStrategyType.STANDARD
    
    @property
    def name(self) -> str:
        return self._name
    
    @property
    def strategy_type(self) -> LineageStrategyType:
        return self._type
    
    def get_default_edge_type(self) -> EdgeType:
        return EdgeType.DEPENDS_ON
    
    def build(
        self,
        dag: DAGProvider,
        table_lineage: TableLineage,
        column_lineages: List[ColumnLineage],
        **kwargs
    ):
        for table in table_lineage.source_tables:
            node_id = f"table:{table}"
            dag.add_node(node_id, "table", table)
        
        for table in table_lineage.target_tables:
            node_id = f"table:{table}"
            dag.add_node(node_id, "table", table)
        
        for cte_name, _ in table_lineage.cte_tables.items():
            node_id = f"cte:{cte_name}"
            dag.add_node(node_id, "cte", cte_name)
        
        for source in table_lineage.source_tables:
            for target in table_lineage.target_tables:
                dag.add_edge(
                    f"table:{source}",
                    f"table:{target}",
                    EdgeType.DEPENDS_ON
                )
        
        for col_lineage in column_lineages:
            target_table = col_lineage.target.table or "unknown"
            target_id = f"column:{target_table}.{col_lineage.target.column}"
            dag.add_node(target_id, "column", col_lineage.target.column)
            
            for source in col_lineage.sources:
                source_table = source.table or "unknown"
                source_id = f"column:{source_table}.{source.column}"
                dag.add_node(source_id, "column", source.column)
                dag.add_edge(
                    source_id,
                    target_id,
                    EdgeType.TRANSFORMS,
                    {"transformation": col_lineage.transformation}
                )


class CTEAwareGraphStrategy(GraphBuildStrategy):
    def __init__(self):
        self._name = "cte_aware"
        self._type = LineageStrategyType.CTE_AWARE
    
    @property
    def name(self) -> str:
        return self._name
    
    @property
    def strategy_type(self) -> LineageStrategyType:
        return self._type
    
    def get_default_edge_type(self) -> EdgeType:
        return EdgeType.DEPENDS_ON
    
    def build(
        self,
        dag: DAGProvider,
        table_lineage: TableLineage,
        column_lineages: List[ColumnLineage],
        **kwargs
    ):
        for cte_name, cte_sql in table_lineage.cte_tables.items():
            node_id = f"cte:{cte_name}"
            dag.add_node(node_id, "cte", cte_name, {"sql": cte_sql})
        
        for table in table_lineage.source_tables:
            node_id = f"table:{table}"
            dag.add_node(node_id, "table", table)
            
            for cte_name in table_lineage.cte_tables:
                dag.add_edge(
                    node_id,
                    f"cte:{cte_name}",
                    EdgeType.FILTERS,
                    {"role": "source_to_cte"}
                )
        
        for table in table_lineage.target_tables:
            node_id = f"table:{table}"
            dag.add_node(node_id, "table", table)
            
            for cte_name in table_lineage.cte_tables:
                dag.add_edge(
                    f"cte:{cte_name}",
                    node_id,
                    EdgeType.TRANSFORMS,
                    {"role": "cte_to_target"}
                )
        
        for col_lineage in column_lineages:
            target_table = col_lineage.target.table or "unknown"
            target_id = f"column:{target_table}.{col_lineage.target.column}"
            dag.add_node(target_id, "column", col_lineage.target.column)
            
            for source in col_lineage.sources:
                source_table = source.table or "unknown"
                source_id = f"column:{source_table}.{source.column}"
                dag.add_node(source_id, "column", source.column)
                dag.add_edge(
                    source_id,
                    target_id,
                    EdgeType.TRANSFORMS,
                    {"transformation": col_lineage.transformation}
                )


class StrategyRegistry:
    def __init__(self):
        self._parse_strategies: Dict[str, ParseStrategy] = {}
        self._graph_strategies: Dict[str, GraphBuildStrategy] = {}
        self._active_parse: str = "standard"
        self._active_graph: str = "standard"
        
        self._register_defaults()
    
    def _register_defaults(self):
        self.register_parse_strategy(StandardParseStrategy())
        self.register_parse_strategy(StrictParseStrategy())
        self.register_parse_strategy(PermissiveParseStrategy())
        self.register_parse_strategy(TableOnlyParseStrategy())
        self.register_parse_strategy(ColumnOnlyParseStrategy())
        
        self.register_graph_strategy(StandardGraphStrategy())
        self.register_graph_strategy(CTEAwareGraphStrategy())
    
    def register_parse_strategy(self, strategy: ParseStrategy):
        self._parse_strategies[strategy.name] = strategy
    
    def register_graph_strategy(self, strategy: GraphBuildStrategy):
        self._graph_strategies[strategy.name] = strategy
    
    def get_parse_strategy(self, name: Optional[str] = None) -> ParseStrategy:
        strategy_name = name or self._active_parse
        return self._parse_strategies[strategy_name]
    
    def get_graph_strategy(self, name: Optional[str] = None) -> GraphBuildStrategy:
        strategy_name = name or self._active_graph
        return self._graph_strategies[strategy_name]
    
    def set_active_parse_strategy(self, name: str) -> bool:
        if name in self._parse_strategies:
            self._active_parse = name
            return True
        return False
    
    def set_active_graph_strategy(self, name: str) -> bool:
        if name in self._graph_strategies:
            self._active_graph = name
            return True
        return False
    
    def list_parse_strategies(self) -> List[str]:
        return list(self._parse_strategies.keys())
    
    def list_graph_strategies(self) -> List[str]:
        return list(self._graph_strategies.keys())
    
    def get_active_parse_strategy_name(self) -> str:
        return self._active_parse
    
    def get_active_graph_strategy_name(self) -> str:
        return self._active_graph
