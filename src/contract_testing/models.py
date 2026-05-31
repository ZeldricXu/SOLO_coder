from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

from src.common.models import generate_id, utc_now


class SchemaType(str, Enum):
    OPENAPI = "openapi"
    GRAPHQL = "graphql"
    PROTOBUF = "protobuf"
    ASYNCAPI = "asyncapi"


class ValidationSeverity(str, Enum):
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


class ValidationError(BaseModel):
    error_id: str = Field(default_factory=lambda: generate_id("err"))
    severity: ValidationSeverity = ValidationSeverity.ERROR
    path: str
    message: str
    schema_path: Optional[str] = None
    value: Any = None


class ValidationResult(BaseModel):
    valid: bool
    errors: List[ValidationError] = Field(default_factory=list)
    warnings: List[ValidationError] = Field(default_factory=list)
    duration_ms: float = 0.0


class SchemaDefinition(BaseModel):
    schema_id: str = Field(default_factory=lambda: generate_id("sch"))
    name: str
    type: SchemaType
    version: str = "1.0.0"
    content: Dict[str, Any]
    description: str = ""
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class MockEndpoint(BaseModel):
    endpoint_id: str = Field(default_factory=lambda: generate_id("ep"))
    path: str
    method: str
    request_schema: Optional[Dict[str, Any]] = None
    response_schema: Optional[Dict[str, Any]] = None
    status_code: int = 200
    mock_response: Optional[Any] = None
    delay_ms: int = 0
    headers: Dict[str, str] = Field(default_factory=dict)


class MockServerConfig(BaseModel):
    server_id: str = Field(default_factory=lambda: generate_id("mock"))
    name: str
    schema_id: str
    endpoints: List[MockEndpoint] = Field(default_factory=list)
    base_url: str = "/"
    enabled: bool = True
    port: Optional[int] = None
    created_at: datetime = Field(default_factory=utc_now)


class ContractTestResult(BaseModel):
    test_id: str = Field(default_factory=lambda: generate_id("test"))
    schema_id: str
    endpoint: str
    method: str
    passed: bool
    request_validation: ValidationResult
    response_validation: ValidationResult
    duration_ms: float = 0.0
    timestamp: datetime = Field(default_factory=utc_now)


class APICallLog(BaseModel):
    log_id: str = Field(default_factory=lambda: generate_id("log"))
    method: str
    path: str
    request_headers: Dict[str, str] = Field(default_factory=dict)
    request_body: Optional[Any] = None
    response_status: int
    response_headers: Dict[str, str] = Field(default_factory=dict)
    response_body: Optional[Any] = None
    timestamp: datetime = Field(default_factory=utc_now)
