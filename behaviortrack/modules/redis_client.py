import json
import logging
import threading
from typing import Any, Dict, List, Optional
from contextlib import contextmanager

from ..config import settings


logger = logging.getLogger(__name__)


class RedisClientManager:
    _instance: Optional["RedisClientManager"] = None
    _lock = threading.Lock()
    
    def __init__(self):
        self._redis = None
        self._connected = False
        self._connect_lock = threading.Lock()
    
    @classmethod
    def get_instance(cls) -> "RedisClientManager":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = cls()
        return cls._instance
    
    def _connect(self) -> bool:
        try:
            import redis
        except ImportError:
            logger.error("Redis library not installed. Run: pip install redis")
            return False
        
        with self._connect_lock:
            if self._connected and self._redis:
                return True
            
            try:
                self._redis = redis.Redis(
                    host=settings.REDIS_HOST,
                    port=settings.REDIS_PORT,
                    password=settings.REDIS_PASSWORD,
                    db=settings.REDIS_DB,
                    ssl=settings.REDIS_USE_SSL,
                    decode_responses=True,
                    socket_timeout=5,
                    socket_connect_timeout=5,
                    retry_on_timeout=True
                )
                
                self._redis.ping()
                self._connected = True
                logger.info(f"Redis connected successfully: {settings.REDIS_HOST}:{settings.REDIS_PORT}")
                return True
                
            except Exception as e:
                logger.error(f"Failed to connect to Redis: {str(e)}")
                self._connected = False
                return False
    
    def is_connected(self) -> bool:
        if not self._connected:
            return self._connect()
        return self._connected
    
    def get_client(self):
        if self.is_connected():
            return self._redis
        return None
    
    @contextmanager
    def pipeline(self):
        if not self.is_connected():
            yield None
            return
        
        pipe = self._redis.pipeline()
        try:
            yield pipe
            pipe.execute()
        except Exception as e:
            logger.exception(f"Redis pipeline error: {str(e)}")
            pipe.reset()
            raise
    
    def enqueue(self, queue_key: str, item: Any) -> bool:
        if not self.is_connected():
            return False
        
        try:
            serialized = json.dumps(item, ensure_ascii=False)
            self._redis.rpush(queue_key, serialized)
            return True
        except Exception as e:
            logger.exception(f"Failed to enqueue to Redis: {str(e)}")
            return False
    
    def enqueue_batch(self, queue_key: str, items: List[Any]) -> int:
        if not self.is_connected():
            return 0
        
        try:
            count = 0
            with self.pipeline() as pipe:
                if pipe is None:
                    return 0
                
                for item in items:
                    serialized = json.dumps(item, ensure_ascii=False)
                    pipe.rpush(queue_key, serialized)
                    count += 1
            
            return count
        except Exception as e:
            logger.exception(f"Failed to enqueue batch to Redis: {str(e)}")
            return 0
    
    def dequeue(self, queue_key: str, timeout: int = 0) -> Optional[Any]:
        if not self.is_connected():
            return None
        
        try:
            if timeout > 0:
                result = self._redis.blpop(queue_key, timeout=timeout)
                if result:
                    serialized = result[1]
                    return json.loads(serialized)
            else:
                result = self._redis.lpop(queue_key)
                if result:
                    return json.loads(result)
            
            return None
        except Exception as e:
            logger.exception(f"Failed to dequeue from Redis: {str(e)}")
            return None
    
    def dequeue_batch(self, queue_key: str, batch_size: int = 100) -> List[Any]:
        if not self.is_connected() or batch_size <= 0:
            return []
        
        items = []
        try:
            with self.pipeline() as pipe:
                if pipe is None:
                    return []
                
                for _ in range(batch_size):
                    pipe.lpop(queue_key)
            
            for result in pipe.execute():
                if result:
                    try:
                        items.append(json.loads(result))
                    except:
                        pass
            
            return items
        except Exception as e:
            logger.exception(f"Failed to dequeue batch from Redis: {str(e)}")
            return items
    
    def queue_size(self, queue_key: str) -> int:
        if not self.is_connected():
            return 0
        
        try:
            return self._redis.llen(queue_key)
        except Exception as e:
            logger.exception(f"Failed to get queue size: {str(e)}")
            return 0
    
    def clear_queue(self, queue_key: str) -> bool:
        if not self.is_connected():
            return False
        
        try:
            self._redis.delete(queue_key)
            return True
        except Exception as e:
            logger.exception(f"Failed to clear queue: {str(e)}")
            return False
    
    def set_cache(self, key: str, value: Any, ttl_seconds: Optional[int] = None) -> bool:
        if not self.is_connected():
            return False
        
        try:
            serialized = json.dumps(value, ensure_ascii=False)
            if ttl_seconds and ttl_seconds > 0:
                self._redis.setex(key, ttl_seconds, serialized)
            else:
                self._redis.set(key, serialized)
            return True
        except Exception as e:
            logger.exception(f"Failed to set cache: {str(e)}")
            return False
    
    def get_cache(self, key: str) -> Optional[Any]:
        if not self.is_connected():
            return None
        
        try:
            result = self._redis.get(key)
            if result:
                return json.loads(result)
            return None
        except Exception as e:
            logger.exception(f"Failed to get cache: {str(e)}")
            return None
    
    def delete_cache(self, key: str) -> bool:
        if not self.is_connected():
            return False
        
        try:
            return self._redis.delete(key) > 0
        except Exception as e:
            logger.exception(f"Failed to delete cache: {str(e)}")
            return False
    
    def cache_exists(self, key: str) -> bool:
        if not self.is_connected():
            return False
        
        try:
            return self._redis.exists(key) > 0
        except Exception as e:
            logger.exception(f"Failed to check cache existence: {str(e)}")
            return False
    
    def incrby(self, key: str, amount: int = 1) -> Optional[int]:
        if not self.is_connected():
            return None
        
        try:
            return self._redis.incrby(key, amount)
        except Exception as e:
            logger.exception(f"Failed to increment: {str(e)}")
            return None
    
    def sadd(self, key: str, members: List[Any]) -> int:
        if not self.is_connected():
            return 0
        
        try:
            serialized = [json.dumps(m, ensure_ascii=False) for m in members]
            return self._redis.sadd(key, *serialized)
        except Exception as e:
            logger.exception(f"Failed to add to set: {str(e)}")
            return 0
    
    def scard(self, key: str) -> int:
        if not self.is_connected():
            return 0
        
        try:
            return self._redis.scard(key)
        except Exception as e:
            logger.exception(f"Failed to get set size: {str(e)}")
            return 0
    
    def delete_pattern(self, pattern: str) -> int:
        if not self.is_connected():
            return 0
        
        try:
            keys = self._redis.keys(pattern)
            if keys:
                return self._redis.delete(*keys)
            return 0
        except Exception as e:
            logger.exception(f"Failed to delete pattern: {str(e)}")
            return 0
    
    def close(self) -> None:
        if self._redis:
            try:
                self._redis.close()
            except:
                pass
        self._redis = None
        self._connected = False


redis_manager = RedisClientManager()
