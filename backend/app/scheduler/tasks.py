from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from datetime import datetime
import logging
from app.core.config import get_settings
from app.core.database import SessionLocal
from app.core.utils import get_week_key

settings = get_settings()
logger = logging.getLogger("scheduler")

scheduler = BackgroundScheduler(timezone="Asia/Shanghai")


def _monday_first_reminder_job():
    from app.services.notification import send_monday_first_reminder
    logger.info(f"[{datetime.now()}] Running Monday first reminder job")
    try:
        db = SessionLocal()
        send_monday_first_reminder(db)
        db.close()
    except Exception as e:
        logger.error(f"Monday reminder failed: {e}")


def _wednesday_followup_job():
    from app.services.notification import send_wednesday_followup_reminder
    logger.info(f"[{datetime.now()}] Running Wednesday follow-up reminder job")
    try:
        db = SessionLocal()
        send_wednesday_followup_reminder(db)
        db.close()
    except Exception as e:
        logger.error(f"Wednesday reminder failed: {e}")


def _friday_urgent_job():
    from app.services.notification import send_friday_urgent_reminder
    logger.info(f"[{datetime.now()}] Running Friday urgent reminder job")
    try:
        db = SessionLocal()
        send_friday_urgent_reminder(db)
        db.close()
    except Exception as e:
        logger.error(f"Friday reminder failed: {e}")


def _generate_weekly_summary_job():
    logger.info(f"[{datetime.now()}] Running weekly summary generation job")
    try:
        db = SessionLocal()
        from app.api.summaries import _build_summary_for_week
        from app.services.notification import broadcast_weekly_summary
        from app.api.export import _summary_to_markdown, _summary_to_html, _send_email, _generate_pdf
        import os

        week_key = get_week_key()
        summary = _build_summary_for_week(db, week_key)

        try:
            fname = _generate_pdf(summary)
            from app.api.export import EXPORT_DIR
            summary.pdf_path = os.path.join(EXPORT_DIR, fname)
            db.commit()
        except Exception as e:
            logger.warning(f"PDF generation failed: {e}")

        md = _summary_to_markdown(summary)
        html = _summary_to_html(summary)
        recipients = settings.report_email_list
        if recipients:
            subject = f"[周报汇总] {summary.content.get('week_display', week_key)}"
            _send_email(recipients, subject, html, summary.pdf_path)

        broadcast_weekly_summary(db, summary)
        summary.status = "distributed"
        db.commit()
        db.close()
        logger.info(f"Weekly summary for {week_key} generated and distributed")
    except Exception as e:
        logger.error(f"Weekly summary generation failed: {e}", exc_info=True)


def start_scheduler():
    settings = get_settings()

    scheduler.add_job(
        _monday_first_reminder_job,
        CronTrigger(day_of_week='mon',
                    hour=settings.REMINDER_MONDAY_HOUR,
                    minute=settings.REMINDER_MONDAY_MINUTE),
        id='monday_first_reminder',
        replace_existing=True,
        misfire_grace_time=3600
    )

    scheduler.add_job(
        _wednesday_followup_job,
        CronTrigger(day_of_week='wed',
                    hour=settings.REMINDER_WEDNESDAY_HOUR,
                    minute=settings.REMINDER_WEDNESDAY_MINUTE),
        id='wednesday_followup',
        replace_existing=True,
        misfire_grace_time=3600
    )

    scheduler.add_job(
        _friday_urgent_job,
        CronTrigger(day_of_week='fri',
                    hour=settings.REMINDER_FRIDAY_HOUR,
                    minute=settings.REMINDER_FRIDAY_MINUTE),
        id='friday_urgent',
        replace_existing=True,
        misfire_grace_time=3600
    )

    scheduler.add_job(
        _generate_weekly_summary_job,
        CronTrigger(day_of_week='fri',
                    hour=settings.SUMMARY_FRIDAY_HOUR,
                    minute=settings.SUMMARY_FRIDAY_MINUTE),
        id='weekly_summary_generation',
        replace_existing=True,
        misfire_grace_time=7200
    )

    try:
        scheduler.start()
        logger.info("Scheduler started successfully")
        jobs = scheduler.get_jobs()
        for j in jobs:
            logger.info(f"  - Scheduled job: {j.id} -> next run: {j.next_run_time}")
    except Exception as e:
        logger.error(f"Scheduler start failed: {e}")


def stop_scheduler():
    if scheduler.running:
        scheduler.shutdown(wait=False)
        logger.info("Scheduler stopped")


def list_scheduled_jobs():
    jobs = scheduler.get_jobs()
    return [{"id": j.id, "next_run_time": str(j.next_run_time) if j.next_run_time else None} for j in jobs]
