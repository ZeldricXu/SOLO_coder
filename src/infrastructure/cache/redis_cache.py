import json
import logging
from typing import Any, Optional

from src.infrastructure.config.settings import RedisConfig

logger = logging.getLogger(__name__)


class RedisCache:
    def __init__(self, config: RedisConfig):
        self._config = config
        self._client = None

    def _get_client(self):
        if self._client is None:
            try:
                import redis
                self._client = redis.Redis(
                    host=self._config.host,
                    port=self._config.port,
                    db=self._config.db,
                    password=self._config.password or None,
                    max_connections=self._config.max_connections,
                    decode_responses=True,
                )
                self._client.ping()
            except Exception as e:
                logger.error(f"Failed to connect to Redis: {e}")
                raise
        return self._client

    def get(self, key: str) -> Optional[str]:
        try:
            client = self._get_client()
            return client.get(key)
        except Exception as e:
            logger.warning(f"Redis GET failed for key '{key}': {e}")
            return None

    def get_json(self, key: str) -> Optional[Any]:
        value = self.get(key)
        if value is None:
            return None
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return None

    def set(self, key: str, value: str, ttl: Optional[int] = None) -> bool:
        try:
            client = self._get_client()
            client.set(key, value, ex=ttl or self._config.default_ttl)
            return True
        except Exception as e:
            logger.warning(f"Redis SET failed for key '{key}': {e}")
            return False

    def set_json(self, key: str, value: Any, ttl: Optional[int] = None) -> bool:
        return self.set(key, json.dumps(value, ensure_ascii=False), ttl)

    def delete(self, key: str) -> bool:
        try:
            client = self._get_client()
            client.delete(key)
            return True
        except Exception as e:
            logger.warning(f"Redis DELETE failed for key '{key}': {e}")
            return False

    def exists(self, key: str) -> bool:
        try:
            client = self._get_client()
            return bool(client.exists(key))
        except Exception as e:
            logger.warning(f"Redis EXISTS failed for key '{key}': {e}")
            return False

    def mget(self, keys: list) -> list:
        try:
            client = self._get_client()
            return client.mget(keys)
        except Exception as e:
            logger.warning(f"Redis MGET failed: {e}")
            return [None] * len(keys)

    def mset(self, mapping: dict, ttl: Optional[int] = None) -> bool:
        try:
            client = self._get_client()
            pipe = client.pipeline()
            pipe.mset(mapping)
            if ttl:
                for key in mapping:
                    pipe.expire(key, ttl)
            pipe.execute()
            return True
        except Exception as e:
            logger.warning(f"Redis MSET failed: {e}")
            return False

    def incr(self, key: str, amount: int = 1) -> Optional[int]:
        try:
            client = self._get_client()
            return client.incr(key, amount)
        except Exception as e:
            logger.warning(f"Redis INCR failed for key '{key}': {e}")
            return None

    def expire(self, key: str, ttl: int) -> bool:
        try:
            client = self._get_client()
            return client.expire(key, ttl)
        except Exception as e:
            logger.warning(f"Redis EXPIRE failed for key '{key}': {e}")
            return False

    def keys(self, pattern: str) -> list:
        try:
            client = self._get_client()
            return client.keys(pattern)
        except Exception as e:
            logger.warning(f"Redis KEYS failed for pattern '{pattern}': {e}")
            return []

    def close(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None
