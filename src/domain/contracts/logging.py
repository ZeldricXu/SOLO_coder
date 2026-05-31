"""Logging-related contract interfaces."""
from __future__ import annotations

from abc import ABC, abstractmethod
from enum import Enum
from typing import Any, Dict, Optional


class LogLevel(str, Enum):
    DEBUG = "DEBUG"
    INFO = "INFO"
    WARNING = "WARNING"
    ERROR = "ERROR"
    CRITICAL = "CRITICAL"


class ILogger(ABC):
    @abstractmethod
    def debug(self, message: str, **kwargs: Any) -> None:
        pass

    @abstractmethod
    def info(self, message: str, **kwargs: Any) -> None:
        pass

    @abstractmethod
    def warning(self, message: str, **kwargs: Any) -> None:
        pass

    @abstractmethod
    def error(self, message: str, **kwargs: Any) -> None:
        pass

    @abstractmethod
    def critical(self, message: str, **kwargs: Any) -> None:
        pass

    @abstractmethod
    def log(
        self,
        level: LogLevel,
        message: str,
        exc_info: Optional[BaseException] = None,
        **kwargs: Any,
    ) -> None:
        pass

    @abstractmethod
    def get_level(self) -> LogLevel:
        pass

    @abstractmethod
    def set_level(self, level: LogLevel) -> None:
        pass


class ILogManager(ABC):
    @abstractmethod
    def get_logger(self, name: str) -> ILogger:
        pass

    @abstractmethod
    def set_global_level(self, level: LogLevel) -> None:
        pass

    @abstractmethod
    def get_global_level(self) -> LogLevel:
        pass

    @abstractmethod
    def set_logger_level(self, logger_name: str, level: LogLevel) -> None:
        pass

    @abstractmethod
    def get_logger_level(self, logger_name: str) -> LogLevel:
        pass

    @abstractmethod
    def list_loggers(self) -> Dict[str, LogLevel]:
        pass

    @abstractmethod
    def reload_config(self) -> None:
        pass
