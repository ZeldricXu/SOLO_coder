import logging
from typing import Any, Dict, List, Optional

import networkx as nx

from src.domain.lineage.lineage_parser import LineageParser
from src.domain.lineage.dag_builder import LineageDAGBuilder
from src.domain.lineage.models import LineageGraph, LineageNode, NodeType
from src.infrastructure.db.metastore import Metastore

logger = logging.getLogger(__name__)


class LineageService:
    def __init__(self, metastore: Optional[Metastore] = None):
        self._parser = LineageParser()
        self._dag_builder = LineageDAGBuilder()
        self._metastore = metastore
        self._cached_dag: Optional[nx.DiGraph] = None

    def parse_sql_lineage(self, sql: str, default_database: str = "default") -> Dict[str, Any]:
        graph = self._parser.parse_sql(sql, default_database)
        return graph.to_dict()

    def build_lineage_dag(self, sql_list: List[str], default_database: str = "default") -> Dict[str, Any]:
        dag = self._dag_builder.build_from_sql_list(sql_list, default_database)
        self._cached_dag = dag
        stats = self._dag_builder.get_statistics()
        return {
            "statistics": stats,
            "topological_order": self._dag_builder.topological_sort(),
        }

    def get_upstream(self, node_id: str, depth: int = -1) -> List[Dict[str, Any]]:
        return self._dag_builder.get_upstream(node_id, depth)

    def get_downstream(self, node_id: str, depth: int = -1) -> List[Dict[str, Any]]:
        return self._dag_builder.get_downstream(node_id, depth)

    def impact_analysis(self, node_id: str) -> Dict[str, Any]:
        return self._dag_builder.get_impact_analysis(node_id)

    def get_lineage_paths(self, source_id: str, target_id: str) -> List[List[str]]:
        return self._dag_builder.get_all_paths(source_id, target_id)

    def export_dot(self) -> str:
        return self._dag_builder.export_dot()

    def export_json(self) -> Dict[str, Any]:
        return self._dag_builder.export_json()

    def save_lineage_to_metastore(self, sql: str, default_database: str = "default") -> None:
        if self._metastore is None:
            logger.warning("Metastore not configured, cannot save lineage")
            return

        graph = self._parser.parse_sql(sql, default_database)
        for edge in graph.edges:
            source_node = graph.nodes.get(edge.source_id)
            target_node = graph.nodes.get(edge.target_id)

            if source_node and target_node:
                src_db = source_node.database or default_database
                src_tbl = source_node.name if source_node.node_type == NodeType.TABLE else source_node.schema_name
                src_col = source_node.name if source_node.node_type == NodeType.COLUMN else None
                tgt_db = target_node.database or default_database
                tgt_tbl = target_node.name if target_node.node_type == NodeType.TABLE else target_node.schema_name
                tgt_col = target_node.name if target_node.node_type == NodeType.COLUMN else None

                if src_tbl and tgt_tbl:
                    self._metastore.save_lineage(
                        source_db=src_db,
                        source_table=src_tbl,
                        source_column=src_col,
                        target_db=tgt_db,
                        target_table=tgt_tbl,
                        target_column=tgt_col,
                        transformation=edge.transformation,
                        sql_text=sql,
                    )

    def get_lineage_from_metastore(self, database_name: str, table_name: str, direction: str = "upstream") -> List[Dict[str, Any]]:
        if self._metastore is None:
            return []
        return self._metastore.get_lineage(database_name, table_name, direction)

    def refresh_dag(self) -> Dict[str, Any]:
        if self._cached_dag is None:
            return {"status": "no_dag_cached"}
        stats = self._dag_builder.get_statistics()
        return {"statistics": stats, "status": "refreshed"}
