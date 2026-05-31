from __future__ import annotations

import json
import os
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Dict, Iterator, List, Optional, Set, Tuple, Union

from .lineage_graph import LineageGraph, LineageNode, LineageEdge, NodeType, EdgeType


class LineageStore(ABC):
    @abstractmethod
    def save_graph(self, graph: LineageGraph, name: str, metadata: Optional[Dict[str, Any]] = None) -> str:
        pass

    @abstractmethod
    def load_graph(self, name: str) -> Optional[LineageGraph]:
        pass

    @abstractmethod
    def delete_graph(self, name: str) -> bool:
        pass

    @abstractmethod
    def list_graphs(self) -> List[Dict[str, Any]]:
        pass

    @abstractmethod
    def merge_graphs(self, target_name: str, source_names: List[str]) -> LineageGraph:
        pass

    @abstractmethod
    def get_node(self, graph_name: str, node_id: str) -> Optional[LineageNode]:
        pass

    @abstractmethod
    def find_nodes(self, graph_name: str, query: Dict[str, Any]) -> List[LineageNode]:
        pass

    @abstractmethod
    def find_edges(self, graph_name: str, query: Dict[str, Any]) -> List[LineageEdge]:
        pass

    @abstractmethod
    def add_node(self, graph_name: str, node: LineageNode) -> None:
        pass

    @abstractmethod
    def add_edge(self, graph_name: str, edge: LineageEdge) -> None:
        pass

    @abstractmethod
    def export_to_json(self, graph_name: str, path: str) -> None:
        pass

    @abstractmethod
    def import_from_json(self, path: str, name: Optional[str] = None) -> LineageGraph:
        pass

    @abstractmethod
    def export_to_graphml(self, graph_name: str, path: str) -> None:
        pass

    @abstractmethod
    def clear(self) -> None:
        pass


@dataclass
class StoredGraph:
    name: str
    graph: LineageGraph
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: str = field(default_factory=lambda: __import__("datetime").datetime.now().isoformat())
    updated_at: str = field(default_factory=lambda: __import__("datetime").datetime.now().isoformat())

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "graph": self.graph.to_dict(),
            "metadata": self.metadata,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "StoredGraph":
        return cls(
            name=data["name"],
            graph=LineageGraph.from_dict(data["graph"]),
            metadata=data.get("metadata", {}),
            created_at=data.get("created_at", __import__("datetime").datetime.now().isoformat()),
            updated_at=data.get("updated_at", __import__("datetime").datetime.now().isoformat()),
        )


class MemoryLineageStore(LineageStore):
    def __init__(self):
        self._graphs: Dict[str, StoredGraph] = {}

    def save_graph(self, graph: LineageGraph, name: str, metadata: Optional[Dict[str, Any]] = None) -> str:
        if name in self._graphs:
            stored = self._graphs[name]
            stored.graph = graph
            stored.metadata = metadata or {}
            stored.updated_at = __import__("datetime").datetime.now().isoformat()
        else:
            self._graphs[name] = StoredGraph(
                name=name,
                graph=graph,
                metadata=metadata or {},
            )
        return name

    def load_graph(self, name: str) -> Optional[LineageGraph]:
        stored = self._graphs.get(name)
        return stored.graph if stored else None

    def delete_graph(self, name: str) -> bool:
        if name in self._graphs:
            del self._graphs[name]
            return True
        return False

    def list_graphs(self) -> List[Dict[str, Any]]:
        result = []
        for name, stored in self._graphs.items():
            result.append({
                "name": name,
                "node_count": len(stored.graph.nodes),
                "edge_count": len(stored.graph.edges),
                "metadata": stored.metadata,
                "created_at": stored.created_at,
                "updated_at": stored.updated_at,
            })
        return result

    def merge_graphs(self, target_name: str, source_names: List[str]) -> LineageGraph:
        merged = LineageGraph()
        for source_name in source_names:
            source_graph = self.load_graph(source_name)
            if source_graph:
                merged.merge(source_graph)
        
        if target_name:
            self.save_graph(merged, target_name)
        
        return merged

    def get_node(self, graph_name: str, node_id: str) -> Optional[LineageNode]:
        graph = self.load_graph(graph_name)
        return graph.get_node(node_id) if graph else None

    def find_nodes(self, graph_name: str, query: Dict[str, Any]) -> List[LineageNode]:
        graph = self.load_graph(graph_name)
        if not graph:
            return []

        results = []
        for node in graph.nodes:
            match = True
            for key, value in query.items():
                if key == "node_type":
                    if node.node_type != value and node.node_type.value != value:
                        match = False
                        break
                elif hasattr(node, key):
                    if getattr(node, key) != value:
                        match = False
                        break
                elif key in node.metadata:
                    if node.metadata[key] != value:
                        match = False
                        break
                else:
                    match = False
                    break
            if match:
                results.append(node)
        return results

    def find_edges(self, graph_name: str, query: Dict[str, Any]) -> List[LineageEdge]:
        graph = self.load_graph(graph_name)
        if not graph:
            return []

        results = []
        for edge in graph.edges:
            match = True
            for key, value in query.items():
                if key == "edge_type":
                    if edge.edge_type != value and edge.edge_type.value != value:
                        match = False
                        break
                elif hasattr(edge, key):
                    if getattr(edge, key) != value:
                        match = False
                        break
                elif key in edge.metadata:
                    if edge.metadata[key] != value:
                        match = False
                        break
                else:
                    match = False
                    break
            if match:
                results.append(edge)
        return results

    def add_node(self, graph_name: str, node: LineageNode) -> None:
        graph = self.load_graph(graph_name)
        if graph:
            graph.add_node(node)
            self._graphs[graph_name].updated_at = __import__("datetime").datetime.now().isoformat()

    def add_edge(self, graph_name: str, edge: LineageEdge) -> None:
        graph = self.load_graph(graph_name)
        if graph:
            graph.add_edge(edge)
            self._graphs[graph_name].updated_at = __import__("datetime").datetime.now().isoformat()

    def export_to_json(self, graph_name: str, path: str) -> None:
        stored = self._graphs.get(graph_name)
        if not stored:
            raise ValueError(f"Graph not found: {graph_name}")

        data = stored.to_dict()
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    def import_from_json(self, path: str, name: Optional[str] = None) -> LineageGraph:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)

        stored = StoredGraph.from_dict(data)
        if name:
            stored.name = name
        
        self._graphs[stored.name] = stored
        return stored.graph

    def export_to_graphml(self, graph_name: str, path: str) -> None:
        graph = self.load_graph(graph_name)
        if not graph:
            raise ValueError(f"Graph not found: {graph_name}")
        graph.to_graphml(path)

    def import_from_graphml(self, path: str, name: str) -> LineageGraph:
        graph = LineageGraph.from_graphml(path)
        self.save_graph(graph, name)
        return graph

    def search_nodes(self, graph_name: str, keyword: str) -> List[LineageNode]:
        graph = self.load_graph(graph_name)
        if not graph:
            return []

        keyword = keyword.lower()
        results = []
        for node in graph.nodes:
            if (keyword in node.name.lower() or
                keyword in node.full_name.lower() or
                (node.expression and keyword in node.expression.lower()) or
                (node.alias and keyword in node.alias.lower()) or
                any(keyword in str(v).lower() for v in node.metadata.values())):
                results.append(node)
        return results

    def get_graph_summary(self, graph_name: str) -> Optional[Dict[str, Any]]:
        from .analyzer import LineageAnalyzer
        graph = self.load_graph(graph_name)
        if not graph:
            return None

        analyzer = LineageAnalyzer(graph)
        summary = analyzer.get_summary()
        stored = self._graphs[graph_name]
        
        return {
            **summary.to_dict(),
            "name": graph_name,
            "metadata": stored.metadata,
            "created_at": stored.created_at,
            "updated_at": stored.updated_at,
        }

    def batch_save(self, graphs: Dict[str, LineageGraph], metadata: Optional[Dict[str, Any]] = None) -> List[str]:
        saved_names = []
        for name, graph in graphs.items():
            self.save_graph(graph, name, metadata)
            saved_names.append(name)
        return saved_names

    def batch_load(self, names: List[str]) -> Dict[str, Optional[LineageGraph]]:
        return {name: self.load_graph(name) for name in names}

    def clone_graph(self, source_name: str, target_name: str) -> Optional[LineageGraph]:
        source_graph = self.load_graph(source_name)
        if not source_graph:
            return None

        import copy
        cloned_data = copy.deepcopy(source_graph.to_dict())
        cloned_graph = LineageGraph.from_dict(cloned_data)
        self.save_graph(cloned_graph, target_name)
        return cloned_graph

    def get_lineage_between(self, graph_name: str, source_node_id: str, target_node_id: str) -> Dict[str, Any]:
        graph = self.load_graph(graph_name)
        if not graph:
            return {}

        paths = graph.find_paths(source_node_id, target_node_id)
        shortest_path = graph.find_shortest_path(source_node_id, target_node_id)

        nodes_in_paths = set()
        for path in paths:
            nodes_in_paths.update(path)

        subgraph = graph.get_subgraph(list(nodes_in_paths))

        return {
            "paths": paths,
            "shortest_path": shortest_path,
            "path_count": len(paths),
            "subgraph": subgraph.to_dict(),
        }

    def get_upstream_lineage(self, graph_name: str, node_id: str, max_depth: Optional[int] = None) -> Dict[str, Any]:
        graph = self.load_graph(graph_name)
        if not graph or not graph.has_node(node_id):
            return {}

        ancestors = graph.get_ancestors(node_id)
        all_nodes = ancestors | {node_id}

        if max_depth is not None:
            filtered = set()
            for anc_id in ancestors:
                path = graph.find_shortest_path(anc_id, node_id)
                if path and len(path) - 1 <= max_depth:
                    filtered.add(anc_id)
            all_nodes = filtered | {node_id}

        subgraph = graph.get_subgraph(list(all_nodes))

        return {
            "target_node": graph.get_node(node_id).to_dict(),
            "upstream_nodes": [graph.get_node(nid).to_dict() for nid in ancestors if graph.get_node(nid)],
            "subgraph": subgraph.to_dict(),
            "depth": len(graph.find_shortest_path(min(ancestors, key=lambda x: len(graph.find_shortest_path(x, node_id))), node_id)) - 1 if ancestors else 0,
        }

    def get_downstream_lineage(self, graph_name: str, node_id: str, max_depth: Optional[int] = None) -> Dict[str, Any]:
        graph = self.load_graph(graph_name)
        if not graph or not graph.has_node(node_id):
            return {}

        descendants = graph.get_descendants(node_id)
        all_nodes = descendants | {node_id}

        if max_depth is not None:
            filtered = set()
            for desc_id in descendants:
                path = graph.find_shortest_path(node_id, desc_id)
                if path and len(path) - 1 <= max_depth:
                    filtered.add(desc_id)
            all_nodes = filtered | {node_id}

        subgraph = graph.get_subgraph(list(all_nodes))

        return {
            "target_node": graph.get_node(node_id).to_dict(),
            "downstream_nodes": [graph.get_node(nid).to_dict() for nid in descendants if graph.get_node(nid)],
            "subgraph": subgraph.to_dict(),
            "depth": len(graph.find_shortest_path(node_id, max(descendants, key=lambda x: len(graph.find_shortest_path(node_id, x))))) - 1 if descendants else 0,
        }

    def clear(self) -> None:
        self._graphs.clear()

    def __len__(self) -> int:
        return len(self._graphs)

    def __contains__(self, name: str) -> bool:
        return name in self._graphs

    def __iter__(self) -> Iterator[Tuple[str, LineageGraph]]:
        for name, stored in self._graphs.items():
            yield name, stored.graph


class FileLineageStore(MemoryLineageStore):
    def __init__(self, storage_dir: str):
        super().__init__()
        self.storage_dir = storage_dir
        os.makedirs(storage_dir, exist_ok=True)
        self._load_all()

    def _load_all(self) -> None:
        for filename in os.listdir(self.storage_dir):
            if filename.endswith(".json"):
                path = os.path.join(self.storage_dir, filename)
                name = filename[:-5]
                try:
                    self.import_from_json(path, name)
                except Exception:
                    pass

    def save_graph(self, graph: LineageGraph, name: str, metadata: Optional[Dict[str, Any]] = None) -> str:
        name = super().save_graph(graph, name, metadata)
        path = os.path.join(self.storage_dir, f"{name}.json")
        self.export_to_json(name, path)
        return name

    def delete_graph(self, name: str) -> bool:
        deleted = super().delete_graph(name)
        if deleted:
            path = os.path.join(self.storage_dir, f"{name}.json")
            if os.path.exists(path):
                os.remove(path)
        return deleted

    def clear(self) -> None:
        super().clear()
        for filename in os.listdir(self.storage_dir):
            if filename.endswith(".json"):
                os.remove(os.path.join(self.storage_dir, filename))
