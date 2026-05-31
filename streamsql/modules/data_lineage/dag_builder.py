from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import networkx as nx

from streamsql.core.models import generate_id
from streamsql.modules.data_lineage.column_lineage import ColumnLineage, TableLineage


@dataclass
class LineageNode:
    node_id: str = field(default_factory=lambda: generate_id("node"))
    node_type: str = "table"
    name: str = ""
    attributes: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "node_id": self.node_id,
            "node_type": self.node_type,
            "name": self.name,
            "attributes": self.attributes,
        }


@dataclass
class LineageEdge:
    edge_id: str = field(default_factory=lambda: generate_id("edge"))
    source: str = ""
    target: str = ""
    edge_type: str = "depends_on"
    attributes: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "edge_id": self.edge_id,
            "source": self.source,
            "target": self.target,
            "edge_type": self.edge_type,
            "attributes": self.attributes,
        }


class LineageDAGBuilder:
    def __init__(self):
        self._nodes: dict[str, LineageNode] = {}
        self._edges: list[LineageEdge] = []
        self._graph: nx.DiGraph = nx.DiGraph()

    def add_table_lineage(self, table_lineage: TableLineage) -> None:
        target_node = self._get_or_create_node(
            table_lineage.target_table, "table", {"operation": table_lineage.operation_type}
        )

        for source_table in table_lineage.source_tables:
            source_node = self._get_or_create_node(source_table, "table")
            self._add_edge(
                source_node.name,
                target_node.name,
                "table_dependency",
                {"operation": table_lineage.operation_type},
            )

        for col_lineage in table_lineage.column_lineages:
            self._add_column_lineage(col_lineage, target_node.name)

    def _add_column_lineage(self, col_lineage: ColumnLineage, target_table: str) -> None:
        target_col_node = self._get_or_create_node(
            f"{target_table}.{col_lineage.target_column}",
            "column",
            {
                "transform_type": col_lineage.transform_type,
                "expression": col_lineage.expression,
                "is_aggregation": col_lineage.is_aggregation,
                "is_join": col_lineage.is_join,
            },
        )

        self._add_edge(
            target_table,
            target_col_node.name,
            "contains",
            {},
        )

        for source_table, source_col in col_lineage.source_columns:
            source_col_node = self._get_or_create_node(
                f"{source_table}.{source_col}", "column"
            )
            self._add_edge(
                source_col_node.name,
                target_col_node.name,
                "column_dependency",
                {"transform_type": col_lineage.transform_type},
            )

            if source_table not in self._nodes:
                self._get_or_create_node(source_table, "table")
            self._add_edge(
                source_table,
                source_col_node.name,
                "contains",
                {},
            )

    def _get_or_create_node(self, name: str, node_type: str, attributes: dict[str, Any] | None = None) -> LineageNode:
        if name in self._nodes:
            if attributes:
                self._nodes[name].attributes.update(attributes)
            return self._nodes[name]

        node = LineageNode(name=name, node_type=node_type, attributes=attributes or {})
        self._nodes[name] = node
        self._graph.add_node(name, **node.attributes)
        return node

    def _add_edge(self, source: str, target: str, edge_type: str, attributes: dict[str, Any]) -> None:
        edge = LineageEdge(
            source=source,
            target=target,
            edge_type=edge_type,
            attributes=attributes,
        )
        self._edges.append(edge)
        self._graph.add_edge(source, target, **attributes, edge_type=edge_type)

    def build(self) -> nx.DiGraph:
        return self._graph.copy()

    def get_nodes(self) -> list[LineageNode]:
        return list(self._nodes.values())

    def get_edges(self) -> list[LineageEdge]:
        return self._edges

    def get_upstream(self, node_name: str, depth: int = -1) -> list[str]:
        if node_name not in self._graph:
            return []
        return list(nx.ancestors(self._graph, node_name))

    def get_downstream(self, node_name: str, depth: int = -1) -> list[str]:
        if node_name not in self._graph:
            return []
        return list(nx.descendants(self._graph, node_name))

    def get_path(self, source: str, target: str) -> list[str]:
        try:
            return nx.shortest_path(self._graph, source, target)
        except (nx.NetworkXNoPath, nx.NodeNotFound):
            return []

    def has_cycle(self) -> bool:
        return not nx.is_directed_acyclic_graph(self._graph)

    def get_cycles(self) -> list[list[str]]:
        try:
            return list(nx.find_cycle(self._graph))
        except nx.NetworkXNoCycle:
            return []

    def get_topological_order(self) -> list[str]:
        try:
            return list(nx.topological_sort(self._graph))
        except nx.NetworkXUnfeasible:
            return []

    def get_lineage_summary(self) -> dict[str, Any]:
        return {
            "total_nodes": len(self._nodes),
            "total_edges": len(self._edges),
            "table_count": sum(1 for n in self._nodes.values() if n.node_type == "table"),
            "column_count": sum(1 for n in self._nodes.values() if n.node_type == "column"),
            "has_cycle": self.has_cycle(),
            "is_connected": nx.is_weakly_connected(self._graph) if self._graph.nodes else False,
            "density": nx.density(self._graph) if self._graph.nodes else 0.0,
        }

    def to_dict(self) -> dict[str, Any]:
        return {
            "nodes": [n.to_dict() for n in self._nodes.values()],
            "edges": [e.to_dict() for e in self._edges],
            "summary": self.get_lineage_summary(),
        }

    def export_graphviz(self, path: str) -> None:
        agraph = nx.nx_agraph.to_agraph(self._graph)
        agraph.draw(path, prog="dot")
