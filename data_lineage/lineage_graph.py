from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Iterator, List, Optional, Set, Tuple, Union

import networkx as nx


class NodeType(str, Enum):
    TABLE = "table"
    COLUMN = "column"
    CTE = "cte"
    SUBQUERY = "subquery"
    VIEW = "view"


class EdgeType(str, Enum):
    SELECT_FROM = "select_from"
    INSERT_INTO = "insert_into"
    CREATE_AS = "create_as"
    JOIN_ON = "join_on"
    WHERE_FILTER = "where_filter"
    WINDOW_PARTITION = "window_partition"
    WINDOW_ORDER = "window_order"
    AGGREGATE = "aggregate"
    COMPUTED = "computed"
    TRANSFORM = "transform"


@dataclass
class LineageNode:
    id: str
    name: str
    node_type: NodeType
    schema: Optional[str] = None
    database: Optional[str] = None
    expression: Optional[str] = None
    alias: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def full_name(self) -> str:
        parts = []
        if self.database:
            parts.append(self.database)
        if self.schema:
            parts.append(self.schema)
        parts.append(self.name)
        return ".".join(parts)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "node_type": self.node_type.value,
            "schema": self.schema,
            "database": self.database,
            "expression": self.expression,
            "alias": self.alias,
            "metadata": self.metadata,
            "full_name": self.full_name,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "LineageNode":
        return cls(
            id=data["id"],
            name=data["name"],
            node_type=NodeType(data["node_type"]),
            schema=data.get("schema"),
            database=data.get("database"),
            expression=data.get("expression"),
            alias=data.get("alias"),
            metadata=data.get("metadata", {}),
        )


@dataclass
class LineageEdge:
    source_id: str
    target_id: str
    edge_type: EdgeType
    expression: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def key(self) -> Tuple[str, str, EdgeType]:
        return (self.source_id, self.target_id, self.edge_type)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "source_id": self.source_id,
            "target_id": self.target_id,
            "edge_type": self.edge_type.value,
            "expression": self.expression,
            "metadata": self.metadata,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "LineageEdge":
        return cls(
            source_id=data["source_id"],
            target_id=data["target_id"],
            edge_type=EdgeType(data["edge_type"]),
            expression=data.get("expression"),
            metadata=data.get("metadata", {}),
        )


class LineageGraph:
    def __init__(self):
        self._graph: nx.DiGraph = nx.DiGraph()
        self._nodes: Dict[str, LineageNode] = {}
        self._edges: Dict[Tuple[str, str, EdgeType], LineageEdge] = {}

    def add_node(self, node: LineageNode) -> None:
        if node.id not in self._nodes:
            self._nodes[node.id] = node
            self._graph.add_node(node.id, **node.to_dict())

    def add_edge(self, edge: LineageEdge) -> None:
        if edge.key not in self._edges:
            self._edges[edge.key] = edge
            self._graph.add_edge(
                edge.source_id,
                edge.target_id,
                **edge.to_dict()
            )

    def get_node(self, node_id: str) -> Optional[LineageNode]:
        return self._nodes.get(node_id)

    def get_edge(self, source_id: str, target_id: str, edge_type: EdgeType) -> Optional[LineageEdge]:
        return self._edges.get((source_id, target_id, edge_type))

    def has_node(self, node_id: str) -> bool:
        return node_id in self._nodes

    def has_edge(self, source_id: str, target_id: str) -> bool:
        return self._graph.has_edge(source_id, target_id)

    def remove_node(self, node_id: str) -> None:
        if node_id in self._nodes:
            del self._nodes[node_id]
            edges_to_remove = [
                k for k in self._edges.keys()
                if k[0] == node_id or k[1] == node_id
            ]
            for k in edges_to_remove:
                del self._edges[k]
            self._graph.remove_node(node_id)

    def remove_edge(self, source_id: str, target_id: str, edge_type: EdgeType) -> None:
        key = (source_id, target_id, edge_type)
        if key in self._edges:
            del self._edges[key]
            self._graph.remove_edge(source_id, target_id)

    @property
    def nodes(self) -> List[LineageNode]:
        return list(self._nodes.values())

    @property
    def edges(self) -> List[LineageEdge]:
        return list(self._edges.values())

    @property
    def graph(self) -> nx.DiGraph:
        return self._graph

    def get_nodes_by_type(self, node_type: NodeType) -> List[LineageNode]:
        return [n for n in self._nodes.values() if n.node_type == node_type]

    def get_edges_by_type(self, edge_type: EdgeType) -> List[LineageEdge]:
        return [e for e in self._edges.values() if e.edge_type == edge_type]

    def get_predecessors(self, node_id: str) -> List[LineageNode]:
        preds = self._graph.predecessors(node_id)
        return [self._nodes[pid] for pid in preds if pid in self._nodes]

    def get_successors(self, node_id: str) -> List[LineageNode]:
        succs = self._graph.successors(node_id)
        return [self._nodes[sid] for sid in succs if sid in self._nodes]

    def get_in_edges(self, node_id: str) -> List[LineageEdge]:
        edges = []
        for src, _ in self._graph.in_edges(node_id):
            for edge_type in EdgeType:
                edge = self.get_edge(src, node_id, edge_type)
                if edge:
                    edges.append(edge)
        return edges

    def get_out_edges(self, node_id: str) -> List[LineageEdge]:
        edges = []
        for _, tgt in self._graph.out_edges(node_id):
            for edge_type in EdgeType:
                edge = self.get_edge(node_id, tgt, edge_type)
                if edge:
                    edges.append(edge)
        return edges

    def get_ancestors(self, node_id: str) -> Set[str]:
        return nx.ancestors(self._graph, node_id)

    def get_descendants(self, node_id: str) -> Set[str]:
        return nx.descendants(self._graph, node_id)

    def find_paths(self, source_id: str, target_id: str) -> List[List[str]]:
        return list(nx.all_simple_paths(self._graph, source_id, target_id))

    def find_shortest_path(self, source_id: str, target_id: str) -> List[str]:
        try:
            return nx.shortest_path(self._graph, source_id, target_id)
        except nx.NetworkXNoPath:
            return []

    def is_dag(self) -> bool:
        return nx.is_directed_acyclic_graph(self._graph)

    def topological_sort(self) -> List[str]:
        if self.is_dag():
            return list(nx.topological_sort(self._graph))
        return []

    def get_leaf_nodes(self) -> List[LineageNode]:
        leaf_ids = [n for n in self._graph.nodes() if self._graph.out_degree(n) == 0]
        return [self._nodes[lid] for lid in leaf_ids if lid in self._nodes]

    def get_root_nodes(self) -> List[LineageNode]:
        root_ids = [n for n in self._graph.nodes() if self._graph.in_degree(n) == 0]
        return [self._nodes[rid] for rid in root_ids if rid in self._nodes]

    def get_subgraph(self, node_ids: List[str]) -> "LineageGraph":
        subgraph = LineageGraph()
        for nid in node_ids:
            node = self._nodes.get(nid)
            if node:
                subgraph.add_node(node)
        for edge in self._edges.values():
            if edge.source_id in node_ids and edge.target_id in node_ids:
                subgraph.add_edge(edge)
        return subgraph

    def get_lineage_subgraph(self, node_id: str, upstream: bool = True, downstream: bool = True) -> "LineageGraph":
        node_ids = {node_id}
        if upstream:
            node_ids.update(self.get_ancestors(node_id))
        if downstream:
            node_ids.update(self.get_descendants(node_id))
        return self.get_subgraph(list(node_ids))

    def merge(self, other: "LineageGraph") -> None:
        for node in other.nodes:
            self.add_node(node)
        for edge in other.edges:
            self.add_edge(edge)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "nodes": [n.to_dict() for n in self._nodes.values()],
            "edges": [e.to_dict() for e in self._edges.values()],
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "LineageGraph":
        graph = cls()
        for node_data in data.get("nodes", []):
            graph.add_node(LineageNode.from_dict(node_data))
        for edge_data in data.get("edges", []):
            graph.add_edge(LineageEdge.from_dict(edge_data))
        return graph

    def to_graphml(self, path: str) -> None:
        nx.write_graphml(self._graph, path)

    @classmethod
    def from_graphml(cls, path: str) -> "LineageGraph":
        graph = cls()
        nx_graph = nx.read_graphml(path)
        for node_id, attrs in nx_graph.nodes(data=True):
            node = LineageNode(
                id=node_id,
                name=attrs.get("name", node_id),
                node_type=NodeType(attrs.get("node_type", NodeType.TABLE.value)),
                schema=attrs.get("schema"),
                database=attrs.get("database"),
                expression=attrs.get("expression"),
                alias=attrs.get("alias"),
                metadata={k: v for k, v in attrs.items() if k not in ["id", "name", "node_type", "schema", "database", "expression", "alias"]},
            )
            graph.add_node(node)
        for source, target, attrs in nx_graph.edges(data=True):
            edge = LineageEdge(
                source_id=source,
                target_id=target,
                edge_type=EdgeType(attrs.get("edge_type", EdgeType.SELECT_FROM.value)),
                expression=attrs.get("expression"),
                metadata={k: v for k, v in attrs.items() if k not in ["source_id", "target_id", "edge_type", "expression"]},
            )
            graph.add_edge(edge)
        return graph

    def clear(self) -> None:
        self._graph.clear()
        self._nodes.clear()
        self._edges.clear()

    def __len__(self) -> int:
        return len(self._nodes)

    def __iter__(self) -> Iterator[LineageNode]:
        return iter(self._nodes.values())

    def __contains__(self, node_id: str) -> bool:
        return node_id in self._nodes

    def __repr__(self) -> str:
        return f"LineageGraph(nodes={len(self._nodes)}, edges={len(self._edges)})"
