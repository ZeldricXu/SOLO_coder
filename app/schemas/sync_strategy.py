from __future__ import annotations

from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field, ConfigDict

from app.utils.constants import SyncStrategy, DEFAULT_SCHEDULED_SYNC_TIME


class WarehouseSyncStrategyUpdate(BaseModel):
    sync_strategy: Optional[SyncStrategy] = Field(
        None, description="同步策略: REALTIME实时, SCHEDULED定时, MANUAL手动, VIRTUAL虚拟"
    )
    is_virtual: Optional[bool] = Field(None, description="是否为虚拟仓")
    scheduled_sync_time: Optional[str] = Field(
        None, description="定时同步时间, 格式HH:MM, 默认02:00"
    )


class WarehouseSyncStrategyResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    warehouse_id: int
    sync_strategy: Optional[SyncStrategy]
    is_virtual: bool
    scheduled_sync_time: str
    last_snapshot_at: Optional[datetime]


class ManualSyncRequest(BaseModel):
    target_warehouse_ids: Optional[list[int]] = Field(
        None, description="目标仓库ID列表, 为空则同步所有关联仓库"
    )
    sync_type: Optional[str] = Field("INCREMENTAL", description="同步类型: FULL全量, INCREMENTAL增量")


class ManualSyncResult(BaseModel):
    warehouse_id: int
    target_warehouse_ids: list[int]
    sync_id: Optional[int]
    status: str
    message: str
    started_at: datetime


class ScheduledSyncResult(BaseModel):
    warehouse_id: int
    success: bool
    processed_count: int
    failed_count: int
    total_count: int
    error: Optional[str] = None
    executed_at: datetime


class SyncQueueItem(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    event_id: int
    cdc_log_id: int
    event_type: str
    created_at: datetime
    status: str


class SyncQueueResponse(BaseModel):
    warehouse_id: int
    total_count: int
    items: list[SyncQueueItem]


class InventorySnapshotCreate(BaseModel):
    warehouse_id: int = Field(..., description="仓库ID")
    sku_id: int = Field(..., description="SKU ID")
    quantity: int = Field(default=0, ge=0, description="库存数量")
    available_quantity: int = Field(default=0, ge=0, description="可用数量")
    snapshot_date: Optional[datetime] = Field(None, description="快照时间")


class InventorySnapshotResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    warehouse_id: int
    sku_id: int
    quantity: int
    available_quantity: int
    snapshot_date: datetime
    created_at: datetime


class SnapshotListFilter(BaseModel):
    warehouse_id: int = Field(..., description="仓库ID")
    sku_id: Optional[int] = Field(None, description="SKU ID筛选")
    start_date: Optional[datetime] = Field(None, description="开始日期")
    end_date: Optional[datetime] = Field(None, description="结束日期")
    page: int = Field(default=1, ge=1, description="页码")
    page_size: int = Field(default=20, ge=1, le=100, description="每页数量")


class SnapshotListResponse(BaseModel):
    warehouse_id: int
    total_count: int
    page: int
    page_size: int
    items: list[InventorySnapshotResponse]


__all__ = [
    "WarehouseSyncStrategyUpdate",
    "WarehouseSyncStrategyResponse",
    "ManualSyncRequest",
    "ManualSyncResult",
    "ScheduledSyncResult",
    "SyncQueueItem",
    "SyncQueueResponse",
    "InventorySnapshotCreate",
    "InventorySnapshotResponse",
    "SnapshotListFilter",
    "SnapshotListResponse",
]
