from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any, Callable, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import Event, EventBus, EventType
from streamsql.core.exceptions import SchemaExtractionError, TimeoutError, ValidationError
from streamsql.core.models import SchemaInfo, TableSchema, generate_id

from streamsql.modules.metadata_crawler.schema_extractor import SchemaExtractor
from streamsql.modules.metadata_crawler.stats_collector import StatsCollector
from streamsql.modules.metadata_crawler.strategies import (
    CrawlStrategy,
    CrawlStrategyConfig,
    ScanMode,
    StrategyRegistry,
)
from streamsql.modules.metadata_crawler.dynamic_config import (
    ConfigUpdateEvent,
    DynamicConfigManager,
    get_global_config_manager,
)


class MetadataCrawler:
    """
    Metadata crawler with support for dynamic configuration and pluggable strategies.

    Enhanced features:
    - Dynamic configuration with hot reload
    - Pluggable crawl strategies (sequential, batch, priority, throttled)
    - Configuration change callbacks
    - Backward compatible with existing API
    """

    def __init__(
        self,
        datasource: Optional[dict[str, Any]] = None,
        context: Optional[ProcessingContext] = None,
        strategy: Optional[CrawlStrategy] = None,
        config_manager: Optional[DynamicConfigManager] = None,
    ):
        self.datasource = datasource or {}
        self.ds_name = self.datasource.get("name", "unknown")
        self.ds_type = self.datasource.get("type", "unknown")
        self.connection = self.datasource.get("connection", {})

        try:
            config = ConfigManager.get()
            default_sample_size = config.modules.metadata_crawler.sample_size
            default_timeout = config.modules.metadata_crawler.timeout
        except Exception:
            default_sample_size = 100
            default_timeout = 30

        self.context = context or ProcessingContext(trace_id=generate_id("trace"))
        self.event_bus = EventBus()
        self.schema_extractor = SchemaExtractor()
        self.stats_collector = StatsCollector()

        self._config_manager = config_manager or get_global_config_manager()
        self._strategy = strategy or StrategyRegistry.get("sequential")
        self._sample_size = default_sample_size
        self._timeout = default_timeout

        if not self._config_manager.has_config(self.ds_name):
            default_config = CrawlStrategyConfig(
                sample_size=default_sample_size,
                timeout_total=default_timeout,
            )
            self._config_manager.set_config(self.ds_name, default_config, notify=False)

        self._config_manager.register_callback(self._on_config_update)

        self._connection = None

    @property
    def sample_size(self) -> int:
        """Get current sample size from active configuration."""
        return self._config_manager.get_config(self.ds_name).sample_size

    @sample_size.setter
    def sample_size(self, value: int) -> None:
        """Set sample size in active configuration."""
        self._config_manager.update_config(
            self.ds_name,
            {"sample_size": value},
            updated_by="property_setter",
        )

    @property
    def timeout(self) -> int:
        """Get current timeout from active configuration."""
        return self._config_manager.get_config(self.ds_name).timeout_total

    @timeout.setter
    def timeout(self, value: int) -> None:
        """Set timeout in active configuration."""
        self._config_manager.update_config(
            self.ds_name,
            {"timeout_total": value},
            updated_by="property_setter",
        )

    @property
    def strategy(self) -> Optional[CrawlStrategy]:
        """Get current crawl strategy."""
        return self._strategy

    def set_strategy(self, strategy: CrawlStrategy) -> None:
        """Set the crawl strategy at runtime."""
        self._strategy = strategy
        self.event_bus.emit(
            Event(
                EventType.CONFIG_UPDATED,
                {
                    "module": "metadata_crawler",
                    "datasource": self.ds_name,
                    "strategy_changed_to": strategy.name,
                },
            )
        )

    def set_strategy_by_name(self, strategy_name: str) -> bool:
        """Set strategy by registered name. Returns True if successful."""
        strategy = StrategyRegistry.get(strategy_name)
        if strategy:
            self.set_strategy(strategy)
            return True
        return False

    def get_active_config(self) -> CrawlStrategyConfig:
        """Get the active configuration for this datasource."""
        return self._config_manager.get_config(self.ds_name)

    def update_config(self, updates: dict[str, Any]) -> ConfigUpdateEvent:
        """Update specific configuration fields."""
        return self._config_manager.update_config(
            self.ds_name,
            updates,
            updated_by="crawler_api",
        )

    def reset_config(self) -> ConfigUpdateEvent:
        """Reset configuration to default."""
        return self._config_manager.reset_config(self.ds_name)

    def _on_config_update(self, event: ConfigUpdateEvent) -> None:
        """Handle configuration updates."""
        if event.datasource == self.ds_name:
            self.event_bus.emit(
                Event(
                    EventType.CONFIG_UPDATED,
                    {
                        "module": "metadata_crawler",
                        "datasource": self.ds_name,
                        "old_config": event.old_config.to_dict(),
                        "new_config": event.new_config.to_dict(),
                        "updated_by": event.updated_by,
                    },
                )
            )

    async def scan(
        self,
        strategy: Optional[CrawlStrategy] = None,
        config_override: Optional[dict[str, Any]] = None,
    ) -> SchemaInfo:
        """
        Scan datasource with optional strategy and config overrides.

        Args:
            strategy: Optional strategy to use for this scan only
            config_override: Optional config overrides for this scan only

        Returns:
            SchemaInfo containing all discovered table schemas
        """
        self.context.add_metadata("datasource", self.ds_name)
        self.event_bus.emit(
            Event(EventType.TASK_STARTED, {"module": "metadata_crawler", "datasource": self.ds_name})
        )

        active_config = self.get_active_config()
        if config_override:
            config_dict = active_config.to_dict()
            config_dict.update(config_override)
            active_config = CrawlStrategyConfig.from_dict(config_dict)

        active_strategy = strategy or self._strategy

        try:
            async def _run_scan() -> SchemaInfo:
                await self._connect()

                all_tables = await self._list_tables()
                tables = self._filter_tables(all_tables, active_config)
                self.context.add_metric("tables_found", len(tables))
                self.context.add_metric("tables_filtered", len(all_tables) - len(tables))

                async def extract_single(table_name: str, config: CrawlStrategyConfig) -> Optional[TableSchema]:
                    try:
                        return await self._extract_table_schema(table_name, config=config)
                    except Exception as e:
                        self.context.add_error("schema_extraction", f"{table_name}: {e}")
                        self.context.add_metric(f"table_{table_name}_failed", 1)
                        raise

                table_schemas, failed = await active_strategy.execute(
                    tables,
                    extract_single,
                    active_config,
                )

                for schema in table_schemas:
                    self.context.add_metric(f"table_{schema.table}_success", 1)

                schema_info = SchemaInfo(
                    datasource=self.datasource.get("name", "unknown") if self.datasource else "unknown",
                    tables=table_schemas,
                )

                event_bus = EventBus()
                await event_bus.emit_async(Event(
                    event_type=EventType.SCHEMA_UPDATED,
                    payload=schema_info.model_dump(),
                    source="metadata_crawler",
                ))

                return schema_info

            result = await asyncio.wait_for(_run_scan(), timeout=active_config.timeout_total)
            return result

        except asyncio.TimeoutError:
            raise TimeoutError("metadata_scan", active_config.timeout_total)
        finally:
            await self._disconnect()

    async def scan_table(
        self,
        database: str,
        table: str,
        config_override: Optional[dict[str, Any]] = None,
    ) -> TableSchema:
        self.context.add_metadata("target_table", f"{database}.{table}")

        active_config = self.get_active_config()
        if config_override:
            config_dict = active_config.to_dict()
            config_dict.update(config_override)
            active_config = CrawlStrategyConfig.from_dict(config_dict)

        async def _run_scan() -> TableSchema:
            await self._connect()
            return await self._extract_table_schema(table, database, active_config)

        try:
            return await asyncio.wait_for(_run_scan(), timeout=active_config.timeout_per_table)
        except asyncio.TimeoutError:
            raise TimeoutError(f"table_scan_{database}.{table}", active_config.timeout_per_table)
        finally:
            await self._disconnect()

    async def refresh_stats(
        self,
        schema: TableSchema,
        config_override: Optional[dict[str, Any]] = None,
    ) -> TableSchema:
        self.context.add_metadata("refresh_table", f"{schema.database}.{schema.table}")

        active_config = self.get_active_config()
        if config_override:
            config_dict = active_config.to_dict()
            config_dict.update(config_override)
            active_config = CrawlStrategyConfig.from_dict(config_dict)

        try:
            async with asyncio.timeout(active_config.timeout_per_table):
                await self._connect()
                samples = await self._fetch_samples(
                    schema.database, schema.table, active_config.sample_size
                )
                return self.stats_collector.collect_table_stats(
                    schema, samples, active_config.sample_size
                )
        except asyncio.TimeoutError:
            raise TimeoutError(
                f"stats_refresh_{schema.database}.{schema.table}",
                active_config.timeout_per_table,
            )
        finally:
            await self._disconnect()

    def _filter_tables(
        self,
        tables: list[str],
        config: CrawlStrategyConfig,
    ) -> list[str]:
        """Filter tables based on configuration."""
        filtered = tables

        if config.include_tables:
            include_set = set(config.include_tables)
            filtered = [t for t in filtered if t in include_set]

        if config.exclude_tables:
            exclude_set = set(config.exclude_tables)
            filtered = [t for t in filtered if t not in exclude_set]

        return filtered

    async def _connect(self) -> None:
        if self._connection is not None:
            return

        try:
            if self.ds_type == "mock":
                self._connection = MockConnection(self.connection)
            elif self.ds_type == "mysql":
                self._connection = await self._connect_mysql()
            elif self.ds_type == "postgresql":
                self._connection = await self._connect_postgresql()
            else:
                self._connection = MockConnection(self.connection)

            self.context.track_resource(self._connection)
        except Exception as e:
            raise SchemaExtractionError(self.ds_name, f"Connection failed: {e}") from e

    async def _disconnect(self) -> None:
        if self._connection is not None:
            try:
                if hasattr(self._connection, "close"):
                    await self._connection.close()
            except Exception:
                pass
            self._connection = None

    async def _connect_mysql(self) -> Any:
        return MockConnection(self.connection)

    async def _connect_postgresql(self) -> Any:
        return MockConnection(self.connection)

    async def _list_tables(self) -> list[str]:
        return await self._connection.list_tables()

    async def _extract_table_schema(
        self,
        table: str,
        database: Optional[str] = None,
        config: Optional[CrawlStrategyConfig] = None,
    ) -> TableSchema:
        if database is None:
            database = self.connection.get("database", "default")

        if config is None:
            config = self.get_active_config()

        raw_schema = await self._connection.get_table_schema(database, table)
        schema = self.schema_extractor.infer_from_samples(raw_schema, database, table)

        if config.collect_stats:
            samples = await self._fetch_samples(database, table, config.sample_size)
            schema = self.stats_collector.collect_table_stats(schema, samples, config.sample_size)

        return schema

    async def _fetch_samples(self, database: str, table: str, limit: int) -> list[dict[str, Any]]:
        return await self._connection.fetch_samples(database, table, limit)


class MockConnection:
    def __init__(self, config: dict[str, Any]):
        self.config = config
        self._mock_tables = config.get("mock_tables", {
            "users": [
                {"id": 1, "name": "Alice", "email": "alice@example.com", "age": 30, "active": True, "created_at": "2024-01-01"},
                {"id": 2, "name": "Bob", "email": "bob@example.com", "age": 25, "active": True, "created_at": "2024-01-02"},
                {"id": 3, "name": "Charlie", "email": "charlie@example.com", "age": 35, "active": False, "created_at": "2024-01-03"},
            ],
            "orders": [
                {"id": 1, "user_id": 1, "amount": 99.99, "status": "completed", "created_at": "2024-01-10"},
                {"id": 2, "user_id": 2, "amount": 149.50, "status": "pending", "created_at": "2024-01-11"},
            ],
        })

    async def list_tables(self) -> list[str]:
        await asyncio.sleep(0.01)
        return list(self._mock_tables.keys())

    async def get_table_schema(self, database: str, table: str) -> list[dict[str, Any]]:
        await asyncio.sleep(0.01)
        if table in self._mock_tables:
            return self._mock_tables[table]
        return []

    async def fetch_samples(self, database: str, table: str, limit: int) -> list[dict[str, Any]]:
        await asyncio.sleep(0.01)
        if table in self._mock_tables:
            return self._mock_tables[table][:limit]
        return []

    async def close(self) -> None:
        pass
