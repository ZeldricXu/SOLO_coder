import pytest
import asyncio
from modules.data_access.cache_manager import (
    InMemoryCache,
    CacheManager,
    CacheEntry,
)


@pytest.mark.asyncio
async def test_in_memory_cache_set_get():
    cache = InMemoryCache(max_size=100, default_ttl=300)
    
    await cache.set("key1", "value1")
    result = await cache.get("key1")
    
    assert result == "value1"


@pytest.mark.asyncio
async def test_in_memory_cache_delete():
    cache = InMemoryCache(max_size=100, default_ttl=300)
    
    await cache.set("key1", "value1")
    await cache.delete("key1")
    result = await cache.get("key1")
    
    assert result is None


@pytest.mark.asyncio
async def test_in_memory_cache_ttl():
    cache = InMemoryCache(max_size=100, default_ttl=1)
    
    await cache.set("key1", "value1", ttl=1)
    result1 = await cache.get("key1")
    assert result1 == "value1"
    
    await asyncio.sleep(1.5)
    
    result2 = await cache.get("key1")
    assert result2 is None


@pytest.mark.asyncio
async def test_in_memory_cache_lru_eviction():
    cache = InMemoryCache(max_size=3, default_ttl=300)
    
    await cache.set("key1", "value1")
    await cache.set("key2", "value2")
    await cache.set("key3", "value3")
    
    await cache.get("key1")
    
    await cache.set("key4", "value4")
    
    result = await cache.get("key2")
    assert result is None
    
    result1 = await cache.get("key1")
    assert result1 == "value1"


@pytest.mark.asyncio
async def test_in_memory_cache_clear():
    cache = InMemoryCache(max_size=100, default_ttl=300)
    
    await cache.set("key1", "value1")
    await cache.set("key2", "value2")
    await cache.clear()
    
    result1 = await cache.get("key1")
    result2 = await cache.get("key2")
    
    assert result1 is None
    assert result2 is None


@pytest.mark.asyncio
async def test_cache_manager():
    manager = CacheManager()
    
    await manager.set("key1", "value1")
    result = await manager.get("key1")
    
    assert result == "value1"
    
    await manager.delete("key1")
    result2 = await manager.get("key1")
    assert result2 is None


@pytest.mark.asyncio
async def test_cache_manager_clear():
    manager = CacheManager()
    
    await manager.set("key1", "value1")
    await manager.set("key2", "value2")
    
    result1 = await manager.get("key1")
    result2 = await manager.get("key2")
    
    assert result1 == "value1"
    assert result2 == "value2"
    
    await manager.clear()
    
    result3 = await manager.get("key1")
    result4 = await manager.get("key2")
    
    assert result3 is None
    assert result4 is None


@pytest.mark.asyncio
async def test_cache_entry_expired():
    import time
    
    entry = CacheEntry("value", ttl=1)
    entry.expires_at = time.time() - 2
    
    assert entry.is_expired() is True
