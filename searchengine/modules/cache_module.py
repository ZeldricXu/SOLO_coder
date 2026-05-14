import logging
import json
import time
import threading
from typing import Dict, Any, Optional
from datetime import datetime, timedelta
from searchengine.config.settings import settings


class CacheModule:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self._cache: Dict[str, Dict[str, Any]] = {}
        self._lock = threading.Lock()
        self._default_ttl = settings.CACHE_TTL
        self._enabled = settings.CACHE_ENABLED
        self._cleanup_thread = None
        self._stop_cleanup = threading.Event()
    
    def set(self, key: str, value: Any, ttl: Optional[int] = None) -> bool:
        if not self._enabled:
            return False
        
        actual_ttl = ttl if ttl is not None else self._default_ttl
        expire_at = time.time() + actual_ttl
        
        try:
            with self._lock:
                self._cache[key] = {
                    "value": value,
                    "expire_at": expire_at,
                    "created_at": time.time(),
                    "hits": 0
                }
            self.logger.debug(f"Set cache key: {key}")
            return True
        except Exception as e:
            self.logger.error(f"Failed to set cache key {key}: {e}")
            return False
    
    def get(self, key: str) -> Optional[Any]:
        if not self._enabled:
            return None
        
        try:
            with self._lock:
                if key not in self._cache:
                    return None
                
                cache_item = self._cache[key]
                
                if self._is_expired(cache_item):
                    del self._cache[key]
                    return None
                
                cache_item["hits"] += 1
                return cache_item["value"]
        except Exception as e:
            self.logger.error(f"Failed to get cache key {key}: {e}")
            return None
    
    def delete(self, key: str) -> bool:
        try:
            with self._lock:
                if key in self._cache:
                    del self._cache[key]
                    self.logger.debug(f"Deleted cache key: {key}")
                    return True
            return False
        except Exception as e:
            self.logger.error(f"Failed to delete cache key {key}: {e}")
            return False
    
    def exists(self, key: str) -> bool:
        if not self._enabled:
            return False
        
        try:
            with self._lock:
                if key not in self._cache:
                    return False
                
                if self._is_expired(self._cache[key]):
                    del self._cache[key]
                    return False
                
                return True
        except Exception as e:
            self.logger.error(f"Failed to check cache key {key}: {e}")
            return False
    
    def _is_expired(self, cache_item: Dict[str, Any]) -> bool:
        expire_at = cache_item.get("expire_at", 0)
        return time.time() >= expire_at
    
    def get_or_set(self, key: str, func, ttl: Optional[int] = None) -> Any:
        cached_value = self.get(key)
        if cached_value is not None:
            return cached_value
        
        value = func()
        self.set(key, value, ttl)
        return value
    
    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total_keys = len(self._cache)
            total_hits = sum(item["hits"] for item in self._cache.values())
            expired_count = sum(1 for item in self._cache.values() if self._is_expired(item))
            
            return {
                "total_keys": total_keys,
                "total_hits": total_hits,
                "expired_keys": expired_count,
                "enabled": self._enabled,
                "default_ttl": self._default_ttl
            }
    
    def get_key_info(self, key: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            if key not in self._cache:
                return None
            
            item = self._cache[key]
            expire_at = item.get("expire_at", 0)
            remaining_ttl = max(0, expire_at - time.time())
            
            return {
                "key": key,
                "hits": item.get("hits", 0),
                "remaining_ttl": int(remaining_ttl),
                "created_at": datetime.fromtimestamp(item.get("created_at", 0)).isoformat()
            }
    
    def clear(self) -> int:
        with self._lock:
            count = len(self._cache)
            self._cache.clear()
            self.logger.info(f"Cleared {count} cache items")
            return count
    
    def clean_expired(self) -> int:
        with self._lock:
            keys_to_delete = [
                key for key, item in self._cache.items()
                if self._is_expired(item)
            ]
            for key in keys_to_delete:
                del self._cache[key]
            
            cleaned_count = len(keys_to_delete)
            if cleaned_count > 0:
                self.logger.info(f"Cleaned {cleaned_count} expired cache items")
            return cleaned_count
    
    def enable(self) -> None:
        self._enabled = True
        self.logger.info("Cache module enabled")
    
    def disable(self) -> None:
        self._enabled = False
        self.logger.info("Cache module disabled")
    
    def is_enabled(self) -> bool:
        return self._enabled
    
    def delete_pattern(self, pattern: str) -> int:
        import fnmatch
        with self._lock:
            keys_to_delete = [
                key for key in self._cache.keys()
                if fnmatch.fnmatch(key, pattern)
            ]
            for key in keys_to_delete:
                del self._cache[key]
            
            deleted_count = len(keys_to_delete)
            if deleted_count > 0:
                self.logger.info(f"Deleted {deleted_count} cache keys matching pattern: {pattern}")
            return deleted_count
    
    def get_all_keys(self) -> list:
        with self._lock:
            return list(self._cache.keys())


cache_module = CacheModule()
