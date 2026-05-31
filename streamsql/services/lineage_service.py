from __future__ import annotations

from typing import Any, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import EventBus
from streamsql.modules.data_lineage.extractor import DataLineageExtractor


class LineageService:
    def __init__(self, config_manager: Optional[ConfigManager] = None):
        self.config_manager = config_manager or ConfigManager()
        self.event_bus = EventBus()
        self.extractor = DataLineageExtractor()

    def extract_from_sql(self, sql: str | list[str]) -> dict[str, Any]:
        context = ProcessingContext(trace_id="extract_lineage")

        if isinstance(sql, str):
            sqls = [sql]
        else:
            sqls = sql

        results = []
        for s in sqls:
            table_lineage = self.extractor.extract_from_sql(s)
            results.append({
                "target_table": table_lineage.target_table,
                "source_tables": table_lineage.source_tables,
                "operation": table_lineage.operation_type,
                "column_lineages": [
                    {
                        "target_column": cl.target_column,
                        "source_columns": [f"{t}.{c}" for t, c in cl.source_columns],
                        "transform_type": cl.transform_type,
                        "is_aggregation": cl.is_aggregation,
                        "is_join": cl.is_join,
                    }
                    for cl in table_lineage.column_lineages
                ],
            })

        graph = self.extractor.get_graph()

        return {
            "parsed_queries": len(sqls),
            "lineages": results,
            "graph_summary": graph.get_summary(),
            "extraction_time_ms": context.get_elapsed_ms(),
        }

    def get_table_lineage(self, table_name: str) -> dict[str, Any]:
        return self.extractor.get_graph().get_table_lineage(table_name)

    def get_column_lineage(self, table_name: str, column_name: str) -> dict[str, Any]:
        return self.extractor.get_graph().get_column_lineage(table_name, column_name)

    def analyze_impact(self, table_name: str) -> dict[str, Any]:
        impact = self.extractor.get_graph().analyze_impact(table_name)
        return {
            "target_table": table_name,
            "impacted_nodes_count": len(impact.impacted_nodes),
            "impact_depth": impact.impact_depth,
            "risk_level": impact.risk_level,
            "affected_tables": impact.affected_tables,
            "affected_columns": impact.affected_columns,
            "affected_queries": impact.affected_queries,
            "impacted_nodes": impact.impacted_nodes,
        }

    def get_upstream(self, table_name: str) -> list[str]:
        return self.extractor.get_upstream(table_name)

    def get_downstream(self, table_name: str) -> list[str]:
        return self.extractor.get_downstream(table_name)

    def find_path(self, source_table: str, target_table: str) -> list[str]:
        return self.extractor.get_graph().builder.get_path(source_table, target_table)

    def get_all_tables(self) -> list[str]:
        return self.extractor.get_graph().get_all_tables()

    def get_summary(self) -> dict[str, Any]:
        return self.extractor.get_summary()

    def export_graph(self, path: str) -> None:
        self.extractor.export(path)

    def search_tables(self, keyword: str) -> list[str]:
        return self.extractor.get_graph().search(keyword)

    def has_cycle(self) -> bool:
        return self.extractor.lineage_graph.builder.has_cycle()

    def get_topological_order(self) -> list[str]:
        return self.extractor.lineage_graph.builder.get_topological_order()
