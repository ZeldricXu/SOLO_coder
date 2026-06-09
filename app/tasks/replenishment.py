from typing import Optional

from app.tasks.celery_app import celery_app
from app.core.database import SessionLocal
from app.core.logging import get_logger
from app.services.replenishment_service import ReplenishmentService

logger = get_logger(__name__)


@celery_app.task(bind=True, max_retries=3, default_retry_delay=300)
def generate_daily_replenishment(self) -> dict:
    db = SessionLocal()
    try:
        replenishment_service = ReplenishmentService(db)

        suggestions = replenishment_service.generate_suggestions(
            auto_create=False,
            consider_seasonality=True,
            consider_lead_time=True,
        )

        if suggestions:
            notify_replenishment_suggestions.delay(len(suggestions))

        return {
            "status": "completed",
            "suggestions_count": len(suggestions),
            "total_estimated_cost": sum(s.estimated_total_cost for s in suggestions if s.estimated_total_cost),
        }
    except Exception as e:
        logger.error("Daily replenishment generation failed", error=str(e))
        self.retry(exc=e)
    finally:
        db.close()


@celery_app.task
def notify_replenishment_suggestions(count: int) -> dict:
    from app.tasks.alert import send_notification

    return send_notification(
        channel="email",
        recipients=["procurement@company.com", "inventory@company.com"],
        title=f"补货建议已生成 - {count}条",
        message=f"系统已自动生成 {count} 条补货建议，请及时审核处理。",
        data={"suggestions_count": count},
    )


@celery_app.task(bind=True, max_retries=2)
def generate_replenishment_for_sku(self, sku_id: int, warehouse_id: Optional[int] = None) -> dict:
    db = SessionLocal()
    try:
        replenishment_service = ReplenishmentService(db)
        suggestions = replenishment_service.generate_suggestions(
            sku_ids=[sku_id],
            warehouse_ids=[warehouse_id] if warehouse_id else None,
            auto_create=True,
        )

        return {
            "status": "completed",
            "sku_id": sku_id,
            "warehouse_id": warehouse_id,
            "suggestions_count": len(suggestions),
            "suggestions": [
                {
                    "id": s.id,
                    "suggested_quantity": s.suggested_quantity,
                    "estimated_total_cost": float(s.estimated_total_cost) if s.estimated_total_cost else 0,
                }
                for s in suggestions
            ],
        }
    except Exception as e:
        logger.error("Generate replenishment for SKU failed", sku_id=sku_id, error=str(e))
        self.retry(exc=e)
    finally:
        db.close()


@celery_app.task
def convert_suggestion_to_po(suggestion_id: int, created_by: int) -> dict:
    db = SessionLocal()
    try:
        replenishment_service = ReplenishmentService(db)
        po = replenishment_service.convert_to_purchase_order(suggestion_id, created_by)

        return {
            "status": "converted",
            "suggestion_id": suggestion_id,
            "purchase_order_id": po.id,
            "purchase_order_no": po.order_no,
        }
    except Exception as e:
        logger.error(
            "Convert suggestion to PO failed",
            suggestion_id=suggestion_id,
            error=str(e),
        )
        return {"status": "failed", "suggestion_id": suggestion_id, "error": str(e)}
    finally:
        db.close()
