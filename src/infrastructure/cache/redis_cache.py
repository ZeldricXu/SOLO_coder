"""Redis cache implementation."""
from __future__ import annotations

import json
import pickle
from typing import Any, Optional

try:
    import redis
    REDIS_AVAILABLE = True
except ImportError:
    REDIS_AVAILABLE = False

from ...infrastructure.logging.structured_logger import LogManager


class RedisCache:
    def __init__(self, host: str = "localhost", port: int = 6379, db: int = 0, default_ttl: int = 3600) -> None:
        self._host = host
        self._port = port
        self._db = db
        self._default_ttl = default_ttl
        self._client: Optional[redis.Redis] = None
        self._logger = LogManager().get_logger(__name__)
        self._initialize()

    def _initialize(self) -> None:
        if REDIS_AVAILABLE:
            try:
                self._client = redis.Redis(host=self._host, port=self._port, db=self._db, decode_responses=False)
                self._client.ping()
                self._logger.info("Redis cache initialized successfully")
            except Exception as e:
                self._logger.warning(f"Failed to connect to Redis: {e}. Using in-memory cache.")
                self._client = None
                self._memory_cache: dict = {}
        else:
            self._logger.warning("Redis library not available. Using in-memory cache.")
            self._client = None
            self._memory_cache: dict = {}

    async def get(self, key: str) -> Optional[Any]:
        try:
            if self._client is not None:
                data = self._client.get(key)
                if data:
                    return self._deserialize(data)
            else:
                if key in self._memory_cache:
                    return self._memory_cache[key]
        except Exception as e:
            self._logger.error(f"Error getting cache key '{key}': {e}")
        return None

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        try:
            ttl_seconds = ttl if ttl is not None else self._default_ttl
            if self._client is not None:
                serialized = self._serialize(value)
                self._client.setex(key, ttl_seconds, serialized)
            else:
                self._memory_cache[key] = value
        except Exception as e:
            self._logger.error(f"Error setting cache key '{key}': {e}")

    async def delete(self, key: str) -> None:
        try:
            if self._client is not None:
                self._client.delete(key)
            else:
                self._memory_cache.pop(key, None)
        except Exception as e:
            self._logger.error(f"Error deleting cache key '{key}': {e}")

    async def exists(self, key: str) -> bool:
        try:
            if self._client is not None:
                return bool(self._client.exists(key))
            else:
                return key in self._memory_cache
        except Exception as e:
            self._logger.error(f"Error checking cache key '{key}': {e}")
            return False

    async def clear_pattern(self, pattern: str) -> int:
        try:
            if self._client is not None:
                keys = list(self._client.scan_iter(match=pattern))
                if keys:
                    return self._client.delete(*keys)
                return 0
            else:
                deleted = 0
                for key in list(self._memory_cache.keys()):
                    if self._match_pattern(key, pattern):
                        del self._memory_cache[key]
                        deleted += 1
                return deleted
        except Exception as e:
            self._logger.error(f"Error clearing cache pattern '{pattern}': {e}")
            return 0

    def _serialize(self, value: Any) -> bytes:
        try:
            return json.dumps(value).encode("utf-8")
        except (TypeError, ValueError):
            return pickle.dumps(value)

    def _deserialize(self, data: bytes) -> Any:
        try:
            return json.loads(data.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return pickle.loads(data)

    def _match_pattern(self, key: str, pattern: str) -> bool:
        import fnmatch
        return fnmatch.fnmatch(key, pattern)
