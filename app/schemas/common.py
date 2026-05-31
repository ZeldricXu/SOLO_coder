from datetime import datetime, timezone
from typing import Generic, TypeVar, Optional, List, Any, Dict
from pydantic import BaseModel, Field, ConfigDict

T = TypeVar("T")


class BaseResponse(BaseModel, Generic[T]):
    code: int = Field(200, description="响应状态码")
    message: str = Field("success", description="响应消息")
    data: Optional[T] = Field(None, description="响应数据")
    timestamp: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc),
        description="响应时间戳",
    )
    request_id: Optional[str] = Field(None, description="请求ID")


class PaginationParams(BaseModel):
    page: int = Field(1, ge=1, description="页码")
    page_size: int = Field(20, ge=1, le=100, description="每页数量")


class PaginatedResponse(BaseModel, Generic[T]):
    items: List[T] = Field(..., description="数据列表")
    total: int = Field(..., description="总数量")
    page: int = Field(..., description="当前页码")
    page_size: int = Field(..., description="每页数量")
    total_pages: int = Field(..., description="总页数")


class ErrorResponse(BaseModel):
    code: int = Field(..., description="错误码")
    message: str = Field(..., description="错误消息")
    details: Optional[Dict[str, Any]] = Field(None, description="错误详情")
    request_id: Optional[str] = Field(None, description="请求ID")
    timestamp: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc),
        description="响应时间戳",
    )
