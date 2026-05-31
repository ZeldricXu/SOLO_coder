import time
import json
import asyncio
from typing import Any, Optional, Dict, Callable, Tuple
from dataclasses import dataclass
from collections import OrderedDict
from threading import Lock
from ..config import settings
from .logging_module import get_logger

logger = get_logger(__name__)


@dataclass
class CacheEntry:
    value: Any
    expires_at: float
    created_at: float
    hits: int = 0


class CacheStrategy:
    LRU = "lru"
    LFU = "lfu"
    FIFO = "fifo"
    TTL = "ttl"


class CacheInvalidationEvent:
    def __init__(self, key: str, reason: str, old_value: Any = None):
        self.key = key
        self.reason = reason
        self.old_value = old_value
        self.timestamp = time.time()


class CacheManager:
    _instance: Optional['CacheManager'] = None
    _lock: Lock = Lock()

    def __new__(cls) -> 'CacheManager':
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
                    cls._instance._initialize()
        return cls._instance

    def _initialize(self) -> None:
        self._cache: OrderedDict[str, CacheEntry] = OrderedDict()
        self._strategy: str = CacheStrategy.LRU
        self._default_ttl: int = settings.cache_ttl
        self._max_size: int = settings.cache_max_size
        self._invalidation_callbacks: list[Callable[[CacheInvalidationEvent], None]] = []
        self._key_tags: Dict[str, set[str]] = {}
        self._tag_keys: Dict[str, set[str]] = {}

    def get(self, key: str) -> Optional[Any]:
        with self._lock:
            entry = self._cache.get(key)
            if entry is None:
                return None

            if entry.expires_at < time.time():
                self._delete(key, "expired")
                return None

            entry.hits += 1
            self._update_access_order(key)
            return entry.value

    def set(self, key: str, value: Any, ttl: Optional[int] = None, tags: Optional[list[str]] = None) -> None:
        with self._lock:
            if len(self._cache) >= self._max_size:
                self._evict()

            actual_ttl = ttl if ttl is not None else self._default_ttl
            entry = CacheEntry(
                value=value,
                expires_at=time.time() + actual_ttl,
                created_at=time.time(),
                hits=0
            )
            self._cache[key] = entry

            if tags:
                self._key_tags[key] = set(tags)
                for tag in tags:
                    if tag not in self._tag_keys:
                        self._tag_keys[tag] = set()
                    self._tag_keys[tag].add(key)

    def delete(self, key: str) -> None:
        with self._lock:
            self._delete(key, "manual")

    def _delete(self, key: str, reason: str) -> None:
        if key in self._cache:
            old_value = self._cache[key].value
            del self._cache[key]

            if key in self._key_tags:
                for tag in self._key_tags[key]:
                    if tag in self._tag_keys:
                        self._tag_keys[tag].discard(key)
                del self._key_tags[key]

            event = CacheInvalidationEvent(key, reason, old_value)
            self._notify_callbacks(event)

    def invalidate_tag(self, tag: str) -> int:
        with self._lock:
            if tag not in self._tag_keys:
                return 0
            keys_to_invalidate = list(self._tag_keys[tag])
            for key in keys_to_invalidate:
                self._delete(key, f"tag_invalidation:{tag}")
            return len(keys_to_invalidate)

    def invalidate_pattern(self, pattern: str) -> int:
        import fnmatch
        with self._lock:
            keys_to_invalidate = [k for k in self._cache.keys() if fnmatch.fnmatch(k, pattern)]
            for key in keys_to_invalidate:
                self._delete(key, "pattern_invalidation")
            return len(keys_to_invalidate)

    def clear(self) -> int:
        with self._lock:
            count = len(self._cache)
            for key in list(self._cache.keys()):
                self._delete(key, "clear")
            return count

    def _evict(self) -> None:
        if self._strategy == CacheStrategy.LRU:
            if self._cache:
                oldest_key = next(iter(self._cache))
                self._delete(oldest_key, "eviction:lru")
        elif self._strategy == CacheStrategy.LFU:
            if self._cache:
                lfu_key = min(self._cache.items(), key=lambda x: x[1].hits)[0]
                self._delete(lfu_key, "eviction:lfu")
        elif self._strategy == CacheStrategy.FIFO:
            if self._cache:
                oldest_key = min(self._cache.items(), key=lambda x: x[1].created_at)[0]
                self._delete(oldest_key, "eviction:fifo")

    def _update_access_order(self, key: str) -> None:
        if self._strategy == CacheStrategy.LRU:
            self._cache.move_to_end(key)

    def _notify_callbacks(self, event: CacheInvalidationEvent) -> None:
        for callback in self._invalidation_callbacks:
            try:
                callback(event)
            except Exception as e:
                logger.error(f"Cache invalidation callback error: {e}")

    def add_invalidation_callback(self, callback: Callable[[CacheInvalidationEvent], None]) -> None:
        self._invalidation_callbacks.append(callback)

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total_hits = sum(e.hits for e in self._cache.values())
            return {
                "size": len(self._cache),
                "max_size": self._max_size,
                "strategy": self._strategy,
                "default_ttl": self._default_ttl,
                "total_hits": total_hits,
                "tags_count": len(self._tag_keys),
            }

    async def get_or_set(self, key: str, coro_func: Callable, ttl: Optional[int] = None) -> Any:
        value = self.get(key)
        if value is not None:
            return value
        value = await coro_func()
        self.set(key, value, ttl)
        return value

    def get_or_set_sync(self, key: str, func: Callable, ttl: Optional[int] = None) -> Any:
        value = self.get(key)
        if value is not None:
            return value
        value = func()
        self.set(key, value, ttl)
        return value

    def set_strategy(self, strategy: str) -> None:
        valid_strategies = [CacheStrategy.LRU, CacheStrategy.LFU, CacheStrategy.FIFO, CacheStrategy.TTL]
        if strategy not in valid_strategies:
            raise ValueError(f"Invalid cache strategy: {strategy}. Valid: {valid_strategies}")
        self._strategy = strategy

    def get_strategy(self) -> str:
        return self._strategy


class DistributedCache(CacheManager):
    def __init__(self):
        super().__init__()
        self._redis_client = None
        self._use_redis = False

    async def init_redis(self, redis_url: str) -> None:
        try:
            import aioredis
            self._redis_client = aioredis.from_url(redis_url)
            self._use_redis = True
            logger.info("Distributed cache (Redis) initialized")
        except ImportError:
            logger.warning("aioredis not available, falling back to in-memory cache")
        except Exception as e:
            logger.warning(f"Failed to connect to Redis: {e}, falling back to in-memory cache")

    async def get(self, key: str) -> Optional[Any]:
        if self._use_redis:
            try:
                raw_value = await self._redis_client.get(key)
                if raw_value:
                    return json.loads(raw_value)
            except Exception as e:
                logger.error(f"Redis get error: {e}")
        return super().get(key)

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        if self._use_redis:
            try:
                actual_ttl = ttl if ttl is not None else self._default_ttl
                await self._redis_client.setex(key, actual_ttl, json.dumps(value))
            except Exception as e:
                logger.error(f"Redis set error: {e}")
        super().set(key, value, ttl)


def get_cache() -> CacheManager:
    return CacheManager()
