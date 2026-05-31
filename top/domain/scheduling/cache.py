from __future__ import annotations

import asyncio
import hashlib
import json
import pickle
import threading
import time
from abc import ABC, abstractmethod
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, Generic, List, Optional, TypeVar, Set

from top.domain.scheduling.models import utc_now


T = TypeVar('T')


class CacheLevel(str, Enum):
    L1 = "L1"
    L2 = "L2"


class CacheEntryStatus(str, Enum):
    FRESH = "fresh"
    STALE = "stale"
    EXPIRED = "expired"


@dataclass
class CacheStats:
    l1_hits: int = 0
    l1_misses: int = 0
    l2_hits: int = 0
    l2_misses: int = 0
    l1_evictions: int = 0
    l2_evictions: int = 0
    l1_size: int = 0
    l2_size: int = 0
    warm_up_count: int = 0

    @property
    def l1_hit_rate(self) -> float:
        total = self.l1_hits + self.l1_misses
        return self.l1_hits / total if total > 0 else 0.0

    @property
    def l2_hit_rate(self) -> float:
        total = self.l2_hits + self.l2_misses
        return self.l2_hits / total if total > 0 else 0.0

    @property
    def overall_hit_rate(self) -> float:
        total_hits = self.l1_hits + self.l2_hits
        total = total_hits + self.l2_misses
        return total_hits / total if total > 0 else 0.0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "l1_hits": self.l1_hits,
            "l1_misses": self.l1_misses,
            "l2_hits": self.l2_hits,
            "l2_misses": self.l2_misses,
            "l1_evictions": self.l1_evictions,
            "l2_evictions": self.l2_evictions,
            "l1_size": self.l1_size,
            "l2_size": self.l2_size,
            "l1_hit_rate": self.l1_hit_rate,
            "l2_hit_rate": self.l2_hit_rate,
            "overall_hit_rate": self.overall_hit_rate,
            "warm_up_count": self.warm_up_count,
        }


@dataclass
class CacheEntry(Generic[T]):
    key: str
    value: T
    created_at: datetime = field(default_factory=utc_now)
    expires_at: Optional[datetime] = None
    tags: List[str] = field(default_factory=list)
    hit_count: int = 0

    @property
    def is_expired(self) -> bool:
        if self.expires_at is None:
            return False
        return utc_now() >= self.expires_at

    @property
    def age_ms(self) -> float:
        return (utc_now() - self.created_at).total_seconds() * 1000

    def get_status(self, stale_threshold_seconds: int = 60) -> CacheEntryStatus:
        if self.is_expired:
            return CacheEntryStatus.EXPIRED
        age_seconds = self.age_ms / 1000
        if age_seconds > stale_threshold_seconds:
            return CacheEntryStatus.STALE
        return CacheEntryStatus.FRESH


class CacheBackend(ABC, Generic[T]):
    @abstractmethod
    async def get(self, key: str) -> Optional[CacheEntry[T]]:
        pass

    @abstractmethod
    async def set(
        self,
        key: str,
        value: T,
        ttl_seconds: Optional[int] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        pass

    @abstractmethod
    async def delete(self, key: str) -> bool:
        pass

    @abstractmethod
    async def delete_by_tags(self, tags: List[str]) -> int:
        pass

    @abstractmethod
    async def clear(self) -> None:
        pass

    @abstractmethod
    async def size(self) -> int:
        pass

    @abstractmethod
    async def get_stats(self) -> Dict[str, Any]:
        pass

    @abstractmethod
    async def warm_up(self, entries: Dict[str, tuple[T, Optional[int]]]) -> None:
        pass


class L1CacheBackend(CacheBackend[T]):
    def __init__(self, max_size: int = 1000, default_ttl_seconds: int = 300):
        self._max_size = max_size
        self._default_ttl = default_ttl_seconds
        self._cache: 'OrderedDict[str, CacheEntry[T]]' = OrderedDict()
        self._tag_index: Dict[str, Set[str]] = {}
        self._lock = threading.RLock()
        self._hits = 0
        self._misses = 0
        self._evictions = 0

    def _evict_if_needed(self) -> None:
        while len(self._cache) >= self._max_size:
            if self._cache:
                oldest_key, oldest_entry = self._cache.popitem(last=False)
                self._remove_from_tag_index(oldest_key, oldest_entry.tags)
                self._evictions += 1

    def _remove_from_tag_index(self, key: str, tags: List[str]) -> None:
        for tag in tags:
            if tag in self._tag_index:
                self._tag_index[tag].discard(key)
                if not self._tag_index[tag]:
                    del self._tag_index[tag]

    def _add_to_tag_index(self, key: str, tags: List[str]) -> None:
        for tag in tags:
            if tag not in self._tag_index:
                self._tag_index[tag] = set()
            self._tag_index[tag].add(key)

    async def get(self, key: str) -> Optional[CacheEntry[T]]:
        with self._lock:
            if key in self._cache:
                entry = self._cache[key]
                if entry.is_expired:
                    self._cache.pop(key, None)
                    self._remove_from_tag_index(key, entry.tags)
                    self._misses += 1
                    return None
                self._hits += 1
                entry.hit_count += 1
                self._cache.move_to_end(key)
                return entry
            self._misses += 1
            return None

    async def set(
        self,
        key: str,
        value: T,
        ttl_seconds: Optional[int] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        with self._lock:
            if key in self._cache:
                old_entry = self._cache[key]
                self._remove_from_tag_index(key, old_entry.tags)

            self._evict_if_needed()

            expires_at = None
            if ttl_seconds is not None:
                expires_at = utc_now() + timedelta(seconds=ttl_seconds)
            elif self._default_ttl > 0:
                expires_at = utc_now() + timedelta(seconds=self._default_ttl)

            entry = CacheEntry(
                key=key,
                value=value,
                expires_at=expires_at,
                tags=tags or [],
            )
            self._cache[key] = entry
            self._add_to_tag_index(key, entry.tags)

    async def delete(self, key: str) -> bool:
        with self._lock:
            if key in self._cache:
                entry = self._cache.pop(key)
                self._remove_from_tag_index(key, entry.tags)
                return True
            return False

    async def delete_by_tags(self, tags: List[str]) -> int:
        with self._lock:
            keys_to_delete: Set[str] = set()
            for tag in tags:
                if tag in self._tag_index:
                    keys_to_delete.update(self._tag_index[tag])

            for key in keys_to_delete:
                if key in self._cache:
                    entry = self._cache.pop(key)
                    self._remove_from_tag_index(key, entry.tags)

            return len(keys_to_delete)

    async def clear(self) -> None:
        with self._lock:
            self._cache.clear()
            self._tag_index.clear()

    async def size(self) -> int:
        with self._lock:
            return len(self._cache)

    async def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "size": len(self._cache),
                "max_size": self._max_size,
                "hits": self._hits,
                "misses": self._misses,
                "evictions": self._evictions,
            }

    async def warm_up(self, entries: Dict[str, tuple[T, Optional[int]]]) -> None:
        for key, (value, ttl) in entries.items():
            await self.set(key, value, ttl_seconds=ttl)


class L2CacheBackend(CacheBackend[T]):
    def __init__(
        self,
        default_ttl_seconds: int = 1800,
        redis_url: Optional[str] = None,
    ):
        self._default_ttl = default_ttl_seconds
        self._redis_url = redis_url
        self._redis_client = None
        self._cache: Dict[str, CacheEntry[T]] = {}
        self._tag_index: Dict[str, Set[str]] = {}
        self._lock = threading.RLock()
        self._hits = 0
        self._misses = 0
        self._evictions = 0

    async def _init_redis(self) -> None:
        if self._redis_url and self._redis_client is None:
            try:
                import redis.asyncio as redis
                self._redis_client = redis.from_url(self._redis_url)
            except ImportError:
                pass

    def _serialize(self, value: T) -> str:
        try:
            return json.dumps(value)
        except (TypeError, ValueError):
            return base64.b64encode(pickle.dumps(value)).decode('ascii')

    def _deserialize(self, data: str) -> T:
        try:
            return json.loads(data)
        except (TypeError, ValueError, json.JSONDecodeError):
            return pickle.loads(base64.b64decode(data.encode('ascii')))

    async def get(self, key: str) -> Optional[CacheEntry[T]]:
        if self._redis_client:
            data = await self._redis_client.get(key)
            if data:
                self._hits += 1
                return CacheEntry(key=key, value=self._deserialize(data))
            self._misses += 1
            return None

        with self._lock:
            if key in self._cache:
                entry = self._cache[key]
                if entry.is_expired:
                    self._cache.pop(key, None)
                    self._misses += 1
                    return None
                self._hits += 1
                entry.hit_count += 1
                return entry
            self._misses += 1
            return None

    async def set(
        self,
        key: str,
        value: T,
        ttl_seconds: Optional[int] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        ttl = ttl_seconds if ttl_seconds is not None else self._default_ttl

        if self._redis_client:
            await self._redis_client.setex(key, ttl, self._serialize(value))
            return

        with self._lock:
            if key in self._cache:
                old_entry = self._cache[key]
                for tag in old_entry.tags:
                    if tag in self._tag_index:
                        self._tag_index[tag].discard(key)

            expires_at = utc_now() + timedelta(seconds=ttl) if ttl > 0 else None
            entry = CacheEntry(
                key=key,
                value=value,
                expires_at=expires_at,
                tags=tags or [],
            )
            self._cache[key] = entry

            for tag in (tags or []):
                if tag not in self._tag_index:
                    self._tag_index[tag] = set()
                self._tag_index[tag].add(key)

    async def delete(self, key: str) -> bool:
        if self._redis_client:
            result = await self._redis_client.delete(key)
            return result > 0

        with self._lock:
            if key in self._cache:
                entry = self._cache.pop(key)
                for tag in entry.tags:
                    if tag in self._tag_index:
                        self._tag_index[tag].discard(key)
                        if not self._tag_index[tag]:
                            del self._tag_index[tag]
                return True
            return False

    async def delete_by_tags(self, tags: List[str]) -> int:
        if self._redis_client:
            keys_to_delete = []
            for tag in tags:
                matched = await self._redis_client.keys(f"tag:{tag}:*")
                keys_to_delete.extend(matched)
            if keys_to_delete:
                await self._redis_client.delete(*keys_to_delete)
            return len(keys_to_delete)

        with self._lock:
            keys_to_delete: Set[str] = set()
            for tag in tags:
                if tag in self._tag_index:
                    keys_to_delete.update(self._tag_index[tag])

            for key in keys_to_delete:
                if key in self._cache:
                    entry = self._cache.pop(key)
                    for tag in entry.tags:
                        if tag in self._tag_index:
                            self._tag_index[tag].discard(key)
                            if not self._tag_index[tag]:
                                del self._tag_index[tag]

            return len(keys_to_delete)

    async def clear(self) -> None:
        if self._redis_client:
            await self._redis_client.flushdb()
            return

        with self._lock:
            self._cache.clear()
            self._tag_index.clear()

    async def size(self) -> int:
        if self._redis_client:
            return await self._redis_client.dbsize()

        with self._lock:
            return len(self._cache)

    async def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "size": len(self._cache) if not self._redis_client else None,
                "hits": self._hits,
                "misses": self._misses,
                "evictions": self._evictions,
                "uses_redis": self._redis_client is not None,
            }

    async def warm_up(self, entries: Dict[str, tuple[T, Optional[int]]]) -> None:
        for key, (value, ttl) in entries.items():
            await self.set(key, value, ttl_seconds=ttl)


import base64


class CacheInvalidationStrategy(str, Enum):
    TIME_BASED = "time_based"
    WRITE_THROUGH = "write_through"
    WRITE_BACK = "write_back"
    EVENT_DRIVEN = "event_driven"


@dataclass
class CacheConfig:
    l1_max_size: int = 1000
    l1_ttl_seconds: int = 300
    l2_ttl_seconds: int = 1800
    l2_redis_url: Optional[str] = None
    invalidation_strategy: CacheInvalidationStrategy = CacheInvalidationStrategy.TIME_BASED
    stale_while_revalidate: bool = True
    stale_ttl_seconds: int = 60


class MultiLevelCache:
    def __init__(self, config: Optional[CacheConfig] = None):
        self._config = config or CacheConfig()
        self._l1: L1CacheBackend[Any] = L1CacheBackend(
            max_size=self._config.l1_max_size,
            default_ttl_seconds=self._config.l1_ttl_seconds,
        )
        self._l2: L2CacheBackend[Any] = L2CacheBackend(
            default_ttl_seconds=self._config.l2_ttl_seconds,
            redis_url=self._config.l2_redis_url,
        )
        self._stats = CacheStats()
        self._lock = asyncio.Lock()
        self._warmup_complete = False

    @property
    def config(self) -> CacheConfig:
        return self._config

    @property
    def stats(self) -> CacheStats:
        return self._stats

    @property
    def is_warmed_up(self) -> bool:
        return self._warmup_complete

    def _generate_key(self, *parts: Any) -> str:
        raw = ":".join(str(p) for p in parts)
        return hashlib.sha256(raw.encode()).hexdigest()[:32]

    async def get(self, key: str) -> Optional[Any]:
        l1_entry = await self._l1.get(key)
        if l1_entry is not None:
            if self._config.stale_while_revalidate:
                if l1_entry.get_status(self._config.stale_ttl_seconds) == CacheEntryStatus.STALE:
                    self._stats.l1_hits += 1
                    return l1_entry.value
            self._stats.l1_hits += 1
            return l1_entry.value

        self._stats.l1_misses += 1

        l2_entry = await self._l2.get(key)
        if l2_entry is not None:
            self._stats.l2_hits += 1
            await self._l1.set(
                key,
                l2_entry.value,
                ttl_seconds=self._config.l1_ttl_seconds,
            )
            return l2_entry.value

        self._stats.l2_misses += 1
        return None

    async def set(
        self,
        key: str,
        value: Any,
        ttl_seconds: Optional[int] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        async with self._lock:
            await self._l1.set(key, value, ttl_seconds, tags)
            await self._l2.set(key, value, ttl_seconds, tags)

    async def delete(self, key: str) -> bool:
        async with self._lock:
            l1_deleted = await self._l1.delete(key)
            l2_deleted = await self._l2.delete(key)
            return l1_deleted or l2_deleted

    async def invalidate(self, keys: List[str]) -> int:
        async with self._lock:
            count = 0
            for key in keys:
                if await self.delete(key):
                    count += 1
            return count

    async def invalidate_by_tags(self, tags: List[str]) -> int:
        async with self._lock:
            l1_count = await self._l1.delete_by_tags(tags)
            l2_count = await self._l2.delete_by_tags(tags)
            return l1_count + l2_count

    async def get_or_load(
        self,
        key: str,
        loader: Callable[[], Any],
        ttl_seconds: Optional[int] = None,
        tags: Optional[List[str]] = None,
    ) -> Any:
        cached = await self.get(key)
        if cached is not None:
            return cached

        value = loader()
        if asyncio.iscoroutine(value):
            value = await value

        await self.set(key, value, ttl_seconds, tags)
        return value

    async def warm_up(
        self,
        entries: Dict[str, tuple[Any, Optional[int]]],
    ) -> None:
        async with self._lock:
            self._stats.warm_up_count += 1
            await self._l2.warm_up(entries)
            for key, (value, ttl) in list(entries.items())[:self._config.l1_max_size // 2]:
                await self._l1.set(key, value, ttl_seconds=ttl)
            self._warmup_complete = True

    async def clear(self) -> None:
        async with self._lock:
            await self._l1.clear()
            await self._l2.clear()
            self._warmup_complete = False

    async def get_stats(self) -> Dict[str, Any]:
        l1_stats = await self._l1.get_stats()
        l2_stats = await self._l2.get_stats()

        self._stats.l1_size = l1_stats.get('size', 0)
        self._stats.l2_size = l2_stats.get('size', 0)

        return {
            "stats": self._stats.to_dict(),
            "l1": l1_stats,
            "l2": l2_stats,
            "config": {
                "l1_max_size": self._config.l1_max_size,
                "l1_ttl": self._config.l1_ttl_seconds,
                "l2_ttl": self._config.l2_ttl_seconds,
                "invalidation_strategy": self._config.invalidation_strategy.value,
            },
            "warmed_up": self._warmup_complete,
        }


_cache_instance: Optional[MultiLevelCache] = None


def get_cache(config: Optional[CacheConfig] = None) -> MultiLevelCache:
    global _cache_instance
    if _cache_instance is None:
        _cache_instance = MultiLevelCache(config)
    return _cache_instance


def set_cache_instance(cache: MultiLevelCache) -> None:
    global _cache_instance
    _cache_instance = cache
