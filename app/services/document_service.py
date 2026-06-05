import json
from typing import List, Optional, Dict, Any, Tuple
from datetime import datetime
from sqlalchemy import and_, or_

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.document import (
    DocumentCreate,
    DocumentUpdate,
    ProcessingOptions,
    DocumentTypeEnum,
    DocumentStatusEnum,
    DocumentPriorityEnum,
)
from app.schemas.extraction import ExtractionSchema
from app.schemas.review import ReviewPriorityEnum
from app.models.document import Document, DocumentStatus
from app.models.extraction import ExtractionResult
from app.core.database import get_sync_db

logger = get_logger(__name__)
settings = get_settings()


class DocumentService:
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
        from app.ml.preprocessing import DocumentPreprocessor
        from app.ml.extractor import MultimodalExtractor
        from app.services.storage import StorageService
        from app.services.validation_service import ValidationService
        from app.services.review_service import ReviewService

        self.preprocessor = DocumentPreprocessor()
        self.extractor = MultimodalExtractor()
        self.storage = StorageService()
        self.validation_service = ValidationService()
        self.review_service = ReviewService()

    def create_document(
        self,
        file_data: bytes,
        original_filename: str,
        mime_type: Optional[str] = None,
        priority: DocumentPriorityEnum = DocumentPriorityEnum.MEDIUM,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Document:
        from app.ml.parsers import ParserFactory

        doc_type = ParserFactory.detect_document_type(original_filename, mime_type)

        document_create = DocumentCreate(
            filename=original_filename,
            document_type=doc_type,
            status=DocumentStatusEnum.UPLOADED,
            priority=priority,
            metadata=metadata or {},
        )

        db = next(get_sync_db())
        try:
            doc = Document(**document_create.model_dump())
            db.add(doc)
            db.flush()

            storage_path = f"documents/{doc.id}/{original_filename}"
            self.storage.upload_bytes(storage_path, file_data, settings.MINIO_BUCKET_DOCUMENTS)

            doc.storage_path = storage_path
            doc.file_size = len(file_data)
            doc.uploaded_at = datetime.utcnow()
            db.commit()
            db.refresh(doc)

            logger.info(f"Created document {doc.id}: {original_filename} ({doc_type.value}, {len(file_data)} bytes)")
            return doc

        except Exception as e:
            logger.error(f"Failed to create document: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_document(self, document_id: int) -> Optional[Document]:
        db = next(get_sync_db())
        try:
            return db.query(Document).filter(Document.id == document_id).first()
        finally:
            db.close()

    def get_document_with_details(self, document_id: int) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if not doc:
                return None

            extraction_results = db.query(ExtractionResult).filter(
                ExtractionResult.document_id == document_id
            ).order_by(ExtractionResult.created_at.desc()).all()

            return {
                "id": doc.id,
                "filename": doc.filename,
                "document_type": doc.document_type.value,
                "status": doc.status.value,
                "priority": doc.priority.value,
                "file_size": doc.file_size,
                "page_count": doc.page_count,
                "storage_path": doc.storage_path,
                "uploaded_at": doc.uploaded_at,
                "processing_started_at": doc.processing_started_at,
                "processing_completed_at": doc.processing_completed_at,
                "processing_metadata": doc.processing_metadata,
                "metadata": doc.metadata,
                "extraction_results": [
                    {
                        "id": er.id,
                        "status": er.status.value,
                        "model_version": er.model_version,
                        "schema_name": er.schema_name,
                        "schema_version": er.schema_version,
                        "field_count": len(er.fields) if er.fields else 0,
                        "created_at": er.created_at,
                    }
                    for er in extraction_results
                ],
            }
        finally:
            db.close()

    def list_documents(
        self,
        status: Optional[DocumentStatusEnum] = None,
        doc_type: Optional[DocumentTypeEnum] = None,
        priority: Optional[DocumentPriorityEnum] = None,
        uploaded_after: Optional[datetime] = None,
        uploaded_before: Optional[datetime] = None,
        search: Optional[str] = None,
        page: int = 1,
        page_size: int = 20,
    ) -> Tuple[List[Document], int]:
        db = next(get_sync_db())
        try:
            query = db.query(Document)

            if status:
                query = query.filter(Document.status == status)
            if doc_type:
                query = query.filter(Document.document_type == doc_type)
            if priority:
                query = query.filter(Document.priority == priority)
            if uploaded_after:
                query = query.filter(Document.uploaded_at >= uploaded_after)
            if uploaded_before:
                query = query.filter(Document.uploaded_at <= uploaded_before)
            if search:
                query = query.filter(Document.filename.ilike(f"%{search}%"))

            query = query.order_by(
                Document.priority.desc(),
                Document.uploaded_at.desc(),
            )

            total = query.count()
            documents = query.offset((page - 1) * page_size).limit(page_size).all()

            return documents, total

        finally:
            db.close()

    def update_document(
        self,
        document_id: int,
        update: DocumentUpdate,
    ) -> Optional[Document]:
        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if not doc:
                return None

            for field, value in update.model_dump(exclude_unset=True).items():
                setattr(doc, field, value)

            db.commit()
            db.refresh(doc)

            logger.info(f"Updated document {document_id}")
            return doc

        except Exception as e:
            logger.error(f"Failed to update document {document_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def delete_document(self, document_id: int) -> bool:
        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if not doc:
                return False

            try:
                self.storage.delete_file(doc.storage_path, settings.MINIO_BUCKET_DOCUMENTS)
            except Exception as e:
                logger.warning(f"Failed to delete file from storage: {e}")

            db.delete(doc)
            db.commit()

            logger.info(f"Deleted document {document_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to delete document {document_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def process_document(
        self,
        document_id: int,
        options: Optional[ProcessingOptions] = None,
        extraction_schema: Optional[ExtractionSchema] = None,
        model_version: Optional[str] = None,
    ) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if not doc:
                raise ValueError(f"Document not found: {document_id}")

            options = options or ProcessingOptions()
            schema = extraction_schema or self.extractor.get_default_insurance_schema()

            doc.status = DocumentStatus.PROCESSING
            doc.processing_started_at = datetime.utcnow()
            if not doc.processing_metadata:
                doc.processing_metadata = {}
            doc.processing_metadata["options"] = options.model_dump()
            db.commit()

            logger.info(f"Started processing document {document_id}: {doc.filename}")

            standardized_doc = self.preprocessor.process_document(
                document_id,
                options=options,
            )

            if options.run_layout_analysis:
                from app.ml.layout_analyzer import LayoutAnalyzer
                layout_analyzer = LayoutAnalyzer()
                layout_result = layout_analyzer.analyze_layout(standardized_doc)

                doc.processing_metadata["layout_result"] = {
                    "region_count": len(layout_result.regions),
                    "tree_depth": layout_result.document_tree.depth if layout_result.document_tree else 0,
                }
                db.commit()

            if options.extract_tables:
                from app.ml.table_extractor import TableExtractor
                table_extractor = TableExtractor()
                from app.ml.layout_analyzer import LayoutAnalyzer, LayoutAnalyzerResult
                layout_analyzer = LayoutAnalyzer()
                layout_result = layout_analyzer.analyze_layout(standardized_doc)
                tables = table_extractor.extract_tables(
                    document_id=document_id,
                    standardized_doc=standardized_doc,
                    layout_result=layout_result,
                )

                doc.processing_metadata["tables_extracted"] = len(tables)
                db.commit()

            extraction_result = None
            if options.run_extraction:
                from app.ml.extractor import ExtractionContext
                context = ExtractionContext(
                    document_id=document_id,
                    standardized_doc=standardized_doc,
                    schema=schema,
                    options=options,
                    model_version=model_version,
                )

                extraction_result = self.extractor.extract_fields(context)

            doc.status = DocumentStatus.PROCESSED
            doc.processing_completed_at = datetime.utcnow()
            doc.processing_metadata["completed"] = True
            db.commit()

            if extraction_result:
                validation_result = self.validation_service.validate_extraction_result(
                    extraction_result_id=extraction_result.id,
                )

                if validation_result.get("needs_review"):
                    self.review_service.create_review_task(
                        document_id=document_id,
                        extraction_result_id=extraction_result.id,
                        priority=ReviewPriorityEnum.MEDIUM if doc.priority == DocumentPriorityEnum.MEDIUM else ReviewPriorityEnum.HIGH,
                    )
                    doc.status = DocumentStatus.NEEDS_REVIEW
                    db.commit()
                else:
                    doc.status = DocumentStatus.COMPLETED
                    db.commit()

            logger.info(f"Completed processing document {document_id}")

            return {
                "document_id": document_id,
                "status": doc.status.value,
                "standardized_doc": standardized_doc.model_dump() if options.return_standardized else None,
                "extraction_result": extraction_result.model_dump() if extraction_result and options.return_extraction else None,
                "page_count": len(standardized_doc.pages),
                "processing_time_seconds": (doc.processing_completed_at - doc.processing_started_at).total_seconds(),
            }

        except Exception as e:
            logger.error(f"Failed to process document {document_id}: {e}", exc_info=True)
            db.rollback()

            doc = db.query(Document).filter(Document.id == document_id).first()
            if doc:
                doc.status = DocumentStatus.FAILED
                doc.processing_metadata = doc.processing_metadata or {}
                doc.processing_metadata["error"] = str(e)
                db.commit()

            raise
        finally:
            db.close()

    def get_document_file(self, document_id: int) -> Optional[Tuple[str, bytes]]:
        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if not doc or not doc.storage_path:
                return None

            file_data = self.storage.download_file_bytes(
                doc.storage_path,
                settings.MINIO_BUCKET_DOCUMENTS,
            )

            return doc.filename, file_data

        finally:
            db.close()

    def get_document_file_url(self, document_id: int, expires_in: int = 3600) -> Optional[str]:
        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if not doc or not doc.storage_path:
                return None

            return self.storage.get_file_url(
                doc.storage_path,
                settings.MINIO_BUCKET_DOCUMENTS,
                expires_in,
            )

        finally:
            db.close()

    def reprocess_document(
        self,
        document_id: int,
        options: Optional[ProcessingOptions] = None,
    ) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if not doc:
                raise ValueError(f"Document not found: {document_id}")

            doc.status = DocumentStatus.QUEUED
            doc.processing_started_at = None
            doc.processing_completed_at = None
            doc.processing_metadata = doc.processing_metadata or {}
            doc.processing_metadata["reprocess_count"] = doc.processing_metadata.get("reprocess_count", 0) + 1
            db.commit()

            logger.info(f"Reprocessing document {document_id} (attempt {doc.processing_metadata['reprocess_count']})")

            return self.process_document(document_id, options)

        except Exception as e:
            logger.error(f"Failed to reprocess document {document_id}: {e}", exc_info=True)
            raise
        finally:
            db.close()

    def get_processing_status(self, document_id: int) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if not doc:
                return None

            status = doc.status.value
            percent_complete = 0

            if doc.status == DocumentStatus.PROCESSING:
                percent_complete = 50
            elif doc.status in [DocumentStatus.PROCESSED, DocumentStatus.NEEDS_REVIEW, DocumentStatus.COMPLETED]:
                percent_complete = 100
            elif doc.status == DocumentStatus.FAILED:
                percent_complete = -1

            processing_time = None
            if doc.processing_started_at and doc.processing_completed_at:
                processing_time = (doc.processing_completed_at - doc.processing_started_at).total_seconds()
            elif doc.processing_started_at:
                processing_time = (datetime.utcnow() - doc.processing_started_at).total_seconds()

            return {
                "document_id": document_id,
                "status": status,
                "percent_complete": percent_complete,
                "processing_time_seconds": processing_time,
                "page_count": doc.page_count,
                "error": doc.processing_metadata.get("error") if doc.processing_metadata else None,
            }

        finally:
            db.close()
