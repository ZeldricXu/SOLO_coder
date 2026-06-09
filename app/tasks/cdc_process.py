from typing import Optional
from sqlalchemy.orm import Session

from app.tasks.celery_app import celery_app
from app.core.database import SessionLocal
from app.core.logging import get_logger
from app.models.cdc import CDCEventStatus, CDCEventType
from app.services.inventory_sync_service import InventorySyncService

logger = get_logger(__name__)


@celery_app.task(bind=True, max_retries=3, default_retry_delay=30)
def process_pending_cdc_events(self, batch_size: int = 100) -> dict:
    db = SessionLocal()
    try:
        from app.models.cdc import CDCEvent

        pending_events = (
            db.query(CDCEvent)
            .filter(CDCEvent.status == CDCEventStatus.PENDING)
            .order_by(CDCEvent.created_at.asc())
            .limit(batch_size)
            .all()
        )

        if not pending_events:
            return {"status": "no_pending_events", "processed": 0}

        sync_service = InventorySyncService(db)

        success_count = 0
        failed_count = 0
        errors = []

        for event in pending_events:
            try:
                process_single_cdc_event(event, sync_service)
                event.status = CDCEventStatus.PROCESSED
                success_count += 1
            except Exception as e:
                event.status = CDCEventStatus.FAILED
                event.error_message = str(e)
                failed_count += 1
                errors.append({"event_id": event.id, "error": str(e)})

        db.commit()

        return {
            "status": "completed",
            "processed": success_count + failed_count,
            "success": success_count,
            "failed": failed_count,
            "errors": errors[:20],
        }
    except Exception as e:
        logger.error("Process pending CDC events failed", error=str(e))
        db.rollback()
        self.retry(exc=e)
    finally:
        db.close()


def process_single_cdc_event(event, sync_service) -> None:
    if event.event_type == CDCEventType.INVENTORY_CHANGED:
        cdc_log = event.cdc_log
        if cdc_log and cdc_log.new_data:
            sku_id = cdc_log.new_data.get("sku_id")
            warehouse_id = cdc_log.new_data.get("warehouse_id")
            if sku_id and warehouse_id:
                pass

    elif event.event_type == CDCEventType.ORDER_CREATED:
        pass

    else:
        logger.warning("Unknown CDC event type", event_type=event.event_type)


@celery_app.task
def process_external_cdc(
    table_name: str,
    operation: str,
    record_id: int,
    old_data: Optional[dict] = None,
    new_data: Optional[dict] = None,
    source_system: str = "ERP",
) -> dict:
    db = SessionLocal()
    try:
        from app.models.cdc import CDCLog, CDCEvent, CDCOperation

        cdc_log = CDCLog(
            table_name=table_name,
            operation=CDCOperation(operation),
            record_id=record_id,
            old_data=old_data,
            new_data=new_data,
            source_system=source_system,
            processed=False,
        )
        db.add(cdc_log)
        db.flush()

        event_type = None
        if table_name == "inventory" and operation in ["INSERT", "UPDATE"]:
            event_type = CDCEventType.INVENTORY_CHANGED
        elif table_name == "purchase_order" and operation == "INSERT":
            event_type = CDCEventType.ORDER_CREATED

        if event_type:
            cdc_event = CDCEvent(
                cdc_log_id=cdc_log.id,
                event_type=event_type,
                status=CDCEventStatus.PENDING,
            )
            db.add(cdc_event)

        db.commit()

        return {
            "status": "recorded",
            "cdc_log_id": cdc_log.id,
            "event_type": event_type.value if event_type else None,
        }
    except Exception as e:
        logger.error("Process external CDC failed", error=str(e))
        db.rollback()
        return {"status": "failed", "error": str(e)}
    finally:
        db.close()
