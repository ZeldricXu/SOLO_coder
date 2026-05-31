from typing import Dict, Any, Optional, List
from datetime import datetime, timedelta
from collections import OrderedDict
import hashlib
import asyncio
import logging

logger = logging.getLogger(__name__)


class L1Cache:
    def __init__(self, max_size: int = 1000, ttl_seconds: int = 300):
        self._cache: OrderedDict[str, tuple] = OrderedDict()
        self._max_size = max_size
        self._ttl_seconds = ttl_seconds
        self._lock = asyncio.Lock()
        self._hits = 0
        self._misses = 0

    async def get(self, key: str) -> Optional[Any]:
        async with self._lock:
            if key not in self._cache:
                self._misses += 1
                return None

            value, expire_at = self._cache[key]
            if datetime.utcnow() > expire_at:
                del self._cache[key]
                self._misses += 1
                return None

            self._cache.move_to_end(key)
            self._hits += 1
            return value

    async def set(self, key: str, value: Any, ttl_seconds: Optional[int] = None) -> None:
        async with self._lock:
            expire_at = datetime.utcnow() + timedelta(seconds=ttl_seconds or self._ttl_seconds)
            self._cache[key] = (value, expire_at)
            self._cache.move_to_end(key)

            while len(self._cache) > self._max_size:
                self._cache.popitem(last=False)

    async def invalidate(self, key: str) -> bool:
        async with self._lock:
            if key in self._cache:
                del self._cache[key]
                return True
            return False

    async def invalidate_pattern(self, pattern: str) -> int:
        async with self._lock:
            keys_to_delete = [k for k in self._cache if pattern in k]
            for k in keys_to_delete:
                del self._cache[k]
            return len(keys_to_delete)

    async def clear(self) -> None:
        async with self._lock:
            self._cache.clear()

    def get_stats(self) -> Dict[str, Any]:
        total = self._hits + self._misses
        hit_rate = self._hits / total if total > 0 else 0
        return {
            "size": len(self._cache),
            "max_size": self._max_size,
            "hits": self._hits,
            "misses": self._misses,
            "hit_rate": round(hit_rate, 4),
        }


class L2Cache:
    def __init__(self, redis_url: Optional[str] = None, ttl_seconds: int = 3600):
        self._ttl_seconds = ttl_seconds
        self._redis_client = None
        self._use_redis = False
        self._memory_store: Dict[str, tuple] = {}
        self._lock = asyncio.Lock()
        self._hits = 0
        self._misses = 0

        if redis_url:
            try:
                import redis.asyncio as redis
                self._redis_client = redis.from_url(redis_url)
                self._use_redis = True
                logger.info("L2 Cache initialized with Redis backend")
            except ImportError:
                logger.warning("redis-py not installed, falling back to memory store")

    async def get(self, key: str) -> Optional[Any]:
        if self._use_redis and self._redis_client:
            try:
                import json
                value = await self._redis_client.get(key)
                if value:
                    self._hits += 1
                    return json.loads(value)
            except Exception as e:
                logger.warning(f"Redis get error: {e}")

        async with self._lock:
            if key not in self._memory_store:
                self._misses += 1
                return None

            value, expire_at = self._memory_store[key]
            if datetime.utcnow() > expire_at:
                del self._memory_store[key]
                self._misses += 1
                return None

            self._hits += 1
            return value

    async def set(self, key: str, value: Any, ttl_seconds: Optional[int] = None) -> None:
        ttl = ttl_seconds or self._ttl_seconds

        if self._use_redis and self._redis_client:
            try:
                import json
                await self._redis_client.setex(
                    key,
                    ttl,
                    json.dumps(value, default=str)
                )
            except Exception as e:
                logger.warning(f"Redis set error: {e}")

        async with self._lock:
            expire_at = datetime.utcnow() + timedelta(seconds=ttl)
            self._memory_store[key] = (value, expire_at)

    async def invalidate(self, key: str) -> bool:
        if self._use_redis and self._redis_client:
            try:
                await self._redis_client.delete(key)
            except Exception as e:
                logger.warning(f"Redis delete error: {e}")

        async with self._lock:
            if key in self._memory_store:
                del self._memory_store[key]
                return True
            return False

    async def invalidate_pattern(self, pattern: str) -> int:
        if self._use_redis and self._redis_client:
            try:
                keys = []
                async for key in self._redis_client.scan_iter(f"*{pattern}*"):
                    keys.append(key)
                if keys:
                    await self._redis_client.delete(*keys)
            except Exception as e:
                logger.warning(f"Redis pattern delete error: {e}")

        async with self._lock:
            keys_to_delete = [k for k in self._memory_store if pattern in k]
            for k in keys_to_delete:
                del self._memory_store[k]
            return len(keys_to_delete)

    def get_stats(self) -> Dict[str, Any]:
        total = self._hits + self._misses
        hit_rate = self._hits / total if total > 0 else 0
        return {
            "backend": "redis" if self._use_redis else "memory",
            "size": len(self._memory_store),
            "hits": self._hits,
            "misses": self._misses,
            "hit_rate": round(hit_rate, 4),
        }


class MultiLevelCache:
    def __init__(
        self,
        l1_max_size: int = 1000,
        l1_ttl_seconds: int = 300,
        l2_redis_url: Optional[str] = None,
        l2_ttl_seconds: int = 3600,
    ):
        self.l1 = L1Cache(max_size=l1_max_size, ttl_seconds=l1_ttl_seconds)
        self.l2 = L2Cache(redis_url=l2_redis_url, ttl_seconds=l2_ttl_seconds)
        self._key_prefix = "doc_pipeline"

    def _make_key(self, *parts: str) -> str:
        raw = ":".join(parts)
        return f"{self._key_prefix}:{hashlib.md5(raw.encode()).hexdigest()}"

    async def get(self, *key_parts: str) -> Optional[Any]:
        key = self._make_key(*key_parts)

        value = await self.l1.get(key)
        if value is not None:
            logger.debug(f"L1 Cache HIT: {key}")
            return value

        logger.debug(f"L1 Cache MISS, checking L2: {key}")
        value = await self.l2.get(key)
        if value is not None:
            await self.l1.set(key, value)
            return value

        return None

    async def set(self, value: Any, *key_parts: str, ttl_seconds: Optional[int] = None) -> None:
        key = self._make_key(*key_parts)
        await self.l1.set(key, value, ttl_seconds)
        await self.l2.set(key, value, ttl_seconds)

    async def invalidate(self, *key_parts: str) -> None:
        key = self._make_key(*key_parts)
        await self.l1.invalidate(key)
        await self.l2.invalidate(key)

    async def invalidate_document(self, document_id: str) -> int:
        pattern = self._make_key(document_id)
        l1_count = await self.l1.invalidate_pattern(pattern)
        l2_count = await self.l2.invalidate_pattern(pattern)
        return l1_count + l2_count

    async def warm_up(self, entries: List[tuple]) -> int:
        warmed = 0
        for key_parts, value in entries:
            try:
                await self.set(value, *key_parts)
                warmed += 1
            except Exception as e:
                logger.warning(f"Warm up failed for {key_parts}: {e}")
        return warmed

    def get_stats(self) -> Dict[str, Any]:
        return {
            "l1": self.l1.get_stats(),
            "l2": self.l2.get_stats(),
        }
