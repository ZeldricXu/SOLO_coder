from celery import Celery
from kombu import Queue, Exchange

from app.core.config import get_settings

settings = get_settings()

celery_app = Celery(
    "docintel",
    broker=settings.REDIS_URL,
    backend=settings.REDIS_URL,
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
    task_time_limit=settings.CELERY_TASK_TIME_LIMIT,
    task_soft_time_limit=settings.CELERY_TASK_SOFT_TIME_LIMIT,
    worker_prefetch_multiplier=settings.CELERY_WORKER_PREFETCH_MULTIPLIER,
    worker_max_tasks_per_child=settings.CELERY_WORKER_MAX_TASKS_PER_CHILD,
    worker_concurrency=settings.CELERY_WORKER_CONCURRENCY,
)

default_exchange = Exchange("default", type="direct")
high_priority_exchange = Exchange("high_priority", type="direct")
batch_exchange = Exchange("batch", type="direct")

celery_app.conf.task_queues = (
    Queue(
        "high_priority",
        high_priority_exchange,
        routing_key="high_priority",
        queue_arguments={"x-max-priority": 10},
    ),
    Queue(
        "default",
        default_exchange,
        routing_key="default",
        queue_arguments={"x-max-priority": 5},
    ),
    Queue(
        "batch",
        batch_exchange,
        routing_key="batch",
        queue_arguments={"x-max-priority": 1},
    ),
)

celery_app.conf.task_routes = {
    "app.tasks.document.process_document": {
        "queue": "default",
        "routing_key": "default",
    },
    "app.tasks.document.process_document_high_priority": {
        "queue": "high_priority",
        "routing_key": "high_priority",
    },
    "app.tasks.batch.process_batch": {
        "queue": "batch",
        "routing_key": "batch",
    },
}

celery_app.autodiscover_tasks(["app.tasks"])
