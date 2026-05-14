import pytest
from datetime import datetime
from logtrace.core.log_parser import LogParser


class TestLogParser:
    def test_parse_info_log(self, log_parser):
        raw_log = "2026-05-04 16:00:00 INFO Application started successfully"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'info'
        assert 'Application started successfully' in log_content
        assert timestamp is not None
        assert timestamp.hour == 16
        assert log_source == 'application'

    def test_parse_error_log(self, log_parser):
        raw_log = "[ERROR] 2026-05-04 16:00:02 Database connection failed: timeout after 30s"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'error'
        assert 'Database connection failed' in log_content

    def test_parse_warning_log(self, log_parser):
        raw_log = "2026-05-04 16:00:01 WARNING CPU usage exceeds 80%"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'warning'
        assert 'CPU usage exceeds 80%' in log_content

    def test_parse_warn_alias_to_warning(self, log_parser):
        raw_log = "[WARN] Low memory warning"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'warning'

    def test_parse_critical_alias_to_fatal(self, log_parser):
        raw_log = "[CRITICAL] System critical failure"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'fatal'

    def test_parse_fatal_log(self, log_parser):
        raw_log = "2026-05-04 16:00:05 FATAL System crash detected"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'fatal'

    def test_parse_debug_log(self, log_parser):
        raw_log = "DEBUG: Variable value = 42"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'debug'

    def test_parse_default_level_when_no_level(self, log_parser):
        raw_log = "This is a plain log without level"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'info'

    def test_parse_custom_default_level(self, log_parser):
        raw_log = "This is a plain log without level"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log, default_level='debug')

        assert log_level == 'debug'

    def test_parse_timestamp_iso_format(self, log_parser):
        raw_log = "2026-05-04T16:00:00Z INFO Test log"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert timestamp is not None
        assert timestamp.year == 2026
        assert timestamp.month == 5
        assert timestamp.day == 4

    def test_parse_timestamp_with_milliseconds(self, log_parser):
        raw_log = "2026-05-04 16:00:00.123 INFO Test log"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert timestamp is not None
        assert timestamp.hour == 16
        assert timestamp.minute == 0
        assert timestamp.second == 0

    def test_parse_timestamp_slash_format(self, log_parser):
        raw_log = "05/04/2026 16:00:00 INFO Test log"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert timestamp is not None

    def test_parse_no_timestamp(self, log_parser):
        raw_log = "[INFO] Log without timestamp"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert timestamp is None

    def test_parse_case_insensitive_level(self, log_parser):
        raw_log = "info lowercase level test"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'info'

    def test_parse_log_with_multiple_fields(self, log_parser):
        raw_log = "2026-05-04 16:00:00 [INFO] [node_01] [application] User login successful"
        log_level, log_content, timestamp, log_source = log_parser.parse(raw_log)

        assert log_level == 'info'
        assert 'User login successful' in log_content
        assert timestamp is not None
