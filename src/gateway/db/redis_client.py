from typing import Optional
import redis.asyncio as redis
from redis.asyncio import Redis, ConnectionPool

from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("redis")

_pool: Optional[ConnectionPool] = None
_client: Optional[Redis] = None


def get_connection_pool() -> ConnectionPool:
    global _pool
    if _pool is None:
        settings = get_settings()
        _pool = ConnectionPool.from_url(
            settings.redis.url,
            max_connections=settings.redis.max_connections,
            decode_responses=settings.redis.decode_responses,
            socket_connect_timeout=5,
            socket_timeout=5,
            socket_keepalive=True,
            retry_on_timeout=True,
        )
    return _pool


def get_redis() -> Redis:
    global _client
    if _client is None:
        pool = get_connection_pool()
        _client = Redis(connection_pool=pool)
    return _client


async def init_redis() -> None:
    logger.info("Initializing Redis connection...")
    client = get_redis()
    try:
        await client.ping()
        logger.info("Redis connection initialized successfully")
    except Exception as e:
        logger.error("Failed to connect to Redis", error=str(e))
        raise


async def close_redis() -> None:
    global _client, _pool
    if _client:
        await _client.close()
        _client = None
    if _pool:
        await _pool.disconnect()
        _pool = None
    logger.info("Redis connections closed")
