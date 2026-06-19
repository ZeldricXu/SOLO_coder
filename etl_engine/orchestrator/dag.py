from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, model_validator

from etl_engine.exceptions import CyclicDependencyError


class DAGNode(BaseModel):
    id: str
    type: Literal["extract", "transform", "quality_check", "load"]
    config: dict = {}
    dependencies: list[str] = []
    retry_count: int = 0
    max_retries: int = 3
    retry_delay_seconds: int = 60
    on_failure: Literal["retry", "skip", "fail"] = "retry"
    data_interval: dict | None = None


class DAGEdge(BaseModel):
    source: str
    target: str
    data_mapping: dict = {}


class DAGDefinition(BaseModel):
    nodes: list[DAGNode]
    edges: list[DAGEdge]
    schedule: str | None = None
    sla_seconds: int | None = None

    @model_validator(mode="after")
    def _check_node_ids_unique(self) -> "DAGDefinition":
        node_ids = [n.id for n in self.nodes]
        if len(node_ids) != len(set(node_ids)):
            raise ValueError("DAG node ids must be unique")
        return self


class DAG:
    def __init__(self, definition: DAGDefinition) -> None:
        self.definition = definition
        self._node_map: dict[str, DAGNode] = {n.id: n for n in definition.nodes}
        self._adjacency: dict[str, list[str]] = {n.id: [] for n in definition.nodes}
        self._reverse_adjacency: dict[str, list[str]] = {n.id: [] for n in definition.nodes}
        self._bad_edges: list[DAGEdge] = []
        for edge in definition.edges:
            if edge.source in self._adjacency and edge.target in self._reverse_adjacency:
                self._adjacency[edge.source].append(edge.target)
                self._reverse_adjacency[edge.target].append(edge.source)
            else:
                self._bad_edges.append(edge)

    def validate(self) -> bool:
        errors = self.validate_with_details()
        return len(errors) == 0

    def validate_with_details(self) -> list[dict]:
        errors: list[dict] = []

        cycle = self._detect_cycle_nodes()
        if cycle:
            errors.append({
                "type": "cyclic_dependency",
                "message": f"Cyclic dependency detected: {' -> '.join(cycle + [cycle[0]])}",
                "nodes": cycle,
                "cycle_pair": (cycle[-1], cycle[0]) if len(cycle) >= 1 else None,
            })

        node_ids = set(self._node_map.keys())
        for node in self.definition.nodes:
            for dep in node.dependencies:
                if dep not in node_ids:
                    errors.append({
                        "type": "missing_dependency",
                        "message": f"Node '{node.id}' depends on non-existent task '{dep}'",
                        "node": node.id,
                        "missing_dependency": dep,
                    })

        for bad_edge in self._bad_edges:
            missing_refs = []
            if bad_edge.source not in node_ids:
                missing_refs.append(bad_edge.source)
            if bad_edge.target not in node_ids:
                missing_refs.append(bad_edge.target)
            for ref in missing_refs:
                errors.append({
                    "type": "missing_dependency",
                    "message": f"Edge references non-existent task '{ref}' (edge: {bad_edge.source} -> {bad_edge.target})",
                    "node": bad_edge.target if bad_edge.target in node_ids else bad_edge.source,
                    "missing_dependency": ref,
                })

        edge_node_ids = set()
        for edge in self.definition.edges:
            edge_node_ids.add(edge.source)
            edge_node_ids.add(edge.target)
        orphan_nodes = node_ids - edge_node_ids
        if len(orphan_nodes) > 0 and len(self.definition.edges) > 0:
            for node_id in orphan_nodes:
                node = self._node_map[node_id]
                if not node.dependencies and not self._adjacency[node_id]:
                    errors.append({
                        "type": "orphan_node",
                        "message": f"Orphan node detected: '{node_id}' has no edges connected",
                        "node": node_id,
                    })

        return errors

    def get_execution_order(self) -> list[list[str]]:
        return self._topological_sort()

    def get_node(self, node_id: str) -> DAGNode:
        node = self._node_map.get(node_id)
        if node is None:
            raise KeyError(f"Node '{node_id}' not found in DAG")
        return node

    def get_upstream(self, node_id: str) -> list[str]:
        if node_id not in self._reverse_adjacency:
            raise KeyError(f"Node '{node_id}' not found in DAG")
        return list(self._reverse_adjacency[node_id])

    def get_downstream(self, node_id: str) -> list[str]:
        if node_id not in self._adjacency:
            raise KeyError(f"Node '{node_id}' not found in DAG")
        return list(self._adjacency[node_id])

    def _detect_cycle(self) -> bool:
        return len(self._detect_cycle_nodes()) > 0

    def _detect_cycle_nodes(self) -> list[str]:
        WHITE, GRAY, BLACK = 0, 1, 2
        color = {node_id: WHITE for node_id in self._node_map}
        parent: dict[str, str | None] = {node_id: None for node_id in self._node_map}
        cycle_nodes: list[str] = []

        def dfs(node: str) -> list[str] | None:
            color[node] = GRAY
            for neighbor in self._adjacency[node]:
                if color[neighbor] == GRAY:
                    cycle = [neighbor]
                    current = node
                    while current is not None and current != neighbor:
                        cycle.append(current)
                        current = parent[current]
                    cycle.reverse()
                    return cycle
                if color[neighbor] == WHITE:
                    parent[neighbor] = node
                    result = dfs(neighbor)
                    if result is not None:
                        return result
            color[node] = BLACK
            return None

        for node_id in self._node_map:
            if color[node_id] == WHITE:
                result = dfs(node_id)
                if result is not None:
                    return result
        return []

    def _topological_sort(self) -> list[list[str]]:
        in_degree: dict[str, int] = {n.id: 0 for n in self.definition.nodes}
        for edge in self.definition.edges:
            in_degree[edge.target] += 1

        current_layer = [nid for nid, deg in in_degree.items() if deg == 0]
        layers: list[list[str]] = []

        while current_layer:
            layers.append(sorted(current_layer))
            next_layer: list[str] = []
            for node_id in current_layer:
                for neighbor in self._adjacency[node_id]:
                    in_degree[neighbor] -= 1
                    if in_degree[neighbor] == 0:
                        next_layer.append(neighbor)
            current_layer = next_layer

        return layers
