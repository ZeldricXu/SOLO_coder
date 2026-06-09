import os
import sys
import yaml
import json
import tempfile
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import Mock, patch, MagicMock
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(__file__))))

from app.services.schema_service import ExtractionSchemaService
from app.services.review_service import ReviewService
from app.ml.document_type_detector import DocumentTypeDetector, DocumentTypeDetectionResult
from app.schemas.extraction import (
    ExtractionSchemaCreate,
    ExtractionSchemaUpdate,
    FieldSchema,
    FieldDataTypeEnum,
)
from app.schemas.review import (
    BatchReviewConfirmRequest,
    FieldCorrection,
)
from app.models.extraction import ExtractionSchema as ExtractionSchemaModel
from app.models.review import ReviewTask, ReviewStatus, ReviewPriority
from app.models.extraction import ExtractedField, ExtractionResult
from app.models.document import Document, DocumentStatus


@pytest.fixture
def sample_auto_insurance_yaml():
    return """
schema_name: test_auto_insurance
schema_version: "1.0"
description: Test auto insurance schema
document_types:
  - accident_report
business_line: auto_insurance
created_by: test_admin
is_active: true
fields:
  - field_name: license_plate
    field_type: string
    description: 车牌号
    required: true
    examples:
      - "京A12345"
    validation_rules:
      pattern: "^[京津沪渝][A-Z][A-Z0-9]{5}$"
      min_length: 6
      max_length: 8

  - field_name: accident_location
    field_type: string
    description: 事故地点
    required: true
    examples:
      - "北京市朝阳区建国路88号"

  - field_name: accident_date
    field_type: date
    description: 事故日期
    required: true
    examples:
      - "2026-05-15"
    validation_rules:
      date_format: "%Y-%m-%d"
"""


@pytest.fixture
def sample_health_insurance_yaml():
    return """
schema_name: test_health_insurance
schema_version: "1.0"
description: Test health insurance schema
document_types:
  - medical_report
business_line: health_insurance
created_by: test_admin
is_active: true
fields:
  - field_name: patient_name
    field_type: string
    description: 患者姓名
    required: true

  - field_name: diagnosis_code
    field_type: string
    description: ICD-10诊断编码
    required: true
    validation_rules:
      pattern: "^[A-Z]\\\\d{2}(?:\\\\.\\\\d{1,2})?$"

  - field_name: previous_medical_history
    field_type: string
    description: 既往病史
    required: true
    examples:
      - "高血压病史10年"
"""


class TestExtractionSchemaService:
    def test_parse_yaml(self, sample_auto_insurance_yaml):
        service = ExtractionSchemaService()
        result = service._parse_yaml(sample_auto_insurance_yaml)

        assert result is not None
        assert result["schema_name"] == "test_auto_insurance"
        assert result["schema_version"] == "1.0"
        assert len(result["fields"]) == 3

    def test_yaml_to_schema_dict(self, sample_auto_insurance_yaml):
        service = ExtractionSchemaService()
        yaml_data = service._parse_yaml(sample_auto_insurance_yaml)
        schema_dict = service._yaml_to_schema_dict(yaml_data)

        assert schema_dict["schema_name"] == "test_auto_insurance"
        assert len(schema_dict["fields"]) == 3
        assert schema_dict["business_line"] == "auto_insurance"
        assert schema_dict["fields"][0]["field_name"] == "license_plate"

    def test_load_schema_from_yaml_content(self, sample_health_insurance_yaml, db_session):
        service = ExtractionSchemaService()
        schema = service.load_schema_from_yaml_content(
            sample_health_insurance_yaml, created_by="test_user"
        )

        assert schema is not None
        assert schema.schema_name == "test_health_insurance"
        assert schema.created_by == "test_user"
        assert len(schema.fields) == 3

    def test_get_default_schema(self, db_session):
        service = ExtractionSchemaService()

        default_fields = [
            {
                "field_name": "patient_name",
                "field_type": "string",
                "description": "患者姓名",
                "required": True,
            }
        ]

        db_session.add(
            ExtractionSchemaModel(
                schema_name="default_test",
                schema_version="1.0",
                description="Test default schema",
                business_line="general",
                fields=default_fields,
                is_default=True,
                is_active=True,
            )
        )
        db_session.commit()

        default = service.get_default_schema()
        assert default is not None
        assert default.schema_name == "default_test"

    def test_get_schema_by_name(self, db_session):
        service = ExtractionSchemaService()

        fields = [{"field_name": "test_field", "field_type": "string"}]
        db_session.add(
            ExtractionSchemaModel(
                schema_name="test_schema_by_name",
                schema_version="1.0",
                description="Test schema",
                fields=fields,
                is_active=True,
            )
        )
        db_session.commit()

        schema = service.get_schema_by_name("test_schema_by_name")
        assert schema is not None
        assert schema.schema_name == "test_schema_by_name"

    def test_get_schema_for_document(self, db_session):
        service = ExtractionSchemaService()

        auto_fields = [{"field_name": "license_plate", "field_type": "string"}]
        health_fields = [{"field_name": "diagnosis_code", "field_type": "string"}]

        db_session.add_all([
            ExtractionSchemaModel(
                schema_name="test_auto",
                schema_version="1.0",
                description="Auto schema",
                business_line="auto_insurance",
                document_types=["accident_report"],
                fields=auto_fields,
                is_active=True,
            ),
            ExtractionSchemaModel(
                schema_name="test_health",
                schema_version="1.0",
                description="Health schema",
                business_line="health_insurance",
                document_types=["medical_report"],
                fields=health_fields,
                is_active=True,
            ),
        ])
        db_session.commit()

        auto_schema = service.get_schema_for_document(
            business_line="auto_insurance",
            document_type="accident_report",
        )
        assert auto_schema is not None
        assert auto_schema.schema_name == "test_auto"

        health_schema = service.get_schema_for_document(
            business_line="health_insurance",
            document_type="medical_report",
        )
        assert health_schema is not None
        assert health_schema.schema_name == "test_health"

    def test_create_schema(self, db_session):
        service = ExtractionSchemaService()

        fields = [
            FieldSchema(
                field_name="test_field",
                field_type=FieldDataTypeEnum.STRING,
                description="Test field",
                required=True,
            )
        ]

        schema_create = ExtractionSchemaCreate(
            schema_name="test_create_schema",
            schema_version="1.0",
            description="Test create schema",
            business_line="general",
            document_types=["test_doc"],
            fields=fields,
            created_by="test_user",
        )

        schema = service.create_schema(schema_create)
        assert schema is not None
        assert schema.schema_name == "test_create_schema"
        assert schema.id > 0

    def test_update_schema(self, db_session):
        service = ExtractionSchemaService()

        fields = [{"field_name": "old_field", "field_type": "string"}]
        schema_model = ExtractionSchemaModel(
            schema_name="test_update_schema",
            schema_version="1.0",
            description="Old description",
            fields=fields,
            is_active=True,
        )
        db_session.add(schema_model)
        db_session.commit()

        new_fields = [
            FieldSchema(
                field_name="new_field",
                field_type=FieldDataTypeEnum.NUMBER,
                description="New field",
            )
        ]

        update = ExtractionSchemaUpdate(
            description="New description",
            fields=new_fields,
        )

        updated = service.update_schema(schema_model.id, update)
        assert updated is not None
        assert updated.description == "New description"
        assert len(updated.fields) == 1
        assert updated.fields[0]["field_name"] == "new_field"

    def test_set_default_schema(self, db_session):
        service = ExtractionSchemaService()

        fields = [{"field_name": "test_field", "field_type": "string"}]
        schema1 = ExtractionSchemaModel(
            schema_name="schema1",
            schema_version="1.0",
            fields=fields,
            is_active=True,
            is_default=False,
        )
        schema2 = ExtractionSchemaModel(
            schema_name="schema2",
            schema_version="1.0",
            fields=fields,
            is_active=True,
            is_default=False,
        )
        db_session.add_all([schema1, schema2])
        db_session.commit()

        service.set_default_schema(schema2.id)
        db_session.refresh(schema2)
        assert schema2.is_default is True

        db_session.refresh(schema1)
        assert schema1.is_default is False

    def test_export_schema_to_yaml(self, db_session):
        service = ExtractionSchemaService()

        fields = [
            {
                "field_name": "test_field",
                "field_type": "string",
                "description": "Test field",
                "required": True,
                "validation_rules": {"min_length": 2, "max_length": 10},
                "examples": ["test", "example"],
            }
        ]
        schema = ExtractionSchemaModel(
            schema_name="test_export",
            schema_version="1.0",
            description="Test export schema",
            business_line="general",
            document_types=["test_doc"],
            fields=fields,
            is_active=True,
            created_by="test_user",
        )
        db_session.add(schema)
        db_session.commit()

        yaml_output = service.export_schema_to_yaml(schema.id)
        assert yaml_output is not None
        assert "test_export" in yaml_output
        assert "test_field" in yaml_output


class TestDocumentTypeDetector:
    def test_detect_pdf_with_text_layer(self):
        detector = DocumentTypeDetector()

        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
            try:
                import fitz
                doc = fitz.open()
                page = doc.new_page()
                page.insert_text((100, 100), "This is a test document with text layer. 患者姓名: 张三")
                doc.save(f.name)
                doc.close()

                result = detector.detect_document_type(
                    f.name,
                    "test_document.pdf",
                    "application/pdf",
                )

                assert result.has_text_layer is True
                assert result.is_scanned_document is False
                assert result.recommended_ocr is False
                assert result.text_density > 0
                assert result.page_count == 1

            finally:
                os.unlink(f.name)

    def test_detect_image_file(self):
        detector = DocumentTypeDetector()

        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            try:
                from PIL import Image
                img = Image.new("RGB", (100, 100), color="white")
                img.save(f.name, "PNG")

                result = detector.detect_document_type(
                    f.name,
                    "test_image.png",
                    "image/png",
                )

                assert result.has_text_layer is False
                assert result.is_scanned_document is True
                assert result.recommended_ocr is True
                assert result.optimal_processing_path == "ocr_only"

            finally:
                os.unlink(f.name)

    def test_detect_tables_in_text(self):
        detector = DocumentTypeDetector()

        text_with_tables = """
        费用明细表
        | 项目 | 数量 | 单价 | 金额 |
        |------|------|------|------|
        | 挂号费 | 1 | 50 | 50 |
        | 检查费 | 1 | 500 | 500 |
        合计: 550元
        """

        assert detector._detect_tables_in_text(text_with_tables) is True

        text_without_tables = """
        患者姓名: 张三
        诊断: 感冒
        日期: 2026-01-01
        """

        assert detector._detect_tables_in_text(text_without_tables) is False

    def test_count_tables_in_text(self):
        detector = DocumentTypeDetector()

        text = """
        表1: 费用明细
        表2: 药品清单
        表格3: 检查项目
        """

        count = detector._count_tables_in_text(text)
        assert count >= 3

    def test_determine_optimal_path(self):
        detector = DocumentTypeDetector()

        result1 = DocumentTypeDetectionResult(
            has_text_layer=True,
            text_layer_quality=0.9,
            is_scanned_document=False,
        )
        assert detector._determine_optimal_path(result1) == "text_only"

        result2 = DocumentTypeDetectionResult(
            has_text_layer=True,
            has_tables=True,
            text_layer_quality=0.7,
        )
        assert detector._determine_optimal_path(result2) == "text_with_table_recognition"

        result3 = DocumentTypeDetectionResult(
            has_handwriting=True,
            handwriting_confidence=0.8,
        )
        assert detector._determine_optimal_path(result3) == "ocr_with_handwriting"

        result4 = DocumentTypeDetectionResult(
            is_scanned_document=True,
            has_text_layer=False,
        )
        assert detector._determine_optimal_path(result4) == "ocr_with_layout"

    def test_get_processing_options(self):
        detector = DocumentTypeDetector()

        result = DocumentTypeDetectionResult(
            has_text_layer=True,
            has_tables=True,
            has_handwriting=False,
            recommended_ocr=False,
            text_layer_quality=0.9,
        )

        options = detector.get_processing_options(result)
        assert options["use_ocr"] is False
        assert options["detect_tables"] is True
        assert options["detect_handwriting"] is False
        assert options["prefer_text_layer"] is True


class TestBatchReview:
    def test_get_batch_review_task(self, db_session):
        service = ReviewService()

        doc = Document(
            filename="test_doc.pdf",
            original_filename="test_doc.pdf",
            storage_path="/tmp/test.pdf",
            document_type="pdf",
            page_count=2,
            status=DocumentStatus.NEEDS_REVIEW,
        )
        db_session.add(doc)
        db_session.flush()

        extraction = ExtractionResult(
            document_id=doc.id,
            schema_name="test_schema",
            schema_version="1.0",
            status="completed",
            overall_confidence=0.85,
        )
        db_session.add(extraction)
        db_session.flush()

        field1 = ExtractedField(
            extraction_result_id=extraction.id,
            field_name="patient_name",
            field_type="string",
            value="张三",
            confidence=0.95,
            is_low_confidence=False,
            page_number=1,
            bounding_box={"x1": 100, "y1": 100, "x2": 200, "y2": 120},
        )
        field2 = ExtractedField(
            extraction_result_id=extraction.id,
            field_name="total_amount",
            field_type="number",
            value="5000.00",
            confidence=0.55,
            is_low_confidence=True,
            page_number=1,
            bounding_box={"x1": 100, "y1": 200, "x2": 300, "y2": 220},
        )
        db_session.add_all([field1, field2])
        db_session.flush()

        review_task = ReviewTask(
            document_id=doc.id,
            extraction_result_id=extraction.id,
            status=ReviewStatus.PENDING,
            priority=ReviewPriority.MEDIUM,
            fields_to_review=[
                {"field_id": field2.id, "field_name": "total_amount"}
            ],
        )
        db_session.add(review_task)
        db_session.commit()

        result = service.get_batch_review_task(review_task.id)

        assert result is not None
        assert result["task_id"] == review_task.id
        assert len(result["extracted_fields"]) == 2
        assert len(result["low_confidence_fields"]) == 1
        assert result["low_confidence_fields"][0]["field_name"] == "total_amount"
        assert len(result["field_highlights"]) == 2
        assert "bounding_box" in result["field_highlights"][0]
        assert "color" in result["field_highlights"][0]

    def test_batch_confirm_all_fields(self, db_session):
        service = ReviewService()

        doc = Document(
            filename="test_doc.pdf",
            original_filename="test_doc.pdf",
            storage_path="/tmp/test.pdf",
            document_type="pdf",
            status=DocumentStatus.NEEDS_REVIEW,
        )
        db_session.add(doc)
        db_session.flush()

        extraction = ExtractionResult(
            document_id=doc.id,
            schema_name="test_schema",
            schema_version="1.0",
            status="completed",
            overall_confidence=0.8,
        )
        db_session.add(extraction)
        db_session.flush()

        field1 = ExtractedField(
            extraction_result_id=extraction.id,
            field_name="patient_name",
            field_type="string",
            value="张三",
            confidence=0.9,
            is_low_confidence=False,
            reviewed=False,
        )
        field2 = ExtractedField(
            extraction_result_id=extraction.id,
            field_name="total_amount",
            field_type="number",
            value="5000.00",
            confidence=0.6,
            is_low_confidence=True,
            reviewed=False,
        )
        db_session.add_all([field1, field2])
        db_session.flush()

        review_task = ReviewTask(
            document_id=doc.id,
            extraction_result_id=extraction.id,
            status=ReviewStatus.IN_PROGRESS,
            priority=ReviewPriority.MEDIUM,
            started_at=datetime.utcnow() - timedelta(minutes=5),
        )
        db_session.add(review_task)
        db_session.commit()

        request = BatchReviewConfirmRequest(
            task_id=review_task.id,
            completed_by="test_reviewer",
            confirm_all=True,
        )

        result = service.batch_confirm_review(request)

        assert result is not None
        assert result["confirmed_count"] == 2
        assert result["correction_count"] == 0
        assert result["all_fields_completed"] is True
        assert result["is_correct"] is True

        db_session.refresh(field1)
        db_session.refresh(field2)
        assert field1.reviewed is True
        assert field2.reviewed is True
        assert field1.reviewed_by == "test_reviewer"
        assert field2.reviewed_by == "test_reviewer"

    def test_batch_confirm_with_corrections(self, db_session):
        service = ReviewService()

        doc = Document(
            filename="test_doc.pdf",
            original_filename="test_doc.pdf",
            storage_path="/tmp/test.pdf",
            document_type="pdf",
            status=DocumentStatus.NEEDS_REVIEW,
        )
        db_session.add(doc)
        db_session.flush()

        extraction = ExtractionResult(
            document_id=doc.id,
            schema_name="test_schema",
            schema_version="1.0",
            status="completed",
            overall_confidence=0.7,
        )
        db_session.add(extraction)
        db_session.flush()

        field1 = ExtractedField(
            extraction_result_id=extraction.id,
            field_name="patient_name",
            field_type="string",
            value="张三",
            confidence=0.9,
            is_low_confidence=False,
            reviewed=False,
        )
        field2 = ExtractedField(
            extraction_result_id=extraction.id,
            field_name="total_amount",
            field_type="number",
            value="5000.00",
            confidence=0.55,
            is_low_confidence=True,
            reviewed=False,
        )
        db_session.add_all([field1, field2])
        db_session.flush()

        review_task = ReviewTask(
            document_id=doc.id,
            extraction_result_id=extraction.id,
            status=ReviewStatus.IN_PROGRESS,
            priority=ReviewPriority.MEDIUM,
            started_at=datetime.utcnow() - timedelta(minutes=10),
        )
        db_session.add(review_task)
        db_session.commit()

        request = BatchReviewConfirmRequest(
            task_id=review_task.id,
            completed_by="test_reviewer",
            field_ids_to_confirm=[field1.id],
            corrections=[
                FieldCorrection(
                    field_id=field2.id,
                    field_name="total_amount",
                    old_value="5000.00",
                    new_value="5500.00",
                    comment="金额错误，应该是5500",
                )
            ],
        )

        result = service.batch_confirm_review(request)

        assert result is not None
        assert result["confirmed_count"] == 1
        assert result["correction_count"] == 1
        assert result["is_correct"] is False

        db_session.refresh(field2)
        assert field2.value == "5500.00"
        assert field2.reviewed_value == "5500.00"
        assert field2.reviewed is True


class TestReviewEfficiencyStatistics:
    def test_get_review_efficiency_statistics(self, db_session):
        service = ReviewService()

        now = datetime.utcnow()

        reviewer_stats = [
            ("reviewer_a", 0.8, 5),
            ("reviewer_a", 0.9, 0),
            ("reviewer_a", 0.7, 3),
            ("reviewer_b", 0.6, 8),
            ("reviewer_b", 0.9, 0),
        ]

        for i, (reviewer, duration, corrections) in enumerate(reviewer_stats):
            days_ago = len(reviewer_stats) - i
            task_date = now - timedelta(days=days_ago)

            task = ReviewTask(
                document_id=i + 1,
                status=ReviewStatus.COMPLETED,
                priority=ReviewPriority.MEDIUM,
                completed_by=reviewer,
                completed_at=task_date,
                started_at=task_date - timedelta(minutes=int(duration * 10)),
                review_duration=duration * 60,
                is_correct=corrections == 0,
                correction_count=corrections,
            )
            db_session.add(task)

        db_session.commit()

        stats = service.get_review_efficiency_statistics(days=30)

        assert stats is not None
        assert stats["total_reviewed"] == len(reviewer_stats)
        assert stats["overall_pass_rate"] == 2 / len(reviewer_stats)
        assert stats["avg_corrections_per_task"] == sum(
            c for _, _, c in reviewer_stats
        ) / len(reviewer_stats)
        assert len(stats["daily_trends"]) > 0
        assert len(stats["reviewer_leaderboard"]) == 2

        reviewer_a_stats = next(
            r for r in stats["reviewer_leaderboard"] if r["reviewer"] == "reviewer_a"
        )
        assert reviewer_a_stats["total_reviews"] == 3
        assert reviewer_a_stats["pass_rate"] == 1 / 3

    def test_daily_trends_format(self, db_session):
        service = ReviewService()

        now = datetime.utcnow()

        for i in range(5):
            task_date = now - timedelta(days=i)
            task = ReviewTask(
                document_id=i + 1,
                status=ReviewStatus.COMPLETED,
                priority=ReviewPriority.MEDIUM,
                completed_by="test_reviewer",
                completed_at=task_date,
                started_at=task_date - timedelta(minutes=5),
                review_duration=300.0,
                is_correct=i % 2 == 0,
                correction_count=0 if i % 2 == 0 else 2,
            )
            db_session.add(task)

        db_session.commit()

        stats = service.get_review_efficiency_statistics(days=7)
        daily_trends = stats["daily_trends"]

        assert len(daily_trends) == 5

        for day_data in daily_trends:
            assert "date" in day_data
            assert "total_reviews" in day_data
            assert "avg_processing_time_seconds" in day_data
            assert "pass_rate" in day_data
            assert "correction_rate" in day_data
            assert 0 <= day_data["pass_rate"] <= 1
            assert 0 <= day_data["correction_rate"] <= 1


class TestExtractionServiceSchemaCompatibility:
    def test_run_extraction_with_schema_name(self, monkeypatch):
        from app.services.extraction_service import ExtractionService

        mock_schema_service = Mock()
        mock_schema = Mock()
        mock_schema.schema_name = "test_schema"
        mock_schema.schema_version = "1.0"
        mock_schema.description = "Test"
        mock_schema.document_types = ["test"]
        mock_schema.fields = [
            {"field_name": "field1", "field_type": "string", "description": "Test field", "required": True},
        ]
        mock_schema_service.get_schema_by_name.return_value = mock_schema

        mock_extractor = Mock()
        mock_extractor.get_default_insurance_schema.return_value = mock_schema
        mock_extractor.extract_fields.return_value = {
            "extraction_result_id": 1,
            "fields": [],
        }

        monkeypatch.setattr(
            "app.services.extraction_service.ExtractionSchemaService",
            lambda: mock_schema_service,
        )
        monkeypatch.setattr(
            "app.services.extraction_service.MultimodalExtractor",
            lambda: mock_extractor,
        )
        monkeypatch.setattr(
            "app.services.extraction_service.ValidationService",
            lambda: Mock(),
        )
        monkeypatch.setattr(
            "app.services.extraction_service.ReviewService",
            lambda: Mock(),
        )

        mock_doc = Mock()
        mock_doc.id = 1
        mock_doc.status = "preprocessed"

        mock_db = Mock()
        mock_db.query.return_value.filter.return_value.first.return_value = mock_doc
        mock_db.query.return_value.filter.return_value.first.side_effect = [
            mock_doc, None, None
        ]

        with patch("app.services.extraction_service.get_sync_db", return_value=mock_db):
            service = ExtractionService()
            service._instance = None
            service._initialized = False

            schema = service._resolve_extraction_schema(
                schema_name="test_schema"
            )

            assert schema is not None
            mock_schema_service.get_schema_by_name.assert_called_with("test_schema")

    def test_backward_compatibility_run_extraction(self, monkeypatch):
        from app.services.extraction_service import ExtractionService

        mock_schema = Mock()
        mock_schema.schema_name = "default_schema"

        mock_extractor = Mock()
        mock_extractor.get_default_insurance_schema.return_value = mock_schema

        monkeypatch.setattr(
            "app.services.extraction_service.ExtractionSchemaService",
            lambda: Mock(),
        )
        monkeypatch.setattr(
            "app.services.extraction_service.MultimodalExtractor",
            lambda: mock_extractor,
        )
        monkeypatch.setattr(
            "app.services.extraction_service.ValidationService",
            lambda: Mock(),
        )
        monkeypatch.setattr(
            "app.services.extraction_service.ReviewService",
            lambda: Mock(),
        )

        service = ExtractionService()
        service._instance = None
        service._initialized = False

        schema = service._resolve_extraction_schema()

        assert schema is not None
        mock_extractor.get_default_insurance_schema.assert_called_once()


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
