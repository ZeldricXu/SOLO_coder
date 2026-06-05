import time
from typing import Optional, Dict, Any, List

from app.tasks.celery_app import celery_app
from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.batch import BatchStatusEnum, BatchDocumentStatusEnum
from app.services.batch_service import BatchService
from app.tasks.document import process_document_task

logger = get_logger(__name__)
settings = get_settings()


@celery_app.task(bind=True, name="app.tasks.batch.process_batch")
def process_batch_task(
    self,
    batch_id: int,
    options: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    try:
        batch_service = BatchService()

        batch = batch_service.get_batch(batch_id)
        if not batch:
            raise ValueError(f"Batch not found: {batch_id}")

        batch_service.update_batch_status(batch_id, BatchStatusEnum.PROCESSING)

        details = batch_service.get_batch_with_details(batch_id)
        if not details:
            raise ValueError(f"Batch details not found: {batch_id}")

        pending_docs = [
            doc for doc in details["documents"]
            if doc["status"] in [
                BatchDocumentStatusEnum.PENDING.value,
                BatchDocumentStatusEnum.QUEUED.value,
            ]
        ]

        total_docs = len(pending_docs)
        logger.info(f"Starting batch processing for batch {batch_id}: {total_docs} documents")

        concurrent_limit = settings.BATCH_CONCURRENCY_LIMIT
        active_tasks: List = []
        completed_count = 0
        failed_count = 0

        for i, doc in enumerate(pending_docs):
            while len(active_tasks) >= concurrent_limit:
                for j, task_info in enumerate(active_tasks):
                    if task_info["task"].ready():
                        try:
                            task_info["task"].get()
                            completed_count += 1
                        except Exception as e:
                            logger.error(f"Task failed for document {task_info['document_id']}: {e}")
                            failed_count += 1
                        active_tasks.pop(j)
                        break
                else:
                    time.sleep(0.5)

            document_id = doc["document_id"]
            if not document_id:
                continue

            batch_service.update_batch_document_status(
                batch_id=batch_id,
                document_id=document_id,
                status=BatchDocumentStatusEnum.QUEUED,
            )

            priority = batch.priority.value if hasattr(batch.priority, "value") else batch.priority
            task_name = "app.tasks.document.process_document"

            if priority == "high":
                task_name = "app.tasks.document.process_document_high_priority"

            task = celery_app.send_task(
                task_name,
                args=[document_id, options, None, batch_id],
                queue="high_priority" if priority == "high" else "batch",
            )

            active_tasks.append({
                "task": task,
                "document_id": document_id,
            })

            progress = int((i + 1) / total_docs * 100) if total_docs > 0 else 0
            self.update_state(state="PROGRESS", meta={
                "batch_id": batch_id,
                "total": total_docs,
                "completed": completed_count,
                "failed": failed_count,
                "active": len(active_tasks),
                "progress": progress,
            })

        while active_tasks:
            for j, task_info in enumerate(active_tasks):
                if task_info["task"].ready():
                    try:
                        task_info["task"].get()
                        completed_count += 1
                    except Exception as e:
                        logger.error(f"Task failed for document {task_info['document_id']}: {e}")
                        failed_count += 1
                    active_tasks.pop(j)
                    break
            else:
                time.sleep(0.5)

        progress = batch_service.get_batch_progress(batch_id)

        result = {
            "batch_id": batch_id,
            "total_documents": total_docs,
            "completed_documents": completed_count,
            "failed_documents": failed_count,
            "progress": progress,
        }

        logger.info(
            f"Completed batch processing for batch {batch_id}: "
            f"{completed_count} completed, {failed_count} failed"
        )

        return result

    except Exception as e:
        logger.error(f"Failed to process batch {batch_id}: {e}", exc_info=True)
        batch_service.update_batch_status(batch_id, BatchStatusEnum.FAILED)
        raise
