from typing import Optional
from celery import group

from app.tasks.celery_app import celery_app
from app.core.database import SessionLocal
from app.core.logging import get_logger
from app.services.alert_service import AlertService

logger = get_logger(__name__)


@celery_app.task(bind=True, max_retries=3, default_retry_delay=60)
def check_all_alerts(self, sku_ids: Optional[list[int]] = None) -> dict:
    db = SessionLocal()
    try:
        alert_service = AlertService(db)

        active_rules = alert_service.get_active_rules()

        if not active_rules:
            return {"status": "no_active_rules", "alert_count": 0}

        alert_tasks = []
        for rule in active_rules:
            alert_tasks.append(check_rule_alerts.s(rule.id, sku_ids))

        job = group(alert_tasks)()
        results = job.get()

        total_alerts = sum(r.get("alert_count", 0) for r in results)
        failed_rules = sum(1 for r in results if r.get("status") == "failed")

        return {
            "status": "completed",
            "rules_checked": len(active_rules),
            "total_alerts": total_alerts,
            "failed_rules": failed_rules,
            "results": results,
        }
    except Exception as e:
        logger.error("Check all alerts failed", error=str(e))
        self.retry(exc=e)
    finally:
        db.close()


@celery_app.task(bind=True, max_retries=2, default_retry_delay=30)
def check_rule_alerts(self, rule_id: int, sku_ids: Optional[list[int]] = None) -> dict:
    db = SessionLocal()
    try:
        alert_service = AlertService(db)
        alerts = alert_service.check_rule(rule_id, sku_ids)

        if alerts:
            send_alert_notifications.delay([a.id for a in alerts])

        return {
            "status": "completed",
            "rule_id": rule_id,
            "alert_count": len(alerts),
        }
    except Exception as e:
        logger.error("Check rule alerts failed", rule_id=rule_id, error=str(e))
        return {"status": "failed", "rule_id": rule_id, "error": str(e)}
    finally:
        db.close()


@celery_app.task(bind=True, max_retries=3)
def send_alert_notifications(self, alert_ids: list[int]) -> dict:
    db = SessionLocal()
    try:
        alert_service = AlertService(db)

        success_count = 0
        failed_count = 0
        errors = []

        for alert_id in alert_ids:
            try:
                alert_service.send_notifications(alert_id)
                success_count += 1
            except Exception as notify_error:
                failed_count += 1
                errors.append({"alert_id": alert_id, "error": str(notify_error)})

        return {
            "status": "completed",
            "success_count": success_count,
            "failed_count": failed_count,
            "errors": errors,
        }
    except Exception as e:
        logger.error("Send alert notifications failed", error=str(e))
        self.retry(exc=e)
    finally:
        db.close()


@celery_app.task
def send_notification(
    channel: str,
    recipients: list[str],
    title: str,
    message: str,
    data: Optional[dict] = None,
) -> dict:
    from app.core.config import settings
    import httpx

    try:
        if channel == "email":
            return send_email(recipients, title, message, data)
        elif channel == "webhook":
            for url in recipients:
                try:
                    httpx.post(
                        url,
                        json={
                            "title": title,
                            "message": message,
                            "data": data,
                        },
                        timeout=10,
                    )
                except Exception as e:
                    logger.error("Webhook notification failed", url=url, error=str(e))
            return {"status": "sent", "channel": "webhook", "count": len(recipients)}
        elif channel in ["dingtalk", "wechat", "telegram"]:
            return send_instant_message(channel, recipients, title, message)
        else:
            return {"status": "unknown_channel", "channel": channel}
    except Exception as e:
        logger.error("Send notification failed", channel=channel, error=str(e))
        return {"status": "failed", "channel": channel, "error": str(e)}


def send_email(recipients: list[str], title: str, message: str, data: Optional[dict] = None) -> dict:
    from app.core.config import settings
    import smtplib
    from email.mime.text import MIMEText
    from email.mime.multipart import MIMEMultipart

    if not settings.SMTP_USER or not settings.SMTP_PASSWORD:
        return {"status": "skipped", "reason": "SMTP not configured"}

    msg = MIMEMultipart()
    msg["From"] = settings.SMTP_FROM_EMAIL
    msg["To"] = ", ".join(recipients)
    msg["Subject"] = title

    html_content = f"<p>{message}</p>"
    if data:
        html_content += "<ul>"
        for k, v in data.items():
            html_content += f"<li><strong>{k}:</strong> {v}</li>"
        html_content += "</ul>"

    msg.attach(MIMEText(html_content, "html"))

    try:
        with smtplib.SMTP(settings.SMTP_HOST, settings.SMTP_PORT) as server:
            server.starttls()
            server.login(settings.SMTP_USER, settings.SMTP_PASSWORD)
            server.send_message(msg)
        return {"status": "sent", "channel": "email", "recipients": recipients}
    except Exception as e:
        raise e


def send_instant_message(
    channel: str, recipients: list[str], title: str, message: str
) -> dict:
    from app.core.config import settings
    import httpx

    full_message = f"**{title}**\n\n{message}"

    if channel == "telegram" and settings.TELEGRAM_BOT_TOKEN:
        for chat_id in recipients:
            try:
                httpx.post(
                    f"https://api.telegram.org/bot{settings.TELEGRAM_BOT_TOKEN}/sendMessage",
                    json={"chat_id": chat_id, "text": full_message, "parse_mode": "Markdown"},
                    timeout=10,
                )
            except Exception as e:
                logger.error("Telegram notification failed", chat_id=chat_id, error=str(e))
        return {"status": "sent", "channel": "telegram", "count": len(recipients)}

    return {"status": "sent", "channel": channel}


@celery_app.task
def auto_resolve_alerts() -> dict:
    db = SessionLocal()
    try:
        alert_service = AlertService(db)
        resolved_count = alert_service.auto_resolve_recovered_alerts()
        return {
            "status": "completed",
            "auto_resolved_count": resolved_count,
        }
    except Exception as e:
        logger.error("Auto resolve alerts failed", error=str(e))
        return {"status": "failed", "error": str(e)}
    finally:
        db.close()
