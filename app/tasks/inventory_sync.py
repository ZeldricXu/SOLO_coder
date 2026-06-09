from typing import Optional
from celery import group
from sqlalchemy.orm import Session

from app.tasks.celery_app import celery_app
from app.core.database import SessionLocal
from app.core.logging import get_logger
from app.models.inventory_sync import SyncStatus, SyncType
from app.models.warehouse import Warehouse
from app.services.inventory_sync_service import InventorySyncService

logger = get_logger(__name__)


@celery_app.task(bind=True, max_retries=3, default_retry_delay=60)
def run_incremental_sync(self, source_warehouse_id: Optional[int] = None) -> dict:
    db = SessionLocal()
    try:
        sync_service = InventorySyncService(db)

        warehouses = db.query(Warehouse).filter(Warehouse.is_active == True).all()
        warehouse_pairs = []

        for source in warehouses:
            for target in warehouses:
                if source.id != target.id:
                    if source_warehouse_id is None or source.id == source_warehouse_id:
                        warehouse_pairs.append((source.id, target.id))

        if not warehouse_pairs:
            return {"status": "no_active_warehouses", "sync_count": 0}

        sync_tasks = []
        for source_id, target_id in warehouse_pairs:
            sync_tasks.append(
                sync_warehouse_pair.s(source_id, target_id, SyncType.INCREMENTAL.value)
            )

        job = group(sync_tasks)()
        results = job.get()

        success_count = sum(1 for r in results if r.get("status") == "completed")
        failed_count = len(results) - success_count

        return {
            "status": "completed",
            "total_pairs": len(warehouse_pairs),
            "success_count": success_count,
            "failed_count": failed_count,
            "results": results,
        }
    except Exception as e:
        logger.error("Incremental sync failed", error=str(e))
        self.retry(exc=e)
    finally:
        db.close()


@celery_app.task(bind=True, max_retries=3, default_retry_delay=120)
def sync_warehouse_pair(
    self, source_warehouse_id: int, target_warehouse_id: int, sync_type: str
) -> dict:
    db = SessionLocal()
    try:
        sync_service = InventorySyncService(db)

        sync_record = sync_service.create_sync(
            source_warehouse_id=source_warehouse_id,
            target_warehouse_id=target_warehouse_id,
            sync_type=SyncType(sync_type),
        )

        try:
            result = sync_service.execute_sync(sync_record.id)
            return {
                "status": "completed",
                "sync_id": sync_record.id,
                "source": source_warehouse_id,
                "target": target_warehouse_id,
                "record_count": result.get("record_count", 0),
                "success_count": result.get("success_count", 0),
                "failed_count": result.get("failed_count", 0),
                "conflict_count": result.get("conflict_count", 0),
            }
        except Exception as sync_error:
            sync_service.update_sync_status(
                sync_record.id,
                SyncStatus.FAILED,
                error_message=str(sync_error),
            )
            return {
                "status": "failed",
                "sync_id": sync_record.id,
                "source": source_warehouse_id,
                "target": target_warehouse_id,
                "error": str(sync_error),
            }
    except Exception as e:
        logger.error(
            "Sync warehouse pair failed",
            source=source_warehouse_id,
            target=target_warehouse_id,
            error=str(e),
        )
        self.retry(exc=e)
    finally:
        db.close()


@celery_app.task(bind=True, max_retries=2)
def run_full_sync(self, source_warehouse_id: int, target_warehouse_id: int) -> dict:
    return sync_warehouse_pair(source_warehouse_id, target_warehouse_id, SyncType.FULL.value)


@celery_app.task
def resolve_conflict(conflict_id: int, resolution_strategy: str, resolved_by: int) -> dict:
    db = SessionLocal()
    try:
        sync_service = InventorySyncService(db)
        conflict = sync_service.resolve_conflict(
            conflict_id=conflict_id,
            resolution_strategy=resolution_strategy,
            resolved_by=resolved_by,
        )
        return {
            "status": "resolved",
            "conflict_id": conflict_id,
            "resolution_strategy": resolution_strategy,
        }
    except Exception as e:
        logger.error("Resolve conflict failed", conflict_id=conflict_id, error=str(e))
        return {"status": "failed", "conflict_id": conflict_id, "error": str(e)}
    finally:
        db.close()


@celery_app.task
def check_sync_delays() -> dict:
    db = SessionLocal()
    try:
        sync_service = InventorySyncService(db)
        delays = sync_service.check_sync_delays()

        if delays:
            for delay in delays:
                logger.warning(
                    "Sync delay detected",
                    source=delay["source_warehouse_id"],
                    target=delay["target_warehouse_id"],
                    delay_seconds=delay["delay_seconds"],
                    threshold=delay["threshold"],
                )

        return {
            "status": "completed",
            "delays_detected": len(delays),
            "delays": delays,
        }
    except Exception as e:
        logger.error("Check sync delays failed", error=str(e))
        return {"status": "failed", "error": str(e)}
    finally:
        db.close()
