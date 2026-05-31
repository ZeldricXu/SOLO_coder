import asyncio
import json
import os
import threading
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, TypeVar
from uuid import uuid4

import yaml
from watchdog.events import FileSystemEvent, FileSystemEventHandler
from watchdog.observers import Observer

from .settings import AppSettings, get_settings

T = TypeVar("T")


class ConfigSource(str, Enum):
    ENVIRONMENT = "environment"
    FILE = "file"
    DATABASE = "database"
    REMOTE = "remote"
    DEFAULT = "default"


class ConfigWatcher(FileSystemEventHandler):
    def __init__(self, callback: Callable[[str], None]):
        self.callback = callback

    def on_modified(self, event: FileSystemEvent) -> None:
        if not event.is_directory:
            self.callback(event.src_path)


class ConfigManager:
    def __init__(self, settings: Optional[AppSettings] = None):
        self.settings = settings or get_settings()
        self._configs: Dict[str, Dict[str, Any]] = {}
        self._versions: Dict[str, int] = {}
        self._listeners: List[Callable[[str, Dict[str, Any]], None]] = []
        self._observer: Optional[Observer] = None
        self._lock = threading.RLock()
        self._loaded = False

    def initialize(self) -> None:
        if self._loaded:
            return
        self._load_defaults()
        self._load_from_file()
        self._load_from_env()
        if self.settings.config_watch_enabled:
            self._start_watcher()
        self._loaded = True

    def _load_defaults(self) -> None:
        with self._lock:
            self._configs["default"] = {
                "timeout": 30,
                "retries": 3,
                "pool_size": 10,
                "log_level": "INFO",
                "max_connections": 100,
                "rate_limit": 1000,
            }
            self._versions["default"] = 1

    def _load_from_file(self) -> None:
        config_path = Path(self.settings.config_file)
        if not config_path.exists():
            return
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                if config_path.suffix in (".yaml", ".yml"):
                    data = yaml.safe_load(f) or {}
                elif config_path.suffix == ".json":
                    data = json.load(f)
                else:
                    return
            with self._lock:
                for namespace, params in data.items():
                    if isinstance(params, dict):
                        self._configs[namespace] = params
                        self._versions[namespace] = self._versions.get(namespace, 0) + 1
        except Exception:
            pass

    def _load_from_env(self) -> None:
        env_configs: Dict[str, Dict[str, Any]] = {}
        for key, value in os.environ.items():
            if key.startswith("CFG_"):
                parts = key[4:].split("__", 1)
                if len(parts) == 2:
                    namespace, param = parts[0].lower(), parts[1].lower()
                    if namespace not in env_configs:
                        env_configs[namespace] = {}
                    env_configs[namespace][param] = self._parse_value(value)
        with self._lock:
            for namespace, params in env_configs.items():
                if namespace not in self._configs:
                    self._configs[namespace] = {}
                self._configs[namespace].update(params)
                self._versions[namespace] = self._versions.get(namespace, 0) + 1

    def _parse_value(self, value: str) -> Any:
        value_lower = value.lower()
        if value_lower == "true":
            return True
        if value_lower == "false":
            return False
        if value_lower == "null" or value_lower == "none":
            return None
        try:
            return int(value)
        except ValueError:
            try:
                return float(value)
            except ValueError:
                try:
                    return json.loads(value)
                except Exception:
                    return value

    def _start_watcher(self) -> None:
        config_path = Path(self.settings.config_file)
        if not config_path.exists():
            return
        handler = ConfigWatcher(self._on_file_change)
        self._observer = Observer()
        self._observer.schedule(handler, str(config_path.parent), recursive=False)
        self._observer.start()

    def _on_file_change(self, path: str) -> None:
        if Path(path).resolve() == Path(self.settings.config_file).resolve():
            old_versions = dict(self._versions)
            self._load_from_file()
            for namespace, new_version in self._versions.items():
                if old_versions.get(namespace, 0) < new_version:
                    self._notify_listeners(namespace, self._configs[namespace])

    def add_listener(self, listener: Callable[[str, Dict[str, Any]], None]) -> None:
        with self._lock:
            self._listeners.append(listener)

    def remove_listener(self, listener: Callable[[str, Dict[str, Any]], None]) -> None:
        with self._lock:
            if listener in self._listeners:
                self._listeners.remove(listener)

    def _notify_listeners(self, namespace: str, config: Dict[str, Any]) -> None:
        with self._lock:
            listeners = list(self._listeners)
        for listener in listeners:
            try:
                listener(namespace, config)
            except Exception:
                pass

    def get(self, namespace: str = "default", key: Optional[str] = None, default: Any = None) -> Any:
        self.initialize()
        with self._lock:
            ns_config = self._configs.get(namespace, {})
            if key is None:
                return dict(ns_config)
            return ns_config.get(key, default)

    def set(self, namespace: str, key: str, value: Any, source: ConfigSource = ConfigSource.DEFAULT) -> None:
        self.initialize()
        with self._lock:
            if namespace not in self._configs:
                self._configs[namespace] = {}
            self._configs[namespace][key] = value
            self._versions[namespace] = self._versions.get(namespace, 0) + 1
        self._notify_listeners(namespace, self._configs[namespace])

    def update_namespace(self, namespace: str, updates: Dict[str, Any]) -> None:
        self.initialize()
        with self._lock:
            if namespace not in self._configs:
                self._configs[namespace] = {}
            self._configs[namespace].update(updates)
            self._versions[namespace] = self._versions.get(namespace, 0) + 1
        self._notify_listeners(namespace, self._configs[namespace])

    def get_version(self, namespace: str = "default") -> int:
        self.initialize()
        with self._lock:
            return self._versions.get(namespace, 0)

    def get_namespaces(self) -> List[str]:
        self.initialize()
        with self._lock:
            return list(self._configs.keys())

    def reload(self) -> None:
        self._load_from_file()
        self._load_from_env()

    def shutdown(self) -> None:
        if self._observer:
            self._observer.stop()
            self._observer.join()
            self._observer = None


_config_manager_instance: Optional[ConfigManager] = None
_config_lock = threading.Lock()


def get_config_manager() -> ConfigManager:
    global _config_manager_instance
    if _config_manager_instance is None:
        with _config_lock:
            if _config_manager_instance is None:
                _config_manager_instance = ConfigManager()
                _config_manager_instance.initialize()
    return _config_manager_instance


def reload_config() -> None:
    manager = get_config_manager()
    manager.reload()
