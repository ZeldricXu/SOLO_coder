from datetime import datetime
from typing import Optional, Any
from pydantic import BaseModel, Field, ConfigDict

from app.models.inventory_document import DocumentType, DocumentStatus


class DocumentItemBase(BaseModel):
    sku_id: int = Field(..., description="SKU ID")
    batch_id: Optional[int] = Field(None, description="批次ID")
    serial_numbers: Optional[list[str]] = Field(None, description="序列号列表")
    quantity: int = Field(..., gt=0, description="数量")
    actual_quantity: Optional[int] = Field(None, description="实际数量")
    unit_cost: Optional[float] = Field(None, ge=0, description="单位成本")
    remark: Optional[str] = Field(None, max_length=500, description="备注")


class DocumentItemCreate(DocumentItemBase):
    pass


class DocumentItem(DocumentItemBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    document_id: int
    total_cost: float = Field(description="总成本")
    created_at: datetime
    sku_code: Optional[str] = Field(None, description="SKU编码")
    sku_name: Optional[str] = Field(None, description="SKU名称")
    batch_no: Optional[str] = Field(None, description="批次号")


class DocumentBase(BaseModel):
    document_type: DocumentType = Field(..., description="单据类型")
    warehouse_id: int = Field(..., description="仓库ID")
    target_warehouse_id: Optional[int] = Field(None, description="目标仓库ID(调拨单使用)")
    remark: Optional[str] = Field(None, max_length=500, description="备注")
    reference_type: Optional[str] = Field(None, max_length=50, description="参考类型")
    reference_id: Optional[int] = Field(None, description="参考ID")


class DocumentCreate(DocumentBase):
    items: list[DocumentItemCreate] = Field(default_factory=list, description="单据明细")


class DocumentUpdate(BaseModel):
    warehouse_id: Optional[int] = Field(None, description="仓库ID")
    target_warehouse_id: Optional[int] = Field(None, description="目标仓库ID")
    remark: Optional[str] = Field(None, max_length=500, description="备注")
    reference_type: Optional[str] = Field(None, max_length=50, description="参考类型")
    reference_id: Optional[int] = Field(None, description="参考ID")


class Document(DocumentBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    document_no: str = Field(description="单据编号")
    status: DocumentStatus = Field(description="单据状态")
    total_quantity: int = Field(description="总数量")
    total_amount: float = Field(description="总金额")
    created_by: Optional[int] = Field(None, description="创建人")
    created_at: datetime
    updated_at: datetime
    confirmed_by: Optional[int] = Field(None, description="确认人")
    confirmed_at: Optional[datetime] = Field(None, description="确认时间")
    completed_by: Optional[int] = Field(None, description="完成人")
    completed_at: Optional[datetime] = Field(None, description="完成时间")


class DocumentDetail(Document):
    items: list[DocumentItem] = Field(default_factory=list, description="单据明细")
    warehouse_name: Optional[str] = Field(None, description="仓库名称")
    target_warehouse_name: Optional[str] = Field(None, description="目标仓库名称")
    created_by_name: Optional[str] = Field(None, description="创建人名称")


class DocumentConfirmRequest(BaseModel):
    remark: Optional[str] = Field(None, max_length=500, description="确认备注")


class DocumentCompleteRequest(BaseModel):
    items: Optional[list[dict[str, Any]]] = Field(None, description="实际收货/发货明细(可选)")
    remark: Optional[str] = Field(None, max_length=500, description="完成备注")


class ScanItemRequest(BaseModel):
    document_id: Optional[int] = Field(None, description="单据ID(可选,扫码直接创建)")
    barcode: str = Field(..., description="条码")
    quantity: int = Field(default=1, gt=0, description="数量")
    batch_no: Optional[str] = Field(None, description="批次号")
    serial_number: Optional[str] = Field(None, description="序列号")
    warehouse_id: Optional[int] = Field(None, description="仓库ID")
    document_type: Optional[DocumentType] = Field(None, description="单据类型")


class ScanItemResponse(BaseModel):
    success: bool = Field(description="是否成功")
    sku_id: Optional[int] = Field(None, description="SKU ID")
    sku_code: Optional[str] = Field(None, description="SKU编码")
    sku_name: Optional[str] = Field(None, description="SKU名称")
    quantity: int = Field(description="数量")
    message: Optional[str] = Field(None, description="消息")


class BatchScanRequest(BaseModel):
    items: list[ScanItemRequest] = Field(description="扫码列表")


class BatchScanResponse(BaseModel):
    success_count: int = Field(description="成功数量")
    failed_count: int = Field(description="失败数量")
    items: list[ScanItemResponse] = Field(description="结果列表")


class DocumentListFilter(BaseModel):
    document_type: Optional[DocumentType] = Field(None, description="单据类型")
    status: Optional[DocumentStatus] = Field(None, description="单据状态")
    warehouse_id: Optional[int] = Field(None, description="仓库ID")
    target_warehouse_id: Optional[int] = Field(None, description="目标仓库ID")
    created_by: Optional[int] = Field(None, description="创建人")
    start_date: Optional[datetime] = Field(None, description="开始日期")
    end_date: Optional[datetime] = Field(None, description="结束日期")
    document_no: Optional[str] = Field(None, description="单据编号(模糊查询)")
    reference_type: Optional[str] = Field(None, description="参考类型")
    reference_id: Optional[int] = Field(None, description="参考ID")


class DocumentTraceItem(BaseModel):
    transaction_id: int = Field(description="事务ID")
    transaction_type: str = Field(description="事务类型")
    sku_id: int = Field(description="SKU ID")
    sku_code: Optional[str] = Field(None, description="SKU编码")
    quantity: int = Field(description="数量")
    batch_id: Optional[int] = Field(None, description="批次ID")
    batch_no: Optional[str] = Field(None, description="批次号")
    serial_number: Optional[str] = Field(None, description="序列号")
    created_at: datetime = Field(description="创建时间")
    created_by: Optional[int] = Field(None, description="操作人")


class DocumentTraceResponse(BaseModel):
    document_id: int = Field(description="单据ID")
    document_no: str = Field(description="单据编号")
    items: list[DocumentTraceItem] = Field(description="追溯明细")


class DocumentPrintTemplate(BaseModel):
    template_type: str = Field(description="模板类型")
    content: dict[str, Any] = Field(description="打印内容")


class DocumentStatisticsResponse(BaseModel):
    total_count: int = Field(description="单据总数")
    draft_count: int = Field(description="草稿数量")
    confirmed_count: int = Field(description="已确认数量")
    processing_count: int = Field(description="处理中数量")
    completed_count: int = Field(description="已完成数量")
    cancelled_count: int = Field(description="已取消数量")
    total_amount: float = Field(description="总金额")
    by_type: dict[str, int] = Field(description="按类型统计")
