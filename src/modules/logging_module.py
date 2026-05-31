import os
import sys
import gzip
import shutil
import logging
import structlog
from datetime import datetime, timedelta
from pathlib import Path
from logging.handlers import RotatingFileHandler, TimedRotatingFileHandler
from pythonjsonlogger import jsonlogger
from typing import Optional, Dict, Any


class CompressedRotatingFileHandler(RotatingFileHandler):
    def shouldRollover(self, record):
        if self.stream is None:
            self.stream = self._open()
        if self.maxBytes > 0:
            msg = "%s\n" % self.format(record)
            self.stream.seek(0, 2)
            if self.stream.tell() + len(msg) >= self.maxBytes:
                return True
        return False

    def doRollover(self):
        if self.stream:
            self.stream.close()
            self.stream = None
        if self.backupCount > 0:
            for i in range(self.backupCount - 1, 0, -1):
                sfn = self.rotation_filename("%s.%d.gz" % (self.baseFilename, i))
                dfn = self.rotation_filename("%s.%d.gz" % (self.baseFilename, i + 1))
                if os.path.exists(sfn):
                    if os.path.exists(dfn):
                        os.remove(dfn)
                    os.rename(sfn, dfn)
            dfn = self.rotation_filename(self.baseFilename + ".1.gz")
            if os.path.exists(dfn):
                os.remove(dfn)
            with open(self.baseFilename, 'rb') as f_in:
                with gzip.open(dfn, 'wb') as f_out:
                    shutil.copyfileobj(f_in, f_out)
        if not self.delay:
            self.stream = self._open()


class LogManager:
    _instance: Optional['LogManager'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(
        self,
        log_dir: str = "./logs",
        log_level: str = "INFO",
        max_bytes: int = 100 * 1024 * 1024,
        backup_count: int = 30,
        rotation_interval: str = "midnight",
        enable_console: bool = True,
        enable_json: bool = True,
        service_name: str = "cloud-native-engine",
    ):
        if self._initialized:
            return

        self.log_dir = Path(log_dir)
        self.log_dir.mkdir(parents=True, exist_ok=True)
        self.log_level = getattr(logging, log_level.upper(), logging.INFO)
        self.max_bytes = max_bytes
        self.backup_count = backup_count
        self.rotation_interval = rotation_interval
        self.enable_console = enable_console
        self.enable_json = enable_json
        self.service_name = service_name

        self._setup_logging()
        self._initialized = True

    def _setup_logging(self):
        root_logger = logging.getLogger()
        root_logger.setLevel(self.log_level)
        root_logger.handlers.clear()

        formatters = self._create_formatters()
        handlers = self._create_handlers(formatters)

        for handler in handlers:
            root_logger.addHandler(handler)

        self._setup_structlog()

    def _create_formatters(self) -> Dict[str, logging.Formatter]:
        standard_format = (
            f"%(asctime)s | %(levelname)-8s | {self.service_name} | "
            "%(name)s:%(lineno)d | %(message)s"
        )

        json_format = jsonlogger.JsonFormatter(
            "%(asctime)s %(levelname)s %(name)s %(lineno)d %(message)s "
            "%(exc_info)s %(trace_id)s %(user_id)s %(request_id)s"
        )

        return {
            "standard": logging.Formatter(standard_format),
            "json": json_format,
        }

    def _create_handlers(self, formatters: Dict[str, logging.Formatter]) -> list:
        handlers = []

        if self.enable_console:
            console_handler = logging.StreamHandler(sys.stdout)
            console_handler.setFormatter(formatters["standard"])
            handlers.append(console_handler)

        file_handler = CompressedRotatingFileHandler(
            filename=str(self.log_dir / "app.log"),
            maxBytes=self.max_bytes,
            backupCount=self.backup_count,
            encoding="utf-8",
        )
        file_handler.setFormatter(
            formatters["json"] if self.enable_json else formatters["standard"]
        )
        handlers.append(file_handler)

        error_handler = CompressedRotatingFileHandler(
            filename=str(self.log_dir / "error.log"),
            maxBytes=self.max_bytes,
            backupCount=self.backup_count,
            encoding="utf-8",
        )
        error_handler.setFormatter(
            formatters["json"] if self.enable_json else formatters["standard"]
        )
        error_handler.setLevel(logging.ERROR)
        handlers.append(error_handler)

        access_handler = TimedRotatingFileHandler(
            filename=str(self.log_dir / "access.log"),
            when=self.rotation_interval,
            backupCount=self.backup_count,
            encoding="utf-8",
        )
        access_handler.setFormatter(
            formatters["json"] if self.enable_json else formatters["standard"]
        )
        handlers.append(access_handler)

        return handlers

    def _setup_structlog(self):
        structlog.configure(
            processors=[
                structlog.contextvars.merge_contextvars,
                structlog.processors.add_log_level,
                structlog.processors.StackInfoRenderer(),
                structlog.processors.format_exc_info,
                structlog.processors.TimeStamper(fmt="iso"),
                structlog.processors.JSONRenderer(),
            ],
            wrapper_class=structlog.stdlib.BoundLogger,
            logger_factory=structlog.stdlib.LoggerFactory(),
            cache_logger_on_first_use=True,
        )

    def get_logger(self, name: str) -> structlog.stdlib.BoundLogger:
        return structlog.get_logger(name).bind(service=self.service_name)

    def archive_logs(self, destination_dir: Optional[str] = None) -> Path:
        destination = Path(destination_dir) if destination_dir else self.log_dir / "archive"
        destination.mkdir(parents=True, exist_ok=True)

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        archive_path = destination / f"logs_{timestamp}.tar.gz"

        import tarfile
        with tarfile.open(archive_path, "w:gz") as tar:
            for log_file in self.log_dir.glob("*.log*"):
                if log_file.is_file():
                    tar.add(log_file, arcname=log_file.name)

        return archive_path

    def cleanup_old_logs(self, retention_days: int = 30) -> int:
        cutoff_date = datetime.now() - timedelta(days=retention_days)
        removed_count = 0

        for log_file in self.log_dir.glob("*.log*"):
            if log_file.is_file():
                file_mtime = datetime.fromtimestamp(log_file.stat().st_mtime)
                if file_mtime < cutoff_date:
                    log_file.unlink()
                    removed_count += 1

        return removed_count

    def flush(self):
        for handler in logging.getLogger().handlers:
            handler.flush()


def get_logger(name: str) -> structlog.stdlib.BoundLogger:
    if not LogManager._instance:
        LogManager()
    return LogManager._instance.get_logger(name)
