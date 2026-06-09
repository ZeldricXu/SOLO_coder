import io
import zipfile
import os
from typing import List, Optional, Dict, Any, Tuple
from datetime import datetime
from sqlalchemy import and_, func

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.batch import (
    BatchJobCreate,
    BatchJobUpdate,
    BatchStatusEnum,
    BatchPriorityEnum,
    BatchDocumentStatusEnum,
)
from app.schemas.document import DocumentTypeEnum, DocumentPriorityEnum
from app.models.batch import BatchJob, BatchDocument
from app.models.document import Document, DocumentStatus
from app.core.database import get_sync_db

logger = get_logger(__name__)
settings = get_settings()


class BatchService:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        from app.services.storage import StorageService
        from app.services.document_service import DocumentService

        self.storage = StorageService()
        self.document_service = DocumentService()

    def create_batch_from_zip(
        self,
        zip_data: bytes,
        job_name: str,
        priority: BatchPriorityEnum = BatchPriorityEnum.MEDIUM,
        document_priority: DocumentPriorityEnum = DocumentPriorityEnum.MEDIUM,
        job_metadata: Optional[Dict[str, Any]] = None,
    ) -> BatchJob:
        db = next(get_sync_db())
        try:
            documents_info = self._extract_zip_contents(zip_data)

            if not documents_info:
                raise ValueError("No valid documents found in ZIP file")

            batch_create = BatchJobCreate(
                job_name=job_name,
                priority=priority,
                job_metadata=job_metadata or {},
            )

            batch = BatchJob(**batch_create.model_dump())
            batch.total_documents = len(documents_info)
            db.add(batch)
            db.flush()

            zip_storage_path = f"batches/{batch.id}/upload.zip"
            self.storage.upload_bytes(
                zip_storage_path,
                zip_data,
                settings.MINIO_BUCKET_BATCHES,
            )
            batch.storage_path = zip_storage_path

            for doc_info in documents_info:
                try:
                    doc = self.document_service.create_document(
                        file_data=doc_info["content"],
                        original_filename=doc_info["filename"],
                        priority=document_priority,
                        metadata={
                            "batch_id": batch.id,
                            "original_path": doc_info["original_path"],
                        },
                    )

                    batch_doc = BatchDocument(
                        batch_id=batch.id,
                        document_id=doc.id,
                        filename=doc_info["filename"],
                        status=BatchDocumentStatusEnum.PENDING,
                        position=doc_info["position"],
                    )
                    db.add(batch_doc)

                except Exception as e:
                    logger.warning(
                        f"Failed to create document {doc_info['filename']} from batch: {e}"
                    )
                    batch_doc = BatchDocument(
                        batch_id=batch.id,
                        filename=doc_info["filename"],
                        status=BatchDocumentStatusEnum.FAILED,
                        position=doc_info["position"],
                        error_message=str(e),
                    )
                    db.add(batch_doc)

            db.flush()

            total_pending = db.query(BatchDocument).filter(
                and_(
                    BatchDocument.batch_id == batch.id,
                    BatchDocument.status == BatchDocumentStatusEnum.PENDING,
                )
            ).count()

            total_failed = db.query(BatchDocument).filter(
                and_(
                    BatchDocument.batch_id == batch.id,
                    BatchDocument.status == BatchDocumentStatusEnum.FAILED,
                )
            ).count()

            batch.pending_documents = total_pending
            batch.failed_documents = total_failed
            batch.total_documents = total_pending + total_failed

            if total_pending == 0:
                batch.status = BatchStatusEnum.FAILED
            else:
                batch.status = BatchStatusEnum.QUEUED

            db.commit()
            db.refresh(batch)

            logger.info(
                f"Created batch {batch.id}: {job_name} with {batch.total_documents} documents "
                f"({total_pending} pending, {total_failed} failed)"
            )

            return batch

        except Exception as e:
            logger.error(f"Failed to create batch from ZIP: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def _extract_zip_contents(self, zip_data: bytes) -> List[Dict[str, Any]]:
        from app.ml.parsers import ParserFactory

        documents = []
        position = 0

        try:
            with zipfile.ZipFile(io.BytesIO(zip_data)) as zf:
                for info in zf.infolist():
                    if info.is_dir():
                        continue

                    filename = os.path.basename(info.filename)
                    if not filename or filename.startswith("."):
                        continue

                    try:
                        doc_type = ParserFactory.detect_document_type(filename)

                        if doc_type not in [
                            DocumentTypeEnum.PDF,
                            DocumentTypeEnum.WORD,
                            DocumentTypeEnum.IMAGE,
                            DocumentTypeEnum.TXT,
                        ]:
                            continue

                        with zf.open(info) as f:
                            content = f.read()

                        if len(content) == 0:
                            continue

                        documents.append({
                            "filename": filename,
                            "original_path": info.filename,
                            "content": content,
                            "doc_type": doc_type,
                            "position": position,
                        })
                        position += 1

                    except Exception as e:
                        logger.warning(f"Skipping file {info.filename}: {e}")
                        continue

        except zipfile.BadZipFile:
            raise ValueError("Invalid ZIP file")

        return documents

    def get_batch(self, batch_id: int) -> Optional[BatchJob]:
        db = next(get_sync_db())
        try:
            return db.query(BatchJob).filter(BatchJob.id == batch_id).first()
        finally:
            db.close()

    def get_batch_with_details(self, batch_id: int) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            batch = db.query(BatchJob).filter(BatchJob.id == batch_id).first()
            if not batch:
                return None

            batch_docs = db.query(BatchDocument).filter(
                BatchDocument.batch_id == batch_id
            ).order_by(BatchDocument.position).all()

            status_counts = db.query(
                BatchDocument.status,
                func.count(BatchDocument.id)
            ).filter(
                BatchDocument.batch_id == batch_id
            ).group_by(BatchDocument.status).all()

            status_summary = {status.value: count for status, count in status_counts}

            avg_processing_time = None
            completed = batch.completed_documents or 0
            total = batch.total_documents or 0
            progress = 0.0
            if total > 0:
                progress = (completed / total) * 100
            if completed > 0 and batch.processing_started_at and batch.processing_completed_at:
                total_time = (batch.processing_completed_at - batch.processing_started_at).total_seconds()
                avg_processing_time = total_time / completed

            return {
                "id": batch.id,
                "batch_name": batch.job_name,
                "status": batch.status.value,
                "priority": batch.priority.value if hasattr(batch.priority, 'value') else batch.priority,
                "total_documents": batch.total_documents,
                "pending_documents": batch.pending_documents,
                "processing_documents": batch.processing_documents,
                "completed_documents": batch.completed_documents,
                "failed_documents": batch.failed_documents,
                "review_documents": batch.needs_review_documents,
                "storage_path": batch.zip_file_path,
                "created_at": batch.created_at,
                "processing_started_at": batch.processing_started_at,
                "processing_completed_at": batch.processing_completed_at,
                "metadata": batch.job_metadata,
                "status_summary": status_summary,
                "average_processing_time": avg_processing_time,
                "progress": progress,
                "completed": completed,
                "documents": [
                    {
                        "id": bd.id,
                        "document_id": bd.document_id,
                        "filename": bd.filename,
                        "status": bd.status.value,
                        "position": bd.position,
                        "started_at": bd.started_at,
                        "completed_at": bd.completed_at,
                        "error_message": bd.error_message,
                    }
                    for bd in batch_docs
                ],
            }

        finally:
            db.close()

    def list_batches(
        self,
        status: Optional[BatchStatusEnum] = None,
        priority: Optional[BatchPriorityEnum] = None,
        page: int = 1,
        page_size: int = 20,
    ) -> Tuple[List[Dict[str, Any]], int]:
        db = next(get_sync_db())
        try:
            query = db.query(BatchJob)

            if status:
                query = query.filter(BatchJob.status == status)
            if priority:
                query = query.filter(BatchJob.priority == priority)

            query = query.order_by(
                BatchJob.priority.desc(),
                BatchJob.created_at.desc(),
            )

            total = query.count()
            batches = query.offset((page - 1) * page_size).limit(page_size).all()

            result = []
            for batch in batches:
                elapsed_time = None
                if batch.processing_started_at:
                    if batch.processing_completed_at:
                        elapsed_time = (batch.processing_completed_at - batch.processing_started_at).total_seconds()
                    else:
                        elapsed_time = (datetime.utcnow() - batch.processing_started_at).total_seconds()

                result.append({
                    "id": batch.id,
                    "batch_name": batch.batch_name,
                    "status": batch.status.value,
                    "priority": batch.priority.value,
                    "total_documents": batch.total_documents,
                    "completed_documents": batch.completed_documents,
                    "failed_documents": batch.failed_documents,
                    "progress_percent": (
                        (batch.completed_documents + batch.failed_documents) / batch.total_documents * 100
                        if batch.total_documents > 0 else 0
                    ),
                    "created_at": batch.created_at,
                    "elapsed_time_seconds": elapsed_time,
                })

            return result, total

        finally:
            db.close()

    def update_batch_status(
        self,
        batch_id: int,
        status: BatchStatusEnum,
    ) -> Optional[BatchJob]:
        db = next(get_sync_db())
        try:
            batch = db.query(BatchJob).filter(BatchJob.id == batch_id).first()
            if not batch:
                return None

            batch.status = status

            if status == BatchStatusEnum.PROCESSING and not batch.processing_started_at:
                batch.processing_started_at = datetime.utcnow()
            elif status in [BatchStatusEnum.COMPLETED, BatchStatusEnum.FAILED, BatchStatusEnum.CANCELLED]:
                batch.processing_completed_at = datetime.utcnow()

            db.commit()
            db.refresh(batch)

            logger.info(f"Updated batch {batch_id} status to {status.value}")
            return batch

        except Exception as e:
            logger.error(f"Failed to update batch {batch_id} status: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def update_batch_document_status(
        self,
        batch_id: int,
        document_id: int,
        status: BatchDocumentStatusEnum,
        error_message: Optional[str] = None,
    ) -> Optional[BatchDocument]:
        db = next(get_sync_db())
        try:
            batch_doc = db.query(BatchDocument).filter(
                and_(
                    BatchDocument.batch_id == batch_id,
                    BatchDocument.document_id == document_id,
                )
            ).first()

            if not batch_doc:
                return None

            batch_doc.status = status
            if status == BatchDocumentStatusEnum.PROCESSING:
                batch_doc.started_at = datetime.utcnow()
            elif status in [BatchDocumentStatusEnum.COMPLETED, BatchDocumentStatusEnum.FAILED, BatchDocumentStatusEnum.NEEDS_REVIEW]:
                batch_doc.completed_at = datetime.utcnow()

            if error_message:
                batch_doc.error_message = error_message

            db.commit()
            db.refresh(batch_doc)

            self._recalculate_batch_stats(batch_id, db)

            logger.info(
                f"Updated batch {batch_id} document {document_id} status to {status.value}"
            )

            return batch_doc

        except Exception as e:
            logger.error(f"Failed to update batch document status: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def _recalculate_batch_stats(self, batch_id: int, db) -> None:
        batch = db.query(BatchJob).filter(BatchJob.id == batch_id).first()
        if not batch:
            return

        pending = db.query(BatchDocument).filter(
            and_(
                BatchDocument.batch_id == batch_id,
                BatchDocument.status == BatchDocumentStatusEnum.PENDING,
            )
        ).count()

        processing = db.query(BatchDocument).filter(
            and_(
                BatchDocument.batch_id == batch_id,
                BatchDocument.status == BatchDocumentStatusEnum.PROCESSING,
            )
        ).count()

        completed = db.query(BatchDocument).filter(
            and_(
                BatchDocument.batch_id == batch_id,
                BatchDocument.status == BatchDocumentStatusEnum.COMPLETED,
            )
        ).count()

        failed = db.query(BatchDocument).filter(
            and_(
                BatchDocument.batch_id == batch_id,
                BatchDocument.status == BatchDocumentStatusEnum.FAILED,
            )
        ).count()

        review = db.query(BatchDocument).filter(
            and_(
                BatchDocument.batch_id == batch_id,
                BatchDocument.status == BatchDocumentStatusEnum.NEEDS_REVIEW,
            )
        ).count()

        batch.pending_documents = pending
        batch.processing_documents = processing
        batch.completed_documents = completed
        batch.failed_documents = failed
        batch.needs_review_documents = review

        if pending == 0 and processing == 0:
            if failed == batch.total_documents:
                batch.status = BatchStatusEnum.FAILED
            elif review > 0:
                batch.status = BatchStatusEnum.REVIEW
            else:
                batch.status = BatchStatusEnum.COMPLETED

            if not batch.processing_completed_at:
                batch.processing_completed_at = datetime.utcnow()

        db.commit()

    def get_batch_progress(self, batch_id: int) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            batch = db.query(BatchJob).filter(BatchJob.id == batch_id).first()
            if not batch:
                return None

            processed = (batch.completed_documents or 0) + (batch.failed_documents or 0)
            total = batch.total_documents or 0
            progress = (processed / total * 100) if total > 0 else 0

            elapsed_time = None
            if batch.processing_started_at:
                if batch.processing_completed_at:
                    elapsed_time = (batch.processing_completed_at - batch.processing_started_at).total_seconds()
                else:
                    elapsed_time = (datetime.utcnow() - batch.processing_started_at).total_seconds()

            eta_seconds = None
            if processed > 0 and elapsed_time and batch.processing_documents is not None:
                remaining = total - processed
                if remaining > 0:
                    rate = processed / elapsed_time
                    eta_seconds = remaining / rate

            return {
                "batch_id": batch.id,
                "status": batch.status.value,
                "progress_percent": progress,
                "total_documents": total,
                "pending_documents": batch.pending_documents or 0,
                "processing_documents": batch.processing_documents or 0,
                "completed_documents": batch.completed_documents or 0,
                "failed_documents": batch.failed_documents or 0,
                "review_documents": batch.review_documents or 0,
                "elapsed_time_seconds": elapsed_time,
                "eta_seconds": eta_seconds,
            }

        finally:
            db.close()

    def cancel_batch(self, batch_id: int) -> Optional[BatchJob]:
        db = next(get_sync_db())
        try:
            batch = db.query(BatchJob).filter(BatchJob.id == batch_id).first()
            if not batch:
                return None

            if batch.status in [BatchStatusEnum.COMPLETED, BatchStatusEnum.CANCELLED, BatchStatusEnum.FAILED]:
                return batch

            batch.status = BatchStatusEnum.CANCELLED
            batch.processing_completed_at = datetime.utcnow()

            pending_docs = db.query(BatchDocument).filter(
                and_(
                    BatchDocument.batch_id == batch_id,
                    BatchDocument.status == BatchDocumentStatusEnum.PENDING,
                )
            ).all()

            for doc in pending_docs:
                doc.status = BatchDocumentStatusEnum.CANCELLED

            db.commit()
            db.refresh(batch)

            logger.info(f"Cancelled batch {batch_id}")
            return batch

        except Exception as e:
            logger.error(f"Failed to cancel batch {batch_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def delete_batch(self, batch_id: int, delete_documents: bool = False) -> bool:
        db = next(get_sync_db())
        try:
            batch = db.query(BatchJob).filter(BatchJob.id == batch_id).first()
            if not batch:
                return False

            if delete_documents:
                batch_docs = db.query(BatchDocument).filter(
                    BatchDocument.batch_id == batch_id
                ).all()

                for bd in batch_docs:
                    if bd.document_id:
                        try:
                            self.document_service.delete_document(bd.document_id)
                        except Exception as e:
                            logger.warning(f"Failed to delete document {bd.document_id}: {e}")

            try:
                if batch.storage_path:
                    self.storage.delete_file(
                        batch.storage_path,
                        settings.MINIO_BUCKET_BATCHES,
                    )
            except Exception as e:
                logger.warning(f"Failed to delete batch file from storage: {e}")

            db.delete(batch)
            db.commit()

            logger.info(f"Deleted batch {batch_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to delete batch {batch_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_batch_statistics(
        self,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            query = db.query(BatchJob)

            if start_date:
                query = query.filter(BatchJob.created_at >= start_date)
            if end_date:
                query = query.filter(BatchJob.created_at <= end_date)

            total_batches = query.count()
            completed = query.filter(BatchJob.status == BatchStatusEnum.COMPLETED).count()
            failed = query.filter(BatchJob.status == BatchStatusEnum.FAILED).count()
            processing = query.filter(BatchJob.status == BatchStatusEnum.PROCESSING).count()
            queued = query.filter(BatchJob.status == BatchStatusEnum.QUEUED).count()

            total_documents = db.query(func.sum(BatchJob.total_documents)).filter(
                BatchJob.id.in_([b.id for b in query.all()])
            ).scalar() or 0

            avg_batch_size = total_documents / total_batches if total_batches > 0 else 0

            completed_batches = query.filter(
                and_(
                    BatchJob.status == BatchStatusEnum.COMPLETED,
                    BatchJob.processing_started_at.isnot(None),
                    BatchJob.processing_completed_at.isnot(None),
                )
            ).all()

            avg_processing_time = None
            if completed_batches:
                total_time = sum(
                    (b.processing_completed_at - b.processing_started_at).total_seconds()
                    for b in completed_batches
                )
                avg_processing_time = total_time / len(completed_batches)

            return {
                "total_batches": total_batches,
                "completed_batches": completed,
                "failed_batches": failed,
                "processing_batches": processing,
                "queued_batches": queued,
                "success_rate": completed / total_batches if total_batches > 0 else 0,
                "total_documents_processed": int(total_documents),
                "average_batch_size": float(avg_batch_size),
                "average_processing_time_seconds": float(avg_processing_time) if avg_processing_time else None,
            }

        finally:
            db.close()
