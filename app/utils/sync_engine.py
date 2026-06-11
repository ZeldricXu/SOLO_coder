from __future__ import annotations

from datetime import datetime
import hashlib
import json
import logging
from typing import Any, Optional, Union
from sqlalchemy import and_
from sqlalchemy.orm import Session

from app.models.cdc import CDCLog, CDCEvent, CDCOperation, CDCSourceSystem, CDCEventType
from app.models.inventory import Inventory
from app.models.inventory_sync import InventorySync, SyncStatus, SyncType
from app.models.sync_conflict import SyncConflict, ConflictType, ResolutionStrategy, ConflictStatus
from app.models.warehouse import Warehouse
from app.models.sync_strategy import InventorySnapshot
from app.utils.constants import (
    CDC_EVENT_BATCH_SIZE,
    SYNC_DELAY_THRESHOLD_SECONDS,
    SyncStrategy,
)
from app.utils.exceptions import CDCException, SyncDelayAlertException
from app.utils.helpers import get_current_utc_time, calculate_seconds_between

logger = logging.getLogger(__name__)


class CDCCaptureEngine:
    def __init__(self, db: Session, source_system: CDCSourceSystem = CDCSourceSystem.WMS):
        self.db = db
        self.source_system = source_system

    def capture_change(
        self,
        table_name: str,
        operation: CDCOperation,
        record_id: int,
        old_data: Optional[dict[str, Any]] = None,
        new_data: Optional[dict[str, Any]] = None,
        event_type: Optional[CDCEventType] = None,
    ) -> CDCLog:
        try:
            cdc_log = CDCLog(
                table_name=table_name,
                operation=operation,
                record_id=record_id,
                old_data=old_data,
                new_data=new_data,
                source_system=self.source_system,
                processed=False,
                created_at=get_current_utc_time(),
            )
            self.db.add(cdc_log)
            self.db.flush()

            if event_type:
                cdc_event = CDCEvent(
                    cdc_log_id=cdc_log.id,
                    event_type=event_type,
                    created_at=get_current_utc_time(),
                )
                self.db.add(cdc_event)

            logger.info(
                f"CDC captured: table={table_name}, operation={operation}, "
                f"record_id={record_id}, source={self.source_system}"
            )
            return cdc_log
        except Exception as e:
            logger.error(f"CDC capture failed: {str(e)}")
            raise CDCException(f"Failed to capture change: {str(e)}") from e

    def capture_inventory_change(
        self,
        inventory: Inventory,
        operation: CDCOperation,
        old_data: Optional[dict[str, Any]] = None,
    ) -> CDCLog:
        process_result = self.should_process_event(inventory.warehouse_id)
        strategy = process_result.get("strategy")
        action = process_result.get("action")

        if action == "take_snapshot" and strategy == SyncStrategy.VIRTUAL:
            logger.info(
                f"Virtual warehouse {inventory.warehouse_id}: taking snapshot instead of CDC"
            )
            self.take_virtual_snapshot(inventory.warehouse_id)

        if not process_result.get("should_process", True):
            if action in ["queue_for_scheduled", "queue_for_manual"]:
                logger.info(
                    f"Event queued for warehouse {inventory.warehouse_id}, "
                    f"strategy={strategy}, action={action}"
                )
            elif action == "take_snapshot":
                logger.info(
                    f"Virtual warehouse {inventory.warehouse_id}: snapshot taken, CDC skipped"
                )

        new_data = {
            "id": inventory.id,
            "sku_id": inventory.sku_id,
            "warehouse_id": inventory.warehouse_id,
            "zone_id": inventory.zone_id,
            "quantity": inventory.quantity,
            "reserved_quantity": inventory.reserved_quantity,
            "allocated_quantity": inventory.allocated_quantity,
            "available_quantity": inventory.available_quantity,
            "unit_cost": float(inventory.unit_cost),
            "total_value": float(inventory.total_value),
            "updated_at": inventory.updated_at.isoformat() if inventory.updated_at else None,
            "sync_strategy": strategy.value if strategy else None,
            "sync_action": action,
        }
        return self.capture_change(
            table_name="inventories",
            operation=operation,
            record_id=inventory.id,
            old_data=old_data,
            new_data=new_data,
            event_type=CDCEventType.INVENTORY_CHANGED,
        )

    def get_pending_events(self, batch_size: int = CDC_EVENT_BATCH_SIZE) -> list[CDCEvent]:
        return (
            self.db.query(CDCEvent)
            .filter(CDCEvent.status == "PENDING")
            .order_by(CDCEvent.created_at.asc())
            .limit(batch_size)
            .all()
        )

    def mark_event_processed(self, event_id: int) -> None:
        event = self.db.get(CDCEvent, event_id)
        if event:
            event.status = "PROCESSED"
            event.cdc_log.processed = True
            event.cdc_log.processed_at = get_current_utc_time()
            self.db.flush()

    def mark_event_failed(self, event_id: int, error_message: str) -> None:
        event = self.db.get(CDCEvent, event_id)
        if event:
            event.status = "FAILED"
            event.error_message = error_message
            self.db.flush()

    def should_process_event(self, warehouse_id: int) -> dict[str, Any]:
        warehouse = self.db.get(Warehouse, warehouse_id)
        if not warehouse:
            return {"should_process": False, "strategy": None, "reason": "Warehouse not found"}

        strategy = warehouse.sync_strategy or SyncStrategy.REALTIME

        if strategy == SyncStrategy.REALTIME:
            return {"should_process": True, "strategy": strategy, "action": "process_immediately"}
        elif strategy == SyncStrategy.SCHEDULED:
            return {"should_process": False, "strategy": strategy, "action": "queue_for_scheduled"}
        elif strategy == SyncStrategy.MANUAL:
            return {"should_process": False, "strategy": strategy, "action": "queue_for_manual"}
        elif strategy == SyncStrategy.VIRTUAL:
            return {"should_process": False, "strategy": strategy, "action": "take_snapshot"}
        else:
            return {"should_process": True, "strategy": strategy, "action": "process_immediately"}

    def get_sync_queue(
        self,
        warehouse_id: int,
        strategy: Optional[Union[SyncStrategy, list[SyncStrategy]]] = None,
        limit: int = 100,
    ) -> list[CDCEvent]:
        query = (
            self.db.query(CDCEvent)
            .join(CDCLog, CDCEvent.cdc_log_id == CDCLog.id)
            .filter(
                CDCEvent.status == "PENDING",
                CDCLog.table_name == "inventories",
            )
            .order_by(CDCEvent.created_at.asc())
        )

        if strategy:
            if isinstance(strategy, list):
                strategies = [s.value for s in strategy]
            else:
                strategies = [strategy.value]

        query = query.limit(limit)
        return query.all()

    def process_scheduled_sync(self, warehouse_id: int) -> dict[str, Any]:
        warehouse = self.db.get(Warehouse, warehouse_id)
        if not warehouse:
            return {"success": False, "processed_count": 0, "error": "Warehouse not found"}

        if warehouse.sync_strategy != SyncStrategy.SCHEDULED:
            return {
                "success": False,
                "processed_count": 0,
                "error": f"Warehouse strategy is {warehouse.sync_strategy}, not SCHEDULED",
            }

        pending_events = self.get_sync_queue(warehouse_id, limit=CDC_EVENT_BATCH_SIZE)
        processed_count = 0
        failed_count = 0

        for event in pending_events:
            try:
                self.mark_event_processed(event.id)
                processed_count += 1
            except Exception as e:
                self.mark_event_failed(event.id, str(e))
                failed_count += 1
                logger.error(f"Failed to process scheduled event {event.id}: {str(e)}")

        return {
            "success": True,
            "warehouse_id": warehouse_id,
            "processed_count": processed_count,
            "failed_count": failed_count,
            "total_count": len(pending_events),
        }

    def take_virtual_snapshot(self, warehouse_id: int) -> dict[str, Any]:
        warehouse = self.db.get(Warehouse, warehouse_id)
        if not warehouse:
            return {"success": False, "snapshot_count": 0, "error": "Warehouse not found"}

        inventories = (
            self.db.query(Inventory)
            .filter(Inventory.warehouse_id == warehouse_id)
            .all()
        )

        snapshot_date = get_current_utc_time()
        snapshots = []

        for inventory in inventories:
            snapshot = InventorySnapshot(
                warehouse_id=warehouse_id,
                sku_id=inventory.sku_id,
                quantity=inventory.quantity,
                available_quantity=inventory.available_quantity,
                snapshot_date=snapshot_date,
            )
            snapshots.append(snapshot)
            self.db.add(snapshot)

        warehouse.last_snapshot_at = snapshot_date
        self.db.flush()

        return {
            "success": True,
            "warehouse_id": warehouse_id,
            "snapshot_count": len(snapshots),
            "snapshot_date": snapshot_date,
        }


class ConflictDetectionEngine:
    @staticmethod
    def detect_quantity_conflict(source_qty: int, target_qty: int) -> bool:
        return source_qty != target_qty

    @staticmethod
    def detect_cost_conflict(source_cost: float, target_cost: float, tolerance: float = 0.01) -> bool:
        return abs(source_cost - target_cost) > tolerance

    @staticmethod
    def detect_status_conflict(source_active: bool, target_active: bool) -> bool:
        return source_active != target_active

    @staticmethod
    def calculate_data_hash(data: dict[str, Any]) -> str:
        sorted_data = json.dumps(data, sort_keys=True, default=str)
        return hashlib.sha256(sorted_data.encode("utf-8")).hexdigest()

    def detect_conflicts(
        self,
        source_inventory: dict[str, Any],
        target_inventory: dict[str, Any],
    ) -> list[ConflictType]:
        conflicts = []

        if self.detect_quantity_conflict(
            source_inventory.get("quantity", 0), target_inventory.get("quantity", 0)
        ):
            conflicts.append(ConflictType.QUANTITY_MISMATCH)

        if self.detect_cost_conflict(
            float(source_inventory.get("unit_cost", 0.0)),
            float(target_inventory.get("unit_cost", 0.0)),
        ):
            conflicts.append(ConflictType.COST_MISMATCH)

        if self.detect_status_conflict(
            source_inventory.get("is_active", True), target_inventory.get("is_active", True)
        ):
            conflicts.append(ConflictType.STATUS_MISMATCH)

        return conflicts

    def detect_and_create_conflict(
        self,
        db: Session,
        sync_id: int,
        sku_id: int,
        source_inventory: dict[str, Any],
        target_inventory: dict[str, Any],
    ) -> Optional[SyncConflict]:
        conflict_types = self.detect_conflicts(source_inventory, target_inventory)

        if not conflict_types:
            return None

        for conflict_type in conflict_types:
            existing_conflict = (
                db.query(SyncConflict)
                .filter(
                    and_(
                        SyncConflict.sync_id == sync_id,
                        SyncConflict.sku_id == sku_id,
                        SyncConflict.conflict_type == conflict_type,
                        SyncConflict.status == ConflictStatus.PENDING,
                    )
                )
                .first()
            )

            if existing_conflict:
                existing_conflict.source_quantity = source_inventory.get("quantity", 0)
                existing_conflict.target_quantity = target_inventory.get("quantity", 0)
                db.flush()
                return existing_conflict

            conflict = SyncConflict(
                sync_id=sync_id,
                sku_id=sku_id,
                source_quantity=source_inventory.get("quantity", 0),
                target_quantity=target_inventory.get("quantity", 0),
                conflict_type=conflict_type,
                status=ConflictStatus.PENDING,
                created_at=get_current_utc_time(),
            )
            db.add(conflict)
            db.flush()

            logger.warning(
                f"Conflict detected: sync_id={sync_id}, sku_id={sku_id}, "
                f"type={conflict_type}, source_qty={conflict.source_quantity}, "
                f"target_qty={conflict.target_quantity}"
            )

        return conflict

    def resolve_conflict(
        self,
        db: Session,
        conflict_id: int,
        strategy: ResolutionStrategy,
        resolved_by: Optional[int] = None,
    ) -> SyncConflict:
        conflict = db.get(SyncConflict, conflict_id)
        if not conflict:
            raise ValueError(f"Conflict {conflict_id} not found")

        conflict.resolution_strategy = strategy
        conflict.resolved_by = resolved_by
        conflict.resolved_at = get_current_utc_time()
        conflict.status = ConflictStatus.RESOLVED
        db.flush()

        logger.info(
            f"Conflict resolved: id={conflict_id}, strategy={strategy}, "
            f"resolved_by={resolved_by}"
        )
        return conflict

    def apply_resolution(
        self,
        conflict: SyncConflict,
        source_data: dict[str, Any],
        target_data: dict[str, Any],
    ) -> dict[str, Any]:
        if conflict.resolution_strategy == ResolutionStrategy.SOURCE_WINS:
            return source_data
        elif conflict.resolution_strategy == ResolutionStrategy.TARGET_WINS:
            return target_data
        else:
            raise ValueError(f"Manual resolution required for conflict {conflict.id}")


class ConflictResolver:
    def __init__(self, db: Session):
        self.db = db

    def apply_resolution(
        self,
        conflict: SyncConflict,
        source_data: dict[str, Any],
        target_data: dict[str, Any],
    ) -> dict[str, Any]:
        strategy = conflict.resolution_strategy

        if strategy == ResolutionStrategy.SOURCE_WINS:
            return source_data
        elif strategy == ResolutionStrategy.TARGET_WINS:
            return target_data
        elif hasattr(ResolutionStrategy, "LAST_WRITE_WINS") and strategy == ResolutionStrategy.LAST_WRITE_WINS:
            return source_data
        elif hasattr(ResolutionStrategy, "MERGE") and strategy == ResolutionStrategy.MERGE:
            return self._merge_data(source_data, target_data)
        elif hasattr(ResolutionStrategy, "REJECT") and strategy == ResolutionStrategy.REJECT:
            return target_data
        else:
            return target_data

    def _merge_data(
        self,
        source_data: dict[str, Any],
        target_data: dict[str, Any],
    ) -> dict[str, Any]:
        merged = target_data.copy()
        for key, value in source_data.items():
            if key in merged:
                if isinstance(merged[key], (int, float)) and isinstance(value, (int, float)):
                    merged[key] = max(merged[key], value)
                elif value is not None:
                    merged[key] = value
            else:
                merged[key] = value
        return merged

    def resolve_conflict(
        self,
        conflict_id: int,
        strategy: ResolutionStrategy,
        resolved_by: Optional[int] = None,
    ) -> SyncConflict:
        conflict = self.db.get(SyncConflict, conflict_id)
        if not conflict:
            raise ValueError(f"Conflict {conflict_id} not found")

        conflict.resolution_strategy = strategy
        conflict.resolved_by = resolved_by
        conflict.resolved_at = get_current_utc_time()
        conflict.status = ConflictStatus.RESOLVED
        self.db.flush()

        logger.info(f"Conflict resolved: id={conflict_id}, strategy={strategy}")
        return conflict


class ConsistencyChecker:
    @staticmethod
    def verify_record_consistency(
        source_data: dict[str, Any],
        target_data: dict[str, Any],
        fields: Optional[list[str]] = None,
    ) -> tuple[bool, dict[str, Any]]:
        if fields is None:
            fields = ["quantity", "reserved_quantity", "allocated_quantity", "unit_cost"]

        inconsistencies = {}
        for field in fields:
            source_val = source_data.get(field)
            target_val = target_data.get(field)
            if source_val != target_val:
                inconsistencies[field] = {"source": source_val, "target": target_val}

        return len(inconsistencies) == 0, inconsistencies

    @staticmethod
    def calculate_checksum(inventory_list: list[dict[str, Any]]) -> str:
        sorted_data = sorted(inventory_list, key=lambda x: (x.get("sku_id", 0), x.get("warehouse_id", 0)))
        data_str = json.dumps(sorted_data, sort_keys=True, default=str)
        return hashlib.md5(data_str.encode("utf-8")).hexdigest()

    def full_consistency_check(
        self,
        source_inventories: list[dict[str, Any]],
        target_inventories: list[dict[str, Any]],
    ) -> dict[str, Any]:
        source_map = {f"{inv['sku_id']}_{inv['warehouse_id']}_{inv['zone_id']}": inv for inv in source_inventories}
        target_map = {f"{inv['sku_id']}_{inv['warehouse_id']}_{inv['zone_id']}": inv for inv in target_inventories}

        all_keys = set(source_map.keys()) | set(target_map.keys())

        missing_in_source = []
        missing_in_target = []
        inconsistent = []
        consistent = []

        for key in all_keys:
            source = source_map.get(key)
            target = target_map.get(key)

            if source is None:
                missing_in_source.append(key)
            elif target is None:
                missing_in_target.append(key)
            else:
                is_consistent, diffs = self.verify_record_consistency(source, target)
                if is_consistent:
                    consistent.append(key)
                else:
                    inconsistent.append({"key": key, "diffs": diffs})

        source_checksum = self.calculate_checksum(source_inventories)
        target_checksum = self.calculate_checksum(target_inventories)

        return {
            "total_records": len(all_keys),
            "consistent_count": len(consistent),
            "inconsistent_count": len(inconsistent),
            "missing_in_source_count": len(missing_in_source),
            "missing_in_target_count": len(missing_in_target),
            "source_checksum": source_checksum,
            "target_checksum": target_checksum,
            "checksum_match": source_checksum == target_checksum,
            "inconsistent_records": inconsistent,
            "missing_in_source": missing_in_source,
            "missing_in_target": missing_in_target,
        }


class SyncDelayMonitor:
    def __init__(self, threshold_seconds: int = SYNC_DELAY_THRESHOLD_SECONDS):
        self.threshold_seconds = threshold_seconds

    def calculate_delay(self, last_sync_at: Optional[datetime]) -> int:
        if last_sync_at is None:
            return 0
        return calculate_seconds_between(last_sync_at)

    def check_delay(
        self,
        source_warehouse_id: int,
        target_warehouse_id: int,
        last_sync_at: Optional[datetime],
    ) -> tuple[int, bool]:
        delay = self.calculate_delay(last_sync_at)
        exceeded = delay > self.threshold_seconds

        if exceeded:
            logger.warning(
                f"Sync delay exceeded: source={source_warehouse_id}, "
                f"target={target_warehouse_id}, delay={delay}s, "
                f"threshold={self.threshold_seconds}s"
            )
            raise SyncDelayAlertException(
                source_warehouse_id=source_warehouse_id,
                target_warehouse_id=target_warehouse_id,
                delay_seconds=delay,
                threshold=self.threshold_seconds,
            )

        return delay, exceeded

    def get_last_sync_time(self, db: Session, source_id: int, target_id: int) -> Optional[datetime]:
        last_sync = (
            db.query(InventorySync)
            .filter(
                and_(
                    InventorySync.source_warehouse_id == source_id,
                    InventorySync.target_warehouse_id == target_id,
                    InventorySync.sync_status == "COMPLETED",
                )
            )
            .order_by(InventorySync.completed_at.desc())
            .first()
        )
        return last_sync.completed_at if last_sync else None

    def monitor_sync_pair(
        self,
        db: Session,
        source_id: int,
        target_id: int,
    ) -> dict[str, Any]:
        last_sync_at = self.get_last_sync_time(db, source_id, target_id)
        delay = self.calculate_delay(last_sync_at)
        exceeded = delay > self.threshold_seconds

        return {
            "source_warehouse_id": source_id,
            "target_warehouse_id": target_id,
            "last_sync_at": last_sync_at,
            "current_delay_seconds": delay,
            "threshold_seconds": self.threshold_seconds,
            "delay_exceeded": exceeded,
        }


def create_sync_engine(db: Session) -> tuple[CDCCaptureEngine, ConflictDetectionEngine, ConsistencyChecker, SyncDelayMonitor]:
    cdc_engine = CDCCaptureEngine(db)
    conflict_engine = ConflictDetectionEngine()
    consistency_engine = ConsistencyChecker()
    delay_monitor = SyncDelayMonitor()
    return cdc_engine, conflict_engine, consistency_engine, delay_monitor
