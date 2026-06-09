import os
import tempfile
import uuid
from typing import Optional, Dict, Any, Tuple
from pathlib import Path

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.document import (
    StandardizedDocument,
    DocumentTypeEnum,
    DocumentCreate,
    ProcessingOptions,
)
from app.ml.parsers import ParserFactory
from app.ml.ocr_engine import OCREngine
from app.ml.document_type_detector import DocumentTypeDetector, DocumentTypeDetectionResult
from app.core.database import get_sync_db
from app.models.document import Document, DocumentStatus
from app.services.storage import StorageService

logger = get_logger(__name__)
settings = get_settings()


class DocumentPreprocessor:
    def __init__(
        self,
        ocr_engine: Optional[OCREngine] = None,
        storage_service: Optional[StorageService] = None,
        document_type_detector: Optional[DocumentTypeDetector] = None,
    ):
        self.ocr_engine = ocr_engine or OCREngine()
        self.storage_service = storage_service or StorageService()
        self.document_type_detector = document_type_detector or DocumentTypeDetector()
        self.parser_factory = ParserFactory()

    def process_document(
        self,
        document_id: int,
        options: Optional[ProcessingOptions] = None,
    ) -> StandardizedDocument:
        options = options or ProcessingOptions()

        db = next(get_sync_db())
        try:
            doc_record = db.query(Document).filter(Document.id == document_id).first()
            if not doc_record:
                raise ValueError(f"Document not found: {document_id}")

            doc_record.status = DocumentStatus.PREPROCESSING
            db.commit()

            local_file_path = self._download_document(doc_record)

            doc_type = self.parser_factory.detect_document_type(
                doc_record.original_filename, doc_record.mime_type
            )
            doc_record.document_type = doc_type.value

            detection_result = self.document_type_detector.detect_document_type(
                local_file_path,
                doc_record.original_filename,
                doc_record.mime_type,
            )

            optimized_options = self._apply_document_type_options(options, detection_result)

            parser = self._get_optimized_parser(
                doc_type,
                detection_result,
                optimized_options,
            )

            standardized_doc = parser.parse(
                local_file_path,
                doc_record.original_filename,
            )
            standardized_doc.document_id = document_id

            doc_record.page_count = standardized_doc.page_count
            doc_record.preprocessing_metadata = {
                "parser": standardized_doc.metadata.get("parser"),
                "preprocessing_time": standardized_doc.preprocessing_time,
                "ocr_used": any(p.ocr_confidence is not None for p in standardized_doc.pages),
                "ocr_engines": [self.ocr_engine.get_ocr_metadata()],
                "document_type_detection": detection_result.to_dict(),
                "processing_path": detection_result.optimal_processing_path,
            }
            db.commit()

            processed_data_path = self._save_processed_data(
                document_id, standardized_doc
            )
            doc_record.status = DocumentStatus.PREPROCESSED
            db.commit()

            self._cleanup_temp_file(local_file_path)

            logger.info(
                f"Document {document_id} preprocessing complete. "
                f"Pages: {standardized_doc.page_count}, "
                f"Text blocks: {sum(len(p.text_blocks) for p in standardized_doc.pages)}, "
                f"Processing path: {detection_result.optimal_processing_path}, "
                f"OCR used: {doc_record.preprocessing_metadata['ocr_used']}"
            )

            return standardized_doc

        except Exception as e:
            logger.error(f"Preprocessing failed for document {document_id}: {e}", exc_info=True)
            if doc_record:
                doc_record.status = DocumentStatus.FAILED
                doc_record.error_message = str(e)
                import traceback
                doc_record.error_stack = traceback.format_exc()
                db.commit()
            raise
        finally:
            db.close()

    def _apply_document_type_options(
        self,
        base_options: ProcessingOptions,
        detection_result: DocumentTypeDetectionResult,
    ) -> ProcessingOptions:
        optimized = ProcessingOptions(**base_options.model_dump())

        if optimized.use_ocr is None:
            optimized.use_ocr = detection_result.recommended_ocr

        if optimized.use_ocr and detection_result.has_text_layer and not detection_result.is_scanned_document:
            if detection_result.text_layer_quality >= 0.8:
                optimized.use_ocr = False
                logger.info(f"Skipping OCR due to high quality text layer (quality: {detection_result.text_layer_quality:.2f})")

        if optimized.detect_layout is None:
            optimized.detect_layout = detection_result.has_tables or detection_result.has_handwriting

        if optimized.detect_tables is None:
            optimized.detect_tables = detection_result.has_tables

        return optimized

    def _get_optimized_parser(
        self,
        doc_type,
        detection_result: DocumentTypeDetectionResult,
        options: ProcessingOptions,
    ):
        if detection_result.optimal_processing_path == "text_only":
            if hasattr(self.parser_factory, "get_text_only_parser"):
                parser = self.parser_factory.get_text_only_parser(doc_type)
                if parser:
                    return parser

        if detection_result.optimal_processing_path == "ocr_only":
            if hasattr(self.parser_factory, "get_ocr_only_parser"):
                parser = self.parser_factory.get_ocr_only_parser(doc_type, self.ocr_engine)
                if parser:
                    return parser

        if not options.use_ocr:
            if hasattr(self.parser_factory, "get_text_only_parser"):
                parser = self.parser_factory.get_text_only_parser(doc_type)
                if parser:
                    logger.info("Using text-only parser (OCR disabled by options)")
                    return parser

        return self.parser_factory.get_parser(doc_type, self.ocr_engine)

    def _download_document(self, doc_record: Document) -> str:
        if doc_record.minio_bucket and doc_record.minio_object_name:
            temp_dir = tempfile.mkdtemp(prefix="doc_download_")
            file_extension = Path(doc_record.original_filename).suffix
            local_path = os.path.join(temp_dir, f"document_{doc_record.id}{file_extension}")

            self.storage_service.download_file(
                bucket_name=doc_record.minio_bucket,
                object_name=doc_record.minio_object_name,
                file_path=local_path,
            )
            return local_path
        else:
            if os.path.exists(doc_record.storage_path):
                return doc_record.storage_path
            raise ValueError(f"Document storage path not found: {doc_record.storage_path}")

    def _save_processed_data(
        self,
        document_id: int,
        standardized_doc: StandardizedDocument,
    ) -> str:
        import json

        temp_dir = tempfile.mkdtemp(prefix="processed_")
        data_path = os.path.join(temp_dir, f"processed_{document_id}.json")

        with open(data_path, "w", encoding="utf-8") as f:
            json.dump(standardized_doc.model_dump(), f, ensure_ascii=False, indent=2)

        object_name = f"processed/{document_id}/standardized.json"
        self.storage_service.upload_file(
            bucket_name=settings.MINIO_PROCESSED_BUCKET,
            object_name=object_name,
            file_path=data_path,
        )

        return data_path

    def _cleanup_temp_file(self, file_path: str) -> None:
        try:
            temp_dir = os.path.dirname(file_path)
            if "doc_download_" in temp_dir or "processed_" in temp_dir:
                import shutil
                shutil.rmtree(temp_dir, ignore_errors=True)
        except Exception as e:
            logger.debug(f"Failed to cleanup temp file: {e}")

    def process_from_file(
        self,
        file_path: str,
        original_filename: str,
        mime_type: Optional[str] = None,
    ) -> StandardizedDocument:
        doc_type = self.parser_factory.detect_document_type(original_filename, mime_type)
        parser = self.parser_factory.get_parser(doc_type, self.ocr_engine)
        return parser.parse(file_path, original_filename)

    def create_document_record(
        self,
        file_data: bytes,
        original_filename: str,
        mime_type: Optional[str] = None,
        uploaded_by: Optional[str] = None,
        client_id: Optional[str] = None,
        claim_number: Optional[str] = None,
    ) -> Tuple[Document, str]:
        db = next(get_sync_db())
        try:
            file_extension = Path(original_filename).suffix
            file_uuid = str(uuid.uuid4())
            storage_filename = f"{file_uuid}{file_extension}"

            temp_file = tempfile.NamedTemporaryFile(delete=False, suffix=file_extension)
            temp_file.write(file_data)
            temp_file.flush()
            temp_file_path = temp_file.name
            temp_file.close()

            object_name = f"raw/{file_uuid}/{storage_filename}"
            self.storage_service.upload_file(
                bucket_name=settings.MINIO_RAW_BUCKET,
                object_name=object_name,
                file_path=temp_file_path,
            )

            doc_type = self.parser_factory.detect_document_type(original_filename, mime_type)

            doc_create = DocumentCreate(
                original_filename=original_filename,
                filename=storage_filename,
                storage_path=temp_file_path,
                mime_type=mime_type,
                file_size=len(file_data),
                document_type=doc_type,
                uploaded_by=uploaded_by,
                client_id=client_id,
                claim_number=claim_number,
                minio_bucket=settings.MINIO_RAW_BUCKET,
                minio_object_name=object_name,
            )

            doc_record = Document(
                **doc_create.model_dump(),
            )
            db.add(doc_record)
            db.commit()
            db.refresh(doc_record)

            self._cleanup_temp_file(temp_file_path)

            logger.info(f"Created document record: {doc_record.id} for {original_filename}")
            return doc_record, object_name

        except Exception as e:
            logger.error(f"Failed to create document record: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_standardized_document(self, document_id: int) -> Optional[StandardizedDocument]:
        import json

        object_name = f"processed/{document_id}/standardized.json"
        try:
            data_bytes = self.storage_service.download_file_bytes(
                bucket_name=settings.MINIO_PROCESSED_BUCKET,
                object_name=object_name,
            )
            data = json.loads(data_bytes.decode("utf-8"))
            return StandardizedDocument(**data)
        except Exception as e:
            logger.debug(f"Processed data not found for document {document_id}: {e}")
            return None

    def update_document_status(self, document_id: int, status: DocumentStatus, **kwargs) -> None:
        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if doc:
                doc.status = status
                for key, value in kwargs.items():
                    if hasattr(doc, key):
                        setattr(doc, key, value)
                db.commit()
        finally:
            db.close()
