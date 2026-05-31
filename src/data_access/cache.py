from __future__ import annotations

import asyncio
import fnmatch
import logging
import time
from abc import ABC, abstractmethod
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, Generic, Optional, TypeVar

from src.common.exceptions import CacheError
from src.common.utils import async_retry

logger = logging.getLogger(__name__)
K = TypeVar("K")
V = TypeVar("V")


@dataclass
class CacheEntry(Generic[V]):
    value: V
    ttl: Optional[float] = None
    created_at: float = field(default_factory=time.time)
    access_count: int = 0
    last_accessed: float = field(default_factory=time.time)

    def is_expired(self) -> bool:
        if self.ttl is None:
            return False
        return (time.time() - self.created_at) > self.ttl


class CacheStrategy(ABC, Generic[K, V]):
    @abstractmethod
    def get(self, key: K) -> Optional[V]:
        ...

    @abstractmethod
    def set(self, key: K, value: V, ttl: Optional[float] = None) -> None:
        ...

    @abstractmethod
    def delete(self, key: K) -> bool:
        ...

    @abstractmethod
    def clear(self) -> None:
        ...

    @abstractmethod
    def has(self, key: K) -> bool:
        ...


class LRUCache(CacheStrategy[K, V]):
    def __init__(self, capacity: int = 1000, default_ttl: Optional[float] = None) -> None:
        self.capacity = capacity
        self.default_ttl = default_ttl
        self._cache: OrderedDict[K, CacheEntry[V]] = OrderedDict()
        self._lock = asyncio.Lock()

    def _evict_expired(self) -> None:
        expired_keys = [k for k, v in self._cache.items() if v.is_expired()]
        for k in expired_keys:
            del self._cache[k]

    def get(self, key: K) -> Optional[V]:
        if key not in self._cache:
            return None
        entry = self._cache[key]
        if entry.is_expired():
            del self._cache[key]
            return None
        self._cache.move_to_end(key)
        entry.access_count += 1
        entry.last_accessed = time.time()
        return entry.value

    def set(self, key: K, value: V, ttl: Optional[float] = None) -> None:
        self._evict_expired()
        if key in self._cache:
            self._cache.move_to_end(key)
        elif len(self._cache) >= self.capacity:
            self._cache.popitem(last=False)
        self._cache[key] = CacheEntry(
            value=value,
            ttl=ttl or self.default_ttl,
        )

    def delete(self, key: K) -> bool:
        if key in self._cache:
            del self._cache[key]
            return True
        return False

    def clear(self) -> None:
        self._cache.clear()

    def has(self, key: K) -> bool:
        if key not in self._cache:
            return False
        if self._cache[key].is_expired():
            del self._cache[key]
            return False
        return True


class LFUCache(CacheStrategy[K, V]):
    def __init__(self, capacity: int = 1000, default_ttl: Optional[float] = None) -> None:
        self.capacity = capacity
        self.default_ttl = default_ttl
        self._cache: Dict[K, CacheEntry[V]] = {}

    def _evict_lfu(self) -> None:
        if not self._cache:
            return
        min_key = min(self._cache.keys(), key=lambda k: (self._cache[k].access_count, self._cache[k].last_accessed))
        del self._cache[min_key]

    def get(self, key: K) -> Optional[V]:
        if key not in self._cache:
            return None
        entry = self._cache[key]
        if entry.is_expired():
            del self._cache[key]
            return None
        entry.access_count += 1
        entry.last_accessed = time.time()
        return entry.value

    def set(self, key: K, value: V, ttl: Optional[float] = None) -> None:
        if key in self._cache:
            self._cache[key].value = value
            self._cache[key].access_count += 1
            return
        if len(self._cache) >= self.capacity:
            self._evict_lfu()
        self._cache[key] = CacheEntry(
            value=value,
            ttl=ttl or self.default_ttl,
        )

    def delete(self, key: K) -> bool:
        if key in self._cache:
            del self._cache[key]
            return True
        return False

    def clear(self) -> None:
        self._cache.clear()

    def has(self, key: K) -> bool:
        if key not in self._cache:
            return False
        if self._cache[key].is_expired():
            del self._cache[key]
            return False
        return True


class TTLCache(CacheStrategy[K, V]):
    def __init__(self, default_ttl: float = 300, capacity: int = 10000) -> None:
        self.default_ttl = default_ttl
        self.capacity = capacity
        self._cache: Dict[K, CacheEntry[V]] = {}

    def _cleanup(self) -> None:
        expired = [k for k, v in self._cache.items() if v.is_expired()]
        for k in expired:
            del self._cache[k]

    def get(self, key: K) -> Optional[V]:
        entry = self._cache.get(key)
        if entry is None or entry.is_expired():
            if entry is not None:
                del self._cache[key]
            return None
        entry.last_accessed = time.time()
        return entry.value

    def set(self, key: K, value: V, ttl: Optional[float] = None) -> None:
        self._cleanup()
        if len(self._cache) >= self.capacity and key not in self._cache:
            oldest = min(self._cache.keys(), key=lambda k: self._cache[k].created_at)
            del self._cache[oldest]
        self._cache[key] = CacheEntry(value=value, ttl=ttl or self.default_ttl)

    def delete(self, key: K) -> bool:
        if key in self._cache:
            del self._cache[key]
            return True
        return False

    def clear(self) -> None:
        self._cache.clear()

    def has(self, key: K) -> bool:
        entry = self._cache.get(key)
        if entry is None:
            return False
        if entry.is_expired():
            del self._cache[key]
            return False
        return True


class CacheManager:
    def __init__(
        self,
        strategy: str = "lru",
        capacity: int = 1000,
        default_ttl: Optional[float] = None,
    ) -> None:
        self._strategy: CacheStrategy[str, Any]
        if strategy == "lru":
            self._strategy = LRUCache(capacity=capacity, default_ttl=default_ttl)
        elif strategy == "lfu":
            self._strategy = LFUCache(capacity=capacity, default_ttl=default_ttl)
        elif strategy == "ttl":
            self._strategy = TTLCache(default_ttl=default_ttl or 300, capacity=capacity)
        else:
            raise ValueError(f"Unknown cache strategy: {strategy}")
        self._invalidators: list[Callable[[str], bool]] = []
        self._stats: Dict[str, int] = {"hits": 0, "misses": 0, "sets": 0, "deletes": 0}

    def register_invalidator(self, invalidator: Callable[[str], bool]) -> None:
        self._invalidators.append(invalidator)

    def _should_invalidate(self, key: str) -> bool:
        return any(invalidator(key) for invalidator in self._invalidators)

    @async_retry(max_attempts=3, exceptions=(CacheError,))
    async def get(self, key: str) -> Optional[Any]:
        try:
            if self._should_invalidate(key):
                await self.delete(key)
                self._stats["misses"] += 1
                return None
            value = self._strategy.get(key)
            if value is not None:
                self._stats["hits"] += 1
            else:
                self._stats["misses"] += 1
            return value
        except Exception as e:
            logger.error(f"Cache get error for key {key}: {e}")
            raise CacheError(f"Failed to get from cache: {e}")

    @async_retry(max_attempts=3, exceptions=(CacheError,))
    async def set(self, key: str, value: Any, ttl: Optional[float] = None) -> None:
        try:
            self._strategy.set(key, value, ttl)
            self._stats["sets"] += 1
        except Exception as e:
            logger.error(f"Cache set error for key {key}: {e}")
            raise CacheError(f"Failed to set cache: {e}")

    async def delete(self, key: str) -> bool:
        try:
            result = self._strategy.delete(key)
            if result:
                self._stats["deletes"] += 1
            return result
        except Exception as e:
            logger.error(f"Cache delete error for key {key}: {e}")
            raise CacheError(f"Failed to delete cache: {e}")

    async def clear(self) -> None:
        self._strategy.clear()
        self._stats = {"hits": 0, "misses": 0, "sets": 0, "deletes": 0}

    async def has(self, key: str) -> bool:
        return self._strategy.has(key)

    def get_stats(self) -> Dict[str, Any]:
        total = self._stats["hits"] + self._stats["misses"]
        hit_rate = (self._stats["hits"] / total * 100) if total > 0 else 0.0
        return {
            **self._stats,
            "total_requests": total,
            "hit_rate": hit_rate,
        }

    async def cached(self, ttl: Optional[float] = None, key_prefix: str = ""):
        def decorator(func: Callable[..., Any]):
            async def wrapper(*args: Any, **kwargs: Any):
                cache_key = f"{key_prefix}:{func.__name__}:{args}:{sorted(kwargs.items())}"
                cached = await self.get(cache_key)
                if cached is not None:
                    return cached
                result = await func(*args, **kwargs) if asyncio.iscoroutinefunction(func) else func(*args, **kwargs)
                await self.set(cache_key, result, ttl)
                return result
            return wrapper
        return decorator


class InvalidationManager:
    def __init__(self, cache_manager: CacheManager) -> None:
        self.cache_manager = cache_manager
        self._invalidation_rules: Dict[str, list[str]] = {}

    def add_rule(self, pattern: str, invalidate_keys: list[str]) -> None:
        self._invalidation_rules[pattern] = invalidate_keys

    async def invalidate_pattern(self, pattern: str) -> int:
        count = 0
        for key_pattern, keys_to_invalidate in self._invalidation_rules.items():
            if fnmatch.fnmatch(pattern, key_pattern) or fnmatch.fnmatch(key_pattern, pattern):
                for key in keys_to_invalidate:
                    if await self.cache_manager.delete(key):
                        count += 1
        return count

    async def invalidate_by_tags(self, tags: list[str]) -> int:
        count = 0
        for tag in tags:
            invalidator = lambda k: tag in k
            self.cache_manager.register_invalidator(invalidator)
            count += 1
        return count
