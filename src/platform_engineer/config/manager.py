import asyncio
import json
import threading
from copy import deepcopy
from datetime import datetime, timezone
from hashlib import sha256
from typing import Any, Callable, Dict, List, Optional

from ..core.events import DomainEvent, get_global_event_bus
from ..core.exceptions import ConfigNotFoundError, ValidationError
from ..core.models import ConfigDefinition, generate_id
from .sources import ConfigSource


def deep_merge(dest: Dict[str, Any], src: Dict[str, Any]) -> Dict[str, Any]:
    result = dict(dest)
    for key, value in src.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = deepcopy(value)
    return result


class Configuration:
    def __init__(self, data: Dict[str, Any], version: int = 1):
        self._data = deepcopy(data)
        self._version = version
        self._loaded_at = datetime.now(timezone.utc)
        self._checksum = sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()

    def get(self, key: str, default: Any = None) -> Any:
        keys = key.split(".")
        data = self._data
        for k in keys:
            if isinstance(data, dict) and k in data:
                data = data[k]
            else:
                return default
        return data

    def get_or_raise(self, key: str) -> Any:
        value = self.get(key)
        if value is None:
            raise ConfigNotFoundError(f"Config key not found: {key}")
        return value

    def has(self, key: str) -> bool:
        return self.get(key) is not None

    def as_dict(self) -> Dict[str, Any]:
        return deepcopy(self._data)

    def get_version(self) -> int:
        return self._version

    def get_checksum(self) -> str:
        return self._checksum

    def get_loaded_at(self) -> datetime:
        return self._loaded_at


class ConfigManager:
    def __init__(self, logger=None):
        self._sources: List[ConfigSource] = []
        self._config: Optional[Configuration] = None
        self._version = 0
        self._listeners: List[Callable[[Configuration, Configuration], Any]] = []
        self._lock = threading.RLock()
        self._load_lock = threading.Lock()
        self._logger = logger
        self._namespace_configs: Dict[str, ConfigDefinition] = {}
        self._config_store: Dict[str, ConfigDefinition] = {}
        self._event_bus = get_global_event_bus()

    def add_source(self, source: ConfigSource) -> None:
        with self._lock:
            self._sources.append(source)
            self._sources.sort(key=lambda s: s.get_priority())

    def remove_source(self, source: ConfigSource) -> bool:
        with self._lock:
            if source in self._sources:
                self._sources.remove(source)
                return True
            return False

    async def load(self) -> Configuration:
        with self._load_lock:
            with self._lock:
                sources = list(self._sources)
            merged: Dict[str, Any] = {}
            for source in sources:
                try:
                    source_data = await source.load()
                    merged = deep_merge(merged, source_data)
                except Exception as e:
                    if self._logger:
                        self._logger.warning(f"Failed to load config source: {e}")
                    continue
            with self._lock:
                self._version += 1
                new_config = Configuration(merged, self._version)
                old_config = self._config
                self._config = new_config
            if old_config is not None:
                await self._notify_change(old_config, new_config)
                await self._emit_config_changed_event(old_config, new_config)
            return new_config

    async def reload(self) -> Configuration:
        return await self.load()

    async def _notify_change(self, old: Configuration, new: Configuration) -> None:
        with self._lock:
            listeners = list(self._listeners)
        for listener in listeners:
            try:
                result = listener(old, new)
                if asyncio.iscoroutine(result):
                    await result
            except Exception as e:
                if self._logger:
                    self._logger.error(f"Config change listener error: {e}")

    async def _emit_config_changed_event(self, old: Configuration, new: Configuration) -> None:
        event = DomainEvent(
            event_type="config.changed",
            payload={
                "old_version": old.get_version(),
                "new_version": new.get_version(),
                "old_checksum": old.get_checksum(),
                "new_checksum": new.get_checksum(),
                "loaded_at": new.get_loaded_at().isoformat(),
            },
            source="config_manager",
        )
        await self._event_bus.publish(event)

    def get_config(self) -> Optional[Configuration]:
        with self._lock:
            return self._config

    def add_listener(self, listener: Callable[[Configuration, Configuration], Any]) -> None:
        with self._lock:
            self._listeners.append(listener)

    def remove_listener(self, listener: Callable[[Configuration, Configuration], Any]) -> bool:
        with self._lock:
            if listener in self._listeners:
                self._listeners.remove(listener)
                return True
            return False

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            if self._config is None:
                return default
            return self._config.get(key, default)

    def get_or_raise(self, key: str) -> Any:
        with self._lock:
            if self._config is None:
                raise ConfigNotFoundError("Configuration not loaded")
            return self._config.get_or_raise(key)

    def create_config(self, namespace: str, parameters: Dict[str, Any]) -> ConfigDefinition:
        with self._lock:
            config_id = generate_id("cfg")
            config = ConfigDefinition(
                config_id=config_id,
                namespace=namespace,
                parameters=parameters,
                version=1,
                enabled=True,
                applied_at=datetime.now(timezone.utc),
            )
            self._config_store[config_id] = config
            return config

    def update_config(self, config_id: str, parameters: Dict[str, Any]) -> ConfigDefinition:
        with self._lock:
            if config_id not in self._config_store:
                raise ConfigNotFoundError(f"Config not found: {config_id}")
            config = self._config_store[config_id]
            config.parameters.update(parameters)
            config.bump_version()
            return config

    def get_config_definition(self, config_id: str) -> Optional[ConfigDefinition]:
        with self._lock:
            return self._config_store.get(config_id)

    def list_configs(self, namespace: Optional[str] = None) -> List[ConfigDefinition]:
        with self._lock:
            configs = list(self._config_store.values())
        if namespace:
            configs = [c for c in configs if c.namespace == namespace]
        return sorted(configs, key=lambda c: c.updated_at, reverse=True)

    def delete_config(self, config_id: str) -> bool:
        with self._lock:
            if config_id in self._config_store:
                del self._config_store[config_id]
                return True
            return False


_global_config_manager: Optional[ConfigManager] = None
_global_config_lock = threading.Lock()


def get_config_manager() -> ConfigManager:
    global _global_config_manager
    if _global_config_manager is None:
        with _global_config_lock:
            if _global_config_manager is None:
                _global_config_manager = ConfigManager()
    return _global_config_manager


def set_config_manager(manager: ConfigManager) -> None:
    global _global_config_manager
    with _global_config_lock:
        _global_config_manager = manager
