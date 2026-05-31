"""
结构化日志实现 - 遵循 LoggerProtocol
"""

from __future__ import annotations

import json
import os
import sys
import time
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, TextIO

from src.domain.contracts.tracing import LoggerProtocol, TraceContext
from src.domain.models.tracing import LogEntry, LogLevel


class LoggerFormatter(ABC):
    @abstractmethod
    def format(self, entry: LogEntry) -> str: ...


class JsonFormatter(LoggerFormatter):
    def format(self, entry: LogEntry) -> str:
        return entry.to_json()


class TextFormatter(LoggerFormatter):
    def format(self, entry: LogEntry) -> str:
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(entry.timestamp))
        level = entry.level.value.upper().ljust(8)
        trace_info = f"[trace_id={entry.trace_id} span_id={entry.span_id}]" if entry.trace_id else ""
        extra = " ".join(f"{k}={v}" for k, v in entry.extra.items())
        return f"{timestamp} {level} {entry.service_name} {trace_info} {entry.message} {extra}".strip()


class LoggerHandler(ABC):
    def __init__(self, level: LogLevel = LogLevel.DEBUG) -> None:
        self.level = level
        self.formatter: LoggerFormatter = JsonFormatter()

    def set_formatter(self, formatter: LoggerFormatter) -> None:
        self.formatter = formatter

    def should_log(self, entry_level: LogLevel) -> bool:
        levels = list(LogLevel)
        return levels.index(entry_level) >= levels.index(self.level)

    @abstractmethod
    def emit(self, entry: LogEntry) -> None: ...


class ConsoleHandler(LoggerHandler):
    def __init__(
        self,
        level: LogLevel = LogLevel.INFO,
        stream: Optional[TextIO] = None,
        formatter: Optional[LoggerFormatter] = None,
    ) -> None:
        super().__init__(level)
        self.stream = stream or sys.stdout
        if formatter:
            self.set_formatter(formatter)

    def emit(self, entry: LogEntry) -> None:
        if not self.should_log(entry.level):
            return
        output = self.formatter.format(entry)
        self.stream.write(output + "\n")
        self.stream.flush()


class FileHandler(LoggerHandler):
    def __init__(
        self,
        file_path: str,
        level: LogLevel = LogLevel.DEBUG,
        max_bytes: int = 10 * 1024 * 1024,
        backup_count: int = 5,
    ) -> None:
        super().__init__(level)
        self.file_path = file_path
        self.max_bytes = max_bytes
        self.backup_count = backup_count
        self._ensure_dir()

    def _ensure_dir(self) -> None:
        dir_path = os.path.dirname(self.file_path)
        if dir_path and not os.path.exists(dir_path):
            os.makedirs(dir_path, exist_ok=True)

    def _should_rotate(self) -> bool:
        if not os.path.exists(self.file_path):
            return False
        return os.path.getsize(self.file_path) >= self.max_bytes

    def _rotate(self) -> None:
        for i in range(self.backup_count - 1, 0, -1):
            src = f"{self.file_path}.{i}"
            dst = f"{self.file_path}.{i + 1}"
            if os.path.exists(src):
                os.rename(src, dst)
        if os.path.exists(self.file_path):
            os.rename(self.file_path, f"{self.file_path}.1")

    def emit(self, entry: LogEntry) -> None:
        if not self.should_log(entry.level):
            return
        if self._should_rotate():
            self._rotate()
        output = self.formatter.format(entry)
        with open(self.file_path, "a", encoding="utf-8") as f:
            f.write(output + "\n")


class StructuredLogger(LoggerProtocol):
    def __init__(
        self,
        service_name: str = "default",
        handlers: Optional[List[LoggerHandler]] = None,
    ) -> None:
        self.service_name = service_name
        self.handlers = handlers or [ConsoleHandler()]
        self._context: Dict[str, Any] = {}
        self._trace_ctx: Optional[TraceContext] = None

    def _create_entry(self, level: LogLevel, message: str, **kwargs: Any) -> LogEntry:
        trace_id = self._trace_ctx.trace_id if self._trace_ctx else ""
        span_id = self._trace_ctx.span_id if self._trace_ctx else ""
        return LogEntry(
            level=level,
            message=message,
            service_name=self.service_name,
            trace_id=trace_id,
            span_id=span_id,
            extra={**self._context, **kwargs},
        )

    def _log(self, level: LogLevel, message: str, **kwargs: Any) -> None:
        entry = self._create_entry(level, message, **kwargs)
        for handler in self.handlers:
            handler.emit(entry)

    def debug(self, message: str, **kwargs: Any) -> None:
        self._log(LogLevel.DEBUG, message, **kwargs)

    def info(self, message: str, **kwargs: Any) -> None:
        self._log(LogLevel.INFO, message, **kwargs)

    def warning(self, message: str, **kwargs: Any) -> None:
        self._log(LogLevel.WARNING, message, **kwargs)

    def error(self, message: str, **kwargs: Any) -> None:
        self._log(LogLevel.ERROR, message, **kwargs)

    def critical(self, message: str, **kwargs: Any) -> None:
        self._log(LogLevel.CRITICAL, message, **kwargs)

    def with_trace(self, trace_ctx: TraceContext) -> "StructuredLogger":
        new_logger = StructuredLogger(self.service_name, self.handlers)
        new_logger._context = {**self._context}
        new_logger._trace_ctx = trace_ctx
        return new_logger

    def with_context(self, **kwargs: Any) -> "StructuredLogger":
        new_logger = StructuredLogger(self.service_name, self.handlers)
        new_logger._context = {**self._context, **kwargs}
        new_logger._trace_ctx = self._trace_ctx
        return new_logger
