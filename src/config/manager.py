from __future__ import annotations

import logging
from typing import Any, Callable, Dict, List, Optional

from src.config.models import (
    ConfigChangeEvent,
    ConfigDiff,
    ConfigEntry,
    ConfigSnapshot,
    ConfigSourceType,
    ConfigValidationRule,
)
from src.config.sources import ConfigSource, MemorySource
from src.common.utils import merge_dicts

logger = logging.getLogger(__name__)


class ConfigurationManager:
    def __init__(self, namespace: str = "default") -> None:
        self.namespace = namespace
        self._sources: List[ConfigSource] = []
        self._config_cache: Dict[str, Any] = {}
        self._listeners: Dict[str, List[Callable[[ConfigChangeEvent], None]]] = {}
        self._validation_rules: List[ConfigValidationRule] = []
        self._snapshots: List[ConfigSnapshot] = []
        self._memory_source = MemorySource(priority=0)
        self._sources.append(self._memory_source)

    def add_source(self, source: ConfigSource) -> None:
        self._sources.append(source)
        self._sources.sort(key=lambda s: s.priority, reverse=True)
        logger.info(f"Added config source: {source.source_type} (priority: {source.priority})")

    async def load(self) -> Dict[str, Any]:
        merged_config: Dict[str, Any] = {}
        for source in self._sources:
            try:
                config = await source.load()
                merged_config = merge_dicts(merged_config, config)
            except Exception as e:
                logger.error(f"Failed to load from source {source.source_type}: {e}")

        old_config = self._config_cache.copy()
        self._config_cache = merged_config
        self._detect_changes(old_config, merged_config)
        logger.info(f"Loaded {len(merged_config)} config entries")
        return merged_config

    def _detect_changes(self, old: Dict[str, Any], new: Dict[str, Any]) -> List[ConfigChangeEvent]:
        events: List[ConfigChangeEvent] = []
        all_keys = set(old.keys()) | set(new.keys())

        for key in all_keys:
            old_value = old.get(key)
            new_value = new.get(key)
            if old_value != new_value:
                event = ConfigChangeEvent(
                    key=key,
                    old_value=old_value,
                    new_value=new_value,
                    namespace=self.namespace,
                    source=ConfigSourceType.MEMORY,
                )
                events.append(event)
                self._notify_listeners(event)

        return events

    def get(self, key: str, default: Any = None) -> Any:
        return self._config_cache.get(key, default)

    def get_int(self, key: str, default: int = 0) -> int:
        value = self.get(key, default)
        return int(value) if value is not None else default

    def get_float(self, key: str, default: float = 0.0) -> float:
        value = self.get(key, default)
        return float(value) if value is not None else default

    def get_bool(self, key: str, default: bool = False) -> bool:
        value = self.get(key, default)
        if isinstance(value, str):
            return value.lower() in ("true", "1", "yes")
        return bool(value) if value is not None else default

    def get_list(self, key: str, default: Optional[List[Any]] = None) -> List[Any]:
        value = self.get(key, default or [])
        if isinstance(value, str):
            return [item.strip() for item in value.split(",")]
        return value if isinstance(value, list) else default or []

    def get_dict(self, key: str, default: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        value = self.get(key, default or {})
        return value if isinstance(value, dict) else default or {}

    def set(self, key: str, value: Any) -> None:
        old_value = self._config_cache.get(key)
        self._memory_source.set(key, value)
        self._config_cache[key] = value
        if old_value != value:
            event = ConfigChangeEvent(
                key=key,
                old_value=old_value,
                new_value=value,
                namespace=self.namespace,
                source=ConfigSourceType.MEMORY,
            )
            self._notify_listeners(event)

    def delete(self, key: str) -> bool:
        if key in self._config_cache:
            old_value = self._config_cache.pop(key)
            self._memory_source.delete(key)
            event = ConfigChangeEvent(
                key=key,
                old_value=old_value,
                new_value=None,
                namespace=self.namespace,
                source=ConfigSourceType.MEMORY,
            )
            self._notify_listeners(event)
            return True
        return False

    def exists(self, key: str) -> bool:
        return key in self._config_cache

    def get_all(self) -> Dict[str, Any]:
        return self._config_cache.copy()

    def get_with_prefix(self, prefix: str) -> Dict[str, Any]:
        return {k: v for k, v in self._config_cache.items() if k.startswith(prefix)}

    def subscribe(self, key: str, callback: Callable[[ConfigChangeEvent], None]) -> None:
        if key not in self._listeners:
            self._listeners[key] = []
        self._listeners[key].append(callback)

    def subscribe_all(self, callback: Callable[[ConfigChangeEvent], None]) -> None:
        self.subscribe("*", callback)

    def _notify_listeners(self, event: ConfigChangeEvent) -> None:
        for pattern in [event.key, "*"]:
            for callback in self._listeners.get(pattern, []):
                try:
                    callback(event)
                except Exception as e:
                    logger.error(f"Error in config listener for {event.key}: {e}")

    def add_validation_rule(self, rule: ConfigValidationRule) -> None:
        self._validation_rules.append(rule)

    def validate(self) -> List[str]:
        errors: List[str] = []
        for rule in self._validation_rules:
            import fnmatch
            for key, value in self._config_cache.items():
                if not fnmatch.fnmatch(key, rule.key_pattern):
                    continue
                if rule.required and value is None:
                    errors.append(f"Config '{key}' is required")
                if rule.value_type:
                    from src.config.models import ConfigValueType
                    type_checks = {
                        ConfigValueType.STRING: lambda v: isinstance(v, str),
                        ConfigValueType.INTEGER: lambda v: isinstance(v, int) and not isinstance(v, bool),
                        ConfigValueType.FLOAT: lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
                        ConfigValueType.BOOLEAN: lambda v: isinstance(v, bool),
                        ConfigValueType.LIST: lambda v: isinstance(v, list),
                        ConfigValueType.DICT: lambda v: isinstance(v, dict),
                    }
                    checker = type_checks.get(rule.value_type)
                    if checker and not checker(value):
                        errors.append(f"Config '{key}' must be of type {rule.value_type}")
                if rule.allowed_values and value not in rule.allowed_values:
                    errors.append(f"Config '{key}' must be one of {rule.allowed_values}")
                if rule.min_value is not None and isinstance(value, (int, float)):
                    if value < rule.min_value:
                        errors.append(f"Config '{key}' must be >= {rule.min_value}")
                if rule.max_value is not None and isinstance(value, (int, float)):
                    if value > rule.max_value:
                        errors.append(f"Config '{key}' must be <= {rule.max_value}")
        return errors

    def snapshot(self) -> ConfigSnapshot:
        snap = ConfigSnapshot(
            namespace=self.namespace,
            data=self.get_all(),
        )
        self._snapshots.append(snap)
        return snap

    def rollback(self, snapshot_id: str) -> bool:
        for snap in self._snapshots:
            if snap.snapshot_id == snapshot_id:
                old_config = self._config_cache.copy()
                self._config_cache = snap.data.copy()
                self._detect_changes(old_config, snap.data)
                return True
        return False

    def diff(self, other_config: Dict[str, Any]) -> ConfigDiff:
        current = self._config_cache
        added = {k: v for k, v in other_config.items() if k not in current}
        removed = {k: v for k, v in current.items() if k not in other_config}
        changed = {}
        for k in set(current.keys()) & set(other_config.keys()):
            if current[k] != other_config[k]:
                changed[k] = {"old": current[k], "new": other_config[k]}
        return ConfigDiff(
            namespace=self.namespace,
            added=added,
            removed=removed,
            changed=changed,
        )

    async def refresh(self) -> Dict[str, Any]:
        return await self.load()
