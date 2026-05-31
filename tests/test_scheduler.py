import asyncio
from datetime import datetime, timedelta
from typing import Any, Dict

import pytest

from src.scheduler.scheduler import (
    CircuitBreaker,
    DependencyGraph,
    ExecutionResult,
    TaskScheduler,
    TaskStatus,
)
from src.utils.helpers import ExecutionContext


class TestCircuitBreaker:
    def test_initial_state(self):
        cb = CircuitBreaker(failure_threshold=3, recovery_timeout=10)
        assert cb.state == "closed"
        assert cb.failure_count == 0

    def test_closed_state_allows_execution(self):
        cb = CircuitBreaker(failure_threshold=3)
        assert cb.allow_request() is True

    def test_failure_threshold_opens_circuit(self):
        cb = CircuitBreaker(failure_threshold=3, recovery_timeout=1)
        for _ in range(3):
            cb.record_failure()
        assert cb.state == "open"
        assert cb.allow_request() is False

    def test_half_open_after_timeout(self):
        cb = CircuitBreaker(failure_threshold=2, recovery_timeout=0.1)
        cb.record_failure()
        cb.record_failure()
        assert cb.state == "open"
        import time
        time.sleep(0.15)
        assert cb.allow_request() is True
        assert cb.state == "half_open"

    def test_success_closes_circuit(self):
        cb = CircuitBreaker(failure_threshold=2, recovery_timeout=0.1)
        cb.record_failure()
        cb.record_failure()
        import time
        time.sleep(0.15)
        cb.allow_request()
        cb.record_success()
        assert cb.state == "closed"
        assert cb.failure_count == 0


class TestDependencyGraph:
    def test_add_nodes_and_edges(self):
        graph = DependencyGraph()
        graph.add_node("a")
        graph.add_node("b")
        graph.add_node("c")
        graph.add_edge("a", "b")
        graph.add_edge("b", "c")

        assert graph.has_node("a")
        assert graph.has_edge("a", "b")
        assert not graph.has_edge("a", "c")

    def test_get_dependencies(self):
        graph = DependencyGraph()
        graph.add_node("a")
        graph.add_node("b")
        graph.add_node("c")
        graph.add_edge("a", "b")
        graph.add_edge("a", "c")

        deps = graph.get_dependencies("a")
        assert set(deps) == {"b", "c"}

    def test_get_dependents(self):
        graph = DependencyGraph()
        graph.add_node("a")
        graph.add_node("b")
        graph.add_node("c")
        graph.add_edge("a", "c")
        graph.add_edge("b", "c")

        dependents = graph.get_dependents("c")
        assert set(dependents) == {"a", "b"}

    def test_topological_sort(self):
        graph = DependencyGraph()
        graph.add_node("a")
        graph.add_node("b")
        graph.add_node("c")
        graph.add_node("d")
        graph.add_edge("a", "b")
        graph.add_edge("b", "c")
        graph.add_edge("a", "d")

        order = graph.topological_sort()
        assert order.index("a") < order.index("b")
        assert order.index("b") < order.index("c")
        assert order.index("a") < order.index("d")

    def test_cycle_detection(self):
        graph = DependencyGraph()
        graph.add_node("a")
        graph.add_node("b")
        graph.add_node("c")
        graph.add_edge("a", "b")
        graph.add_edge("b", "c")
        graph.add_edge("c", "a")

        assert graph.detect_cycles() is True
        with pytest.raises(ValueError):
            graph.topological_sort()

    def test_get_ready_tasks(self):
        graph = DependencyGraph()
        graph.add_node("a")
        graph.add_node("b")
        graph.add_node("c")
        graph.add_edge("a", "b")
        graph.add_edge("a", "c")

        ready = graph.get_ready_tasks(set())
        assert ready == {"a"}

        ready = graph.get_ready_tasks({"a"})
        assert set(ready) == {"b", "c"}


class TestTaskScheduler:
    @pytest.mark.asyncio
    async def test_register_and_run_task(self, sample_task):
        scheduler = TaskScheduler()
        scheduler.register_task(sample_task)

        executed = []

        async def handler(task, params, context):
            executed.append(task.task_id)
            return {"status": "success"}

        scheduler.register_handler("test_task", handler)
        context = ExecutionContext()
        results = await scheduler.run_all(context)

        assert "task_test_001" in results
        assert results["task_test_001"].success is True
        assert executed == ["task_test_001"]

    @pytest.mark.asyncio
    async def test_task_dependencies(self, sample_task_graph):
        scheduler = TaskScheduler()
        scheduler.register_task_graph(sample_task_graph)

        execution_order = []

        async def handler(task, params, context):
            execution_order.append(task.task_id)
            await asyncio.sleep(0.01)
            return {"done": True}

        scheduler.register_handler("task_a", handler)
        scheduler.register_handler("task_b", handler)
        scheduler.register_handler("task_c", handler)

        context = ExecutionContext()
        results = await scheduler.run_all(context)

        assert len(results) == 3
        assert execution_order.index("task_a") < execution_order.index("task_b")
        assert execution_order.index("task_a") < execution_order.index("task_c")
        assert all(r.success for r in results.values())

    @pytest.mark.asyncio
    async def test_task_retry(self, sample_task):
        scheduler = TaskScheduler()
        scheduler.register_task(sample_task)

        attempt = 0

        async def failing_handler(task, params, context):
            nonlocal attempt
            attempt += 1
            if attempt < 3:
                raise ValueError(f"Failing attempt {attempt}")
            return {"success": True}

        scheduler.register_handler("test_task", failing_handler)

        context = ExecutionContext()
        results = await scheduler.run_all(context)

        assert results["task_test_001"].success is True
        assert attempt == 3

    @pytest.mark.asyncio
    async def test_task_timeout(self):
        scheduler = TaskScheduler()
        task = Task(
            task_id="slow_task",
            name="slow_task",
            dependencies=[],
            timeout=1,
            retries=0,
        )
        scheduler.register_task(task)

        async def slow_handler(task, params, context):
            await asyncio.sleep(5)
            return {"done": True}

        scheduler.register_handler("slow_handler", slow_handler)
        scheduler.register_handler("slow_task", slow_handler)

        context = ExecutionContext()
        results = await scheduler.run_all(context)

        assert results["slow_task"].success is False
        assert "timeout" in str(results["slow_task"].error).lower()

    def test_scheduler_progress(self, sample_task_graph, task_scheduler):
        task_scheduler.register_task_graph(sample_task_graph)
        assert task_scheduler.get_progress() == 0.0

    def test_list_tasks(self, sample_task, sample_task_graph, task_scheduler):
        task_scheduler.register_task(sample_task)
        task_scheduler.register_task_graph(sample_task_graph)

        tasks = task_scheduler.list_tasks()
        assert len(tasks) == 4

        graphs = task_scheduler.list_graphs()
        assert len(graphs) == 1

    def test_get_graph(self, sample_task_graph, task_scheduler):
        task_scheduler.register_task_graph(sample_task_graph)
        graph = task_scheduler.get_graph("graph_test_001")
        assert graph is not None
        assert graph.name == "test_graph"

        assert task_scheduler.get_graph("nonexistent") is None

    @pytest.mark.asyncio
    async def test_circuit_breaker_integration(self):
        scheduler = TaskScheduler()
        task = Task(
            task_id="cb_test",
            name="cb_test",
            dependencies=[],
            retries=0,
        )
        scheduler.register_task(task)

        fail_count = 0

        async def always_fail(task, params, context):
            nonlocal fail_count
            fail_count += 1
            raise ValueError("Always fails")

        scheduler.register_handler("cb_test", always_fail)

        context = ExecutionContext()
        for _ in range(5):
            await scheduler.run_all(context)
            scheduler.reset()

        assert fail_count < 5
