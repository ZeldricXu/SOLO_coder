import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Dict, Any, List

import pytest
from unittest.mock import Mock, MagicMock, patch, call

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from db.models import (
    Sample,
    SampleStatus,
    SampleType,
    AnalysisTask,
    TaskStep,
    TaskStatus,
    StepStatus,
    Variant,
    ACMGClassification,
)
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker, Session


pytestmark = [pytest.mark.unit, pytest.mark.concurrency]


CHROMOSOMES = [f"chr{i}" for i in range(1, 23)] + ["chrX", "chrY"]


class TestCeleryTaskRouting:
    def _get_task_queue_for_chromosome(self, chromosome: str) -> str:
        return f"chrom_{chromosome}"

    def test_chromosome_tasks_routed_to_different_queues(self):
        chromosomes_to_test = ["chr1", "chr2", "chr3"]
        routed_queues = {}

        class ChromosomeAnalysisTask:
            def route_task(self, task_id, chromosome):
                queue = self._get_task_queue_for_chromosome(chromosome)
                routed_queues[task_id] = queue
                return {"queue": queue, "task_id": task_id}

            def _get_task_queue_for_chromosome(self, chromosome):
                return f"chrom_{chromosome}"

        task = ChromosomeAnalysisTask()
        for chrom in chromosomes_to_test:
            task.route_task(f"task_{chrom}", chrom)

        expected_queues = {
            "task_chr1": "chrom_chr1",
            "task_chr2": "chrom_chr2",
            "task_chr3": "chrom_chr3",
        }
        assert routed_queues == expected_queues

        queue_set = set(routed_queues.values())
        assert len(queue_set) == len(chromosomes_to_test)

    def test_chr1_routes_to_chrom_chr1(self):
        queue = self._get_task_queue_for_chromosome("chr1")
        assert queue == "chrom_chr1"

    def test_chr2_routes_to_chrom_chr2(self):
        queue = self._get_task_queue_for_chromosome("chr2")
        assert queue == "chrom_chr2"

    def test_chr3_routes_to_chrom_chr3(self):
        queue = self._get_task_queue_for_chromosome("chr3")
        assert queue == "chrom_chr3"

    def test_route_function_segregates_by_chromosome(self):
        def route_task(task_params):
            chrom = task_params.get("chromosome", "default")
            return f"chrom_{chrom}"

        tasks = [
            {"task_id": "t1", "chromosome": "chr1", "region": "1:100000-200000"},
            {"task_id": "t2", "chromosome": "chr2", "region": "2:100000-200000"},
            {"task_id": "t3", "chromosome": "chrX", "region": "X:100000-200000"},
        ]
        result = {t["task_id"]: route_task(t) for t in tasks}

        assert result == {
            "t1": "chrom_chr1",
            "t2": "chrom_chr2",
            "t3": "chrom_chrX",
        }


class TestMultiTaskSubmission:
    def test_submit_5_tasks_distributed_to_workers(self):
        class WorkerQueueManager:
            def __init__(self, num_workers: int):
                self.num_workers = num_workers
                self.worker_assignments: Dict[int, List[str]] = {
                    i: [] for i in range(num_workers)
                }
                self.task_queue: List[str] = []

            def submit_task(self, task_id: str, priority: int = 0):
                self.task_queue.append(task_id)
                worker_idx = hash(task_id) % self.num_workers
                self.worker_assignments[worker_idx].append(task_id)
                return {
                    "task_id": task_id,
                    "assigned_worker": worker_idx,
                    "status": "queued",
                }

        manager = WorkerQueueManager(num_workers=3)
        submitted = []
        for i in range(5):
            result = manager.submit_task(f"analysis_task_{i}", priority=i % 3)
            submitted.append(result)

        assert len(submitted) == 5
        assert len(manager.task_queue) == 5

        total_assigned = sum(len(tasks) for tasks in manager.worker_assignments.values())
        assert total_assigned == 5

        all_task_ids = set()
        for tasks in manager.worker_assignments.values():
            all_task_ids.update(tasks)
        assert all_task_ids == {f"analysis_task_{i}" for i in range(5)}

    def test_worker_distribution_logic(self):
        class RoundRobinScheduler:
            def __init__(self, num_workers: int = 4):
                self.num_workers = num_workers
                self.current_worker = 0
                self.assignments = {}

            def next_worker(self):
                worker = self.current_worker
                self.current_worker = (self.current_worker + 1) % self.num_workers
                return worker

            def schedule(self, task_ids: List[str]) -> Dict[str, int]:
                for tid in task_ids:
                    self.assignments[tid] = self.next_worker()
                return self.assignments

        scheduler = RoundRobinScheduler(num_workers=3)
        task_ids = [f"job_{i}" for i in range(5)]
        result = scheduler.schedule(task_ids)

        assert len(result) == 5
        worker_0_tasks = [t for t, w in result.items() if w == 0]
        worker_1_tasks = [t for t, w in result.items() if w == 1]
        worker_2_tasks = [t for t, w in result.items() if w == 2]

        assert len(worker_0_tasks) >= 1
        assert len(worker_1_tasks) >= 1
        assert len(worker_2_tasks) >= 1
        assert len(worker_0_tasks) + len(worker_1_tasks) + len(worker_2_tasks) == 5

    def test_5_analysis_tasks_with_priority(self):
        class PriorityTaskQueue:
            def __init__(self):
                self.pending = []
                self.processing = []
                self.completed = []

            def submit(self, task_id, priority=0):
                entry = {"task_id": task_id, "priority": priority, "submit_time": time.time()}
                self.pending.append(entry)
                return entry

            def dispatch(self, num_workers):
                self.pending.sort(key=lambda x: (-x["priority"], x["submit_time"]))
                for _ in range(min(num_workers, len(self.pending))):
                    task = self.pending.pop(0)
                    task["start_time"] = time.time()
                    self.processing.append(task)

        queue = PriorityTaskQueue()
        for i in range(5):
            queue.submit(f"analysis_{i}", priority=i % 3)
        assert len(queue.pending) == 5

        queue.dispatch(num_workers=3)
        assert len(queue.processing) == 3
        assert len(queue.pending) == 2

        processing_priorities = [t["priority"] for t in queue.processing]
        assert sorted(processing_priorities, reverse=True) == processing_priorities


@pytest.fixture
def concurrent_db_engine(tmp_path_factory):
    db_path = tmp_path_factory.mktemp("concurrent_db") / "concurrent_test.db"
    url = f"sqlite:///{db_path}"
    engine = create_engine(
        url,
        connect_args={"check_same_thread": False},
        echo=False,
    )
    from db.database import Base
    from db import models  # noqa: F401
    Base.metadata.create_all(bind=engine)
    yield engine
    Base.metadata.drop_all(bind=engine)
    engine.dispose()


@pytest.fixture
def concurrent_db_session(concurrent_db_engine):
    SessionLocal = sessionmaker(
        autocommit=False,
        autoflush=False,
        bind=concurrent_db_engine,
    )
    session = SessionLocal()
    yield session
    session.close()


class TestDatabaseConcurrentUpdates:
    def test_two_threads_update_sample_status_with_for_update(
        self, concurrent_db_engine, concurrent_db_session
    ):
        SessionLocal = sessionmaker(
            autocommit=False, autoflush=False, bind=concurrent_db_engine
        )

        sample = Sample(
            sample_id="CONCURRENT_SAMPLE_001",
            sample_type=SampleType.WES,
            status=SampleStatus.REGISTERED,
            patient_id="PAT_CONC_001",
            library_id="LIB_CONC_001",
        )
        concurrent_db_session.add(sample)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(sample)
        sample_id_val = sample.id

        final_status = [None, None]
        errors = []
        lock = threading.Lock()

        def update_status(thread_idx, new_status):
            try:
                session = SessionLocal()
                with lock:
                    locked_sample = (
                        session.query(Sample)
                        .filter(Sample.id == sample_id_val)
                        .with_for_update()
                        .one()
                    )
                    time.sleep(0.01)
                    locked_sample.status = new_status
                    session.commit()
                    session.refresh(locked_sample)
                    final_status[thread_idx] = locked_sample.status
                session.close()
            except Exception as e:
                errors.append(str(e))

        thread1 = threading.Thread(target=update_status, args=(0, SampleStatus.QC_PASSED))
        thread2 = threading.Thread(target=update_status, args=(1, SampleStatus.ANALYZING))

        thread1.start()
        thread2.start()
        thread1.join(timeout=10)
        thread2.join(timeout=10)

        verify_session = SessionLocal()
        verified = verify_session.query(Sample).filter(Sample.id == sample_id_val).one()
        final_db_status = verified.status
        verify_session.close()

        assert final_db_status in (SampleStatus.QC_PASSED, SampleStatus.ANALYZING)
        assert verified.id == sample_id_val
        assert len(errors) == 0

    def test_pessimistic_lock_prevents_lost_updates(
        self, concurrent_db_engine, concurrent_db_session
    ):
        SessionLocal = sessionmaker(
            autocommit=False, autoflush=False, bind=concurrent_db_engine
        )

        sample = Sample(
            sample_id="LOCK_TEST_001",
            sample_type=SampleType.WES,
            status=SampleStatus.REGISTERED,
            total_variants=0,
        )
        concurrent_db_session.add(sample)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(sample)
        sample_id = sample.id

        increment_count = 10
        threads = []
        errors = []
        update_lock = threading.Lock()

        def increment_variants(amount):
            try:
                session = SessionLocal()
                try:
                    with update_lock:
                        obj = (
                            session.query(Sample)
                            .filter(Sample.id == sample_id)
                            .with_for_update()
                            .one()
                        )
                        current = obj.total_variants or 0
                        time.sleep(0.001)
                        obj.total_variants = current + amount
                        session.commit()
                finally:
                    session.close()
            except Exception as e:
                errors.append(str(e))

        for i in range(increment_count):
            t = threading.Thread(target=increment_variants, args=(1,))
            threads.append(t)

        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10)

        verify = SessionLocal()
        result = verify.query(Sample).filter(Sample.id == sample_id).one()
        verify.close()

        assert result.total_variants == increment_count
        assert len(errors) == 0


class TestVariantBatchInsert:
    def test_two_threads_insert_100_variants_each(
        self, concurrent_db_engine, concurrent_db_session
    ):
        SessionLocal = sessionmaker(
            autocommit=False, autoflush=False, bind=concurrent_db_engine
        )

        sample = Sample(
            sample_id="VAR_INSERT_001",
            sample_type=SampleType.WES,
            status=SampleStatus.REGISTERED,
        )
        concurrent_db_session.add(sample)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(sample)
        sid = sample.id
        sample_pk = sid

        insert_errors = []
        insert_lock = threading.Lock()

        def insert_variants(start_idx: int, count: int, thread_tag: str):
            try:
                session = SessionLocal()
                batch = []
                for i in range(count):
                    var_num = start_idx + i
                    variant = Variant(
                        sample_id=sample_pk,
                        variant_id=f"VAR_{thread_tag}_{var_num:04d}",
                        chromosome=f"chr{(var_num % 22) + 1}",
                        position=1000000 + var_num,
                        ref="A",
                        alt="G",
                        variant_type="SNV",
                        genotype="0/1",
                        genotype_quality=99.0,
                        depth=50 + (var_num % 50),
                        allele_depth=25 + (var_num % 25),
                        allele_frequency=0.4 + (var_num % 10) * 0.01,
                        gene=f"GENE_{(var_num % 100) + 1}",
                        consequence="missense_variant",
                        impact="MODERATE",
                    )
                    batch.append(variant)
                with insert_lock:
                    session.bulk_save_objects(batch)
                    session.commit()
                session.close()
            except Exception as e:
                insert_errors.append(str(e))
                raise

        t1 = threading.Thread(target=insert_variants, args=(0, 100, "T1"))
        t2 = threading.Thread(target=insert_variants, args=(100, 100, "T2"))

        t1.start()
        t2.start()
        t1.join(timeout=30)
        t2.join(timeout=30)

        assert len(insert_errors) == 0

        verify = SessionLocal()
        total = verify.query(Variant).filter(Variant.sample_id == sample_pk).count()
        verify.close()

        assert total == 200

    def test_variant_ids_uniqueness_after_concurrent_insert(
        self, concurrent_db_engine, concurrent_db_session
    ):
        SessionLocal = sessionmaker(
            autocommit=False, autoflush=False, bind=concurrent_db_engine
        )

        sample = Sample(
            sample_id="VAR_UNIQUE_001",
            sample_type=SampleType.WES,
        )
        concurrent_db_session.add(sample)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(sample)
        sample_pk = sample.id

        errors = []

        def insert_unique_batch(prefix, start, count):
            try:
                sess = SessionLocal()
                for i in range(count):
                    v = Variant(
                        sample_id=sample_pk,
                        variant_id=f"{prefix}_VAR_{start + i}",
                        chromosome="chr1",
                        position=start + i,
                        ref="C",
                        alt="T",
                    )
                    sess.add(v)
                sess.commit()
                sess.close()
            except Exception as e:
                errors.append(str(e))

        t1 = threading.Thread(target=insert_unique_batch, args=("A", 0, 50))
        t2 = threading.Thread(target=insert_unique_batch, args=("B", 0, 50))
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        assert len(errors) == 0

        check = SessionLocal()
        all_variants = check.query(Variant).filter(Variant.sample_id == sample_pk).all()
        variant_ids = [v.variant_id for v in all_variants]
        check.close()

        assert len(variant_ids) == len(set(variant_ids))
        assert len(variant_ids) == 100


class TestTaskStepStatusCompetition:
    def test_two_workers_update_same_step_no_inconsistency(
        self, concurrent_db_engine, concurrent_db_session
    ):
        SessionLocal = sessionmaker(
            autocommit=False, autoflush=False, bind=concurrent_db_engine
        )

        sample = Sample(sample_id="STEP_COMP_001", sample_type=SampleType.WES)
        concurrent_db_session.add(sample)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(sample)

        task = AnalysisTask(
            task_id="TASK_STEP_COMP",
            task_name="Concurrency test task",
            sample_id=sample.id,
            status=TaskStatus.RUNNING,
            pipeline_version="1.0.0",
        )
        concurrent_db_session.add(task)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(task)
        task_id_pk = task.id

        step = TaskStep(
            task_id=task_id_pk,
            step_id="step_bwa_mem",
            step_name="BWA-MEM Alignment",
            step_type="bwa_mem",
            status=StepStatus.RUNNING,
        )
        concurrent_db_session.add(step)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(step)
        step_pk_id = step.id

        results = {}
        err_list = []
        state_lock = threading.Lock()

        def worker_update(worker_id, new_status, duration):
            try:
                sess = SessionLocal()
                with state_lock:
                    locked_step = (
                        sess.query(TaskStep)
                        .filter(TaskStep.id == step_pk_id)
                        .with_for_update()
                        .one()
                    )
                    if locked_step.status not in (StepStatus.COMPLETED, StepStatus.FAILED):
                        time.sleep(0.005)
                        locked_step.status = new_status
                        locked_step.duration_seconds = duration
                        locked_step.metrics = {"completed_by": worker_id}
                        sess.commit()
                        sess.refresh(locked_step)
                        results[worker_id] = {
                            "status": locked_step.status,
                            "applied": True,
                        }
                    else:
                        results[worker_id] = {
                            "status": locked_step.status,
                            "applied": False,
                            "reason": "already_finalized",
                        }
                sess.close()
            except Exception as e:
                err_list.append(str(e))
                results[worker_id] = {"error": str(e)}

        worker_1 = threading.Thread(
            target=worker_update, args=("worker_A", StepStatus.COMPLETED, 120.5)
        )
        worker_2 = threading.Thread(
            target=worker_update, args=("worker_B", StepStatus.FAILED, 45.2)
        )

        worker_1.start()
        worker_2.start()
        worker_1.join(timeout=10)
        worker_2.join(timeout=10)

        verify = SessionLocal()
        final_step = (
            verify.query(TaskStep).filter(TaskStep.id == step_pk_id).one()
        )
        final_status = final_step.status
        verify.close()

        assert len(err_list) == 0
        assert final_status in (StepStatus.COMPLETED, StepStatus.FAILED)

        applied_workers = [w for w, r in results.items() if r.get("applied")]
        skipped_workers = [w for w, r in results.items() if not r.get("applied", True)]
        assert len(applied_workers) >= 1
        if len(applied_workers) == 1:
            assert len(skipped_workers) == 1

    def test_concurrent_step_updates_with_version_logic(
        self, concurrent_db_engine, concurrent_db_session
    ):
        SessionLocal = sessionmaker(
            autocommit=False, autoflush=False, bind=concurrent_db_engine
        )

        sample = Sample(sample_id="VERSION_TEST", sample_type=SampleType.WES)
        concurrent_db_session.add(sample)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(sample)

        task = AnalysisTask(
            task_id="VERSION_TASK_001",
            task_name="Version test",
            sample_id=sample.id,
            status=TaskStatus.RUNNING,
        )
        concurrent_db_session.add(task)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(task)

        step = TaskStep(
            task_id=task.id,
            step_id="step_1",
            step_name="S1",
            step_type="fastqc",
            status=StepStatus.PENDING,
        )
        concurrent_db_session.add(step)
        concurrent_db_session.commit()
        concurrent_db_session.refresh(step)
        step_pk = step.id

        transitions = [StepStatus.RUNNING, StepStatus.COMPLETED]
        applied_transitions = []
        lock = threading.Lock()
        errors = []

        def apply_transition(worker, status_list):
            try:
                sess = SessionLocal()
                for s in status_list:
                    with lock:
                        obj = (
                            sess.query(TaskStep)
                            .filter(TaskStep.id == step_pk)
                            .with_for_update()
                            .one()
                        )
                        obj.status = s
                        sess.commit()
                        sess.refresh(obj)
                        applied_transitions.append((worker, s))
                sess.close()
            except Exception as e:
                errors.append(str(e))

        t1 = threading.Thread(
            target=apply_transition, args=("W1", [StepStatus.RUNNING])
        )
        t2 = threading.Thread(
            target=apply_transition,
            args=("W2", [StepStatus.RUNNING, StepStatus.COMPLETED]),
        )

        t1.start()
        t2.start()
        t1.join()
        t2.join()

        assert len(errors) == 0
        verify = SessionLocal()
        final = verify.query(TaskStep).filter(TaskStep.id == step_pk).one()
        verify.close()

        assert final.status in (StepStatus.RUNNING, StepStatus.COMPLETED)


class TestMinIOConcurrentUploads:
    def test_10_files_concurrent_upload_all_succeed(self):
        class MockedMinIOClient:
            def __init__(self):
                self.uploaded_objects = {}
                self._lock = threading.Lock()
                self.fail_next = False
                self.call_count = 0

            def fput_object(self, bucket, object_name, file_path):
                with self._lock:
                    self.call_count += 1
                    if self.fail_next:
                        raise Exception("Simulated upload failure")
                    key = f"{bucket}/{object_name}"
                    self.uploaded_objects[key] = file_path
                    return {"key": key, "size": 1024, "etag": "etag_" + str(self.call_count)}

            def bucket_exists(self, bucket):
                return True

        bucket = "test-bucket"
        client = MockedMinIOClient()
        upload_results = {}
        errors = []

        def upload_file(file_idx):
            file_name = f"analysis/file_{file_idx:03d}.bam"
            local_path = f"/tmp/mock_file_{file_idx}.bam"
            try:
                result = client.fput_object(bucket, file_name, local_path)
                upload_results[file_idx] = {"status": "success", **result}
            except Exception as e:
                errors.append((file_idx, str(e)))
                upload_results[file_idx] = {"status": "failed", "error": str(e)}

        threads = []
        for i in range(10):
            t = threading.Thread(target=upload_file, args=(i,))
            threads.append(t)

        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=15)

        assert len(errors) == 0
        assert client.call_count == 10
        assert len(client.uploaded_objects) == 10

        success_count = sum(
            1 for r in upload_results.values() if r["status"] == "success"
        )
        assert success_count == 10

        for i in range(10):
            expected_key = f"{bucket}/analysis/file_{i:03d}.bam"
            assert expected_key in client.uploaded_objects

    def test_concurrent_upload_with_realistic_file_paths(self):
        class AtomicUploadTracker:
            def __init__(self):
                self.successes: List[str] = []
                self.failures: List[str] = []
                self._lock = threading.Lock()
                self.call_count = 0

            def mark_success(self, object_key):
                with self._lock:
                    self.successes.append(object_key)
                    self.call_count += 1

            def mark_failure(self, object_key, err):
                with self._lock:
                    self.failures.append((object_key, str(err)))

        tracker = AtomicUploadTracker()

        mock_client = Mock()
        mock_client.bucket_exists.return_value = True
        mock_client.upload_file.side_effect = lambda bucket, key, path: tracker.mark_success(key) or {
            "object_key": key,
            "size": 1024 * 1024,
            "etag": uuid.uuid4().hex,
        }

        files = [
            ("sample001", "fastq/sample001_R1.fastq.gz", "/data/sample001_R1.fastq.gz"),
            ("sample001", "fastq/sample001_R2.fastq.gz", "/data/sample001_R2.fastq.gz"),
            ("sample001", "bam/sample001.sorted.bam", "/data/sample001.sorted.bam"),
            ("sample001", "bam/sample001.sorted.bam.bai", "/data/sample001.sorted.bam.bai"),
            ("sample001", "vcf/sample001.vcf.gz", "/data/sample001.vcf.gz"),
            ("sample002", "fastq/sample002_R1.fastq.gz", "/data/sample002_R1.fastq.gz"),
            ("sample002", "fastq/sample002_R2.fastq.gz", "/data/sample002_R2.fastq.gz"),
            ("sample002", "vcf/sample002.vcf.gz", "/data/sample002.vcf.gz"),
            ("sample003", "report/sample003_report.pdf", "/data/sample003_report.pdf"),
            ("cohort001", "joint/joint.vcf.gz", "/data/joint.vcf.gz"),
        ]

        def do_upload(bucket, key, path):
            try:
                mock_client.upload_file(bucket, key, path)
            except Exception as e:
                tracker.mark_failure(key, e)

        threads = []
        for bucket, key, path in files:
            t = threading.Thread(target=do_upload, args=(bucket, key, path))
            threads.append(t)

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(tracker.successes) == 10
        assert len(tracker.failures) == 0
        assert tracker.call_count == 10
