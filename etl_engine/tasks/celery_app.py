from celery import Celery

from etl_engine.config import settings

celery_app = Celery(
    "etl_engine",
    broker=settings.CELERY_BROKER_URL,
    backend=settings.CELERY_RESULT_BACKEND,
)

celery_app.conf.update(
    serializer="json",
    result_serializer="json",
    accept_content=["json"],
    task_serializer="json",
    result_expires=3600,
    task_track_started=True,
    task_acks_late=True,
    worker_prefetch_multiplier=1,
    task_routes={
        "etl_engine.tasks.etl_tasks.run_pipeline_task": {"queue": "pipelines"},
        "etl_engine.tasks.etl_tasks.test_source_connection_task": {"queue": "maintenance"},
        "etl_engine.tasks.etl_tasks.run_quality_check_task": {"queue": "quality"},
    },
    task_default_queue="default",
    broker_connection_retry_on_startup=True,
)

celery_app.autodiscover_tasks(["etl_engine.tasks"])
