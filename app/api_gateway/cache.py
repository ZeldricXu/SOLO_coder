import asyncio
import time
import hashlib
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Any, Tuple
from threading import Lock
from collections import OrderedDict
from app.logging_module import get_logger


logger = get_logger(__name__)


class CacheError(Exception):
    def __init__(self, code: str, message: str, details: Any = None):
        self.code = code
        self.message = message
        self.details = details
        super().__init__(message)


class FastLRUCache:
    __slots__ = (
        '_max_size', '_default_ttl', '_cache', '_lock',
        '_hits', '_misses', '_evictions', '_expire_check_count'
    )
    
    def __init__(self, max_size: int = 1000, default_ttl_seconds: int = 300):
        self._max_size = max_size
        self._default_ttl = default_ttl_seconds
        self._cache: OrderedDict = OrderedDict()
        self._lock = Lock()
        self._hits = 0
        self._misses = 0
        self._evictions = 0
        self._expire_check_count = 0
    
    def get_nowait(self, key: str) -> Tuple[Optional[Any], bool]:
        with self._lock:
            entry = self._cache.get(key)
            if entry is None:
                self._misses += 1
                return None, False
            
            value, expires_at = entry
            
            if expires_at is not None and time.time() > expires_at:
                del self._cache[key]
                self._evictions += 1
                self._misses += 1
                return None, False
            
            self._cache.move_to_end(key)
            self._hits += 1
            return value, True
    
    def set_nowait(self, key: str, value: Any, ttl_seconds: Optional[int] = None) -> bool:
        with self._lock:
            if ttl_seconds is not None:
                expires_at = time.time() + ttl_seconds
            elif self._default_ttl > 0:
                expires_at = time.time() + self._default_ttl
            else:
                expires_at = None
            
            if key in self._cache:
                del self._cache[key]
            
            while len(self._cache) >= self._max_size:
                self._cache.popitem(last=False)
                self._evictions += 1
            
            self._cache[key] = (value, expires_at)
            return True
    
    def delete_nowait(self, key: str) -> bool:
        with self._lock:
            if key in self._cache:
                del self._cache[key]
                return True
            return False
    
    def clear_nowait(self):
        with self._lock:
            self._cache.clear()
            logger.info("Cache cleared")
    
    def cleanup_expired_nowait(self) -> int:
        with self._lock:
            now = time.time()
            expired = 0
            keys_to_delete = []
            
            for key, (_, expires_at) in self._cache.items():
                if expires_at is not None and now > expires_at:
                    keys_to_delete.append(key)
            
            for key in keys_to_delete:
                del self._cache[key]
                expired += 1
            
            self._evictions += expired
            return expired
    
    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total = self._hits + self._misses
            hit_rate = self._hits / total if total > 0 else 0.0
            
            return {
                "size": len(self._cache),
                "max_size": self._max_size,
                "hits": self._hits,
                "misses": self._misses,
                "evictions": self._evictions,
                "hit_rate": hit_rate,
                "default_ttl_seconds": self._default_ttl
            }
    
    def size(self) -> int:
        with self._lock:
            return len(self._cache)


class CacheKeyGenerator:
    __slots__ = ()
    
    @staticmethod
    def generate(
        method: str,
        path: str,
        query_params: Dict[str, Any] = None,
        body: Any = None
    ) -> str:
        parts = [method.upper(), path]
        
        if query_params:
            items = sorted(query_params.items())
            buffer = []
            for k, v in items:
                buffer.append(k)
                buffer.append('=')
                buffer.append(str(v))
                buffer.append('&')
            if buffer:
                buffer.pop()
            parts.append(''.join(buffer))
        
        if body is not None:
            if isinstance(body, dict):
                body_str = ''.join(f"{k}:{v}" for k, v in sorted(body.items()))
            else:
                body_str = str(body)
            parts.append(hashlib.md5(body_str.encode(), usedforsecurity=False).hexdigest()[:16])
        
        raw = '|'.join(parts)
        return hashlib.sha256(raw.encode(), usedforsecurity=False).hexdigest()


class StatsAccumulator:
    __slots__ = ('_stats', '_lock')
    
    def __init__(self):
        self._stats: Dict[str, Dict[str, int]] = {}
        self._lock = Lock()
    
    def record_hit(self, path: str):
        with self._lock:
            if path not in self._stats:
                self._stats[path] = {"hits": 0, "misses": 0}
            self._stats[path]["hits"] += 1
    
    def record_miss(self, path: str):
        with self._lock:
            if path not in self._stats:
                self._stats[path] = {"hits": 0, "misses": 0}
            self._stats[path]["misses"] += 1
    
    def get_snapshot(self) -> Tuple[Dict[str, Dict[str, int]], int, int]:
        with self._lock:
            snapshot = {k: v.copy() for k, v in self._stats.items()}
            total_hits = sum(s["hits"] for s in self._stats.values())
            total_misses = sum(s["misses"] for s in self._stats.values())
            return snapshot, total_hits, total_misses


class ResponseCache:
    __slots__ = (
        '_memory_cache', '_enable_persistence', '_session_factory',
        '_stats', '_cleanup_task', '_running'
    )
    
    def __init__(
        self,
        memory_cache: FastLRUCache = None,
        enable_persistence: bool = False,
        session_factory=None
    ):
        self._memory_cache = memory_cache or FastLRUCache()
        self._enable_persistence = enable_persistence
        self._session_factory = session_factory
        self._stats = StatsAccumulator()
        self._cleanup_task: Optional[asyncio.Task] = None
        self._running = False
        
        logger.info(
            "Response cache initialized",
            memory_enabled=True,
            persistence_enabled=enable_persistence
        )
    
    async def start(self):
        if self._running:
            return
        
        self._running = True
        self._cleanup_task = asyncio.create_task(self._cleanup_loop())
        logger.info("Response cache started")
    
    async def stop(self):
        self._running = False
        if self._cleanup_task:
            self._cleanup_task.cancel()
            try:
                await self._cleanup_task
            except asyncio.CancelledError:
                pass
        logger.info("Response cache stopped")
    
    async def get(
        self,
        method: str,
        path: str,
        query_params: Dict[str, Any] = None,
        body: Any = None,
        headers: Dict[str, str] = None
    ) -> Tuple[Optional[Any], bool]:
        cache_key = CacheKeyGenerator.generate(
            method=method,
            path=path,
            query_params=query_params,
            body=body
        )
        
        value, hit = self._memory_cache.get_nowait(cache_key)
        
        if hit:
            self._stats.record_hit(path)
            logger.debug(f"Cache hit", path=path)
        else:
            self._stats.record_miss(path)
            logger.debug(f"Cache miss", path=path)
        
        return value, hit
    
    async def set(
        self,
        method: str,
        path: str,
        value: Any,
        ttl_seconds: Optional[int] = None,
        query_params: Dict[str, Any] = None,
        body: Any = None,
        headers: Dict[str, str] = None
    ) -> bool:
        cache_key = CacheKeyGenerator.generate(
            method=method,
            path=path,
            query_params=query_params,
            body=body
        )
        
        success = self._memory_cache.set_nowait(cache_key, value, ttl_seconds)
        
        if success and self._enable_persistence and self._session_factory:
            try:
                await self._persist_to_db(cache_key, value, path, ttl_seconds)
            except Exception as e:
                logger.warning(f"Cache persistence failed", error=str(e))
        
        logger.debug(f"Cache set", path=path, ttl=ttl_seconds)
        return success
    
    async def invalidate(self, path: str = None, cache_key: str = None) -> int:
        if cache_key:
            deleted = 1 if self._memory_cache.delete_nowait(cache_key) else 0
            logger.info(f"Invalidated cache entry", key=cache_key[:16] if len(cache_key) > 16 else cache_key)
            return deleted
        
        deleted = 0
        if path:
            logger.info(f"Invalidated cache entries for path", path=path, count=0)
        
        return deleted
    
    async def invalidate_all(self):
        self._memory_cache.clear_nowait()
        logger.info("All cache entries invalidated")
    
    async def _persist_to_db(
        self,
        cache_key: str,
        value: Any,
        route_path: str,
        ttl_seconds: Optional[int]
    ):
        from sqlalchemy import select
        from app.data_access.models import CacheEntry as DBCacheEntry
        
        async with self._session_factory() as session:
            stmt = select(DBCacheEntry).where(DBCacheEntry.cache_key == cache_key)
            result = await session.execute(stmt)
            existing = result.scalar_one_or_none()
            
            expires_at = None
            if ttl_seconds:
                expires_at = datetime.utcnow() + timedelta(seconds=ttl_seconds)
            
            if existing:
                existing.cache_value = {"data": value} if isinstance(value, (dict, list, str, int, float, bool)) else {"data": str(value)}
                existing.expires_at = expires_at
                existing.hit_count = (existing.hit_count or 0) + 1
            else:
                entry = DBCacheEntry(
                    cache_key=cache_key,
                    cache_value={"data": value} if isinstance(value, (dict, list, str, int, float, bool)) else {"data": str(value)},
                    route_path=route_path,
                    expires_at=expires_at,
                    hit_count=1
                )
                session.add(entry)
            
            await session.commit()
    
    async def _cleanup_loop(self):
        while self._running:
            try:
                await asyncio.sleep(60)
                expired = self._memory_cache.cleanup_expired_nowait()
                if expired > 0:
                    logger.debug(f"Cleaned up {expired} expired cache entries")
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.error(f"Cache cleanup error", error=str(e))
    
    def get_stats(self) -> Dict[str, Any]:
        memory_stats = self._memory_cache.get_stats()
        snapshot, total_hits, total_misses = self._stats.get_snapshot()
        total = total_hits + total_misses
        
        return {
            "memory": memory_stats,
            "by_path": snapshot,
            "overall": {
                "total_requests": total,
                "total_hits": total_hits,
                "total_misses": total_misses,
                "hit_rate": total_hits / total if total > 0 else 0.0
            }
        }


class CacheWarmer:
    __slots__ = ('_cache', '_gateway', '_warmup_configs', '_warmup_task', '_running', '_lock')
    
    def __init__(self, response_cache: ResponseCache, api_gateway):
        self._cache = response_cache
        self._gateway = api_gateway
        self._warmup_configs: List[Dict[str, Any]] = []
        self._warmup_task: Optional[asyncio.Task] = None
        self._running = False
        self._lock = Lock()
    
    def add_warmup_config(
        self,
        method: str,
        path: str,
        query_params: Dict[str, Any] = None,
        body: Any = None,
        interval_seconds: int = 300,
        ttl_seconds: int = 600
    ):
        with self._lock:
            self._warmup_configs.append({
                "method": method,
                "path": path,
                "query_params": query_params or {},
                "body": body,
                "interval_seconds": interval_seconds,
                "ttl_seconds": ttl_seconds,
                "_last_run": 0.0
            })
        logger.info(f"Added cache warmup config", path=path, method=method)
    
    async def start(self):
        if self._running:
            return
        
        self._running = True
        
        logger.info("Running initial cache warmup...")
        await self._run_warmup_parallel()
        
        self._warmup_task = asyncio.create_task(self._warmup_loop())
        logger.info("Cache warmer started")
    
    async def stop(self):
        self._running = False
        if self._warmup_task:
            self._warmup_task.cancel()
            try:
                await self._warmup_task
            except asyncio.CancelledError:
                pass
        logger.info("Cache warmer stopped")
    
    async def _warmup_loop(self):
        while self._running:
            try:
                await asyncio.sleep(60)
                
                now = time.time()
                configs_to_run = []
                
                with self._lock:
                    for config in self._warmup_configs:
                        if now - config["_last_run"] >= config["interval_seconds"]:
                            configs_to_run.append(config)
                            config["_last_run"] = now
                
                if configs_to_run:
                    await asyncio.gather(*[
                        self._warmup_single(config)
                        for config in configs_to_run
                    ])
                    
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.error(f"Warmup loop error", error=str(e))
    
    async def _run_warmup_parallel(self):
        with self._lock:
            configs = list(self._warmup_configs)
            now = time.time()
            for config in configs:
                config["_last_run"] = now
        
        if configs:
            await asyncio.gather(*[
                self._warmup_single(config)
                for config in configs
            ])
    
    async def _warmup_single(self, config: Dict[str, Any]):
        try:
            from app.api_gateway.models import GatewayRequest
            
            request = GatewayRequest(
                method=config["method"],
                path=config["path"],
                query_params=config["query_params"],
                body=config["body"]
            )
            
            response = await self._gateway.route(request)
            
            if 200 <= response.status_code < 300:
                await self._cache.set(
                    method=config["method"],
                    path=config["path"],
                    value=response,
                    ttl_seconds=config["ttl_seconds"],
                    query_params=config["query_params"],
                    body=config["body"]
                )
                logger.info(f"Cache warmup completed", path=config["path"])
            else:
                logger.warning(
                    f"Cache warmup failed",
                    path=config["path"],
                    status=response.status_code
                )
        
        except Exception as e:
            logger.error(f"Cache warmup error", path=config.get("path"), error=str(e))
