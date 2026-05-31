from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional


class NodeType(Enum):
    DATABASE = "DATABASE"
    TABLE = "TABLE"
    COLUMN = "COLUMN"
    VIEW = "VIEW"
    STREAM = "STREAM"


class EdgeType(Enum):
    DERIVES_FROM = "DERIVES_FROM"
    TRANSFORMS = "TRANSFORMS"
    COPIES = "COPIES"
    JOINS = "JOINS"
    AGGREGATES = "AGGREGATES"
    FILTERS = "FILTERS"


@dataclass
class LineageNode:
    node_id: str
    node_type: NodeType
    name: str
    database: Optional[str] = None
    schema_name: Optional[str] = None
    properties: Dict[str, Any] = field(default_factory=dict)

    @property
    def qualified_name(self) -> str:
        parts = []
        if self.database:
            parts.append(self.database)
        if self.schema_name:
            parts.append(self.schema_name)
        parts.append(self.name)
        return ".".join(parts)


@dataclass
class ColumnLineage:
    source_db: str
    source_table: str
    source_column: str
    target_db: str
    target_table: str
    target_column: str
    transformation: Optional[str] = None
    transformation_type: EdgeType = EdgeType.DERIVES_FROM

    @property
    def source_qualified(self) -> str:
        return f"{self.source_db}.{self.source_table}.{self.source_column}"

    @property
    def target_qualified(self) -> str:
        return f"{self.target_db}.{self.target_table}.{self.target_column}"

    def to_dict(self) -> Dict[str, Any]:
        return {
            "source_db": self.source_db,
            "source_table": self.source_table,
            "source_column": self.source_column,
            "target_db": self.target_db,
            "target_table": self.target_table,
            "target_column": self.target_column,
            "transformation": self.transformation,
            "transformation_type": self.transformation_type.value,
        }


@dataclass
class LineageEdge:
    source_id: str
    target_id: str
    edge_type: EdgeType = EdgeType.DERIVES_FROM
    transformation: Optional[str] = None
    sql_text: Optional[str] = None
    column_lineages: List[ColumnLineage] = field(default_factory=list)
    properties: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "source_id": self.source_id,
            "target_id": self.target_id,
            "edge_type": self.edge_type.value,
            "transformation": self.transformation,
            "sql_text": self.sql_text,
            "column_lineages": [cl.to_dict() for cl in self.column_lineages],
        }


@dataclass
class LineageGraph:
    nodes: Dict[str, LineageNode] = field(default_factory=dict)
    edges: List[LineageEdge] = field(default_factory=list)

    def add_node(self, node: LineageNode) -> None:
        self.nodes[node.node_id] = node

    def add_edge(self, edge: LineageEdge) -> None:
        self.edges.append(edge)

    def get_node(self, node_id: str) -> Optional[LineageNode]:
        return self.nodes.get(node_id)

    def get_upstream_nodes(self, node_id: str) -> List[LineageNode]:
        upstream = []
        for edge in self.edges:
            if edge.target_id == node_id:
                source = self.nodes.get(edge.source_id)
                if source:
                    upstream.append(source)
        return upstream

    def get_downstream_nodes(self, node_id: str) -> List[LineageNode]:
        downstream = []
        for edge in self.edges:
            if edge.source_id == node_id:
                target = self.nodes.get(edge.target_id)
                if target:
                    downstream.append(target)
        return downstream

    def get_upstream_edges(self, node_id: str) -> List[LineageEdge]:
        return [e for e in self.edges if e.target_id == node_id]

    def get_downstream_edges(self, node_id: str) -> List[LineageEdge]:
        return [e for e in self.edges if e.source_id == node_id]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "nodes": {nid: {"id": nid, "type": n.node_type.value, "name": n.name, "qualified_name": n.qualified_name}
                      for nid, n in self.nodes.items()},
            "edges": [e.to_dict() for e in self.edges],
        }
