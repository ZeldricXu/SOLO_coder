from __future__ import annotations

import asyncio
import pytest

from streamsql.modules.metadata_crawler import (
    BatchStrategy,
    CrawlStrategyConfig,
    DynamicConfigManager,
    MetadataCrawler,
    PriorityStrategy,
    ScanMode,
    SequentialStrategy,
    StrategyRegistry,
    ThrottledStrategy,
    get_global_config_manager,
)


class TestCrawlStrategyConfig:
    def test_default_config(self):
        config = CrawlStrategyConfig()
        assert config.scan_mode == ScanMode.FULL
        assert config.sample_size == 100
        assert config.timeout_total == 300

    def test_config_to_dict_and_back(self):
        config = CrawlStrategyConfig(
            sample_size=200,
            timeout_total=600,
            include_tables=["users", "orders"],
        )
        config_dict = config.to_dict()
        restored = CrawlStrategyConfig.from_dict(config_dict)
        assert restored.sample_size == 200
        assert restored.timeout_total == 600
        assert restored.include_tables == ["users", "orders"]

    def test_config_clone(self):
        config = CrawlStrategyConfig(sample_size=150)
        cloned = config.clone()
        cloned.sample_size = 200
        assert config.sample_size == 150
        assert cloned.sample_size == 200


class TestStrategies:
    def test_strategy_registry_has_builtin_strategies(self):
        names = StrategyRegistry.list_names()
        assert "sequential" in names
        assert "batch" in names
        assert "priority" in names
        assert "throttled" in names

    def test_get_strategy_by_name(self):
        strategy = StrategyRegistry.get("sequential")
        assert strategy is not None
        assert strategy.name == "sequential"

    @pytest.mark.asyncio
    async def test_sequential_strategy(self):
        strategy = SequentialStrategy()
        tables = ["table1", "table2", "table3"]
        extracted = []

        async def extract_fn(table, config):
            extracted.append(table)
            return {"table": table}

        schemas, failed = await strategy.execute(tables, extract_fn, CrawlStrategyConfig())
        assert len(schemas) == 3
        assert len(failed) == 0
        assert extracted == tables

    @pytest.mark.asyncio
    async def test_batch_strategy(self):
        strategy = BatchStrategy()
        tables = [f"table{i}" for i in range(25)]
        config = CrawlStrategyConfig(max_tables_per_batch=10)

        async def extract_fn(table, cfg):
            return {"table": table}

        schemas, failed = await strategy.execute(tables, extract_fn, config)
        assert len(schemas) == 25
        assert len(failed) == 0

    @pytest.mark.asyncio
    async def test_priority_strategy(self):
        priority_tables = ["important_table", "critical_table"]
        strategy = PriorityStrategy(priority_tables=priority_tables)
        tables = ["normal_table", "important_table", "critical_table", "other_table"]
        order = []

        async def extract_fn(table, config):
            order.append(table)
            return {"table": table}

        await strategy.execute(tables, extract_fn, CrawlStrategyConfig())
        assert order[0] == "important_table"
        assert order[1] == "critical_table"


class TestDynamicConfigManager:
    def test_singleton(self):
        mgr1 = get_global_config_manager()
        mgr2 = get_global_config_manager()
        assert mgr1 is mgr2

    def test_set_and_get_config(self):
        mgr = DynamicConfigManager()
        config = CrawlStrategyConfig(sample_size=500)
        event = mgr.set_config("test_ds", config)
        assert event.datasource == "test_ds"
        assert event.new_config.sample_size == 500

        retrieved = mgr.get_config("test_ds")
        assert retrieved.sample_size == 500

    def test_update_config(self):
        mgr = DynamicConfigManager()
        mgr.set_config("test_ds", CrawlStrategyConfig(sample_size=100))
        event = mgr.update_config("test_ds", {"sample_size": 300, "timeout_total": 120})
        assert event.new_config.sample_size == 300
        assert event.new_config.timeout_total == 120

    def test_config_version_history(self):
        mgr = DynamicConfigManager()
        mgr.set_config("test_ds", CrawlStrategyConfig(sample_size=100))
        mgr.update_config("test_ds", {"sample_size": 200})
        mgr.update_config("test_ds", {"sample_size": 300})

        history = mgr.get_version_history("test_ds")
        assert len(history) == 3
        assert history[0].sample_size == 100
        assert history[1].sample_size == 200
        assert history[2].sample_size == 300

    def test_rollback(self):
        mgr = DynamicConfigManager()
        mgr.set_config("test_ds", CrawlStrategyConfig(sample_size=100))
        mgr.update_config("test_ds", {"sample_size": 200})
        mgr.update_config("test_ds", {"sample_size": 300})

        event = mgr.rollback("test_ds", versions=1)
        assert event is not None
        assert event.new_config.sample_size == 200

    def test_callback_on_config_update(self):
        mgr = DynamicConfigManager()
        callback_called = []

        def callback(event):
            callback_called.append(event)

        mgr.register_callback(callback)
        mgr.set_config("test_ds", CrawlStrategyConfig(sample_size=100))

        assert len(callback_called) == 1
        assert callback_called[0].datasource == "test_ds"

    def test_unregister_callback(self):
        mgr = DynamicConfigManager()
        callback_called = []

        def callback(event):
            callback_called.append(event)

        mgr.register_callback(callback)
        mgr.unregister_callback(callback)
        mgr.set_config("test_ds", CrawlStrategyConfig(sample_size=100))

        assert len(callback_called) == 0

    def test_import_export_config(self):
        mgr = DynamicConfigManager()
        config = CrawlStrategyConfig(
            sample_size=123,
            include_tables=["a", "b"],
            exclude_tables=["c"],
        )
        mgr.set_config("test_ds", config)

        exported = mgr.export_config("test_ds")
        assert exported["sample_size"] == 123
        assert exported["include_tables"] == ["a", "b"]

        new_mgr = DynamicConfigManager()
        new_mgr.import_config("test_ds2", exported)
        imported = new_mgr.get_config("test_ds2")
        assert imported.sample_size == 123
        assert imported.include_tables == ["a", "b"]

    def test_reset_config(self):
        mgr = DynamicConfigManager()
        mgr.set_config("test_ds", CrawlStrategyConfig(sample_size=500))
        event = mgr.reset_config("test_ds")
        assert event.new_config.sample_size == 100


class TestMetadataCrawlerEnhanced:
    @pytest.fixture
    def crawler(self):
        return MetadataCrawler(
            datasource={"name": "test_db", "type": "mock"},
        )

    def test_default_strategy(self, crawler):
        assert crawler.strategy is not None
        assert crawler.strategy.name == "sequential"

    def test_set_strategy(self, crawler):
        batch_strategy = BatchStrategy()
        crawler.set_strategy(batch_strategy)
        assert crawler.strategy.name == "batch"

    def test_set_strategy_by_name(self, crawler):
        result = crawler.set_strategy_by_name("batch")
        assert result is True
        assert crawler.strategy.name == "batch"

    def test_set_strategy_by_name_invalid(self, crawler):
        result = crawler.set_strategy_by_name("invalid_strategy")
        assert result is False

    def test_get_active_config(self, crawler):
        config = crawler.get_active_config()
        assert config is not None
        assert isinstance(config, CrawlStrategyConfig)

    def test_update_config(self, crawler):
        event = crawler.update_config({"sample_size": 500})
        assert event.new_config.sample_size == 500
        assert crawler.sample_size == 500

    def test_sample_size_property(self, crawler):
        crawler.sample_size = 250
        assert crawler.sample_size == 250

    def test_timeout_property(self, crawler):
        crawler.timeout = 120
        assert crawler.timeout == 120

    @pytest.mark.asyncio
    async def test_scan_with_config_override(self, crawler):
        result = await crawler.scan(
            config_override={"sample_size": 10, "max_tables_per_batch": 1}
        )
        assert result is not None
        assert len(result.tables) >= 1

    @pytest.mark.asyncio
    async def test_scan_with_custom_strategy(self, crawler):
        batch_strategy = BatchStrategy()
        result = await crawler.scan(strategy=batch_strategy)
        assert result is not None
        assert len(result.tables) >= 1
