"""Structured logging implementation with dynamic log level support."""
from __future__ import annotations

import json
import logging
import logging.handlers
import os
import sys
from datetime import datetime
from typing import Any, Dict, Optional
from pythonjsonlogger import jsonlogger

from ...domain.contracts.logging import ILogger, ILogManager, LogLevel as ContractLogLevel


LogLevel = ContractLogLevel


class StructuredLogger(ILogger):
    def __init__(self, name: str, level: LogLevel = LogLevel.INFO) -> None:
        self._name = name
        self._logger = logging.getLogger(name)
        self.set_level(level)
        self._setup_handler()

    def _setup_handler(self) -> None:
        if not self._logger.handlers:
            handler = logging.StreamHandler(sys.stdout)
            formatter = jsonlogger.JsonFormatter(
                "%(asctime)s %(name)s %(levelname)s %(message)s"
            )
            handler.setFormatter(formatter)
            self._logger.addHandler(handler)
            self._logger.propagate = False

    def debug(self, message: str, **kwargs: Any) -> None:
        self.log(LogLevel.DEBUG, message, **kwargs)

    def info(self, message: str, **kwargs: Any) -> None:
        self.log(LogLevel.INFO, message, **kwargs)

    def warning(self, message: str, **kwargs: Any) -> None:
        self.log(LogLevel.WARNING, message, **kwargs)

    def error(self, message: str, **kwargs: Any) -> None:
        self.log(LogLevel.ERROR, message, **kwargs)

    def critical(self, message: str, **kwargs: Any) -> None:
        self.log(LogLevel.CRITICAL, message, **kwargs)

    def log(
        self,
        level: LogLevel,
        message: str,
        exc_info: Optional[BaseException] = None,
        **kwargs: Any,
    ) -> None:
        if not self._is_level_enabled(level):
            return

        log_data = {
            "timestamp": datetime.utcnow().isoformat(),
            "logger": self._name,
            "level": level.value,
            "message": message,
        }
        if kwargs:
            log_data["context"] = kwargs

        log_method = getattr(self._logger, level.value.lower())
        log_method(json.dumps(log_data), exc_info=exc_info)

    def _is_level_enabled(self, level: LogLevel) -> bool:
        level_order = [
            LogLevel.DEBUG,
            LogLevel.INFO,
            LogLevel.WARNING,
            LogLevel.ERROR,
            LogLevel.CRITICAL,
        ]
        return level_order.index(level) >= level_order.index(self.get_level())

    def get_level(self) -> LogLevel:
        return LogLevel(self._logger.level) if self._logger.level else LogLevel.INFO

    def set_level(self, level: LogLevel) -> None:
        self._logger.setLevel(level.value)
        for handler in self._logger.handlers:
            handler.setLevel(level.value)


class LogManager(ILogManager):
    _instance: Optional["LogManager"] = None
    _global_level: LogLevel = LogLevel.INFO
    _loggers: Dict[str, StructuredLogger] = {}

    def __new__(cls) -> "LogManager":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self) -> None:
        if not hasattr(self, "_initialized"):
            self._initialized = True
            self._file_handler: Optional[logging.handlers.RotatingFileHandler] = None

    def get_logger(self, name: str) -> StructuredLogger:
        if name not in self._loggers:
            self._loggers[name] = StructuredLogger(name, self._global_level)
        return self._loggers[name]

    def set_global_level(self, level: LogLevel) -> None:
        self._global_level = level
        for logger in self._loggers.values():
            logger.set_level(level)

    def get_global_level(self) -> LogLevel:
        return self._global_level

    def set_logger_level(self, logger_name: str, level: LogLevel) -> None:
        if logger_name in self._loggers:
            self._loggers[logger_name].set_level(level)
        else:
            logger = self.get_logger(logger_name)
            logger.set_level(level)

    def get_logger_level(self, logger_name: str) -> LogLevel:
        if logger_name in self._loggers:
            return self._loggers[logger_name].get_level()
        return self._global_level

    def list_loggers(self) -> Dict[str, LogLevel]:
        return {name: logger.get_level() for name, logger in self._loggers.items()}

    def reload_config(self) -> None:
        pass

    def setup_file_logging(self, log_file_path: str, max_mb: int = 100, backup_count: int = 5) -> None:
        os.makedirs(os.path.dirname(log_file_path), exist_ok=True)

        if self._file_handler is not None:
            for logger in self._loggers.values():
                logger._logger.removeHandler(self._file_handler)

        self._file_handler = logging.handlers.RotatingFileHandler(
            log_file_path,
            maxBytes=max_mb * 1024 * 1024,
            backupCount=backup_count,
        )
        formatter = jsonlogger.JsonFormatter(
            "%(asctime)s %(name)s %(levelname)s %(message)s"
        )
        self._file_handler.setFormatter(formatter)

        for logger in self._loggers.values():
            logger._logger.addHandler(self._file_handler)
