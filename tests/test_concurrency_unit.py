import random
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import pandas as pd
import pytest


@pytest.mark.concurrency
@pytest.mark.unit
class TestCeleryTaskIdempotentDistribution:
    def test_only_one_worker_claims_task(self):
        lock_store = {}
        store_lock = threading.Lock()
        claim_results = []

        def acquire_distributed_lock(key: str) -> bool:
            with store_lock:
                if key in lock_store:
                    return False
                lock_store[key] = True
                return True

        def worker_claim_task(task_id: str, worker_id: int) -> bool:
            acquired = acquire_distributed_lock(task_id)
            claim_results.append((worker_id, acquired))
            return acquired

        task_id = "task_123"
        with ThreadPoolExecutor(max_workers=3) as executor:
            futures = [
                executor.submit(worker_claim_task, task_id, i)
                for i in range(3)
            ]
            for f in futures:
                f.result()

        successful_claims = sum(1 for _, acquired in claim_results if acquired)
        assert successful_claims == 1, (
            f"Expected exactly 1 successful claim, got {successful_claims}. "
            f"Results: {claim_results}"
        )

        failed_claims = sum(1 for _, acquired in claim_results if not acquired)
        assert failed_claims == 2, (
            f"Expected exactly 2 failed claims, got {failed_claims}"
        )

        assert len(claim_results) == 3


@pytest.mark.concurrency
@pytest.mark.unit
class TestPipelineDeduplicateManualTrigger:
    class PipelineTriggerService:
        def __init__(self):
            self.in_flight_executions: set[str] = set()
            self._lock = threading.Lock()

        def trigger_pipeline(self, pipeline_id: str):
            with self._lock:
                if pipeline_id in self.in_flight_executions:
                    return None
                self.in_flight_executions.add(pipeline_id)
                execution_id = f"exec_{pipeline_id}_{uuid.uuid4().hex[:8]}"
                return execution_id

    def test_exactly_one_trigger_succeeds(self):
        service = self.PipelineTriggerService()
        results = []

        def do_trigger():
            result = service.trigger_pipeline("pipeline_abc")
            results.append(result)
            return result

        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(do_trigger) for _ in range(5)]
            for f in futures:
                f.result()

        non_none_results = [r for r in results if r is not None]
        none_results = [r for r in results if r is None]

        assert len(non_none_results) == 1, (
            f"Expected exactly 1 non-None execution_id, got {len(non_none_results)}"
        )
        assert len(none_results) == 4, (
            f"Expected exactly 4 None results, got {len(none_results)}"
        )

        assert len(service.in_flight_executions) == 1
        assert "pipeline_abc" in service.in_flight_executions


@pytest.mark.concurrency
@pytest.mark.unit
class TestWriterPartitionRetry:
    def test_partition_2_retried_after_failure(self):
        partitions = [
            pd.DataFrame({"id": range(i * 10, (i + 1) * 10), "value": [f"val_{j}" for j in range(i * 10, (i + 1) * 10)]})
            for i in range(5)
        ]

        class MockTargetWriter:
            def __init__(self):
                self.write_calls = []
                self.partition_call_counts = {}
                self._lock = threading.Lock()
                self._partition_2_failures = 1

            def write_partition(self, partition_id: int, data: pd.DataFrame):
                with self._lock:
                    self.write_calls.append((partition_id, len(data)))
                    self.partition_call_counts[partition_id] = (
                        self.partition_call_counts.get(partition_id, 0) + 1
                    )

                if partition_id == 2 and self._partition_2_failures > 0:
                    with self._lock:
                        self._partition_2_failures -= 1
                    raise RuntimeError("network error")

                return True

        def write_all_partitions(partitions_list, writer, max_retries=3):
            success_partitions = []
            success_rows = 0
            failed_partitions = set(range(len(partitions_list)))
            attempt = 0

            while failed_partitions and attempt <= max_retries:
                current_failed = set()
                for pid in list(failed_partitions):
                    try:
                        writer.write_partition(pid, partitions_list[pid])
                        success_partitions.append(pid)
                        success_rows += len(partitions_list[pid])
                    except RuntimeError:
                        current_failed.add(pid)
                failed_partitions = current_failed
                attempt += 1

            return {
                "success_partitions": success_partitions,
                "success_rows": success_rows,
                "failed_partitions": list(failed_partitions),
                "attempts_made": attempt,
            }

        writer = MockTargetWriter()
        result = write_all_partitions(partitions, writer, max_retries=3)

        total_calls = len(writer.write_calls)
        assert total_calls == 6, f"Expected 6 total write calls, got {total_calls}"

        assert writer.partition_call_counts.get(2) == 2, (
            f"Expected partition 2 to be called 2 times, got {writer.partition_call_counts.get(2)}"
        )

        for pid in [0, 1, 3, 4]:
            assert writer.partition_call_counts.get(pid) == 1, (
                f"Expected partition {pid} to be called once, got {writer.partition_call_counts.get(pid)}"
            )

        assert len(result["success_partitions"]) == 5
        assert sorted(result["success_partitions"]) == [0, 1, 2, 3, 4]
        assert len(result["failed_partitions"]) == 0
        assert result["success_rows"] == 50

        seen_partitions = set()
        for pid in result["success_partitions"]:
            assert pid not in seen_partitions, f"Duplicate write for partition {pid}"
            seen_partitions.add(pid)


@pytest.mark.concurrency
@pytest.mark.unit
class TestRedisLockConcurrentAccess:
    class FakeRedisLock:
        def __init__(self):
            self._store = {}
            self._lock = threading.Lock()

        def acquire(self, key: str) -> bool:
            with self._lock:
                if key in self._store:
                    return False
                self._store[key] = time.time()
                return True

        def release(self, key: str) -> bool:
            with self._lock:
                if key in self._store:
                    del self._store[key]
                    return True
                return False

    def test_only_one_thread_acquires_lock(self):
        lock = self.FakeRedisLock()
        results = []
        lock_key = "etl:lock:pipeline_x"

        def try_acquire(thread_id: int):
            acquired = lock.acquire(lock_key)
            results.append((thread_id, acquired))
            return acquired

        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(try_acquire, i) for i in range(10)]
            for f in futures:
                f.result()

        successful = sum(1 for _, acquired in results if acquired)
        failed = sum(1 for _, acquired in results if not acquired)

        assert successful == 1, f"Expected 1 successful acquisition, got {successful}"
        assert failed == 9, f"Expected 9 failed acquisitions, got {failed}"
        assert len(results) == 10

        assert lock.acquire("other_key") is True
        assert lock.acquire(lock_key) is False


@pytest.mark.concurrency
@pytest.mark.unit
class TestParallelTaskCompletionTracking:
    def test_all_tasks_complete_no_duplicates(self):
        completion_order = []
        completion_set = set()
        _lock = threading.Lock()

        def run_task(task_id: int):
            sleep_time = random.uniform(0.01, 0.1)
            time.sleep(sleep_time)
            with _lock:
                completion_order.append(task_id)
                completion_set.add(task_id)
            return task_id

        with ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(run_task, i) for i in range(5)]
            results = [f.result() for f in futures]

        assert len(completion_order) == 5, (
            f"Expected 5 completions, got {len(completion_order)}"
        )
        assert len(completion_set) == 5, (
            f"Expected 5 unique completions, got {len(completion_set)}"
        )
        assert completion_set == {0, 1, 2, 3, 4}

        assert len(results) == 5
        assert sorted(results) == [0, 1, 2, 3, 4]

        for tid in completion_order:
            assert completion_order.count(tid) == 1, (
                f"Task {tid} appears more than once in completion order"
            )
