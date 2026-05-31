from __future__ import annotations

import asyncio
import time
from datetime import timedelta

import pytest

from top.domain.scheduling.cache import (
    CacheConfig,
    CacheEntryStatus,
    CacheLevel,
    CacheStats,
    L1CacheBackend,
    L2CacheBackend,
    MultiLevelCache,
    CacheInvalidationStrategy,
)
from top.domain.audit.batch import (
    BatchConfig,
    BatchFlushStrategy,
    BatchPriority,
    BatchingCommandBus,
    BatchCommandStore,
    BatchAuditLogStore,
)
from top.domain.audit.stores import InMemoryCommandStore, InMemoryAuditLogStore
from top.domain.audit.bus import CommandHandler
from top.core.models import CommandRecord, AuditLogEntry
from top.infrastructure.persistence.monitoring import (
    MetricsCollector,
    MonitoredRepository,
    PrometheusExporter,
    RepositoryOperation,
    export_prometheus_metrics,
    get_database_metrics,
    get_metrics_collector,
)


class TestMultiLevelCache:
    @pytest.mark.asyncio
    async def test_l1_cache_basic(self):
        l1 = L1CacheBackend(max_size=100, default_ttl_seconds=60)

        await l1.set("key1", "value1")
        entry = await l1.get("key1")
        assert entry is not None
        assert entry.value == "value1"
        assert entry.hit_count == 1

        entry2 = await l1.get("key1")
        assert entry2.hit_count == 2

        missing = await l1.get("missing")
        assert missing is None

    @pytest.mark.asyncio
    async def test_l1_cache_lru_eviction(self):
        l1 = L1CacheBackend(max_size=3, default_ttl_seconds=60)

        await l1.set("key1", "value1")
        await l1.set("key2", "value2")
        await l1.set("key3", "value3")

        await l1.get("key1")

        await l1.set("key4", "value4")

        assert await l1.get("key2") is None
        assert await l1.get("key1") is not None
        assert await l1.get("key3") is not None
        assert await l1.get("key4") is not None

    @pytest.mark.asyncio
    async def test_l1_cache_ttl(self):
        l1 = L1CacheBackend(max_size=100, default_ttl_seconds=1)

        await l1.set("key1", "value1")
        assert await l1.get("key1") is not None

        await asyncio.sleep(1.1)
        assert await l1.get("key1") is None

    @pytest.mark.asyncio
    async def test_l1_cache_tag_invalidation(self):
        l1 = L1CacheBackend(max_size=100, default_ttl_seconds=60)

        await l1.set("key1", "value1", tags=["group_a", "common"])
        await l1.set("key2", "value2", tags=["group_a", "common"])
        await l1.set("key3", "value3", tags=["group_b", "common"])

        deleted = await l1.delete_by_tags(["group_a"])
        assert deleted == 2

        assert await l1.get("key1") is None
        assert await l1.get("key2") is None
        assert await l1.get("key3") is not None

    @pytest.mark.asyncio
    async def test_l2_cache_basic(self):
        l2 = L2CacheBackend(default_ttl_seconds=60)

        await l2.set("key1", {"data": "test"})
        entry = await l2.get("key1")
        assert entry is not None
        assert entry.value == {"data": "test"}

    @pytest.mark.asyncio
    async def test_multi_level_cache_hierarchy(self):
        cache = MultiLevelCache(CacheConfig(
            l1_max_size=10,
            l1_ttl_seconds=60,
            l2_ttl_seconds=300,
        ))

        await cache.set("key1", "value1")

        value = await cache.get("key1")
        assert value == "value1"
        assert cache.stats.l1_hits == 1

        value2 = await cache.get("key1")
        assert value2 == "value1"
        assert cache.stats.l1_hits == 2
        assert cache.stats.l2_misses == 0

    @pytest.mark.asyncio
    async def test_multi_level_cache_l1_miss_l2_hit(self):
        cache = MultiLevelCache(CacheConfig(
            l1_max_size=2,
            l1_ttl_seconds=60,
            l2_ttl_seconds=300,
        ))

        await cache.set("key1", "value1")
        await cache.set("key2", "value2")
        await cache.set("key3", "value3")

        assert await cache.get("key1") == "value1"
        assert cache.stats.l1_misses == 1
        assert cache.stats.l2_hits == 1
        assert cache.stats.l1_hits == 2

    @pytest.mark.asyncio
    async def test_cache_get_or_load(self):
        cache = MultiLevelCache(CacheConfig(
            l1_max_size=10,
            l1_ttl_seconds=60,
            l2_ttl_seconds=300,
        ))

        call_count = 0

        def loader():
            nonlocal call_count
            call_count += 1
            return f"loaded_value_{call_count}"

        result1 = await cache.get_or_load("key", loader)
        assert result1 == "loaded_value_1"
        assert call_count == 1

        result2 = await cache.get_or_load("key", loader)
        assert result2 == "loaded_value_1"
        assert call_count == 1

    @pytest.mark.asyncio
    async def test_cache_warm_up(self):
        cache = MultiLevelCache(CacheConfig(
            l1_max_size=5,
            l1_ttl_seconds=60,
            l2_ttl_seconds=300,
        ))

        entries = {
            f"key_{i}": (f"value_{i}", 60)
            for i in range(10)
        }

        await cache.warm_up(entries)

        assert cache.is_warmed_up
        assert cache.stats.warm_up_count == 1

        for i in range(10):
            value = await cache.get(f"key_{i}")
            assert value == f"value_{i}"

    @pytest.mark.asyncio
    async def test_cache_invalidation(self):
        cache = MultiLevelCache(CacheConfig(
            l1_max_size=10,
            l1_ttl_seconds=60,
            l2_ttl_seconds=300,
        ))

        await cache.set("key1", "value1")
        await cache.set("key2", "value2")
        await cache.set("key3", "value3", tags=["tag1", "tag2"])

        deleted = await cache.invalidate(["key1", "key2"])
        assert deleted == 2

        assert await cache.get("key1") is None
        assert await cache.get("key2") is None
        assert await cache.get("key3") == "value3"

        await cache.set("key4", "value4", tags=["tag1", "tag3"])

        deleted_tags = await cache.invalidate_by_tags(["tag1"])
        assert deleted_tags >= 1

    @pytest.mark.asyncio
    async def test_cache_stats(self):
        cache = MultiLevelCache(CacheConfig(
            l1_max_size=10,
            l1_ttl_seconds=60,
            l2_ttl_seconds=300,
        ))

        await cache.set("key1", "value1")

        for _ in range(10):
            await cache.get("key1")

        await cache.get("missing")

        stats = await cache.get_stats()

        assert stats["stats"]["l1_hits"] >= 10
        assert stats["stats"]["l1_misses"] == 1
        assert stats["stats"]["l2_misses"] == 1
        assert "l1_hit_rate" in stats["stats"]
        assert "overall_hit_rate" in stats["stats"]

    def test_cache_level_enum(self):
        assert CacheLevel.L1.value == "L1"
        assert CacheLevel.L2.value == "L2"

    def test_cache_invalidation_strategy_enum(self):
        strategies = list(CacheInvalidationStrategy)
        assert CacheInvalidationStrategy.TIME_BASED in strategies
        assert CacheInvalidationStrategy.WRITE_THROUGH in strategies
        assert CacheInvalidationStrategy.WRITE_BACK in strategies
        assert CacheInvalidationStrategy.EVENT_DRIVEN in strategies


class TestBatchingCommandBus:
    @pytest.mark.asyncio
    async def test_send_batch_basic(self):
        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=100,
                strategy=BatchFlushStrategy.MANUAL,
            )
        )

        commands = [
            ("create_user", {"name": "Alice"}),
            ("create_user", {"name": "Bob"}),
            ("update_user", {"id": 1, "name": "Alicia"}),
        ]

        result = await bus.send_batch(commands)

        assert result.total_items == 3
        assert result.successful == 3
        assert result.failed == 0
        assert result.success_rate == 1.0
        assert result.network_round_trips > 0

    @pytest.mark.asyncio
    async def test_send_batch_with_handlers(self):
        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=100,
                strategy=BatchFlushStrategy.MANUAL,
            )
        )

        handler_calls = []

        async def handler(cmd: CommandRecord):
            handler_calls.append(cmd)
            return {"processed": True}

        bus.register_handler("process_order", handler)

        commands = [
            ("process_order", {"order_id": 1}),
            ("process_order", {"order_id": 2}),
        ]

        result = await bus.send_batch(commands)

        assert result.total_items == 2
        assert result.successful == 2
        assert len(handler_calls) == 2
        assert handler_calls[0].payload == {"order_id": 1}
        assert handler_calls[1].payload == {"order_id": 2}

    @pytest.mark.asyncio
    async def test_send_batch_with_command_handler_instance(self):
        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=100,
                strategy=BatchFlushStrategy.MANUAL,
            )
        )

        class MyHandler(CommandHandler):
            def __init__(self):
                self.calls = []

            async def handle(self, command: CommandRecord) -> dict:
                self.calls.append(command)
                return {"handled": True}

        handler = MyHandler()
        bus.register_handler("my_command", handler)

        commands = [
            ("my_command", {"data": "test1"}),
            ("my_command", {"data": "test2"}),
        ]

        result = await bus.send_batch(commands)

        assert result.total_items == 2
        assert result.successful == 2
        assert len(handler.calls) == 2

    @pytest.mark.asyncio
    async def test_send_queued_and_flush(self):
        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=5,
                flush_interval_ms=100,
                strategy=BatchFlushStrategy.HYBRID,
            )
        )

        cmd_ids = []
        for i in range(10):
            cmd_id = await bus.send_queued(
                command_type=f"test_{i}",
                payload={"index": i},
                priority=BatchPriority.NORMAL,
            )
            cmd_ids.append(cmd_id)

        queue_sizes = bus.queue_size()
        assert sum(queue_sizes.values()) == 10

        flushed = await bus.flush()
        assert flushed == 10
        assert bus.total_items_processed == 10
        assert bus.total_batches_processed == 1

        queue_sizes_after = bus.queue_size()
        assert sum(queue_sizes_after.values()) == 0

    @pytest.mark.asyncio
    async def test_batch_priority(self):
        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=100,
                strategy=BatchFlushStrategy.MANUAL,
            )
        )

        normal_result = await bus.send_batch(
            [("normal_task", {"value": 1})],
            priority=BatchPriority.NORMAL,
        )
        assert normal_result.successful == 1

        high_result = await bus.send_batch(
            [("high_task", {"value": 2})],
            priority=BatchPriority.HIGH,
        )
        assert high_result.successful == 1

        critical_result = await bus.send_batch(
            [("critical_task", {"value": 3})],
            priority=BatchPriority.CRITICAL,
        )
        assert critical_result.successful == 1

    @pytest.mark.asyncio
    async def test_batch_processor_auto_flush(self):
        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=3,
                flush_interval_ms=50,
                strategy=BatchFlushStrategy.SIZE_BASED,
            )
        )

        await bus.start_batcher()

        for i in range(5):
            await bus.send_queued("task", {"i": i})

        await asyncio.sleep(0.2)

        assert bus.total_batches_processed >= 1
        assert bus.total_items_processed == 5

        await bus.stop_batcher()

    @pytest.mark.asyncio
    async def test_batch_stats(self):
        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=10,
                flush_interval_ms=100,
                strategy=BatchFlushStrategy.MANUAL,
            )
        )

        await bus.send_batch([
            ("task1", {}),
            ("task2", {}),
        ])

        stats = bus.get_stats()

        assert stats["total_items_processed"] == 2
        assert stats["total_batches_processed"] == 1
        assert "config" in stats
        assert "queues" in stats
        assert "network_round_trips_saved" in stats

    @pytest.mark.asyncio
    async def test_batch_retry_on_failure(self):
        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=10,
                strategy=BatchFlushStrategy.MANUAL,
                retry_on_failure=True,
                max_retries=2,
                retry_delay_ms=50,
            )
        )

        call_count = 0

        async def failing_handler(cmd: CommandRecord):
            nonlocal call_count
            call_count += 1
            if call_count < 2:
                raise ValueError("Transient error")
            return {"success": True}

        bus.register_handler("failing_task", failing_handler)

        result = await bus.send_batch([
            ("failing_task", {}),
        ])

        assert result.total_items == 1
        assert result.successful == 1
        assert call_count == 2


class TestDatabaseMonitoring:
    @pytest.mark.asyncio
    async def test_metrics_collector_basic(self):
        collector = MetricsCollector(
            slow_query_threshold_ms=100,
            long_running_threshold_ms=500,
        )

        query_id = collector.start_query(
            repository="user_repo",
            operation=RepositoryOperation.READ,
            query="SELECT * FROM users",
        )

        assert collector.get_active_query_count() == 1

        await collector.record_query(query_id, success=True)

        assert collector.get_active_query_count() == 0

    @pytest.mark.asyncio
    async def test_metrics_collector_stats(self):
        collector = MetricsCollector(
            slow_query_threshold_ms=50,
            long_running_threshold_ms=200,
        )

        for i in range(10):
            qid = collector.start_query("user_repo", RepositoryOperation.READ)
            await asyncio.sleep(0.01)
            await collector.record_query(qid, success=True)

        qid_fail = collector.start_query("user_repo", RepositoryOperation.DELETE)
        await collector.record_query(qid_fail, success=False)

        stats = collector.get_all_stats()

        assert stats["summary"]["total_queries"] == 11
        assert stats["summary"]["total_failed"] == 1
        assert stats["summary"]["active_queries"] == 0

        user_stats = collector.get_repository_stats("user_repo")
        assert len(user_stats) == 2

    def test_query_latency_percentiles(self):
        from top.infrastructure.persistence.monitoring import QueryStats

        stats = QueryStats(
            repository="test",
            operation=RepositoryOperation.READ,
        )

        for latency in [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]:
            stats.record(latency, success=True, slow_threshold=100)

        latency = stats.calculate_latency_percentiles()

        assert latency.avg_ms == 55.0
        assert latency.min_ms == 10
        assert latency.max_ms == 100
        assert latency.p50_ms >= 40
        assert latency.p50_ms <= 60
        assert latency.p95_ms >= 80
        assert latency.p99_ms >= 90

    @pytest.mark.asyncio
    async def test_long_running_query_detection(self):
        collector = MetricsCollector(
            slow_query_threshold_ms=100,
            long_running_threshold_ms=100,
        )

        qid = collector.start_query("slow_repo", RepositoryOperation.QUERY)

        await asyncio.sleep(0.15)

        long_running = collector.get_long_running_queries()
        assert len(long_running) == 1
        assert long_running[0].repository == "slow_repo"
        assert long_running[0].elapsed_ms >= 100

        await collector.record_query(qid, success=True)

    @pytest.mark.asyncio
    async def test_monitored_repository_context_manager(self):
        collector = MetricsCollector()
        monitored = MonitoredRepository("test_repo", collector)

        async with monitored.measure(RepositoryOperation.CREATE) as (qid, callback):
            assert qid is not None
            assert collector.get_active_query_count() == 1

        assert collector.get_active_query_count() == 0

        stats = collector.get_repository_stats("test_repo")
        assert len(stats) == 1
        assert stats[0].total_queries == 1
        assert stats[0].failed_queries == 0

    @pytest.mark.asyncio
    async def test_monitored_repository_exception_handling(self):
        collector = MetricsCollector()
        monitored = MonitoredRepository("error_repo", collector)

        with pytest.raises(ValueError):
            async with monitored.measure(RepositoryOperation.UPDATE):
                raise ValueError("Test error")

        stats = collector.get_repository_stats("error_repo")
        assert len(stats) == 1
        assert stats[0].total_queries == 1
        assert stats[0].failed_queries == 1

    def test_prometheus_exporter(self):
        collector = MetricsCollector()
        exporter = PrometheusExporter(collector, prefix="myprefix")

        qid1 = collector.start_query("user", RepositoryOperation.READ)
        asyncio.run(collector.record_query(qid1, success=True))

        qid2 = collector.start_query("user", RepositoryOperation.READ)
        asyncio.run(collector.record_query(qid2, success=True))

        qid3 = collector.start_query("user", RepositoryOperation.CREATE)
        asyncio.run(collector.record_query(qid3, success=False))

        metrics = exporter.export()

        assert "myprefix_db_queries_total" in metrics
        assert "myprefix_db_health" in metrics
        assert "myprefix_db_repository_queries_total" in metrics
        assert 'repository="user"' in metrics

    def test_export_prometheus_metrics_function(self):
        from top.infrastructure.persistence.monitoring import set_metrics_collector
        set_metrics_collector(MetricsCollector())

        metrics = export_prometheus_metrics()
        assert "top_db_health" in metrics
        assert "top_db_pool_connections" in metrics

    def test_get_database_metrics_function(self):
        from top.infrastructure.persistence.monitoring import set_metrics_collector
        set_metrics_collector(MetricsCollector())

        metrics = get_database_metrics()
        assert "summary" in metrics
        assert "health" in metrics
        assert "by_repository" in metrics

    def test_health_status_update(self):
        collector = MetricsCollector()

        collector.update_health_status(
            healthy=True,
            response_time_ms=5.5,
            available_connections=8,
            total_connections=10,
        )

        stats = collector.get_all_stats()
        assert stats["health"]["healthy"] == True
        assert stats["health"]["available_connections"] == 8
        assert stats["health"]["total_connections"] == 10

        collector.update_health_status(
            healthy=False,
            response_time_ms=1000.0,
            available_connections=0,
            total_connections=10,
            error_message="Connection timeout",
        )

        stats2 = collector.get_all_stats()
        assert stats2["health"]["healthy"] == False
        assert stats2["health"]["error_message"] == "Connection timeout"


class TestFeatureIntegration:
    @pytest.mark.asyncio
    async def test_cache_with_commands(self):
        cache = MultiLevelCache(CacheConfig(
            l1_max_size=100,
            l1_ttl_seconds=60,
            l2_ttl_seconds=300,
        ))

        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=10,
                strategy=BatchFlushStrategy.MANUAL,
            )
        )

        cached_result = None

        async def handler(cmd: CommandRecord):
            nonlocal cached_result
            cached_result = await cache.get_or_load(
                key=f"cmd_{cmd.command_type}",
                loader=lambda: {"cached": True, "type": cmd.command_type},
            )
            return cached_result

        bus.register_handler("cached_command", handler)

        result = await bus.send_batch([
            ("cached_command", {}),
        ])

        assert result.successful == 1
        assert cached_result is not None
        assert cached_result["cached"] == True

        cached_value = await cache.get("cmd_cached_command")
        assert cached_value is not None
        assert cached_value["type"] == "cached_command"

    @pytest.mark.asyncio
    async def test_batch_with_monitoring(self):
        collector = MetricsCollector()
        monitored = MonitoredRepository("audit_repo", collector)

        bus = BatchingCommandBus(
            batch_config=BatchConfig(
                max_batch_size=10,
                strategy=BatchFlushStrategy.MANUAL,
            )
        )

        async def handler(cmd: CommandRecord):
            async with monitored.measure(RepositoryOperation.CREATE):
                await asyncio.sleep(0.01)
                return {"stored": True}

        bus.register_handler("store_audit", handler)

        result = await bus.send_batch([
            ("store_audit", {"action": "login"}),
            ("store_audit", {"action": "logout"}),
        ])

        assert result.total_items == 2
        assert result.successful == 2

        repo_stats = collector.get_repository_stats("audit_repo")
        assert len(repo_stats) == 1
        assert repo_stats[0].total_queries == 2
        assert repo_stats[0].success_rate == 1.0
