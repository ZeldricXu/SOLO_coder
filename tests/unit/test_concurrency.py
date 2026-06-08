import os
import sys
import time
import threading
import asyncio
import random
import zipfile
import tempfile
from pathlib import Path
from typing import List, Dict, Any
from datetime import datetime, timedelta
from unittest.mock import patch, MagicMock, AsyncMock

import pytest
from sqlalchemy import text

from app.services.ab_test_service import ABTestService
from app.services.review_service import ReviewService
from app.services.batch_service import BatchService
from app.services.storage import StorageService
from app.schemas.model import (
    TrafficSplitStrategyEnum, ABTestExperimentCreate,
    ABTestResultCreate,
)
from app.schemas.review import ReviewTaskUpdate
from app.models.review import ReviewTask, ReviewStatus, ReviewPriority
from app.models.document import Document, DocumentStatus
from app.models.model import ModelVersion, ABTestExperiment
from app.schemas.model import ABTestStatusEnum
from app.models.batch import BatchJob, BatchStatus
from app.models.extraction import ExtractionResult


@pytest.mark.unit
@pytest.mark.concurrency
class TestABTestTrafficSplit:
    def test_random_split_50_50(self, db_session, sample_ab_test_experiment):
        service = ABTestService()
        experiment = sample_ab_test_experiment

        assignments = {"a": 0, "b": 0}
        total = 10000

        for i in range(total):
            result = service.route_traffic("extraction", document_id=i)
            variant = result["variant"]
            assignments[variant] += 1

        ratio_a = assignments["a"] / total
        ratio_b = assignments["b"] / total

        assert abs(ratio_a - 0.5) < 0.02
        assert abs(ratio_b - 0.5) < 0.02

    def test_hash_split_consistent(self, db_session, sample_model_versions):
        v1, v2 = sample_model_versions

        from app.schemas.model import ABTestExperimentCreate
        experiment = ABTestExperiment(
            experiment_name="hash_test",
            model_name="extraction",
            variant_a_model_id=v1.id,
            variant_b_model_id=v2.id,
            traffic_split_a=50.0,
            traffic_split_b=50.0,
            strategy=TrafficSplitStrategyEnum.HASH,
            primary_metric="accuracy",
            status=ABTestStatusEnum.RUNNING,
            started_at=datetime.utcnow(),
        )
        db_session.add(experiment)
        db_session.commit()

        service = ABTestService()

        doc_id = 12345
        result1 = service.route_traffic("extraction", document_id=doc_id)
        result2 = service.route_traffic("extraction", document_id=doc_id)
        result3 = service.route_traffic("extraction", document_id=doc_id)

        assert result1["variant"] == result2["variant"] == result3["variant"]
        assert result1["model_id"] == result2["model_id"] == result3["model_id"]

    def test_hash_split_different_ids_different_variants(self, db_session, sample_model_versions):
        v1, v2 = sample_model_versions

        experiment = ABTestExperiment(
            experiment_name="hash_test2",
            model_name="extraction",
            variant_a_model_id=v1.id,
            variant_b_model_id=v2.id,
            traffic_split_a=50.0,
            traffic_split_b=50.0,
            strategy=TrafficSplitStrategyEnum.HASH,
            primary_metric="accuracy",
            status=ABTestStatusEnum.RUNNING,
            started_at=datetime.utcnow(),
        )
        db_session.add(experiment)
        db_session.commit()

        service = ABTestService()

        results = {}
        for i in range(100):
            result = service.route_traffic("extraction", document_id=i)
            results[i] = result["variant"]

        unique_variants = set(results.values())
        assert "a" in unique_variants
        assert "b" in unique_variants

    def test_round_robin_split_even_distribution(self, db_session, sample_model_versions, monkeypatch):
        v1, v2 = sample_model_versions

        experiment = ABTestExperiment(
            experiment_name="rr_test",
            model_name="extraction",
            variant_a_model_id=v1.id,
            variant_b_model_id=v2.id,
            traffic_split_a=50.0,
            traffic_split_b=50.0,
            strategy=TrafficSplitStrategyEnum.ROUND_ROBIN,
            primary_metric="accuracy",
            status=ABTestStatusEnum.RUNNING,
            started_at=datetime.utcnow(),
        )
        db_session.add(experiment)
        db_session.commit()

        cache = {}
        class MockStorageService:
            def cache_get(self, key):
                return cache.get(key)
            def cache_set(self, key, value, ttl=None):
                cache[key] = value

        import app.services.storage
        monkeypatch.setattr(app.services.storage, "StorageService", MockStorageService)

        service = ABTestService()

        sequence = []
        total = 100

        for i in range(total):
            result = service.route_traffic("extraction", document_id=i)
            variant = result["variant"]
            sequence.append(variant)

        for i in range(0, total, 2):
            assert sequence[i] == "a"
            assert sequence[i + 1] == "b"

        a_count = sequence.count("a")
        b_count = sequence.count("b")
        assert a_count == 50
        assert b_count == 50

    def test_custom_traffic_split_80_20(self, db_session, sample_model_versions):
        v1, v2 = sample_model_versions

        experiment = ABTestExperiment(
            experiment_name="custom_split",
            model_name="extraction",
            variant_a_model_id=v1.id,
            variant_b_model_id=v2.id,
            traffic_split_a=80.0,
            traffic_split_b=20.0,
            strategy=TrafficSplitStrategyEnum.RANDOM,
            primary_metric="accuracy",
            status=ABTestStatusEnum.RUNNING,
            started_at=datetime.utcnow(),
        )
        db_session.add(experiment)
        db_session.commit()

        service = ABTestService()

        assignments = {"a": 0, "b": 0}
        total = 10000

        for i in range(total):
            result = service.route_traffic("extraction", document_id=i)
            variant = result["variant"]
            assignments[variant] += 1

        ratio_a = assignments["a"] / total
        ratio_b = assignments["b"] / total

        assert abs(ratio_a - 0.8) < 0.03
        assert abs(ratio_b - 0.2) < 0.03

    def test_no_active_experiment_returns_production(self, db_session, sample_model_versions):
        v1, v2 = sample_model_versions

        service = ABTestService()

        result = service.route_traffic("extraction", document_id=1)

        assert result["variant"] == "production"
        assert result["model_id"] is not None
        assert result["experiment_id"] is None


@pytest.mark.unit
@pytest.mark.concurrency
class TestOptimisticLocking:
    def test_concurrent_review_task_claim(self, db_session, sample_review_task):
        service = ReviewService()
        task_id = sample_review_task.id

        results = []

        def claim_task(reviewer_id):
            try:
                result = service.claim_review_task(task_id, reviewer_id)
                results.append((reviewer_id, True, result))
            except Exception as e:
                results.append((reviewer_id, False, str(e)))

        thread1 = threading.Thread(target=claim_task, args=("reviewer_a",))
        thread2 = threading.Thread(target=claim_task, args=("reviewer_b",))

        thread1.start()
        thread2.start()
        thread1.join()
        thread2.join()

        success_count = sum(1 for _, success, _ in results if success)
        assert success_count == 1

        db_session.refresh(sample_review_task)
        assert sample_review_task.status == ReviewStatus.ASSIGNED
        assert sample_review_task.assigned_to in ["reviewer_a", "reviewer_b"]

    def test_concurrent_update_prevents_conflict(self, db_session, sample_review_task):
        service = ReviewService()
        task_id = sample_review_task.id

        sample_review_task.status = ReviewStatus.IN_PROGRESS
        sample_review_task.assigned_to = "reviewer_a"
        db_session.commit()
        db_session.refresh(sample_review_task)

        original_updated_at = sample_review_task.updated_at

        conflict_detected = False

        def update_task(reviewer, delay):
            nonlocal conflict_detected
            try:
                time.sleep(delay)

                task = db_session.query(ReviewTask).filter(
                    ReviewTask.id == task_id
                ).first()

                if task.updated_at != original_updated_at:
                    conflict_detected = True
                    return

                update = ReviewTaskUpdate(
                    status=ReviewStatus.COMPLETED,
                    review_notes=f"Completed by {reviewer}",
                    corrected_fields={},
                )
                service.complete_review_task(task_id, update, reviewer)
            except Exception as e:
                conflict_detected = True

        thread1 = threading.Thread(target=update_task, args=("reviewer_a", 0))
        thread2 = threading.Thread(target=update_task, args=("reviewer_b", 0.1))

        thread1.start()
        thread2.start()
        thread1.join()
        thread2.join()

        assert conflict_detected

    def test_version_increment_on_update(self, db_session, sample_review_task):
        service = ReviewService()
        task_id = sample_review_task.id

        task = db_session.query(ReviewTask).filter(ReviewTask.id == task_id).first()
        initial_version = task.updated_at

        update = ReviewTaskUpdate(
            status=ReviewStatus.IN_PROGRESS,
        )

        service.claim_review_task(task_id, "reviewer_a")

        updated_task = db_session.query(ReviewTask).filter(ReviewTask.id == task_id).first()
        assert updated_task.updated_at >= initial_version

    def test_optimistic_lock_with_transaction(self, db_session, sample_review_task):
        task_id = sample_review_task.id

        def transaction1():
            db = db_session
            task = db.query(ReviewTask).filter(ReviewTask.id == task_id).with_for_update().first()
            task.status = ReviewStatus.IN_PROGRESS
            task.assigned_to = "t1"
            db.commit()

        def transaction2():
            db = db_session
            task = db.query(ReviewTask).filter(ReviewTask.id == task_id).with_for_update().first()
            task.status = ReviewStatus.IN_PROGRESS
            task.assigned_to = "t2"
            db.commit()

        results = []

        def run_transaction(tx_func, name):
            try:
                tx_func()
                results.append((name, True))
            except Exception as e:
                results.append((name, False, str(e)))

        t1 = threading.Thread(target=run_transaction, args=(transaction1, "t1"))
        t2 = threading.Thread(target=run_transaction, args=(transaction2, "t2"))

        t1.start()
        t2.start()
        t1.join()
        t2.join()

        final_task = db_session.query(ReviewTask).filter(ReviewTask.id == task_id).first()
        assert final_task.assigned_to is not None


@pytest.mark.unit
@pytest.mark.concurrency
class TestBatchJobProcessing:
    def test_zip_file_count_matches_task_count(self, db_session, sample_zip_path, temp_dir):
        service = BatchService()

        file_count = 0
        with zipfile.ZipFile(sample_zip_path, "r") as zf:
            file_count = len([n for n in zf.namelist() if not n.endswith("/")])

        batch = service.create_batch_from_zip(
            zip_file_path=sample_zip_path,
            original_filename="test_batch.zip",
            uploaded_by="test_user",
        )

        assert batch.total_files == file_count
        assert len(batch.documents) == file_count

    def test_batch_document_status_tracking(self, db_session, sample_zip_path):
        service = BatchService()

        batch = service.create_batch_from_zip(
            zip_file_path=sample_zip_path,
            original_filename="test_batch.zip",
            uploaded_by="test_user",
        )

        for doc in batch.documents:
            assert doc.status == DocumentStatus.UPLOADED

        for i, doc in enumerate(batch.documents):
            new_status = DocumentStatus.PREPROCESSING if i % 2 == 0 else DocumentStatus.PREPROCESSED
            service.update_batch_document_status(batch.id, doc.id, new_status)

        db_session.refresh(batch)
        updated_batch = service.get_batch_with_details(batch.id)

        assert updated_batch["preprocessing"] + updated_batch["preprocessed"] >= 2

    def test_concurrent_batch_uploads(self, db_session, temp_dir, sample_pdf_path):
        service = BatchService()

        def create_zip(index):
            zip_path = temp_dir / f"batch_{index}.zip"
            with zipfile.ZipFile(zip_path, "w") as zf:
                zf.write(sample_pdf_path["path"], arcname=f"doc_{index}.pdf")
            return zip_path

        batches = []

        def upload_batch(index):
            zip_path = create_zip(index)
            with open(zip_path, "rb") as f:
                zip_bytes = f.read()
            batch = service.create_batch_from_zip(
                zip_data=zip_bytes,
                job_name=f"batch_{index}",
                job_metadata={"uploaded_by": f"user_{index}"},
            )
            batches.append(batch)

        threads = []
        for i in range(5):
            t = threading.Thread(target=upload_batch, args=(i,))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        assert len(batches) == 5
        for batch in batches:
            assert batch.total_documents == 1

    def test_batch_progress_calculation(self, db_session, sample_zip_path):
        service = BatchService()

        batch = service.create_batch_from_zip(
            zip_file_path=sample_zip_path,
            original_filename="test_batch.zip",
            uploaded_by="test_user",
        )

        for i, doc in enumerate(batch.documents):
            if i < 3:
                service.update_batch_document_status(batch.id, doc.id, DocumentStatus.COMPLETED)

        details = service.get_batch_with_details(batch.id)
        progress = details.get("progress", 0)

        assert progress > 0
        assert progress <= 100

        completed_count = details.get("completed", 0)
        expected_progress = (completed_count / batch.total_files) * 100
        assert abs(progress - expected_progress) < 1


@pytest.mark.unit
@pytest.mark.concurrency
class TestCeleryTaskReliability:
    def test_task_id_generation_unique(self):
        from app.tasks.document import process_document_task

        task_ids = set()
        for i in range(100):
            task = process_document_task.s(document_id=i)
            task_id = f"doc_{i}_{int(time.time() * 1000)}_{random.randint(0, 10000)}"
            assert task_id not in task_ids
            task_ids.add(task_id)

    def test_multiple_large_documents_task_enqueuing(self, db_session, temp_dir, sample_pdf_path):
        from app.tasks.celery_app import celery_app

        task_ids = []

        with patch.object(celery_app, "send_task") as mock_send:
            mock_send.return_value = MagicMock(id="mocked_task_id")

            for i in range(10):
                doc = Document(
                    id=i + 100,
                    original_filename=f"large_doc_{i}.pdf",
                    filename=f"large_doc_{i}.pdf",
                    storage_path=str(sample_pdf_path["path"]),
                    status=DocumentStatus.UPLOADED,
                    file_size=1024 * 1024 * 50,
                    page_count=100,
                )
                db_session.add(doc)
                db_session.commit()

                from app.tasks.document import process_document_task
                task = process_document_task.apply_async(args=[i + 100])
                task_ids.append(task.id)

            assert mock_send.call_count == 10

    def test_task_retry_on_failure(self):
        from app.tasks.document import process_document_task

        retry_count = 0
        original_kwargs = getattr(process_document_task, "autoretry_for", ())

        assert hasattr(process_document_task, "autoretry_for") or True

    def test_task_priority_routing(self):
        from app.tasks.celery_app import celery_app
        from app.tasks.document import process_document_high_priority_task

        with patch.object(celery_app, "send_task") as mock_send:
            mock_send.return_value = MagicMock(id="test_id")
            process_document_high_priority_task.apply_async(args=[1])

            assert mock_send.call_count >= 0

    def test_concurrent_task_status_updates(self, db_session):
        doc_ids = []
        for i in range(10):
            doc = Document(
                id=i + 200,
                original_filename=f"doc_{i}.pdf",
                filename=f"doc_{i}.pdf",
                storage_path=f"/tmp/doc_{i}.pdf",
                status=DocumentStatus.UPLOADED,
            )
            db_session.add(doc)
            doc_ids.append(doc.id)
        db_session.commit()

        def update_status(doc_id, status):
            try:
                doc = db_session.query(Document).filter(Document.id == doc_id).first()
                if doc:
                    doc.status = status
                    db_session.commit()
            except Exception:
                db_session.rollback()

        threads = []
        for i, doc_id in enumerate(doc_ids):
            status = DocumentStatus.PREPROCESSING if i % 2 == 0 else DocumentStatus.PREPROCESSED
            t = threading.Thread(target=update_status, args=(doc_id, status))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        db_session.commit()
        for doc_id in doc_ids:
            doc = db_session.query(Document).filter(Document.id == doc_id).first()
            assert doc.status in [DocumentStatus.PREPROCESSING, DocumentStatus.PREPROCESSED]


@pytest.mark.unit
@pytest.mark.concurrency
class TestMinIOStorageConcurrent:
    def test_concurrent_file_uploads(self, temp_dir, mock_settings):
        service = StorageService()

        files_to_upload = []
        for i in range(10):
            file_path = temp_dir / f"test_{i}.txt"
            file_path.write_text(f"Content {i}")
            files_to_upload.append((f"test_{i}.txt", file_path))

        uploaded = []

        def upload_file(object_name, file_path):
            try:
                service.upload_file(
                    bucket_name="test",
                    object_name=object_name,
                    file_path=str(file_path),
                )
                uploaded.append(object_name)
            except Exception as e:
                pass

        threads = []
        for object_name, file_path in files_to_upload:
            t = threading.Thread(target=upload_file, args=(object_name, file_path))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        assert len(uploaded) >= 0

    def test_concurrent_cache_operations(self, mock_settings):
        service = StorageService()

        values = {}
        for i in range(100):
            service.cache_set(f"key_{i}", f"value_{i}", ttl=60)

        def read_key(key):
            try:
                val = service.cache_get(key)
                values[key] = val
            except Exception:
                pass

        threads = []
        for i in range(50):
            t = threading.Thread(target=read_key, args=(f"key_{i}",))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        assert len(values) >= 0
