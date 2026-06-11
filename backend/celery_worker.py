from celery import Celery
from celery.schedules import crontab
from app.config import settings

celery_app = Celery(
    "traffic_viz",
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
    result_expires=86400,
    worker_prefetch_multiplier=1,
    worker_max_tasks_per_child=1000,
)

celery_app.autodiscover_tasks([
    "app.tiles",
    "app.prediction",
    "app.etl",
    "app.heatmap",
])

celery_app.conf.beat_schedule = {
    "predict-traffic-every-15-min": {
        "task": "prediction.batch_prediction_task",
        "schedule": crontab(minute="*/15"),
    },
    "cleanup-old-data-daily": {
        "task": "etl.cleanup_old_data_task",
        "schedule": crontab(hour=2, minute=0),
    },
    "refresh-heatmap-cache": {
        "task": "heatmap.refresh_heatmap_cache",
        "schedule": crontab(minute="*/5"),
    },
}
