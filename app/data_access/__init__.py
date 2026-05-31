"""
Data Access Module.
Implements cache strategies (LRU, LFU, TTL) and invalidation management.
"""

import asyncio
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, Generic, List, Optional, TypeVar, Tuple
from heapq import heappush, heappop

from app.logging import get_logger


T = TypeVar('T')


class CacheStrategy(str, Enum):
    LRU = "lru"
    LFU = "lfu"
    TTL = "ttl"
    FIFO = "fifo"


class CacheLevel(str, Enum):
    L1 = "l1"
    L2 = "l2"
    L3 = "l3"


@dataclass
class CacheEntry:
    key: str
    value: Any
    created_at: float = field(default_factory=time.time)
    accessed_at: float = field(default_factory=time.time)
    access_count: int = 0
    expires_at: Optional[float] = None
    tags: List[str] = field(default_factory=list)


class LRUCache:
    def __init__(self, capacity: int = 1000):
        self._capacity = capacity
        self._cache: OrderedDict[str, CacheEntry] = OrderedDict()
        self._logger = get_logger("lru_cache")
    
    def get(self, key: str) -> Optional[Any]:
        if key not in self._cache:
            return None
        
        entry = self._cache.pop(key)
        entry.accessed_at = time.time()
        entry.access_count += 1
        self._cache[key] = entry
        
        if entry.expires_at and time.time() > entry.expires_at:
            self._cache.pop(key, None)
            return None
        
        return entry.value
    
    def set(
        self,
        key: str,
        value: Any,
        ttl: Optional[float] = None,
        tags: Optional[List[str]] = None
    ):
        if key in self._cache:
            self._cache.pop(key)
        elif len(self._cache) >= self._capacity:
            oldest_key = next(iter(self._cache))
            evicted = self._cache.pop(oldest_key)
            self._logger.debug("Evicted entry", key=oldest_key)
        
        entry = CacheEntry(
            key=key,
            value=value,
            tags=tags or []
        )
        if ttl:
            entry.expires_at = time.time() + ttl
        
        self._cache[key] = entry
    
    def delete(self, key: str) -> bool:
        if key in self._cache:
            self._cache.pop(key)
            return True
        return False
    
    def clear(self):
        self._cache.clear()
    
    def invalidate_by_tag(self, tag: str):
        keys_to_remove = [
            k for k, v in self._cache.items()
            if tag in v.tags
        ]
        for k in keys_to_remove:
            self._cache.pop(k, None)
    
    def keys(self) -> List[str]:
        return list(self._cache.keys())
    
    def __len__(self) -> int:
        return len(self._cache)


class LFUCache:
    def __init__(self, capacity: int = 1000):
        self._capacity = capacity
        self._cache: Dict[str, CacheEntry] = {}
        self._freq: Dict[int, OrderedDict] = {}
        self._min_freq = 0
        self._logger = get_logger("lfu_cache")
    
    def _update_freq(self, key: str, entry: CacheEntry):
        old_freq = entry.access_count
        if old_freq in self._freq:
            self._freq[old_freq].pop(key, None)
            if not self._freq[old_freq]:
                del self._freq[old_freq]
                if old_freq == self._min_freq:
                    self._min_freq += 1
        
        entry.access_count += 1
        new_freq = entry.access_count
        if new_freq not in self._freq:
            self._freq[new_freq] = OrderedDict()
        self._freq[new_freq][key] = entry
    
    def get(self, key: str) -> Optional[Any]:
        if key not in self._cache:
            return None
        
        entry = self._cache[key]
        entry.accessed_at = time.time()
        
        if entry.expires_at and time.time() > entry.expires_at:
            del self._cache[key]
            return None
        
        self._update_freq(key, entry)
        return entry.value
    
    def set(
        self,
        key: str,
        value: Any,
        ttl: Optional[float] = None,
        tags: Optional[List[str]] = None
    ):
        if key in self._cache:
            entry = self._cache[key]
            entry.value = value
            entry.accessed_at = time.time()
            if ttl:
                entry.expires_at = time.time() + ttl
            if tags:
                entry.tags = tags
            self._update_freq(key, entry)
            return
        
        if len(self._cache) >= self._capacity:
            evict_order = self._freq.get(self._min_freq, OrderedDict())
            if evict_order:
                evict_key = next(iter(evict_order))
                evict_order.pop(evict_key)
                del self._cache[evict_key]
                if not evict_order:
                    del self._freq[self._min_freq]
                self._logger.debug("Evicted entry", key=evict_key)
        
        entry = CacheEntry(
            key=key,
            value=value,
            access_count=1,
            tags=tags or []
        )
        if ttl:
            entry.expires_at = time.time() + ttl
        
        self._cache[key] = entry
        self._min_freq = 1
        if 1 not in self._freq:
            self._freq[1] = OrderedDict()
        self._freq[1][key] = entry
    
    def delete(self, key: str) -> bool:
        if key not in self._cache:
            return False
        
        entry = self._cache[key]
        freq = entry.access_count
        if freq in self._freq:
            self._freq[freq].pop(key, None)
            if not self._freq[freq]:
                del self._freq[freq]
        
        del self._cache[key]
        return True
    
    def clear(self):
        self._cache.clear()
        self._freq.clear()
        self._min_freq = 0
    
    def invalidate_by_tag(self, tag: str):
        keys_to_remove = [
            k for k, v in self._cache.items()
            if tag in v.tags
        ]
        for k in keys_to_remove:
            self.delete(k)


class TTLCache:
    def __init__(self, default_ttl: float = 300.0):
        self._default_ttl = default_ttl
        self._cache: Dict[str, CacheEntry] = {}
        self._expiry_heap: List[Tuple[float, str]] = []
        self._logger = get_logger("ttl_cache")
    
    def _cleanup(self):
        now = time.time()
        while self._expiry_heap:
            expires_at, key = self._expiry_heap[0]
            if expires_at > now:
                break
            heappop(self._expiry_heap)
            if key in self._cache:
                entry = self._cache[key]
                if entry.expires_at == expires_at:
                    del self._cache[key]
    
    def get(self, key: str) -> Optional[Any]:
        self._cleanup()
        if key not in self._cache:
            return None
        
        entry = self._cache[key]
        entry.accessed_at = time.time()
        entry.access_count += 1
        return entry.value
    
    def set(
        self,
        key: str,
        value: Any,
        ttl: Optional[float] = None,
        tags: Optional[List[str]] = None
    ):
        self._cleanup()
        actual_ttl = ttl or self._default_ttl
        expires_at = time.time() + actual_ttl
        
        entry = CacheEntry(
            key=key,
            value=value,
            expires_at=expires_at,
            tags=tags or []
        )
        self._cache[key] = entry
        heappush(self._expiry_heap, (expires_at, key))
    
    def delete(self, key: str) -> bool:
        self._cleanup()
        if key in self._cache:
            del self._cache[key]
            return True
        return False
    
    def clear(self):
        self._cache.clear()
        self._expiry_heap.clear()
    
    def invalidate_by_tag(self, tag: str):
        self._cleanup()
        keys_to_remove = [
            k for k, v in self._cache.items()
            if tag in v.tags
        ]
        for k in keys_to_remove:
            del self._cache[k]


class MultiLevelCache:
    def __init__(
        self,
        l1_capacity: int = 100,
        l2_capacity: int = 1000,
        default_ttl: float = 300.0
    ):
        self._l1 = LRUCache(l1_capacity)
        self._l2 = LFUCache(l2_capacity)
        self._l3 = TTLCache(default_ttl)
        self._logger = get_logger("multi_level_cache")
        self._hit_count = 0
        self._miss_count = 0
    
    def get(self, key: str) -> Optional[Any]:
        value = self._l1.get(key)
        if value is not None:
            self._hit_count += 1
            self._logger.debug("L1 cache hit", key=key)
            return value
        
        value = self._l2.get(key)
        if value is not None:
            self._hit_count += 1
            self._l1.set(key, value)
            self._logger.debug("L2 cache hit", key=key)
            return value
        
        value = self._l3.get(key)
        if value is not None:
            self._hit_count += 1
            self._l1.set(key, value)
            self._l2.set(key, value)
            self._logger.debug("L3 cache hit", key=key)
            return value
        
        self._miss_count += 1
        return None
    
    def set(
        self,
        key: str,
        value: Any,
        ttl: Optional[float] = None,
        tags: Optional[List[str]] = None
    ):
        self._l1.set(key, value, ttl=ttl, tags=tags)
        self._l2.set(key, value, ttl=ttl, tags=tags)
        self._l3.set(key, value, ttl=ttl, tags=tags)
    
    def delete(self, key: str) -> bool:
        l1_deleted = self._l1.delete(key)
        l2_deleted = self._l2.delete(key)
        l3_deleted = self._l3.delete(key)
        return l1_deleted or l2_deleted or l3_deleted
    
    def invalidate_by_tag(self, tag: str):
        self._l1.invalidate_by_tag(tag)
        self._l2.invalidate_by_tag(tag)
        self._l3.invalidate_by_tag(tag)
    
    def clear(self):
        self._l1.clear()
        self._l2.clear()
        self._l3.clear()
    
    def get_stats(self) -> Dict[str, Any]:
        total = self._hit_count + self._miss_count
        hit_rate = self._hit_count / total if total > 0 else 0.0
        return {
            "hit_count": self._hit_count,
            "miss_count": self._miss_count,
            "hit_rate": hit_rate,
            "l1_size": len(self._l1),
            "l2_size": len(self._l2)
        }


class CacheManager:
    def __init__(self):
        self._caches: Dict[str, MultiLevelCache] = {}
        self._default_ttl = 300.0
        self._logger = get_logger("cache_manager")
    
    def get_or_create(
        self,
        name: str,
        l1_capacity: int = 100,
        l2_capacity: int = 1000,
        default_ttl: float = 300.0
    ) -> MultiLevelCache:
        if name not in self._caches:
            self._caches[name] = MultiLevelCache(
                l1_capacity=l1_capacity,
                l2_capacity=l2_capacity,
                default_ttl=default_ttl
            )
            self._logger.info("Created cache", name=name)
        return self._caches[name]
    
    def get(self, name: str) -> Optional[MultiLevelCache]:
        return self._caches.get(name)
    
    def invalidate_all(self):
        for cache in self._caches.values():
            cache.clear()
    
    def invalidate_tag(self, tag: str):
        for cache in self._caches.values():
            cache.invalidate_by_tag(tag)
    
    def list_caches(self) -> List[Dict[str, Any]]:
        return [
            {"name": name, **cache.get_stats()}
            for name, cache in self._caches.items()
        ]


class CacheBackedDataSource:
    def __init__(self, cache: MultiLevelCache):
        self._cache = cache
        self._logger = get_logger("cached_datasource")
    
    async def get_or_fetch(
        self,
        key: str,
        fetcher: Callable[[], Any],
        ttl: Optional[float] = None,
        tags: Optional[List[str]] = None,
        force_refresh: bool = False
    ) -> Any:
        if not force_refresh:
            cached = self._cache.get(key)
            if cached is not None:
                self._logger.debug("Cache hit", key=key)
                return cached
        
        self._logger.debug("Cache miss, fetching", key=key)
        value = await fetcher() if asyncio.iscoroutinefunction(fetcher) else fetcher()
        
        if value is not None:
            self._cache.set(key, value, ttl=ttl, tags=tags)
        
        return value
    
    def invalidate(self, key: str):
        self._cache.delete(key)
    
    def invalidate_tag(self, tag: str):
        self._cache.invalidate_by_tag(tag)
