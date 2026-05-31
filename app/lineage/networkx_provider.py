"""
NetworkX implementation of DAGProvider.
"""

from typing import Any, Dict, List, Optional, Tuple

import networkx as nx

from app.lineage.base import DAGProvider
from app.lineage.models import EdgeType


class NetworkXProvider(DAGProvider):
    def __init__(self):
        self.graph = nx.DiGraph()
        self._node_metadata: Dict[str, Dict[str, Any]] = {}
        self._edge_metadata: Dict[Tuple[str, str], Dict[str, Any]] = {}
    
    def add_node(
        self,
        node_id: str,
        node_type: str,
        name: str,
        attributes: Optional[Dict[str, Any]] = None
    ):
        self.graph.add_node(node_id)
        self._node_metadata[node_id] = {
            "type": node_type,
            "name": name,
            **(attributes or {})
        }
    
    def add_edge(
        self,
        source: str,
        target: str,
        edge_type: EdgeType = EdgeType.DEPENDS_ON,
        attributes: Optional[Dict[str, Any]] = None
    ):
        self.graph.add_edge(source, target)
        self._edge_metadata[(source, target)] = {
            "type": edge_type.value,
            **(attributes or {})
        }
    
    def get_upstream(self, node_id: str) -> List[str]:
        return list(nx.ancestors(self.graph, node_id))
    
    def get_downstream(self, node_id: str) -> List[str]:
        return list(nx.descendants(self.graph, node_id))
    
    def has_cycle(self) -> bool:
        return not nx.is_directed_acyclic_graph(self.graph)
    
    def topological_sort(self) -> List[str]:
        return list(nx.topological_sort(self.graph))
    
    def export_graph(self) -> Dict[str, Any]:
        return {
            "nodes": [
                {
                    "id": node_id,
                    **self._node_metadata.get(node_id, {})
                }
                for node_id in self.graph.nodes()
            ],
            "edges": [
                {
                    "source": edge[0],
                    "target": edge[1],
                    **self._edge_metadata.get(edge, {})
                }
                for edge in self.graph.edges()
            ]
        }
    
    def merge_from(self, other: "DAGProvider"):
        if isinstance(other, NetworkXProvider):
            for node_id, metadata in other._node_metadata.items():
                self.add_node(
                    node_id,
                    metadata["type"],
                    metadata["name"],
                    {k: v for k, v in metadata.items() if k not in ["type", "name"]}
                )
            for (source, target), metadata in other._edge_metadata.items():
                self.add_edge(
                    source,
                    target,
                    EdgeType(metadata["type"]),
                    {k: v for k, v in metadata.items() if k != "type"}
                )
        else:
            data = other.export_graph()
            for node in data["nodes"]:
                node_id = node["id"]
                node_type = node.get("type", "unknown")
                node_name = node.get("name", node_id)
                attrs = {k: v for k, v in node.items() if k not in ["id", "type", "name"]}
                self.add_node(node_id, node_type, node_name, attrs)
            for edge in data["edges"]:
                source = edge["source"]
                target = edge["target"]
                edge_type = EdgeType(edge.get("type", EdgeType.DEPENDS_ON))
                attrs = {k: v for k, v in edge.items() if k not in ["source", "target", "type"]}
                self.add_edge(source, target, edge_type, attrs)
