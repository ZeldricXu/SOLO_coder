"""
Logging strategy registry.
Supports different strategies for different scenes and hot swapping.
"""

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional

from app.logging.config import (
    LoggingConfig,
    SceneConfig,
    HandlerConfig,
    LogLevel,
    LogHandlerType,
    LogFormatType,
    create_default_config
)


class ConfigChangedCallback:
    def __init__(self, callback: Callable[[str, Any], None]):
        self._callback = callback
    
    def __call__(self, scene: str, config: Any):
        self._callback(scene, config)


class LoggingStrategyRegistry:
    def __init__(self):
        self._config: LoggingConfig = create_default_config()
        self._active_scene: str = self._config.default_scene
        self._listeners: Dict[str, List[Callable[[str, Any], None]]] = {}
        self._change_history: List[Dict[str, Any]] = []
    
    def get_config(self) -> LoggingConfig:
        return self._config
    
    def set_config(self, config: LoggingConfig):
        old_config = self._config
        self._config = config
        self._record_change("full_config", old_config, config)
        self._notify_listeners("*", config)
    
    def get_active_scene(self) -> str:
        return self._active_scene
    
    def set_active_scene(self, scene_name: str) -> bool:
        if scene_name not in self._config.scenes:
            return False
        
        old_scene = self._active_scene
        self._active_scene = scene_name
        scene_config = self._config.scenes[scene_name]
        self._record_change("scene_switch", old_scene, scene_name)
        self._notify_listeners(scene_name, scene_config)
        return True
    
    def get_scene_config(self, scene_name: Optional[str] = None) -> SceneConfig:
        scene = scene_name or self._active_scene
        if scene in self._config.scenes:
            return self._config.scenes[scene]
        
        default_scene = self._config.scenes.get(self._config.default_scene)
        if default_scene:
            return default_scene
        
        return SceneConfig(name=scene)
    
    def update_scene(self, scene_name: str, scene_config: SceneConfig) -> bool:
        old_config = self._config.scenes.get(scene_name)
        self._config.scenes[scene_name] = scene_config
        self._config.version += 1
        self._record_change("scene_update", old_config, scene_config)
        if scene_name == self._active_scene:
            self._notify_listeners(scene_name, scene_config)
        return True
    
    def update_handler(self, handler_name: str, handler_config: HandlerConfig) -> bool:
        old_config = self._config.handlers.get(handler_name)
        self._config.handlers[handler_name] = handler_config
        self._config.version += 1
        self._record_change("handler_update", old_config, handler_config)
        self._notify_listeners("handlers", handler_config)
        return True
    
    def update_level(self, level: LogLevel, scene_name: Optional[str] = None):
        scene = scene_name or self._active_scene
        if scene in self._config.scenes:
            old_level = self._config.scenes[scene].level
            self._config.scenes[scene].level = level
            self._config.version += 1
            self._record_change("level_update", old_level, level)
            self._notify_listeners(scene, self._config.scenes[scene])
    
    def register_listener(
        self,
        event_type: str,
        callback: Callable[[str, Any], None]
    ):
        if event_type not in self._listeners:
            self._listeners[event_type] = []
        self._listeners[event_type].append(callback)
    
    def unregister_listener(
        self,
        event_type: str,
        callback: Callable[[str, Any], None]
    ):
        if event_type in self._listeners and callback in self._listeners[event_type]:
            self._listeners[event_type].remove(callback)
    
    def _notify_listeners(self, event_type: str, data: Any):
        listeners = self._listeners.get(event_type, [])
        for callback in listeners:
            try:
                callback(event_type, data)
            except Exception:
                pass
        
        wildcards = self._listeners.get("*", [])
        for callback in wildcards:
            try:
                callback(event_type, data)
            except Exception:
                pass
    
    def _record_change(self, change_type: str, old_value: Any, new_value: Any):
        self._change_history.append({
            "timestamp": datetime.utcnow(),
            "type": change_type,
            "old": old_value,
            "new": new_value
        })
        if len(self._change_history) > 100:
            self._change_history = self._change_history[-50:]
    
    def get_change_history(self, limit: int = 20) -> List[Dict[str, Any]]:
        return self._change_history[-limit:]
    
    def list_scenes(self) -> List[str]:
        return list(self._config.scenes.keys())
    
    def list_handlers(self) -> List[str]:
        return list(self._config.handlers.keys())
