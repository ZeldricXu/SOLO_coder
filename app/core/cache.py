from __future__ import annotations

import json
import pickle
from typing import Any, Optional, Union, List, Dict, Set
from redis import Redis

try:
    from rediscluster import RedisCluster
except ImportError:
    RedisCluster = None  # type: ignore

from app.core.config import settings


class CacheManager:
    def __init__(self):
        self._redis: Optional[Redis] = None
        self._cluster: Optional[RedisCluster] = None
        self._use_cluster = False

    def connect(self) -> None:
        if RedisCluster is not None:
            try:
                self._cluster = RedisCluster(
                    startup_nodes=settings.redis_cluster_node_list,
                    decode_responses=False,
                    skip_full_coverage_check=True,
                )
                self._cluster.ping()
                self._use_cluster = True
                return
            except Exception:
                pass
        self._redis = Redis.from_url(settings.REDIS_BROKER_URL)
        self._use_cluster = False

    def _get_client(self) -> Union[Redis, RedisCluster]:
        if self._use_cluster and self._cluster:
            return self._cluster
        if self._redis is None:
            self.connect()
        assert self._redis is not None
        return self._redis

    def get(self, key: str) -> Optional[Any]:
        client = self._get_client()
        value = client.get(key)
        if value is None:
            return None
        try:
            return pickle.loads(value)
        except (pickle.UnpicklingError, TypeError):
            try:
                return json.loads(value)
            except (json.JSONDecodeError, TypeError):
                return value.decode("utf-8") if isinstance(value, bytes) else value

    def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        client = self._get_client()
        if isinstance(value, (dict, list, tuple)):
            serialized = pickle.dumps(value)
        elif isinstance(value, str):
            serialized = value.encode("utf-8")
        else:
            serialized = pickle.dumps(value)

        if ttl:
            client.setex(key, ttl, serialized)
        else:
            client.set(key, serialized)

    def delete(self, key: str) -> None:
        client = self._get_client()
        client.delete(key)

    def delete_pattern(self, pattern: str) -> None:
        client = self._get_client()
        for key in client.scan_iter(match=pattern):
            client.delete(key)

    def exists(self, key: str) -> bool:
        client = self._get_client()
        return client.exists(key) > 0

    def incr(self, key: str, amount: int = 1) -> int:
        client = self._get_client()
        return client.incrby(key, amount)

    def expire(self, key: str, ttl: int) -> None:
        client = self._get_client()
        client.expire(key, ttl)

    def sadd(self, key: str, *values: Any) -> int:
        client = self._get_client()
        return client.sadd(key, *[pickle.dumps(v) for v in values])

    def srem(self, key: str, *values: Any) -> int:
        client = self._get_client()
        return client.srem(key, *[pickle.dumps(v) for v in values])

    def smembers(self, key: str) -> Set[Any]:
        client = self._get_client()
        return {pickle.loads(v) for v in client.smembers(key)}

    def rpush(self, key: str, *values: Any) -> int:
        client = self._get_client()
        return client.rpush(key, *[pickle.dumps(v) for v in values])

    def lpop(self, key: str) -> Optional[Any]:
        client = self._get_client()
        value = client.lpop(key)
        return pickle.loads(value) if value else None

    def lrange(self, key: str, start: int = 0, end: int = -1) -> List[Any]:
        client = self._get_client()
        return [pickle.loads(v) for v in client.lrange(key, start, end)]

    def hset(self, key: str, field: str, value: Any) -> int:
        client = self._get_client()
        return client.hset(key, field, pickle.dumps(value))

    def hget(self, key: str, field: str) -> Optional[Any]:
        client = self._get_client()
        value = client.hget(key, field)
        return pickle.loads(value) if value else None

    def hgetall(self, key: str) -> Dict[str, Any]:
        client = self._get_client()
        return {k.decode("utf-8"): pickle.loads(v) for k, v in client.hgetall(key).items()}


cache = CacheManager()
