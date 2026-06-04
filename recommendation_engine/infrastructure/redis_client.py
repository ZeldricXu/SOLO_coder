import asyncio
from typing import Optional, Any, Dict, List
import json
from loguru import logger
from redis.asyncio import Redis, ConnectionPool

from config import settings


class RedisClient:
    _instance: Optional["RedisClient"] = None
    _pool: Optional[ConnectionPool] = None
    _client: Optional[Redis] = None

    def __new__(cls) -> "RedisClient":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    async def initialize(self) -> None:
        if self._pool is not None:
            return
        try:
            pool_kwargs: Dict[str, Any] = {
                "host": settings.redis_host,
                "port": settings.redis_port,
                "db": settings.redis_db,
                "max_connections": settings.redis_max_connections,
                "encoding": "utf-8",
                "decode_responses": True,
            }
            if settings.redis_password:
                pool_kwargs["password"] = settings.redis_password

            self._pool = ConnectionPool(**pool_kwargs)
            self._client = Redis(connection_pool=self._pool)

            await self._client.ping()
            logger.info(
                f"Redis connected successfully to {settings.redis_host}:{settings.redis_port}"
            )
        except Exception as e:
            logger.error(f"Failed to connect to Redis: {e}")
            raise

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None
        if self._pool is not None:
            await self._pool.aclose()
            self._pool = None
        logger.info("Redis connection closed")

    def _get_client(self) -> Redis:
        if self._client is None:
            raise RuntimeError("Redis client not initialized")
        return self._client

    async def set(
        self,
        key: str,
        value: Any,
        ttl_seconds: Optional[int] = None,
        nx: bool = False,
    ) -> bool:
        client = self._get_client()
        if isinstance(value, (dict, list)):
            value = json.dumps(value, ensure_ascii=False)
        elif not isinstance(value, (str, bytes, int, float)):
            value = str(value)

        kwargs: Dict[str, Any] = {"nx": nx}
        if ttl_seconds is not None:
            kwargs["ex"] = ttl_seconds

        result = await client.set(key, value, **kwargs)
        return result is not None

    async def get(self, key: str) -> Optional[str]:
        client = self._get_client()
        return await client.get(key)

    async def get_json(self, key: str) -> Optional[Any]:
        value = await self.get(key)
        if value is None:
            return None
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value

    async def delete(self, *keys: str) -> int:
        client = self._get_client()
        return await client.delete(*keys)

    async def exists(self, key: str) -> bool:
        client = self._get_client()
        return await client.exists(key) > 0

    async def expire(self, key: str, ttl_seconds: int) -> bool:
        client = self._get_client()
        return await client.expire(key, ttl_seconds)

    async def incr(self, key: str, amount: int = 1) -> int:
        client = self._get_client()
        return await client.incr(key, amount)

    async def incrbyfloat(self, key: str, amount: float) -> float:
        client = self._get_client()
        return await client.incrbyfloat(key, amount)

    async def hset(self, key: str, mapping: Dict[str, Any]) -> int:
        client = self._get_client()
        str_mapping = {
            k: json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else str(v)
            for k, v in mapping.items()
        }
        return await client.hset(key, mapping=str_mapping)

    async def hget(self, key: str, field: str) -> Optional[str]:
        client = self._get_client()
        return await client.hget(key, field)

    async def hgetall(self, key: str) -> Dict[str, str]:
        client = self._get_client()
        return await client.hgetall(key)

    async def hincrby(self, key: str, field: str, amount: int = 1) -> int:
        client = self._get_client()
        return await client.hincrby(key, field, amount)

    async def hincrbyfloat(self, key: str, field: str, amount: float) -> float:
        client = self._get_client()
        return await client.hincrbyfloat(key, field, amount)

    async def lpush(self, key: str, *values: Any) -> int:
        client = self._get_client()
        str_values = [
            json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else str(v)
            for v in values
        ]
        return await client.lpush(key, *str_values)

    async def rpush(self, key: str, *values: Any) -> int:
        client = self._get_client()
        str_values = [
            json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else str(v)
            for v in values
        ]
        return await client.rpush(key, *str_values)

    async def lrange(self, key: str, start: int, end: int) -> List[str]:
        client = self._get_client()
        return await client.lrange(key, start, end)

    async def lpop(self, key: str, count: int = 1) -> Optional[List[str]]:
        client = self._get_client()
        return await client.lpop(key, count)

    async def zadd(
        self, key: str, mapping: Dict[str, float], nx: bool = False, xx: bool = False
    ) -> int:
        client = self._get_client()
        return await client.zadd(key, mapping, nx=nx, xx=xx)

    async def zrange(
        self, key: str, start: int, end: int, desc: bool = False, withscores: bool = False
    ) -> List:
        client = self._get_client()
        return await client.zrange(
            key, start, end, desc=desc, withscores=withscores
        )

    async def zrevrangebyscore(
        self,
        key: str,
        max_score: float = float("inf"),
        min_score: float = float("-inf"),
        offset: int = 0,
        count: int = 10,
        withscores: bool = False,
    ) -> List:
        client = self._get_client()
        return await client.zrevrangebyscore(
            key, max_score, min_score, offset=offset, count=count, withscores=withscores
        )

    async def zscore(self, key: str, member: str) -> Optional[float]:
        client = self._get_client()
        return await client.zscore(key, member)

    async def zincrby(self, key: str, amount: float, member: str) -> float:
        client = self._get_client()
        return await client.zincrby(key, amount, member)

    async def sadd(self, key: str, *members: Any) -> int:
        client = self._get_client()
        str_members = [
            json.dumps(m, ensure_ascii=False) if isinstance(m, (dict, list)) else str(m)
            for m in members
        ]
        return await client.sadd(key, *str_members)

    async def sismember(self, key: str, member: Any) -> bool:
        client = self._get_client()
        if isinstance(member, (dict, list)):
            member = json.dumps(member, ensure_ascii=False)
        return await client.sismember(key, str(member))

    async def smembers(self, key: str) -> set:
        client = self._get_client()
        return await client.smembers(key)

    def pipeline(self) -> Any:
        client = self._get_client()
        return client.pipeline()

    async def ping(self) -> bool:
        try:
            client = self._get_client()
            return await client.ping()
        except Exception:
            return False

    async def mget(self, keys: List[str]) -> List[Optional[str]]:
        client = self._get_client()
        return await client.mget(keys)

    async def mset(self, mapping: Dict[str, Any], ttl_seconds: Optional[int] = None) -> None:
        client = self._get_client()
        pipe = client.pipeline()
        for key, value in mapping.items():
            if isinstance(value, (dict, list)):
                value = json.dumps(value, ensure_ascii=False)
            pipe.set(key, value)
            if ttl_seconds is not None:
                pipe.expire(key, ttl_seconds)
        await pipe.execute()


_redis_client: Optional[RedisClient] = None


async def get_redis_client() -> RedisClient:
    global _redis_client
    if _redis_client is None:
        _redis_client = RedisClient()
        await _redis_client.initialize()
    return _redis_client


async def close_redis_client() -> None:
    global _redis_client
    if _redis_client is not None:
        await _redis_client.close()
        _redis_client = None
