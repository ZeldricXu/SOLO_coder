import asyncio
import time
from datetime import datetime
from typing import Dict, List, Optional, Any, Callable
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, update, delete
from app.logging_module import get_logger
from app.data_access.models import DynamicConfig


logger = get_logger(__name__)


class ConfigChangeEvent:
    def __init__(
        self,
        config_key: str,
        old_value: Optional[Dict[str, Any]],
        new_value: Dict[str, Any],
        old_version: Optional[int],
        new_version: int,
        change_type: str
    ):
        self.config_key = config_key
        self.old_value = old_value
        self.new_value = new_value
        self.old_version = old_version
        self.new_version = new_version
        self.change_type = change_type
        self.timestamp = datetime.utcnow()
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "config_key": self.config_key,
            "old_value": self.old_value,
            "new_value": self.new_value,
            "old_version": self.old_version,
            "new_version": self.new_version,
            "change_type": self.change_type,
            "timestamp": self.timestamp.isoformat()
        }


class ConfigChangeListener:
    def __init__(
        self,
        listener_id: str,
        config_key: str,
        callback: Callable,
        auto_reload: bool = True
    ):
        self.listener_id = listener_id
        self.config_key = config_key
        self.callback = callback
        self.auto_reload = auto_reload
        self.last_version: Optional[int] = None


class DynamicConfigManager:
    def __init__(self, session_factory):
        self._session_factory = session_factory
        self._local_cache: Dict[str, Dict[str, Any]] = {}
        self._config_versions: Dict[str, int] = {}
        self._listeners: Dict[str, List[ConfigChangeListener]] = {}
        self._global_listeners: List[ConfigChangeListener] = []
        self._polling_task: Optional[asyncio.Task] = None
        self._running = False
        self._lock = asyncio.Lock()
        self._change_history: List[ConfigChangeEvent] = []
        self._max_history = 1000
        
        logger.info("Dynamic config manager initialized")
    
    async def start(self, polling_interval_seconds: int = 10):
        if self._running:
            return
        
        self._running = True
        
        await self._load_all_configs()
        
        if polling_interval_seconds > 0:
            self._polling_task = asyncio.create_task(
                self._polling_loop(polling_interval_seconds)
            )
        
        logger.info("Dynamic config manager started", polling_interval=polling_interval_seconds)
    
    async def stop(self):
        self._running = False
        
        if self._polling_task:
            self._polling_task.cancel()
            try:
                await self._polling_task
            except asyncio.CancelledError:
                pass
        
        logger.info("Dynamic config manager stopped")
    
    async def _load_all_configs(self):
        try:
            async with self._session_factory() as session:
                stmt = select(DynamicConfig)
                result = await session.execute(stmt)
                configs = result.scalars().all()
                
                for config in configs:
                    self._local_cache[config.config_key] = config.config_value
                    self._config_versions[config.config_key] = config.version
                
                logger.info(f"Loaded {len(configs)} dynamic configs from database")
        
        except Exception as e:
            logger.error(f"Failed to load dynamic configs", error=str(e))
    
    async def _polling_loop(self, interval_seconds: int):
        while self._running:
            await asyncio.sleep(interval_seconds)
            try:
                await self._check_for_updates()
            except Exception as e:
                logger.error(f"Error in config polling loop", error=str(e))
    
    async def _check_for_updates(self):
        try:
            async with self._session_factory() as session:
                stmt = select(DynamicConfig.config_key, DynamicConfig.version, DynamicConfig.config_value)
                result = await session.execute(stmt)
                rows = result.fetchall()
                
                for config_key, version, config_value in rows:
                    old_version = self._config_versions.get(config_key)
                    
                    if old_version is None or version > old_version:
                        old_value = self._local_cache.get(config_key)
                        self._local_cache[config_key] = config_value
                        self._config_versions[config_key] = version
                        
                        change_type = "created" if old_version is None else "updated"
                        event = ConfigChangeEvent(
                            config_key=config_key,
                            old_value=old_value,
                            new_value=config_value,
                            old_version=old_version,
                            new_version=version,
                            change_type=change_type
                        )
                        
                        await self._notify_listeners(event)
                        logger.info(
                            f"Config {change_type}",
                            config_key=config_key,
                            version=version
                        )
                
                cached_keys = set(self._config_versions.keys())
                db_keys = set(row[0] for row in rows)
                deleted_keys = cached_keys - db_keys
                
                for config_key in deleted_keys:
                    old_value = self._local_cache.pop(config_key, None)
                    old_version = self._config_versions.pop(config_key, None)
                    
                    event = ConfigChangeEvent(
                        config_key=config_key,
                        old_value=old_value,
                        new_value=None,
                        old_version=old_version,
                        new_version=None,
                        change_type="deleted"
                    )
                    
                    await self._notify_listeners(event)
                    logger.info(f"Config deleted", config_key=config_key)
        
        except Exception as e:
            logger.error(f"Failed to check for config updates", error=str(e))
    
    async def _notify_listeners(self, event: ConfigChangeEvent):
        self._change_history.append(event)
        if len(self._change_history) > self._max_history:
            self._change_history = self._change_history[-self._max_history:]
        
        for listener in self._global_listeners:
            try:
                if asyncio.iscoroutinefunction(listener.callback):
                    await listener.callback(event)
                else:
                    listener.callback(event)
            except Exception as e:
                logger.error(
                    f"Global listener error",
                    listener_id=listener.listener_id,
                    error=str(e)
                )
        
        for listener in self._listeners.get(event.config_key, []):
            try:
                if asyncio.iscoroutinefunction(listener.callback):
                    await listener.callback(event)
                else:
                    listener.callback(event)
            except Exception as e:
                logger.error(
                    f"Listener error",
                    listener_id=listener.listener_id,
                    config_key=event.config_key,
                    error=str(e)
                )
    
    def get_config(self, config_key: str, default: Any = None) -> Any:
        value = self._local_cache.get(config_key)
        if value is not None:
            return value
        return default
    
    def get_config_version(self, config_key: str) -> Optional[int]:
        return self._config_versions.get(config_key)
    
    async def set_config(
        self,
        config_key: str,
        value: Dict[str, Any],
        description: Optional[str] = None
    ) -> bool:
        try:
            async with self._session_factory() as session:
                stmt = select(DynamicConfig).where(DynamicConfig.config_key == config_key)
                result = await session.execute(stmt)
                existing = result.scalar_one_or_none()
                
                if existing:
                    old_value = existing.config_value
                    old_version = existing.version
                    existing.config_value = value
                    existing.version = old_version + 1
                    if description:
                        existing.description = description
                    
                    event = ConfigChangeEvent(
                        config_key=config_key,
                        old_value=old_value,
                        new_value=value,
                        old_version=old_version,
                        new_version=old_version + 1,
                        change_type="updated"
                    )
                else:
                    new_config = DynamicConfig(
                        config_key=config_key,
                        config_value=value,
                        version=1,
                        description=description
                    )
                    session.add(new_config)
                    
                    event = ConfigChangeEvent(
                        config_key=config_key,
                        old_value=None,
                        new_value=value,
                        old_version=None,
                        new_version=1,
                        change_type="created"
                    )
                
                await session.commit()
                
                self._local_cache[config_key] = value
                self._config_versions[config_key] = event.new_version
                
                await self._notify_listeners(event)
                
                logger.info(
                    f"Config saved",
                    config_key=config_key,
                    version=event.new_version
                )
                return True
        
        except Exception as e:
            logger.error(f"Failed to save config", config_key=config_key, error=str(e))
            return False
    
    async def delete_config(self, config_key: str) -> bool:
        try:
            async with self._session_factory() as session:
                stmt = select(DynamicConfig).where(DynamicConfig.config_key == config_key)
                result = await session.execute(stmt)
                existing = result.scalar_one_or_none()
                
                if not existing:
                    return False
                
                old_value = existing.config_value
                old_version = existing.version
                
                await session.delete(existing)
                await session.commit()
                
                self._local_cache.pop(config_key, None)
                self._config_versions.pop(config_key, None)
                
                event = ConfigChangeEvent(
                    config_key=config_key,
                    old_value=old_value,
                    new_value=None,
                    old_version=old_version,
                    new_version=None,
                    change_type="deleted"
                )
                
                await self._notify_listeners(event)
                
                logger.info(f"Config deleted", config_key=config_key)
                return True
        
        except Exception as e:
            logger.error(f"Failed to delete config", config_key=config_key, error=str(e))
            return False
    
    def add_listener(
        self,
        config_key: str,
        callback: Callable,
        listener_id: Optional[str] = None,
        auto_reload: bool = True
    ) -> str:
        if listener_id is None:
            listener_id = f"listener_{id(callback)}_{int(time.time() * 1000)}"
        
        listener = ConfigChangeListener(
            listener_id=listener_id,
            config_key=config_key,
            callback=callback,
            auto_reload=auto_reload
        )
        
        if config_key not in self._listeners:
            self._listeners[config_key] = []
        self._listeners[config_key].append(listener)
        
        logger.info(f"Added listener", config_key=config_key, listener_id=listener_id)
        return listener_id
    
    def add_global_listener(
        self,
        callback: Callable,
        listener_id: Optional[str] = None,
        auto_reload: bool = True
    ) -> str:
        if listener_id is None:
            listener_id = f"global_listener_{id(callback)}_{int(time.time() * 1000)}"
        
        listener = ConfigChangeListener(
            listener_id=listener_id,
            config_key="*",
            callback=callback,
            auto_reload=auto_reload
        )
        
        self._global_listeners.append(listener)
        logger.info(f"Added global listener", listener_id=listener_id)
        return listener_id
    
    def remove_listener(self, config_key: str, listener_id: str) -> bool:
        if config_key in self._listeners:
            original_len = len(self._listeners[config_key])
            self._listeners[config_key] = [
                l for l in self._listeners[config_key]
                if l.listener_id != listener_id
            ]
            if len(self._listeners[config_key]) < original_len:
                logger.info(f"Removed listener", config_key=config_key, listener_id=listener_id)
                return True
        return False
    
    def remove_global_listener(self, listener_id: str) -> bool:
        original_len = len(self._global_listeners)
        self._global_listeners = [
            l for l in self._global_listeners
            if l.listener_id != listener_id
        ]
        if len(self._global_listeners) < original_len:
            logger.info(f"Removed global listener", listener_id=listener_id)
            return True
        return False
    
    def get_all_configs(self) -> Dict[str, Dict[str, Any]]:
        return {
            key: {
                "value": self._local_cache[key],
                "version": self._config_versions.get(key)
            }
            for key in self._local_cache
        }
    
    async def force_reload(self, config_key: Optional[str] = None) -> int:
        try:
            async with self._session_factory() as session:
                if config_key:
                    stmt = select(DynamicConfig).where(DynamicConfig.config_key == config_key)
                else:
                    stmt = select(DynamicConfig)
                
                result = await session.execute(stmt)
                configs = result.scalars().all()
                
                updated_count = 0
                for config in configs:
                    old_version = self._config_versions.get(config.config_key)
                    
                    if old_version is None or config.version > old_version:
                        old_value = self._local_cache.get(config.config_key)
                        self._local_cache[config.config_key] = config.config_value
                        self._config_versions[config.config_key] = config.version
                        
                        if old_version != config.version:
                            change_type = "created" if old_version is None else "updated"
                            event = ConfigChangeEvent(
                                config_key=config.config_key,
                                old_value=old_value,
                                new_value=config.config_value,
                                old_version=old_version,
                                new_version=config.version,
                                change_type=change_type
                            )
                            await self._notify_listeners(event)
                            updated_count += 1
                
                logger.info(f"Force reloaded {updated_count} configs")
                return updated_count
        
        except Exception as e:
            logger.error(f"Failed to force reload configs", error=str(e))
            return 0
    
    def get_change_history(
        self,
        config_key: Optional[str] = None,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        history = reversed(self._change_history)
        
        if config_key:
            history = [e for e in history if e.config_key == config_key]
        
        return [e.to_dict() for e in list(history)[:limit]]
    
    def get_stats(self) -> Dict[str, Any]:
        return {
            "cached_configs": len(self._local_cache),
            "listeners": {
                "total": sum(len(l) for l in self._listeners.values()) + len(self._global_listeners),
                "by_config": {k: len(v) for k, v in self._listeners.items()},
                "global": len(self._global_listeners)
            },
            "change_history_size": len(self._change_history),
            "running": self._running
        }
