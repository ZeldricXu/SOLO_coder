import time
import asyncio
from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, Generic, Optional, TypeVar
from collections import OrderedDict

from config import settings

T = TypeVar("T")


class CacheEntry(Generic[T]):
    def __init__(self, value: T, ttl: int = 300):
        self.value = value
        self.expires_at = time.time() + ttl
        self.created_at = time.time()
        self.access_count = 0

    def is_expired(self) -> bool:
        return time.time() > self.expires_at

    def touch(self, ttl: Optional[int] = None) -> None:
        self.access_count += 1
        if ttl:
            self.expires_at = time.time() + ttl


class CacheStrategy(ABC):
    @abstractmethod
    async def get(self, key: str) -> Optional[Any]:
        pass

    @abstractmethod
    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        pass

    @abstractmethod
    async def delete(self, key: str) -> bool:
        pass

    @abstractmethod
    async def exists(self, key: str) -> bool:
        pass

    @abstractmethod
    async def clear(self) -> None:
        pass


class InMemoryCache(CacheStrategy):
    def __init__(self, max_size: int = 1000, default_ttl: int = 300):
        self._cache: "OrderedDict[str, CacheEntry]" = OrderedDict()
        self._max_size = max_size
        self._default_ttl = default_ttl
        self._lock = asyncio.Lock()

    async def get(self, key: str) -> Optional[Any]:
        async with self._lock:
            entry = self._cache.get(key)
            if entry is None:
                return None
            if entry.is_expired():
                del self._cache[key]
                return None
            entry.touch()
            self._cache.move_to_end(key)
            return entry.value

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        async with self._lock:
            if len(self._cache) >= self._max_size and key not in self._cache:
                self._evict_lru()
            self._cache[key] = CacheEntry(value, ttl or self._default_ttl)

    async def delete(self, key: str) -> bool:
        async with self._lock:
            if key in self._cache:
                del self._cache[key]
                return True
            return False

    async def exists(self, key: str) -> bool:
        async with self._lock:
            entry = self._cache.get(key)
            if entry and not entry.is_expired():
                return True
            if entry and entry.is_expired():
                del self._cache[key]
            return False

    async def clear(self) -> None:
        async with self._lock:
            self._cache.clear()

    def _evict_lru(self) -> None:
        if self._cache:
            self._cache.popitem(last=False)

    async def cleanup_expired(self) -> int:
        async with self._lock:
            expired_keys = [
                key for key, entry in self._cache.items()
                if entry.is_expired()
            ]
            for key in expired_keys:
                del self._cache[key]
            return len(expired_keys)

    async def get_stats(self) -> Dict[str, Any]:
        async with self._lock:
            return {
                "size": len(self._cache),
                "max_size": self._max_size,
                "hit_rate": 0.0,
            }


class RedisCache(CacheStrategy):
    def __init__(self, redis_url: str, default_ttl: int = 300):
        self._redis_url = redis_url
        self._default_ttl = default_ttl
        self._client = None
        try:
            import redis
            self._client = redis.from_url(redis_url)
        except ImportError:
            self._client = None

    async def get(self, key: str) -> Optional[Any]:
        if not self._client is None:
            return None
        try:
            import json
            value = self._client.get(key)
            if value:
                return json.loads(value)
            return None
        except Exception:
            return None

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        if not self._client:
            return
        try:
            import json
            self._client.setex(
                key,
                json.dumps(value),
                ttl or self._default_ttl,
            )
        except Exception:
            pass

    async def delete(self, key: str) -> bool:
        if not self._client:
            return False
        try:
            return self._client.delete(key) > 0
        except Exception:
            return False

    async def exists(self, key: str) -> bool:
        if not self._client:
            return False
        try:
            return self._client.exists(key) > 0
        except Exception:
            return False

    async def clear(self) -> None:
        if not self._client:
            try:
                self._client.flushdb()
            except Exception:
                pass


class CacheManager:
    def __init__(self, strategy: Optional[CacheStrategy] = None):
        self._strategy = strategy or InMemoryCache(
            max_size=1000,
            default_ttl=settings.cache_ttl,
        )
        self._hit_count = 0
        self._miss_count = 0
        self._lock = asyncio.Lock()

    @classmethod
    def with_redis(cls, redis_url: str, default_ttl: int = 300) -> "CacheManager":
        return cls(RedisCache(redis_url, default_ttl))

    async def get(self, key: str) -> Optional[Any]:
        value = await self._strategy.get(key)
        async with self._lock:
            if value is not None:
                self._hit_count += 1
            else:
                self._miss_count += 1
        return value

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        await self._strategy.set(key, value, ttl)

    async def delete(self, key: str) -> bool:
        return await self._strategy.delete(key)

    async def exists(self, key: str) -> bool:
        return await self._strategy.exists(key)

    async def clear(self) -> None:
        await self._strategy.clear()

    async def get_or_set(
        self,
        key: str,
        fallback: Callable[[], Any],
        ttl: Optional[int] = None,
    ) -> Any:
        value = await self.get(key)
        if value is not None:
            return value

        if asyncio.iscoroutinefunction(fallback):
            value = await fallback()
        else:
            value = fallback()

        await self.set(key, value, ttl)
        return value

    async def invalidate_pattern(self, pattern: str) -> int:
        if isinstance(self._strategy, InMemoryCache):
            count = 0
            async with self._strategy._lock:
                keys = [k for k in self._strategy._cache.keys() if pattern in k]
                for key in keys:
                    del self._strategy._cache[key]
                    count += 1
            return count
        return 0

    async def get_stats(self) -> Dict[str, Any]:
        total = self._hit_count + self._miss_count
        hit_rate = self._hit_count / total if total > 0 else 0.0
        stats = {
            "hits": self._hit_count,
            "misses": self._miss_count,
            "total": total,
            "hit_rate": hit_rate,
        }
        if isinstance(self._strategy, InMemoryCache):
            mem_stats = await self._strategy.get_stats()
            stats.update(mem_stats)
        return stats

    async def reset_stats(self) -> None:
        async with self._lock:
            self._hit_count = 0
            self._miss_count = 0


cache_manager = CacheManager()
