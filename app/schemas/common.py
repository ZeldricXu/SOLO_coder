from datetime import datetime
from typing import Any, Generic, Optional, TypeVar
from pydantic import BaseModel, Field, ConfigDict

T = TypeVar("T")


class APIResponse(BaseModel, Generic[T]):
    model_config = ConfigDict(from_attributes=True)

    code: int = Field(default=200, description="响应状态码")
    message: str = Field(default="success", description="响应消息")
    data: Optional[T] = Field(default=None, description="响应数据")
    timestamp: datetime = Field(default_factory=datetime.utcnow, description="响应时间戳")


class PaginatedParams(BaseModel):
    page: int = Field(default=1, ge=1, description="页码")
    page_size: int = Field(default=20, ge=1, le=100, description="每页条数")
    sort_by: Optional[str] = Field(default=None, description="排序字段")
    sort_order: str = Field(default="desc", description="排序方向: asc/desc")


class PaginatedResponse(BaseModel, Generic[T]):
    model_config = ConfigDict(from_attributes=True)

    items: list[T] = Field(description="数据列表")
    page: int = Field(description="当前页码")
    page_size: int = Field(description="每页条数")
    total: int = Field(description="总条数")
    total_pages: int = Field(description="总页数")
    has_next: bool = Field(description="是否有下一页")
    has_prev: bool = Field(description="是否有上一页")


class BulkOperationRequest(BaseModel):
    ids: list[int] = Field(description="操作的ID列表")
    action: str = Field(description="操作类型")
    params: Optional[dict[str, Any]] = Field(default=None, description="操作参数")


class BulkOperationResponse(BaseModel):
    success_count: int = Field(description="成功数量")
    failed_count: int = Field(description="失败数量")
    failed_ids: list[int] = Field(default_factory=list, description="失败的ID列表")
    errors: list[dict[str, Any]] = Field(default_factory=list, description="错误详情")


class IdResponse(BaseModel):
    id: int = Field(description="记录ID")


class SuccessResponse(BaseModel):
    success: bool = Field(default=True, description="是否成功")
    message: str = Field(default="操作成功", description="消息")
