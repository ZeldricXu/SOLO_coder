from __future__ import annotations

import asyncio
import json
import time
from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, Generic, Optional, Type, TypeVar

from src.shared.config import settings
from src.shared.errors import ConfigurationError

try:
    import redis.asyncio as redis_async

    REDIS_AVAILABLE = True
except ImportError:
    REDIS_AVAILABLE = False

T = TypeVar("T")


class CacheBackend(ABC):
    @abstractmethod
    async def get(self, key: str) -> Optional[Any]: ...

    @abstractmethod
    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None: ...

    @abstractmethod
    async def delete(self, key: str) -> None: ...

    @abstractmethod
    async def exists(self, key: str) -> bool: ...

    @abstractmethod
    async def clear(self) -> None: ...


class InMemoryCache(CacheBackend):
    def __init__(self):
        self._cache: Dict[str, tuple[Any, Optional[float]]] = {}
        self._lock = asyncio.Lock()

    async def get(self, key: str) -> Optional[Any]:
        async with self._lock:
            entry = self._cache.get(key)
            if entry is None:
                return None

            value, expiry = entry
            if expiry is not None and time.time() > expiry:
                del self._cache[key]
                return None

            return value

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        expiry = time.time() + ttl if ttl is not None else None
        async with self._lock:
            self._cache[key] = (value, expiry)

    async def delete(self, key: str) -> None:
        async with self._lock:
            self._cache.pop(key, None)

    async def exists(self, key: str) -> bool:
        value = await self.get(key)
        return value is not None

    async def clear(self) -> None:
        async with self._lock:
            self._cache.clear()


class RedisCache(CacheBackend):
    def __init__(self, host: str, port: int, db: int = 0):
        if not REDIS_AVAILABLE:
            raise ConfigurationError("Redis package not installed")
        self._client = redis_async.Redis(host=host, port=port, db=db, decode_responses=True)

    async def get(self, key: str) -> Optional[Any]:
        raw = await self._client.get(key)
        if raw is None:
            return None
        try:
            return json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            return raw

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        if not isinstance(value, str):
            value = json.dumps(value, default=str)
        if ttl:
            await self._client.setex(key, ttl, value)
        else:
            await self._client.set(key, value)

    async def delete(self, key: str) -> None:
        await self._client.delete(key)

    async def exists(self, key: str) -> bool:
        return await self._client.exists(key) > 0

    async def clear(self) -> None:
        await self._client.flushdb()


class CacheManager:
    def __init__(self, backend: Optional[CacheBackend] = None):
        self.backend = backend or InMemoryCache()

    async def get(self, key: str) -> Optional[Any]:
        return await self.backend.get(key)

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        await self.backend.set(key, value, ttl)

    async def delete(self, key: str) -> None:
        await self.backend.delete(key)

    async def exists(self, key: str) -> bool:
        return await self.backend.exists(key)

    async def clear(self) -> None:
        await self.backend.clear()

    async def cached(
        self,
        key_prefix: str,
        ttl: int = 300,
    ) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
            async def wrapper(*args: Any, **kwargs: Any) -> Any:
                cache_key = f"{key_prefix}:{hash(str(args) + str(sorted(kwargs.items())))}"
                cached = await self.get(cache_key)
                if cached is not None:
                    return cached

                result = await func(*args, **kwargs)
                await self.set(cache_key, result, ttl)
                return result

            return wrapper

        return decorator


_default_cache: Optional[CacheManager] = None


def get_cache() -> CacheManager:
    global _default_cache
    if _default_cache is None:
        if REDIS_AVAILABLE and settings.redis.host:
            try:
                backend = RedisCache(
                    host=settings.redis.host,
                    port=settings.redis.port,
                    db=settings.redis.db,
                )
                _default_cache = CacheManager(backend)
            except Exception:
                _default_cache = CacheManager(InMemoryCache())
        else:
            _default_cache = CacheManager(InMemoryCache())
    return _default_cache


def invalidate_pattern(pattern: str) -> None:
    pass
