from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, List
from pydantic import BaseModel, Field, ConfigDict

from app.schemas.common import APIResponse, PaginatedResponse


class InspectionStatusEnum(str, PyEnum):
    PENDING = "PENDING"
    PASSED = "PASSED"
    FAILED = "FAILED"
    PARTIAL = "PARTIAL"


class BatchStatusEnum(str, PyEnum):
    RECEIVED = "RECEIVED"
    INSPECTING = "INSPECTING"
    STORING = "STORING"
    ALLOCATED = "ALLOCATED"
    SHIPPED = "SHIPPED"
    CLOSED = "CLOSED"
    FROZEN = "FROZEN"


class AllocationStrategyEnum(str, PyEnum):
    FIFO = "FIFO"
    FEFO = "FEFO"
    LIFO = "LIFO"


class BatchGenerateRuleEnum(str, PyEnum):
    DATE_SEQUENCE = "DATE_SEQUENCE"
    SUPPLIER_DATE = "SUPPLIER_DATE"
    PRODUCT_DATE = "PRODUCT_DATE"
    WAREHOUSE_DATE = "WAREHOUSE_DATE"
    CUSTOM = "CUSTOM"


class BatchBase(BaseModel):
    sku_id: int = Field(description="SKU ID")
    warehouse_id: int = Field(description="仓库ID")
    supplier_id: Optional[int] = Field(default=None, description="供应商ID")
    quantity: int = Field(ge=0, description="数量")
    unit_cost: float = Field(ge=0, description="单位成本")
    production_date: Optional[datetime] = Field(default=None, description="生产日期")
    expiration_date: Optional[datetime] = Field(default=None, description="有效期")
    manufacture_date: Optional[datetime] = Field(default=None, description="制造日期")
    lot_number: Optional[str] = Field(default=None, max_length=100, description="批号")
    quality_grade: Optional[str] = Field(default=None, max_length=50, description="质量等级")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class BatchCreate(BatchBase):
    batch_no: Optional[str] = Field(default=None, max_length=100, description="批次号，不填则自动生成")
    inspection_status: InspectionStatusEnum = Field(
        default=InspectionStatusEnum.PENDING,
        description="质检状态"
    )


class BatchUpdate(BaseModel):
    supplier_id: Optional[int] = Field(default=None, description="供应商ID")
    unit_cost: Optional[float] = Field(default=None, ge=0, description="单位成本")
    production_date: Optional[datetime] = Field(default=None, description="生产日期")
    expiration_date: Optional[datetime] = Field(default=None, description="有效期")
    manufacture_date: Optional[datetime] = Field(default=None, description="制造日期")
    lot_number: Optional[str] = Field(default=None, max_length=100, description="批号")
    inspection_status: Optional[InspectionStatusEnum] = Field(default=None, description="质检状态")
    quality_grade: Optional[str] = Field(default=None, max_length=50, description="质量等级")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class Batch(BatchBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    batch_no: str
    remaining_quantity: int
    inspection_status: InspectionStatusEnum
    received_date: Optional[datetime]
    created_at: datetime
    updated_at: datetime

    sku_code: Optional[str] = Field(default=None, description="SKU编码")
    sku_name: Optional[str] = Field(default=None, description="SKU名称")
    warehouse_name: Optional[str] = Field(default=None, description="仓库名称")
    supplier_name: Optional[str] = Field(default=None, description="供应商名称")


class BatchDetail(Batch):
    model_config = ConfigDict(from_attributes=True)

    serial_number_count: int = Field(default=0, description="关联序列号数量")
    is_frozen: bool = Field(default=False, description="是否冻结")
    frozen_reason: Optional[str] = Field(default=None, description="冻结原因")
    frozen_at: Optional[datetime] = Field(default=None, description="冻结时间")
    shelf_life_days: Optional[int] = Field(default=None, description="剩余保质期天数")
    is_expiring: bool = Field(default=False, description="是否临期")
    days_to_expire: Optional[int] = Field(default=None, description="距到期天数")


class BatchGenerateRequest(BaseModel):
    sku_id: int = Field(description="SKU ID")
    warehouse_id: int = Field(description="仓库ID")
    supplier_id: Optional[int] = Field(default=None, description="供应商ID")
    rule: BatchGenerateRuleEnum = Field(
        default=BatchGenerateRuleEnum.DATE_SEQUENCE,
        description="生成规则"
    )
    prefix: Optional[str] = Field(default=None, max_length=50, description="自定义前缀")
    count: int = Field(default=1, ge=1, le=100, description="生成数量")


class BatchGenerateResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    batch_numbers: List[str] = Field(description="生成的批次号列表")
    rule: BatchGenerateRuleEnum
    count: int


class BatchReceiveItem(BaseModel):
    sku_id: int = Field(description="SKU ID")
    quantity: int = Field(ge=1, description="接收数量")
    unit_cost: float = Field(ge=0, description="单位成本")
    production_date: Optional[datetime] = Field(default=None, description="生产日期")
    expiration_date: Optional[datetime] = Field(default=None, description="有效期")
    lot_number: Optional[str] = Field(default=None, max_length=100, description="批号")


class BatchReceiveRequest(BaseModel):
    warehouse_id: int = Field(description="仓库ID")
    supplier_id: int = Field(description="供应商ID")
    purchase_order_id: Optional[int] = Field(default=None, description="采购订单ID")
    reference_no: Optional[str] = Field(default=None, max_length=100, description="参考编号")
    items: List[BatchReceiveItem] = Field(description="入库明细")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class BatchReceiveResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    batch_ids: List[int] = Field(description="创建的批次ID列表")
    batch_numbers: List[str] = Field(description="创建的批次号列表")
    total_quantity: int = Field(description="总接收数量")
    total_amount: float = Field(description="总金额")


class BatchSplitRequest(BaseModel):
    target_warehouse_id: Optional[int] = Field(default=None, description="目标仓库ID，不填则同仓库")
    split_quantity: int = Field(ge=1, description="拆分数量")
    new_batch_no: Optional[str] = Field(default=None, max_length=100, description="新批次号，不填则自动生成")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class BatchSplitResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    original_batch_id: int
    original_batch_no: str
    original_remaining_quantity: int
    new_batch_id: int
    new_batch_no: str
    new_quantity: int


class BatchMergeRequest(BaseModel):
    source_batch_ids: List[int] = Field(description="源批次ID列表")
    target_batch_id: int = Field(description="目标批次ID")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class BatchMergeResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    target_batch_id: int
    target_batch_no: str
    merged_quantity: int
    merged_batch_ids: List[int]


class BatchFreezeRequest(BaseModel):
    reason: str = Field(..., min_length=1, max_length=500, description="冻结原因")


class BatchInventoryItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    batch_id: int
    batch_no: str
    sku_id: int
    sku_code: str
    sku_name: Optional[str]
    quantity: int
    remaining_quantity: int
    unit_cost: float
    total_value: float
    production_date: Optional[datetime]
    expiration_date: Optional[datetime]
    inspection_status: InspectionStatusEnum
    is_frozen: bool
    warehouse_id: int
    warehouse_name: Optional[str]


class BatchTraceNode(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    batch_no: str
    action: str
    quantity: int
    location: str
    operated_at: datetime
    operated_by: Optional[int]
    reference_type: Optional[str]
    reference_id: Optional[int]
    remark: Optional[str]


class BatchTraceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    batch_id: int
    batch_no: str
    forward_trace: List[BatchTraceNode] = Field(description="正向追溯路径")
    backward_trace: List[BatchTraceNode] = Field(description="反向追溯路径")


class BatchFilterParams(BaseModel):
    sku_id: Optional[int] = Field(default=None, description="SKU ID")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID")
    supplier_id: Optional[int] = Field(default=None, description="供应商ID")
    inspection_status: Optional[InspectionStatusEnum] = Field(default=None, description="质检状态")
    batch_status: Optional[BatchStatusEnum] = Field(default=None, description="批次状态")
    is_frozen: Optional[bool] = Field(default=None, description="是否冻结")
    is_expiring: Optional[bool] = Field(default=None, description="是否临期")
    date_from: Optional[datetime] = Field(default=None, description="入库开始日期")
    date_to: Optional[datetime] = Field(default=None, description="入库结束日期")
    expiration_from: Optional[datetime] = Field(default=None, description="到期开始日期")
    expiration_to: Optional[datetime] = Field(default=None, description="到期结束日期")
    keyword: Optional[str] = Field(default=None, description="关键词搜索")
    min_remaining: Optional[int] = Field(default=None, description="最小剩余数量")
    max_remaining: Optional[int] = Field(default=None, description="最大剩余数量")


class BatchListResponse(APIResponse[PaginatedResponse[Batch]]):
    pass


class BatchDetailResponse(APIResponse[BatchDetail]):
    pass


class BatchGenerateResponseAPI(APIResponse[BatchGenerateResponse]):
    pass


class BatchReceiveResponseAPI(APIResponse[BatchReceiveResponse]):
    pass


class BatchInventoryResponse(APIResponse[List[BatchInventoryItem]]):
    pass


class BatchTraceResponseAPI(APIResponse[BatchTraceResponse]):
    pass
