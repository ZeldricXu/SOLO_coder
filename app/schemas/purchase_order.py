from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field, ConfigDict

from app.models.purchase_order import PurchaseOrderStatus
from app.schemas.common import APIResponse, PaginatedResponse


class PurchaseOrderStatusEnum(str, PyEnum):
    DRAFT = "DRAFT"
    SUBMITTED = "SUBMITTED"
    APPROVING = "APPROVING"
    PARTIAL_APPROVED = "PARTIAL_APPROVED"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    PROCESSING = "PROCESSING"
    PARTIAL_RECEIVED = "PARTIAL_RECEIVED"
    RECEIVED = "RECEIVED"
    CANCELLED = "CANCELLED"
    CLOSED = "CLOSED"


class ForecastMethodEnum(str, PyEnum):
    MOVING_AVERAGE = "MOVING_AVERAGE"
    EXPONENTIAL_SMOOTHING = "EXPONENTIAL_SMOOTHING"
    ARIMA = "ARIMA"
    LINEAR_REGRESSION = "LINEAR_REGRESSION"


class PurchaseOrderItemBase(BaseModel):
    sku_id: int = Field(description="SKU ID")
    quantity: int = Field(ge=1, description="采购数量")
    unit_price: float = Field(ge=0, description="单价")
    tax_rate: Optional[float] = Field(default=0.0, ge=0, le=1, description="税率")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class PurchaseOrderItemCreate(PurchaseOrderItemBase):
    pass


class PurchaseOrderItemUpdate(BaseModel):
    quantity: Optional[int] = Field(default=None, ge=1, description="采购数量")
    unit_price: Optional[float] = Field(default=None, ge=0, description="单价")
    tax_rate: Optional[float] = Field(default=None, ge=0, le=1, description="税率")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")
    received_quantity: Optional[int] = Field(default=None, ge=0, description="已收数量")
    rejected_quantity: Optional[int] = Field(default=None, ge=0, description="拒收数量")


class PurchaseOrderItem(PurchaseOrderItemBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    purchase_order_id: int
    received_quantity: int = Field(default=0, description="已收数量")
    rejected_quantity: int = Field(default=0, description="拒收数量")
    tax_amount: float = Field(default=0.0, description="税额")
    total_amount: float = Field(default=0.0, description="总金额")
    created_at: datetime

    sku_code: Optional[str] = Field(default=None, description="SKU编码")
    sku_name: Optional[str] = Field(default=None, description="SKU名称")


class PurchaseOrderBase(BaseModel):
    supplier_id: int = Field(description="供应商ID")
    warehouse_id: int = Field(description="仓库ID")
    order_date: datetime = Field(description="订单日期")
    expected_date: Optional[datetime] = Field(default=None, description="预计到货日期")
    shipping_method: Optional[str] = Field(default=None, max_length=100, description="运输方式")
    shipping_cost: Optional[float] = Field(default=0.0, ge=0, description="运费")
    tax_rate: Optional[float] = Field(default=0.0, ge=0, le=1, description="税率")
    discount_rate: Optional[float] = Field(default=0.0, ge=0, le=1, description="折扣率")
    remark: Optional[str] = Field(default=None, max_length=1000, description="备注")


class PurchaseOrderCreate(PurchaseOrderBase):
    items: List[PurchaseOrderItemCreate] = Field(description="采购明细")


class PurchaseOrderUpdate(BaseModel):
    supplier_id: Optional[int] = Field(default=None, description="供应商ID")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID")
    order_date: Optional[datetime] = Field(default=None, description="订单日期")
    expected_date: Optional[datetime] = Field(default=None, description="预计到货日期")
    shipping_method: Optional[str] = Field(default=None, max_length=100, description="运输方式")
    shipping_cost: Optional[float] = Field(default=None, ge=0, description="运费")
    tax_rate: Optional[float] = Field(default=None, ge=0, le=1, description="税率")
    discount_rate: Optional[float] = Field(default=None, ge=0, le=1, description="折扣率")
    remark: Optional[str] = Field(default=None, max_length=1000, description="备注")
    items: Optional[List[PurchaseOrderItemCreate]] = Field(default=None, description="采购明细")


class PurchaseOrderGenerateRequest(BaseModel):
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID，为空则所有仓库")
    sku_ids: Optional[List[int]] = Field(default=None, description="指定SKU ID列表")
    category_id: Optional[int] = Field(default=None, description="商品分类ID")
    forecast_method: ForecastMethodEnum = Field(
        default=ForecastMethodEnum.MOVING_AVERAGE,
        description="预测方法"
    )
    forecast_periods: int = Field(default=30, ge=1, le=365, description="预测周期（天）")
    history_days: int = Field(default=90, ge=7, le=730, description="历史数据天数")
    safety_stock_multiplier: float = Field(
        default=1.5, ge=0.5, le=5.0,
        description="安全库存系数"
    )
    lead_time_days: Optional[int] = Field(default=None, ge=1, description="采购提前期（天）")
    service_level: float = Field(
        default=0.95, ge=0.5, le=0.99,
        description="服务水平（用于计算安全库存）"
    )
    auto_create: bool = Field(default=False, description="是否自动创建草稿订单")


class PurchaseOrderGenerateItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    sku_id: int
    sku_code: str
    sku_name: Optional[str] = None
    current_stock: int
    reserved_stock: int
    available_stock: int
    safety_stock: int
    reorder_point: int
    forecast_demand: float
    lead_time_days: int
    suggested_quantity: int
    unit_price: float
    supplier_id: Optional[int] = None
    supplier_name: Optional[str] = None


class PurchaseOrderGenerateResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    items: List[PurchaseOrderGenerateItem] = Field(description="建议采购明细")
    total_quantity: int = Field(description="建议采购总数量")
    total_amount: float = Field(description="建议采购总金额")
    forecast_method: ForecastMethodEnum
    forecast_periods: int
    history_days: int
    created_order_id: Optional[int] = Field(default=None, description="自动创建的订单ID")
    created_order_no: Optional[str] = Field(default=None, description="自动创建的订单编号")


class PurchaseOrderReceiveItem(BaseModel):
    item_id: int = Field(description="订单明细ID")
    received_quantity: int = Field(ge=0, description="实收数量")
    rejected_quantity: Optional[int] = Field(default=0, ge=0, description="拒收数量")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")
    batch_no: Optional[str] = Field(default=None, max_length=50, description="批次号")
    expire_date: Optional[datetime] = Field(default=None, description="有效期")


class PurchaseOrderReceiveRequest(BaseModel):
    items: List[PurchaseOrderReceiveItem] = Field(description="收货明细")
    actual_date: Optional[datetime] = Field(default=None, description="实际到货日期")
    warehouse_id: Optional[int] = Field(default=None, description="入库仓库ID")
    zone_id: Optional[int] = Field(default=None, description="入库库区ID")
    remark: Optional[str] = Field(default=None, max_length=1000, description="备注")


class PurchaseOrder(PurchaseOrderBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    order_no: str
    total_amount: float
    tax_amount: float
    discount_amount: float
    grand_total: float
    status: PurchaseOrderStatus
    actual_date: Optional[datetime] = None
    created_by: int
    created_at: datetime
    updated_at: datetime
    approved_by: Optional[int] = None
    approved_at: Optional[datetime] = None

    items: List[PurchaseOrderItem] = Field(default_factory=list)
    supplier_name: Optional[str] = None
    warehouse_name: Optional[str] = None
    created_by_name: Optional[str] = None
    approved_by_name: Optional[str] = None
    current_approval_node: Optional[str] = None
    approval_status: Optional[str] = None


class PurchaseOrderDetail(PurchaseOrder):
    model_config = ConfigDict(from_attributes=True)

    approval_records: List[Dict[str, Any]] = Field(default_factory=list)
    inventory_transactions: List[Dict[str, Any]] = Field(default_factory=list)
    can_submit: bool = Field(default=False, description="是否可提交审批")
    can_approve: bool = Field(default=False, description="是否可审批")
    can_receive: bool = Field(default=False, description="是否可收货")
    can_close: bool = Field(default=False, description="是否可关闭")
    can_cancel: bool = Field(default=False, description="是否可取消")
    can_edit: bool = Field(default=False, description="是否可编辑")


class PurchaseOrderListFilter(BaseModel):
    status: Optional[List[PurchaseOrderStatus]] = Field(default=None, description="订单状态")
    supplier_id: Optional[int] = Field(default=None, description="供应商ID")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID")
    created_by: Optional[int] = Field(default=None, description="创建人ID")
    start_date: Optional[datetime] = Field(default=None, description="开始日期")
    end_date: Optional[datetime] = Field(default=None, description="结束日期")
    order_no: Optional[str] = Field(default=None, description="订单编号（模糊搜索）")
    keyword: Optional[str] = Field(default=None, description="关键词搜索")


class PurchaseOrderListResponse(APIResponse[PaginatedResponse[PurchaseOrder]]):
    pass


class PurchaseOrderDetailResponse(APIResponse[PurchaseOrderDetail]):
    pass


class PurchaseOrderGenerateResultResponse(APIResponse[PurchaseOrderGenerateResponse]):
    pass


class PurchaseOrderIdResponse(APIResponse[dict]):
    pass
