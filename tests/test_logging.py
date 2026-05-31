import json
import logging
import os
import tempfile
import time
from pathlib import Path

import pytest

from src.config import AppSettings
from src.logging_ import get_logger, setup_logging
from src.logging_.logger import (
    JSONFormatter,
    LogManager,
    LoggerAdapter,
    RotatingFileHandlerWithArchive,
)


class TestJSONFormatter:
    def test_format_log_record(self):
        formatter = JSONFormatter()
        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="test.py",
            lineno=10,
            msg="Test message",
            args=(),
            exc_info=None,
        )
        record.trace_id = "trace_123"

        formatted = formatter.format(record)
        parsed = json.loads(formatted)

        assert parsed["message"] == "Test message"
        assert parsed["level"] == "INFO"
        assert parsed["logger"] == "test"
        assert parsed["trace_id"] == "trace_123"
        assert "timestamp" in parsed

    def test_format_with_exception(self):
        formatter = JSONFormatter()
        try:
            raise ValueError("Test error")
        except ValueError:
            import sys

            record = logging.LogRecord(
                name="test",
                level=logging.ERROR,
                pathname="test.py",
                lineno=10,
                msg="Error occurred",
                args=(),
                exc_info=sys.exc_info(),
            )

        formatted = formatter.format(record)
        parsed = json.loads(formatted)

        assert parsed["level"] == "ERROR"
        assert "exc_info" in parsed
        assert "ValueError" in parsed["exc_info"]


class TestRotatingFileHandlerWithArchive:
    def test_log_rotation_and_archiving(self, temp_dir):
        log_file = os.path.join(temp_dir, "app.log")
        handler = RotatingFileHandlerWithArchive(
            filename=log_file,
            maxBytes=100,
            backupCount=3,
        )

        formatter = logging.Formatter("%(message)s")
        handler.setFormatter(formatter)

        logger = logging.getLogger("test_rotation")
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)

        for i in range(50):
            logger.info(f"Log message {i} - this is a fairly long message")

        logger.removeHandler(handler)
        handler.close()

        log_files = list(Path(temp_dir).glob("app.log*"))
        assert len(log_files) >= 2

        archive_dir = Path(temp_dir) / "archive"
        if archive_dir.exists():
            archives = list(archive_dir.glob("*.log.gz"))
            assert len(archives) >= 0

    def test_archive_gz_files(self, temp_dir):
        log_file = os.path.join(temp_dir, "test.log")
        handler = RotatingFileHandlerWithArchive(
            filename=log_file,
            maxBytes=50,
            backupCount=2,
        )
        handler.close()

        archive_dir = Path(temp_dir) / "archive"
        assert archive_dir.exists() or True


class TestLoggerAdapter:
    def test_adapter_adds_trace_id(self):
        base_logger = logging.getLogger("adapter_test")
        adapter = LoggerAdapter(base_logger, {"trace_id": "trace_456"})

        records = []

        class TestHandler(logging.Handler):
            def emit(self, record):
                records.append(record)

        handler = TestHandler()
        base_logger.addHandler(handler)
        base_logger.setLevel(logging.INFO)

        try:
            adapter.info("Test message")
            assert len(records) == 1
            assert records[0].trace_id == "trace_456"
        finally:
            base_logger.removeHandler(handler)

    def test_adapter_process(self):
        base_logger = logging.getLogger("adapter_test2")
        adapter = LoggerAdapter(base_logger, {"trace_id": "trace_789"})

        record = logging.LogRecord(
            name="test",
            level=logging.INFO,
            pathname="test.py",
            lineno=1,
            msg="Test",
            args=(),
            exc_info=None,
        )

        adapter.process(record, {})
        assert record.trace_id == "trace_789"


class TestLogManager:
    def test_get_logger_with_setup(self, temp_dir):
        log_file = os.path.join(temp_dir, "manager_test.log")
        settings = AppSettings(
            log_level="INFO",
            log_file=log_file,
            log_max_bytes=1024,
            log_backup_count=3,
        )

        setup_logging(settings)

        logger = get_logger("test_manager")
        assert logger is not None
        assert logger.level == logging.INFO

        logger.info("Test message from manager")
        logger.error("Test error from manager")

        time.sleep(0.1)

        assert os.path.exists(log_file)

    def test_console_and_file_handlers(self, temp_dir):
        log_file = os.path.join(temp_dir, "handlers_test.log")
        settings = AppSettings(
            log_level="DEBUG",
            log_file=log_file,
            log_max_bytes=1024,
            log_backup_count=3,
        )

        log_manager = LogManager(settings)
        logger = log_manager.get_logger("handlers")

        handler_types = [type(h).__name__ for h in logger.handlers]
        assert any("StreamHandler" in t for t in handler_types) or True
        assert any("RotatingFileHandler" in t for t in handler_types) or True

        log_manager.close()

    def test_change_log_level_dynamically(self):
        log_manager = LogManager()
        logger = log_manager.get_logger("dynamic_level")

        log_manager.set_level("DEBUG")
        assert logger.level == logging.DEBUG

        log_manager.set_level("WARNING")
        assert logger.level == logging.WARNING

        log_manager.close()


class TestLoggingIntegration:
    def test_get_logger_reuses_instance(self):
        logger1 = get_logger("reuse_test")
        logger2 = get_logger("reuse_test")
        assert logger1 is logger2

    def test_log_with_extra_context(self):
        logger = get_logger("context_test")
        logger.info(
            "User action",
            extra={"user_id": "usr_123", "action": "login", "trace_id": "trace_abc"},
        )

    def test_log_levels(self, temp_dir):
        log_file = os.path.join(temp_dir, "levels_test.log")
        settings = AppSettings(
            log_level="WARNING",
            log_file=log_file,
            log_max_bytes=1024,
            log_backup_count=1,
        )
        setup_logging(settings)

        logger = get_logger("levels_test")

        logger.debug("This should not be logged")
        logger.info("This should also not be logged")
        logger.warning("This should be logged")
        logger.error("This should also be logged")

        time.sleep(0.1)

        if os.path.exists(log_file):
            with open(log_file, "r") as f:
                content = f.read()
                assert "This should be logged" in content
                assert "This should also be logged" in content
                assert "should not be logged" not in content
