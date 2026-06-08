import os
import sys
import io
import json
import uuid
import time
import hashlib
import tempfile
import zipfile
from datetime import datetime, date, timedelta
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple, Generator

import pytest
from faker import Faker
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT_DIR = Path(__file__).parent.parent
sys.path.insert(0, str(ROOT_DIR))

os.environ["TESTING"] = "True"
os.environ["ENV"] = "test"
os.environ["LOG_LEVEL"] = "ERROR"

from app.core.config import Settings, get_settings
from app.core.database import Base, get_sync_db, init_db
from app.core.logging_config import get_logger
from app.schemas.common import BoundingBox, TextBlock, ImageRegion, TableData, ValidationError
from app.schemas.document import (
    PageInfo, StandardizedDocument, DocumentTypeEnum,
    ProcessingOptions, DocumentCreate, DocumentStatusEnum,
)
from app.schemas.extraction import (
    ExtractionSchema, FieldSchema, FieldDataTypeEnum,
    ExtractedFieldCreate, ExtractionResultCreate,
)
from app.schemas.model import (
    ABTestExperimentCreate, TrafficSplitStrategyEnum,
)
from app.models.document import Document
from app.models.extraction import ExtractionResult, ExtractedField, ExtractionStatus
from app.models.review import ReviewTask, ReviewStatus, ReviewPriority
from app.models.model import ModelVersion, ABTestExperiment, ModelType, ModelStatus
from app.schemas.model import ABTestStatusEnum, TrafficSplitStrategyEnum

logger = get_logger(__name__)
fake = Faker("zh_CN")


def get_test_settings() -> Settings:
    return Settings(
        ENV="test",
        LOG_LEVEL="ERROR",
        DATABASE_URL=os.getenv("TEST_DATABASE_URL", "sqlite:///./test.db"),
        DATABASE_SYNC_URL=os.getenv("TEST_DATABASE_SYNC_URL", "sqlite:///./test.db"),
        REDIS_URL=os.getenv("TEST_REDIS_URL", "redis://localhost:6379/15"),
        MINIO_ENDPOINT=os.getenv("TEST_MINIO_ENDPOINT", "localhost:9000"),
        MINIO_ACCESS_KEY="testminio",
        MINIO_SECRET_KEY="testminio123",
        MINIO_SECURE=False,
        MINIO_BUCKET="test-documents",
        MINIO_BUCKET_BATCHES="test-batches",
        ML_MODEL_CACHE_DIR="/tmp/docintel_test_cache",
        DEBUG=True,
        CELERY_BROKER_URL=os.getenv("TEST_REDIS_URL", "redis://localhost:6379/15"),
        CELERY_RESULT_BACKEND=os.getenv("TEST_REDIS_URL", "redis://localhost:6379/15"),
        CELERY_TASK_TIME_LIMIT=3600,
        CELERY_TASK_SOFT_TIME_LIMIT=3000,
        CELERY_WORKER_PREFETCH_MULTIPLIER=1,
        CELERY_MAX_RETRIES=3,
    )


@pytest.fixture(scope="session")
def test_settings():
    return get_test_settings()


@pytest.fixture(scope="session")
def mock_settings():
    import app.core.config
    original = app.core.config.get_settings
    app.core.config.get_settings = get_test_settings
    yield get_test_settings()
    app.core.config.get_settings = original


@pytest.fixture
def db_session(mock_settings, monkeypatch):
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    engine = create_engine("sqlite:///./test.db", connect_args={"check_same_thread": False})
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)

    TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
    session = TestingSessionLocal()

    import app.core.database
    original_sync_engine = app.core.database.sync_engine
    original_sync_session_local = app.core.database.SyncSessionLocal
    app.core.database.sync_engine = engine
    app.core.database.SyncSessionLocal = TestingSessionLocal

    def mock_get_sync_db():
        try:
            yield session
            session.commit()
        except Exception:
            session.rollback()
            raise

    monkeypatch.setattr(app.core.database, "get_sync_db", mock_get_sync_db)

    for service_module in [
        "app.services.ab_test_service",
        "app.services.review_service",
        "app.services.batch_service",
        "app.services.validation_service",
        "app.services.document_service",
    ]:
        try:
            mod = __import__(service_module, fromlist=["get_sync_db"])
            if hasattr(mod, "get_sync_db"):
                monkeypatch.setattr(mod, "get_sync_db", mock_get_sync_db)
        except (ImportError, AttributeError):
            pass

    try:
        yield session
    finally:
        session.close()
        app.core.database.sync_engine = original_sync_engine
        app.core.database.SyncSessionLocal = original_sync_session_local
        Base.metadata.drop_all(bind=engine)
        engine.dispose()
        if os.path.exists("./test.db"):
            os.remove("./test.db")


@pytest.fixture(scope="session")
def postgresql_container():
    pytest.importorskip("testcontainers")
    pytest.importorskip("testcontainers.postgresql")

    from testcontainers.postgres import PostgresContainer

    with PostgresContainer("postgres:16-alpine") as container:
        yield container


@pytest.fixture(scope="session")
def redis_container():
    pytest.importorskip("testcontainers")
    pytest.importorskip("testcontainers.redis")

    from testcontainers.redis import RedisContainer

    with RedisContainer("redis:7-alpine") as container:
        yield container


@pytest.fixture(scope="session")
def minio_container():
    pytest.importorskip("testcontainers")
    pytest.importorskip("testcontainers.core")

    from testcontainers.core.container import DockerContainer
    from testcontainers.core.waiting_utils import wait_for_logs

    class MinioContainer(DockerContainer):
        def __init__(self, image="minio/minio:latest", access_key="testminio", secret_key="testminio123"):
            super().__init__(image)
            self.access_key = access_key
            self.secret_key = secret_key
            self.with_exposed_ports(9000)
            self.with_env("MINIO_ROOT_USER", access_key)
            self.with_env("MINIO_ROOT_PASSWORD", secret_key)
            self.with_command("server /data")

        def get_connection_url(self):
            host = self.get_container_host_ip()
            port = self.get_exposed_port(9000)
            return f"{host}:{port}"

    with MinioContainer("minio/minio:latest") as container:
        try:
            wait_for_logs(container, "Waiting for all MinIO sub-systems to be ready", timeout=30)
        except:
            time.sleep(5)
        container.access_key = "testminio"
        container.secret_key = "testminio123"
        yield container


@pytest.fixture(scope="session")
def integration_db_url(postgresql_container):
    return postgresql_container.get_connection_url()


@pytest.fixture(scope="session")
def integration_redis_url(redis_container):
    return f"redis://{redis_container.get_container_host_ip()}:{redis_container.get_exposed_port(6379)}/0"


@pytest.fixture
def integration_settings(postgresql_container, redis_container, minio_container):
    settings = Settings(
        ENV="test",
        LOG_LEVEL="ERROR",
        DATABASE_URL=postgresql_container.get_connection_url(),
        REDIS_URL=f"redis://{redis_container.get_container_host_ip()}:{redis_container.get_exposed_port(6379)}/0",
        MINIO_ENDPOINT=f"{minio_container.get_container_host_ip()}:{minio_container.get_exposed_port(9000)}",
        MINIO_ACCESS_KEY=minio_container.access_key,
        MINIO_SECRET_KEY=minio_container.secret_key,
        MINIO_SECURE=False,
        MINIO_BUCKET="test-documents",
        ML_MODEL_CACHE_DIR="/tmp/docintel_integration_cache",
        DEBUG=True,
        AB_TEST_ENABLED=False,
    )

    import app.core.config
    original = app.core.config.get_settings
    app.core.config.get_settings = lambda: settings

    from app.core.database import init_db
    init_db()

    import app.services.storage
    app.services.storage.StorageService._instance = None

    yield settings

    app.core.config.get_settings = original


@pytest.fixture
def temp_dir():
    with tempfile.TemporaryDirectory() as tmpdir:
        yield Path(tmpdir)


@pytest.fixture
def sample_pdf_path(temp_dir):
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib.units import mm
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
    from reportlab.lib import colors
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont

    pdf_path = temp_dir / "medical_report_zh.pdf"

    try:
        pdfmetrics.registerFont(TTFont("NotoSans", "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"))
        font_name = "NotoSans"
    except:
        font_name = "Helvetica"

    doc = SimpleDocTemplate(
        str(pdf_path),
        pagesize=A4,
        rightMargin=20*mm,
        leftMargin=20*mm,
        topMargin=20*mm,
        bottomMargin=20*mm,
    )

    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        "CustomTitle",
        parent=styles["Heading1"],
        fontName=font_name,
        fontSize=18,
        alignment=1,
        spaceAfter=10,
    )
    heading_style = ParagraphStyle(
        "CustomHeading",
        parent=styles["Heading2"],
        fontName=font_name,
        fontSize=14,
        spaceAfter=8,
    )
    normal_style = ParagraphStyle(
        "CustomNormal",
        parent=styles["Normal"],
        fontName=font_name,
        fontSize=11,
        leading=16,
    )

    story = []

    story.append(Paragraph("医院诊断证明书", title_style))
    story.append(Spacer(1, 8))

    patient_name = fake.name()
    patient_id = fake.ssn()
    admission_date = fake.date_between(start_date="-30d", end_date="-1d")
    discharge_date = admission_date + timedelta(days=7)
    diagnosis_code = "J45.900"
    diagnosis_desc = "支气管哮喘"
    total_amount = round(fake.random_int(min=1000, max=50000) + fake.random_int() / 100, 2)

    story.append(Paragraph(f"<b>患者姓名：</b>{patient_name}", normal_style))
    story.append(Paragraph(f"<b>身份证号：</b>{patient_id}", normal_style))
    story.append(Paragraph(f"<b>性别：</b>{fake.random_element(['男', '女'])}", normal_style))
    story.append(Paragraph(f"<b>年龄：</b>{fake.random_int(min=18, max=80)}岁", normal_style))
    story.append(Spacer(1, 8))

    story.append(Paragraph("就诊信息", heading_style))
    story.append(Paragraph(f"<b>入院日期：</b>{admission_date.strftime('%Y-%m-%d')}", normal_style))
    story.append(Paragraph(f"<b>出院日期：</b>{discharge_date.strftime('%Y-%m-%d')}", normal_style))
    story.append(Paragraph(f"<b>就诊科室：</b>呼吸内科", normal_style))
    story.append(Paragraph(f"<b>主治医生：</b>{fake.name()}医生", normal_style))
    story.append(Spacer(1, 8))

    story.append(Paragraph("诊断信息", heading_style))
    story.append(Paragraph(f"<b>诊断编码：</b>{diagnosis_code}", normal_style))
    story.append(Paragraph(f"<b>诊断描述：</b>{diagnosis_desc}", normal_style))
    story.append(Spacer(1, 8))

    story.append(Paragraph("费用明细", heading_style))

    table_data = [
        ["项目", "类型", "金额（元）"],
        ["挂号费", "诊疗", "50.00"],
        ["检查费", "检查", "1,200.00"],
        ["化验费", "检验", "800.00"],
        ["药费", "药品", f"{total_amount * 0.4:.2f}"],
        ["治疗费", "治疗", f"{total_amount * 0.3:.2f}"],
        ["床位费", "住院", f"{total_amount * 0.3:.2f}"],
        ["合计", "", f"{total_amount:.2f}"],
    ]

    table = Table(table_data, colWidths=[100, 80, 80])
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.grey),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.whitesmoke),
        ("ALIGN", (0, 0), (-1, -1), "CENTER"),
        ("FONTNAME", (0, 0), (-1, -1), font_name),
        ("FONTSIZE", (0, 0), (-1, -1), 10),
        ("BOTTOMPADDING", (0, 0), (-1, 0), 8),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
    ]))
    story.append(table)
    story.append(Spacer(1, 12))

    story.append(Paragraph(f"<b>总金额：</b>¥ {total_amount:.2f}", normal_style))
    story.append(Paragraph(f"<b>发票日期：</b>{discharge_date.strftime('%Y-%m-%d')}", normal_style))
    story.append(Spacer(1, 20))

    story.append(Paragraph("医生签名：______________", normal_style))
    story.append(Paragraph(f"医院盖章：{fake.city()}人民医院", normal_style))
    story.append(Paragraph(f"日期：{discharge_date.strftime('%Y年%m月%d日')}", normal_style))

    doc.build(story)

    return {
        "path": pdf_path,
        "patient_name": patient_name,
        "patient_id": patient_id,
        "diagnosis_code": diagnosis_code,
        "diagnosis_desc": diagnosis_desc,
        "total_amount": total_amount,
        "admission_date": admission_date.strftime("%Y-%m-%d"),
        "discharge_date": discharge_date.strftime("%Y-%m-%d"),
    }


@pytest.fixture
def sample_image_path(temp_dir):
    img = Image.new("RGB", (1200, 1600), color="white")
    draw = ImageDraw.Draw(img)

    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", 32)
        small_font = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", 24)
    except:
        font = ImageFont.load_default()
        small_font = ImageFont.load_default()

    patient_name = fake.name()
    diagnosis_code = "I10"
    amount = "3,500.00"

    draw.text((100, 80), "医疗费用发票", fill="black", font=font)
    draw.text((100, 180), f"姓名: {patient_name}", fill="black", font=small_font)
    draw.text((100, 240), f"诊断编码: {diagnosis_code}", fill="black", font=small_font)
    draw.text((100, 300), f"金额: ¥{amount}", fill="black", font=small_font)
    draw.text((100, 360), f"日期: 2024-01-15", fill="black", font=small_font)

    image_path = temp_dir / "medical_invoice.jpg"
    img.save(image_path, "JPEG", quality=85)

    return {
        "path": image_path,
        "patient_name": patient_name,
        "diagnosis_code": diagnosis_code,
        "amount": amount,
    }


@pytest.fixture
def low_quality_image_path(temp_dir):
    img = Image.new("RGB", (800, 600), color="white")
    draw = ImageDraw.Draw(img)

    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", 20)
    except:
        font = ImageFont.load_default()

    draw.text((50, 50), "模糊的文本内容", fill=(180, 180, 180), font=font)
    draw.text((50, 100), "难以识别的文字", fill=(200, 200, 200), font=font)

    for _ in range(500):
        x = fake.random_int(min=0, max=799)
        y = fake.random_int(min=0, max=599)
        draw.point((x, y), fill=(fake.random_int(0, 50),) * 3)

    img = img.rotate(15, fillcolor="white")
    img = img.filter(ImageFilter.GaussianBlur(radius=2))
    img = img.filter(ImageFilter.UnsharpMask(radius=1, percent=50))

    image_path = temp_dir / "low_quality_scan.jpg"
    img.save(image_path, "JPEG", quality=30)

    return image_path


@pytest.fixture
def multi_page_pdf_path(temp_dir):
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
    from reportlab.lib.units import mm
    from reportlab.platypus import (
        SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak
    )
    from reportlab.lib import colors

    pdf_path = temp_dir / "multi_page_invoice.pdf"
    doc = SimpleDocTemplate(str(pdf_path), pagesize=A4)

    styles = getSampleStyleSheet()
    normal_style = ParagraphStyle("Normal", parent=styles["Normal"], fontSize=11, leading=14)
    heading_style = ParagraphStyle("Heading", parent=styles["Heading2"], fontSize=14, spaceAfter=8)

    story = []

    story.append(Paragraph("费用明细 - 第1页", heading_style))
    table_data_1 = [
        ["项目编号", "项目名称", "单价", "数量", "金额"],
        ["001", "血常规检查", "50.00", "1", "50.00"],
        ["002", "肝功能检查", "120.00", "1", "120.00"],
        ["003", "肾功能检查", "100.00", "1", "100.00"],
        ["004", "心电图", "80.00", "1", "80.00"],
    ]
    t1 = Table(table_data_1, colWidths=[60, 150, 60, 50, 70])
    t1.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.grey),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.whitesmoke),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
    ]))
    story.append(t1)
    story.append(PageBreak())

    story.append(Paragraph("费用明细 - 第2页", heading_style))
    table_data_2 = [
        ["项目编号", "项目名称", "单价", "数量", "金额"],
        ["005", "胸部CT", "800.00", "1", "800.00"],
        ["006", "药品A", "15.00", "10", "150.00"],
        ["007", "药品B", "25.00", "7", "175.00"],
        ["008", "输液费", "30.00", "3", "90.00"],
        ["", "", "", "合计", "1,565.00"],
    ]
    t2 = Table(table_data_2, colWidths=[60, 150, 60, 50, 70])
    t2.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.grey),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.whitesmoke),
        ("GRID", (0, 0), (-1, -1), 0.5, colors.grey),
    ]))
    story.append(t2)

    doc.build(story)
    return pdf_path


@pytest.fixture
def encrypted_pdf_path(temp_dir):
    from reportlab.lib.pagesizes import A4
    from reportlab.platypus import SimpleDocTemplate, Paragraph
    from reportlab.lib.styles import getSampleStyleSheet
    import pikepdf

    pdf_path = temp_dir / "encrypted_report.pdf"

    doc = SimpleDocTemplate(str(pdf_path), pagesize=A4)
    styles = getSampleStyleSheet()
    story = [Paragraph("This is an encrypted document", styles["Normal"])]
    doc.build(story)

    encrypted_path = temp_dir / "encrypted_report_secure.pdf"
    with pikepdf.open(pdf_path) as pdf:
        pdf.save(encrypted_path, encryption=pikepdf.Encryption(owner="owner_pass", user="user_pass"))

    return encrypted_path


@pytest.fixture
def sample_zip_path(temp_dir, sample_pdf_path, sample_image_path):
    zip_path = temp_dir / "documents_batch.zip"

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(sample_pdf_path["path"], arcname="report_1.pdf")
        zf.write(sample_image_path["path"], arcname="invoice_1.jpg")

        from reportlab.lib.pagesizes import A4
        from reportlab.platypus import SimpleDocTemplate, Paragraph
        from reportlab.lib.styles import getSampleStyleSheet

        for i in range(3):
            doc_path = temp_dir / f"doc_{i}.pdf"
            doc = SimpleDocTemplate(str(doc_path), pagesize=A4)
            story = [Paragraph(f"Document {i}", getSampleStyleSheet()["Normal"])]
            doc.build(story)
            zf.write(doc_path, arcname=f"doc_{i}.pdf")

    return zip_path


@pytest.fixture
def extraction_schema():
    return ExtractionSchema(
        schema_name="insurance_claim",
        schema_version="1.0",
        description="保险理赔信息抽取Schema",
        fields=[
            FieldSchema(
                field_name="patient_name",
                description="患者姓名",
                data_type=FieldDataTypeEnum.STRING,
                required=True,
                confidence_threshold=0.7,
            ),
            FieldSchema(
                field_name="patient_id",
                description="患者身份证号",
                data_type=FieldDataTypeEnum.STRING,
                required=True,
                confidence_threshold=0.8,
            ),
            FieldSchema(
                field_name="diagnosis_code",
                description="ICD-10诊断编码",
                data_type=FieldDataTypeEnum.STRING,
                required=True,
                confidence_threshold=0.75,
            ),
            FieldSchema(
                field_name="diagnosis_description",
                description="诊断描述",
                data_type=FieldDataTypeEnum.STRING,
                required=False,
                confidence_threshold=0.6,
            ),
            FieldSchema(
                field_name="total_amount",
                description="总费用金额",
                data_type=FieldDataTypeEnum.NUMBER,
                required=True,
                confidence_threshold=0.8,
            ),
            FieldSchema(
                field_name="invoice_date",
                description="发票日期",
                data_type=FieldDataTypeEnum.DATE,
                required=True,
                confidence_threshold=0.7,
            ),
        ],
    )


@pytest.fixture
def mock_standardized_doc(sample_pdf_path):
    sample_data = sample_pdf_path

    pages = []
    text_blocks = []

    texts = [
        (50, 50, 300, 100, "医院诊断证明书"),
        (50, 120, 300, 150, f"患者姓名：{sample_data['patient_name']}"),
        (50, 160, 300, 190, f"身份证号：{sample_data['patient_id']}"),
        (50, 240, 300, 270, f"诊断编码：{sample_data['diagnosis_code']}"),
        (50, 280, 300, 310, f"诊断描述：{sample_data['diagnosis_desc']}"),
        (50, 400, 300, 430, f"总金额：{sample_data['total_amount']}"),
        (50, 440, 300, 470, f"发票日期：{sample_data['discharge_date']}"),
        (400, 550, 550, 580, "医生签名"),
    ]

    for i, (x1, y1, x2, y2, text) in enumerate(texts):
        text_blocks.append(TextBlock(
            text=text,
            bbox=BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2),
            block_type="text",
            page_number=1,
            confidence=0.95,
        ))

    pages.append(PageInfo(
        page_number=1,
        width=595,
        height=842,
        text_blocks=text_blocks,
        tables=[
            TableData(
                headers=["项目", "类型", "金额"],
                rows=[
                    ["挂号费", "诊疗", "50.00"],
                    ["检查费", "检查", "1200.00"],
                    ["合计", "", f"{sample_data['total_amount']}"],
                ],
                row_count=3,
                col_count=3,
            )
        ],
        image_regions=[],
        ocr_confidence=0.95,
    ))

    return StandardizedDocument(
        document_id=1,
        original_filename="medical_report_zh.pdf",
        document_type=DocumentTypeEnum.PDF,
        page_count=1,
        pages=pages,
        language="zh",
        preprocessing_time=0.5,
    )


@pytest.fixture
def sample_extraction_result(db_session, mock_standardized_doc, extraction_schema):
    doc = Document(
        id=1,
        original_filename=mock_standardized_doc.original_filename,
        filename=mock_standardized_doc.original_filename,
        storage_path="/tmp/test.pdf",
        status=DocumentStatusEnum.EXTRACTED,
        page_count=1,
    )
    db_session.add(doc)
    db_session.commit()

    result = ExtractionResult(
        document_id=1,
        schema_name=extraction_schema.schema_name,
        schema_version=extraction_schema.schema_version,
        overall_confidence=0.85,
        status=ExtractionStatus.COMPLETED,
    )
    db_session.add(result)
    db_session.commit()

    fields = [
        ExtractedField(
            extraction_result_id=result.id,
            field_name="patient_name",
            value="张三",
            confidence=0.92,
            is_low_confidence=False,
        ),
        ExtractedField(
            extraction_result_id=result.id,
            field_name="diagnosis_code",
            value="J45.900",
            confidence=0.88,
            is_low_confidence=False,
        ),
        ExtractedField(
            extraction_result_id=result.id,
            field_name="total_amount",
            value="3500.00",
            confidence=0.75,
            is_low_confidence=True,
        ),
    ]
    db_session.add_all(fields)
    db_session.commit()

    return result


@pytest.fixture
def sample_review_task(db_session, sample_extraction_result):
    task = ReviewTask(
        document_id=1,
        extraction_result_id=sample_extraction_result.id,
        status=ReviewStatus.PENDING,
        priority=ReviewPriority.HIGH,
        fields_to_review=[{"field_name": "total_amount", "confidence": 0.75}],
    )
    db_session.add(task)
    db_session.commit()
    return task


@pytest.fixture
def sample_model_versions(db_session):
    v1 = ModelVersion(
        model_name="extraction",
        model_type=ModelType.EXTRACTION,
        version="1.0.0",
        local_path="/models/extraction_v1.pt",
        status=ModelStatus.PRODUCTION,
        metrics={"accuracy": 0.85, "f1": 0.83},
        is_default=True,
    )
    v2 = ModelVersion(
        model_name="extraction",
        model_type=ModelType.EXTRACTION,
        version="2.0.0",
        local_path="/models/extraction_v2.pt",
        status=ModelStatus.STAGING,
        metrics={"accuracy": 0.90, "f1": 0.88},
    )
    db_session.add_all([v1, v2])
    db_session.commit()
    return v1, v2


@pytest.fixture
def sample_ab_test_experiment(db_session, sample_model_versions):
    v1, v2 = sample_model_versions
    experiment = ABTestExperiment(
        experiment_name="extraction_v1_vs_v2",
        model_name="extraction",
        variant_a_model_id=v1.id,
        variant_b_model_id=v2.id,
        traffic_split_a=50.0,
        traffic_split_b=50.0,
        strategy=TrafficSplitStrategyEnum.RANDOM,
        primary_metric="accuracy",
        status=ABTestStatusEnum.RUNNING,
        started_at=datetime.utcnow(),
    )
    db_session.add(experiment)
    db_session.commit()
    return experiment


@pytest.fixture
def validation_context():
    return {
        "max_amount": 1000000,
        "document_id": 1,
        "extraction_result_id": 1,
    }


@pytest.fixture
def mock_ocr_engine():
    class MockOCREngine:
        def __init__(self, confidence=0.9):
            self._confidence = confidence
            self.called = False

        def is_available(self):
            return True

        def ocr_image(self, image, page_number=1):
            self.called = True
            return [
                TextBlock(
                    text="OCR识别的文本",
                    bbox=BoundingBox(x1=0, y1=0, x2=100, y2=30),
                    confidence=self._confidence,
                    page_number=page_number,
                )
            ]

        def get_ocr_metadata(self):
            return {"engine": "paddleocr", "version": "test"}

    return MockOCREngine


@pytest.fixture
def mock_layout_analyzer():
    from app.ml.layout_analyzer import LayoutRegion, RegionType

    class MockLayoutAnalyzer:
        def analyze_layout(self, standardized_doc):
            regions = []
            for page_idx, page in enumerate(standardized_doc.pages):
                page_regions = [
                    LayoutRegion(
                        region_id=f"region_{page_idx}_0",
                        region_type=RegionType.TITLE,
                        bbox=BoundingBox(x1=50, y1=50, x2=500, y2=100),
                        page_number=page_idx + 1,
                        confidence=0.95,
                        text_blocks=page.text_blocks[:1] if page.text_blocks else [],
                    ),
                    LayoutRegion(
                        region_id=f"region_{page_idx}_1",
                        region_type=RegionType.PARAGRAPH,
                        bbox=BoundingBox(x1=50, y1=120, x2=500, y2=300),
                        page_number=page_idx + 1,
                        confidence=0.92,
                        text_blocks=page.text_blocks[1:5] if len(page.text_blocks) > 5 else [],
                    ),
                    LayoutRegion(
                        region_id=f"region_{page_idx}_2",
                        region_type=RegionType.TABLE,
                        bbox=BoundingBox(x1=50, y1=320, x2=500, y2=500),
                        page_number=page_idx + 1,
                        confidence=0.90,
                    ),
                    LayoutRegion(
                        region_id=f"region_{page_idx}_3",
                        region_type=RegionType.SIGNATURE,
                        bbox=BoundingBox(x1=400, y1=550, x2=550, y2=600),
                        page_number=page_idx + 1,
                        confidence=0.85,
                    ),
                ]
                regions.extend(page_regions)
            return {
                "regions": [
                    {
                        "region_id": r.region_id,
                        "region_type": r.region_type.value,
                        "bbox": r.bbox.model_dump(),
                        "page_number": r.page_number,
                        "confidence": r.confidence,
                        "text": " ".join(tb.text for tb in r.text_blocks),
                    }
                    for r in regions
                ],
                "document_tree": {"node_id": "root", "node_type": "document", "children": []},
            }

    return MockLayoutAnalyzer()


@pytest.fixture
def low_confidence_text_blocks():
    return [
        TextBlock(
            text="模糊文本1",
            bbox=BoundingBox(x1=0, y1=0, x2=100, y2=30),
            confidence=0.45,
            page_number=1,
        ),
        TextBlock(
            text="模糊文本2",
            bbox=BoundingBox(x1=0, y1=35, x2=100, y2=65),
            confidence=0.38,
            page_number=1,
        ),
    ]
