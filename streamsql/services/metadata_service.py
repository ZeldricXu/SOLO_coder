from __future__ import annotations

from typing import Any, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import EventBus
from streamsql.modules.metadata_crawler.crawler import MetadataCrawler


class MetadataService:
    def __init__(self, config_manager: Optional[ConfigManager] = None):
        self.config_manager = config_manager or ConfigManager()
        self.event_bus = EventBus()
        self.crawler = MetadataCrawler()

    def crawl_data_source(
        self,
        data_source_config: dict[str, Any],
        scan_tables: Optional[list[str]] = None,
        sample_size: int = 1000,
    ) -> dict[str, Any]:
        context = ProcessingContext(trace_id=f"crawl_{data_source_config.get('name', 'unknown')}")

        connection = self.crawler.create_mock_connection(
            source_type=data_source_config.get("type", "database"),
            host=data_source_config.get("host", "localhost"),
            port=data_source_config.get("port", 5432),
            database=data_source_config.get("database", "default"),
        )

        tables = scan_tables or ["users", "orders", "products", "transactions"]
        schemas = self.crawler.crawl(connection, tables, sample_size=sample_size)

        return {
            "data_source": data_source_config.get("name", "unknown"),
            "tables_scanned": len(schemas),
            "schemas": [s.to_dict() for s in schemas],
            "total_columns": sum(len(s.columns) for s in schemas),
            "scan_duration_ms": context.get_elapsed_ms(),
        }

    def get_table_schema(
        self,
        table_name: str,
        data_source_config: dict[str, Any],
    ) -> dict[str, Any]:
        result = self.crawl_data_source(data_source_config, scan_tables=[table_name])
        if result["schemas"]:
            return result["schemas"][0]
        return {}

    def get_table_stats(
        self,
        table_name: str,
        data_source_config: dict[str, Any],
        sample_data: Optional[list[dict[str, Any]]] = None,
    ) -> dict[str, Any]:
        from streamsql.modules.metadata_crawler.stats_collector import StatsCollector

        collector = StatsCollector()

        if sample_data is None:
            sample_data = [
                {"id": i, "name": f"user_{i}", "age": 20 + i % 30, "active": i % 2 == 0}
                for i in range(100)
            ]

        table_stats = collector.collect_table_stats(sample_data, table_name)
        return table_stats.to_dict()

    def infer_schema_from_data(self, data: list[dict[str, Any]]) -> dict[str, Any]:
        from streamsql.modules.metadata_crawler.schema_extractor import SchemaExtractor

        extractor = SchemaExtractor()
        schema = extractor.infer_from_data(data, table_name="inferred")
        return schema.to_dict()
