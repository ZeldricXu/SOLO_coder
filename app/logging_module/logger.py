import sys
import json
import time
from typing import Any, Dict, Optional
from loguru import logger
from pythonjsonlogger import jsonlogger
from datetime import datetime


class StructuredLogger:
    _instance: Optional["StructuredLogger"] = None
    _initialized: bool = False

    def __new__(cls) -> "StructuredLogger":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, level: str = "INFO", format_type: str = "json"):
        if StructuredLogger._initialized:
            return
        
        self._configure_logger(level, format_type)
        StructuredLogger._initialized = True

    def _configure_logger(self, level: str, format_type: str):
        logger.remove()
        
        if format_type == "json":
            logger.add(
                sys.stdout,
                level=level,
                serialize=True,
                format=self._json_formatter,
                enqueue=True
            )
        else:
            logger.add(
                sys.stdout,
                level=level,
                format="<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | <level>{level: <8}</level> | <cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - <level>{message}</level>",
                enqueue=True
            )

    def _json_formatter(self, record):
        log_record = {
            "timestamp": record["time"].strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
            "level": record["level"].name,
            "module": record["name"],
            "function": record["function"],
            "line": record["line"],
            "message": record["message"],
        }
        if record["extra"]:
            log_record["extra"] = record["extra"]
        if record["exception"]:
            log_record["exception"] = str(record["exception"])
        return json.dumps(log_record, ensure_ascii=False)

    def info(self, message: str, **kwargs: Any):
        logger.bind(**kwargs).info(message)

    def debug(self, message: str, **kwargs: Any):
        logger.bind(**kwargs).debug(message)

    def warning(self, message: str, **kwargs: Any):
        logger.bind(**kwargs).warning(message)

    def error(self, message: str, **kwargs: Any):
        logger.bind(**kwargs).error(message)

    def critical(self, message: str, **kwargs: Any):
        logger.bind(**kwargs).critical(message)

    def bind(self, **kwargs: Any) -> Any:
        return logger.bind(**kwargs)


def get_logger(name: Optional[str] = None) -> StructuredLogger:
    return StructuredLogger()
