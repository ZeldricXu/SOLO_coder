import logging
import sys
from typing import Optional, Dict, Any
from datetime import datetime
from threading import Lock
from ..config import settings


class DynamicLogger:
    _instance: Optional['DynamicLogger'] = None
    _lock: Lock = Lock()

    def __new__(cls) -> 'DynamicLogger':
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
                    cls._instance._initialize()
        return cls._instance

    def _initialize(self) -> None:
        self._loggers: Dict[str, logging.Logger] = {}
        self._current_level: int = self._parse_level(settings.log_level)
        self._handlers: list[logging.Handler] = []
        self._setup_default_handlers()

    @staticmethod
    def _parse_level(level_str: str) -> int:
        level_map = {
            "DEBUG": logging.DEBUG,
            "INFO": logging.INFO,
            "WARNING": logging.WARNING,
            "ERROR": logging.ERROR,
            "CRITICAL": logging.CRITICAL,
        }
        return level_map.get(level_str.upper(), logging.INFO)

    def _setup_default_handlers(self) -> None:
        console_handler = logging.StreamHandler(sys.stdout)
        console_handler.setLevel(self._current_level)
        formatter = logging.Formatter(
            "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
        )
        console_handler.setFormatter(formatter)
        self._handlers.append(console_handler)

        if settings.log_file:
            file_handler = logging.FileHandler(settings.log_file)
            file_handler.setLevel(self._current_level)
            file_handler.setFormatter(formatter)
            self._handlers.append(file_handler)

    def get_logger(self, name: str) -> logging.Logger:
        if name not in self._loggers:
            logger = logging.getLogger(name)
            logger.setLevel(self._current_level)
            logger.propagate = False
            for handler in self._handlers:
                logger.addHandler(handler)
            self._loggers[name] = logger
        return self._loggers[name]

    def set_level(self, level_str: str, logger_name: Optional[str] = None) -> None:
        new_level = self._parse_level(level_str)
        self._current_level = new_level

        if logger_name:
            if logger_name in self._loggers:
                self._loggers[logger_name].setLevel(new_level)
        else:
            for logger in self._loggers.values():
                logger.setLevel(new_level)
            for handler in self._handlers:
                handler.setLevel(new_level)

    def get_current_level(self) -> str:
        return logging.getLevelName(self._current_level)

    def get_all_loggers(self) -> Dict[str, str]:
        return {
            name: logging.getLevelName(logger.level)
            for name, logger in self._loggers.items()
        }


def get_logger(name: str) -> logging.Logger:
    return DynamicLogger().get_logger(name)


def set_log_level(level: str, logger_name: Optional[str] = None) -> None:
    DynamicLogger().set_level(level, logger_name)


def get_current_log_level() -> str:
    return DynamicLogger().get_current_level()
