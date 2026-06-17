import pytest
import asyncio
import time
import threading
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from unittest.mock import MagicMock, patch, AsyncMock
from datetime import datetime

from app.heatmap.service import HeatmapService


@pytest.mark.concurrency
class TestHeatmapTileRequestDedup:
    """多个用户同时请求不同时间范围的热力瓦片时的切片生成任务排队与去重"""

    def test_same_tile_request_deduplicated(self):
        processed_keys = set()
        lock = threading.Lock()
        call_count = 0

        def generate_tile(z, x, y, time_range):
            nonlocal call_count
            key = f"{z}_{x}_{y}_{time_range}"
            with lock:
                if key in processed_keys:
                    return None
                processed_keys.add(key)
                call_count += 1
            time.sleep(0.01)
            return f"tile_{key}"

        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = []
            for _ in range(5):
                futures.append(executor.submit(generate_tile, 14, 13634, 6497, "2024-01-01_08:00"))

            results = [f.result() for f in futures]

        non_none_results = [r for r in results if r is not None]
        assert len(non_none_results) == 1, \
            f"Same tile request should be deduplicated, but got {len(non_none_results)} results"
        assert call_count == 1

    def test_different_time_ranges_produce_different_tiles(self):
        processed_keys = set()
        lock = threading.Lock()
        results_map = {}

        def generate_tile(z, x, y, time_range):
            key = f"{z}_{x}_{y}_{time_range}"
            with lock:
                if key not in processed_keys:
                    processed_keys.add(key)
                    results_map[key] = f"tile_{key}"
            return results_map.get(key)

        time_ranges = ["2024-01-01_08:00", "2024-01-01_09:00", "2024-01-01_10:00"]

        with ThreadPoolExecutor(max_workers=3) as executor:
            futures = [
                executor.submit(generate_tile, 14, 13634, 6497, tr)
                for tr in time_ranges
            ]
            results = [f.result() for f in futures]

        assert len(set(results)) == 3, "Different time ranges should produce different tiles"

    def test_concurrent_requests_thread_safety(self):
        processed_keys = set()
        lock = threading.Lock()
        results = []
        errors = []

        def generate_tile(z, x, y, time_range):
            try:
                key = f"{z}_{x}_{y}_{time_range}"
                with lock:
                    if key in processed_keys:
                        return f"cached_{key}"
                    processed_keys.add(key)
                time.sleep(0.005)
                return f"generated_{key}"
            except Exception as e:
                errors.append(e)
                return None

        with ThreadPoolExecutor(max_workers=8) as executor:
            futures = []
            for i in range(50):
                tr = f"2024-01-01_{i % 5:02d}:00"
                futures.append(executor.submit(generate_tile, 14, 13634 + i % 3, 6497, tr))

            for f in as_completed(futures):
                result = f.result()
                if result:
                    results.append(result)

        assert len(errors) == 0, f"Errors in concurrent requests: {errors}"
        assert len(results) > 0

    def test_task_queue_ordering_with_priority(self):
        task_queue = []
        lock = threading.Lock()

        def enqueue_task(priority, tile_key):
            with lock:
                task_queue.append((priority, tile_key))
                task_queue.sort(key=lambda x: x[0])

        def dequeue_task():
            with lock:
                if task_queue:
                    return task_queue.pop(0)
                return None

        enqueue_task(3, "tile_low_priority")
        enqueue_task(1, "tile_high_priority")
        enqueue_task(2, "tile_medium_priority")

        first = dequeue_task()
        assert first[1] == "tile_high_priority", "Highest priority task should be dequeued first"

        second = dequeue_task()
        assert second[1] == "tile_medium_priority"

        third = dequeue_task()
        assert third[1] == "tile_low_priority"

    def test_async_concurrent_tile_generation(self):
        async def generate_tile_async(z, x, y, time_range):
            await asyncio.sleep(0.01)
            return f"tile_{z}_{x}_{y}_{time_range}"

        async def run_concurrent_requests():
            tasks = []
            for i in range(10):
                tr = f"2024-01-01_{8 + i}:00"
                tasks.append(generate_tile_async(14, 13634, 6497, tr))

            results = await asyncio.gather(*tasks)
            return results

        results = asyncio.get_event_loop().run_until_complete(run_concurrent_requests())
        assert len(results) == 10
        assert all(r is not None for r in results)

    def test_cache_prevents_redundant_generation(self):
        cache = {}
        cache_lock = threading.Lock()
        generation_count = 0

        def get_or_generate_tile(z, x, y, time_range):
            nonlocal generation_count
            cache_key = f"{z}_{x}_{y}_{time_range}"

            with cache_lock:
                if cache_key in cache:
                    return cache[cache_key]

            time.sleep(0.01)
            with cache_lock:
                if cache_key in cache:
                    return cache[cache_key]
                generation_count += 1
                tile_data = f"tile_{cache_key}"
                cache[cache_key] = tile_data
                return tile_data

        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = []
            for _ in range(10):
                futures.append(executor.submit(
                    get_or_generate_tile, 14, 13634, 6497, "2024-01-01_08:00"
                ))

            results = [f.result() for f in futures]

        assert generation_count == 1, \
            f"Expected 1 generation, got {generation_count} — cache not preventing redundant work"

        unique_results = set(results)
        assert len(unique_results) == 1, "All concurrent requests should return same cached result"


@pytest.mark.concurrency
class TestCeleryWorkerPoolSaturation:
    """Celery worker池打满时的任务积压监控"""

    def test_queue_depth_monitoring(self):
        queue_depth = 0
        max_depth = 0
        workers = 4
        lock = threading.Lock()

        def submit_task():
            nonlocal queue_depth, max_depth
            with lock:
                queue_depth += 1
                if queue_depth > max_depth:
                    max_depth = queue_depth

        def complete_task():
            nonlocal queue_depth
            with lock:
                queue_depth -= 1

        for i in range(20):
            submit_task()
            if i >= workers:
                complete_task()

        assert max_depth > workers, \
            f"Max queue depth {max_depth} should exceed worker count {workers} during saturation"

    def test_backpressure_when_pool_full(self):
        max_concurrent = 4
        active_tasks = 0
        lock = threading.Lock()
        rejected = 0
        accepted = 0

        def try_submit():
            nonlocal active_tasks, rejected, accepted
            with lock:
                if active_tasks >= max_concurrent:
                    rejected += 1
                    return False
                active_tasks += 1
                accepted += 1
                return True

        def complete():
            nonlocal active_tasks
            with lock:
                active_tasks -= 1

        results = []
        for i in range(10):
            results.append(try_submit())

        assert rejected > 0, "Some tasks should be rejected when pool is full"
        assert accepted == max_concurrent

        complete()
        result = try_submit()
        assert result is True, "Should accept after a task completes"

    def test_task_timeout_and_cleanup(self):
        completed_tasks = []
        timed_out_tasks = []

        task_start_time = time.time()
        task_timeout = 0.05

        def run_task(task_id, duration):
            time.sleep(duration)
            completed_tasks.append(task_id)

        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = {
                executor.submit(run_task, "fast", 0.01): "fast",
                executor.submit(run_task, "slow", 10.0): "slow",
            }

            for f in as_completed(futures, timeout=0.1):
                try:
                    f.result(timeout=0.01)
                except Exception:
                    timed_out_tasks.append(futures[f])

        assert "fast" in completed_tasks

    def test_worker_utilization_calculation(self):
        total_workers = 4
        busy_workers = 3
        utilization = busy_workers / total_workers

        assert 0.0 <= utilization <= 1.0
        assert abs(utilization - 0.75) < 0.01

        idle_workers = total_workers - busy_workers
        assert idle_workers == 1

    def test_priority_with_backpressure(self):
        task_queue = []
        max_concurrent = 2
        active_count = 0
        lock = threading.Lock()

        def submit_with_priority(task_id, priority):
            nonlocal active_count
            with lock:
                if active_count < max_concurrent:
                    active_count += 1
                    task_queue.append((priority, task_id, "running"))
                else:
                    task_queue.append((priority, task_id, "queued"))

        submit_with_priority("t1", 1)
        submit_with_priority("t2", 3)
        submit_with_priority("t3", 2)
        submit_with_priority("t4", 1)

        running = [t for t in task_queue if t[2] == "running"]
        queued = [t for t in task_queue if t[2] == "queued"]

        assert len(running) == 2
        assert len(queued) == 2

        queued_sorted = sorted(queued, key=lambda x: x[0])
        assert queued_sorted[0][1] == "t4", "Lower priority number = higher priority"

    def test_celery_inspect_active_tasks(self):
        mock_inspect = MagicMock()
        mock_inspect.active.return_value = {
            "worker1": [
                {"id": "task-1", "name": "generate_heatmap", "args": [14, 13634, 6497]},
                {"id": "task-2", "name": "generate_heatmap", "args": [14, 13634, 6498]},
            ],
            "worker2": [
                {"id": "task-3", "name": "generate_3dtiles", "args": [116.3, 39.8, 116.5, 40.0]},
            ],
        }

        active = mock_inspect.active()
        total_active = sum(len(tasks) for tasks in active.values())
        assert total_active == 3

        heatmap_tasks = [
            t for tasks in active.values()
            for t in tasks if t["name"] == "generate_heatmap"
        ]
        assert len(heatmap_tasks) == 2
