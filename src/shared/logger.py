from __future__ import annotations

import json
import logging
import sys
from datetime import datetime
from typing import Any, Dict, Optional
from uuid import uuid4


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        log_entry = {
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "trace_id": getattr(record, "trace_id", None),
            "module": record.module,
            "line": record.lineno,
        }

        if hasattr(record, "extra") and record.extra:
            log_entry.update(record.extra)

        if record.exc_info:
            log_entry["exception"] = self.formatException(record.exc_info)

        return json.dumps(log_entry, ensure_ascii=False)


class StructuredLogger:
    def __init__(self, name: str, level: int = logging.INFO):
        self._logger = logging.getLogger(name)
        self._logger.setLevel(level)

        if not self._logger.handlers:
            handler = logging.StreamHandler(sys.stdout)
            handler.setFormatter(JsonFormatter())
            self._logger.addHandler(handler)

    def _log(
        self,
        level: int,
        message: str,
        trace_id: Optional[str] = None,
        **extra: Any,
    ) -> None:
        record = logging.LogRecord(
            name=self._logger.name,
            level=level,
            pathname="",
            lineno=0,
            msg=message,
            args=(),
            exc_info=None,
        )
        record.trace_id = trace_id
        record.extra = extra
        self._logger.handle(record)

    def debug(self, message: str, trace_id: Optional[str] = None, **extra: Any) -> None:
        self._log(logging.DEBUG, message, trace_id, **extra)

    def info(self, message: str, trace_id: Optional[str] = None, **extra: Any) -> None:
        self._log(logging.INFO, message, trace_id, **extra)

    def warning(self, message: str, trace_id: Optional[str] = None, **extra: Any) -> None:
        self._log(logging.WARNING, message, trace_id, **extra)

    def error(self, message: str, trace_id: Optional[str] = None, **extra: Any) -> None:
        self._log(logging.ERROR, message, trace_id, **extra)

    def critical(self, message: str, trace_id: Optional[str] = None, **extra: Any) -> None:
        self._log(logging.CRITICAL, message, trace_id, **extra)

    def exception(
        self,
        message: str,
        trace_id: Optional[str] = None,
        exc_info: bool = True,
        **extra: Any,
    ) -> None:
        if exc_info:
            extra["exc_info"] = True
        self._log(logging.ERROR, message, trace_id, **extra)


def get_logger(name: str, level: int = logging.INFO) -> StructuredLogger:
    return StructuredLogger(name, level)


def generate_trace_id() -> str:
    return f"trace_{uuid4().hex[:24]}"


class LogContext:
    def __init__(self, trace_id: Optional[str] = None, **context: Any):
        self.trace_id = trace_id or generate_trace_id()
        self.context: Dict[str, Any] = context

    def add(self, **kwargs: Any) -> "LogContext":
        self.context.update(kwargs)
        return self

    def get_log_kwargs(self) -> Dict[str, Any]:
        return {"trace_id": self.trace_id, **self.context}
