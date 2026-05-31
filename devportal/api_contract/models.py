from datetime import datetime, timezone
from sqlalchemy import Column, String, Integer, DateTime, JSON, Float, Boolean, ForeignKey, Text, Enum
from sqlalchemy.orm import relationship
import enum

from ..core.database import Base
from ..core.models import CoreEntity, generate_id


class SchemaType(str, enum.Enum):
    OPENAPI = "openapi"
    GRAPHQL = "graphql"


class MockServerStatus(str, enum.Enum):
    STOPPED = "stopped"
    RUNNING = "running"
    ERROR = "error"


class APISchema(CoreEntity):
    __tablename__ = "api_schemas"

    type = Column(String, nullable=False, default="api_schema")
    name = Column(String, nullable=False, index=True)
    version = Column(String, nullable=False, default="1.0.0")
    schema_type = Column(String, nullable=False, default=SchemaType.OPENAPI)
    content = Column(Text, nullable=False)
    content_hash = Column(String, nullable=False, index=True)
    url = Column(String, nullable=True)
    is_valid = Column(Boolean, default=False)
    validation_errors = Column(JSON, default=list)
    last_validated_at = Column(DateTime, nullable=True)
    namespace = Column(String, default="default", index=True)


class SchemaVersion(CoreEntity):
    __tablename__ = "schema_versions"

    type = Column(String, nullable=False, default="schema_version")
    schema_id = Column(String, ForeignKey("api_schemas.id"), nullable=False)
    version = Column(String, nullable=False)
    content = Column(Text, nullable=False)
    content_hash = Column(String, nullable=False)
    changelog = Column(Text, nullable=True)


class MockServer(CoreEntity):
    __tablename__ = "mock_servers"

    type = Column(String, nullable=False, default="mock_server")
    name = Column(String, nullable=False, index=True)
    schema_id = Column(String, ForeignKey("api_schemas.id"), nullable=False)
    status = Column(String, default=MockServerStatus.STOPPED)
    port = Column(Integer, nullable=True)
    host = Column(String, default="0.0.0.0")
    base_path = Column(String, default="/")
    latency_ms = Column(Integer, default=0)
    error_rate = Column(Float, default=0.0)
    custom_responses = Column(JSON, default=dict)
    started_at = Column(DateTime, nullable=True)
    stopped_at = Column(DateTime, nullable=True)
    pid = Column(Integer, nullable=True)
    log_config = Column(JSON, default=dict)


class ContractTest(CoreEntity):
    __tablename__ = "contract_tests"

    type = Column(String, nullable=False, default="contract_test")
    name = Column(String, nullable=False, index=True)
    schema_id = Column(String, ForeignKey("api_schemas.id"), nullable=False)
    test_type = Column(String, nullable=False, default="request_validation")
    test_config = Column(JSON, default=dict)
    test_cases = Column(JSON, default=list)
    last_run_at = Column(DateTime, nullable=True)
    last_run_status = Column(String, nullable=True)
    last_run_results = Column(JSON, default=dict)
    pass_count = Column(Integer, default=0)
    fail_count = Column(Integer, default=0)


class SchemaValidationResult(Base):
    __tablename__ = "schema_validation_results"

    id = Column(String, primary_key=True, default=lambda: generate_id("val"))
    schema_id = Column(String, ForeignKey("api_schemas.id"), nullable=False)
    validator = Column(String, nullable=False)
    is_valid = Column(Boolean, default=False)
    errors = Column(JSON, default=list)
    warnings = Column(JSON, default=list)
    metadata = Column(JSON, default=dict)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc), index=True)


class MockRequestLog(Base):
    __tablename__ = "mock_request_logs"

    id = Column(String, primary_key=True, default=lambda: generate_id("req"))
    mock_server_id = Column(String, ForeignKey("mock_servers.id"), nullable=False)
    method = Column(String, nullable=False)
    path = Column(String, nullable=False, index=True)
    query_params = Column(JSON, default=dict)
    request_headers = Column(JSON, default=dict)
    request_body = Column(JSON, nullable=True)
    response_status = Column(Integer, nullable=False)
    response_headers = Column(JSON, default=dict)
    response_body = Column(JSON, nullable=True)
    latency_ms = Column(Integer, default=0)
    matched_operation = Column(String, nullable=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc), index=True)
