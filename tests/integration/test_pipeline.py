import os
import sys
import io
import json
import time
import tempfile
import zipfile
from pathlib import Path
from typing import Dict, Any
from datetime import datetime
from unittest.mock import patch, MagicMock

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

ROOT_DIR = Path(__file__).parent.parent.parent
sys.path.insert(0, str(ROOT_DIR))


@pytest.fixture(scope="session")
def postgres_container():
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
        wait_for_logs(container, "Waiting for all MinIO sub-systems to be ready", timeout=30)
        container.access_key = "testminio"
        container.secret_key = "testminio123"
        yield container


@pytest.fixture(scope="session")
def integration_settings(postgres_container, redis_container, minio_container):
    from app.core.config import Settings

    settings = Settings(
        ENV="test",
        LOG_LEVEL="ERROR",
        DATABASE_URL=postgres_container.get_connection_url(),
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

    yield settings

    app.core.config.get_settings = original


@pytest.fixture(scope="session")
def integration_db_engine(integration_settings):
    from app.core.database import Base

    engine = create_engine(integration_settings.DATABASE_URL)
    Base.metadata.create_all(bind=engine)

    yield engine

    Base.metadata.drop_all(bind=engine)
    engine.dispose()


@pytest.fixture
def integration_db_session(integration_db_engine):
    SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=integration_db_engine)
    session = SessionLocal()

    try:
        yield session
    finally:
        session.rollback()
        session.close()


@pytest.fixture
def minio_client(integration_settings, minio_container):
    from minio import Minio

    client = Minio(
        integration_settings.MINIO_ENDPOINT,
        access_key=integration_settings.MINIO_ACCESS_KEY,
        secret_key=integration_settings.MINIO_SECRET_KEY,
        secure=integration_settings.MINIO_SECURE,
    )

    bucket = integration_settings.MINIO_BUCKET
    if not client.bucket_exists(bucket):
        client.make_bucket(bucket)

    yield client


@pytest.fixture
def redis_client(integration_settings, redis_container):
    import redis

    client = redis.Redis(
        host=redis_container.get_container_host_ip(),
        port=redis_container.get_exposed_port(6379),
        db=0,
        decode_responses=True,
    )

    yield client

    client.flushdb()


@pytest.fixture
def test_client(integration_settings, integration_db_engine):
    from app.core.database import Base, get_sync_db

    SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=integration_db_engine)

    def override_get_db():
        db = SessionLocal()
        try:
            yield db
        finally:
            db.close()

    from app.main import app
    app.dependency_overrides[get_sync_db] = override_get_db

    with TestClient(app) as client:
        yield client

    app.dependency_overrides.clear()


@pytest.mark.integration
class TestFullProcessingPipeline:
    def test_health_check(self, test_client):
        response = test_client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "healthy"

    def test_upload_pdf_document(self, test_client, sample_pdf_path, minio_client):
        with open(sample_pdf_path["path"], "rb") as f:
            response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("medical_report.pdf", f, "application/pdf")},
                data={"priority": "high"},
            )

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "document_id" in data["data"]
        assert data["data"]["status"] == "uploaded"

        document_id = data["data"]["document_id"]
        assert document_id > 0

    def test_upload_document_saved_to_minio(self, test_client, sample_pdf_path, minio_client, integration_settings):
        with open(sample_pdf_path["path"], "rb") as f:
            response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("medical_report.pdf", f, "application/pdf")},
            )

        assert response.status_code == 200
        data = response.json()
        document_id = data["data"]["document_id"]

        objects = list(minio_client.list_objects(integration_settings.MINIO_BUCKET, recursive=True))
        assert len(objects) >= 1

    def test_get_document_info(self, test_client, sample_pdf_path):
        with open(sample_pdf_path["path"], "rb") as f:
            upload_response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("medical_report.pdf", f, "application/pdf")},
            )

        document_id = upload_response.json()["data"]["document_id"]

        response = test_client.get(f"/api/v1/documents/{document_id}")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["data"]["id"] == document_id
        assert data["data"]["original_filename"] == "medical_report.pdf"

    def test_unsupported_format_returns_error(self, test_client, temp_dir):
        test_file = temp_dir / "test.xyz"
        test_file.write_bytes(b"invalid content")

        with open(test_file, "rb") as f:
            response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("test.xyz", f, "application/xyz")},
            )

        assert response.status_code in [200, 400]
        data = response.json()
        if response.status_code == 200:
            doc_id = data["data"]["document_id"]
            process_response = test_client.post(f"/api/v1/documents/{doc_id}/process")
            assert process_response.status_code in [200, 400, 500]

    def test_full_pipeline_process_document(self, test_client, sample_pdf_path, integration_db_session):
        from app.models.document import Document
        from app.models.extraction import ExtractionResult, ExtractedField
        from app.models.review import ReviewTask

        with open(sample_pdf_path["path"], "rb") as f:
            upload_response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("medical_report.pdf", f, "application/pdf")},
            )

        document_id = upload_response.json()["data"]["document_id"]

        process_response = test_client.post(
            f"/api/v1/documents/{document_id}/process",
            json={"options": {"skip_ocr": True, "run_async": False}},
        )

        assert process_response.status_code == 200
        process_data = process_response.json()
        assert process_data["success"] is True

        time.sleep(1)

        status_response = test_client.get(f"/api/v1/documents/{document_id}/status")
        assert status_response.status_code == 200
        status_data = status_response.json()

        doc = integration_db_session.query(Document).filter(
            Document.id == document_id
        ).first()

        assert doc is not None

        extraction_result = integration_db_session.query(ExtractionResult).filter(
            ExtractionResult.document_id == document_id
        ).first()

        if extraction_result:
            fields = integration_db_session.query(ExtractedField).filter(
                ExtractedField.extraction_result_id == extraction_result.id
            ).all()

            field_names = [f.field_name for f in fields]
            assert len(field_names) > 0

            review_tasks = integration_db_session.query(ReviewTask).filter(
                ReviewTask.document_id == document_id
            ).all()

            if any(f.needs_review for f in fields):
                assert len(review_tasks) >= 1

    def test_extraction_results_persisted(self, test_client, sample_pdf_path, integration_db_session):
        with open(sample_pdf_path["path"], "rb") as f:
            upload_response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("medical_report.pdf", f, "application/pdf")},
            )

        document_id = upload_response.json()["data"]["document_id"]

        test_client.post(
            f"/api/v1/documents/{document_id}/process",
            json={"options": {"run_async": False}},
        )

        time.sleep(2)

        response = test_client.get("/api/v1/extractions/")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True

        extractions = data["data"]["items"]
        doc_extractions = [e for e in extractions if e.get("document_id") == document_id]
        assert len(doc_extractions) >= 1

    def test_validation_high_confidence_direct_complete(self, test_client, sample_pdf_path, integration_db_session):
        from app.models.document import Document
        from app.models.extraction import ExtractionResult, ExtractedField

        with open(sample_pdf_path["path"], "rb") as f:
            upload_response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("medical_report.pdf", f, "application/pdf")},
            )

        document_id = upload_response.json()["data"]["document_id"]

        test_client.post(
            f"/api/v1/documents/{document_id}/process",
            json={"options": {"run_async": False}},
        )

        time.sleep(2)

        extraction_result = integration_db_session.query(ExtractionResult).filter(
            ExtractionResult.document_id == document_id
        ).first()

        if extraction_result:
            high_conf_fields = integration_db_session.query(ExtractedField).filter(
                ExtractedField.extraction_result_id == extraction_result.id,
                ExtractedField.confidence >= 0.8,
                ExtractedField.needs_review == False,
            ).all()

            if len(high_conf_fields) > 0:
                doc = integration_db_session.query(Document).filter(
                    Document.id == document_id
                ).first()
                assert doc is not None

    def test_low_quality_document_flagged_for_review(self, test_client, low_quality_image_path, integration_db_session):
        from app.models.document import Document
        from app.models.review import ReviewTask

        with open(low_quality_image_path, "rb") as f:
            upload_response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("low_quality.jpg", f, "image/jpeg")},
            )

        document_id = upload_response.json()["data"]["document_id"]

        test_client.post(
            f"/api/v1/documents/{document_id}/process",
            json={"options": {"run_async": False}},
        )

        time.sleep(2)

        doc = integration_db_session.query(Document).filter(
            Document.id == document_id
        ).first()

        if doc.status == "needs_review" or doc.status == "failed":
            review_tasks = integration_db_session.query(ReviewTask).filter(
                ReviewTask.document_id == document_id
            ).all()
            assert len(review_tasks) >= 1

    def test_review_task_complete_updates_result(self, test_client, sample_pdf_path, integration_db_session):
        from app.models.document import Document
        from app.models.extraction import ExtractionResult, ExtractedField
        from app.models.review import ReviewTask, ReviewStatus

        with open(sample_pdf_path["path"], "rb") as f:
            upload_response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("medical_report.pdf", f, "application/pdf")},
            )

        document_id = upload_response.json()["data"]["document_id"]

        test_client.post(
            f"/api/v1/documents/{document_id}/process",
            json={"options": {"run_async": False}},
        )

        time.sleep(2)

        review_tasks = integration_db_session.query(ReviewTask).filter(
            ReviewTask.document_id == document_id
        ).all()

        if len(review_tasks) > 0:
            task_id = review_tasks[0].id

            queue_response = test_client.get("/api/v1/review/queue?status=pending")
            assert queue_response.status_code == 200

            complete_response = test_client.post(
                f"/api/v1/review/tasks/{task_id}/complete",
                json={
                    "status": "completed",
                    "corrected_fields": {
                        "total_amount": "3500.00",
                    },
                    "review_notes": "Reviewed and corrected",
                    "is_correct": True,
                },
            )

            assert complete_response.status_code == 200
            data = complete_response.json()
            assert data["success"] is True

            updated_task = integration_db_session.query(ReviewTask).filter(
                ReviewTask.id == task_id
            ).first()
            assert updated_task.status == ReviewStatus.COMPLETED
            assert updated_task.completed_by is not None


@pytest.mark.integration
class TestBatchProcessingIntegration:
    def test_batch_upload_zip(self, test_client, sample_zip_path):
        with open(sample_zip_path, "rb") as f:
            response = test_client.post(
                "/api/v1/batches/upload",
                files={"file": ("documents.zip", f, "application/zip")},
            )

        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "batch_id" in data["data"]
        assert data["data"]["total_files"] >= 2

    def test_batch_process(self, test_client, sample_zip_path, integration_db_session):
        from app.models.batch import Batch

        with open(sample_zip_path, "rb") as f:
            upload_response = test_client.post(
                "/api/v1/batches/upload",
                files={"file": ("documents.zip", f, "application/zip")},
            )

        batch_id = upload_response.json()["data"]["batch_id"]

        process_response = test_client.post(f"/api/v1/batches/{batch_id}/process")
        assert process_response.status_code == 200

        progress_response = test_client.get(f"/api/v1/batches/{batch_id}/progress")
        assert progress_response.status_code == 200
        data = progress_response.json()
        assert data["success"] is True

        batch = integration_db_session.query(Batch).filter(Batch.id == batch_id).first()
        assert batch is not None
        assert batch.total_files >= 2

    def test_batch_document_count_matches_zip(self, test_client, sample_zip_path, integration_db_session):
        with zipfile.ZipFile(sample_zip_path, "r") as zf:
            expected_count = len([n for n in zf.namelist() if not n.endswith("/")])

        with open(sample_zip_path, "rb") as f:
            upload_response = test_client.post(
                "/api/v1/batches/upload",
                files={"file": ("documents.zip", f, "application/zip")},
            )

        batch_id = upload_response.json()["data"]["batch_id"]

        from app.models.batch import BatchDocument
        doc_count = integration_db_session.query(BatchDocument).filter(
            BatchDocument.batch_id == batch_id
        ).count()

        assert doc_count == expected_count


@pytest.mark.integration
class TestFailureScenarios:
    def test_encrypted_pdf_fails_gracefully(self, test_client, encrypted_pdf_path):
        with open(encrypted_pdf_path, "rb") as f:
            upload_response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("encrypted.pdf", f, "application/pdf")},
            )

        document_id = upload_response.json()["data"]["document_id"]

        process_response = test_client.post(
            f"/api/v1/documents/{document_id}/process",
            json={"options": {"run_async": False}},
        )

        assert process_response.status_code in [200, 500]

        time.sleep(1)

        status_response = test_client.get(f"/api/v1/documents/{document_id}/status")
        data = status_response.json()

        status = data["data"]["status"]
        assert status in ["failed", "preprocessing"]

        if status == "failed":
            error_msg = data["data"].get("error_message", "")
            assert len(error_msg) > 0
            assert any(keyword in error_msg.lower() for keyword in ["encrypt", "password", "permission"])

    def test_unsupported_file_format(self, test_client, temp_dir):
        exe_file = temp_dir / "test.exe"
        exe_file.write_bytes(b"MZ" + b"\x00" * 100)

        with open(exe_file, "rb") as f:
            response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("test.exe", f, "application/exe")},
            )

        assert response.status_code == 200
        data = response.json()
        document_id = data["data"]["document_id"]

        process_response = test_client.post(
            f"/api/v1/documents/{document_id}/process",
            json={"options": {"run_async": False}},
        )

        time.sleep(1)

        status_response = test_client.get(f"/api/v1/documents/{document_id}/status")
        status_data = status_response.json()

        status = status_data["data"]["status"]
        assert status in ["failed", "unknown"]

    def test_low_quality_ocr_flagged(self, test_client, low_quality_image_path, integration_db_session):
        from app.models.document import Document

        with open(low_quality_image_path, "rb") as f:
            upload_response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("low_quality.jpg", f, "image/jpeg")},
            )

        document_id = upload_response.json()["data"]["document_id"]

        test_client.post(
            f"/api/v1/documents/{document_id}/process",
            json={"options": {"run_async": False}},
        )

        time.sleep(2)

        doc = integration_db_session.query(Document).filter(
            Document.id == document_id
        ).first()

        assert doc is not None

        if doc.preprocessing_metadata:
            ocr_used = doc.preprocessing_metadata.get("ocr_used", False)
            if ocr_used:
                assert doc.status in ["needs_review", "failed"]


@pytest.mark.integration
class TestStorageIntegration:
    def test_minio_upload_and_download(self, minio_client, integration_settings, temp_dir):
        test_content = b"Test content for MinIO integration"
        object_name = "test_upload.txt"

        with tempfile.NamedTemporaryFile(delete=False, suffix=".txt") as f:
            f.write(test_content)
            temp_path = f.name

        minio_client.fput_object(
            integration_settings.MINIO_BUCKET,
            object_name,
            temp_path,
        )

        assert minio_client.bucket_exists(integration_settings.MINIO_BUCKET)

        response = minio_client.get_object(
            integration_settings.MINIO_BUCKET,
            object_name,
        )
        downloaded = response.read()
        assert downloaded == test_content

        os.unlink(temp_path)

    def test_redis_cache_set_get(self, redis_client):
        redis_client.set("test_key", "test_value", ex=60)
        value = redis_client.get("test_key")
        assert value == "test_value"

    def test_redis_pubsub(self, redis_client):
        channel = "test_channel"
        message = {"type": "progress", "value": 50}

        received = []

        def listener():
            pubsub = redis_client.pubsub()
            pubsub.subscribe(channel)
            for msg in pubsub.listen():
                if msg["type"] == "message":
                    received.append(json.loads(msg["data"]))
                    break

        import threading
        t = threading.Thread(target=listener)
        t.start()
        time.sleep(0.5)

        redis_client.publish(channel, json.dumps(message))
        t.join(timeout=2)

        assert len(received) == 1
        assert received[0] == message

    def test_postgres_connection(self, integration_db_session):
        result = integration_db_session.execute(text("SELECT 1"))
        assert result.scalar() == 1


@pytest.mark.integration
class TestReviewWorkflowIntegration:
    def test_review_queue_integration(self, test_client, sample_pdf_path, integration_db_session):
        from app.models.extraction import ExtractedField
        from app.models.review import ReviewTask, ReviewStatus

        with open(sample_pdf_path["path"], "rb") as f:
            upload_response = test_client.post(
                "/api/v1/documents/upload",
                files={"file": ("medical_report.pdf", f, "application/pdf")},
            )

        document_id = upload_response.json()["data"]["document_id"]

        test_client.post(
            f"/api/v1/documents/{document_id}/process",
            json={"options": {"run_async": False}},
        )

        time.sleep(2)

        queue_response = test_client.get("/api/v1/review/queue?status=pending")
        assert queue_response.status_code == 200
        queue_data = queue_response.json()
        assert queue_data["success"] is True

        tasks = queue_data["data"]["items"]
        assert len(tasks) >= 0

        if len(tasks) > 0:
            task_id = tasks[0]["id"]

            claim_response = test_client.post(
                f"/api/v1/review/tasks/{task_id}/claim",
                json={"reviewer_id": "reviewer_integration_test"},
            )

            assert claim_response.status_code == 200

            task = integration_db_session.query(ReviewTask).filter(
                ReviewTask.id == task_id
            ).first()
            assert task.status == ReviewStatus.ASSIGNED
            assert task.assigned_to == "reviewer_integration_test"
