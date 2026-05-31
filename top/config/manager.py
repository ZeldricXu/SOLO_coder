import asyncio
import json
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional, TypeVar

from top.core.models import ConfigModel


T = TypeVar("T")


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass
class ConfigSnapshot:
    timestamp: datetime
    namespace: str
    configs: Dict[str, ConfigModel]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "timestamp": self.timestamp.isoformat(),
            "namespace": self.namespace,
            "configs": {k: v.model_dump() for k, v in self.configs.items()},
        }


@dataclass
class ConfigChangeEvent:
    event_type: str
    namespace: str
    config_id: str
    old_version: Optional[int]
    new_version: Optional[int]
    timestamp: datetime = field(default_factory=utc_now)


@dataclass
class ConfigRollbackResult:
    success: bool
    namespace: str
    config_id: str
    target_version: int
    restored_version: Optional[int]
    message: str = ""


class ConfigStore(ABC):
    @abstractmethod
    async def get(self, namespace: str, config_id: str) -> Optional[ConfigModel]:
        pass

    @abstractmethod
    async def get_version(
        self, namespace: str, config_id: str, version: int
    ) -> Optional[ConfigModel]:
        pass

    @abstractmethod
    async def set(self, config: ConfigModel) -> ConfigModel:
        pass

    @abstractmethod
    async def list(self, namespace: str) -> Dict[str, ConfigModel]:
        pass

    @abstractmethod
    async def list_versions(self, namespace: str, config_id: str) -> List[ConfigModel]:
        pass

    @abstractmethod
    async def delete(self, namespace: str, config_id: str) -> bool:
        pass

    @abstractmethod
    async def create_snapshot(self, namespace: str) -> ConfigSnapshot:
        pass

    @abstractmethod
    async def restore_snapshot(self, snapshot: ConfigSnapshot) -> bool:
        pass


class InMemoryConfigStore(ConfigStore):
    def __init__(self):
        self._store: Dict[str, Dict[str, Dict[int, ConfigModel]]] = {}
        self._snapshots: Dict[str, List[ConfigSnapshot]] = {}
        self._lock = asyncio.Lock()

    def _ensure_namespace(self, namespace: str) -> Dict[str, Dict[int, ConfigModel]]:
        if namespace not in self._store:
            self._store[namespace] = {}
        return self._store[namespace]

    async def get(self, namespace: str, config_id: str) -> Optional[ConfigModel]:
        async with self._lock:
            ns_store = self._store.get(namespace, {})
            versions = ns_store.get(config_id, {})
            if not versions:
                return None
            max_version = max(versions.keys())
            return versions[max_version]

    async def get_version(
        self, namespace: str, config_id: str, version: int
    ) -> Optional[ConfigModel]:
        async with self._lock:
            ns_store = self._store.get(namespace, {})
            versions = ns_store.get(config_id, {})
            return versions.get(version)

    async def set(self, config: ConfigModel) -> ConfigModel:
        async with self._lock:
            ns_store = self._ensure_namespace(config.namespace)
            if config.config_id not in ns_store:
                ns_store[config.config_id] = {}

            ns_store[config.config_id][config.version] = config
            return config

    async def list(self, namespace: str) -> Dict[str, ConfigModel]:
        async with self._lock:
            ns_store = self._store.get(namespace, {})
            result: Dict[str, ConfigModel] = {}
            for config_id, versions in ns_store.items():
                if versions:
                    max_version = max(versions.keys())
                    result[config_id] = versions[max_version]
            return result

    async def list_versions(self, namespace: str, config_id: str) -> List[ConfigModel]:
        async with self._lock:
            ns_store = self._store.get(namespace, {})
            versions = ns_store.get(config_id, {})
            sorted_configs = sorted(versions.values(), key=lambda c: c.version, reverse=True)
            return sorted_configs

    async def delete(self, namespace: str, config_id: str) -> bool:
        async with self._lock:
            ns_store = self._store.get(namespace, {})
            if config_id in ns_store:
                del ns_store[config_id]
                return True
            return False

    async def create_snapshot(self, namespace: str) -> ConfigSnapshot:
        current_configs = await self.list(namespace)
        snapshot = ConfigSnapshot(
            timestamp=utc_now(),
            namespace=namespace,
            configs=dict(current_configs),
        )
        if namespace not in self._snapshots:
            self._snapshots[namespace] = []
        self._snapshots[namespace].append(snapshot)
        return snapshot

    async def restore_snapshot(self, snapshot: ConfigSnapshot) -> bool:
        async with self._lock:
            for config_id, config in snapshot.configs.items():
                await self.set(config)
        return True

    def get_snapshots(self, namespace: str) -> List[ConfigSnapshot]:
        return list(self._snapshots.get(namespace, []))


class ConfigManager:
    def __init__(self, store: Optional[ConfigStore] = None):
        self._store = store or InMemoryConfigStore()
        self._listeners: Dict[str, List[Callable[[ConfigChangeEvent], None]]] = {}
        self._lock = asyncio.Lock()

    @property
    def store(self) -> ConfigStore:
        return self._store

    def add_listener(
        self, namespace: str, listener: Callable[[ConfigChangeEvent], None]
    ) -> None:
        if namespace not in self._listeners:
            self._listeners[namespace] = []
        self._listeners[namespace].append(listener)

    def remove_listener(
        self, namespace: str, listener: Callable[[ConfigChangeEvent], None]
    ) -> None:
        if namespace in self._listeners and listener in self._listeners[namespace]:
            self._listeners[namespace].remove(listener)

    def _notify_listeners(self, event: ConfigChangeEvent) -> None:
        for listener in self._listeners.get(event.namespace, []):
            try:
                listener(event)
            except Exception:
                pass

    async def get(
        self, namespace: str, config_id: str, default: Optional[T] = None
    ) -> Optional[ConfigModel | T]:
        config = await self._store.get(namespace, config_id)
        if config is None:
            return default
        return config

    async def get_version(
        self, namespace: str, config_id: str, version: int
    ) -> Optional[ConfigModel]:
        return await self._store.get_version(namespace, config_id, version)

    async def set(
        self,
        namespace: str,
        config_id: str,
        parameters: Dict[str, Any],
        enabled: bool = True,
    ) -> ConfigModel:
        existing = await self._store.get(namespace, config_id)
        old_version = existing.version if existing else None
        new_version = (existing.version + 1) if existing else 1

        config = ConfigModel(
            config_id=config_id,
            namespace=namespace,
            version=new_version,
            parameters=parameters,
            enabled=enabled,
            applied_at=utc_now(),
        )

        await self._store.set(config)

        event = ConfigChangeEvent(
            event_type="updated" if existing else "created",
            namespace=namespace,
            config_id=config_id,
            old_version=old_version,
            new_version=new_version,
        )
        self._notify_listeners(event)

        return config

    async def update(
        self,
        namespace: str,
        config_id: str,
        parameters: Optional[Dict[str, Any]] = None,
        enabled: Optional[bool] = None,
    ) -> Optional[ConfigModel]:
        existing = await self._store.get(namespace, config_id)
        if not existing:
            return None

        new_params = (
            {**existing.parameters, **parameters} if parameters else existing.parameters
        )
        new_enabled = enabled if enabled is not None else existing.enabled

        return await self.set(
            namespace=namespace,
            config_id=config_id,
            parameters=new_params,
            enabled=new_enabled,
        )

    async def list(self, namespace: str) -> Dict[str, ConfigModel]:
        return await self._store.list(namespace)

    async def list_versions(self, namespace: str, config_id: str) -> List[ConfigModel]:
        return await self._store.list_versions(namespace, config_id)

    async def delete(self, namespace: str, config_id: str) -> bool:
        existing = await self._store.get(namespace, config_id)
        if not existing:
            return False

        success = await self._store.delete(namespace, config_id)
        if success:
            event = ConfigChangeEvent(
                event_type="deleted",
                namespace=namespace,
                config_id=config_id,
                old_version=existing.version,
                new_version=None,
            )
            self._notify_listeners(event)
        return success

    async def rollback(
        self,
        namespace: str,
        config_id: str,
        target_version: int,
    ) -> ConfigRollbackResult:
        target = await self._store.get_version(namespace, config_id, target_version)
        if not target:
            return ConfigRollbackResult(
                success=False,
                namespace=namespace,
                config_id=config_id,
                target_version=target_version,
                restored_version=None,
                message=f"Version {target_version} not found",
            )

        current = await self._store.get(namespace, config_id)
        current_version = current.version if current else 0

        restored_config = await self.set(
            namespace=namespace,
            config_id=config_id,
            parameters=target.parameters,
            enabled=target.enabled,
        )

        return ConfigRollbackResult(
            success=True,
            namespace=namespace,
            config_id=config_id,
            target_version=target_version,
            restored_version=restored_config.version,
            message=f"Rolled back from v{current_version} to v{target_version}",
        )

    async def create_snapshot(self, namespace: str) -> ConfigSnapshot:
        return await self._store.create_snapshot(namespace)

    async def restore_snapshot(self, snapshot: ConfigSnapshot) -> bool:
        return await self._store.restore_snapshot(snapshot)

    async def get_parameter(
        self,
        namespace: str,
        config_id: str,
        key: str,
        default: Optional[Any] = None,
    ) -> Any:
        config = await self.get(namespace, config_id)
        if not config or not config.enabled:
            return default
        return config.parameters.get(key, default)


_config_manager: Optional[ConfigManager] = None


def get_config_manager() -> ConfigManager:
    global _config_manager
    if _config_manager is None:
        _config_manager = ConfigManager()
    return _config_manager
