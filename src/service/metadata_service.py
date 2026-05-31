import logging
from typing import Any, Dict, List, Optional

from src.domain.metadata.schema_extractor import SchemaExtractor, TableSchema
from src.domain.metadata.stats_collector import StatsCollector, TableStats
from src.domain.metadata.sample_fetcher import SampleFetcher, SampleData
from src.infrastructure.config.settings import MetadataConfig
from src.infrastructure.db.connection_pool import ConnectionPool
from src.infrastructure.db.metastore import Metastore

logger = logging.getLogger(__name__)


class MetadataService:
    def __init__(
        self,
        pool: ConnectionPool,
        metastore: Optional[Metastore] = None,
        config: Optional[MetadataConfig] = None,
    ):
        self._config = config or MetadataConfig()
        self._schema_extractor = SchemaExtractor(pool)
        self._stats_collector = StatsCollector(pool, self._config.max_sample_rows)
        self._sample_fetcher = SampleFetcher(pool, self._config)
        self._metastore = metastore
        self._pool = pool

    def scan_database(self, database_name: str, schema_name: str = "public") -> Dict[str, Any]:
        schemas = self._schema_extractor.extract_all_schemas(database_name, schema_name)
        result = {
            "database_name": database_name,
            "schema_name": schema_name,
            "tables": [],
        }
        for schema in schemas:
            table_info = schema.to_dict()
            result["tables"].append(table_info)

            if self._metastore:
                try:
                    self._metastore.save_table_metadata(
                        database_name=database_name,
                        table_name=schema.table_name,
                        schema_json=schema.to_dict(),
                    )
                    for col in schema.columns:
                        self._metastore.save_column_metadata(
                            database_name=database_name,
                            table_name=schema.table_name,
                            column_name=col.name,
                            data_type=col.data_type,
                            nullable=col.nullable,
                            default_value=col.default_value,
                            comment=col.comment,
                            ordinal_position=col.ordinal_position,
                        )
                except Exception as e:
                    logger.error(f"Failed to save metadata for {schema.table_name}: {e}")

        return result

    def get_table_schema(self, database_name: str, table_name: str, schema_name: str = "public") -> Optional[Dict[str, Any]]:
        if self._metastore:
            cached = self._metastore.get_table_metadata(database_name, table_name)
            if cached:
                return cached

        schema = self._schema_extractor.extract_table_schema(database_name, table_name, schema_name)
        if schema:
            return schema.to_dict()
        return None

    def collect_table_stats(self, database_name: str, table_name: str, schema_name: str = "public") -> Dict[str, Any]:
        stats = self._stats_collector.collect_table_stats(database_name, table_name, schema_name)

        if self._metastore:
            try:
                self._metastore.save_table_metadata(
                    database_name=database_name,
                    table_name=table_name,
                    schema_json={},
                    stats_json=stats.to_dict(),
                )
            except Exception as e:
                logger.error(f"Failed to save stats for {table_name}: {e}")

        return stats.to_dict()

    def get_sample_data(
        self,
        database_name: str,
        table_name: str,
        schema_name: str = "public",
        limit: Optional[int] = None,
        method: Optional[str] = None,
    ) -> Dict[str, Any]:
        sample = self._sample_fetcher.fetch_sample(database_name, table_name, schema_name, limit, method)

        if self._metastore:
            try:
                self._metastore.save_table_metadata(
                    database_name=database_name,
                    table_name=table_name,
                    schema_json={},
                    sample_json=sample.to_dict(),
                )
            except Exception as e:
                logger.error(f"Failed to save sample for {table_name}: {e}")

        return sample.to_dict()

    def full_scan(self, database_name: str, schema_name: str = "public") -> Dict[str, Any]:
        scan_result = self.scan_database(database_name, schema_name)
        stats_results = {}
        sample_results = {}

        for table_info in scan_result.get("tables", []):
            table_name = table_info["table_name"]
            try:
                stats = self.collect_table_stats(database_name, table_name, schema_name)
                stats_results[table_name] = stats
            except Exception as e:
                logger.error(f"Stats collection failed for {table_name}: {e}")
                stats_results[table_name] = {"error": str(e)}

            try:
                sample = self.get_sample_data(database_name, table_name, schema_name)
                sample_results[table_name] = sample
            except Exception as e:
                logger.error(f"Sample fetch failed for {table_name}: {e}")
                sample_results[table_name] = {"error": str(e)}

        return {
            "database_name": database_name,
            "schema_name": schema_name,
            "table_count": len(scan_result.get("tables", [])),
            "schemas": scan_result,
            "stats": stats_results,
            "samples": sample_results,
        }

    def list_tables(self, database_name: Optional[str] = None) -> List[Dict[str, Any]]:
        if self._metastore:
            return self._metastore.list_tables(database_name)
        return self._schema_extractor.extract_table_list(database_name or "default", "public")
