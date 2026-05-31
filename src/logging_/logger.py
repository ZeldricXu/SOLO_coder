import asyncio
import json
import logging
import logging.handlers
import os
import tarfile
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Dict, Optional

from src.config import get_settings
from src.utils.helpers import generate_trace_id


class JSONFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        log_entry = {
            "timestamp": datetime.fromtimestamp(record.created).isoformat(),
            "level": record.levelname,
            "module": record.module,
            "function": record.funcName,
            "line": record.lineno,
            "message": record.getMessage(),
            "trace_id": getattr(record, "trace_id", None),
        }

        if record.exc_info:
            log_entry["exception"] = self.formatException(record.exc_info)

        if hasattr(record, "extra") and isinstance(record.extra, dict):
            log_entry.update(record.extra)

        return json.dumps(log_entry, ensure_ascii=False)


class RotatingFileHandlerWithArchive(logging.handlers.RotatingFileHandler):
    def __init__(
        self,
        filename: str,
        mode: str = "a",
        maxBytes: int = 0,
        backupCount: int = 0,
        encoding: Optional[str] = None,
        delay: bool = False,
        archive_dir: Optional[str] = None,
    ):
        super().__init__(filename, mode, maxBytes, backupCount, encoding, delay)
        self.archive_dir = Path(archive_dir) if archive_dir else Path(filename).parent / "archive"
        self.archive_dir.mkdir(parents=True, exist_ok=True)

    def doRollover(self) -> None:
        super().doRollover()
        self._archive_old_logs()

    def _archive_old_logs(self) -> None:
        try:
            log_dir = Path(self.baseFilename).parent
            current_date = datetime.now().date()

            for log_file in log_dir.glob("*.log.*"):
                if not log_file.is_file():
                    continue

                file_mtime = datetime.fromtimestamp(log_file.stat().st_mtime).date()
                if (current_date - file_mtime) > timedelta(days=1):
                    self._archive_file(log_file)

            for archive_file in self.archive_dir.glob("*.tar.gz"):
                if archive_file.is_file():
                    file_mtime = datetime.fromtimestamp(archive_file.stat().st_mtime)
                    if (datetime.now() - file_mtime) > timedelta(days=30):
                        archive_file.unlink()

        except Exception as e:
            print(f"Log archiving failed: {e}")

    def _archive_file(self, log_file: Path) -> None:
        try:
            date_str = datetime.fromtimestamp(log_file.stat().st_mtime).strftime("%Y%m%d")
            archive_name = f"{log_file.stem}_{date_str}.tar.gz"
            archive_path = self.archive_dir / archive_name

            if not archive_path.exists():
                with tarfile.open(archive_path, "w:gz") as tar:
                    tar.add(log_file, arcname=log_file.name)

            log_file.unlink()

        except Exception as e:
            print(f"Failed to archive {log_file}: {e}")


class LogManager:
    _instance: Optional["LogManager"] = None
    _loggers: Dict[str, logging.Logger] = {}

    def __new__(cls) -> "LogManager":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self) -> None:
        if not hasattr(self, "initialized"):
            self.settings = get_settings()
            self._setup_base_directory()
            self.initialized = True

    def _setup_base_directory(self) -> None:
        log_dir = Path(self.settings.LOG_DIR)
        log_dir.mkdir(parents=True, exist_ok=True)

        archive_dir = Path(self.settings.LOG_ARCHIVE_DIR)
        archive_dir.mkdir(parents=True, exist_ok=True)

    def setup_logger(
        self,
        name: str,
        level: Optional[str] = None,
        log_file: Optional[str] = None,
        enable_console: bool = True,
        enable_file: bool = True,
        json_format: bool = True,
    ) -> logging.Logger:
        if name in self._loggers:
            return self._loggers[name]

        logger = logging.getLogger(name)
        logger.setLevel(getattr(logging, level or self.settings.LOG_LEVEL, logging.INFO))
        logger.propagate = False

        formatter = (
            JSONFormatter()
            if json_format
            else logging.Formatter(
                "%(asctime)s - %(name)s - %(levelname)s - %(trace_id)s - %(message)s",
                defaults={"trace_id": "no-trace"},
            )
        )

        if enable_console:
            console_handler = logging.StreamHandler()
            console_handler.setFormatter(formatter)
            logger.addHandler(console_handler)

        if enable_file:
            log_path = log_file or f"{self.settings.LOG_DIR}/{name}.log"
            file_handler = RotatingFileHandlerWithArchive(
                filename=log_path,
                maxBytes=self.settings.LOG_MAX_BYTES,
                backupCount=self.settings.LOG_BACKUP_COUNT,
                archive_dir=self.settings.LOG_ARCHIVE_DIR,
                encoding="utf-8",
            )
            file_handler.setFormatter(formatter)
            logger.addHandler(file_handler)

        self._loggers[name] = logger
        return logger

    def get_logger(self, name: str) -> logging.Logger:
        if name not in self._loggers:
            return self.setup_logger(name)
        return self._loggers[name]

    def get_all_loggers(self) -> Dict[str, logging.Logger]:
        return self._loggers.copy()

    async def cleanup_old_logs(self, days: int = 30) -> int:
        removed_count = 0
        try:
            log_dir = Path(self.settings.LOG_DIR)
            archive_dir = Path(self.settings.LOG_ARCHIVE_DIR)
            cutoff = time.time() - (days * 86400)

            for directory in [log_dir, archive_dir]:
                if directory.exists():
                    for file_path in directory.rglob("*"):
                        if file_path.is_file() and file_path.stat().st_mtime < cutoff:
                            file_path.unlink()
                            removed_count += 1

        except Exception as e:
            print(f"Log cleanup failed: {e}")

        return removed_count


def setup_logger(
    name: str,
    level: Optional[str] = None,
    log_file: Optional[str] = None,
    enable_console: bool = True,
    enable_file: bool = True,
) -> logging.Logger:
    manager = LogManager()
    return manager.setup_logger(name, level, log_file, enable_console, enable_file)


def get_logger(name: str) -> logging.Logger:
    manager = LogManager()
    return manager.get_logger(name)


class LoggerAdapter(logging.LoggerAdapter):
    def __init__(self, logger: logging.Logger, trace_id: Optional[str] = None):
        super().__init__(logger, {})
        self.trace_id = trace_id or generate_trace_id()

    def process(self, msg: str, kwargs: Any) -> tuple:
        kwargs["extra"] = kwargs.get("extra") or {}
        kwargs["extra"]["trace_id"] = self.trace_id
        return msg, kwargs

    def with_trace(self, trace_id: str) -> "LoggerAdapter":
        return LoggerAdapter(self.logger, trace_id)
