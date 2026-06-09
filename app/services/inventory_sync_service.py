from __future__ import annotations
from datetime import datetime
import logging
from sqlalchemy import and_, func
from sqlalchemy.orm import Session

from app.models.inventory import Inventory
from app.models.inventory_sync import InventorySync, SyncType, SyncStatus
from app.models.sync_conflict import SyncConflict, ConflictStatus
from app.models.inventory_transaction import TransactionType
from app.schemas.warehouse import (
    InventorySyncCreate,
    SyncConflictResolve,
    InventoryTransactionCreate,
)
from app.utils.exceptions import (
    SyncException,
    SyncConflictException,
)
from app.utils.helpers import get_current_utc_time
from app.utils.constants import (
    ConflictType,
)
from app.utils.sync_engine import (
    create_sync_engine,
)
from app.services.warehouse_service import WarehouseService
from app.services.inventory_service import InventoryService

logger = logging.getLogger(__name__)


class InventorySyncService:
    def __init__(self, db: Session):
        self.db = db
        self.warehouse_service = WarehouseService(db)
        self.inventory_service = InventoryService(db)
        self.cdc_engine, self.conflict_engine, self.consistency_engine, self.delay_monitor = (
            create_sync_engine(db)
        )

    def get_sync(self, sync_id: int) -> InventorySync:
        sync = self.db.get(InventorySync, sync_id)
        if not sync:
            raise SyncException(sync_id, "Sync task not found")
        return sync

    def list_syncs(
        self,
        source_warehouse_id: int | None = None,
        target_warehouse_id: int | None = None,
        sync_status: SyncStatus | None = None,
        sync_type: SyncType | None = None,
        skip: int = 0,
        limit: int = 100,
    ) -> list[InventorySync]:
        query = self.db.query(InventorySync)

        if source_warehouse_id:
            query = query.filter(InventorySync.source_warehouse_id == source_warehouse_id)
        if target_warehouse_id:
            query = query.filter(InventorySync.target_warehouse_id == target_warehouse_id)
        if sync_status:
            query = query.filter(InventorySync.sync_status == sync_status)
        if sync_type:
            query = query.filter(InventorySync.sync_type == sync_type)

        return query.order_by(InventorySync.created_at.desc()).offset(skip).limit(limit).all()

    def count_syncs(
        self,
        source_warehouse_id: int | None = None,
        target_warehouse_id: int | None = None,
        sync_status: SyncStatus | None = None,
        sync_type: SyncType | None = None,
    ) -> int:
        query = self.db.query(func.count(InventorySync.id))

        if source_warehouse_id:
            query = query.filter(InventorySync.source_warehouse_id == source_warehouse_id)
        if target_warehouse_id:
            query = query.filter(InventorySync.target_warehouse_id == target_warehouse_id)
        if sync_status:
            query = query.filter(InventorySync.sync_status == sync_status)
        if sync_type:
            query = query.filter(InventorySync.sync_type == sync_type)

        return query.scalar() or 0

    def create_sync(self, sync_in: InventorySyncCreate) -> InventorySync:
        self.warehouse_service.get_warehouse(sync_in.source_warehouse_id)
        self.warehouse_service.get_warehouse(sync_in.target_warehouse_id)

        if sync_in.source_warehouse_id == sync_in.target_warehouse_id:
            raise SyncException(
                None, "Source and target warehouses cannot be the same"
            )

        sync = InventorySync(
            **sync_in.model_dump(),
            sync_status=SyncStatus.PENDING,
            created_at=get_current_utc_time(),
        )
        self.db.add(sync)
        self.db.flush()
        self.db.refresh(sync)

        logger.info(
            f"Sync task created: id={sync.id}, "
            f"source={sync_in.source_warehouse_id}, "
            f"target={sync_in.target_warehouse_id}, "
            f"type={sync_in.sync_type}"
        )

        return sync

    def _get_source_inventories(
        self, source_warehouse_id: int, incremental: bool = False, last_sync_at: datetime | None = None
    ) -> list[dict]:
        query = self.db.query(Inventory).filter(
            Inventory.warehouse_id == source_warehouse_id
        )

        if incremental and last_sync_at:
            query = query.filter(Inventory.updated_at >= last_sync_at)

        inventories = query.all()

        return [
            {
                "sku_id": inv.sku_id,
                "warehouse_id": inv.warehouse_id,
                "zone_id": inv.zone_id,
                "quantity": inv.quantity,
                "reserved_quantity": inv.reserved_quantity,
                "allocated_quantity": inv.allocated_quantity,
                "available_quantity": inv.available_quantity,
                "unit_cost": float(inv.unit_cost),
                "total_value": float(inv.total_value),
                "updated_at": inv.updated_at,
            }
            for inv in inventories
        ]

    def _get_target_inventories(
        self, target_warehouse_id: int, sku_ids: list[int] | None = None
    ) -> dict[tuple[int, int], dict]:
        query = self.db.query(Inventory).filter(
            Inventory.warehouse_id == target_warehouse_id
        )

        if sku_ids:
            query = query.filter(Inventory.sku_id.in_(sku_ids))

        inventories = query.all()

        result = {}
        for inv in inventories:
            key = (inv.sku_id, inv.zone_id)
            result[key] = {
                "id": inv.id,
                "sku_id": inv.sku_id,
                "warehouse_id": inv.warehouse_id,
                "zone_id": inv.zone_id,
                "quantity": inv.quantity,
                "reserved_quantity": inv.reserved_quantity,
                "allocated_quantity": inv.allocated_quantity,
                "available_quantity": inv.available_quantity,
                "unit_cost": float(inv.unit_cost),
                "total_value": float(inv.total_value),
                "updated_at": inv.updated_at,
            }
        return result

    def process_sync(self, sync_id: int) -> InventorySync:
        sync = self.get_sync(sync_id)

        if sync.sync_status == SyncStatus.RUNNING:
            raise SyncException(sync_id, "Sync is already running")

        try:
            sync.sync_status = SyncStatus.RUNNING
            sync.started_at = get_current_utc_time()
            self.db.flush()

            last_sync_at = self.delay_monitor.get_last_sync_time(
                sync.source_warehouse_id, sync.target_warehouse_id
            )

            source_inventories = self._get_source_inventories(
                sync.source_warehouse_id,
                incremental=(sync.sync_type == SyncType.INCREMENTAL),
                last_sync_at=last_sync_at,
            )

            sync.record_count = len(source_inventories)

            if not source_inventories:
                sync.sync_status = SyncStatus.COMPLETED
                sync.completed_at = get_current_utc_time()
                sync.success_count = 0
                sync.failed_count = 0
                self.db.flush()
                logger.info(f"Sync {sync_id} completed with no records to sync")
                return sync

            sku_ids = list({inv["sku_id"] for inv in source_inventories})
            target_inventories = self._get_target_inventories(
                sync.target_warehouse_id, sku_ids
            )

            success_count = 0
            failed_count = 0

            for source_inv in source_inventories:
                try:
                    key = (source_inv["sku_id"], source_inv["zone_id"])
                    target_inv = target_inventories.get(key)

                    conflicts = self.conflict_engine.detect_conflicts(
                        source_inv, target_inv or {}
                    )

                    if conflicts:
                        self.conflict_engine.detect_and_create_conflict(
                            self.db,
                            sync_id=sync.id,
                            sku_id=source_inv["sku_id"],
                            source_inventory=source_inv,
                            target_inventory=target_inv or {},
                        )
                        failed_count += 1
                        continue

                    if target_inv:
                        target_record = self.db.get(Inventory, target_inv["id"])
                        if target_record:
                            old_data = {
                                "quantity": target_record.quantity,
                                "reserved_quantity": target_record.reserved_quantity,
                                "allocated_quantity": target_record.allocated_quantity,
                                "unit_cost": float(target_record.unit_cost),
                            }

                            target_record.quantity = source_inv["quantity"]
                            target_record.reserved_quantity = source_inv["reserved_quantity"]
                            target_record.allocated_quantity = source_inv["allocated_quantity"]
                            target_record.unit_cost = source_inv["unit_cost"]
                            target_record.available_quantity = source_inv["available_quantity"]
                            target_record.total_value = source_inv["total_value"]
                            target_record.updated_at = get_current_utc_time()

                            self.cdc_engine.capture_inventory_change(
                                target_record, "UPDATE", old_data
                            )
                    else:
                        target_record = Inventory(
                            sku_id=source_inv["sku_id"],
                            warehouse_id=sync.target_warehouse_id,
                            zone_id=source_inv["zone_id"],
                            quantity=source_inv["quantity"],
                            reserved_quantity=source_inv["reserved_quantity"],
                            allocated_quantity=source_inv["allocated_quantity"],
                            available_quantity=source_inv["available_quantity"],
                            in_transit_quantity=0,
                            unit_cost=source_inv["unit_cost"],
                            total_value=source_inv["total_value"],
                            created_at=get_current_utc_time(),
                            updated_at=get_current_utc_time(),
                        )
                        self.db.add(target_record)
                        self.db.flush()

                        self.cdc_engine.capture_inventory_change(
                            target_record, "INSERT", None
                        )

                    transaction_in = InventoryTransactionCreate(
                        sku_id=source_inv["sku_id"],
                        warehouse_id=sync.target_warehouse_id,
                        zone_id=source_inv["zone_id"],
                        transaction_type=TransactionType.ADJUSTMENT,
                        quantity=source_inv["quantity"] - (target_inv["quantity"] if target_inv else 0),
                        unit_cost=source_inv["unit_cost"],
                        reason=f"Sync from warehouse {sync.source_warehouse_id}",
                        reference_type="SYNC",
                        reference_id=sync_id,
                    )
                    self.inventory_service._create_transaction(transaction_in)

                    success_count += 1
                    self.db.flush()

                except Exception as e:
                    logger.error(
                        f"Failed to sync SKU {source_inv['sku_id']}: {str(e)}"
                    )
                    failed_count += 1
                    continue

            sync.success_count = success_count
            sync.failed_count = failed_count
            sync.sync_status = SyncStatus.COMPLETED if failed_count == 0 else SyncStatus.COMPLETED
            sync.completed_at = get_current_utc_time()

            if sync.failed_count > 0:
                sync.error_message = f"Completed with {sync.failed_count} conflicts"

            self.db.flush()
            self.db.refresh(sync)

            logger.info(
                f"Sync {sync_id} completed: total={sync.record_count}, "
                f"success={success_count}, failed={failed_count}"
            )

            return sync

        except Exception as e:
            logger.error(f"Sync {sync_id} failed: {str(e)}")
            sync.sync_status = SyncStatus.FAILED
            sync.error_message = str(e)
            sync.completed_at = get_current_utc_time()
            self.db.flush()
            raise SyncException(sync_id, str(e)) from e

    def get_sync_conflicts(
        self,
        sync_id: int | None = None,
        sku_id: int | None = None,
        conflict_type: ConflictType | None = None,
        status: ConflictStatus | None = None,
        skip: int = 0,
        limit: int = 100,
    ) -> list[SyncConflict]:
        query = self.db.query(SyncConflict)

        if sync_id:
            query = query.filter(SyncConflict.sync_id == sync_id)
        if sku_id:
            query = query.filter(SyncConflict.sku_id == sku_id)
        if conflict_type:
            query = query.filter(SyncConflict.conflict_type == conflict_type)
        if status:
            query = query.filter(SyncConflict.status == status)

        return query.order_by(SyncConflict.created_at.desc()).offset(skip).limit(limit).all()

    def count_conflicts(
        self,
        sync_id: int | None = None,
        sku_id: int | None = None,
        conflict_type: ConflictType | None = None,
        status: ConflictStatus | None = None,
    ) -> int:
        query = self.db.query(func.count(SyncConflict.id))

        if sync_id:
            query = query.filter(SyncConflict.sync_id == sync_id)
        if sku_id:
            query = query.filter(SyncConflict.sku_id == sku_id)
        if conflict_type:
            query = query.filter(SyncConflict.conflict_type == conflict_type)
        if status:
            query = query.filter(SyncConflict.status == status)

        return query.scalar() or 0

    def resolve_conflict(
        self,
        conflict_id: int,
        resolve_in: SyncConflictResolve,
    ) -> SyncConflict:
        conflict = self.conflict_engine.resolve_conflict(
            self.db,
            conflict_id,
            resolve_in.resolution_strategy,
            resolve_in.resolved_by,
        )

        sync = self.get_sync(conflict.sync_id)

        source_inv = self._get_source_inventories(sync.source_warehouse_id)
        source_inv_map = {(inv["sku_id"], inv["zone_id"]): inv for inv in source_inv}

        target_inventories = self._get_target_inventories(sync.target_warehouse_id)
        key = (conflict.sku_id, next(iter(target_inventories.values()))["zone_id"])

        source_data = source_inv_map.get(key, {})
        target_data = target_inventories.get(key, {})

        try:
            resolved_data = self.conflict_engine.apply_resolution(
                conflict, source_data, target_data
            )

            if target_data and "id" in target_data:
                target_record = self.db.get(Inventory, target_data["id"])
                if target_record and resolved_data:
                    old_data = {
                        "quantity": target_record.quantity,
                        "unit_cost": float(target_record.unit_cost),
                    }

                    target_record.quantity = resolved_data.get("quantity", target_record.quantity)
                    target_record.unit_cost = resolved_data.get("unit_cost", target_record.unit_cost)
                    target_record.available_quantity = target_record.quantity - target_record.reserved_quantity - target_record.allocated_quantity
                    target_record.total_value = target_record.quantity * float(target_record.unit_cost)
                    target_record.updated_at = get_current_utc_time()

                    self.cdc_engine.capture_inventory_change(
                        target_record, "UPDATE", old_data
                    )

                    logger.info(
                        f"Conflict {conflict_id} resolved with strategy "
                        f"{resolve_in.resolution_strategy}"
                    )

            self.db.flush()
            self.db.refresh(conflict)

            return conflict

        except ValueError as e:
            raise SyncConflictException(
                conflict_id, str(conflict.conflict_type), str(e)
            ) from e

    def check_sync_delay(
        self,
        source_warehouse_id: int,
        target_warehouse_id: int,
    ) -> dict:
        self.warehouse_service.get_warehouse(source_warehouse_id)
        self.warehouse_service.get_warehouse(target_warehouse_id)

        return self.delay_monitor.monitor_sync_pair(
            self.db, source_warehouse_id, target_warehouse_id
        )

    def get_sync_delay_report(self) -> list[dict]:
        warehouses = self.warehouse_service.list_warehouses(limit=1000)
        warehouse_ids = [wh.id for wh in warehouses]

        report = []
        for source_id in warehouse_ids:
            for target_id in warehouse_ids:
                if source_id != target_id:
                    try:
                        status = self.delay_monitor.monitor_sync_pair(
                            self.db, source_id, target_id
                        )
                        report.append(status)
                    except Exception:
                        continue

        return report

    def perform_consistency_check(
        self,
        source_warehouse_id: int,
        target_warehouse_id: int,
    ) -> dict:
        self.warehouse_service.get_warehouse(source_warehouse_id)
        self.warehouse_service.get_warehouse(target_warehouse_id)

        source_inventories = self._get_source_inventories(source_warehouse_id)
        target_inventories_list = self._get_source_inventories(target_warehouse_id)

        return self.consistency_engine.full_consistency_check(
            source_inventories, target_inventories_list
        )

    def process_cdc_events(self) -> dict:
        events = self.cdc_engine.get_pending_events()

        processed_count = 0
        failed_count = 0

        for event in events:
            try:
                if event.event_type == "INVENTORY_CHANGED":
                    cdc_log = event.cdc_log
                    if cdc_log and cdc_log.new_data:
                        pass

                self.cdc_engine.mark_event_processed(event.id)
                processed_count += 1
            except Exception as e:
                logger.error(f"Failed to process CDC event {event.id}: {str(e)}")
                self.cdc_engine.mark_event_failed(event.id, str(e))
                failed_count += 1

        return {
            "total": len(events),
            "processed": processed_count,
            "failed": failed_count,
        }

    def retry_failed_sync(self, sync_id: int) -> InventorySync:
        sync = self.get_sync(sync_id)

        if sync.sync_status != SyncStatus.FAILED:
            raise SyncException(sync_id, "Only failed syncs can be retried")

        pending_conflicts = (
            self.db.query(SyncConflict)
            .filter(
                and_(
                    SyncConflict.sync_id == sync_id,
                    SyncConflict.status == ConflictStatus.PENDING,
                )
            )
            .count()
        )

        if pending_conflicts > 0:
            raise SyncException(
                sync_id,
                f"Cannot retry sync with {pending_conflicts} pending conflicts. "
                f"Please resolve them first.",
            )

        return self.process_sync(sync_id)


def create_inventory_sync_service(db: Session) -> InventorySyncService:
    return InventorySyncService(db)
