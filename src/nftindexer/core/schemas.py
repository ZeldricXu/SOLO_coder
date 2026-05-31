from datetime import datetime
from typing import Any, Dict, Generic, List, Optional, TypeVar
from pydantic import BaseModel, Field, ConfigDict

T = TypeVar("T")


class BaseResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True, populate_by_name=True)

    code: int = Field(default=200)
    message: str = Field(default="success")
    request_id: Optional[str] = Field(default=None)


class ResourceCreateRequest(BaseModel):
    type: str
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, str] = Field(default_factory=dict)


class ResourceResponse(BaseResponse):
    data: Dict[str, Any]


class ResourceStatusResponse(BaseResponse):
    data: Dict[str, Any]


class BatchOperation(BaseModel):
    action: str
    id: str
    params: Dict[str, Any] = Field(default_factory=dict)


class BatchOperationRequest(BaseModel):
    operations: List[BatchOperation]


class BatchResult(BaseModel):
    id: str
    success: bool
    data: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class BatchOperationResponse(BaseResponse):
    data: Dict[str, Any]


class PaginationParams(BaseModel):
    page: int = Field(default=1, ge=1)
    page_size: int = Field(default=20, ge=1, le=100)


class PaginatedResponse(BaseResponse, Generic[T]):
    data: List[T]
    total: int
    page: int
    page_size: int
    total_pages: int
