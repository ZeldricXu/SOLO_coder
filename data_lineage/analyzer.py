from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple
from collections import defaultdict

from .lineage_graph import LineageGraph, LineageNode, LineageEdge, NodeType, EdgeType


@dataclass
class ImpactAnalysisResult:
    target_node: LineageNode
    impacted_nodes: List[LineageNode] = field(default_factory=list)
    impacted_tables: List[LineageNode] = field(default_factory=list)
    impacted_columns: List[LineageNode] = field(default_factory=list)
    impact_paths: List[List[str]] = field(default_factory=list)
    severity: str = "medium"

    def to_dict(self) -> Dict[str, Any]:
        return {
            "target_node": self.target_node.to_dict(),
            "impacted_nodes": [n.to_dict() for n in self.impacted_nodes],
            "impacted_tables": [n.to_dict() for n in self.impacted_tables],
            "impacted_columns": [n.to_dict() for n in self.impacted_columns],
            "impact_paths": self.impact_paths,
            "severity": self.severity,
            "impact_count": len(self.impacted_nodes),
        }


@dataclass
class LineageSummary:
    total_tables: int = 0
    total_columns: int = 0
    total_ctes: int = 0
    total_subqueries: int = 0
    total_edges: int = 0
    root_nodes: List[str] = field(default_factory=list)
    leaf_nodes: List[str] = field(default_factory=list)
    longest_path_length: int = 0
    is_dag: bool = True
    table_coverage: float = 0.0
    column_coverage: float = 0.0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "total_tables": self.total_tables,
            "total_columns": self.total_columns,
            "total_ctes": self.total_ctes,
            "total_subqueries": self.total_subqueries,
            "total_edges": self.total_edges,
            "root_nodes": self.root_nodes,
            "leaf_nodes": self.leaf_nodes,
            "longest_path_length": self.longest_path_length,
            "is_dag": self.is_dag,
            "table_coverage": self.table_coverage,
            "column_coverage": self.column_coverage,
        }


class LineageAnalyzer:
    def __init__(self, graph: LineageGraph):
        self.graph = graph

    def analyze_impact(self, node_id: str, max_depth: Optional[int] = None) -> ImpactAnalysisResult:
        node = self.graph.get_node(node_id)
        if not node:
            raise ValueError(f"节点不存在: {node_id}")

        descendants = self.graph.get_descendants(node_id)
        impacted_nodes = []
        impacted_tables = []
        impacted_columns = []

        for desc_id in descendants:
            desc_node = self.graph.get_node(desc_id)
            if desc_node:
                impacted_nodes.append(desc_node)
                if desc_node.node_type == NodeType.TABLE:
                    impacted_tables.append(desc_node)
                elif desc_node.node_type == NodeType.COLUMN:
                    impacted_columns.append(desc_node)

        impact_paths = []
        for desc_id in descendants:
            paths = self.graph.find_paths(node_id, desc_id)
            for path in paths:
                if max_depth is None or len(path) <= max_depth + 1:
                    impact_paths.append(path)

        severity = "low"
        if len(impacted_nodes) > 10:
            severity = "high"
        elif len(impacted_nodes) > 3:
            severity = "medium"

        return ImpactAnalysisResult(
            target_node=node,
            impacted_nodes=impacted_nodes,
            impacted_tables=impacted_tables,
            impacted_columns=impacted_columns,
            impact_paths=impact_paths,
            severity=severity,
        )

    def analyze_lineage(self, node_id: str, upstream: bool = True, downstream: bool = True) -> Dict[str, Any]:
        node = self.graph.get_node(node_id)
        if not node:
            raise ValueError(f"节点不存在: {node_id}")

        result = {
            "target_node": node.to_dict(),
            "upstream": [],
            "downstream": [],
            "upstream_depth": 0,
            "downstream_depth": 0,
        }

        if upstream:
            ancestors = self.graph.get_ancestors(node_id)
            for anc_id in ancestors:
                anc_node = self.graph.get_node(anc_id)
                if anc_node:
                    path = self.graph.find_shortest_path(anc_id, node_id)
                    result["upstream"].append({
                        "node": anc_node.to_dict(),
                        "distance": len(path) - 1 if path else 0,
                    })
            result["upstream_depth"] = max(
                (item["distance"] for item in result["upstream"]), default=0
            )

        if downstream:
            descendants = self.graph.get_descendants(node_id)
            for desc_id in descendants:
                desc_node = self.graph.get_node(desc_id)
                if desc_node:
                    path = self.graph.find_shortest_path(node_id, desc_id)
                    result["downstream"].append({
                        "node": desc_node.to_dict(),
                        "distance": len(path) - 1 if path else 0,
                    })
            result["downstream_depth"] = max(
                (item["distance"] for item in result["downstream"]), default=0
            )

        return result

    def trace_source(self, node_id: str) -> List[Dict[str, Any]]:
        node = self.graph.get_node(node_id)
        if not node:
            raise ValueError(f"节点不存在: {node_id}")

        paths = []
        root_nodes = self.graph.get_root_nodes()

        for root in root_nodes:
            path = self.graph.find_shortest_path(root.id, node_id)
            if path:
                path_nodes = [self.graph.get_node(pid) for pid in path if self.graph.get_node(pid)]
                paths.append({
                    "source": root.to_dict(),
                    "path": [n.to_dict() for n in path_nodes],
                    "length": len(path) - 1,
                })

        paths.sort(key=lambda x: x["length"])
        return paths

    def find_critical_path(self) -> List[LineageNode]:
        if not self.graph.is_dag():
            return []

        topo_order = self.graph.topological_sort()
        dist = {node_id: 0 for node_id in topo_order}
        prev = {node_id: None for node_id in topo_order}

        for node_id in topo_order:
            for successor in self.graph.get_successors(node_id):
                if dist[successor.id] < dist[node_id] + 1:
                    dist[successor.id] = dist[node_id] + 1
                    prev[successor.id] = node_id

        end_node = max(dist, key=dist.get) if dist else None
        if not end_node or dist[end_node] == 0:
            return []

        path = []
        current = end_node
        while current is not None:
            node = self.graph.get_node(current)
            if node:
                path.append(node)
            current = prev[current]

        path.reverse()
        return path

    def calculate_coverage(self, known_tables: Optional[Set[str]] = None,
                          known_columns: Optional[Set[str]] = None) -> Tuple[float, float]:
        table_nodes = self.graph.get_nodes_by_type(NodeType.TABLE)
        column_nodes = self.graph.get_nodes_by_type(NodeType.COLUMN)

        table_coverage = 1.0
        if known_tables:
            covered_tables = {n.full_name for n in table_nodes} & known_tables
            table_coverage = len(covered_tables) / len(known_tables) if known_tables else 1.0

        column_coverage = 1.0
        if known_columns:
            covered_columns = set()
            for col_node in column_nodes:
                col_full_name = col_node.full_name
                if col_full_name in known_columns:
                    covered_columns.add(col_full_name)
            column_coverage = len(covered_columns) / len(known_columns) if known_columns else 1.0

        return table_coverage, column_coverage

    def get_summary(self, known_tables: Optional[Set[str]] = None,
                   known_columns: Optional[Set[str]] = None) -> LineageSummary:
        table_nodes = self.graph.get_nodes_by_type(NodeType.TABLE)
        column_nodes = self.graph.get_nodes_by_type(NodeType.COLUMN)
        cte_nodes = self.graph.get_nodes_by_type(NodeType.CTE)
        subquery_nodes = self.graph.get_nodes_by_type(NodeType.SUBQUERY)

        root_nodes = self.graph.get_root_nodes()
        leaf_nodes = self.graph.get_leaf_nodes()

        critical_path = self.find_critical_path()

        table_coverage, column_coverage = self.calculate_coverage(known_tables, known_columns)

        return LineageSummary(
            total_tables=len(table_nodes),
            total_columns=len(column_nodes),
            total_ctes=len(cte_nodes),
            total_subqueries=len(subquery_nodes),
            total_edges=len(self.graph.edges),
            root_nodes=[n.full_name for n in root_nodes],
            leaf_nodes=[n.full_name for n in leaf_nodes],
            longest_path_length=len(critical_path) - 1,
            is_dag=self.graph.is_dag(),
            table_coverage=table_coverage,
            column_coverage=column_coverage,
        )

    def find_circular_dependencies(self) -> List[List[str]]:
        try:
            cycles = list(self.graph.graph._find_cycle())
            return cycles
        except Exception:
            return []

    def get_node_degree_centrality(self) -> Dict[str, Dict[str, float]]:
        result = {}
        for node_id in self.graph.graph.nodes():
            in_degree = self.graph.graph.in_degree(node_id)
            out_degree = self.graph.graph.out_degree(node_id)
            total_nodes = len(self.graph)
            result[node_id] = {
                "in_degree": in_degree,
                "out_degree": out_degree,
                "in_centrality": in_degree / total_nodes if total_nodes > 0 else 0,
                "out_centrality": out_degree / total_nodes if total_nodes > 0 else 0,
                "total_centrality": (in_degree + out_degree) / (2 * total_nodes) if total_nodes > 0 else 0,
            }
        return result

    def get_hot_spots(self, top_n: int = 5) -> List[Dict[str, Any]]:
        centrality = self.get_node_degree_centrality()
        sorted_nodes = sorted(
            centrality.items(),
            key=lambda x: x[1]["total_centrality"],
            reverse=True
        )[:top_n]

        result = []
        for node_id, cent in sorted_nodes:
            node = self.graph.get_node(node_id)
            if node:
                result.append({
                    "node": node.to_dict(),
                    "centrality": cent,
                })
        return result

    def compare_lineage(self, other_graph: LineageGraph) -> Dict[str, Any]:
        nodes_self = {n.id: n for n in self.graph.nodes}
        nodes_other = {n.id: n for n in other_graph.nodes}

        edges_self = {e.key for e in self.graph.edges}
        edges_other = {e.key for e in other_graph.edges}

        common_nodes = set(nodes_self.keys()) & set(nodes_other.keys())
        only_in_self = set(nodes_self.keys()) - set(nodes_other.keys())
        only_in_other = set(nodes_other.keys()) - set(nodes_self.keys())

        common_edges = edges_self & edges_other
        edges_only_in_self = edges_self - edges_other
        edges_only_in_other = edges_other - edges_self

        return {
            "nodes": {
                "common": len(common_nodes),
                "only_in_self": [nodes_self[nid].to_dict() for nid in only_in_self],
                "only_in_other": [nodes_other[nid].to_dict() for nid in only_in_other],
            },
            "edges": {
                "common": len(common_edges),
                "only_in_self": len(edges_only_in_self),
                "only_in_other": len(edges_only_in_other),
            },
            "similarity": {
                "node_similarity": len(common_nodes) / max(len(nodes_self), len(nodes_other), 1),
                "edge_similarity": len(common_edges) / max(len(edges_self), len(edges_other), 1),
            },
        }

    def get_downstream_tables(self, table_name: str) -> List[LineageNode]:
        table_id = f"table:{table_name}"
        if not self.graph.has_node(table_id):
            return []

        descendants = self.graph.get_descendants(table_id)
        result = []
        for desc_id in descendants:
            node = self.graph.get_node(desc_id)
            if node and node.node_type == NodeType.TABLE:
                result.append(node)
        return result

    def get_upstream_tables(self, table_name: str) -> List[LineageNode]:
        table_id = f"table:{table_name}"
        if not self.graph.has_node(table_id):
            return []

        ancestors = self.graph.get_ancestors(table_id)
        result = []
        for anc_id in ancestors:
            node = self.graph.get_node(anc_id)
            if node and node.node_type == NodeType.TABLE:
                result.append(node)
        return result

    def get_column_dependencies(self, column_name: str, table_name: str) -> List[Dict[str, Any]]:
        column_id = f"column:{table_name}.{column_name}"
        if not self.graph.has_node(column_id):
            return []

        ancestors = self.graph.get_ancestors(column_id)
        dependencies = []
        for anc_id in ancestors:
            node = self.graph.get_node(anc_id)
            if node and node.node_type == NodeType.COLUMN:
                path = self.graph.find_shortest_path(anc_id, column_id)
                edges = []
                for i in range(len(path) - 1):
                    for edge_type in EdgeType:
                        edge = self.graph.get_edge(path[i], path[i + 1], edge_type)
                        if edge:
                            edges.append(edge.to_dict())
                            break

                dependencies.append({
                    "source_column": node.to_dict(),
                    "path_length": len(path) - 1,
                    "edges": edges,
                })

        dependencies.sort(key=lambda x: x["path_length"])
        return dependencies

    def find_unused_columns(self, table_name: str, known_columns: List[str]) -> List[str]:
        table_id = f"table:{table_name}"
        if not self.graph.has_node(table_id):
            return known_columns

        descendants = self.graph.get_descendants(table_id)
        used_columns = set()

        for desc_id in descendants:
            node = self.graph.get_node(desc_id)
            if node and node.node_type == NodeType.COLUMN:
                col_name = node.name
                used_columns.add(col_name)

        return [col for col in known_columns if col not in used_columns]

    def validate_lineage(self) -> List[Dict[str, Any]]:
        issues = []

        if not self.graph.is_dag():
            cycles = self.find_circular_dependencies()
            issues.append({
                "type": "circular_dependency",
                "severity": "high",
                "message": "检测到循环依赖",
                "details": cycles,
            })

        for node in self.graph.nodes:
            in_edges = self.graph.get_in_edges(node.id)
            out_edges = self.graph.get_out_edges(node.id)

            if len(in_edges) == 0 and len(out_edges) == 0:
                issues.append({
                    "type": "isolated_node",
                    "severity": "low",
                    "message": f"孤立节点: {node.full_name}",
                    "node_id": node.id,
                })

        for edge in self.graph.edges:
            if not self.graph.has_node(edge.source_id):
                issues.append({
                    "type": "missing_source_node",
                    "severity": "medium",
                    "message": f"边的源节点不存在: {edge.source_id}",
                    "edge": edge.to_dict(),
                })
            if not self.graph.has_node(edge.target_id):
                issues.append({
                    "type": "missing_target_node",
                    "severity": "medium",
                    "message": f"边的目标节点不存在: {edge.target_id}",
                    "edge": edge.to_dict(),
                })

        return issues
