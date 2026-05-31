import pytest
import logging
import json
from io import StringIO

from app.logging import get_logger, StructuredLogger, LogContext


class TestStructuredLogger:
    def test_get_logger_returns_structured_logger(self):
        logger = get_logger("test")
        assert isinstance(logger, StructuredLogger)

    def test_logger_outputs_json(self):
        logger = get_logger("test_json")
        stream = StringIO()
        handler = logging.StreamHandler(stream)
        logger.logger.addHandler(handler)
        logger.setLevel(logging.INFO)

        logger.info("Test message", extra_key="extra_value")

        output = stream.getvalue().strip()
        log_entry = json.loads(output)

        assert "message" in log_entry
        assert log_entry["message"] == "Test message"
        assert "extra_key" in log_entry
        assert log_entry["extra_key"] == "extra_value"
        assert "timestamp" in log_entry
        assert "level" in log_entry

    def test_logger_includes_context(self):
        LogContext.set_request_id("test-request-123")
        LogContext.set_user_id("test-user-456")

        logger = get_logger("test_context")
        stream = StringIO()
        handler = logging.StreamHandler(stream)
        logger.logger.addHandler(handler)
        logger.setLevel(logging.INFO)

        logger.info("Context test")

        output = stream.getvalue().strip()
        log_entry = json.loads(output)

        assert log_entry["request_id"] == "test-request-123"
        assert log_entry["user_id"] == "test-user-456"

        LogContext.clear()

    def test_log_context_works_as_context_manager(self):
        logger = get_logger("test_cm")
        stream = StringIO()
        handler = logging.StreamHandler(stream)
        logger.logger.addHandler(handler)
        logger.setLevel(logging.INFO)

        with LogContext(request_id="cm-test-123", user_id="cm-user-456"):
            logger.info("Inside context")

        output = stream.getvalue().strip()
        log_entry = json.loads(output)

        assert log_entry["request_id"] == "cm-test-123"
        assert log_entry["user_id"] == "cm-user-456"

    def test_logger_with_exception(self):
        logger = get_logger("test_exception")
        stream = StringIO()
        handler = logging.StreamHandler(stream)
        logger.logger.addHandler(handler)
        logger.setLevel(logging.ERROR)

        try:
            raise ValueError("Test error")
        except Exception as e:
            logger.error("Exception occurred", error=str(e), exc_info=True)

        output = stream.getvalue().strip()
        log_entry = json.loads(output)

        assert "exception" in log_entry
        assert "Test error" in log_entry["exception"]

    def test_different_log_levels(self):
        logger = get_logger("test_levels")
        stream = StringIO()
        handler = logging.StreamHandler(stream)
        logger.logger.addHandler(handler)
        logger.setLevel(logging.DEBUG)

        logger.debug("Debug message")
        logger.info("Info message")
        logger.warning("Warning message")
        logger.error("Error message")
        logger.critical("Critical message")

        lines = [line for line in stream.getvalue().strip().split("\n") if line]
        assert len(lines) == 5

        levels = [json.loads(line)["level"] for line in lines]
        assert levels == ["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]
