import os
import json
import re
import time
import tempfile
from typing import List, Dict, Any, Optional, Tuple
from dataclasses import dataclass
from datetime import datetime
import hashlib

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.document import StandardizedDocument
from app.schemas.extraction import (
    ExtractionSchema,
    FieldSchema,
    ExtractedFieldCreate,
    ExtractionResultCreate,
    FieldDataTypeEnum,
    ExtractionStatusEnum,
)
from app.schemas.common import BoundingBox, ValidationError
from app.services.storage import StorageService
from app.services.ab_test_service import ABTestService
from app.core.database import get_sync_db
from app.models.extraction import ExtractionResult, ExtractedField
from app.models.document import DocumentStatus

logger = get_logger(__name__)
settings = get_settings()


@dataclass
class ExtractionContext:
    document_id: int
    standardized_doc: StandardizedDocument
    layout_result: Optional[Dict[str, Any]]
    tables_result: Optional[List[Dict[str, Any]]]
    schema: ExtractionSchema
    model_version: Optional[str]
    ab_test_group: Optional[str] = None


class MultimodalExtractor:
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
        self.storage_service = StorageService()
        self.ab_test_service = ABTestService()
        self.model = None
        self._initialize_model()

    def _initialize_model(self):
        try:
            os.makedirs(settings.ML_MODEL_CACHE_DIR, exist_ok=True)

            cache_key = f"extraction_model_{settings.EXTRACTION_MODEL_NAME}_{settings.EXTRACTION_MODEL_VERSION}"
            cached_model = self.storage_service.cache_get(cache_key)

            if cached_model:
                logger.info("Using cached extraction model")
                self.model = cached_model
            else:
                logger.info(f"Initializing extraction model: {settings.EXTRACTION_MODEL_NAME}")
                try:
                    import torch
                    from transformers import AutoModel, AutoProcessor

                    model_name = settings.EXTRACTION_MODEL_NAME

                    self.model = {
                        "name": settings.EXTRACTION_MODEL_NAME,
                        "version": settings.EXTRACTION_MODEL_VERSION,
                        "type": "multimodal_llm",
                        "device": settings.ML_DEVICE,
                    }

                    self.storage_service.cache_set(cache_key, self.model, ttl=86400)
                except ImportError:
                    logger.warning("Transformers not available, using rule-based extraction")
                    self.model = {
                        "name": "rule_based",
                        "version": "1.0.0",
                        "type": "rule_based",
                    }

            logger.info("Multimodal extractor initialized successfully")
        except Exception as e:
            logger.error(f"Failed to initialize multimodal extractor: {e}")
            self.model = {
                "name": "rule_based",
                "version": "1.0.0",
                "type": "rule_based",
            }

    def extract_fields(
        self,
        context: ExtractionContext,
    ) -> Dict[str, Any]:
        logger.info(f"Extracting fields for document: {context.document_id}")
        start_time = time.time()

        if settings.AB_TEST_ENABLED:
            context.ab_test_group = self.ab_test_service.assign_model_version(
                context.document_id, "extraction"
            )

        db = next(get_sync_db())
        try:
            extraction_result = self._create_extraction_result(db, context)

            doc_text = self._get_full_document_text(context.standardized_doc)
            table_data = self._get_table_text(context.tables_result)
            layout_info = self._get_layout_text(context.layout_result)

            all_context = f"Document Text:\n{doc_text}\n\n"
            if table_data:
                all_context += f"Tables:\n{table_data}\n\n"
            if layout_info:
                all_context += f"Layout:\n{layout_info}"

            extracted_fields: List[Dict[str, Any]] = []

            for field_schema in context.schema.fields:
                field_result = self._extract_single_field(
                    field_schema=field_schema,
                    context=all_context,
                    standardized_doc=context.standardized_doc,
                    layout_result=context.layout_result,
                    tables_result=context.tables_result,
                )
                extracted_fields.append(field_result)
                self._save_extracted_field(db, extraction_result.id, field_schema, field_result)

            overall_confidence = self._calculate_overall_confidence(extracted_fields)

            extraction_result.overall_confidence = overall_confidence
            extraction_result.processing_time = time.time() - start_time
            extraction_result.status = ExtractionStatusEnum.COMPLETED.value
            extraction_result.raw_extraction = {"fields": extracted_fields}
            extraction_result.structured_output = {
                f["field_name"]: f.get("normalized_value") or f.get("value")
                for f in extracted_fields
            }

            db.commit()
            db.refresh(extraction_result)

            if settings.AB_TEST_ENABLED and context.ab_test_group:
                self.ab_test_service.record_ab_result(
                    document_id=context.document_id,
                    extraction_result_id=extraction_result.id,
                    model_version=context.ab_test_group,
                    fields=extracted_fields,
                )

            self._update_document_status(context.document_id, DocumentStatus.EXTRACTED)

            result = {
                "extraction_result_id": extraction_result.id,
                "document_id": context.document_id,
                "schema_name": context.schema.schema_name,
                "overall_confidence": overall_confidence,
                "processing_time": time.time() - start_time,
                "model_name": self.model.get("name"),
                "model_version": self.model.get("version"),
                "is_ab_test": context.ab_test_group is not None,
                "ab_test_group": context.ab_test_group,
                "fields": extracted_fields,
                "low_confidence_fields": [
                    f for f in extracted_fields
                    if f.get("confidence", 1.0) < settings.EXTRACTION_CONFIDENCE_THRESHOLD
                ],
            }

            self._save_extraction_result(context.document_id, result)

            logger.info(
                f"Extraction complete for document {context.document_id}. "
                f"Fields: {len(extracted_fields)}, "
                f"Confidence: {overall_confidence:.3f}"
            )

            return result

        except Exception as e:
            logger.error(f"Extraction failed for document {context.document_id}: {e}", exc_info=True)
            if "extraction_result" in locals() and extraction_result.id:
                extraction_result.status = ExtractionStatusEnum.FAILED.value
                extraction_result.error_message = str(e)
                db.commit()
            self._update_document_status(context.document_id, DocumentStatus.FAILED)
            raise
        finally:
            db.close()

    def _create_extraction_result(self, db, context: ExtractionContext) -> ExtractionResult:
        result_create = ExtractionResultCreate(
            document_id=context.document_id,
            schema_name=context.schema.schema_name,
            schema_version=context.schema.schema_version,
            status=ExtractionStatusEnum.PROCESSING,
            model_name=self.model.get("name"),
            model_version=self.model.get("version"),
            is_ab_test=context.ab_test_group is not None,
            ab_test_group=context.ab_test_group,
        )

        result = ExtractionResult(**result_create.model_dump())
        db.add(result)
        db.flush()
        return result

    def _save_extracted_field(
        self,
        db,
        extraction_result_id: int,
        field_schema: FieldSchema,
        field_result: Dict[str, Any],
    ) -> None:
        field_create = ExtractedFieldCreate(
            extraction_result_id=extraction_result_id,
            field_name=field_schema.field_name,
            field_type=field_schema.field_type,
            value=field_result.get("value"),
            normalized_value=field_result.get("normalized_value"),
            confidence=field_result.get("confidence", 0.0),
            is_low_confidence=field_result.get("confidence", 1.0) < settings.EXTRACTION_CONFIDENCE_THRESHOLD,
            page_number=field_result.get("page_number"),
            bounding_box=field_result.get("bbox"),
            text_block=field_result.get("source_text"),
            validation_status=field_result.get("validation_status", "unchecked"),
            validation_errors=field_result.get("validation_errors"),
            suggested_value=field_result.get("suggested_value"),
        )

        field = ExtractedField(**field_create.model_dump())
        db.add(field)

    def _get_full_document_text(self, doc: StandardizedDocument) -> str:
        texts = []
        for page in doc.pages:
            for block in page.text_blocks:
                texts.append(block.text)
        return "\n".join(texts)

    def _get_table_text(self, tables: Optional[List[Dict[str, Any]]]) -> str:
        if not tables:
            return ""

        table_texts = []
        for table_idx, table in enumerate(tables):
            json_data = table.get("json_data", {})
            headers = json_data.get("headers", [])
            rows = json_data.get("rows", [])

            table_str = f"Table {table_idx + 1}:\n"
            if headers:
                table_str += "Headers: " + " | ".join(headers) + "\n"
            for row in rows:
                table_str += "Row: " + " | ".join(row) + "\n"
            table_texts.append(table_str)

        return "\n".join(table_texts)

    def _get_layout_text(self, layout: Optional[Dict[str, Any]]) -> str:
        if not layout:
            return ""

        regions = layout.get("regions", [])
        texts = []
        for region in regions:
            rtype = region.get("region_type", "")
            text = region.get("metadata", {}).get("text", "")
            if text:
                texts.append(f"[{rtype.upper()}] {text}")

        return "\n".join(texts)

    def _extract_single_field(
        self,
        field_schema: FieldSchema,
        context: str,
        standardized_doc: StandardizedDocument,
        layout_result: Optional[Dict[str, Any]],
        tables_result: Optional[List[Dict[str, Any]]],
    ) -> Dict[str, Any]:
        field_name = field_schema.field_name
        field_type = field_schema.field_type

        extraction_methods = [
            self._extract_with_regex,
            self._extract_from_table,
            self._extract_with_heuristic,
        ]

        best_result = None
        best_confidence = -1

        for method in extraction_methods:
            try:
                result = method(
                    field_name=field_name,
                    field_type=field_type,
                    context=context,
                    field_schema=field_schema,
                    standardized_doc=standardized_doc,
                    tables_result=tables_result,
                )
                if result and result.get("value") and result.get("confidence", 0) > best_confidence:
                    best_result = result
                    best_confidence = result.get("confidence", 0)
            except Exception as e:
                logger.debug(f"Extraction method {method.__name__} failed for {field_name}: {e}")
                continue

        if best_result is None:
            best_result = {
                "field_name": field_name,
                "value": None,
                "normalized_value": None,
                "confidence": 0.0,
                "is_low_confidence": True,
            }

        best_result["field_name"] = field_name
        best_result["field_type"] = field_type.value

        normalized = self._normalize_value(
            best_result.get("value"),
            field_type,
        )
        best_result["normalized_value"] = normalized

        if "is_low_confidence" not in best_result:
            best_result["is_low_confidence"] = best_result.get(
                "confidence", 1.0
            ) < settings.EXTRACTION_CONFIDENCE_THRESHOLD

        return best_result

    def _extract_with_regex(
        self,
        field_name: str,
        field_type: FieldDataTypeEnum,
        context: str,
        field_schema: FieldSchema,
        **kwargs,
    ) -> Optional[Dict[str, Any]]:
        patterns = self._get_field_patterns(field_name, field_type)

        for pattern_info in patterns:
            pattern = pattern_info["pattern"]
            match = re.search(pattern, context, re.IGNORECASE | re.MULTILINE)
            if match:
                value = match.group("value") if "value" in match.groupdict() else match.group(0)
                value = value.strip()

                bbox, page_number, source_text = self._find_value_location(value, kwargs["standardized_doc"])

                return {
                    "value": value,
                    "confidence": pattern_info.get("confidence", 0.8),
                    "bbox": bbox.model_dump() if bbox else None,
                    "page_number": page_number,
                    "source_text": source_text,
                    "extraction_method": "regex",
                    "pattern_used": pattern,
                }

        return None

    def _get_field_patterns(self, field_name: str, field_type: FieldDataTypeEnum) -> List[Dict[str, Any]]:
        field_lower = field_name.lower()

        patterns_map = {
            "name": [
                {"pattern": r"(?:姓名|患者姓名|病人姓名|Name|Patient Name)[:：]\s*(?P<value>[\u4e00-\u9fa5A-Za-z\s]{2,30})", "confidence": 0.9},
                {"pattern": r"姓\s*名[:：]\s*(?P<value>[\u4e00-\u9fa5A-Za-z\s]{2,30})", "confidence": 0.85},
            ],
            "patient_name": [
                {"pattern": r"(?:患者姓名|患者|病人)[:：]\s*(?P<value>[\u4e00-\u9fa5A-Za-z\s]{2,30})", "confidence": 0.9},
            ],
            "amount": [
                {"pattern": r"(?:金额|总金额|费用|合计|Amount|Total)[:：]?\s*[¥￥$]?\s*(?P<value>\d+(?:[.,]\d+)?)", "confidence": 0.85},
                {"pattern": r"[¥￥$]\s*(?P<value>\d+(?:[.,]\d+)?)", "confidence": 0.75},
            ],
            "total_amount": [
                {"pattern": r"(?:总计|共计|合计|总金额)[:：]?\s*[¥￥$]?\s*(?P<value>\d+(?:[.,]\d+)?)", "confidence": 0.9},
            ],
            "date": [
                {"pattern": r"(?:日期|日期|Date)[:：]\s*(?P<value>\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?)", "confidence": 0.9},
                {"pattern": r"(?P<value>\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?)", "confidence": 0.7},
            ],
            "diagnosis_code": [
                {"pattern": r"(?:诊断编码|诊断代码|ICD编码|ICD码|ICD-?10)[:：]?\s*(?P<value>[A-Z]\d{2}(?:\.\d{1,2})?)", "confidence": 0.9},
                {"pattern": r"(?<![A-Z0-9])(?P<value>[A-Z]\d{2}(?:\.\d{1,2})?)(?![A-Z0-9])", "confidence": 0.6},
            ],
            "id_card": [
                {"pattern": r"(?:身份证号|身份证|ID)[:：]?\s*(?P<value>\d{17}[\dXx])", "confidence": 0.95},
            ],
            "phone": [
                {"pattern": r"(?:电话|手机|联系电话|Phone|Tel)[:：]?\s*(?P<value>1[3-9]\d{9})", "confidence": 0.9},
            ],
        }

        for key, patterns in patterns_map.items():
            if key in field_lower:
                return patterns

        if field_type == FieldDataTypeEnum.DATE:
            return [
                {"pattern": r"(?P<value>\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?)", "confidence": 0.7},
            ]
        elif field_type == FieldDataTypeEnum.NUMBER:
            return [
                {"pattern": r"(?P<value>\d+(?:[.,]\d+)?)", "confidence": 0.5},
            ]

        return []

    def _extract_from_table(
        self,
        field_name: str,
        field_type: FieldDataTypeEnum,
        tables_result: Optional[List[Dict[str, Any]]],
        **kwargs,
    ) -> Optional[Dict[str, Any]]:
        if not tables_result:
            return None

        field_lower = field_name.lower()

        for table in tables_result:
            json_data = table.get("json_data", {})
            headers = json_data.get("headers", [])
            rows = json_data.get("rows", [])

            for header_idx, header in enumerate(headers):
                if field_lower in str(header).lower():
                    if rows:
                        value = rows[0][header_idx] if rows and header_idx < len(rows[0]) else None
                        if value:
                            bbox = None
                            page_number = table.get("page_number")

                            for cell in table.get("cells", []):
                                if cell.get("row_index") == 1 and cell.get("col_index") == header_idx:
                                    bbox = cell.get("bbox")
                                    break

                            return {
                                "value": value,
                                "confidence": 0.85,
                                "bbox": bbox,
                                "page_number": page_number,
                                "source_text": f"{header}: {value}",
                                "extraction_method": "table",
                                "table_id": table.get("table_id"),
                            }

        return None

    def _extract_with_heuristic(
        self,
        field_name: str,
        context: str,
        field_schema: FieldSchema,
        standardized_doc: StandardizedDocument,
        **kwargs,
    ) -> Optional[Dict[str, Any]]:
        field_lower = field_name.lower()

        keywords = self._get_field_keywords(field_name)
        if not keywords:
            return None

        for keyword in keywords:
            pattern = rf"{keyword}[:：]?\s*(?P<value>.{{1,100}}?)(?:\n|$)"
            match = re.search(pattern, context, re.IGNORECASE)
            if match:
                value = match.group("value").strip()
                value = re.sub(r"\s+", " ", value).strip()

                bbox, page_number, source_text = self._find_value_location(
                    value[:50], standardized_doc
                )

                return {
                    "value": value,
                    "confidence": 0.6,
                    "bbox": bbox.model_dump() if bbox else None,
                    "page_number": page_number,
                    "source_text": source_text,
                    "extraction_method": "heuristic",
                    "keyword_used": keyword,
                }

        return None

    def _get_field_keywords(self, field_name: str) -> List[str]:
        field_lower = field_name.lower()

        keywords_map = {
            "name": ["姓名", "患者姓名", "Name", "Patient Name"],
            "gender": ["性别", "Gender", "Sex"],
            "age": ["年龄", "Age"],
            "department": ["科室", "Department", "Dept"],
            "doctor": ["医生", "医师", "Doctor"],
            "hospital": ["医院", "Hospital"],
            "diagnosis": ["诊断", "Diagnosis"],
            "prescription": ["处方", "Prescription"],
            "medicine": ["药品", "药物", "Medicine", "Drug"],
            "dosage": ["用法用量", "用量", "Dosage"],
        }

        for key, keywords in keywords_map.items():
            if key in field_lower:
                return keywords

        return [field_name]

    def _find_value_location(
        self,
        value: str,
        standardized_doc: StandardizedDocument,
    ) -> Tuple[Optional[BoundingBox], Optional[int], Optional[str]]:
        if not value:
            return None, None, None

        for page in standardized_doc.pages:
            for block in page.text_blocks:
                if value in block.text:
                    return block.bbox, page.page_number, block.text

        return None, None, None

    def _normalize_value(self, value: Optional[str], field_type: FieldDataTypeEnum) -> Optional[str]:
        if value is None:
            return None

        value = str(value).strip()

        if field_type == FieldDataTypeEnum.DATE:
            return self._normalize_date(value)
        elif field_type == FieldDataTypeEnum.NUMBER:
            return self._normalize_number(value)
        elif field_type == FieldDataTypeEnum.STRING:
            return re.sub(r"\s+", " ", value).strip()

        return value

    def _normalize_date(self, date_str: str) -> Optional[str]:
        try:
            date_str = date_str.replace("年", "-").replace("月", "-").replace("日", "")
            date_str = date_str.replace("/", "-")

            match = re.match(r"(\d{4})-(\d{1,2})-(\d{1,2})", date_str)
            if match:
                year, month, day = match.groups()
                return f"{int(year):04d}-{int(month):02d}-{int(day):02d}"
        except Exception:
            pass
        return date_str

    def _normalize_number(self, num_str: str) -> Optional[str]:
        try:
            num_str = num_str.replace(",", "").replace("，", "")
            num_str = re.sub(r"[^\d.-]", "", num_str)
            if num_str:
                return str(float(num_str))
        except Exception:
            pass
        return num_str

    def _calculate_overall_confidence(self, fields: List[Dict[str, Any]]) -> float:
        if not fields:
            return 0.0

        confidences = [f.get("confidence", 0.0) for f in fields]
        return sum(confidences) / len(confidences)

    def _update_document_status(self, document_id: int, status: DocumentStatus) -> None:
        db = next(get_sync_db())
        try:
            from app.models.document import Document

            doc = db.query(Document).filter(Document.id == document_id).first()
            if doc:
                doc.status = status
                db.commit()
        finally:
            db.close()

    def _save_extraction_result(self, document_id: int, result: Dict[str, Any]) -> None:
        try:
            object_name = f"processed/{document_id}/extraction.json"
            data = json.dumps(result, ensure_ascii=False, indent=2).encode("utf-8")
            self.storage_service.upload_bytes(
                bucket_name=settings.MINIO_PROCESSED_BUCKET,
                object_name=object_name,
                data=data,
            )
        except Exception as e:
            logger.warning(f"Failed to save extraction result: {e}")

    def get_extraction_result(self, document_id: int) -> Optional[Dict[str, Any]]:
        try:
            object_name = f"processed/{document_id}/extraction.json"
            data = self.storage_service.download_file_bytes(
                bucket_name=settings.MINIO_PROCESSED_BUCKET,
                object_name=object_name,
            )
            return json.loads(data.decode("utf-8"))
        except Exception as e:
            logger.debug(f"Extraction result not found for document {document_id}: {e}")
            return None

    def get_default_insurance_schema(self) -> ExtractionSchema:
        return ExtractionSchema(
            schema_name="insurance_claim",
            schema_version="1.0",
            description="Insurance claim document extraction schema",
            document_types=["medical_report", "invoice", "accident_report"],
            fields=[
                FieldSchema(
                    field_name="patient_name",
                    field_type=FieldDataTypeEnum.STRING,
                    description="患者姓名",
                    required=True,
                ),
                FieldSchema(
                    field_name="id_card",
                    field_type=FieldDataTypeEnum.STRING,
                    description="身份证号",
                    required=True,
                ),
                FieldSchema(
                    field_name="diagnosis_code",
                    field_type=FieldDataTypeEnum.STRING,
                    description="ICD-10诊断编码",
                    required=True,
                ),
                FieldSchema(
                    field_name="diagnosis",
                    field_type=FieldDataTypeEnum.STRING,
                    description="诊断结果",
                    required=False,
                ),
                FieldSchema(
                    field_name="total_amount",
                    field_type=FieldDataTypeEnum.NUMBER,
                    description="总费用金额",
                    required=True,
                    validation_rules={"min": 0, "max": 10000000},
                ),
                FieldSchema(
                    field_name="treatment_date",
                    field_type=FieldDataTypeEnum.DATE,
                    description="就诊日期",
                    required=True,
                ),
                FieldSchema(
                    field_name="discharge_date",
                    field_type=FieldDataTypeEnum.DATE,
                    description="出院日期",
                    required=False,
                ),
                FieldSchema(
                    field_name="hospital",
                    field_type=FieldDataTypeEnum.STRING,
                    description="就诊医院",
                    required=False,
                ),
                FieldSchema(
                    field_name="department",
                    field_type=FieldDataTypeEnum.STRING,
                    description="就诊科室",
                    required=False,
                ),
                FieldSchema(
                    field_name="doctor_name",
                    field_type=FieldDataTypeEnum.STRING,
                    description="主治医生",
                    required=False,
                ),
            ],
        )
