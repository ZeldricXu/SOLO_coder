from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Optional
import threading

from streamsql.core.events import Event, EventBus, EventType

from streamsql.modules.metadata_crawler.strategies import CrawlStrategyConfig


@dataclass
class ConfigUpdateEvent:
    datasource: str
    old_config: CrawlStrategyConfig
    new_config: CrawlStrategyConfig
    updated_at: datetime = field(default_factory=datetime.utcnow)
    updated_by: str = "system"


class DynamicConfigManager:
    """
    Manages dynamic configuration for metadata crawler with hot reload support.

    Supports:
    - In-memory configuration with thread-safe updates
    - File-based configuration with automatic reload
    - Configuration change callbacks
    - Version tracking and rollback
    """

    def __init__(self, event_bus: Optional[EventBus] = None):
        self._event_bus = event_bus or EventBus()
        self._configs: dict[str, CrawlStrategyConfig] = {}
        self._config_versions: dict[str, list[CrawlStrategyConfig]] = {}
        self._callbacks: list[Callable[[ConfigUpdateEvent], Any]] = []
        self._file_watcher_task: Optional[asyncio.Task] = None
        self._watch_files: dict[str, str] = {}
        self._lock = threading.RLock()
        self._default_config = CrawlStrategyConfig()

    def get_config(self, datasource: str) -> CrawlStrategyConfig:
        """Get configuration for a datasource, returns default if not found."""
        with self._lock:
            return self._configs.get(datasource, self._default_config).clone()

    def set_config(
        self,
        datasource: str,
        config: CrawlStrategyConfig,
        notify: bool = True,
        updated_by: str = "system",
    ) -> ConfigUpdateEvent:
        """Set configuration for a datasource."""
        with self._lock:
            old_config = self._configs.get(datasource, self._default_config).clone()
            config.updated_at = datetime.utcnow()
            self._configs[datasource] = config.clone()

            if datasource not in self._config_versions:
                self._config_versions[datasource] = []
            self._config_versions[datasource].append(config.clone())

            if len(self._config_versions[datasource]) > 100:
                self._config_versions[datasource] = self._config_versions[datasource][-100:]

            event = ConfigUpdateEvent(
                datasource=datasource,
                old_config=old_config,
                new_config=config.clone(),
                updated_by=updated_by,
            )

        if notify:
            self._notify_callbacks(event)

        return event

    def update_config(
        self,
        datasource: str,
        updates: dict[str, Any],
        notify: bool = True,
        updated_by: str = "system",
    ) -> ConfigUpdateEvent:
        """Update specific fields of a datasource configuration."""
        current = self.get_config(datasource)
        current_dict = current.to_dict()
        current_dict.update(updates)
        new_config = CrawlStrategyConfig.from_dict(current_dict)
        return self.set_config(datasource, new_config, notify, updated_by)

    def rollback(self, datasource: str, versions: int = 1) -> Optional[ConfigUpdateEvent]:
        """Rollback to a previous configuration version."""
        with self._lock:
            if datasource not in self._config_versions:
                return None

            history = self._config_versions[datasource]
            if len(history) <= versions:
                return None

            target_idx = len(history) - versions - 1
            target_config = history[target_idx]

            self._config_versions[datasource] = history[:target_idx + 1]

            old_config = self._configs.get(datasource, self._default_config).clone()
            self._configs[datasource] = target_config.clone()

            event = ConfigUpdateEvent(
                datasource=datasource,
                old_config=old_config,
                new_config=target_config.clone(),
                updated_by="rollback",
            )

        self._notify_callbacks(event)
        return event

    def get_version_history(self, datasource: str) -> list[CrawlStrategyConfig]:
        """Get version history for a datasource."""
        with self._lock:
            return [c.clone() for c in self._config_versions.get(datasource, [])]

    def list_configs(self) -> list[tuple[str, CrawlStrategyConfig]]:
        """List all managed configurations."""
        with self._lock:
            return [(ds, cfg.clone()) for ds, cfg in self._configs.items()]

    def register_callback(self, callback: Callable[[ConfigUpdateEvent], Any]) -> None:
        """Register a callback to be called on configuration updates."""
        self._callbacks.append(callback)

    def unregister_callback(self, callback: Callable[[ConfigUpdateEvent], Any]) -> None:
        """Unregister a callback."""
        if callback in self._callbacks:
            self._callbacks.remove(callback)

    def _notify_callbacks(self, event: ConfigUpdateEvent) -> None:
        """Notify all registered callbacks about a configuration change."""
        self._event_bus.emit(
            Event(
                EventType.CONFIG_UPDATED,
                {
                    "datasource": event.datasource,
                    "old_config": event.old_config.to_dict(),
                    "new_config": event.new_config.to_dict(),
                    "updated_by": event.updated_by,
                },
            )
        )

        for callback in self._callbacks:
            try:
                callback(event)
            except Exception:
                pass

    async def watch_file(
        self,
        datasource: str,
        file_path: str,
        reload_interval: int = 5,
    ) -> None:
        """Watch a YAML/JSON file for configuration changes and auto-reload."""
        import yaml
        import json

        self._watch_files[datasource] = file_path

        async def watcher():
            last_modified = 0.0
            while True:
                try:
                    path = Path(file_path)
                    if path.exists():
                        mtime = path.stat().st_mtime
                        if mtime > last_modified:
                            last_modified = mtime
                            content = path.read_text()

                            if file_path.endswith((".yaml", ".yml")):
                                data = yaml.safe_load(content)
                            elif file_path.endswith(".json"):
                                data = json.loads(content)
                            else:
                                data = {}

                            if data:
                                new_config = CrawlStrategyConfig.from_dict(data)
                                self.set_config(
                                    datasource,
                                    new_config,
                                    updated_by="file_watcher",
                                )
                except Exception:
                    pass

                await asyncio.sleep(reload_interval)

        self._file_watcher_task = asyncio.create_task(watcher())

    def stop_watching(self, datasource: Optional[str] = None) -> None:
        """Stop watching configuration files."""
        if datasource:
            self._watch_files.pop(datasource, None)
        else:
            self._watch_files.clear()

        if self._file_watcher_task and not self._file_watcher_task.done():
            self._file_watcher_task.cancel()

    def export_config(self, datasource: str) -> dict[str, Any]:
        """Export configuration as a dictionary."""
        return self.get_config(datasource).to_dict()

    def import_config(
        self,
        datasource: str,
        config_dict: dict[str, Any],
        notify: bool = True,
    ) -> ConfigUpdateEvent:
        """Import configuration from a dictionary."""
        config = CrawlStrategyConfig.from_dict(config_dict)
        return self.set_config(datasource, config, notify)

    def reset_config(self, datasource: str, notify: bool = True) -> ConfigUpdateEvent:
        """Reset configuration to default."""
        return self.set_config(datasource, self._default_config.clone(), notify)

    def has_config(self, datasource: str) -> bool:
        """Check if a datasource has a custom configuration."""
        with self._lock:
            return datasource in self._configs


_global_config_manager: Optional[DynamicConfigManager] = None


def get_global_config_manager() -> DynamicConfigManager:
    """Get the global DynamicConfigManager instance."""
    global _global_config_manager
    if _global_config_manager is None:
        _global_config_manager = DynamicConfigManager()
    return _global_config_manager
