import asyncio
import json
from datetime import datetime
from typing import Optional, Dict, Any

from app.tasks.celery_app import celery_app
from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.document import ProcessingOptions, DocumentPriorityEnum
from app.schemas.extraction import ExtractionSchema
from app.services.document_service import DocumentService
from app.services.storage import StorageService
from app.services.ab_test_service import ABTestService

logger = get_logger(__name__)
settings = get_settings()


@celery_app.task(bind=True, name="app.tasks.document.process_document")
def process_document_task(
    self,
    document_id: int,
    options: Optional[Dict[str, Any]] = None,
    extraction_schema: Optional[Dict[str, Any]] = None,
    batch_id: Optional[int] = None,
) -> Dict[str, Any]:
    try:
        document_service = DocumentService()
        storage = StorageService()

        self.update_state(state="PROGRESS", meta={
            "document_id": document_id,
            "stage": "preprocessing",
            "progress": 10,
        })

        _send_progress_update(document_id, "preprocessing", 10, batch_id)

        proc_options = ProcessingOptions(**(options or {}))
        schema = ExtractionSchema(**extraction_schema) if extraction_schema else None

        ab_test_service = ABTestService()
        routing = ab_test_service.route_traffic(
            model_name=settings.DEFAULT_EXTRACTION_MODEL,
            document_id=document_id,
        )

        model_version = None
        if routing.get("version"):
            model_version = f"{routing['model_name']}=={routing['version']}"

        self.update_state(state="PROGRESS", meta={
            "document_id": document_id,
            "stage": "extracting",
            "progress": 50,
            "model_version": model_version,
        })
        _send_progress_update(document_id, "extracting", 50, batch_id)

        result = document_service.process_document(
            document_id=document_id,
            options=proc_options,
            extraction_schema=schema,
            model_version=model_version,
        )

        self.update_state(state="PROGRESS", meta={
            "document_id": document_id,
            "stage": "validating",
            "progress": 80,
        })
        _send_progress_update(document_id, "validating", 80, batch_id)

        self.update_state(state="SUCCESS", meta={
            "document_id": document_id,
            "stage": "completed",
            "progress": 100,
            "result": result,
        })
        _send_progress_update(document_id, "completed", 100, batch_id)

        if batch_id:
            from app.services.batch_service import BatchService
            from app.schemas.batch import BatchDocumentStatusEnum
            batch_service = BatchService()

            doc_status = BatchDocumentStatusEnum.COMPLETED
            if result.get("status") == "needs_review":
                doc_status = BatchDocumentStatusEnum.REVIEW
            elif result.get("status") == "failed":
                doc_status = BatchDocumentStatusEnum.FAILED

            batch_service.update_batch_document_status(
                batch_id=batch_id,
                document_id=document_id,
                status=doc_status,
            )

        if routing.get("experiment_id"):
            from app.services.ab_test_service import ABTestService
            from app.schemas.model import ABTestResultCreate

            ab_service = ABTestService()
            confidence = result.get("extraction_result", {}).get("confidence_score", 0.0) if result.get("extraction_result") else 0.0

            result_create = ABTestResultCreate(
                experiment_id=routing["experiment_id"],
                variant=routing["variant"],
                document_id=document_id,
                metric_name="confidence",
                metric_value=float(confidence),
            )
            ab_service.record_result(result_create)

        logger.info(f"Completed processing document {document_id}")
        return result

    except Exception as e:
        logger.error(f"Failed to process document {document_id}: {e}", exc_info=True)

        if batch_id:
            from app.services.batch_service import BatchService
            from app.schemas.batch import BatchDocumentStatusEnum
            batch_service = BatchService()
            batch_service.update_batch_document_status(
                batch_id=batch_id,
                document_id=document_id,
                status=BatchDocumentStatusEnum.FAILED,
                error_message=str(e),
            )

        _send_progress_update(document_id, "failed", -1, batch_id)

        self.update_state(state="FAILURE", meta={
            "document_id": document_id,
            "error": str(e),
        })
        raise


@celery_app.task(bind=True, name="app.tasks.document.process_document_high_priority")
def process_document_high_priority_task(
    self,
    document_id: int,
    options: Optional[Dict[str, Any]] = None,
    extraction_schema: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    return process_document_task(
        self,
        document_id,
        options,
        extraction_schema,
        None,
    )


@celery_app.task(name="app.tasks.document.cleanup_old_tasks")
def cleanup_old_tasks_task() -> Dict[str, Any]:
    from datetime import datetime, timedelta
    from app.core.database import get_sync_db
    from app.models.document import Document, DocumentStatus

    db = next(get_sync_db())
    try:
        cutoff = datetime.utcnow() - timedelta(days=settings.CELERY_RESULT_EXPIRES // 86400)

        from app.services.storage import StorageService
        storage = StorageService()

        old_docs = db.query(Document).filter(
            Document.created_at < cutoff,
            Document.status.in_([
                DocumentStatus.FAILED,
                DocumentStatus.COMPLETED,
            ])
        ).limit(1000).all()

        cleaned_count = 0
        for doc in old_docs:
            try:
                cache_key = f"doc:progress:{doc.id}"
                storage.cache_delete(cache_key)
                cleaned_count += 1
            except Exception:
                continue

        logger.info(f"Cleaned up {cleaned_count} old task entries")
        return {"cleaned_count": cleaned_count}

    finally:
        db.close()


def _send_progress_update(
    document_id: int,
    stage: str,
    progress: int,
    batch_id: Optional[int] = None,
) -> None:
    try:
        storage = StorageService()

        progress_data = {
            "document_id": document_id,
            "batch_id": batch_id,
            "stage": stage,
            "progress": progress,
            "timestamp": datetime.utcnow().isoformat(),
        }

        cache_key = f"doc:progress:{document_id}"
        storage.cache_set(cache_key, json.dumps(progress_data), 3600)

        if batch_id:
            channel = f"batch:{batch_id}:progress"
            try:
                storage.publish_message(channel, json.dumps(progress_data))
            except Exception:
                pass

    except Exception as e:
        logger.debug(f"Failed to send progress update: {e}")
