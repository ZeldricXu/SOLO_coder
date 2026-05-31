import logging
from collections import deque
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError
from typing import Dict, List, Optional, Set, Tuple

import networkx as nx

from src.domain.lineage.models import (
    LineageGraph,
    LineageNode,
    LineageEdge,
    ColumnLineage,
    NodeType,
    EdgeType,
)
from src.domain.lineage.lineage_parser import LineageParseTimeoutException

logger = logging.getLogger(__name__)


class LineageDAGBuilder:
    DEFAULT_TIMEOUT = 30.0

    def __init__(self, default_timeout: float = DEFAULT_TIMEOUT):
        self._nx_graph: Optional[nx.DiGraph] = None
        self._default_timeout = default_timeout
        self._thread_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="dag-builder")

    def __del__(self):
        try:
            self._thread_pool.shutdown(wait=False)
        except Exception:
            pass

    def build_from_graph(
        self,
        lineage_graph: LineageGraph,
        timeout: Optional[float] = None,
    ) -> nx.DiGraph:
        timeout_seconds = timeout or self._default_timeout

        def do_build():
            dag = nx.DiGraph()
            for node_id, node in lineage_graph.nodes.items():
                dag.add_node(node_id, **{
                    "node_type": node.node_type.value,
                    "name": node.name,
                    "database": node.database,
                    "schema_name": node.schema_name,
                    "qualified_name": node.qualified_name,
                })
            for edge in lineage_graph.edges:
                dag.add_edge(
                    edge.source_id,
                    edge.target_id,
                    edge_type=edge.edge_type.value,
                    transformation=edge.transformation,
                    sql_text=edge.sql_text,
                )

            if not nx.is_directed_acyclic_graph(dag):
                cycles = list(nx.simple_cycles(dag))
                logger.warning(f"Lineage graph contains cycles: {cycles}")
                for cycle in cycles:
                    if len(cycle) > 1:
                        dag.remove_edge(cycle[-1], cycle[0])

            return dag

        future = self._thread_pool.submit(do_build)
        try:
            result = future.result(timeout=timeout_seconds)
            self._nx_graph = result
            return result
        except FutureTimeoutError:
            future.cancel()
            logger.warning(f"DAG building timed out after {timeout_seconds}s")
            raise LineageParseTimeoutException("DAG building", timeout_seconds)

    def build_from_sql_list(
        self,
        sql_list: List[str],
        default_database: str = "default",
        timeout: Optional[float] = None,
    ) -> nx.DiGraph:
        from src.domain.lineage.lineage_parser import LineageParser

        timeout_seconds = timeout or self._default_timeout
        per_sql_timeout = min(5.0, timeout_seconds / max(1, len(sql_list)))

        parser = LineageParser(default_timeout=per_sql_timeout)
        combined_graph = LineageGraph()

        start_time = __import__("time").time()
        for sql in sql_list:
            elapsed = __import__("time").time() - start_time
            if elapsed >= timeout_seconds:
                logger.warning(f"DAG building from SQL list timed out after {elapsed:.1f}s")
                raise LineageParseTimeoutException(
                    f"Multiple SQL parsing timed out after {timeout_seconds}s",
                    timeout_seconds,
                )
            try:
                sub_graph = parser.parse_sql(sql, default_database, timeout=per_sql_timeout)
                for node_id, node in sub_graph.nodes.items():
                    if node_id not in combined_graph.nodes:
                        combined_graph.add_node(node)
                for edge in sub_graph.edges:
                    combined_graph.add_edge(edge)
            except LineageParseTimeoutException:
                raise
            except Exception as e:
                logger.error(f"Failed to parse SQL for lineage: {e}")

        return self.build_from_graph(combined_graph, timeout=timeout_seconds)

    def get_upstream(self, node_id: str, depth: int = -1) -> List[Dict]:
        if self._nx_graph is None:
            return []
        if node_id not in self._nx_graph:
            return []
        result = []
        visited = set()
        queue = deque([(node_id, 0)])
        while queue:
            current, current_depth = queue.popleft()
            if current in visited:
                continue
            visited.add(current)
            if current != node_id:
                node_data = self._nx_graph.nodes[current]
                result.append({
                    "node_id": current,
                    "depth": current_depth,
                    **node_data,
                })
            if depth == -1 or current_depth < depth:
                for pred in self._nx_graph.predecessors(current):
                    if pred not in visited:
                        queue.append((pred, current_depth + 1))
        return result

    def get_downstream(self, node_id: str, depth: int = -1) -> List[Dict]:
        if self._nx_graph is None:
            return []
        if node_id not in self._nx_graph:
            return []
        result = []
        visited = set()
        queue = deque([(node_id, 0)])
        while queue:
            current, current_depth = queue.popleft()
            if current in visited:
                continue
            visited.add(current)
            if current != node_id:
                node_data = self._nx_graph.nodes[current]
                result.append({
                    "node_id": current,
                    "depth": current_depth,
                    **node_data,
                })
            if depth == -1 or current_depth < depth:
                for succ in self._nx_graph.successors(current):
                    if succ not in visited:
                        queue.append((succ, current_depth + 1))
        return result

    def get_root_nodes(self) -> List[str]:
        if self._nx_graph is None:
            return []
        return [n for n in self._nx_graph.nodes if self._nx_graph.in_degree(n) == 0]

    def get_leaf_nodes(self) -> List[str]:
        if self._nx_graph is None:
            return []
        return [n for n in self._nx_graph.nodes if self._nx_graph.out_degree(n) == 0]

    def get_impact_analysis(self, node_id: str) -> Dict:
        downstream = self.get_downstream(node_id)
        table_nodes = [d for d in downstream if d.get("node_type") == NodeType.TABLE.value]
        column_nodes = [d for d in downstream if d.get("node_type") == NodeType.COLUMN.value]
        return {
            "source_node": node_id,
            "impacted_tables": len(table_nodes),
            "impacted_columns": len(column_nodes),
            "total_impacted": len(downstream),
            "details": downstream,
        }

    def topological_sort(self) -> List[str]:
        if self._nx_graph is None:
            return []
        try:
            return list(nx.topological_sort(self._nx_graph))
        except nx.NetworkXUnfeasible:
            logger.warning("Graph has cycles, cannot perform topological sort")
            return []

    def get_all_paths(self, source_id: str, target_id: str) -> List[List[str]]:
        if self._nx_graph is None:
            return []
        try:
            return list(nx.all_simple_paths(self._nx_graph, source_id, target_id))
        except nx.NetworkXNoPath:
            return []

    def get_statistics(self) -> Dict:
        if self._nx_graph is None:
            return {}
        return {
            "total_nodes": self._nx_graph.number_of_nodes(),
            "total_edges": self._nx_graph.number_of_edges(),
            "table_nodes": sum(1 for n, d in self._nx_graph.nodes(data=True) if d.get("node_type") == NodeType.TABLE.value),
            "column_nodes": sum(1 for n, d in self._nx_graph.nodes(data=True) if d.get("node_type") == NodeType.COLUMN.value),
            "is_dag": nx.is_directed_acyclic_graph(self._nx_graph),
            "root_nodes": len(self.get_root_nodes()),
            "leaf_nodes": len(self.get_leaf_nodes()),
        }

    def export_dot(self) -> str:
        if self._nx_graph is None:
            return ""
        lines = ["digraph lineage {"]
        for node_id, node_data in self._nx_graph.nodes(data=True):
            label = node_data.get("qualified_name", node_data.get("name", node_id))
            node_type = node_data.get("node_type", "UNKNOWN")
            shape = "box" if node_type == NodeType.TABLE.value else "ellipse"
            lines.append(f'  "{node_id}" [label="{label}", shape={shape}];')
        for u, v, edge_data in self._nx_graph.edges(data=True):
            edge_type = edge_data.get("edge_type", "")
            label = edge_type.replace("_", " ").title() if edge_type else ""
            lines.append(f'  "{u}" -> "{v}" [label="{label}"];')
        lines.append("}")
        return "\n".join(lines)

    def export_json(self) -> Dict:
        if self._nx_graph is None:
            return {}
        from networkx.readwrite import json_graph
        return json_graph.node_link_data(self._nx_graph)
