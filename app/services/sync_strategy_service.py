from __future__ import annotations

from datetime import datetime
import logging
from typing import Optional, Union
from sqlalchemy import and_, func
from sqlalchemy.orm import Session

from app.models.warehouse import Warehouse
from app.models.inventory import Inventory
from app.models.inventory_sync import InventorySync, SyncType, SyncStatus
from app.models.sync_strategy import InventorySnapshot
from app.models.cdc import CDCEvent, CDCLog
from app.schemas.sync_strategy import (
    WarehouseSyncStrategyUpdate,
    ManualSyncRequest,
    ScheduledSyncResult,
    SyncQueueItem,
    SyncQueueResponse,
    InventorySnapshotCreate,
    InventorySnapshotResponse,
    SnapshotListFilter,
    SnapshotListResponse,
    ManualSyncResult,
)
from app.utils.constants import (
    SyncStrategy,
    DEFAULT_SCHEDULED_SYNC_TIME,
    CDC_EVENT_BATCH_SIZE,
)
from app.utils.exceptions import (
    NotFoundException,
    ValidationException,
    BusinessException,
)
from app.utils.helpers import get_current_utc_time
from app.utils.sync_engine import CDCCaptureEngine, create_sync_engine

logger = logging.getLogger(__name__)


class SyncStrategyService:
    def __init__(self, db: Session):
        self.db = db
        self.cdc_engine, self.conflict_engine, self.consistency_engine, self.delay_monitor = (
            create_sync_engine(db)
        )

    def _get_warehouse_or_404(self, warehouse_id: int) -> Warehouse:
        warehouse = self.db.get(Warehouse, warehouse_id)
        if not warehouse:
            raise NotFoundException(f"Warehouse {warehouse_id} not found")
        return warehouse

    def _validate_scheduled_sync_time(self, time_str: str) -> bool:
        try:
            datetime.strptime(time_str, "%H:%M")
            return True
        except ValueError:
            return False

    def update_warehouse_sync_strategy(
        self,
        warehouse_id: int,
        strategy_update: WarehouseSyncStrategyUpdate,
    ) -> Warehouse:
        warehouse = self._get_warehouse_or_404(warehouse_id)

        if strategy_update.sync_strategy is not None:
            warehouse.sync_strategy = strategy_update.sync_strategy

            if strategy_update.sync_strategy == SyncStrategy.VIRTUAL:
                warehouse.is_virtual = True
                warehouse.warehouse_type = "VIRTUAL"

        if strategy_update.is_virtual is not None:
            warehouse.is_virtual = strategy_update.is_virtual
            if strategy_update.is_virtual:
                warehouse.sync_strategy = SyncStrategy.VIRTUAL
                warehouse.warehouse_type = "VIRTUAL"

        if strategy_update.scheduled_sync_time is not None:
            if not self._validate_scheduled_sync_time(strategy_update.scheduled_sync_time):
                raise ValidationException(
                    f"Invalid scheduled_sync_time format: {strategy_update.scheduled_sync_time}. "
                    f"Expected format HH:MM"
                )
            warehouse.scheduled_sync_time = strategy_update.scheduled_sync_time

        self.db.flush()
        logger.info(
            f"Updated sync strategy for warehouse {warehouse_id}: "
            f"strategy={warehouse.sync_strategy}, "
            f"is_virtual={warehouse.is_virtual}, "
            f"scheduled_time={warehouse.scheduled_sync_time}"
        )
        return warehouse

    def get_warehouse_sync_strategy(self, warehouse_id: int) -> dict[str, any]:
        warehouse = self._get_warehouse_or_404(warehouse_id)
        return {
            "warehouse_id": warehouse.id,
            "sync_strategy": warehouse.sync_strategy,
            "is_virtual": warehouse.is_virtual,
            "scheduled_sync_time": warehouse.scheduled_sync_time,
            "last_snapshot_at": warehouse.last_snapshot_at,
        }

    def trigger_manual_sync(
        self,
        warehouse_id: int,
        request: ManualSyncRequest,
    ) -> ManualSyncResult:
        warehouse = self._get_warehouse_or_404(warehouse_id)

        if warehouse.sync_strategy not in [SyncStrategy.MANUAL, SyncStrategy.REALTIME]:
            raise BusinessException(
                f"Manual sync not allowed for warehouse with strategy {warehouse.sync_strategy}"
            )

        target_warehouse_ids = request.target_warehouse_ids
        if not target_warehouse_ids:
            target_warehouses = (
                self.db.query(Warehouse)
                .filter(
                    Warehouse.id != warehouse_id,
                    Warehouse.is_active == True,
                    Warehouse.is_virtual == False,
                )
                .all()
            )
            target_warehouse_ids = [w.id for w in target_warehouses]

        sync_type = SyncType.INCREMENTAL
        if request.sync_type and request.sync_type.upper() == "FULL":
            sync_type = SyncType.FULL

        sync_id = None
        if target_warehouse_ids:
            target_id = target_warehouse_ids[0]
            sync = InventorySync(
                source_warehouse_id=warehouse_id,
                target_warehouse_id=target_id,
                sync_type=sync_type,
                sync_status=SyncStatus.RUNNING,
                started_at=get_current_utc_time(),
            )
            self.db.add(sync)
            self.db.flush()
            sync_id = sync.id

        pending_events = self.cdc_engine.get_sync_queue(
            warehouse_id, strategy=[SyncStrategy.MANUAL], limit=CDC_EVENT_BATCH_SIZE
        )
        processed_count = 0
        for event in pending_events:
            self.cdc_engine.mark_event_processed(event.id)
            processed_count += 1

        logger.info(
            f"Manual sync triggered for warehouse {warehouse_id}: "
            f"targets={target_warehouse_ids}, processed_events={processed_count}"
        )

        return ManualSyncResult(
            warehouse_id=warehouse_id,
            target_warehouse_ids=target_warehouse_ids,
            sync_id=sync_id,
            status="RUNNING",
            message=f"Manual sync started. Processed {processed_count} pending events.",
            started_at=get_current_utc_time(),
        )

    def run_scheduled_sync(self, warehouse_id: int) -> ScheduledSyncResult:
        warehouse = self._get_warehouse_or_404(warehouse_id)

        if warehouse.sync_strategy != SyncStrategy.SCHEDULED:
            raise BusinessException(
                f"Scheduled sync not allowed for warehouse with strategy {warehouse.sync_strategy}"
            )

        result = self.cdc_engine.process_scheduled_sync(warehouse_id)

        logger.info(
            f"Scheduled sync executed for warehouse {warehouse_id}: "
            f"processed={result.get('processed_count')}, "
            f"failed={result.get('failed_count')}"
        )

        return ScheduledSyncResult(
            warehouse_id=warehouse_id,
            success=result.get("success", False),
            processed_count=result.get("processed_count", 0),
            failed_count=result.get("failed_count", 0),
            total_count=result.get("total_count", 0),
            error=result.get("error"),
            executed_at=get_current_utc_time(),
        )

    def run_all_scheduled_syncs(self) -> list[ScheduledSyncResult]:
        scheduled_warehouses = (
            self.db.query(Warehouse)
            .filter(
                Warehouse.sync_strategy == SyncStrategy.SCHEDULED,
                Warehouse.is_active == True,
            )
            .all()
        )

        results = []
        for warehouse in scheduled_warehouses:
            try:
                result = self.run_scheduled_sync(warehouse.id)
                results.append(result)
            except Exception as e:
                logger.error(f"Scheduled sync failed for warehouse {warehouse.id}: {str(e)}")
                results.append(
                    ScheduledSyncResult(
                        warehouse_id=warehouse.id,
                        success=False,
                        processed_count=0,
                        failed_count=0,
                        total_count=0,
                        error=str(e),
                        executed_at=get_current_utc_time(),
                    )
                )

        return results

    def get_sync_queue(
        self,
        warehouse_id: int,
        limit: int = 100,
    ) -> SyncQueueResponse:
        self._get_warehouse_or_404(warehouse_id)

        pending_events = self.cdc_engine.get_sync_queue(
            warehouse_id, limit=limit
        )

        items = []
        for event in pending_events:
            items.append(
                SyncQueueItem(
                    event_id=event.id,
                    cdc_log_id=event.cdc_log_id,
                    event_type=event.event_type.value if hasattr(event.event_type, "value") else str(event.event_type),
                    created_at=event.created_at,
                    status=event.status,
                )
            )

        return SyncQueueResponse(
            warehouse_id=warehouse_id,
            total_count=len(items),
            items=items,
        )

    def create_inventory_snapshot(
        self,
        snapshot_in: InventorySnapshotCreate,
    ) -> InventorySnapshot:
        self._get_warehouse_or_404(snapshot_in.warehouse_id)

        snapshot_date = snapshot_in.snapshot_date or get_current_utc_time()

        snapshot = InventorySnapshot(
            warehouse_id=snapshot_in.warehouse_id,
            sku_id=snapshot_in.sku_id,
            quantity=snapshot_in.quantity,
            available_quantity=snapshot_in.available_quantity,
            snapshot_date=snapshot_date,
        )

        self.db.add(snapshot)
        self.db.flush()

        warehouse = self._get_warehouse_or_404(snapshot_in.warehouse_id)
        warehouse.last_snapshot_at = snapshot_date
        self.db.flush()

        logger.info(
            f"Created inventory snapshot: warehouse={snapshot_in.warehouse_id}, "
            f"sku={snapshot_in.sku_id}, qty={snapshot_in.quantity}"
        )
        return snapshot

    def take_virtual_warehouse_snapshot(
        self,
        warehouse_id: int,
    ) -> dict[str, any]:
        warehouse = self._get_warehouse_or_404(warehouse_id)

        if not warehouse.is_virtual and warehouse.sync_strategy != SyncStrategy.VIRTUAL:
            raise BusinessException(
                f"Warehouse {warehouse_id} is not a virtual warehouse"
            )

        result = self.cdc_engine.take_virtual_snapshot(warehouse_id)
        self.db.commit()

        return result

    def list_snapshots(
        self,
        filter_params: SnapshotListFilter,
    ) -> SnapshotListResponse:
        self._get_warehouse_or_404(filter_params.warehouse_id)

        query = self.db.query(InventorySnapshot).filter(
            InventorySnapshot.warehouse_id == filter_params.warehouse_id
        )

        if filter_params.sku_id:
            query = query.filter(InventorySnapshot.sku_id == filter_params.sku_id)
        if filter_params.start_date:
            query = query.filter(InventorySnapshot.snapshot_date >= filter_params.start_date)
        if filter_params.end_date:
            query = query.filter(InventorySnapshot.snapshot_date <= filter_params.end_date)

        total_count = query.with_entities(func.count(InventorySnapshot.id)).scalar() or 0

        offset = (filter_params.page - 1) * filter_params.page_size
        snapshots = (
            query.order_by(InventorySnapshot.snapshot_date.desc(), InventorySnapshot.id.desc())
            .offset(offset)
            .limit(filter_params.page_size)
            .all()
        )

        items = [
            InventorySnapshotResponse.model_validate(snapshot) for snapshot in snapshots
        ]

        return SnapshotListResponse(
            warehouse_id=filter_params.warehouse_id,
            total_count=total_count,
            page=filter_params.page,
            page_size=filter_params.page_size,
            items=items,
        )

    def get_snapshot(self, snapshot_id: int) -> InventorySnapshot:
        snapshot = self.db.get(InventorySnapshot, snapshot_id)
        if not snapshot:
            raise NotFoundException(f"Inventory snapshot {snapshot_id} not found")
        return snapshot


__all__ = ["SyncStrategyService"]
