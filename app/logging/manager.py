"""
Logging lifecycle manager with dynamic configuration support.
"""

import logging
import os
from logging.handlers import RotatingFileHandler
from typing import Dict, List, Optional

from app.logging.config import (
    LoggingConfig,
    SceneConfig,
    HandlerConfig,
    LogLevel,
    LogHandlerType,
    LogFormatType
)
from app.logging.formatters import StructuredFormatter
from app.logging.logger import ContextLogger
from app.logging.registry import LoggingStrategyRegistry
from app.logging.storage import LogStorage


_LOG_LEVEL_MAP: Dict[str, int] = {
    "NOTSET": logging.NOTSET,
    "DEBUG": logging.DEBUG,
    "INFO": logging.INFO,
    "WARNING": logging.WARNING,
    "ERROR": logging.ERROR,
    "CRITICAL": logging.CRITICAL,
}


class LoggingManager:
    _instance: Optional["LoggingManager"] = None
    _initialized = False
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def __init__(self):
        if LoggingManager._initialized:
            return
        LoggingManager._initialized = True
        
        self._registry = LoggingStrategyRegistry()
        self._active_handlers: Dict[str, logging.Handler] = {}
        self._storage = LogStorage()
        
        self.root_logger = logging.getLogger()
        self._build_from_config(self._registry.get_config())
        self._register_config_listener()
    
    def _register_config_listener(self):
        def on_config_changed(event_type: str, data):
            self._reconfigure_from_event(event_type, data)
        self._registry.register_listener("*", on_config_changed)
    
    def _reconfigure_from_event(self, event_type: str, data):
        if event_type == "*" and isinstance(data, LoggingConfig):
            self._build_from_config(data)
        elif isinstance(data, SceneConfig) and event_type in self._registry.list_scenes():
            self._apply_scene_config(data)
        elif event_type == "handlers":
            self._update_single_handler(data)
    
    def _build_from_config(self, config: LoggingConfig):
        self.root_logger.handlers.clear()
        self._active_handlers.clear()
        
        global_level = _LOG_LEVEL_MAP.get(config.global_level.value, logging.DEBUG)
        self.root_logger.setLevel(global_level)
        
        for handler_name, handler_config in config.handlers.items():
            if not handler_config.enabled:
                continue
            handler = self._create_handler(handler_config)
            if handler:
                self._active_handlers[handler_name] = handler
                self.root_logger.addHandler(handler)
        
        active_scene = self._registry.get_active_scene()
        scene_config = config.scenes.get(active_scene)
        if scene_config:
            self._apply_scene_log_level(scene_config)
    
    def _create_handler(self, handler_config: HandlerConfig) -> Optional[logging.Handler]:
        level = _LOG_LEVEL_MAP.get(handler_config.level.value, logging.INFO)
        
        if handler_config.handler_type == LogHandlerType.CONSOLE:
            handler = logging.StreamHandler()
            handler.setLevel(level)
            handler.setFormatter(StructuredFormatter())
            return handler
        
        elif handler_config.handler_type == LogHandlerType.ROTATING_FILE:
            params = handler_config.params
            log_dir = params.get("log_dir", os.environ.get("LOG_DIR", "./logs"))
            os.makedirs(log_dir, exist_ok=True)
            
            filename = os.path.join(log_dir, params.get("filename", "app.log"))
            max_bytes = params.get("max_bytes", 10 * 1024 * 1024)
            backup_count = params.get("backup_count", 5)
            
            handler = RotatingFileHandler(
                filename=filename,
                maxBytes=max_bytes,
                backupCount=backup_count,
                encoding="utf-8"
            )
            handler.setLevel(level)
            handler.setFormatter(StructuredFormatter())
            return handler
        
        elif handler_config.handler_type == LogHandlerType.FILE:
            params = handler_config.params
            log_dir = params.get("log_dir", os.environ.get("LOG_DIR", "./logs"))
            os.makedirs(log_dir, exist_ok=True)
            
            filename = os.path.join(log_dir, params.get("filename", "app.log"))
            handler = logging.FileHandler(filename, encoding="utf-8")
            handler.setLevel(level)
            handler.setFormatter(StructuredFormatter())
            return handler
        
        return None
    
    def _apply_scene_config(self, scene_config: SceneConfig):
        self._apply_scene_log_level(scene_config)
        
        enabled_handlers = set(scene_config.handlers)
        for handler_name, handler in self._active_handlers.items():
            if handler_name in enabled_handlers:
                if handler_name not in self.root_logger.handlers:
                    self.root_logger.addHandler(handler)
            else:
                if handler in self.root_logger.handlers:
                    self.root_logger.removeHandler(handler)
    
    def _apply_scene_log_level(self, scene_config: SceneConfig):
        level = _LOG_LEVEL_MAP.get(scene_config.level.value, logging.INFO)
        self.root_logger.setLevel(level)
    
    def _update_single_handler(self, handler_config: HandlerConfig):
        old_handler = self._active_handlers.get(handler_config.name)
        if old_handler:
            if old_handler in self.root_logger.handlers:
                self.root_logger.removeHandler(old_handler)
        
        if handler_config.enabled:
            new_handler = self._create_handler(handler_config)
            if new_handler:
                self._active_handlers[handler_config.name] = new_handler
                scene_config = self._registry.get_scene_config()
                if handler_config.name in scene_config.handlers:
                    self.root_logger.addHandler(new_handler)
    
    def get_logger(self, name: str, trace_id: Optional[str] = None) -> ContextLogger:
        return ContextLogger(name, trace_id)
    
    def store_log(self, entry):
        self._storage.store(entry)
    
    def query_logs(
        self,
        level: Optional[str] = None,
        module: Optional[str] = None,
        since: Optional = None,
        limit: int = 100
    ) -> list:
        return self._storage.query_logs(level, module, since, limit)
    
    def cleanup_old_logs(self, retention_days: int = 7):
        self._storage.cleanup_old_logs(retention_days)
    
    def get_config(self) -> LoggingConfig:
        return self._registry.get_config()
    
    def update_config(self, config: LoggingConfig):
        self._registry.set_config(config)
    
    def switch_scene(self, scene_name: str) -> bool:
        return self._registry.set_active_scene(scene_name)
    
    def get_active_scene(self) -> str:
        return self._registry.get_active_scene()
    
    def update_log_level(self, level: LogLevel, scene_name: Optional[str] = None):
        self._registry.update_level(level, scene_name)
    
    def list_scenes(self) -> List[str]:
        return self._registry.list_scenes()
    
    def get_strategy_registry(self) -> LoggingStrategyRegistry:
        return self._registry
