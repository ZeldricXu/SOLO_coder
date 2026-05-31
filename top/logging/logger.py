import asyncio
import gzip
import json
import logging
import logging.handlers
import os
import shutil
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import IntEnum
from pathlib import Path
from typing import Any, Dict, List, Optional


class LogLevel(IntEnum):
    DEBUG = logging.DEBUG
    INFO = logging.INFO
    WARNING = logging.WARNING
    ERROR = logging.ERROR
    CRITICAL = logging.CRITICAL


@dataclass
class LogContext:
    trace_id: str = ""
    user_id: str = ""
    module: str = ""
    action: str = ""
    extra: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        result = {
            "trace_id": self.trace_id,
            "user_id": self.user_id,
            "module": self.module,
            "action": self.action,
        }
        result.update(self.extra)
        return {k: v for k, v in result.items() if v}


@dataclass
class LogRotationConfig:
    max_bytes: int = 10 * 1024 * 1024
    backup_count: int = 10
    when: str = "midnight"
    interval: int = 1
    compression: bool = True
    retention_days: int = 30


class StructuredFormatter(logging.Formatter):
    def __init__(self, service_name: str = "task-orchestrator"):
        super().__init__()
        self.service_name = service_name

    def format(self, record: logging.LogRecord) -> str:
        log_entry = {
            "timestamp": self._format_timestamp(record.created),
            "level": record.levelname,
            "logger": record.name,
            "service": self.service_name,
            "message": record.getMessage(),
            "pid": record.process,
            "thread": record.threadName,
            "module": record.module,
            "function": record.funcName,
            "line": record.lineno,
        }

        if hasattr(record, "context") and isinstance(record.context, LogContext):
            log_entry["context"] = record.context.to_dict()

        if hasattr(record, "extra_data"):
            log_entry["extra"] = record.extra_data

        if record.exc_info:
            log_entry["exception"] = self.formatException(record.exc_info)

        return json.dumps(log_entry, ensure_ascii=False)

    def _format_timestamp(self, timestamp: float) -> str:
        return datetime.fromtimestamp(timestamp).astimezone().isoformat()


class ContextFilter(logging.Filter):
    def __init__(self, context: Optional[LogContext] = None):
        self._context = context
        self._local = threading.local()

    def set_context(self, context: LogContext) -> None:
        self._local.context = context

    def get_context(self) -> Optional[LogContext]:
        return getattr(self._local, "context", self._context)

    def filter(self, record: logging.LogRecord) -> bool:
        context = self.get_context()
        if context:
            record.context = context
        return True


class LogRotator:
    def __init__(self, config: LogRotationConfig):
        self.config = config

    def should_rotate(self, file_path: Path) -> bool:
        if not file_path.exists():
            return False
        return file_path.stat().st_size >= self.config.max_bytes

    def rotate(self, file_path: Path) -> List[Path]:
        rotated_files = []
        base_dir = file_path.parent
        base_name = file_path.name

        for i in range(self.config.backup_count - 1, 0, -1):
            src = base_dir / f"{base_name}.{i}"
            if self.config.compression:
                src = base_dir / f"{base_name}.{i}.gz"
            dst = base_dir / f"{base_name}.{i + 1}"
            if self.config.compression:
                dst = base_dir / f"{base_name}.{i + 1}.gz"
            if src.exists():
                src.rename(dst)
                rotated_files.append(dst)

        first_rotated = base_dir / f"{base_name}.1"
        if file_path.exists():
            file_path.rename(first_rotated)
            rotated_files.append(first_rotated)

        if self.config.compression:
            for f in rotated_files[:1]:
                if f.exists() and not f.name.endswith(".gz"):
                    self._compress_file(f)
                    gz_path = Path(str(f) + ".gz")
                    if gz_path.exists():
                        f.unlink()

        return rotated_files

    def _compress_file(self, file_path: Path) -> Path:
        gz_path = Path(str(file_path) + ".gz")
        with open(file_path, "rb") as f_in:
            with gzip.open(gz_path, "wb") as f_out:
                shutil.copyfileobj(f_in, f_out)
        return gz_path


class LogArchiver:
    def __init__(self, log_dir: Path, config: LogRotationConfig):
        self.log_dir = log_dir
        self.config = config

    def cleanup_old_logs(self) -> List[Path]:
        if self.config.retention_days <= 0:
            return []

        cutoff = datetime.now() - timedelta(days=self.config.retention_days)
        removed_files = []

        for log_file in self.log_dir.glob("*.log*"):
            if log_file.is_file():
                mtime = datetime.fromtimestamp(log_file.stat().st_mtime)
                if mtime < cutoff:
                    log_file.unlink()
                    removed_files.append(log_file)

        return removed_files

    def archive(self, source: Path, archive_dir: Path) -> Path:
        archive_dir.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        archive_name = f"{source.stem}_{timestamp}"
        if source.name.endswith(".gz"):
            archive_name += ".gz"
        else:
            archive_name += ".log"
        dest = archive_dir / archive_name
        shutil.copy2(source, dest)
        return dest


class StructuredLogger:
    def __init__(
        self,
        name: str,
        log_dir: Path,
        rotation_config: Optional[LogRotationConfig] = None,
        service_name: str = "task-orchestrator",
    ):
        self.name = name
        self.log_dir = log_dir
        self.rotation_config = rotation_config or LogRotationConfig()
        self.service_name = service_name

        self._logger = logging.getLogger(f"structured.{name}")
        self._logger.setLevel(logging.DEBUG)
        self._logger.propagate = False

        self._context_filter = ContextFilter()
        self._formatter = StructuredFormatter(service_name=service_name)
        self._rotator = LogRotator(self.rotation_config)
        self._archiver = LogArchiver(log_dir, self.rotation_config)

        self._setup_handlers()

    def _setup_handlers(self) -> None:
        self.log_dir.mkdir(parents=True, exist_ok=True)

        console_handler = logging.StreamHandler()
        console_handler.setLevel(logging.INFO)
        console_handler.setFormatter(self._formatter)
        console_handler.addFilter(self._context_filter)
        self._logger.addHandler(console_handler)

        file_handler = logging.handlers.RotatingFileHandler(
            filename=str(self.log_dir / f"{self.name}.log"),
            maxBytes=self.rotation_config.max_bytes,
            backupCount=self.rotation_config.backup_count,
            encoding="utf-8",
        )
        file_handler.setLevel(logging.DEBUG)
        file_handler.setFormatter(self._formatter)
        file_handler.addFilter(self._context_filter)
        self._logger.addHandler(file_handler)

        error_handler = logging.handlers.RotatingFileHandler(
            filename=str(self.log_dir / f"{self.name}_error.log"),
            maxBytes=self.rotation_config.max_bytes,
            backupCount=self.rotation_config.backup_count,
            encoding="utf-8",
        )
        error_handler.setLevel(logging.ERROR)
        error_handler.setFormatter(self._formatter)
        error_handler.addFilter(self._context_filter)
        self._logger.addHandler(error_handler)

    def set_context(self, context: LogContext) -> None:
        self._context_filter.set_context(context)

    def log(
        self,
        level: LogLevel,
        message: str,
        context: Optional[LogContext] = None,
        **extra: Any,
    ) -> None:
        if context:
            self.set_context(context)

        record_kwargs: Dict[str, Any] = {}
        if extra:
            record_kwargs["extra"] = {"extra_data": extra}

        self._logger.log(level.value, message, **record_kwargs)

    def debug(self, message: str, context: Optional[LogContext] = None, **extra: Any) -> None:
        self.log(LogLevel.DEBUG, message, context, **extra)

    def info(self, message: str, context: Optional[LogContext] = None, **extra: Any) -> None:
        self.log(LogLevel.INFO, message, context, **extra)

    def warning(self, message: str, context: Optional[LogContext] = None, **extra: Any) -> None:
        self.log(LogLevel.WARNING, message, context, **extra)

    def error(
        self,
        message: str,
        exc: Optional[Exception] = None,
        context: Optional[LogContext] = None,
        **extra: Any,
    ) -> None:
        if exc:
            self._logger.log(
                LogLevel.ERROR.value,
                message,
                exc_info=True,
                extra={"extra_data": extra} if extra else None,
            )
        else:
            self.log(LogLevel.ERROR, message, context, **extra)

    def critical(self, message: str, context: Optional[LogContext] = None, **extra: Any) -> None:
        self.log(LogLevel.CRITICAL, message, context, **extra)

    def cleanup_old_logs(self) -> List[Path]:
        return self._archiver.cleanup_old_logs()

    def get_logger(self) -> logging.Logger:
        return self._logger


_loggers: Dict[str, StructuredLogger] = {}
_log_config: Dict[str, Any] = {}


def configure_logging(
    log_dir: str = "./logs",
    rotation_config: Optional[LogRotationConfig] = None,
    service_name: str = "task-orchestrator",
    console_level: LogLevel = LogLevel.INFO,
) -> None:
    _log_config.update(
        {
            "log_dir": Path(log_dir),
            "rotation_config": rotation_config or LogRotationConfig(),
            "service_name": service_name,
            "console_level": console_level,
        }
    )


def get_logger(name: str) -> StructuredLogger:
    if name in _loggers:
        return _loggers[name]

    config = _log_config or {
        "log_dir": Path("./logs"),
        "rotation_config": LogRotationConfig(),
        "service_name": "task-orchestrator",
    }

    logger = StructuredLogger(
        name=name,
        log_dir=config["log_dir"],
        rotation_config=config["rotation_config"],
        service_name=config["service_name"],
    )
    _loggers[name] = logger
    return logger
