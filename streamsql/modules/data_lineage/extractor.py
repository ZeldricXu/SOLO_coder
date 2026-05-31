from __future__ import annotations

from typing import Any, Optional

from streamsql.core.context import ProcessingContext
from streamsql.core.events import Event, EventBus, EventType
from streamsql.core.models import generate_id

from streamsql.modules.data_lineage.column_lineage import SQLColumnLineageExtractor, TableLineage
from streamsql.modules.data_lineage.dag_builder import LineageDAGBuilder
from streamsql.modules.data_lineage.graph import LineageGraph


class DataLineageExtractor:
    def __init__(self, context: Optional[ProcessingContext] = None):
        self.context = context or ProcessingContext(trace_id=generate_id("trace"))
        self.event_bus = EventBus()
        self.column_extractor = SQLColumnLineageExtractor()
        self.dag_builder = LineageDAGBuilder()
        self.lineage_graph = LineageGraph()

    def extract_from_sql(self, sql: str) -> TableLineage:
        self.event_bus.emit(
            Event(EventType.TASK_STARTED, {"module": "data_lineage", "sql_length": len(sql)})
        )

        try:
            table_lineage = self.column_extractor.extract_table_lineage(sql)

            self.dag_builder.add_table_lineage(table_lineage)
            self.lineage_graph.add_lineage(table_lineage)

            self.event_bus.emit(
                Event(
                    EventType.LINEAGE_UPDATED,
                    {
                        "target_table": table_lineage.target_table,
                        "source_tables": len(table_lineage.source_tables),
                        "columns": len(table_lineage.column_lineages),
                    },
                )
            )

            self.event_bus.emit(
                Event(EventType.TASK_COMPLETED, {"module": "data_lineage"})
            )

            return table_lineage

        except Exception as e:
            self.event_bus.emit(
                Event(EventType.TASK_FAILED, {"module": "data_lineage", "error": str(e)})
            )
            raise

    def extract_from_sql_batch(self, sqls: list[str]) -> list[TableLineage]:
        results: list[TableLineage] = []
        for sql in sqls:
            results.append(self.extract_from_sql(sql))
        return results

    def extract_table_lineage(self, sql: str) -> TableLineage:
        return self.extract_from_sql(sql)

    def extract_column_lineage(self, sql: str) -> list[Any]:
        return self.column_extractor.extract(sql)

    def get_graph(self) -> LineageGraph:
        return self.lineage_graph

    def get_dag(self) -> Any:
        return self.dag_builder.build()

    def get_upstream(self, table_name: str) -> list[str]:
        return self.lineage_graph.get_table_lineage(table_name)["upstream"]

    def get_downstream(self, table_name: str) -> list[str]:
        return self.lineage_graph.get_table_lineage(table_name)["downstream"]

    def analyze_impact(self, table_name: str) -> Any:
        return self.lineage_graph.analyze_impact(table_name)

    def get_summary(self) -> dict[str, Any]:
        return self.lineage_graph.get_summary()

    def export(self, path: str) -> None:
        self.lineage_graph.save(path)
