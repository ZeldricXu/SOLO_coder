import json
from typing import List, Optional, Dict, Any, Tuple
from datetime import datetime
from sqlalchemy import and_

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.extraction import (
    ExtractionSchema,
    ExtractionResultCreate,
    ExtractedFieldCreate,
    ExtractionStatusEnum,
    FieldDataTypeEnum,
    FieldSchema,
)
from app.models.extraction import ExtractionResult, ExtractedField, ExtractionSchema as ExtractionSchemaModel
from app.models.document import Document, DocumentStatus
from app.core.database import get_sync_db
from app.ml.extractor import MultimodalExtractor
from app.services.validation_service import ValidationService
from app.services.review_service import ReviewService
from app.services.schema_service import ExtractionSchemaService

logger = get_logger(__name__)
settings = get_settings()


class ExtractionService:
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

        self.extractor = MultimodalExtractor()
        self.validation_service = ValidationService()
        self.review_service = ReviewService()
        self.schema_service = ExtractionSchemaService()

    def run_extraction(
        self,
        document_id: int,
        extraction_schema: Optional[ExtractionSchema] = None,
        schema_name: Optional[str] = None,
        schema_id: Optional[int] = None,
        business_line: Optional[str] = None,
        document_type: Optional[str] = None,
        model_version: Optional[str] = None,
        options: Optional[Dict[str, Any]] = None,
    ) -> ExtractionResult:
        from app.ml.extractor import ExtractionContext
        from app.ml.preprocessing import DocumentPreprocessor
        from app.schemas.document import ProcessingOptions

        db = next(get_sync_db())
        try:
            doc = db.query(Document).filter(Document.id == document_id).first()
            if not doc:
                raise ValueError(f"Document not found: {document_id}")

            schema = self._resolve_extraction_schema(
                extraction_schema=extraction_schema,
                schema_name=schema_name,
                schema_id=schema_id,
                business_line=business_line,
                document_type=document_type,
            )

            proc_options = ProcessingOptions(**(options or {}))

            preprocessor = DocumentPreprocessor()
            standardized_doc = preprocessor.process_document(
                document_id,
                options=proc_options,
            )

            context = ExtractionContext(
                document_id=document_id,
                standardized_doc=standardized_doc,
                schema=schema,
                options=proc_options,
                model_version=model_version,
            )

            extraction_result_data = self.extractor.extract_fields(context)

            schema_record = None
            if schema_name or schema_id:
                if schema_id:
                    schema_record = db.query(ExtractionSchemaModel).filter(
                        ExtractionSchemaModel.id == schema_id
                    ).first()
                elif schema_name:
                    schema_record = db.query(ExtractionSchemaModel).filter(
                        ExtractionSchemaModel.schema_name == schema_name
                    ).first()

            result_id = extraction_result_data.get("extraction_result_id")
            if result_id and schema_record:
                db_extraction_result = db.query(ExtractionResult).filter(
                    ExtractionResult.id == result_id
                ).first()
                if db_extraction_result:
                    db_extraction_result.schema_id = schema_record.id
                    db.commit()

            validation_result = self.validation_service.validate_extraction_result(
                extraction_result_id=result_id,
            )

            if validation_result.get("needs_review"):
                self.review_service.create_review_task(
                    document_id=document_id,
                    extraction_result_id=result_id,
                )
                doc.status = DocumentStatus.NEEDS_REVIEW
                db.commit()
            else:
                doc.status = DocumentStatus.COMPLETED
                db.commit()

            return extraction_result_data

        except Exception as e:
            logger.error(f"Failed to run extraction for document {document_id}: {e}", exc_info=True)
            raise
        finally:
            db.close()

    def _resolve_extraction_schema(
        self,
        extraction_schema: Optional[ExtractionSchema] = None,
        schema_name: Optional[str] = None,
        schema_id: Optional[int] = None,
        business_line: Optional[str] = None,
        document_type: Optional[str] = None,
    ) -> ExtractionSchema:
        if extraction_schema:
            logger.info(f"Using provided extraction schema: {extraction_schema.schema_name}")
            return extraction_schema

        if schema_id:
            schema_resp = self.schema_service.get_schema(schema_id)
            if schema_resp:
                logger.info(f"Using schema by ID {schema_id}: {schema_resp.schema_name}")
                return self._convert_schema_response_to_pydantic(schema_resp)

        if schema_name:
            schema_resp = self.schema_service.get_schema_by_name(schema_name)
            if schema_resp:
                logger.info(f"Using schema by name {schema_name}")
                return self._convert_schema_response_to_pydantic(schema_resp)

        if business_line or document_type:
            schema_resp = self.schema_service.get_schema_for_document(
                business_line=business_line,
                document_type=document_type,
            )
            if schema_resp:
                logger.info(
                    f"Using schema for business_line={business_line}, "
                    f"document_type={document_type}: {schema_resp.schema_name}"
                )
                return self._convert_schema_response_to_pydantic(schema_resp)

        logger.info("No schema specified, using default insurance schema")
        return self.extractor.get_default_insurance_schema()

    def _convert_schema_response_to_pydantic(self, schema_resp) -> ExtractionSchema:
        fields = []
        for field_data in schema_resp.fields:
            fields.append(FieldSchema(**field_data))

        return ExtractionSchema(
            schema_name=schema_resp.schema_name,
            schema_version=schema_resp.schema_version,
            description=schema_resp.description,
            document_types=schema_resp.document_types,
            fields=fields,
        )

    def get_extraction_result(
        self,
        extraction_result_id: int,
        include_fields: bool = True,
    ) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            result = db.query(ExtractionResult).filter(
                ExtractionResult.id == extraction_result_id
            ).first()

            if not result:
                return None

            response = {
                "id": result.id,
                "document_id": result.document_id,
                "status": result.status.value,
                "model_version": result.model_version,
                "schema_name": result.schema_name,
                "schema_version": result.schema_version,
                "extraction_time": result.extraction_time,
                "confidence_score": result.confidence_score,
                "error_message": result.error_message,
                "created_at": result.created_at,
                "completed_at": result.completed_at,
                "metadata": result.metadata,
            }

            if include_fields and result.fields:
                response["fields"] = [
                    {
                        "id": f.id,
                        "field_name": f.field_name,
                        "field_type": f.field_type.value,
                        "value": f.value,
                        "normalized_value": f.normalized_value,
                        "confidence": f.confidence,
                        "is_low_confidence": f.is_low_confidence,
                        "validation_status": f.validation_status.value if f.validation_status else "unchecked",
                        "validation_errors": f.validation_errors,
                        "validation_warnings": f.validation_warnings,
                        "suggested_value": f.suggested_value,
                        "page_number": f.page_number,
                        "bounding_box": f.bounding_box,
                        "extraction_method": f.extraction_method,
                        "reviewed": f.reviewed,
                        "reviewed_value": f.reviewed_value,
                    }
                    for f in result.fields
                ]

            return response

        finally:
            db.close()

    def get_latest_extraction_result(
        self,
        document_id: int,
    ) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            result = db.query(ExtractionResult).filter(
                ExtractionResult.document_id == document_id
            ).order_by(
                ExtractionResult.created_at.desc()
            ).first()

            if not result:
                return None

            return self.get_extraction_result(result.id)

        finally:
            db.close()

    def list_extraction_results(
        self,
        document_id: Optional[int] = None,
        status: Optional[ExtractionStatusEnum] = None,
        model_version: Optional[str] = None,
        page: int = 1,
        page_size: int = 20,
    ) -> Tuple[List[Dict[str, Any]], int]:
        db = next(get_sync_db())
        try:
            query = db.query(ExtractionResult)

            if document_id:
                query = query.filter(ExtractionResult.document_id == document_id)
            if status:
                query = query.filter(ExtractionResult.status == status)
            if model_version:
                query = query.filter(ExtractionResult.model_version == model_version)

            query = query.order_by(ExtractionResult.created_at.desc())

            total = query.count()
            results = query.offset((page - 1) * page_size).limit(page_size).all()

            response_list = [
                {
                    "id": r.id,
                    "document_id": r.document_id,
                    "status": r.status.value,
                    "model_version": r.model_version,
                    "schema_name": r.schema_name,
                    "schema_version": r.schema_version,
                    "confidence_score": r.confidence_score,
                    "field_count": len(r.fields) if r.fields else 0,
                    "created_at": r.created_at,
                }
                for r in results
            ]

            return response_list, total

        finally:
            db.close()

    def delete_extraction_result(
        self,
        extraction_result_id: int,
    ) -> bool:
        db = next(get_sync_db())
        try:
            result = db.query(ExtractionResult).filter(
                ExtractionResult.id == extraction_result_id
            ).first()

            if not result:
                return False

            db.delete(result)
            db.commit()

            logger.info(f"Deleted extraction result {extraction_result_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to delete extraction result {extraction_result_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def compare_extraction_results(
        self,
        result_id_a: int,
        result_id_b: int,
    ) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            result_a = db.query(ExtractionResult).filter(
                ExtractionResult.id == result_id_a
            ).first()
            result_b = db.query(ExtractionResult).filter(
                ExtractionResult.id == result_id_b
            ).first()

            if not result_a or not result_b:
                raise ValueError("One or both extraction results not found")

            fields_a = {f.field_name: f for f in result_a.fields} if result_a.fields else {}
            fields_b = {f.field_name: f for f in result_b.fields} if result_b.fields else {}

            all_field_names = set(fields_a.keys()) | set(fields_b.keys())

            field_comparisons = []
            identical_count = 0
            different_count = 0
            missing_a = 0
            missing_b = 0

            for field_name in sorted(all_field_names):
                fa = fields_a.get(field_name)
                fb = fields_b.get(field_name)

                comparison = {
                    "field_name": field_name,
                    "value_a": fa.value if fa else None,
                    "value_b": fb.value if fb else None,
                    "confidence_a": fa.confidence if fa else None,
                    "confidence_b": fb.confidence if fb else None,
                    "status": None,
                }

                if fa is None:
                    comparison["status"] = "missing_in_a"
                    missing_a += 1
                elif fb is None:
                    comparison["status"] = "missing_in_b"
                    missing_b += 1
                elif fa.value == fb.value:
                    comparison["status"] = "identical"
                    identical_count += 1
                else:
                    comparison["status"] = "different"
                    different_count += 1

                field_comparisons.append(comparison)

            return {
                "result_a": {
                    "id": result_a.id,
                    "model_version": result_a.model_version,
                    "confidence_score": result_a.confidence_score,
                    "field_count": len(fields_a),
                },
                "result_b": {
                    "id": result_b.id,
                    "model_version": result_b.model_version,
                    "confidence_score": result_b.confidence_score,
                    "field_count": len(fields_b),
                },
                "comparison": {
                    "total_fields": len(all_field_names),
                    "identical": identical_count,
                    "different": different_count,
                    "missing_in_a": missing_a,
                    "missing_in_b": missing_b,
                    "agreement_rate": (
                        identical_count / len(all_field_names) if all_field_names else 0
                    ),
                },
                "field_comparisons": field_comparisons,
            }

        finally:
            db.close()

    def get_extraction_statistics(
        self,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            query = db.query(ExtractionResult)

            if start_date:
                query = query.filter(ExtractionResult.created_at >= start_date)
            if end_date:
                query = query.filter(ExtractionResult.created_at <= end_date)

            from sqlalchemy import func

            total_extractions = query.count()
            completed = query.filter(
                ExtractionResult.status == ExtractionStatusEnum.COMPLETED
            ).count()
            failed = query.filter(
                ExtractionResult.status == ExtractionStatusEnum.FAILED
            ).count()

            avg_confidence = db.query(
                func.avg(ExtractionResult.confidence_score)
            ).filter(
                ExtractionResult.confidence_score.isnot(None)
            ).scalar()

            avg_extraction_time = db.query(
                func.avg(ExtractionResult.extraction_time)
            ).filter(
                ExtractionResult.extraction_time.isnot(None)
            ).scalar()

            model_versions = db.query(
                ExtractionResult.model_version,
                func.count(ExtractionResult.id),
                func.avg(ExtractionResult.confidence_score),
            ).filter(
                ExtractionResult.model_version.isnot(None)
            ).group_by(
                ExtractionResult.model_version
            ).all()

            low_confidence_count = db.query(ExtractedField).filter(
                ExtractedField.is_low_confidence == True
            ).count()

            total_fields = db.query(ExtractedField).count()

            return {
                "total_extractions": total_extractions,
                "completed": completed,
                "failed": failed,
                "success_rate": completed / total_extractions if total_extractions > 0 else 0,
                "average_confidence": float(avg_confidence) if avg_confidence else None,
                "average_extraction_time": float(avg_extraction_time) if avg_extraction_time else None,
                "total_fields_extracted": total_fields,
                "low_confidence_fields": low_confidence_count,
                "low_confidence_rate": low_confidence_count / total_fields if total_fields > 0 else 0,
                "model_versions": [
                    {
                        "version": mv,
                        "count": cnt,
                        "avg_confidence": float(ac) if ac else None,
                    }
                    for mv, cnt, ac in model_versions
                ],
            }

        finally:
            db.close()

    def get_field_history(
        self,
        document_id: int,
        field_name: str,
    ) -> List[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            fields = db.query(ExtractedField).join(
                ExtractionResult,
                ExtractedField.extraction_result_id == ExtractionResult.id
            ).filter(
                and_(
                    ExtractionResult.document_id == document_id,
                    ExtractedField.field_name == field_name,
                )
            ).order_by(
                ExtractionResult.created_at.desc()
            ).all()

            return [
                {
                    "extraction_result_id": f.extraction_result_id,
                    "model_version": f.extraction_result.model_version,
                    "value": f.value,
                    "normalized_value": f.normalized_value,
                    "confidence": f.confidence,
                    "extracted_at": f.extraction_result.created_at,
                    "reviewed": f.reviewed,
                    "reviewed_value": f.reviewed_value,
                }
                for f in fields
            ]

        finally:
            db.close()
