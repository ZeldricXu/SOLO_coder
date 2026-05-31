from __future__ import annotations

import os

from celery import Celery

broker_url = os.getenv("CELERY_BROKER_URL", "redis://localhost:6379/0")
result_backend = os.getenv("CELERY_RESULT_BACKEND", "redis://localhost:6379/0")

app = Celery(
    "streamsql",
    broker=broker_url,
    backend=result_backend,
    include=[
        "streamsql.workers.tasks",
    ],
)

app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
    task_time_limit=3600,
    task_soft_time_limit=3300,
    worker_prefetch_multiplier=1,
    worker_max_tasks_per_child=1000,
)

app.conf.beat_schedule = {
    "run-lifecycle-cycle-every-hour": {
        "task": "streamsql.workers.tasks.run_lifecycle_cycle",
        "schedule": 3600.0,
    },
    "cleanup-anomalies-every-day": {
        "task": "streamsql.workers.tasks.cleanup_anomalies",
        "schedule": 86400.0,
    },
}


@app.task(bind=True)
def debug_task(self):
    print(f"Request: {self.request!r}")
    return {"status": "ok", "task_id": self.request.id}


if __name__ == "__main__":
    app.start()
