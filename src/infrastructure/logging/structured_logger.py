"""
结构化日志实现
遵循 LoggerProtocol 协议，支持多种输出格式和处理器
"""

from __future__ import annotations

import json
import os
import sys
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, TextIO

from src.core import LogEntry, LogLevel, LoggerProtocol, TraceContext


class LoggerFormatter(ABC):
    """日志格式化器抽象基类"""

    @abstractmethod
    def format(self, entry: LogEntry) -> str: ...


class JsonFormatter(LoggerFormatter):
    """JSON格式输出 - 适合机器解析"""

    def format(self, entry: LogEntry) -> str:
        return entry.to_json()


class TextFormatter(LoggerFormatter):
    """文本格式输出 - 适合人类阅读"""

    def format(self, entry: LogEntry) -> str:
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(entry.timestamp))
        level = entry.level.value.upper().ljust(8)
        trace_info = f"[trace_id={entry.trace_id} span_id={entry.span_id}]" if entry.trace_id else ""
        extra = " ".join(f"{k}={v}" for k, v in entry.extra.items())
        return f"{timestamp} {level} {entry.service_name} {trace_info} {entry.message} {extra}".strip()


class LoggerHandler(ABC):
    """日志处理器抽象基类"""

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
    """控制台输出处理器"""

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
    """文件输出处理器"""

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
    """
    结构化日志器实现
    符合 LoggerProtocol 协议，支持链路追踪和上下文传递
    """

    def __init__(
        self,
        service_name: str = "default",
        handlers: Optional[List[LoggerHandler]] = None,
    ) -> None:
        self.service_name = service_name
        self.handlers = handlers or [ConsoleHandler()]
        self._context: Dict[str, Any] = {}
        self._trace_ctx: Optional[TraceContext] = None

    def add_handler(self, handler: LoggerHandler) -> None:
        self.handlers.append(handler)

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

    def warn(self, message: str, **kwargs: Any) -> None:
        self.warning(message, **kwargs)

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

    def log(self, level: LogLevel, message: str, **kwargs: Any) -> None:
        self._log(level, message, **kwargs)

    def with_trace(
        self,
        trace_ctx: Optional[TraceContext] = None,
        *,
        trace_id: Optional[str] = None,
        span_id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        service_name: Optional[str] = None,
        **kwargs: Any,
    ) -> "StructuredLogger":
        from dataclasses import dataclass, field

        @dataclass
        class SimpleTraceContext:
            trace_id: str = ""
            span_id: str = ""
            parent_span_id: Optional[str] = None
            service_name: str = ""
            tags: Dict[str, Any] = field(default_factory=dict)

        if trace_ctx is not None:
            ctx = trace_ctx
        else:
            from uuid import uuid4
            ctx = SimpleTraceContext(
                trace_id=trace_id or f"trace-{uuid4().hex[:12]}",
                span_id=span_id or f"span-{uuid4().hex[:12]}",
                parent_span_id=parent_span_id,
                service_name=service_name or self.service_name,
                tags=kwargs,
            )

        new_logger = StructuredLogger(self.service_name, self.handlers)
        new_logger._context = {**self._context}
        new_logger._trace_ctx = ctx
        return new_logger
