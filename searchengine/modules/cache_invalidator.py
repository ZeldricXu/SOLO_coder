import logging
import json
import threading
import time
from typing import Dict, Any, List, Optional, Callable, Set
from datetime import datetime
from collections import defaultdict


class CacheInvalidationEvent:
    def __init__(
        self,
        event_type: str,
        content_id: str,
        version: int,
        metadata: Dict[str, Any] = None
    ):
        self.event_type = event_type
        self.content_id = content_id
        self.version = version
        self.metadata = metadata or {}
        self.timestamp = datetime.utcnow()
        self.event_id = f"event_{hash(f'{event_type}{content_id}{version}{self.timestamp.timestamp()}') & 0xFFFFFFFF:08x}"


class CacheInvalidationStats:
    def __init__(self):
        self._lock = threading.Lock()
        self._stats = {
            "total_events": 0,
            "successful_invalidations": 0,
            "failed_invalidations": 0,
            "invalidation_latency": [],
            "events_by_type": defaultdict(int),
            "invalidations_by_pattern": defaultdict(int)
        }
    
    def record_event(self, event: CacheInvalidationEvent, success: bool, latency: float = 0.0):
        with self._lock:
            self._stats["total_events"] += 1
            self._stats["events_by_type"][event.event_type] += 1
            
            if success:
                self._stats["successful_invalidations"] += 1
            else:
                self._stats["failed_invalidations"] += 1
            
            self._stats["invalidation_latency"].append(latency)
            
            if len(self._stats["invalidation_latency"]) > 1000:
                self._stats["invalidation_latency"] = self._stats["invalidation_latency"][-1000:]
    
    def record_pattern_invalidation(self, pattern: str):
        with self._lock:
            self._stats["invalidations_by_pattern"][pattern] += 1
    
    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            stats = self._stats.copy()
            latencies = stats["invalidation_latency"]
            
            if latencies:
                stats["avg_latency"] = sum(latencies) / len(latencies)
                stats["max_latency"] = max(latencies)
                stats["min_latency"] = min(latencies)
                stats["p95_latency"] = sorted(latencies)[int(len(latencies) * 0.95)] if len(latencies) > 20 else 0
            else:
                stats["avg_latency"] = 0
                stats["max_latency"] = 0
                stats["min_latency"] = 0
                stats["p95_latency"] = 0
            
            stats["events_by_type"] = dict(stats["events_by_type"])
            stats["invalidations_by_pattern"] = dict(stats["invalidations_by_pattern"])
            
            if stats["total_events"] > 0:
                stats["success_rate"] = stats["successful_invalidations"] / stats["total_events"]
            else:
                stats["success_rate"] = 1.0
            
            return stats


class CacheInvalidator:
    def __init__(self, cache_module=None):
        self.logger = logging.getLogger(__name__)
        self._cache = cache_module
        self._stats = CacheInvalidationStats()
        self._lock = threading.RLock()
        self._listeners: List[Callable] = []
        self._pending_events: List[CacheInvalidationEvent] = []
        self._enabled = True
        self._patterns = [
            "search:query:*",
            "recommend:*",
            "cache:index:*"
        ]
    
    def set_cache_module(self, cache_module):
        with self._lock:
            self._cache = cache_module
    
    def enable(self):
        with self._lock:
            self._enabled = True
            self.logger.info("Cache invalidator enabled")
    
    def disable(self):
        with self._lock:
            self._enabled = False
            self.logger.info("Cache invalidator disabled")
    
    def is_enabled(self) -> bool:
        with self._lock:
            return self._enabled
    
    def add_listener(self, listener: Callable):
        with self._lock:
            self._listeners.append(listener)
            self.logger.info(f"Added cache invalidation listener")
    
    def remove_listener(self, listener: Callable) -> bool:
        with self._lock:
            try:
                self._listeners.remove(listener)
                return True
            except ValueError:
                return False
    
    def add_invalidation_pattern(self, pattern: str):
        with self._lock:
            if pattern not in self._patterns:
                self._patterns.append(pattern)
                self.logger.info(f"Added invalidation pattern: {pattern}")
    
    def remove_invalidation_pattern(self, pattern: str) -> bool:
        with self._lock:
            if pattern in self._patterns:
                self._patterns.remove(pattern)
                self.logger.info(f"Removed invalidation pattern: {pattern}")
                return True
            return False
    
    def _notify_listeners(self, event: CacheInvalidationEvent):
        for listener in self._listeners:
            try:
                listener(event)
            except Exception as e:
                self.logger.error(f"Cache invalidation listener error: {e}")
    
    def invalidate_on_index_update(self, event_data: Dict[str, Any]):
        with self._lock:
            if not self._enabled:
                self.logger.debug("Cache invalidator disabled, skipping")
                return
            
            if self._cache is None:
                self.logger.warning("Cache module not set, skipping invalidation")
                return
        
        start_time = time.time()
        
        event = CacheInvalidationEvent(
            event_type=event_data.get("action", "update"),
            content_id=event_data.get("content_id", ""),
            version=event_data.get("version", 0),
            metadata=event_data
        )
        
        try:
            with self._lock:
                patterns = self._patterns.copy()
            
            total_deleted = 0
            for pattern in patterns:
                deleted = self._cache.delete_pattern(pattern)
                total_deleted += deleted
                self._stats.record_pattern_invalidation(pattern)
                self.logger.debug(f"Invalidated {deleted} items with pattern: {pattern}")
            
            content_specific_key = f"cache:index:{event.content_id}"
            if self._cache.exists(content_specific_key):
                self._cache.delete(content_specific_key)
                total_deleted += 1
            
            latency = time.time() - start_time
            self._stats.record_event(event, True, latency)
            
            self._notify_listeners(event)
            
            self.logger.info(
                f"Cache invalidation completed: {total_deleted} items deleted, "
                f"event: {event.event_type}, content: {event.content_id}, "
                f"latency: {latency*1000:.2f}ms"
            )
            
            return total_deleted
            
        except Exception as e:
            latency = time.time() - start_time
            self._stats.record_event(event, False, latency)
            self.logger.error(f"Cache invalidation failed: {e}")
            return 0
    
    def invalidate_specific_keys(self, keys: List[str]) -> int:
        if self._cache is None:
            return 0
        
        start_time = time.time()
        deleted_count = 0
        
        event = CacheInvalidationEvent(
            event_type="manual",
            content_id="manual",
            version=0,
            metadata={"keys": keys}
        )
        
        try:
            for key in keys:
                if self._cache.delete(key):
                    deleted_count += 1
            
            latency = time.time() - start_time
            self._stats.record_event(event, True, latency)
            self._notify_listeners(event)
            
            self.logger.info(
                f"Manual cache invalidation: {deleted_count}/{len(keys)} keys deleted"
            )
            
            return deleted_count
            
        except Exception as e:
            latency = time.time() - start_time
            self._stats.record_event(event, False, latency)
            self.logger.error(f"Manual cache invalidation failed: {e}")
            return 0
    
    def invalidate_all(self) -> int:
        if self._cache is None:
            return 0
        
        start_time = time.time()
        
        event = CacheInvalidationEvent(
            event_type="full_clear",
            content_id="*",
            version=0,
            metadata={}
        )
        
        try:
            count = self._cache.clear()
            latency = time.time() - start_time
            
            self._stats.record_event(event, True, latency)
            self._notify_listeners(event)
            
            self.logger.info(f"Full cache invalidation: {count} items cleared")
            return count
            
        except Exception as e:
            latency = time.time() - start_time
            self._stats.record_event(event, False, latency)
            self.logger.error(f"Full cache invalidation failed: {e}")
            return 0
    
    def get_stats(self) -> Dict[str, Any]:
        return self._stats.get_stats()
    
    def reset_stats(self):
        with self._lock:
            self._stats = CacheInvalidationStats()
    
    def setup_integration(self, index_manager, cache_module):
        self.set_cache_module(cache_module)
        index_manager.add_event_listener("index.updated", self.invalidate_on_index_update)
        self.logger.info("Cache invalidator integrated with index manager")


cache_invalidator = CacheInvalidator()
