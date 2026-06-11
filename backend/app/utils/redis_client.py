import redis
from app.config import settings
import logging

logger = logging.getLogger(__name__)


class RedisManager:
    _instance = None
    _client = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._connect()
        return cls._instance

    def _connect(self):
        try:
            self._client = redis.Redis(
                host=settings.REDIS_HOST,
                port=settings.REDIS_PORT,
                db=settings.REDIS_DB,
                password=settings.REDIS_PASSWORD,
                decode_responses=True,
            )
            self._client.ping()
            logger.info("Redis connection established successfully")
        except Exception as e:
            logger.error(f"Failed to connect to Redis: {e}")
            self._client = None

    def get_client(self):
        if self._client is None:
            self._connect()
        return self._client

    def get(self, key: str):
        client = self.get_client()
        if client:
            return client.get(key)
        return None

    def set(self, key: str, value: str, expire: int = 3600):
        client = self.get_client()
        if client:
            return client.setex(key, expire, value)
        return None

    def delete(self, key: str):
        client = self.get_client()
        if client:
            return client.delete(key)
        return None

    def exists(self, key: str) -> bool:
        client = self.get_client()
        if client:
            return client.exists(key) > 0
        return False


redis_manager = RedisManager()
