from datetime import datetime
from typing import Generic, TypeVar, Optional, List, Any, Dict
from pydantic import BaseModel, Field, ConfigDict


T = TypeVar("T")


class BaseResponse(BaseModel, Generic[T]):
    code: int = Field(default=200, description="响应状态码")
    message: str = Field(default="success", description="响应消息")
    data: Optional[T] = Field(default=None, description="响应数据")
    timestamp: datetime = Field(default_factory=lambda: datetime.now(datetime.timezone.utc))

    model_config = ConfigDict(from_attributes=True)


class PaginatedData(BaseModel, Generic[T]):
    items: List[T]
    total: int
    page: int
    page_size: int
    total_pages: int


class PaginatedResponse(BaseResponse[PaginatedData[T]]):
    pass


class ErrorDetail(BaseModel):
    field: Optional[str] = None
    message: str
    code: Optional[str] = None


class ErrorResponse(BaseModel):
    code: int
    message: str
    errors: Optional[List[ErrorDetail]] = None
    trace_id: Optional[str] = None
    timestamp: datetime = Field(default_factory=lambda: datetime.now(datetime.timezone.utc))


class PaginationParams(BaseModel):
    page: int = Field(default=1, ge=1, description="页码")
    page_size: int = Field(default=20, ge=1, le=100, description="每页数量")

    @property
    def offset(self) -> int:
        return (self.page - 1) * self.page_size

    @property
    def limit(self) -> int:
        return self.page_size


class BatchOperation(BaseModel):
    action: str
    id: Optional[str] = None
    params: Optional[Dict[str, Any]] = None


class BatchRequest(BaseModel):
    operations: List[BatchOperation]


class BatchResult(BaseModel):
    id: Optional[str] = None
    action: str
    success: bool
    error: Optional[str] = None
    data: Optional[Any] = None


class BatchResponse(BaseResponse[Dict[str, Any]]):
    batch_id: str
    results: List[BatchResult]
