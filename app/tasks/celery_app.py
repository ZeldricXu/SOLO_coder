from celery import Celery
from celery.signals import task_failure, task_success, task_prerun, task_postrun
from celery.schedules import crontab

from app.core.config import settings
from app.core.logging import get_logger

logger = get_logger(__name__)

celery_app = Celery(
    "inventory_tasks",
    broker=settings.CELERY_BROKER_URL,
    backend=settings.CELERY_RESULT_BACKEND,
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="Asia/Shanghai",
    enable_utc=True,
    task_track_started=True,
    task_time_limit=3600,
    task_soft_time_limit=3300,
    worker_prefetch_multiplier=1,
    worker_max_tasks_per_child=1000,
    result_expires=86400,
    task_always_eager=settings.CELERY_TASK_ALWAYS_EAGER,
)

celery_app.autodiscover_tasks(["app.tasks"])

celery_app.conf.beat_schedule = {
    "inventory-sync-every-5-minutes": {
        "task": "app.tasks.inventory_sync.run_incremental_sync",
        "schedule": 300,
        "args": [],
    },
    "alert-check-every-10-minutes": {
        "task": "app.tasks.alert.check_all_alerts",
        "schedule": 600,
        "args": [],
    },
    "replenishment-check-daily": {
        "task": "app.tasks.replenishment.generate_daily_replenishment",
        "schedule": crontab(hour=2, minute=0),
        "args": [],
    },
    "forecast-update-weekly": {
        "task": "app.tasks.forecast.update_weekly_forecast",
        "schedule": crontab(hour=1, minute=0, day_of_week=0),
        "args": [],
    },
    "cdc-process-every-minute": {
        "task": "app.tasks.cdc_process.process_pending_cdc_events",
        "schedule": 60,
        "args": [],
    },
    "approval-timeout-check-every-30-minutes": {
        "task": "app.tasks.approval.check_approval_timeout",
        "schedule": 1800,
        "args": [],
    },
    "stocktake-reminder-daily": {
        "task": "app.tasks.stocktake.send_stocktake_reminders",
        "schedule": crontab(hour=9, minute=0),
        "args": [],
    },
}


@task_prerun.connect
def task_prerun_handler(sender, task_id, task, *args, **kwargs):
    logger.info(
        "Task starting",
        task_name=task.name,
        task_id=task_id,
        args=args,
    )


@task_postrun.connect
def task_postrun_handler(sender, task_id, task, retval, state, *args, **kwargs):
    logger.info(
        "Task completed",
        task_name=task.name,
        task_id=task_id,
        state=state,
    )


@task_success.connect
def task_success_handler(sender, result, *args, **kwargs):
    logger.info(
        "Task succeeded",
        task_name=sender.name,
        result=str(result)[:200],
    )


@task_failure.connect
def task_failure_handler(sender, task_id, exception, args, kwargs, traceback, einfo, *_args, **kw):
    logger.error(
        "Task failed",
        task_name=sender.name,
        task_id=task_id,
        exception=str(exception),
        traceback=traceback,
    )
