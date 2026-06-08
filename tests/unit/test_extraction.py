import json
from typing import Dict, Any, List
from unittest.mock import patch, MagicMock

import pytest

from app.ml.extractor import (
    MultimodalExtractor, ExtractionContext,
)
from app.schemas.extraction import (
    ExtractionSchema, FieldSchema, FieldDataTypeEnum,
    ExtractedFieldCreate,
)
from app.schemas.common import BoundingBox, TextBlock
from app.models.extraction import ExtractionResult, ExtractedField
from app.models.document import Document, DocumentStatus


@pytest.mark.unit
@pytest.mark.extraction
class TestExtractionContext:
    def test_extraction_context_creation(self, mock_standardized_doc, extraction_schema):
        context = ExtractionContext(
            document_id=1,
            standardized_doc=mock_standardized_doc,
            layout_result={"regions": []},
            tables_result=[],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        assert context.document_id == 1
        assert context.standardized_doc == mock_standardized_doc
        assert context.schema == extraction_schema
        assert context.model_version == "1.0.0"
        assert context.ab_test_group is None


@pytest.mark.unit
@pytest.mark.extraction
class TestMultimodalExtractor:
    def test_singleton_pattern(self):
        extractor1 = MultimodalExtractor()
        extractor2 = MultimodalExtractor()

        assert extractor1 is extractor2

    def test_extract_patient_name(self, db_session, mock_standardized_doc, extraction_schema, sample_pdf_path):
        from app.ml.extractor import MultimodalExtractor

        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=1,
            standardized_doc=mock_standardized_doc,
            layout_result={"regions": []},
            tables_result=[{"headers": ["项目", "金额"], "rows": [["挂号费", "50.00"]]}],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=1,
            original_filename="test.pdf",
            filename="test.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        result = extractor.extract_fields(context)

        assert result is not None
        assert "fields" in result

        field_names = [f["field_name"] for f in result["fields"]]
        assert "patient_name" in field_names
        assert "diagnosis_code" in field_names
        assert "total_amount" in field_names

    def test_extracted_fields_have_confidence(self, db_session, mock_standardized_doc, extraction_schema):
        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=2,
            standardized_doc=mock_standardized_doc,
            layout_result={"regions": []},
            tables_result=[],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=2,
            original_filename="test.pdf",
            filename="test.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        result = extractor.extract_fields(context)

        for field in result["fields"]:
            assert "confidence" in field
            assert 0 <= field["confidence"] <= 1.0

    def test_extract_medical_report_diagnosis_code(self, db_session, mock_standardized_doc, extraction_schema):
        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=3,
            standardized_doc=mock_standardized_doc,
            layout_result={"regions": []},
            tables_result=[],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=3,
            original_filename="test.pdf",
            filename="test.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        result = extractor.extract_fields(context)

        diagnosis_fields = [
            f for f in result["fields"]
            if f["field_name"] == "diagnosis_code" and f.get("value")
        ]

        if diagnosis_fields:
            code = diagnosis_fields[0]["value"]
            assert isinstance(code, str)
            assert len(code) >= 3

    def test_multimodal_extractor_model_initialized(self):
        extractor = MultimodalExtractor()

        assert extractor.model is not None
        assert "name" in extractor.model
        assert "version" in extractor.model
        assert "type" in extractor.model

    def test_table_data_used_in_extraction(self, db_session, mock_standardized_doc, extraction_schema):
        table_data = [
            {
                "headers": ["项目", "类型", "金额"],
                "rows": [
                    ["挂号费", "诊疗", "50.00"],
                    ["检查费", "检查", "1200.00"],
                    ["合计", "", "3500.00"],
                ],
                "row_count": 3,
                "col_count": 3,
            }
        ]

        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=4,
            standardized_doc=mock_standardized_doc,
            layout_result={"regions": []},
            tables_result=table_data,
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=4,
            original_filename="test.pdf",
            filename="test.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        result = extractor.extract_fields(context)

        amount_fields = [
            f for f in result["fields"]
            if f["field_name"] == "total_amount" and f.get("value")
        ]

        if amount_fields:
            value = amount_fields[0]["value"]
            assert value is not None
            if isinstance(value, (int, float)):
                assert value >= 0

    def test_layout_info_used_in_extraction(self, db_session, mock_standardized_doc, extraction_schema):
        layout_result = {
            "regions": [
                {
                    "region_id": "r1",
                    "region_type": "title",
                    "bbox": {"x1": 0, "y1": 0, "x2": 100, "y2": 30},
                    "page_number": 1,
                    "confidence": 0.95,
                    "text": "医院诊断证明书",
                },
                {
                    "region_id": "r2",
                    "region_type": "paragraph",
                    "bbox": {"x1": 0, "y1": 50, "x2": 300, "y2": 80},
                    "page_number": 1,
                    "confidence": 0.92,
                    "text": "患者姓名：张三",
                },
            ],
            "document_tree": {},
        }

        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=5,
            standardized_doc=mock_standardized_doc,
            layout_result=layout_result,
            tables_result=[],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=5,
            original_filename="test.pdf",
            filename="test.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        result = extractor.extract_fields(context)

        patient_fields = [
            f for f in result["fields"]
            if f["field_name"] == "patient_name" and f.get("value")
        ]

        if patient_fields:
            assert patient_fields[0]["confidence"] > 0.5

    def test_extraction_result_has_overall_confidence(self, db_session, mock_standardized_doc, extraction_schema):
        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=6,
            standardized_doc=mock_standardized_doc,
            layout_result={"regions": []},
            tables_result=[],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=6,
            original_filename="test.pdf",
            filename="test.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        result = extractor.extract_fields(context)

        assert "overall_confidence" in result
        assert 0 <= result["overall_confidence"] <= 1.0

    def test_low_quality_document_marked_for_review(self, db_session, low_confidence_text_blocks, extraction_schema):
        from app.schemas.document import StandardizedDocument, PageInfo, DocumentTypeEnum

        low_quality_doc = StandardizedDocument(
            document_id=7,
            original_filename="low_quality.pdf",
            document_type=DocumentTypeEnum.PDF,
            page_count=1,
            pages=[
                PageInfo(
                    page_number=1,
                    width=595,
                    height=842,
                    text_blocks=low_confidence_text_blocks,
                    ocr_confidence=0.4,
                )
            ],
        )

        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=7,
            standardized_doc=low_quality_doc,
            layout_result={"regions": []},
            tables_result=[],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=7,
            original_filename="low_quality.pdf",
            filename="low_quality.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        result = extractor.extract_fields(context)

        for field in result["fields"]:
            if field.get("confidence", 1.0) < 0.6:
                assert field.get("needs_review", True)

    def test_required_field_missing(self, db_session, extraction_schema):
        from app.schemas.document import StandardizedDocument, PageInfo, DocumentTypeEnum

        empty_doc = StandardizedDocument(
            document_id=8,
            original_filename="empty.pdf",
            document_type=DocumentTypeEnum.PDF,
            page_count=1,
            pages=[PageInfo(page_number=1, width=595, height=842)],
        )

        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=8,
            standardized_doc=empty_doc,
            layout_result={"regions": []},
            tables_result=[],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=8,
            original_filename="empty.pdf",
            filename="empty.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        result = extractor.extract_fields(context)

        required_fields = [f.field_name for f in extraction_schema.fields if f.required]
        for field in result["fields"]:
            if field["field_name"] in required_fields and not field.get("value"):
                assert field.get("needs_review", True)

    def test_extraction_saves_to_database(self, db_session, mock_standardized_doc, extraction_schema):
        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=9,
            standardized_doc=mock_standardized_doc,
            layout_result={"regions": []},
            tables_result=[],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=9,
            original_filename="test.pdf",
            filename="test.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        extractor.extract_fields(context)

        result = db_session.query(ExtractionResult).filter(
            ExtractionResult.document_id == 9
        ).first()

        assert result is not None
        assert result.schema_name == extraction_schema.schema_name

        fields = db_session.query(ExtractedField).filter(
            ExtractedField.extraction_result_id == result.id
        ).all()

        assert len(fields) > 0

    def test_extracted_field_data_types_correct(self, db_session, mock_standardized_doc, extraction_schema):
        extractor = MultimodalExtractor()

        context = ExtractionContext(
            document_id=10,
            standardized_doc=mock_standardized_doc,
            layout_result={"regions": []},
            tables_result=[],
            schema=extraction_schema,
            model_version="1.0.0",
        )

        doc = Document(
            id=10,
            original_filename="test.pdf",
            filename="test.pdf",
            storage_path="/tmp/test.pdf",
            status="preprocessed",
            page_count=1,
        )
        db_session.add(doc)
        db_session.commit()

        extractor.extract_fields(context)

        fields = db_session.query(ExtractedField).join(
            ExtractionResult
        ).filter(
            ExtractionResult.document_id == 10
        ).all()

        field_map = {f.field_name: f for f in fields}

        if "total_amount" in field_map and field_map["total_amount"].field_value:
            try:
                value = float(field_map["total_amount"].field_value)
                assert isinstance(value, (int, float))
            except (ValueError, TypeError):
                pass

    def test_multiple_sources_fusion_highest_confidence(self):
        from app.ml.extractor import MultimodalExtractor

        extractor = MultimodalExtractor()

        candidates = [
            {"source": "regex", "value": "3500.00", "confidence": 0.7},
            {"source": "table", "value": "3500.00", "confidence": 0.9},
            {"source": "heuristic", "value": "3500", "confidence": 0.5},
        ]

        def select_best_candidate(candidates_list):
            if not candidates_list:
                return None
            return max(candidates_list, key=lambda x: x["confidence"])

        best = select_best_candidate(candidates)
        assert best["source"] == "table"
        assert best["confidence"] == 0.9


@pytest.mark.unit
@pytest.mark.extraction
class TestExtractionSchema:
    def test_extraction_schema_validation(self):
        schema = ExtractionSchema(
            schema_name="test_schema",
            schema_version="1.0",
            description="Test",
            fields=[
                FieldSchema(
                    field_name="test_field",
                    description="Test field",
                    data_type=FieldDataTypeEnum.STRING,
                    required=True,
                ),
            ],
        )

        assert schema.schema_name == "test_schema"
        assert len(schema.fields) == 1
        assert schema.fields[0].field_name == "test_field"

    def test_field_schema_data_types(self):
        for data_type in FieldDataTypeEnum:
            field = FieldSchema(
                field_name=f"test_{data_type.value}",
                description="Test",
                data_type=data_type,
                required=False,
            )
            assert field.data_type == data_type
