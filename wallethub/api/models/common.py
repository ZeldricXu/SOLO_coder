from datetime import datetime
from typing import Any, Dict, Generic, List, Optional, TypeVar
from pydantic import BaseModel as PydanticBaseModel, Field, field_validator


T = TypeVar("T")


class BaseModel(PydanticBaseModel):
    model_config = {
        "from_attributes": True,
        "populate_by_name": True,
    }


class ErrorResponse(BaseModel):
    code: int = Field(..., description="Error code")
    message: str = Field(..., description="Error message")
    details: Dict[str, Any] = Field(default_factory=dict, description="Additional error details")
    trace_id: Optional[str] = Field(None, description="Trace ID for debugging")


class SuccessResponse(BaseModel):
    code: int = 200
    message: str = "Success"
    data: Optional[Any] = None


class PaginatedResponse(BaseModel, Generic[T]):
    items: List[T]
    total: int
    page: int = 1
    page_size: int = 20
    total_pages: int = 0


class ResourceCreateRequest(BaseModel):
    type: str = Field(..., description="Resource type")
    config: Dict[str, Any] = Field(default_factory=dict, description="Resource configuration")
    labels: Dict[str, str] = Field(default_factory=dict, description="Resource labels")


class ResourceStatusResponse(BaseModel):
    id: str = Field(..., description="Resource ID")
    status: str = Field(..., description="Resource status")
    progress: float = Field(0.0, description="Progress percentage (0-1)")
    phase: Optional[str] = Field(None, description="Current phase")
    started_at: Optional[datetime] = Field(None, description="Start timestamp")
    completed_at: Optional[datetime] = Field(None, description="Completion timestamp")
    error_detail: Optional[Dict[str, Any]] = Field(None, description="Error details if failed")


class BatchOperation(BaseModel):
    action: str = Field(..., description="Operation action: start, stop, delete, etc.")
    id: str = Field(..., description="Resource ID")
    params: Dict[str, Any] = Field(default_factory=dict, description="Operation parameters")


class BatchResult(BaseModel):
    id: str
    success: bool
    error: Optional[str] = None
    result: Optional[Any] = None


class BatchOperationRequest(BaseModel):
    operations: List[BatchOperation]


class BatchOperationResponse(BaseModel):
    batch_id: str
    results: List[BatchResult]


class HealthResponse(BaseModel):
    status: str = "healthy"
    version: str
    timestamp: datetime
    uptime_seconds: float
