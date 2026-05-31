#!/usr/bin/env python3

import asyncio
import sys

sys.path.insert(0, '.')

from top.domain.scheduling.cache import (
    MultiLevelCache, CacheConfig, CacheLevel,
    CacheInvalidationStrategy, CacheStats,
)
from top.domain.audit.batch import (
    BatchingCommandBus, BatchConfig, BatchPriority,
    BatchFlushStrategy, BatchResult,
)
from top.infrastructure.persistence.monitoring import (
    MetricsCollector, PrometheusExporter,
    RepositoryOperation, export_prometheus_metrics,
)

print("=== All imports successful ===")

async def test_cache():
    print("\n1. Testing MultiLevelCache...")
    
    cache = MultiLevelCache(CacheConfig(
        l1_max_size=10,
        l1_ttl_seconds=60,
        l2_ttl_seconds=300,
    ))
    
    await cache.set("key1", "value1")
    v = await cache.get("key1")
    assert v == "value1", f"Expected 'value1', got {v}"
    print("   ✅ Basic set/get works")
    
    await cache.set("key2", "value2", tags=["tag1", "tag2"])
    await cache.set("key3", "value3", tags=["tag1"])
    
    deleted = await cache.invalidate_by_tags(["tag1"])
    assert deleted >= 1
    print("   ✅ Tag-based invalidation works")
    
    await cache.warm_up({
        "warm_key1": ("warm_val1", 60),
        "warm_key2": ("warm_val2", 60),
    })
    assert cache.is_warmed_up
    print("   ✅ Cache warm-up works")
    
    stats = await cache.get_stats()
    assert "l1_hits" in stats["stats"]
    print("   ✅ Cache stats available")
    
    print("   ✅ All cache tests passed!")

async def test_batching():
    print("\n2. Testing BatchingCommandBus...")
    
    bus = BatchingCommandBus(
        batch_config=BatchConfig(
            max_batch_size=10,
            strategy=BatchFlushStrategy.MANUAL,
        )
    )
    
    handler_calls = []
    async def handler(cmd):
        handler_calls.append(cmd)
        return {"ok": True}
    
    bus.register_handler("test_cmd", handler)
    
    commands = [
        ("test_cmd", {"id": 1}),
        ("test_cmd", {"id": 2}),
        ("test_cmd", {"id": 3}),
    ]
    
    result = await bus.send_batch(commands)
    assert result.total_items == 3
    assert result.successful == 3
    assert len(handler_calls) == 3
    print("   ✅ Batch execution works")
    
    for i in range(5):
        cmd_id = await bus.send_queued("queued_task", {"index": i})
    
    assert sum(bus.queue_size().values()) == 5
    print("   ✅ Queue operations work")
    
    flushed = await bus.flush()
    assert flushed == 5
    assert bus.total_items_processed == 8
    print("   ✅ Manual flush works")
    
    stats = bus.get_stats()
    assert stats["total_batches_processed"] == 2
    print("   ✅ Batch stats available")
    
    print("   ✅ All batching tests passed!")

def test_monitoring():
    print("\n3. Testing MetricsCollector & Prometheus...")
    
    collector = MetricsCollector(
        slow_query_threshold_ms=100,
        long_running_threshold_ms=500,
    )
    
    async def run_queries():
        for i in range(5):
            qid = collector.start_query(
                repository="user_repo",
                operation=RepositoryOperation.READ,
            )
            await asyncio.sleep(0.01)
            await collector.record_query(qid, success=True)
    
    asyncio.run(run_queries())
    
    qid_fail = collector.start_query(
        repository="user_repo",
        operation=RepositoryOperation.DELETE,
    )
    asyncio.run(collector.record_query(qid_fail, success=False))
    
    stats = collector.get_all_stats()
    assert stats["summary"]["total_queries"] == 6
    assert stats["summary"]["total_failed"] == 1
    print("   ✅ Query stats collected")
    
    exporter = PrometheusExporter(collector, prefix="myprefix")
    metrics = exporter.export()
    assert "myprefix_db_queries_total" in metrics
    assert "myprefix_db_health" in metrics
    assert 'repository="user_repo"' in metrics
    print("   ✅ Prometheus format exported")
    
    from top.infrastructure.persistence.monitoring import set_metrics_collector
    set_metrics_collector(collector)
    metrics2 = export_prometheus_metrics()
    assert "top_db_health" in metrics2
    print("   ✅ Global export function works")
    
    print("   ✅ All monitoring tests passed!")

def main():
    print("\n" + "="*50)
    print("Running all new feature tests")
    print("="*50)
    
    asyncio.run(test_cache())
    asyncio.run(test_batching())
    test_monitoring()
    
    print("\n" + "="*50)
    print("✅ ALL TESTS PASSED!")
    print("="*50)

if __name__ == "__main__":
    main()
