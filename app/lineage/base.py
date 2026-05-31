"""
Abstract base classes for lineage module.
"""

from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Tuple

from app.lineage.models import (
    ColumnLineage, EdgeType, TableLineage
)


class SQLParser(ABC):
    @property
    @abstractmethod
    def dialect(self) -> str:
        pass
    
    @abstractmethod
    def parse(self, sql: str) -> Tuple[TableLineage, List[ColumnLineage]]:
        pass


class DAGProvider(ABC):
    @abstractmethod
    def add_node(
        self,
        node_id: str,
        node_type: str,
        name: str,
        attributes: Optional[Dict[str, Any]] = None
    ):
        pass
    
    @abstractmethod
    def add_edge(
        self,
        source: str,
        target: str,
        edge_type: EdgeType = EdgeType.DEPENDS_ON,
        attributes: Optional[Dict[str, Any]] = None
    ):
        pass
    
    @abstractmethod
    def get_upstream(self, node_id: str) -> List[str]:
        pass
    
    @abstractmethod
    def get_downstream(self, node_id: str) -> List[str]:
        pass
    
    @abstractmethod
    def has_cycle(self) -> bool:
        pass
    
    @abstractmethod
    def topological_sort(self) -> List[str]:
        pass
    
    @abstractmethod
    def export_graph(self) -> Dict[str, Any]:
        pass
    
    @abstractmethod
    def merge_from(self, other: "DAGProvider"):
        pass
