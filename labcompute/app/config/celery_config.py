from celery import Celery
from app.config.settings import settings

def make_celery() -> Celery:
    celery = Celery(
        "labcompute",
        broker=settings.CELERY_BROKER_URL,
        backend=settings.CELERY_RESULT_BACKEND,
        include=[
            "app.tasks.compute_tasks",
            "app.tasks.matrix_tasks",
            "app.tasks.ode_tasks",
            "app.tasks.stats_tasks"
        ]
    )
    
    celery.conf.update(
        task_serializer="json",
        accept_content=["json"],
        result_serializer="json",
        timezone="UTC",
        enable_utc=True,
        task_track_started=True,
        task_time_limit=settings.TASK_TIMEOUT,
        task_soft_time_limit=settings.TASK_TIMEOUT - 60,
        worker_prefetch_multiplier=1,
        task_acks_late=True,
        result_expires=86400,
    )
    
    return celery

celery_app = make_celery()
