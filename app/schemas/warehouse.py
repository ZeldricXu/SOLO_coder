from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field, ConfigDict

from app.utils.constants import (
    WarehouseType,
    SyncType,
    SyncStatus,
    ConflictType,
    ResolutionStrategy,
    ConflictStatus,
    InventoryTransactionType,
    FIFOStrategy,
)


class WarehouseBase(BaseModel):
    name: str = Field(..., max_length=100, description="仓库名称")
    code: str = Field(..., max_length=50, description="仓库编码")
    address: Optional[str] = Field(None, max_length=255, description="地址")
    city: Optional[str] = Field(None, max_length=100, description="城市")
    province: Optional[str] = Field(None, max_length=100, description="省份")
    country: Optional[str] = Field(None, max_length=100, description="国家")
    postal_code: Optional[str] = Field(None, max_length=20, description="邮编")
    contact_person: Optional[str] = Field(None, max_length=100, description="联系人")
    contact_phone: Optional[str] = Field(None, max_length=20, description="联系电话")
    contact_email: Optional[str] = Field(None, max_length=100, description="联系邮箱")
    warehouse_type: WarehouseType = Field(default=WarehouseType.MAIN, description="仓库类型")
    is_active: bool = Field(default=True, description="是否启用")
    capacity: Optional[int] = Field(None, ge=0, description="仓库容量")


class WarehouseCreate(WarehouseBase):
    pass


class WarehouseUpdate(BaseModel):
    name: Optional[str] = Field(None, max_length=100, description="仓库名称")
    address: Optional[str] = Field(None, max_length=255, description="地址")
    city: Optional[str] = Field(None, max_length=100, description="城市")
    province: Optional[str] = Field(None, max_length=100, description="省份")
    country: Optional[str] = Field(None, max_length=100, description="国家")
    postal_code: Optional[str] = Field(None, max_length=20, description="邮编")
    contact_person: Optional[str] = Field(None, max_length=100, description="联系人")
    contact_phone: Optional[str] = Field(None, max_length=20, description="联系电话")
    contact_email: Optional[str] = Field(None, max_length=100, description="联系邮箱")
    warehouse_type: Optional[WarehouseType] = Field(None, description="仓库类型")
    is_active: Optional[bool] = Field(None, description="是否启用")
    capacity: Optional[int] = Field(None, ge=0, description="仓库容量")


class Warehouse(WarehouseBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    utilization_rate: Optional[float] = Field(None, description="利用率")
    created_at: datetime
    updated_at: datetime


class WarehouseDetail(Warehouse):
    zone_count: int = Field(default=0, description="库区数量")
    total_inventory_quantity: int = Field(default=0, description="总库存数量")
    total_inventory_value: float = Field(default=0.0, description="总库存价值")


class ZoneBase(BaseModel):
    warehouse_id: int = Field(..., description="仓库ID")
    name: str = Field(..., max_length=100, description="库区名称")
    code: str = Field(..., max_length=50, description="库区编码")
    area: Optional[float] = Field(None, gt=0, description="库区面积")
    storage_type: Optional[str] = Field(None, max_length=50, description="存储类型")


class ZoneCreate(ZoneBase):
    pass


class ZoneUpdate(BaseModel):
    name: Optional[str] = Field(None, max_length=100, description="库区名称")
    area: Optional[float] = Field(None, gt=0, description="库区面积")
    storage_type: Optional[str] = Field(None, max_length=50, description="存储类型")


class Zone(ZoneBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime


class InventoryBase(BaseModel):
    sku_id: int = Field(..., description="SKU ID")
    warehouse_id: int = Field(..., description="仓库ID")
    zone_id: int = Field(..., description="库区ID")
    quantity: int = Field(default=0, ge=0, description="总数量")
    reserved_quantity: int = Field(default=0, ge=0, description="预占数量")
    allocated_quantity: int = Field(default=0, ge=0, description="分配数量")
    unit_cost: float = Field(default=0.0, ge=0, description="单位成本")


class Inventory(InventoryBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    available_quantity: int = Field(description="可用数量")
    in_transit_quantity: int = Field(default=0, description="在途数量")
    total_value: float = Field(description="总价值")
    last_counted_at: Optional[datetime] = Field(None, description="上次盘点时间")
    created_at: datetime
    updated_at: datetime


class InventoryDetail(Inventory):
    warehouse_name: Optional[str] = Field(None, description="仓库名称")
    zone_name: Optional[str] = Field(None, description="库区名称")
    sku_code: Optional[str] = Field(None, description="SKU编码")
    sku_name: Optional[str] = Field(None, description="SKU名称")


class InventoryTransactionBase(BaseModel):
    sku_id: int = Field(..., description="SKU ID")
    warehouse_id: int = Field(..., description="仓库ID")
    zone_id: int = Field(..., description="库区ID")
    transaction_type: InventoryTransactionType = Field(..., description="事务类型")
    quantity: int = Field(..., description="数量")
    unit_cost: float = Field(default=0.0, ge=0, description="单位成本")
    reference_type: Optional[str] = Field(None, max_length=50, description="参考类型")
    reference_id: Optional[int] = Field(None, description="参考ID")
    batch_id: Optional[str] = Field(None, max_length=100, description="批次号")
    serial_number: Optional[str] = Field(None, max_length=100, description="序列号")
    reason: Optional[str] = Field(None, max_length=255, description="原因")


class InventoryTransactionCreate(InventoryTransactionBase):
    pass


class InventoryTransaction(InventoryTransactionBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime
    created_by: Optional[int] = Field(None, description="创建人")


class InventorySyncBase(BaseModel):
    source_warehouse_id: int = Field(..., description="源仓库ID")
    target_warehouse_id: int = Field(..., description="目标仓库ID")
    sync_type: SyncType = Field(default=SyncType.INCREMENTAL, description="同步类型")


class InventorySyncCreate(InventorySyncBase):
    pass


class InventorySync(InventorySyncBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    sync_status: SyncStatus
    record_count: int = Field(default=0, description="记录总数")
    success_count: int = Field(default=0, description="成功数量")
    failed_count: int = Field(default=0, description="失败数量")
    started_at: Optional[datetime] = Field(None, description="开始时间")
    completed_at: Optional[datetime] = Field(None, description="完成时间")
    error_message: Optional[str] = Field(None, description="错误信息")
    created_at: datetime


class SyncConflictBase(BaseModel):
    sync_id: int = Field(..., description="同步任务ID")
    sku_id: int = Field(..., description="SKU ID")
    source_quantity: int = Field(default=0, description="源数量")
    target_quantity: int = Field(default=0, description="目标数量")
    conflict_type: ConflictType = Field(..., description="冲突类型")


class SyncConflict(SyncConflictBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    resolution_strategy: Optional[ResolutionStrategy] = Field(None, description="解决策略")
    resolved_by: Optional[int] = Field(None, description="解决人")
    resolved_at: Optional[datetime] = Field(None, description="解决时间")
    status: ConflictStatus
    created_at: datetime


class SyncConflictResolve(BaseModel):
    resolution_strategy: ResolutionStrategy = Field(..., description="解决策略")
    resolved_by: Optional[int] = Field(None, description="解决人")


class SupplierBase(BaseModel):
    name: str = Field(..., max_length=200, description="供应商名称")
    code: str = Field(..., max_length=50, description="供应商编码")
    contact_person: Optional[str] = Field(None, max_length=100, description="联系人")
    contact_phone: Optional[str] = Field(None, max_length=20, description="联系电话")
    contact_email: Optional[str] = Field(None, max_length=100, description="联系邮箱")
    address: Optional[str] = Field(None, max_length=255, description="地址")
    city: Optional[str] = Field(None, max_length=100, description="城市")
    province: Optional[str] = Field(None, max_length=100, description="省份")
    country: Optional[str] = Field(None, max_length=100, description="国家")
    postal_code: Optional[str] = Field(None, max_length=20, description="邮编")
    tax_number: Optional[str] = Field(None, max_length=50, description="税号")
    bank_account: Optional[str] = Field(None, max_length=50, description="银行账号")
    bank_name: Optional[str] = Field(None, max_length=100, description="开户行")
    credit_rating: Optional[str] = Field(None, max_length=20, description="信用等级")
    payment_terms: Optional[str] = Field(None, max_length=100, description="付款条款")
    lead_time_days: Optional[int] = Field(None, ge=0, description="交货周期(天)")
    minimum_order_qty: Optional[int] = Field(None, ge=0, description="最小起订量")
    is_active: bool = Field(default=True, description="是否启用")


class SupplierCreate(SupplierBase):
    pass


class SupplierUpdate(BaseModel):
    name: Optional[str] = Field(None, max_length=200, description="供应商名称")
    contact_person: Optional[str] = Field(None, max_length=100, description="联系人")
    contact_phone: Optional[str] = Field(None, max_length=20, description="联系电话")
    contact_email: Optional[str] = Field(None, max_length=100, description="联系邮箱")
    address: Optional[str] = Field(None, max_length=255, description="地址")
    city: Optional[str] = Field(None, max_length=100, description="城市")
    province: Optional[str] = Field(None, max_length=100, description="省份")
    country: Optional[str] = Field(None, max_length=100, description="国家")
    postal_code: Optional[str] = Field(None, max_length=20, description="邮编")
    tax_number: Optional[str] = Field(None, max_length=50, description="税号")
    bank_account: Optional[str] = Field(None, max_length=50, description="银行账号")
    bank_name: Optional[str] = Field(None, max_length=100, description="开户行")
    credit_rating: Optional[str] = Field(None, max_length=20, description="信用等级")
    payment_terms: Optional[str] = Field(None, max_length=100, description="付款条款")
    lead_time_days: Optional[int] = Field(None, ge=0, description="交货周期(天)")
    minimum_order_qty: Optional[int] = Field(None, ge=0, description="最小起订量")
    is_active: Optional[bool] = Field(None, description="是否启用")


class Supplier(SupplierBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime
    updated_at: datetime


class InventoryFilterParams(BaseModel):
    sku_id: Optional[int] = Field(None, description="SKU ID筛选")
    warehouse_id: Optional[int] = Field(None, description="仓库ID筛选")
    zone_id: Optional[int] = Field(None, description="库区ID筛选")
    batch_id: Optional[str] = Field(None, description="批次号筛选")
    min_quantity: Optional[int] = Field(None, description="最小数量")
    max_quantity: Optional[int] = Field(None, description="最大数量")
    min_available: Optional[int] = Field(None, description="最小可用数量")
    max_available: Optional[int] = Field(None, description="最大可用数量")
    has_low_stock: Optional[bool] = Field(None, description="是否低库存")
    has_overstock: Optional[bool] = Field(None, description="是否超储")


class InventoryAdjustRequest(BaseModel):
    inventory_id: int = Field(..., description="库存ID")
    quantity: int = Field(..., description="调整数量(正负)")
    reason: str = Field(..., max_length=255, description="调整原因")
    unit_cost: Optional[float] = Field(None, ge=0, description="单位成本")


class InventoryTransferRequest(BaseModel):
    source_warehouse_id: int = Field(..., description="源仓库ID")
    source_zone_id: int = Field(..., description="源库区ID")
    target_warehouse_id: int = Field(..., description="目标仓库ID")
    target_zone_id: int = Field(..., description="目标库区ID")
    sku_id: int = Field(..., description="SKU ID")
    quantity: int = Field(..., gt=0, description="调拨数量")
    reason: Optional[str] = Field(None, max_length=255, description="调拨原因")
    strategy: FIFOStrategy = Field(default=FIFOStrategy.FIFO, description="出库策略")


class InventoryReserveRequest(BaseModel):
    inventory_id: int = Field(..., description="库存ID")
    quantity: int = Field(..., gt=0, description="预占数量")
    reference_type: Optional[str] = Field(None, max_length=50, description="参考类型")
    reference_id: Optional[int] = Field(None, description="参考ID")
    expire_seconds: Optional[int] = Field(None, description="过期时间(秒)")


class InventoryReleaseRequest(BaseModel):
    inventory_id: int = Field(..., description="库存ID")
    quantity: int = Field(..., gt=0, description="释放数量")
    reference_type: Optional[str] = Field(None, max_length=50, description="参考类型")
    reference_id: Optional[int] = Field(None, description="参考ID")


class InventoryOverview(BaseModel):
    total_warehouses: int = Field(description="仓库总数")
    total_skus: int = Field(description="SKU总数")
    total_quantity: int = Field(description="总库存数量")
    total_available_quantity: int = Field(description="总可用数量")
    total_reserved_quantity: int = Field(description="总预占数量")
    total_allocated_quantity: int = Field(description="总分配数量")
    total_value: float = Field(description="总库存价值")
    low_stock_count: int = Field(description="低库存SKU数")
    overstock_count: int = Field(description="超储SKU数")
    warehouse_utilization: dict[str, float] = Field(default_factory=dict, description="各仓库利用率")


class WarehouseInventoryOverview(BaseModel):
    warehouse_id: int
    warehouse_name: str
    warehouse_code: str
    total_quantity: int
    total_available_quantity: int
    total_value: float
    utilization_rate: Optional[float]
    sku_count: int
    zone_count: int


class TransactionFilterParams(BaseModel):
    sku_id: Optional[int] = Field(None, description="SKU ID筛选")
    warehouse_id: Optional[int] = Field(None, description="仓库ID筛选")
    zone_id: Optional[int] = Field(None, description="库区ID筛选")
    transaction_type: Optional[InventoryTransactionType] = Field(None, description="事务类型")
    start_date: Optional[datetime] = Field(None, description="开始日期")
    end_date: Optional[datetime] = Field(None, description="结束日期")
    reference_type: Optional[str] = Field(None, description="参考类型")
    reference_id: Optional[int] = Field(None, description="参考ID")
    batch_id: Optional[str] = Field(None, description="批次号")
