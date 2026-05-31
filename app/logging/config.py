"""
Logging configuration models.
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional


class LogFormatType(str, Enum):
    JSON = "json"
    TEXT = "text"
    COLOR = "color"


class LogHandlerType(str, Enum):
    CONSOLE = "console"
    FILE = "file"
    ROTATING_FILE = "rotating_file"
    NETWORK = "network"


class LogLevel(str, Enum):
    NOTSET = "NOTSET"
    DEBUG = "DEBUG"
    INFO = "INFO"
    WARNING = "WARNING"
    ERROR = "ERROR"
    CRITICAL = "CRITICAL"


@dataclass
class HandlerConfig:
    name: str
    handler_type: LogHandlerType
    level: LogLevel = LogLevel.INFO
    enabled: bool = True
    params: Dict[str, Any] = field(default_factory=dict)


@dataclass
class SceneConfig:
    name: str
    level: LogLevel = LogLevel.INFO
    handlers: List[str] = field(default_factory=list)
    formatter_type: LogFormatType = LogFormatType.JSON
    include_trace_id: bool = True
    include_context: bool = True


@dataclass
class LoggingConfig:
    config_id: str = "default"
    version: int = 1
    global_level: LogLevel = LogLevel.DEBUG
    handlers: Dict[str, HandlerConfig] = field(default_factory=dict)
    scenes: Dict[str, SceneConfig] = field(default_factory=dict)
    default_scene: str = "default"


def create_default_config() -> LoggingConfig:
    return LoggingConfig(
        config_id="default",
        version=1,
        global_level=LogLevel.DEBUG,
        handlers={
            "console": HandlerConfig(
                name="console",
                handler_type=LogHandlerType.CONSOLE,
                level=LogLevel.INFO,
                enabled=True,
                params={}
            ),
            "file": HandlerConfig(
                name="file",
                handler_type=LogHandlerType.ROTATING_FILE,
                level=LogLevel.DEBUG,
                enabled=True,
                params={
                    "filename": "app.log",
                    "max_bytes": 10 * 1024 * 1024,
                    "backup_count": 5,
                    "log_dir": "./logs"
                }
            )
        },
        scenes={
            "default": SceneConfig(
                name="default",
                level=LogLevel.INFO,
                handlers=["console", "file"],
                formatter_type=LogFormatType.JSON
            ),
            "debug": SceneConfig(
                name="debug",
                level=LogLevel.DEBUG,
                handlers=["console", "file"],
                formatter_type=LogFormatType.JSON
            ),
            "production": SceneConfig(
                name="production",
                level=LogLevel.WARNING,
                handlers=["file"],
                formatter_type=LogFormatType.JSON
            ),
            "trace": SceneConfig(
                name="trace",
                level=LogLevel.DEBUG,
                handlers=["console", "file"],
                formatter_type=LogFormatType.JSON,
                include_trace_id=True
            )
        },
        default_scene="default"
    )
