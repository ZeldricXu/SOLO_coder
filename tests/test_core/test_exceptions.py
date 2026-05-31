import pytest
from streamsql.core.exceptions import (
    StreamSQLException,
    ValidationError,
    ConfigurationError,
    TimeoutError,
    ResourceAcquisitionError,
    SchemaExtractionError,
    CDCCaptureError,
    SQLParseError,
    LineageExtractionError,
    QualityCheckError,
)


def test_base_exception():
    exc = StreamSQLException("test message")
    assert str(exc) == "test message"


def test_validation_error():
    exc = ValidationError("invalid data", field="name")
    assert isinstance(exc, StreamSQLException)
    assert exc.details["field"] == "name"


def test_configuration_error():
    exc = ConfigurationError("test_key", "missing config")
    assert isinstance(exc, StreamSQLException)


def test_timeout_error():
    exc = TimeoutError("test_op", 30)
    assert isinstance(exc, StreamSQLException)
    assert "test_op" in str(exc)


def test_resource_acquisition_error():
    exc = ResourceAcquisitionError("database", "connection failed")
    assert isinstance(exc, StreamSQLException)


def test_schema_extraction_error():
    exc = SchemaExtractionError("mysql", "table not found")
    assert isinstance(exc, StreamSQLException)


def test_cdc_capture_error():
    exc = CDCCaptureError("mysql", "parse", "invalid format")
    assert isinstance(exc, StreamSQLException)


def test_sql_parse_error():
    exc = SQLParseError("SELECT * FROM", position=10, message="syntax error")
    assert isinstance(exc, StreamSQLException)


def test_lineage_extraction_error():
    exc = LineageExtractionError("SELECT * FROM users", "unsupported syntax")
    assert isinstance(exc, StreamSQLException)


def test_quality_check_error():
    exc = QualityCheckError("rule_001", "users", "null value found")
    assert isinstance(exc, StreamSQLException)
