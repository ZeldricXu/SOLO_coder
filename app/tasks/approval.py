
from app.tasks.celery_app import celery_app
from app.core.database import SessionLocal
from app.core.logging import get_logger
from app.services.approval_service import ApprovalService

logger = get_logger(__name__)


@celery_app.task
def check_approval_timeout() -> dict:
    db = SessionLocal()
    try:
        approval_service = ApprovalService(db)

        timeout_records = approval_service.check_timeout_approvals(
            timeout_hours=48,
            action="remind",
        )

        if timeout_records:
            for record in timeout_records:
                send_approval_reminder.delay(record.id, record.approver_id)

        return {
            "status": "completed",
            "timeout_records": len(timeout_records),
        }
    except Exception as e:
        logger.error("Check approval timeout failed", error=str(e))
        return {"status": "failed", "error": str(e)}
    finally:
        db.close()


@celery_app.task
def send_approval_reminder(record_id: int, approver_id: int) -> dict:
    from app.tasks.alert import send_notification
    from app.models.user import User

    db = SessionLocal()
    try:
        user = db.query(User).filter(User.id == approver_id).first()
        if not user or not user.email:
            return {"status": "user_not_found", "approver_id": approver_id}

        return send_notification(
            channel="email",
            recipients=[user.email],
            title="审批待处理提醒",
            message="您有一条审批请求已超时未处理，请及时登录系统处理。",
            data={"approval_record_id": record_id},
        )
    finally:
        db.close()


@celery_app.task
def notify_approval_status(resource_type: str, resource_id: int, status: str) -> dict:
    from app.tasks.alert import send_notification
    from app.models.user import User

    db = SessionLocal()
    try:
        submitter = None

        if resource_type == "PURCHASE_ORDER":
            from app.models.purchase_order import PurchaseOrder
            po = db.query(PurchaseOrder).filter(PurchaseOrder.id == resource_id).first()
            if po:
                submitter = db.query(User).filter(User.id == po.created_by).first()

        if submitter and submitter.email:
            status_text = {
                "APPROVED": "已通过",
                "REJECTED": "已驳回",
                "PARTIAL_APPROVED": "部分通过",
            }.get(status, status)

            return send_notification(
                channel="email",
                recipients=[submitter.email],
                title=f"审批结果通知 - {status_text}",
                message=f"您提交的{resource_type}审批请求{status_text}。",
                data={"resource_type": resource_type, "resource_id": resource_id, "status": status},
            )

        return {"status": "no_submitter", "resource_type": resource_type, "resource_id": resource_id}
    finally:
        db.close()
