
from app.tasks.celery_app import celery_app
from app.core.database import SessionLocal
from app.core.logging import get_logger
from app.services.stocktake_service import StocktakeService

logger = get_logger(__name__)


@celery_app.task
def send_stocktake_reminders() -> dict:
    db = SessionLocal()
    try:
        stocktake_service = StocktakeService(db)

        pending_tasks = stocktake_service.get_pending_tasks_for_today()

        if not pending_tasks:
            return {"status": "no_pending_tasks", "count": 0}

        for task in pending_tasks:
            if task.assignee_id:
                send_stocktake_task_reminder.delay(task.id, task.assignee_id)

        return {
            "status": "completed",
            "reminders_sent": len(pending_tasks),
        }
    except Exception as e:
        logger.error("Send stocktake reminders failed", error=str(e))
        return {"status": "failed", "error": str(e)}
    finally:
        db.close()


@celery_app.task
def send_stocktake_task_reminder(task_id: int, assignee_id: int) -> dict:
    from app.tasks.alert import send_notification
    from app.models.user import User

    db = SessionLocal()
    try:
        user = db.query(User).filter(User.id == assignee_id).first()
        if not user or not user.email:
            return {"status": "user_not_found", "assignee_id": assignee_id}

        return send_notification(
            channel="email",
            recipients=[user.email],
            title="盘点任务提醒",
            message="您有一个盘点任务待执行，请及时登录系统完成。",
            data={"task_id": task_id},
        )
    finally:
        db.close()


@celery_app.task(bind=True, max_retries=3, default_retry_delay=120)
def generate_cycle_stocktake_plan(self, warehouse_id: int, cycle_type: str = "ABC") -> dict:
    db = SessionLocal()
    try:
        stocktake_service = StocktakeService(db)
        plan = stocktake_service.generate_cycle_plan(warehouse_id, cycle_type)

        return {
            "status": "completed",
            "plan_id": plan.id,
            "plan_no": plan.plan_no,
            "task_count": len(plan.tasks) if hasattr(plan, "tasks") else 0,
        }
    except Exception as e:
        logger.error("Generate cycle stocktake plan failed", warehouse_id=warehouse_id, error=str(e))
        self.retry(exc=e)
    finally:
        db.close()


@celery_app.task
def process_stocktake_differences(plan_id: int, created_by: int) -> dict:
    db = SessionLocal()
    try:
        stocktake_service = StocktakeService(db)

        adjustments = stocktake_service.auto_generate_adjustments(plan_id, created_by)

        return {
            "status": "completed",
            "plan_id": plan_id,
            "adjustments_count": len(adjustments),
            "adjustments": [
                {
                    "id": adj.id,
                    "adjustment_type": adj.adjustment_type.value,
                    "quantity": adj.quantity,
                    "total_cost": float(adj.total_cost) if adj.total_cost else 0,
                }
                for adj in adjustments
            ],
        }
    except Exception as e:
        logger.error("Process stocktake differences failed", plan_id=plan_id, error=str(e))
        return {"status": "failed", "plan_id": plan_id, "error": str(e)}
    finally:
        db.close()


@celery_app.task
def execute_stocktake_adjustment(adjustment_id: int, executed_by: int) -> dict:
    db = SessionLocal()
    try:
        stocktake_service = StocktakeService(db)
        adjustment = stocktake_service.execute_adjustment(adjustment_id, executed_by)

        return {
            "status": "executed",
            "adjustment_id": adjustment_id,
            "adjustment_type": adjustment.adjustment_type.value,
            "quantity": adjustment.quantity,
        }
    except Exception as e:
        logger.error("Execute stocktake adjustment failed", adjustment_id=adjustment_id, error=str(e))
        return {"status": "failed", "adjustment_id": adjustment_id, "error": str(e)}
    finally:
        db.close()
