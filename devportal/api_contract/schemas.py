from datetime import datetime
from typing import Any, Dict, List, Optional, Union
from pydantic import BaseModel, Field, ConfigDict

from .models import SchemaType, MockServerStatus


class APISchemaBase(BaseModel):
    name: str
    version: str = "1.0.0"
    schema_type: SchemaType = SchemaType.OPENAPI
    content: str
    url: Optional[str] = None
    namespace: str = "default"
    attributes: Dict[str, Any] = Field(default_factory=dict)


class APISchemaCreate(APISchemaBase):
    pass


class APISchemaUpdate(BaseModel):
    name: Optional[str] = None
    version: Optional[str] = None
    content: Optional[str] = None
    url: Optional[str] = None
    namespace: Optional[str] = None
    attributes: Optional[Dict[str, Any]] = None
    status: Optional[str] = None


class APISchemaResponse(APISchemaBase):
    id: str
    status: str
    is_valid: bool
    validation_errors: List[Any] = Field(default_factory=list)
    last_validated_at: Optional[datetime] = None
    content_hash: str
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class SchemaVersionBase(BaseModel):
    schema_id: str
    version: str
    content: str
    changelog: Optional[str] = None


class SchemaVersionCreate(SchemaVersionBase):
    pass


class SchemaVersionResponse(SchemaVersionBase):
    id: str
    status: str
    content_hash: str
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class ValidationErrorDetail(BaseModel):
    path: str
    message: str
    error_type: str
    line: Optional[int] = None
    column: Optional[int] = None


class ValidationResult(BaseModel):
    validator: str
    is_valid: bool
    errors: List[ValidationErrorDetail] = Field(default_factory=list)
    warnings: List[ValidationErrorDetail] = Field(default_factory=list)
    metadata: Dict[str, Any] = Field(default_factory=dict)
    validation_id: str
    timestamp: datetime


class FullValidationResponse(BaseModel):
    schema_id: str
    overall_valid: bool
    results: List[ValidationResult]
    timestamp: datetime


class MockServerBase(BaseModel):
    name: str
    schema_id: str
    host: str = "0.0.0.0"
    base_path: str = "/"
    latency_ms: int = 0
    error_rate: float = Field(default=0.0, ge=0.0, le=1.0)
    custom_responses: Dict[str, Any] = Field(default_factory=dict)
    log_config: Dict[str, Any] = Field(default_factory=dict)
    attributes: Dict[str, Any] = Field(default_factory=dict)


class MockServerCreate(MockServerBase):
    pass


class MockServerUpdate(BaseModel):
    name: Optional[str] = None
    host: Optional[str] = None
    base_path: Optional[str] = None
    latency_ms: Optional[int] = None
    error_rate: Optional[float] = None
    custom_responses: Optional[Dict[str, Any]] = None
    log_config: Optional[Dict[str, Any]] = None
    status: Optional[str] = None


class MockServerResponse(MockServerBase):
    id: str
    status: str
    port: Optional[int] = None
    started_at: Optional[datetime] = None
    stopped_at: Optional[datetime] = None
    pid: Optional[int] = None
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class MockServerAction(BaseModel):
    action: str
    params: Dict[str, Any] = Field(default_factory=dict)


class MockServerStatusResponse(BaseModel):
    server_id: str
    status: MockServerStatus
    port: Optional[int] = None
    base_url: Optional[str] = None
    uptime_seconds: Optional[float] = None
    request_count: int = 0


class ContractTestBase(BaseModel):
    name: str
    schema_id: str
    test_type: str = "request_validation"
    test_config: Dict[str, Any] = Field(default_factory=dict)
    test_cases: List[Dict[str, Any]] = Field(default_factory=list)
    attributes: Dict[str, Any] = Field(default_factory=dict)


class ContractTestCreate(ContractTestBase):
    pass


class ContractTestUpdate(BaseModel):
    name: Optional[str] = None
    test_type: Optional[str] = None
    test_config: Optional[Dict[str, Any]] = None
    test_cases: Optional[List[Dict[str, Any]]] = None
    attributes: Optional[Dict[str, Any]] = None
    status: Optional[str] = None


class ContractTestResponse(ContractTestBase):
    id: str
    status: str
    last_run_at: Optional[datetime] = None
    last_run_status: Optional[str] = None
    last_run_results: Dict[str, Any] = Field(default_factory=dict)
    pass_count: int = 0
    fail_count: int = 0
    created_at: datetime
    updated_at: datetime

    model_config = ConfigDict(from_attributes=True)


class TestCaseResult(BaseModel):
    test_id: str
    name: str
    passed: bool
    message: str = ""
    details: Dict[str, Any] = Field(default_factory=dict)


class TestRunResult(BaseModel):
    test_id: str
    overall_status: str
    results: List[TestCaseResult]
    pass_count: int
    fail_count: int
    duration_ms: int
    timestamp: datetime


class SchemaValidationRequest(BaseModel):
    content: str
    schema_type: SchemaType = SchemaType.OPENAPI
    validators: List[str] = Field(default_factory=lambda: ["syntax", "semantic", "compatibility"])


class RequestValidationRequest(BaseModel):
    schema_id: str
    path: str
    method: str
    headers: Dict[str, str] = Field(default_factory=dict)
    query_params: Dict[str, Any] = Field(default_factory=dict)
    body: Optional[Any] = None


class RequestValidationResponse(BaseModel):
    is_valid: bool
    errors: List[ValidationErrorDetail] = Field(default_factory=list)
    matched_operation: Optional[str] = None
    request_id: str
    timestamp: datetime


class MockRequestResponse(BaseModel):
    path: str
    method: str
    status: int
    latency_ms: int
    body: Any
    headers: Dict[str, Any] = Field(default_factory=dict)


class ImportSchemaRequest(BaseModel):
    url: str
    name: Optional[str] = None
    namespace: str = "default"


class DiffRequest(BaseModel):
    schema_id_a: str
    schema_id_b: str


class DiffResponse(BaseModel):
    changes: List[Dict[str, Any]]
    breaking_changes: List[Dict[str, Any]]
    non_breaking_changes: List[Dict[str, Any]]
    diff_id: str
    timestamp: datetime
