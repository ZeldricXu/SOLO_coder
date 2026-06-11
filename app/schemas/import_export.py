from __future__ import annotations
from datetime import datetime
from typing import Any, Optional, Union, List, Dict
from pydantic import BaseModel, Field, ConfigDict

from app.schemas.common import APIResponse, PaginatedResponse
from app.models.import_export import (
    ImportJobType,
    ImportStatus,
    FileType,
    ImportErrorCode,
)
from app.schemas.product import Product, Sku


class ImportJobBase(BaseModel):
    job_type: ImportJobType = Field(description="导入任务类型")
    file_name: str = Field(max_length=255, description="文件名")
    file_type: FileType = Field(description="文件类型")


class ImportJobCreate(ImportJobBase):
    created_by: Optional[int] = Field(default=None, description="创建人ID")


class ImportJobUpdate(BaseModel):
    status: Optional[ImportStatus] = Field(default=None, description="任务状态")
    total_count: Optional[int] = Field(default=None, description="总记录数")
    success_count: Optional[int] = Field(default=None, description="成功数量")
    failed_count: Optional[int] = Field(default=None, description="失败数量")
    completed_at: Optional[datetime] = Field(default=None, description="完成时间")
    error_report_path: Optional[str] = Field(default=None, max_length=500, description="错误报告路径")


class ImportJob(ImportJobBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    total_count: int = Field(default=0, description="总记录数")
    success_count: int = Field(default=0, description="成功数量")
    failed_count: int = Field(default=0, description="失败数量")
    status: ImportStatus = Field(default=ImportStatus.PENDING, description="任务状态")
    created_by: Optional[int] = Field(default=None, description="创建人ID")
    created_at: datetime
    completed_at: Optional[datetime] = Field(default=None, description="完成时间")
    error_report_path: Optional[str] = Field(default=None, description="错误报告路径")


class ImportJobDetail(ImportJob):
    model_config = ConfigDict(from_attributes=True)

    error_count: int = Field(default=0, description="错误数量")


class ImportJobResponse(APIResponse[ImportJob]):
    pass


class ImportJobDetailResponse(APIResponse[ImportJobDetail]):
    pass


class ImportJobListResponse(APIResponse[PaginatedResponse[ImportJob]]):
    pass


class ImportErrorBase(BaseModel):
    job_id: int = Field(description="导入任务ID")
    row_number: int = Field(description="行号")
    field_name: Optional[str] = Field(default=None, max_length=100, description="字段名")
    error_code: ImportErrorCode = Field(description="错误码")
    error_message: str = Field(description="错误信息")
    raw_data: Optional[Union[Dict, List]] = Field(default=None, description="原始数据")


class ImportErrorCreate(ImportErrorBase):
    pass


class ImportError(ImportErrorBase):
    model_config = ConfigDict(from_attributes=True)

    id: int


class ImportErrorListItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    row_number: int
    field_name: Optional[str] = Field(default=None)
    error_code: ImportErrorCode
    error_message: str
    raw_data: Optional[Union[Dict, List]] = Field(default=None)


class ImportErrorListResponse(APIResponse[PaginatedResponse[ImportErrorListItem]]):
    pass


class ImportResult(BaseModel):
    job_id: int = Field(description="任务ID")
    total_count: int = Field(description="总记录数")
    success_count: int = Field(description="成功数量")
    failed_count: int = Field(description="失败数量")
    status: ImportStatus = Field(description="任务状态")
    created_products: List[Product] = Field(default_factory=list, description="创建的商品列表")
    created_skus: List[Sku] = Field(default_factory=list, description="创建的SKU列表")
    errors: List[ImportError] = Field(default_factory=list, description="错误列表")


class ImportResponse(APIResponse[ImportResult]):
    pass


class SkuExportFilter(BaseModel):
    category: Optional[str] = Field(default=None, max_length=100, description="品类过滤")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID过滤")
    stock_status: Optional[str] = Field(default=None, description="库存状态过滤: IN_STOCK/LOW_STOCK/OUT_OF_STOCK")
    product_id: Optional[int] = Field(default=None, description="商品ID过滤")
    sku_code: Optional[str] = Field(default=None, description="SKU编码模糊匹配")
    status: Optional[str] = Field(default=None, description="SKU状态过滤")


class ProductExportFilter(BaseModel):
    category: Optional[str] = Field(default=None, max_length=100, description="品类过滤")
    brand: Optional[str] = Field(default=None, max_length=100, description="品牌过滤")
    status: Optional[str] = Field(default=None, description="商品状态过滤")
    include_skus: bool = Field(default=True, description="是否包含SKU明细")


class ExportJobResponse(BaseModel):
    file_name: str = Field(description="文件名")
    file_type: FileType = Field(description="文件类型")
    total_count: int = Field(description="导出记录数")
