import time
import threading
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple
from collections import OrderedDict
from sqlalchemy import select, and_
from sqlalchemy.ext.asyncio import AsyncSession
from app.models import Config
from app.config import settings
from app.logger import logger


class LRUCache:
    def __init__(self, max_size: int, ttl_seconds: int):
        self.max_size = max_size
        self.ttl_seconds = ttl_seconds
        self._cache: OrderedDict[str, Tuple[Any, float]] = OrderedDict()
        self._lock = threading.RLock()
    
    def get(self, key: str) -> Optional[Any]:
        with self._lock:
            if key not in self._cache:
                return None
            
            value, expire_at = self._cache[key]
            
            if time.time() > expire_at:
                del self._cache[key]
                return None
            
            self._cache.move_to_end(key)
            return value
    
    def set(self, key: str, value: Any) -> None:
        with self._lock:
            if key in self._cache:
                del self._cache[key]
            
            while len(self._cache) >= self.max_size:
                self._cache.popitem(last=False)
            
            self._cache[key] = (value, time.time() + self.ttl_seconds)
    
    def delete(self, key: str) -> bool:
        with self._lock:
            if key in self._cache:
                del self._cache[key]
                return True
            return False
    
    def clear(self) -> None:
        with self._lock:
            self._cache.clear()
    
    def size(self) -> int:
        with self._lock:
            return len(self._cache)
    
    def keys(self) -> List[str]:
        with self._lock:
            return list(self._cache.keys())


class MultiLevelCache:
    def __init__(self, enabled: bool = None):
        self.enabled = enabled if enabled is not None else settings.CACHE_ENABLED
        
        if self.enabled:
            self.l1_cache = LRUCache(
                max_size=settings.CACHE_L1_MAX_SIZE,
                ttl_seconds=settings.CACHE_L1_TTL
            )
            self.l2_cache = LRUCache(
                max_size=settings.CACHE_L2_MAX_SIZE,
                ttl_seconds=settings.CACHE_L2_TTL
            )
            self.l3_cache = LRUCache(
                max_size=10000,
                ttl_seconds=settings.CACHE_L3_TTL
            )
        else:
            self.l1_cache = None
            self.l2_cache = None
            self.l3_cache = None
        
        self._metrics = {
            "l1_hits": 0,
            "l1_misses": 0,
            "l2_hits": 0,
            "l2_misses": 0,
            "l3_hits": 0,
            "l3_misses": 0,
            "db_hits": 0,
            "total_requests": 0
        }
        self._metrics_lock = threading.RLock()
    
    def _make_key(self, config_id: str, namespace: str, version: Optional[int] = None) -> str:
        if version is not None:
            return f"config:{config_id}:{namespace}:v{version}"
        return f"config:{config_id}:{namespace}:latest"
    
    def get(self, config_id: str, namespace: str, version: Optional[int] = None) -> Optional[Dict[str, Any]]:
        if not self.enabled:
            return None
        
        key = self._make_key(config_id, namespace, version)
        
        with self._metrics_lock:
            self._metrics["total_requests"] += 1
        
        if self.l1_cache:
            value = self.l1_cache.get(key)
            if value is not None:
                with self._metrics_lock:
                    self._metrics["l1_hits"] += 1
                logger.debug("Cache L1 hit", key=key)
                return value
        
        with self._metrics_lock:
            self._metrics["l1_misses"] += 1
        
        if self.l2_cache:
            value = self.l2_cache.get(key)
            if value is not None:
                with self._metrics_lock:
                    self._metrics["l2_hits"] += 1
                self._promote_to_l1(key, value)
                logger.debug("Cache L2 hit", key=key)
                return value
        
        with self._metrics_lock:
            self._metrics["l2_misses"] += 1
        
        if self.l3_cache:
            value = self.l3_cache.get(key)
            if value is not None:
                with self._metrics_lock:
                    self._metrics["l3_hits"] += 1
                self._promote_to_l2(key, value)
                self._promote_to_l1(key, value)
                logger.debug("Cache L3 hit", key=key)
                return value
        
        with self._metrics_lock:
            self._metrics["l3_misses"] += 1
        
        return None
    
    def set(
        self,
        config_id: str,
        namespace: str,
        version: Optional[int],
        config_dict: Dict[str, Any]
    ) -> None:
        if not self.enabled:
            return
        
        key = self._make_key(config_id, namespace, version)
        
        if self.l1_cache:
            self.l1_cache.set(key, config_dict)
        
        if self.l2_cache:
            self.l2_cache.set(key, config_dict)
        
        if self.l3_cache:
            self.l3_cache.set(key, config_dict)
        
        logger.debug("Cache set", key=key)
    
    def invalidate(
        self,
        config_id: str,
        namespace: str,
        version: Optional[int] = None
    ) -> None:
        if not self.enabled:
            return
        
        if version is not None:
            key = self._make_key(config_id, namespace, version)
            self._delete_key(key)
        else:
            key_latest = self._make_key(config_id, namespace, None)
            self._delete_key(key_latest)
            
            if self.l3_cache:
                prefix = f"config:{config_id}:{namespace}:v"
                for key in self.l3_cache.keys():
                    if key.startswith(prefix):
                        self._delete_key(key)
        
        logger.info("Cache invalidated", config_id=config_id, namespace=namespace, version=version)
    
    def invalidate_namespace(self, namespace: str) -> None:
        if not self.enabled:
            return
        
        prefix = f"config:{namespace}:"
        for cache in [self.l1_cache, self.l2_cache, self.l3_cache]:
            if cache:
                for key in cache.keys():
                    if key.startswith(prefix):
                        self._delete_key(key)
        
        logger.info("Cache namespace invalidated", namespace=namespace)
    
    def invalidate_all(self) -> None:
        if not self.enabled:
            return
        
        if self.l1_cache:
            self.l1_cache.clear()
        if self.l2_cache:
            self.l2_cache.clear()
        if self.l3_cache:
            self.l3_cache.clear()
        
        logger.info("All cache invalidated")
    
    def _delete_key(self, key: str) -> None:
        if self.l1_cache:
            self.l1_cache.delete(key)
        if self.l2_cache:
            self.l2_cache.delete(key)
        if self.l3_cache:
            self.l3_cache.delete(key)
    
    def _promote_to_l1(self, key: str, value: Any) -> None:
        if self.l1_cache:
            self.l1_cache.set(key, value)
    
    def _promote_to_l2(self, key: str, value: Any) -> None:
        if self.l2_cache:
            self.l2_cache.set(key, value)
    
    def record_db_hit(self) -> None:
        with self._metrics_lock:
            self._metrics["db_hits"] += 1
    
    def get_metrics(self) -> Dict[str, Any]:
        with self._metrics_lock:
            total = self._metrics["total_requests"]
            l1_hits = self._metrics["l1_hits"]
            l2_hits = self._metrics["l2_hits"]
            l3_hits = self._metrics["l3_hits"]
            cache_hits = l1_hits + l2_hits + l3_hits
            
            hit_rate = (cache_hits / total * 100) if total > 0 else 0.0
            
            return {
                "enabled": self.enabled,
                "total_requests": total,
                "cache_hits": cache_hits,
                "cache_misses": self._metrics["db_hits"],
                "hit_rate_percent": round(hit_rate, 2),
                "l1": {
                    "hits": l1_hits,
                    "misses": self._metrics["l1_misses"],
                    "size": self.l1_cache.size() if self.l1_cache else 0,
                    "max_size": self.l1_cache.max_size if self.l1_cache else 0,
                    "ttl": settings.CACHE_L1_TTL
                },
                "l2": {
                    "hits": l2_hits,
                    "misses": self._metrics["l2_misses"],
                    "size": self.l2_cache.size() if self.l2_cache else 0,
                    "max_size": self.l2_cache.max_size if self.l2_cache else 0,
                    "ttl": settings.CACHE_L2_TTL
                },
                "l3": {
                    "hits": l3_hits,
                    "misses": self._metrics["l3_misses"],
                    "size": self.l3_cache.size() if self.l3_cache else 0,
                    "max_size": self.l3_cache.max_size if self.l3_cache else 0,
                    "ttl": settings.CACHE_L3_TTL
                },
                "db_hits": self._metrics["db_hits"]
            }
    
    def reset_metrics(self) -> None:
        with self._metrics_lock:
            self._metrics = {
                "l1_hits": 0,
                "l1_misses": 0,
                "l2_hits": 0,
                "l2_misses": 0,
                "l3_hits": 0,
                "l3_misses": 0,
                "db_hits": 0,
                "total_requests": 0
            }


_global_cache: Optional[MultiLevelCache] = None


def get_global_cache() -> MultiLevelCache:
    global _global_cache
    if _global_cache is None:
        _global_cache = MultiLevelCache()
    return _global_cache


class ConfigManager:
    def __init__(self, db: AsyncSession, cache: MultiLevelCache = None):
        self.db = db
        self.cache = cache or get_global_cache()
    
    async def create_config(
        self,
        config_id: str,
        namespace: str,
        parameters: Dict[str, Any],
        enabled: bool = True
    ) -> Config:
        latest_version = await self._get_latest_version(config_id, namespace)
        new_version = (latest_version or 0) + 1
        
        config = Config(
            config_id=config_id,
            namespace=namespace,
            version=new_version,
            parameters=parameters,
            enabled=enabled
        )
        self.db.add(config)
        await self.db.flush()
        
        self.cache.invalidate(config_id, namespace)
        
        config_dict = self._config_to_dict(config)
        self.cache.set(config_id, namespace, new_version, config_dict)
        self.cache.set(config_id, namespace, None, config_dict)
        
        logger.info("Config created", config_id=config_id, namespace=namespace, version=new_version)
        return config
    
    async def get_config(
        self,
        config_id: str,
        namespace: str = "default",
        version: int = None,
        use_cache: bool = True
    ) -> Optional[Config]:
        if use_cache:
            cached = self.cache.get(config_id, namespace, version)
            if cached:
                logger.debug("Config cache hit", config_id=config_id, namespace=namespace, version=version)
                return self._dict_to_config(cached)
        
        self.cache.record_db_hit()
        
        if version is not None:
            stmt = select(Config).where(
                and_(
                    Config.config_id == config_id,
                    Config.namespace == namespace,
                    Config.version == version
                )
            )
        else:
            stmt = select(Config).where(
                and_(
                    Config.config_id == config_id,
                    Config.namespace == namespace
                )
            ).order_by(Config.version.desc()).limit(1)
        
        result = await self.db.execute(stmt)
        config = result.scalar_one_or_none()
        
        if config and use_cache:
            config_dict = self._config_to_dict(config)
            self.cache.set(config_id, namespace, config.version, config_dict)
            if version is None:
                self.cache.set(config_id, namespace, None, config_dict)
        
        return config
    
    async def get_config_bulk(
        self,
        config_ids: List[str],
        namespace: str = "default"
    ) -> Dict[str, Optional[Dict[str, Any]]]:
        results: Dict[str, Optional[Dict[str, Any]]] = {}
        to_fetch: List[str] = []
        
        for config_id in config_ids:
            cached = self.cache.get(config_id, namespace, None)
            if cached:
                results[config_id] = cached
            else:
                to_fetch.append(config_id)
        
        if to_fetch:
            stmt = select(Config).where(
                and_(
                    Config.config_id.in_(to_fetch),
                    Config.namespace == namespace
                )
            )
            
            result = await self.db.execute(stmt)
            configs = result.scalars().all()
            
            latest_configs: Dict[str, Config] = {}
            for config in configs:
                if config.config_id not in latest_configs or config.version > latest_configs[config.config_id].version:
                    latest_configs[config.config_id] = config
            
            for config_id in to_fetch:
                if config_id in latest_configs:
                    config = latest_configs[config_id]
                    config_dict = self._config_to_dict(config)
                    self.cache.set(config_id, namespace, config.version, config_dict)
                    self.cache.set(config_id, namespace, None, config_dict)
                    results[config_id] = config_dict
                    self.cache.record_db_hit()
                else:
                    results[config_id] = None
        
        return results
    
    async def update_config(
        self,
        config_id: str,
        namespace: str,
        parameters: Dict[str, Any]
    ) -> Optional[Config]:
        current = await self.get_config(config_id, namespace, use_cache=False)
        if not current:
            return None
        
        new_version = current.version + 1
        new_config = Config(
            config_id=config_id,
            namespace=namespace,
            version=new_version,
            parameters=parameters,
            enabled=current.enabled
        )
        self.db.add(new_config)
        await self.db.flush()
        
        self.cache.invalidate(config_id, namespace)
        
        config_dict = self._config_to_dict(new_config)
        self.cache.set(config_id, namespace, new_version, config_dict)
        self.cache.set(config_id, namespace, None, config_dict)
        
        logger.info("Config updated", config_id=config_id, namespace=namespace, old_version=current.version, new_version=new_version)
        return new_config
    
    async def rollback_config(
        self,
        config_id: str,
        namespace: str,
        target_version: int
    ) -> Optional[Config]:
        target_config = await self.get_config(config_id, namespace, version=target_version, use_cache=False)
        if not target_config:
            logger.error("Target version not found", config_id=config_id, target_version=target_version)
            return None
        
        current = await self.get_config(config_id, namespace, use_cache=False)
        if not current:
            return None
        
        if current.version == target_version:
            return current
        
        new_version = current.version + 1
        rolled_back = Config(
            config_id=config_id,
            namespace=namespace,
            version=new_version,
            parameters=target_config.parameters,
            enabled=target_config.enabled
        )
        self.db.add(rolled_back)
        await self.db.flush()
        
        self.cache.invalidate(config_id, namespace)
        
        config_dict = self._config_to_dict(rolled_back)
        self.cache.set(config_id, namespace, new_version, config_dict)
        self.cache.set(config_id, namespace, None, config_dict)
        
        logger.info("Config rolled back", config_id=config_id, from_version=current.version, to_version=target_version)
        return rolled_back
    
    async def list_configs(
        self,
        namespace: str = None,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        conditions = []
        if namespace:
            conditions.append(Config.namespace == namespace)
        
        stmt = select(Config).where(
            and_(*conditions) if conditions else True
        ).order_by(Config.config_id, Config.version.desc())
        
        result = await self.db.execute(stmt)
        configs = result.scalars().all()
        
        seen = set()
        unique_latest = []
        for cfg in configs:
            key = (cfg.config_id, cfg.namespace)
            if key not in seen:
                seen.add(key)
                unique_latest.append(cfg)
                if len(unique_latest) >= limit:
                    break
        
        for cfg in unique_latest:
            config_dict = self._config_to_dict(cfg)
            self.cache.set(cfg.config_id, cfg.namespace, cfg.version, config_dict)
            self.cache.set(cfg.config_id, cfg.namespace, None, config_dict)
        
        return [self._config_to_dict(c) for c in unique_latest]
    
    async def get_config_history(
        self,
        config_id: str,
        namespace: str = "default"
    ) -> List[Dict[str, Any]]:
        stmt = select(Config).where(
            and_(
                Config.config_id == config_id,
                Config.namespace == namespace
            )
        ).order_by(Config.version.desc())
        
        result = await self.db.execute(stmt)
        configs = result.scalars().all()
        
        for cfg in configs:
            config_dict = self._config_to_dict(cfg)
            self.cache.set(config_id, namespace, cfg.version, config_dict)
        
        return [self._config_to_dict(c) for c in configs]
    
    async def disable_config(
        self,
        config_id: str,
        namespace: str = "default"
    ) -> Optional[Config]:
        config = await self.get_config(config_id, namespace, use_cache=False)
        if config:
            new_version = config.version + 1
            new_config = Config(
                config_id=config_id,
                namespace=namespace,
                version=new_version,
                parameters=config.parameters,
                enabled=False
            )
            self.db.add(new_config)
            await self.db.flush()
            
            self.cache.invalidate(config_id, namespace)
            
            config_dict = self._config_to_dict(new_config)
            self.cache.set(config_id, namespace, new_version, config_dict)
            self.cache.set(config_id, namespace, None, config_dict)
            
            logger.info("Config disabled", config_id=config_id)
            return new_config
        
        return config
    
    async def enable_config(
        self,
        config_id: str,
        namespace: str = "default"
    ) -> Optional[Config]:
        config = await self.get_config(config_id, namespace, use_cache=False)
        if config:
            new_version = config.version + 1
            new_config = Config(
                config_id=config_id,
                namespace=namespace,
                version=new_version,
                parameters=config.parameters,
                enabled=True
            )
            self.db.add(new_config)
            await self.db.flush()
            
            self.cache.invalidate(config_id, namespace)
            
            config_dict = self._config_to_dict(new_config)
            self.cache.set(config_id, namespace, new_version, config_dict)
            self.cache.set(config_id, namespace, None, config_dict)
            
            logger.info("Config enabled", config_id=config_id)
            return new_config
        
        return config
    
    async def invalidate_cache(
        self,
        config_id: str = None,
        namespace: str = None,
        version: int = None
    ) -> Dict[str, Any]:
        if config_id and namespace:
            self.cache.invalidate(config_id, namespace, version)
        elif namespace:
            self.cache.invalidate_namespace(namespace)
        else:
            self.cache.invalidate_all()
        
        return self.get_cache_metrics()
    
    def get_cache_metrics(self) -> Dict[str, Any]:
        return self.cache.get_metrics()
    
    def reset_cache_metrics(self) -> None:
        self.cache.reset_metrics()
    
    async def _get_latest_version(self, config_id: str, namespace: str) -> Optional[int]:
        cached = self.cache.get(config_id, namespace, None)
        if cached:
            return cached.get("version")
        
        stmt = select(Config).where(
            and_(
                Config.config_id == config_id,
                Config.namespace == namespace
            )
        ).order_by(Config.version.desc()).limit(1)
        
        result = await self.db.execute(stmt)
        config = result.scalar_one_or_none()
        return config.version if config else None
    
    def _config_to_dict(self, config: Config) -> Dict[str, Any]:
        return {
            "id": config.id,
            "config_id": config.config_id,
            "namespace": config.namespace,
            "version": config.version,
            "parameters": config.parameters,
            "enabled": config.enabled,
            "applied_at": config.applied_at.isoformat() if config.applied_at else None,
            "created_at": config.created_at.isoformat() if config.created_at else None
        }
    
    def _dict_to_config(self, data: Dict[str, Any]) -> Config:
        config = Config(
            id=data.get("id"),
            config_id=data["config_id"],
            namespace=data["namespace"],
            version=data["version"],
            parameters=data["parameters"],
            enabled=data["enabled"]
        )
        
        if data.get("applied_at"):
            config.applied_at = datetime.fromisoformat(data["applied_at"])
        if data.get("created_at"):
            config.created_at = datetime.fromisoformat(data["created_at"])
        
        return config
