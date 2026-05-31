from __future__ import annotations

import asyncio
import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.common.exceptions import CacheError, TimeoutError
from src.data_access.cache import (
    CacheEntry,
    CacheManager,
    InvalidationManager,
    LFUCache,
    LRUCache,
    TTLCache,
)
from src.data_access.data_source import (
    CircuitBreaker,
    DataSource,
    DataSourceConfig,
    DataSourceManager,
)


# =============================================================================
# CacheEntry Tests
# =============================================================================
class TestCacheEntry:
    def test_entry_without_ttl_never_expires(self):
        entry = CacheEntry(value="test_value")
        assert not entry.is_expired()
        assert entry.access_count == 0
        assert entry.value == "test_value"

    def test_entry_with_ttl_expires_correctly(self):
        entry = CacheEntry(value="test_value", ttl=0.1)
        assert not entry.is_expired()
        time.sleep(0.15)
        assert entry.is_expired()

    def test_entry_access_tracking(self):
        entry = CacheEntry(value="test_value")
        assert entry.access_count == 0
        entry.access_count += 1
        assert entry.access_count == 1


# =============================================================================
# LRUCache Tests - Normal Flow
# =============================================================================
class TestLRUCacheNormalFlow:
    def test_basic_set_and_get(self):
        cache = LRUCache[str, str](capacity=10)
        cache.set("key1", "value1")
        assert cache.get("key1") == "value1"

    def test_set_overwrites_existing_key(self):
        cache = LRUCache[str, str](capacity=10)
        cache.set("key1", "value1")
        cache.set("key1", "value2")
        assert cache.get("key1") == "value2"

    def test_get_nonexistent_key_returns_none(self):
        cache = LRUCache[str, str](capacity=10)
        assert cache.get("nonexistent") is None

    def test_has_checks_key_existence(self):
        cache = LRUCache[str, str](capacity=10)
        cache.set("key1", "value1")
        assert cache.has("key1") is True
        assert cache.has("key2") is False

    def test_delete_removes_key(self):
        cache = LRUCache[str, str](capacity=10)
        cache.set("key1", "value1")
        assert cache.delete("key1") is True
        assert cache.get("key1") is None
        assert cache.delete("key1") is False

    def test_clear_removes_all_keys(self):
        cache = LRUCache[str, str](capacity=10)
        cache.set("key1", "value1")
        cache.set("key2", "value2")
        cache.clear()
        assert cache.get("key1") is None
        assert cache.get("key2") is None


# =============================================================================
# LRUCache Tests - Boundary Values
# =============================================================================
class TestLRUCacheBoundary:
    def test_capacity_zero_evicts_immediately(self):
        cache = LRUCache[str, str](capacity=0)
        with pytest.raises(KeyError):
            cache.set("key1", "value1")

    def test_capacity_one_evicts_oldest(self):
        cache = LRUCache[str, str](capacity=1)
        cache.set("key1", "value1")
        cache.set("key2", "value2")
        assert cache.get("key1") is None
        assert cache.get("key2") == "value2"

    def test_eviction_removes_least_recently_used(self):
        cache = LRUCache[str, str](capacity=3)
        cache.set("key1", "value1")
        cache.set("key2", "value2")
        cache.set("key3", "value3")
        cache.get("key1")
        cache.set("key4", "value4")
        assert cache.get("key1") == "value1"
        assert cache.get("key2") is None
        assert cache.get("key3") == "value3"
        assert cache.get("key4") == "value4"

    def test_large_number_of_entries(self):
        cache = LRUCache[int, int](capacity=1000)
        for i in range(2000):
            cache.set(i, i * 2)
        assert cache.get(0) is None
        assert cache.get(1999) == 3998

    def test_empty_string_key(self):
        cache = LRUCache[str, str](capacity=10)
        cache.set("", "empty_value")
        assert cache.get("") == "empty_value"

    def test_none_value(self):
        cache = LRUCache[str, object](capacity=10)
        cache.set("key_none", None)
        assert cache.get("key_none") is None
        assert cache.has("key_none") is True


# =============================================================================
# LRUCache Tests - TTL Expiration
# =============================================================================
class TestLRUCacheTTL:
    def test_ttl_expiration_removes_entry(self):
        cache = LRUCache[str, str](capacity=10, default_ttl=0.1)
        cache.set("key1", "value1")
        assert cache.get("key1") == "value1"
        time.sleep(0.15)
        assert cache.get("key1") is None

    def test_custom_ttl_overrides_default(self):
        cache = LRUCache[str, str](capacity=10, default_ttl=0.1)
        cache.set("key1", "value1", ttl=0.5)
        time.sleep(0.15)
        assert cache.get("key1") == "value1"

    def test_ttl_cleanup_on_set(self):
        cache = LRUCache[str, str](capacity=10, default_ttl=0.1)
        cache.set("key1", "value1")
        time.sleep(0.15)
        cache.set("key2", "value2")
        assert cache.get("key1") is None


# =============================================================================
# LFUCache Tests
# =============================================================================
class TestLFUCacheNormalFlow:
    def test_basic_set_and_get(self):
        cache = LFUCache[str, str](capacity=10)
        cache.set("key1", "value1")
        assert cache.get("key1") == "value1"

    def test_get_increment_access_count(self):
        cache = LFUCache[str, str](capacity=10)
        cache.set("key1", "value1")
        cache.get("key1")
        cache.get("key1")
        assert cache.get("key1") == "value1"

    def test_set_updates_existing_entry(self):
        cache = LFUCache[str, str](capacity=10)
        cache.set("key1", "value1")
        cache.get("key1")
        cache.set("key1", "value2")
        assert cache.get("key1") == "value2"

    def test_delete_and_clear(self):
        cache = LFUCache[str, str](capacity=10)
        cache.set("key1", "value1")
        assert cache.delete("key1") is True
        assert cache.delete("nonexistent") is False
        cache.set("key2", "value2")
        cache.clear()
        assert cache.get("key2") is None


class TestLFUCacheBoundary:
    def test_lfu_eviction_removes_least_frequently_used(self):
        cache = LFUCache[str, str](capacity=3)
        cache.set("key1", "value1")
        cache.set("key2", "value2")
        cache.set("key3", "value3")
        cache.get("key1")
        cache.get("key1")
        cache.get("key2")
        cache.set("key4", "value4")
        assert cache.get("key3") is None
        assert cache.get("key1") == "value1"
        assert cache.get("key2") == "value2"

    def test_lfu_tie_uses_last_accessed(self):
        cache = LFUCache[str, str](capacity=2)
        cache.set("key1", "value1")
        cache.set("key2", "value2")
        time.sleep(0.01)
        cache.get("key1")
        cache.get("key2")
        cache.set("key3", "value3")
        assert cache.get("key1") is None
        assert cache.get("key2") == "value2"

    def test_capacity_one(self):
        cache = LFUCache[str, str](capacity=1)
        cache.set("key1", "value1")
        cache.set("key2", "value2")
        assert cache.get("key1") is None
        assert cache.get("key2") == "value2"

    def test_ttl_expiration(self):
        cache = LFUCache[str, str](capacity=10, default_ttl=0.1)
        cache.set("key1", "value1")
        time.sleep(0.15)
        assert cache.get("key1") is None


# =============================================================================
# TTLCache Tests
# =============================================================================
class TestTTLCacheNormalFlow:
    def test_basic_set_and_get(self):
        cache = TTLCache[str, str](default_ttl=300)
        cache.set("key1", "value1")
        assert cache.get("key1") == "value1"

    def test_custom_ttl_per_entry(self):
        cache = TTLCache[str, str](default_ttl=300)
        cache.set("key1", "value1", ttl=0.1)
        cache.set("key2", "value2", ttl=10)
        time.sleep(0.15)
        assert cache.get("key1") is None
        assert cache.get("key2") == "value2"

    def test_delete_and_clear(self):
        cache = TTLCache[str, str](default_ttl=300)
        cache.set("key1", "value1")
        assert cache.delete("key1") is True
        assert cache.delete("nonexistent") is False
        cache.set("key2", "value2")
        cache.clear()
        assert cache.get("key2") is None

    def test_has_method(self):
        cache = TTLCache[str, str](default_ttl=300)
        cache.set("key1", "value1", ttl=0.1)
        assert cache.has("key1") is True
        time.sleep(0.15)
        assert cache.has("key1") is False


class TestTTLCacheBoundary:
    def test_ttl_cleanup_evicts_oldest_when_full(self):
        cache = TTLCache[str, str](default_ttl=300, capacity=3)
        cache.set("key1", "value1")
        time.sleep(0.01)
        cache.set("key2", "value2")
        time.sleep(0.01)
        cache.set("key3", "value3")
        cache.set("key4", "value4")
        assert cache.get("key1") is None
        assert cache.get("key4") == "value4"

    def test_very_short_ttl(self):
        cache = TTLCache[str, str](default_ttl=0.01)
        cache.set("key1", "value1")
        time.sleep(0.05)
        assert cache.get("key1") is None

    def test_large_capacity(self):
        cache = TTLCache[int, int](default_ttl=300, capacity=10000)
        for i in range(10000):
            cache.set(i, i)
        cache.set(10001, 10001)
        assert cache.get(0) is None
        assert cache.get(10001) == 10001


# =============================================================================
# CacheManager Tests
# =============================================================================
class TestCacheManagerNormalFlow:
    @pytest.mark.asyncio
    async def test_lru_strategy_get_set(self):
        manager = CacheManager(strategy="lru", capacity=10)
        await manager.set("key1", "value1")
        assert await manager.get("key1") == "value1"

    @pytest.mark.asyncio
    async def test_lfu_strategy(self):
        manager = CacheManager(strategy="lfu", capacity=10)
        await manager.set("key1", "value1")
        assert await manager.get("key1") == "value1"

    @pytest.mark.asyncio
    async def test_ttl_strategy(self):
        manager = CacheManager(strategy="ttl", default_ttl=300)
        await manager.set("key1", "value1")
        assert await manager.get("key1") == "value1"

    @pytest.mark.asyncio
    async def test_unknown_strategy_raises_error(self):
        with pytest.raises(ValueError, match="Unknown cache strategy"):
            CacheManager(strategy="unknown")

    @pytest.mark.asyncio
    async def test_delete_and_clear(self):
        manager = CacheManager(capacity=10)
        await manager.set("key1", "value1")
        assert await manager.delete("key1") is True
        await manager.set("key2", "value2")
        await manager.clear()
        assert await manager.get("key2") is None

    @pytest.mark.asyncio
    async def test_has_method(self):
        manager = CacheManager(capacity=10)
        await manager.set("key1", "value1")
        assert await manager.has("key1") is True
        assert await manager.has("nonexistent") is False


class TestCacheManagerStats:
    @pytest.mark.asyncio
    async def test_hit_miss_tracking(self):
        manager = CacheManager(capacity=10)
        await manager.set("key1", "value1")
        await manager.get("key1")
        await manager.get("key2")
        stats = manager.get_stats()
        assert stats["hits"] == 1
        assert stats["misses"] == 1
        assert stats["hit_rate"] == 50.0

    @pytest.mark.asyncio
    async def test_hit_rate_with_no_requests(self):
        manager = CacheManager(capacity=10)
        stats = manager.get_stats()
        assert stats["hit_rate"] == 0.0
        assert stats["total_requests"] == 0

    @pytest.mark.asyncio
    async def test_clear_resets_stats(self):
        manager = CacheManager(capacity=10)
        await manager.set("key1", "value1")
        await manager.get("key1")
        await manager.clear()
        stats = manager.get_stats()
        assert stats["hits"] == 0
        assert stats["misses"] == 0


class TestCacheManagerInvalidation:
    @pytest.mark.asyncio
    async def test_custom_invalidator(self):
        manager = CacheManager(capacity=10)
        invalidator_called = []

        def custom_invalidator(key: str) -> bool:
            invalidator_called.append(key)
            return key.startswith("temp:")

        manager.register_invalidator(custom_invalidator)
        await manager.set("temp:key1", "value1")
        await manager.set("permanent:key2", "value2")
        result = await manager.get("temp:key1")
        assert result is None
        assert await manager.get("permanent:key2") == "value2"
        assert "temp:key1" in invalidator_called


# =============================================================================
# InvalidationManager Tests
# =============================================================================
class TestInvalidationManager:
    @pytest.mark.asyncio
    async def test_invalidate_by_pattern(self):
        cache_manager = CacheManager(capacity=100)
        invalidation_manager = InvalidationManager(cache_manager)
        invalidation_manager.add_rule("users:*", ["users:list", "users:count"])
        await cache_manager.set("users:list", ["user1", "user2"])
        await cache_manager.set("users:count", 2)
        await cache_manager.set("products:list", ["p1"])
        count = await invalidation_manager.invalidate_pattern("users:update")
        assert count == 2
        assert await cache_manager.get("users:list") is None
        assert await cache_manager.get("products:list") is not None

    @pytest.mark.asyncio
    async def test_invalidate_by_tags(self):
        cache_manager = CacheManager(capacity=100)
        invalidation_manager = InvalidationManager(cache_manager)
        count = await invalidation_manager.invalidate_by_tags(["user", "product"])
        assert count == 2


# =============================================================================
# CircuitBreaker Tests
# =============================================================================
class TestCircuitBreakerNormalFlow:
    def test_initial_state_is_closed(self):
        breaker = CircuitBreaker(failure_threshold=3, recovery_timeout=10)
        assert breaker.state == CircuitBreaker.CLOSED
        assert breaker.allow_request() is True

    def test_record_success_in_closed_state(self):
        breaker = CircuitBreaker(failure_threshold=3, recovery_timeout=10)
        breaker.failure_count = 2
        breaker.record_success()
        assert breaker.failure_count == 1
        assert breaker.state == CircuitBreaker.CLOSED

    def test_record_success_in_half_open_closes_circuit(self):
        breaker = CircuitBreaker(failure_threshold=3, recovery_timeout=10)
        breaker.state = CircuitBreaker.HALF_OPEN
        breaker.failure_count = 3
        breaker.record_success()
        assert breaker.state == CircuitBreaker.CLOSED
        assert breaker.failure_count == 0


class TestCircuitBreakerBoundary:
    def test_failure_threshold_reached_opens_circuit(self):
        breaker = CircuitBreaker(failure_threshold=3, recovery_timeout=10)
        breaker.record_failure()
        breaker.record_failure()
        assert breaker.state == CircuitBreaker.CLOSED
        breaker.record_failure()
        assert breaker.state == CircuitBreaker.OPEN
        assert breaker.allow_request() is False

    def test_recovery_timeout_switches_to_half_open(self):
        breaker = CircuitBreaker(failure_threshold=3, recovery_timeout=0.1)
        breaker.record_failure()
        breaker.record_failure()
        breaker.record_failure()
        assert breaker.state == CircuitBreaker.OPEN
        time.sleep(0.15)
        assert breaker.allow_request() is True
        assert breaker.state == CircuitBreaker.HALF_OPEN

    def test_zero_failure_threshold(self):
        breaker = CircuitBreaker(failure_threshold=0, recovery_timeout=10)
        breaker.record_failure()
        assert breaker.state == CircuitBreaker.OPEN


# =============================================================================
# DataSourceManager Tests
# =============================================================================
class TestDataSourceManagerNormalFlow:
    def test_register_and_get_data_source(self):
        manager = DataSourceManager()
        config = DataSourceConfig(name="test_db", type="sql", connection_string="sqlite:///:memory:")
        mock_source = MagicMock(spec=DataSource)
        mock_source.config = config
        manager.register(mock_source)
        assert manager.get("test_db") == mock_source

    def test_get_unknown_source_raises_error(self):
        manager = DataSourceManager()
        with pytest.raises(ValueError, match="Unknown data source"):
            manager.get("nonexistent")

    @pytest.mark.asyncio
    async def test_execute_with_fallback_success(self):
        manager = DataSourceManager()
        mock_source = MagicMock(spec=DataSource)
        mock_source.config = DataSourceConfig(name="test", type="sql", connection_string="test")
        mock_source.execute = AsyncMock(return_value="result")
        manager.register(mock_source)
        result = await manager.execute_with_fallback("test", "SELECT 1")
        assert result == "result"

    @pytest.mark.asyncio
    async def test_execute_with_fallback_on_error(self):
        manager = DataSourceManager()
        mock_source = MagicMock(spec=DataSource)
        mock_source.config = DataSourceConfig(name="test", type="sql", connection_string="test")
        mock_source.execute = AsyncMock(side_effect=Exception("DB Error"))
        manager.register(mock_source)
        fallback = MagicMock(return_value="fallback_result")
        result = await manager.execute_with_fallback("test", "SELECT 1", fallback=fallback)
        assert result == "fallback_result"
        fallback.assert_called_once()

    @pytest.mark.asyncio
    async def test_execute_without_fallback_raises(self):
        manager = DataSourceManager()
        mock_source = MagicMock(spec=DataSource)
        mock_source.config = DataSourceConfig(name="test", type="sql", connection_string="test")
        mock_source.execute = AsyncMock(side_effect=Exception("DB Error"))
        manager.register(mock_source)
        with pytest.raises(Exception, match="DB Error"):
            await manager.execute_with_fallback("test", "SELECT 1")


class TestDataSourceManagerFallback:
    @pytest.mark.asyncio
    async def test_circuit_breaker_open_uses_fallback(self):
        manager = DataSourceManager()
        mock_source = MagicMock(spec=DataSource)
        mock_source.config = DataSourceConfig(name="test", type="sql", connection_string="test")
        mock_source.execute = AsyncMock(return_value="result")
        manager.register(mock_source)
        breaker = manager._circuit_breakers["test"]
        breaker.state = CircuitBreaker.OPEN
        breaker.last_failure_time = time.time()
        fallback = MagicMock(return_value="fallback_result")
        result = await manager.execute_with_fallback("test", "SELECT 1", fallback=fallback)
        assert result == "fallback_result"
        mock_source.execute.assert_not_called()

    @pytest.mark.asyncio
    async def test_circuit_breaker_open_no_fallback_raises_timeout(self):
        manager = DataSourceManager()
        mock_source = MagicMock(spec=DataSource)
        mock_source.config = DataSourceConfig(name="test", type="sql", connection_string="test")
        manager.register(mock_source)
        breaker = manager._circuit_breakers["test"]
        breaker.state = CircuitBreaker.OPEN
        breaker.last_failure_time = time.time()
        with pytest.raises(TimeoutError, match="unavailable"):
            await manager.execute_with_fallback("test", "SELECT 1")


# =============================================================================
# Concurrency Tests
# =============================================================================
class TestCacheConcurrency:
    @pytest.mark.asyncio
    async def test_concurrent_set_operations(self):
        cache = LRUCache[str, int](capacity=1000)

        async def set_key(prefix: str, count: int):
            for i in range(count):
                cache.set(f"{prefix}_{i}", i)

        tasks = [set_key(f"t{j}", 100) for j in range(10)]
        await asyncio.gather(*tasks)
        assert len(cache._cache) == 1000

    @pytest.mark.asyncio
    async def test_concurrent_get_set(self):
        cache = LRUCache[str, int](capacity=1000)
        for i in range(100):
            cache.set(f"key_{i}", i)

        async def get_random():
            for i in range(50):
                cache.get(f"key_{i}")

        async def set_new():
            for i in range(100, 200):
                cache.set(f"key_{i}", i)

        await asyncio.gather(get_random(), set_new())
        assert len(cache._cache) <= 1000

    @pytest.mark.asyncio
    async def test_concurrent_cache_manager_operations(self):
        manager = CacheManager(capacity=1000)

        async def writer():
            for i in range(50):
                await manager.set(f"key_{i}", i)

        async def reader():
            for i in range(50):
                await manager.get(f"key_{i}")

        tasks = []
        for _ in range(5):
            tasks.append(writer())
            tasks.append(reader())
        await asyncio.gather(*tasks)
        stats = manager.get_stats()
        assert stats["sets"] == 250


# =============================================================================
# Timeout and Degradation Tests
# =============================================================================
class TestTimeoutDegradation:
    @pytest.mark.asyncio
    async def test_slow_database_query_with_fallback(self):
        manager = DataSourceManager()
        mock_source = MagicMock(spec=DataSource)
        mock_source.config = DataSourceConfig(name="slow_db", type="sql", connection_string="test")

        async def slow_execute(*args, **kwargs):
            await asyncio.sleep(0.5)
            return "result"

        mock_source.execute = slow_execute
        manager.register(mock_source)
        fallback = MagicMock(return_value="cached_result")
        result = await manager.execute_with_fallback("slow_db", "SELECT *", fallback=fallback)
        assert result == "result"
        fallback.assert_not_called()

    @pytest.mark.asyncio
    async def test_consecutive_failures_open_circuit(self):
        manager = DataSourceManager()
        mock_source = MagicMock(spec=DataSource)
        mock_source.config = DataSourceConfig(name="failing_db", type="sql", connection_string="test")
        mock_source.execute = AsyncMock(side_effect=Exception("Connection failed"))
        manager.register(mock_source)
        for _ in range(5):
            try:
                await manager.execute_with_fallback("failing_db", "SELECT 1")
            except Exception:
                pass
        breaker = manager._circuit_breakers["failing_db"]
        assert breaker.state == CircuitBreaker.OPEN
        fallback = MagicMock(return_value="degraded")
        result = await manager.execute_with_fallback("failing_db", "SELECT 1", fallback=fallback)
        assert result == "degraded"
        mock_source.execute.assert_called()
