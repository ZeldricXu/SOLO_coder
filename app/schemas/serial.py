from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field, ConfigDict

from app.schemas.common import APIResponse, PaginatedResponse


class SerialNumberStatusEnum(str, PyEnum):
    INSTOCK = "INSTOCK"
    ALLOCATED = "ALLOCATED"
    SHIPPED = "SHIPPED"
    RETURNED = "RETURNED"
    SCRAPPED = "SCRAPPED"


class TraceActionEnum(str, PyEnum):
    RECEIVE = "RECEIVE"
    PUTAWAY = "PUTAWAY"
    TRANSFER = "TRANSFER"
    ALLOCATE = "ALLOCATE"
    SHIP = "SHIP"
    RETURN = "RETURN"
    SCRAP = "SCRAP"


class TraceDirectionEnum(str, PyEnum):
    FORWARD = "FORWARD"
    BACKWARD = "BACKWARD"
    FULL = "FULL"


class SerialNumberBase(BaseModel):
    serial_code: str = Field(..., min_length=1, max_length=200, description="序列号编码")
    sku_id: int = Field(description="SKU ID")
    batch_id: Optional[int] = Field(default=None, description="批次ID")
    warehouse_id: int = Field(description="仓库ID")
    production_date: Optional[datetime] = Field(default=None, description="生产日期")
    expiration_date: Optional[datetime] = Field(default=None, description="有效期")
    current_location: Optional[str] = Field(default=None, max_length=200, description="当前位置")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class SerialNumberCreate(SerialNumberBase):
    status: SerialNumberStatusEnum = Field(
        default=SerialNumberStatusEnum.INSTOCK,
        description="序列号状态"
    )


class SerialNumberUpdate(BaseModel):
    batch_id: Optional[int] = Field(default=None, description="批次ID")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID")
    production_date: Optional[datetime] = Field(default=None, description="生产日期")
    expiration_date: Optional[datetime] = Field(default=None, description="有效期")
    current_location: Optional[str] = Field(default=None, max_length=200, description="当前位置")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class SerialNumber(SerialNumberBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    status: SerialNumberStatusEnum
    received_date: Optional[datetime]
    shipped_date: Optional[datetime]
    created_at: datetime
    updated_at: datetime

    sku_code: Optional[str] = Field(default=None, description="SKU编码")
    sku_name: Optional[str] = Field(default=None, description="SKU名称")
    batch_no: Optional[str] = Field(default=None, description="批次号")
    warehouse_name: Optional[str] = Field(default=None, description="仓库名称")


class SerialNumberDetail(SerialNumber):
    model_config = ConfigDict(from_attributes=True)

    trace_count: int = Field(default=0, description="追溯记录数量")
    shelf_life_days: Optional[int] = Field(default=None, description="剩余保质期天数")
    is_expiring: bool = Field(default=False, description="是否临期")
    days_to_expire: Optional[int] = Field(default=None, description="距到期天数")


class SerialNumberImportItem(BaseModel):
    serial_code: str = Field(..., min_length=1, max_length=200, description="序列号编码")
    sku_id: int = Field(description="SKU ID")
    batch_id: Optional[int] = Field(default=None, description="批次ID")
    production_date: Optional[datetime] = Field(default=None, description="生产日期")
    expiration_date: Optional[datetime] = Field(default=None, description="有效期")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class SerialNumberImportRequest(BaseModel):
    warehouse_id: int = Field(description="仓库ID")
    items: List[SerialNumberImportItem] = Field(description="序列号列表")
    skip_existing: bool = Field(default=False, description="是否跳过已存在的序列号")
    update_existing: bool = Field(default=False, description="是否更新已存在的序列号")


class SerialNumberImportResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    success_count: int = Field(description="成功导入数量")
    failed_count: int = Field(description="失败数量")
    skipped_count: int = Field(description="跳过数量")
    updated_count: int = Field(description="更新数量")
    failed_items: List[Dict[str, Any]] = Field(default_factory=list, description="失败详情")


class SerialNumberTrace(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    serial_number_id: int
    serial_code: str
    action: TraceActionEnum
    from_location: Optional[str]
    to_location: Optional[str]
    reference_type: Optional[str]
    reference_id: Optional[int]
    operated_by: Optional[int]
    operated_at: datetime
    remark: Optional[str]


class SerialTraceQuery(BaseModel):
    serial_codes: Optional[List[str]] = Field(default=None, description="序列号列表")
    sku_id: Optional[int] = Field(default=None, description="SKU ID")
    batch_id: Optional[int] = Field(default=None, description="批次ID")
    direction: TraceDirectionEnum = Field(
        default=TraceDirectionEnum.FULL,
        description="追溯方向"
    )
    date_from: Optional[datetime] = Field(default=None, description="开始日期")
    date_to: Optional[datetime] = Field(default=None, description="结束日期")
    max_depth: int = Field(default=10, ge=1, le=100, description="最大追溯深度")


class TraceNode(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    type: str
    label: str
    description: Optional[str]
    timestamp: datetime
    location: Optional[str]
    operator: Optional[str]
    reference_type: Optional[str]
    reference_id: Optional[int]
    metadata: Dict[str, Any] = Field(default_factory=dict)


class TraceEdge(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    source: str
    target: str
    label: Optional[str]
    action: str
    timestamp: datetime


class TraceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    serial_code: str
    direction: TraceDirectionEnum
    nodes: List[TraceNode] = Field(description="追溯节点列表")
    edges: List[TraceEdge] = Field(description="追溯边列表")
    depth: int = Field(description="追溯深度")
    total_nodes: int = Field(description="节点总数")
    total_edges: int = Field(description="边总数")

    def get_forward_path(self) -> List[TraceNode]:
        return [n for n in self.nodes if n.type in ["receive", "putaway", "transfer", "allocate", "ship"]]

    def get_backward_path(self) -> List[TraceNode]:
        return [n for n in self.nodes if n.type in ["return", "scrap", "ship_reverse", "allocate_reverse"]]


class SerialNumberVerifyRequest(BaseModel):
    serial_codes: List[str] = Field(description="待验证的序列号列表")
    sku_id: Optional[int] = Field(default=None, description="指定SKU ID验证")
    warehouse_id: Optional[int] = Field(default=None, description="指定仓库ID验证")
    check_available: bool = Field(default=True, description="是否检查可用状态")


class SerialNumberVerifyResult(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    serial_code: str
    exists: bool
    is_available: bool
    status: Optional[SerialNumberStatusEnum]
    sku_id: Optional[int]
    warehouse_id: Optional[int]
    message: str


class SerialNumberVerifyResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    results: List[SerialNumberVerifyResult]
    valid_count: int
    invalid_count: int
    available_count: int


class SerialNumberScanRequest(BaseModel):
    serial_code: str = Field(description="扫描的序列号")
    sku_id: Optional[int] = Field(default=None, description="SKU ID")
    batch_id: Optional[int] = Field(default=None, description="批次ID")
    warehouse_id: int = Field(description="仓库ID")
    action: TraceActionEnum = Field(description="操作类型")
    location: Optional[str] = Field(default=None, description="位置")
    reference_type: Optional[str] = Field(default=None, description="关联类型")
    reference_id: Optional[int] = Field(default=None, description="关联ID")
    auto_create: bool = Field(default=False, description="不存在时自动创建")


class SerialNumberScanResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    serial_code: str
    success: bool
    message: str
    serial_id: Optional[int]
    action: TraceActionEnum
    previous_status: Optional[SerialNumberStatusEnum]
    new_status: Optional[SerialNumberStatusEnum]


class SerialNumberExportRequest(BaseModel):
    serial_codes: Optional[List[str]] = Field(default=None, description="指定序列号导出")
    sku_id: Optional[int] = Field(default=None, description="SKU ID筛选")
    batch_id: Optional[int] = Field(default=None, description="批次ID筛选")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID筛选")
    status: Optional[SerialNumberStatusEnum] = Field(default=None, description="状态筛选")
    format: str = Field(default="csv", description="导出格式: csv/excel")


class SerialNumberFilterParams(BaseModel):
    sku_id: Optional[int] = Field(default=None, description="SKU ID")
    batch_id: Optional[int] = Field(default=None, description="批次ID")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID")
    status: Optional[SerialNumberStatusEnum] = Field(default=None, description="序列号状态")
    serial_code_prefix: Optional[str] = Field(default=None, description="序列号前缀")
    date_from: Optional[datetime] = Field(default=None, description="创建开始日期")
    date_to: Optional[datetime] = Field(default=None, description="创建结束日期")
    expiration_from: Optional[datetime] = Field(default=None, description="到期开始日期")
    expiration_to: Optional[datetime] = Field(default=None, description="到期结束日期")
    is_expiring: Optional[bool] = Field(default=None, description="是否临期")
    keyword: Optional[str] = Field(default=None, description="关键词搜索")


class SerialNumberAllocateRequest(BaseModel):
    order_id: int = Field(description="订单ID")
    order_type: str = Field(default="SALES_ORDER", description="订单类型")


class SerialNumberShipRequest(BaseModel):
    order_id: int = Field(description="订单ID")
    order_type: str = Field(default="SALES_ORDER", description="订单类型")
    customer_id: Optional[int] = Field(default=None, description="客户ID")
    shipping_address: Optional[str] = Field(default=None, max_length=500, description="收货地址")


class SerialNumberReturnRequest(BaseModel):
    return_order_id: int = Field(description="退货单ID")
    reason: str = Field(default="", max_length=500, description="退货原因")


class SerialNumberScrapRequest(BaseModel):
    reason: str = Field(..., min_length=1, max_length=500, description="报废原因")
    scrap_order_id: Optional[int] = Field(default=None, description="报废单ID")


class SerialNumberListResponse(APIResponse[PaginatedResponse[SerialNumber]]):
    pass


class SerialNumberDetailResponse(APIResponse[SerialNumberDetail]):
    pass


class SerialNumberImportResponseAPI(APIResponse[SerialNumberImportResponse]):
    pass


class SerialNumberVerifyResponseAPI(APIResponse[SerialNumberVerifyResponse]):
    pass


class SerialNumberScanResponseAPI(APIResponse[SerialNumberScanResponse]):
    pass


class TraceResponseAPI(APIResponse[TraceResponse]):
    pass
