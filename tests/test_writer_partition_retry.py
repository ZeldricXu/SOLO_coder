import threading
from typing import Optional

import pandas as pd
import pytest


class WriteCoordinator:
    def __init__(self, writer, max_retries: int = 3):
        self.writer = writer
        self.max_retries = max_retries
        self.partition_status: dict[int, str] = {}
        self.attempts: dict[int, int] = {}
        self._lock = threading.Lock()
        self._success_partitions: list[int] = []
        self._failed_partitions: list[int] = []
        self._total_rows_written: int = 0

    def write_partitions(
        self,
        partitions: list[tuple[int, pd.DataFrame]],
        write_strategy: str = "partition_overwrite",
    ) -> dict:
        partition_map = {pid: data for pid, data in partitions}

        with self._lock:
            for pid in partition_map:
                self.partition_status[pid] = "pending"
                self.attempts[pid] = 0

        pending = set(partition_map.keys())
        retry_count = 0

        while pending and retry_count <= self.max_retries:
            still_failed: set[int] = set()

            for pid in list(pending):
                data = partition_map[pid]
                with self._lock:
                    self.attempts[pid] = self.attempts.get(pid, 0) + 1

                try:
                    self.writer.write_partition(pid, data, write_strategy)
                    with self._lock:
                        self.partition_status[pid] = "success"
                        self._success_partitions.append(pid)
                        self._total_rows_written += len(data)
                except Exception:
                    with self._lock:
                        self.partition_status[pid] = "failed"
                    still_failed.add(pid)

            pending = still_failed
            retry_count += 1

        with self._lock:
            self._failed_partitions = [
                pid for pid, status in self.partition_status.items()
                if status == "failed"
            ]

        return {
            "success_partitions": sorted(self._success_partitions),
            "failed_partitions": sorted(self._failed_partitions),
            "total_rows_written": self._total_rows_written,
            "partition_status": dict(self.partition_status),
            "attempts": dict(self.attempts),
            "write_strategy": write_strategy,
        }


class MockFailureWriter:
    def __init__(
        self,
        failure_schedule: Optional[dict[int, int]] = None,
        always_fail: Optional[list[int]] = None,
    ):
        self.failure_schedule = failure_schedule or {}
        self.always_fail = always_fail or []
        self.write_calls: list[tuple[int, int, str]] = []
        self.partition_call_counts: dict[int, int] = {}
        self._lock = threading.Lock()

    def write_partition(self, partition_id: int, data: pd.DataFrame, strategy: str):
        with self._lock:
            self.write_calls.append((partition_id, len(data), strategy))
            self.partition_call_counts[partition_id] = (
                self.partition_call_counts.get(partition_id, 0) + 1
            )
            call_count = self.partition_call_counts[partition_id]

        if partition_id in self.always_fail:
            raise RuntimeError(f"partition {partition_id} always fails")

        max_failures = self.failure_schedule.get(partition_id, 0)
        if call_count <= max_failures:
            raise RuntimeError(
                f"partition {partition_id} failed on attempt {call_count}"
            )

        return True


@pytest.mark.concurrency
class TestPartialFailureRetryOnlyFailed:
    def _make_partitions(self, count: int = 10, rows_per_partition: int = 10):
        return [
            (
                i,
                pd.DataFrame({
                    "id": range(i * rows_per_partition, (i + 1) * rows_per_partition),
                    "value": [f"p{i}_{j}" for j in range(rows_per_partition)],
                }),
            )
            for i in range(count)
        ]

    def test_partial_failure_retry_cascading(self):
        partitions = self._make_partitions(10, 10)
        failure_schedule = {
            3: 1,
            7: 2,
        }
        writer = MockFailureWriter(failure_schedule=failure_schedule)
        coordinator = WriteCoordinator(writer, max_retries=3)

        result = coordinator.write_partitions(partitions, "partition_overwrite")

        for pid in range(10):
            assert coordinator.partition_status[pid] == "success", (
                f"Partition {pid} should be success, got {coordinator.partition_status[pid]}"
            )

        assert coordinator.attempts[3] == 2, (
            f"Partition 3 should have 2 attempts, got {coordinator.attempts[3]}"
        )
        assert coordinator.attempts[7] == 3, (
            f"Partition 7 should have 3 attempts, got {coordinator.attempts[7]}"
        )

        for pid in [0, 1, 2, 4, 5, 6, 8, 9]:
            assert coordinator.attempts[pid] == 1, (
                f"Partition {pid} should have 1 attempt, got {coordinator.attempts[pid]}"
            )

        total_calls = len(writer.write_calls)
        assert total_calls == 13, f"Expected 13 total calls, got {total_calls}"

        assert len(result["success_partitions"]) == 10
        assert len(result["failed_partitions"]) == 0
        assert result["total_rows_written"] == 100
        assert result["write_strategy"] == "partition_overwrite"

    def test_sequential_retry_flow(self):
        partitions = self._make_partitions(10, 5)
        failure_schedule = {3: 1, 7: 2}
        writer = MockFailureWriter(failure_schedule=failure_schedule)
        coordinator = WriteCoordinator(writer, max_retries=3)

        result = coordinator.write_partitions(partitions)

        assert sorted(result["success_partitions"]) == list(range(10))
        assert writer.partition_call_counts[3] == 2
        assert writer.partition_call_counts[7] == 3

        for pid in range(10):
            assert writer.partition_call_counts[pid] == coordinator.attempts[pid]


@pytest.mark.concurrency
class TestMaxRetriesExhausted:
    def _make_partitions(self, count: int = 5, rows_per_partition: int = 10):
        return [
            (
                i,
                pd.DataFrame({
                    "id": range(i * rows_per_partition, (i + 1) * rows_per_partition),
                    "value": [f"data_{i}_{j}" for j in range(rows_per_partition)],
                }),
            )
            for i in range(count)
        ]

    def test_always_failing_partition_exhausts_retries(self):
        partitions = self._make_partitions(5, 10)
        writer = MockFailureWriter(always_fail=[2])
        coordinator = WriteCoordinator(writer, max_retries=3)

        result = coordinator.write_partitions(partitions, "partition_overwrite")

        assert coordinator.partition_status[2] == "failed", (
            f"Partition 2 should be failed, got {coordinator.partition_status[2]}"
        )
        for pid in [0, 1, 3, 4]:
            assert coordinator.partition_status[pid] == "success", (
                f"Partition {pid} should be success, got {coordinator.partition_status[pid]}"
            )

        assert coordinator.attempts[2] == 4, (
            f"Partition 2 should have 4 attempts (1 initial + 3 retries), "
            f"got {coordinator.attempts[2]}"
        )
        for pid in [0, 1, 3, 4]:
            assert coordinator.attempts[pid] == 1, (
                f"Partition {pid} should have 1 attempt, got {coordinator.attempts[pid]}"
            )

        assert 2 in result["failed_partitions"], (
            f"Partition 2 should be in failed_partitions, got {result['failed_partitions']}"
        )
        assert len(result["failed_partitions"]) == 1
        assert sorted(result["success_partitions"]) == [0, 1, 3, 4]
        assert result["total_rows_written"] == 40

    def test_failed_partitions_returned_in_result(self):
        partitions = self._make_partitions(5, 5)
        writer = MockFailureWriter(always_fail=[2])
        coordinator = WriteCoordinator(writer, max_retries=2)

        result = coordinator.write_partitions(partitions)

        assert "failed_partitions" in result
        assert isinstance(result["failed_partitions"], list)
        assert 2 in result["failed_partitions"]

        assert "partition_status" in result
        assert result["partition_status"][2] == "failed"

        assert "attempts" in result
        assert result["attempts"][2] == 3


@pytest.mark.concurrency
class TestRetryWithDifferentWriteStrategies:
    def _make_partitions(self, count: int = 5, rows_per_partition: int = 5):
        return [
            (
                i,
                pd.DataFrame({
                    "pk": [f"pk_{i}_{j}" for j in range(rows_per_partition)],
                    "col": range(i * rows_per_partition, (i + 1) * rows_per_partition),
                }),
            )
            for i in range(count)
        ]

    def test_insert_strategy_only_retries_failed(self):
        partitions = self._make_partitions(5, 5)
        failure_schedule = {1: 1, 3: 2}
        writer = MockFailureWriter(failure_schedule=failure_schedule)
        coordinator = WriteCoordinator(writer, max_retries=3)

        result = coordinator.write_partitions(partitions, write_strategy="insert")

        assert result["write_strategy"] == "insert"
        for pid in range(5):
            assert coordinator.partition_status[pid] == "success"

        for pid in [0, 2, 4]:
            assert writer.partition_call_counts[pid] == 1, (
                f"Partition {pid} should be written only once for insert strategy"
            )

        call_strategies = {
            pid: [s for (p, _, s) in writer.write_calls if p == pid]
            for pid in range(5)
        }
        for pid, strategies in call_strategies.items():
            for s in strategies:
                assert s == "insert", (
                    f"Partition {pid} should use 'insert' strategy, got {s}"
                )

        total_success_rows = sum(len(data) for _, data in partitions)
        assert result["total_rows_written"] == total_success_rows

    def test_upsert_strategy_only_retries_failed(self):
        partitions = self._make_partitions(5, 8)
        failure_schedule = {2: 1}
        writer = MockFailureWriter(failure_schedule=failure_schedule)
        coordinator = WriteCoordinator(writer, max_retries=3)

        result = coordinator.write_partitions(partitions, write_strategy="upsert")

        assert result["write_strategy"] == "upsert"
        for pid in range(5):
            assert coordinator.partition_status[pid] == "success"

        for pid in [0, 1, 3, 4]:
            assert writer.partition_call_counts[pid] == 1, (
                f"Partition {pid} should be written only once for upsert strategy"
            )

        assert writer.partition_call_counts[2] == 2

        for (pid, _, strategy) in writer.write_calls:
            assert strategy == "upsert", (
                f"All calls should use 'upsert' strategy, got {strategy} for partition {pid}"
            )

        assert len(result["failed_partitions"]) == 0

    def test_partition_overwrite_strategy_only_retries_failed(self):
        partitions = self._make_partitions(6, 6)
        failure_schedule = {0: 2, 4: 1}
        writer = MockFailureWriter(failure_schedule=failure_schedule)
        coordinator = WriteCoordinator(writer, max_retries=3)

        result = coordinator.write_partitions(
            partitions, write_strategy="partition_overwrite"
        )

        assert result["write_strategy"] == "partition_overwrite"
        for pid in range(6):
            assert coordinator.partition_status[pid] == "success"

        for pid in [1, 2, 3, 5]:
            assert writer.partition_call_counts[pid] == 1, (
                f"Partition {pid} should have exactly 1 call for overwrite strategy"
            )

        assert writer.partition_call_counts[0] == 3
        assert writer.partition_call_counts[4] == 2

        total_calls = len(writer.write_calls)
        expected_calls = 6 + 2 + 1
        assert total_calls == expected_calls, (
            f"Expected {expected_calls} total calls, got {total_calls}"
        )

        assert len(result["success_partitions"]) == 6
        assert result["total_rows_written"] == 36
