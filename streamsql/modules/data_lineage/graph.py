from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Optional

import networkx as nx

from streamsql.core.models import generate_id
from streamsql.modules.data_lineage.dag_builder import LineageDAGBuilder, LineageEdge, LineageNode


@dataclass
class LineageImpactAnalysis:
    target_node: str
    impacted_nodes: list[str]
    impact_depth: int
    risk_level: str
    affected_queries: int = 0
    affected_tables: int = 0
    affected_columns: int = 0


class LineageGraph:
    def __init__(self):
        self.builder = LineageDAGBuilder()
        self._lineages: list[dict[str, Any]] = []

    def add_lineage(self, table_lineage: Any) -> None:
        self.builder.add_table_lineage(table_lineage)
        self._lineages.append({
            "target_table": table_lineage.target_table,
            "source_tables": table_lineage.source_tables,
            "operation": table_lineage.operation_type,
            "column_count": len(table_lineage.column_lineages),
        })

    def add_sql(self, sql: str, extractor: Any) -> None:
        table_lineage = extractor.extract_table_lineage(sql)
        self.add_lineage(table_lineage)

    def add_sql_batch(self, sqls: list[str], extractor: Any) -> None:
        for sql in sqls:
            self.add_sql(sql, extractor)

    def get_table_lineage(self, table_name: str) -> dict[str, Any]:
        upstream = self.builder.get_upstream(table_name)
        downstream = self.builder.get_downstream(table_name)

        return {
            "table": table_name,
            "upstream": [n for n in upstream if "." not in n],
            "downstream": [n for n in downstream if "." not in n],
            "upstream_columns": [n for n in upstream if "." in n],
            "downstream_columns": [n for n in downstream if "." in n],
            "upstream_count": len([n for n in upstream if "." not in n]),
            "downstream_count": len([n for n in downstream if "." not in n]),
        }

    def get_column_lineage(self, table_name: str, column_name: str) -> dict[str, Any]:
        node_name = f"{table_name}.{column_name}"
        upstream = self.builder.get_upstream(node_name)
        downstream = self.builder.get_downstream(node_name)

        return {
            "column": node_name,
            "upstream_columns": upstream,
            "downstream_columns": downstream,
            "transform_chain": self._get_transform_chain(node_name),
        }

    def _get_transform_chain(self, node_name: str) -> list[dict[str, Any]]:
        chain: list[dict[str, Any]] = []
        graph = self.builder.build()

        current = node_name
        visited = set()

        while current:
            if current in visited:
                break
            visited.add(current)

            preds = list(graph.predecessors(current))
            if not preds:
                break

            next_node = preds[0]
            edge_data = graph.get_edge_data(next_node, current)

            chain.append({
                "from": next_node,
                "to": current,
                "type": edge_data.get("edge_type", "unknown") if edge_data else "unknown",
                "attributes": edge_data if edge_data else {},
            })

            current = next_node

        return chain

    def analyze_impact(self, table_name: str) -> LineageImpactAnalysis:
        downstream = self.builder.get_downstream(table_name)
        depth = self._max_depth(table_name, downstream)

        tables = [n for n in downstream if "." not in n]
        columns = [n for n in downstream if "." in n]

        if depth > 5:
            risk = "high"
        elif depth > 2:
            risk = "medium"
        else:
            risk = "low"

        return LineageImpactAnalysis(
            target_node=table_name,
            impacted_nodes=downstream,
            impact_depth=depth,
            risk_level=risk,
            affected_tables=len(tables),
            affected_columns=len(columns),
            affected_queries=len([l for l in self._lineages if table_name in l["source_tables"]]),
        )

    def _max_depth(self, source: str, nodes: list[str]) -> int:
        graph = self.builder.build()
        max_depth = 0
        for target in nodes:
            try:
                path_len = nx.shortest_path_length(graph, source, target)
                max_depth = max(max_depth, path_len)
            except Exception:
                pass
        return max_depth

    def find_common_ancestors(self, tables: list[str]) -> list[str]:
        if not tables:
            return []

        ancestors_sets = []
        for table in tables:
            upstream = set(self.builder.get_upstream(table))
            upstream.add(table)
            ancestors_sets.append(upstream)

        common = set.intersection(*ancestors_sets) if ancestors_sets else set()
        return sorted(common)

    def find_dependencies_between(self, source: str, target: str) -> list[list[str]]:
        graph = self.builder.build()
        try:
            return list(nx.all_simple_paths(graph, source, target, cutoff=5))
        except (nx.NetworkXNoPath, nx.NodeNotFound):
            return []

    def get_all_tables(self) -> list[str]:
        return [
            n.name for n in self.builder.get_nodes() if n.node_type == "table"
        ]

    def get_all_columns(self, table_name: Optional[str] = None) -> list[str]:
        columns = [n.name for n in self.builder.get_nodes() if n.node_type == "column"]
        if table_name:
            return [c for c in columns if c.startswith(f"{table_name}.")]
        return columns

    def get_summary(self) -> dict[str, Any]:
        return self.builder.get_lineage_summary()

    def search(self, keyword: str) -> list[str]:
        results = []
        for node in self.builder.get_nodes():
            if keyword.lower() in node.name.lower():
                results.append(node.name)
        return results

    def to_dict(self) -> dict[str, Any]:
        return self.builder.to_dict()

    def to_json(self, indent: int = 2) -> str:
        return json.dumps(self.to_dict(), indent=indent, default=str)

    def save(self, path: str) -> None:
        with open(path, "w") as f:
            f.write(self.to_json())

    @classmethod
    def load(cls, path: str) -> "LineageGraph":
        with open(path, "r") as f:
            data = json.load(f)

        graph = cls()
        for node_data in data.get("nodes", []):
            graph.builder._get_or_create_node(
                node_data["name"],
                node_data["node_type"],
                node_data.get("attributes", {}),
            )

        for edge_data in data.get("edges", []):
            graph.builder._add_edge(
                edge_data["source"],
                edge_data["target"],
                edge_data.get("edge_type", "depends_on"),
                edge_data.get("attributes", {}),
            )

        return graph
