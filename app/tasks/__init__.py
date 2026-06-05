from app.tasks.celery_app import celery_app
from app.tasks.document import (
    process_document_task,
    process_document_high_priority_task,
    cleanup_old_tasks_task,
)
from app.tasks.batch import process_batch_task

__all__ = [
    "celery_app",
    "process_document_task",
    "process_document_high_priority_task",
    "process_batch_task",
    "cleanup_old_tasks_task",
]
