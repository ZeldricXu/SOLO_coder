from collections import deque
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Set

from .builder import ServiceNode, ServiceEdge, ServiceTopology


@dataclass
class DependencyPath:
    path: List[str]
    total_duration_ms: float
    call_count: int
    error_count: int


@dataclass
class CriticalNode:
    service_name: str
    in_degree: int
    out_degree: int
    total_call_count: int
    impact_score: float


@dataclass
class CycleInfo:
    cycle: List[str]
    avg_duration_ms: float


class TopologyAnalyzer:
    def __init__(self, topology: Optional[ServiceTopology] = None):
        self._topology = topology or ServiceTopology()

    def set_topology(self, topology: ServiceTopology) -> None:
        self._topology = topology

    def find_critical_nodes(self) -> List[CriticalNode]:
        in_degree: Dict[str, int] = {name: 0 for name in self._topology.nodes}
        out_degree: Dict[str, int] = {name: 0 for name in self._topology.nodes}
        total_calls: Dict[str, int] = {name: node.call_count for name, node in self._topology.nodes.items()}
        for edge in self._topology.edges.values():
            if edge.target_service in in_degree:
                in_degree[edge.target_service] += 1
            if edge.source_service in out_degree:
                out_degree[edge.source_service] += 1
        critical_nodes = []
        for name in self._topology.nodes:
            node = self._topology.nodes[name]
            impact = (in_degree[name] * 10) + (out_degree[name] * 5) + (total_calls[name] / 1000)
            critical_nodes.append(CriticalNode(
                service_name=name,
                in_degree=in_degree[name],
                out_degree=out_degree[name],
                total_call_count=total_calls[name],
                impact_score=impact,
            ))
        return sorted(critical_nodes, key=lambda n: n.impact_score, reverse=True)

    def find_shortest_path(self, source: str, target: str) -> Optional[DependencyPath]:
        if source not in self._topology.nodes or target not in self._topology.nodes:
            return None
        visited: Set[str] = set()
        queue: deque = deque([(source, [source], 0.0, 0, 0)])
        shortest: Optional[DependencyPath] = None
        while queue:
            current, path, duration, calls, errors = queue.popleft()
            if current == target:
                candidate = DependencyPath(path=path, total_duration_ms=duration, call_count=calls, error_count=errors)
                if shortest is None or len(path) < len(shortest.path):
                    shortest = candidate
                continue
            if current in visited:
                continue
            visited.add(current)
            for edge in self._topology.edges.values():
                if edge.source_service == current and edge.target_service not in visited:
                    new_duration = duration + edge.avg_duration_ms
                    new_calls = calls + edge.call_count
                    new_errors = errors + edge.error_count
                    queue.append((edge.target_service, path + [edge.target_service], new_duration, new_calls, new_errors))
        return shortest

    def find_all_paths(self, source: str, target: str, max_depth: int = 10) -> List[DependencyPath]:
        if source not in self._topology.nodes or target not in self._topology.nodes:
            return []
        paths: List[DependencyPath] = []
        stack = [(source, [source], 0.0, 0, 0, set([source]))]
        while stack:
            current, path, duration, calls, errors, visited = stack.pop()
            if current == target:
                paths.append(DependencyPath(path=path, total_duration_ms=duration, call_count=calls, error_count=errors))
                continue
            if len(path) >= max_depth:
                continue
            for edge in self._topology.edges.values():
                if edge.source_service == current and edge.target_service not in visited:
                    new_visited = visited | {edge.target_service}
                    new_duration = duration + edge.avg_duration_ms
                    new_calls = calls + edge.call_count
                    new_errors = errors + edge.error_count
                    stack.append((edge.target_service, path + [edge.target_service], new_duration, new_calls, new_errors, new_visited))
        return sorted(paths, key=lambda p: len(p.path))

    def detect_cycles(self) -> List[CycleInfo]:
        cycles: List[CycleInfo] = []
        nodes = list(self._topology.nodes.keys())
        for start_node in nodes:
            stack = [(start_node, [start_node], set([start_node]), 0.0)]
            while stack:
                current, path, visited, duration = stack.pop()
                for edge in self._topology.edges.values():
                    if edge.source_service == current:
                        next_node = edge.target_service
                        if next_node == start_node and len(path) > 1:
                            cycles.append(CycleInfo(
                                cycle=path + [next_node],
                                avg_duration_ms=(duration + edge.avg_duration_ms) / len(path),
                            ))
                        elif next_node not in visited:
                            stack.append((next_node, path + [next_node], visited | {next_node}, duration + edge.avg_duration))
        unique_cycles = []
        seen = set()
        for cycle in cycles:
            normalized = "->".join(sorted(cycle.cycle))
            if normalized not in seen:
                seen.add(normalized)
                unique_cycles.append(cycle)
        return unique_cycles

    def get_service_metrics(self, service_name: str) -> Dict[str, Any]:
        node = self._topology.nodes.get(service_name)
        if not node:
            return {}
        incoming_edges = [e for e in self._topology.edges.values() if e.target_service == service_name]
        outgoing_edges = [e for e in self._topology.edges.values() if e.source_service == service_name]
        return {
            "node": node.to_dict(),
            "incoming_edges": [e.to_dict() for e in incoming_edges],
            "outgoing_edges": [e.to_dict() for e in outgoing_edges],
            "dependencies": self._topology.get_dependencies(service_name),
            "dependents": self._topology.get_dependents(service_name),
            "total_incoming_calls": sum(e.call_count for e in incoming_edges),
            "total_outgoing_calls": sum(e.call_count for e in outgoing_edges),
        }

    def get_health_metrics(self) -> Dict[str, Any]:
        total_calls = sum(n.call_count for n in self._topology.nodes.values())
        total_errors = sum(n.error_count for n in self._topology.nodes.values())
        error_rate = total_errors / total_calls if total_calls > 0 else 0.0
        avg_degree = (len(self._topology.edges) * 2 / len(self._topology.nodes)) if self._topology.nodes else 0
        return {
            "node_count": len(self._topology.nodes),
            "edge_count": len(self._topology.edges),
            "total_calls": total_calls,
            "total_errors": total_errors,
            "error_rate": error_rate,
            "avg_degree": avg_degree,
            "connected_components": self._count_connected_components(),
        }

    def _count_connected_components(self) -> int:
        visited: Set[str] = set()
        components = 0
        for node in self._topology.nodes:
            if node not in visited:
                components += 1
                self._dfs_visit(node, visited)
        return components

    def _dfs_visit(self, node: str, visited: Set[str]) -> None:
        if node in visited:
            return
        visited.add(node)
        for edge in self._topology.edges.values():
            if edge.source_service == node:
                self._dfs_visit(edge.target_service, visited)
            elif edge.target_service == node:
                self._dfs_visit(edge.source_service, visited)
