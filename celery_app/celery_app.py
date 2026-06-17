import logging
from celery import Celery
from celery.schedules import crontab

from config.settings import settings

logger = logging.getLogger(__name__)

celery_app = Celery(
    "genome_variant_pipeline",
    broker=settings.celery.broker_url,
    backend=settings.celery.result_backend,
    include=[
        "celery_app.tasks",
    ],
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
    task_time_limit=72 * 3600,
    task_soft_time_limit=70 * 3600,
    worker_prefetch_multiplier=1,
    worker_max_tasks_per_child=100,
    result_expires=3600 * 24 * 7,
)

celery_app.conf.beat_schedule = {
    "cleanup-expired-data-daily": {
        "task": "celery_app.tasks.cleanup_expired_data",
        "schedule": crontab(hour=2, minute=0),
        "args": (),
    },
    "check-pending-tasks-every-5-minutes": {
        "task": "celery_app.tasks.check_and_queue_pending_tasks",
        "schedule": crontab(minute="*/5"),
        "args": (),
    },
}

celery_app.autodiscover_tasks()
