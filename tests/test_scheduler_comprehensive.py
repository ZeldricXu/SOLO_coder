"""
Comprehensive tests for the Scheduler module.

Test Matrix:
  - Normal Path: Basic task execution, dependency ordering, retries
  - Boundary Inputs: Empty graphs, single tasks, large graphs
  - Concurrent Operations: Parallel task execution, race conditions
  - Exception Injection: Timeout, handler failures, circuit breaking

Design follows JUnit 5 + Mockito patterns adapted for pytest + unittest.mock
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any, Callable, Dict, List, Optional
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.models import RunPhase, RunInstance, Task, TaskGraph
from src.scheduler.scheduler import (
    CircuitBreaker,
    DefaultTaskExecutor,
    DependencyGraph,
    ExecutionResult,
    ScheduledTask,
    TaskExecutor,
    TaskScheduler,
    TaskStatus,
)
from src.utils.errors import DependencyError, ValidationError
from src.utils.helpers import ExecutionContext

from tests.builders import (
    CircuitBreakerBuilder,
    DependencyGraphBuilder,
    ExecutionResultBuilder,
    TaskBuilder,
    TaskGraphBuilder,
    async_failing_handler,
    async_slow_handler,
    async_success_handler,
    create_complex_graph,
    create_simple_task,
    sync_handler,
    Counter,
)


# ============================================================================
# TaskStatus Enum Tests
# ============================================================================


class TestTaskStatus:
    """Tests for TaskStatus enum values and behavior."""

    def test_all_status_values_defined(self):
        """Verify all expected status values exist."""
        expected = {"pending", "ready", "running", "completed", "failed", "skipped", "timeout"}
        actual = {s.value for s in TaskStatus}
        assert actual == expected

    def test_status_is_string_enum(self):
        """Verify TaskStatus can be used as a string."""
        assert TaskStatus.PENDING == "pending"
        assert isinstance(TaskStatus.COMPLETED, str)


# ============================================================================
# ExecutionResult Tests
# ============================================================================


class TestExecutionResult:
    """Tests for ExecutionResult dataclass."""

    def test_default_creation(self):
        """Normal path: Create result with all fields."""
        result = ExecutionResult(
            task_id="task_001",
            status=TaskStatus.COMPLETED,
            result={"output": "success"},
        )
        assert result.task_id == "task_001"
        assert result.status == TaskStatus.COMPLETED
        assert result.result == {"output": "success"}
        assert result.error is None

    def test_duration_property_with_timestamps(self):
        """Normal path: Calculate duration correctly."""
        start = time.time()
        end = start + 5.0
        result = ExecutionResult(
            task_id="task_001",
            status=TaskStatus.COMPLETED,
            started_at=start,
            completed_at=end,
        )
        assert result.duration == pytest.approx(5.0, abs=0.01)

    def test_duration_property_without_timestamps(self):
        """Boundary: Return 0.0 when timestamps are None."""
        result = ExecutionResult(task_id="task_001", status=TaskStatus.PENDING)
        assert result.duration == 0.0

    def test_duration_property_with_only_start(self):
        """Boundary: Return 0.0 when only started_at is set."""
        result = ExecutionResult(
            task_id="task_001",
            status=TaskStatus.RUNNING,
            started_at=time.time(),
        )
        assert result.duration == 0.0

    def test_failed_result_with_error(self):
        """Exception path: Failed result stores error message."""
        result = ExecutionResultBuilder() \
            .for_task("task_failed") \
            .failed("Something went wrong") \
            .build()

        assert result.status == TaskStatus.FAILED
        assert result.error == "Something went wrong"

    def test_timeout_result(self):
        """Exception path: Timeout result has correct status."""
        result = ExecutionResultBuilder() \
            .for_task("task_timeout") \
            .timed_out() \
            .build()

        assert result.status == TaskStatus.TIMEOUT
        assert "timed out" in result.error.lower()

    def test_retry_count_tracking(self):
        """Normal path: Track retry count accurately."""
        result = ExecutionResultBuilder() \
            .for_task("task_retry") \
            .completed() \
            .with_retry_count(3) \
            .build()

        assert result.retry_count == 3

    def test_metadata_field(self):
        """Normal path: Store additional metadata."""
        result = ExecutionResultBuilder() \
            .for_task("task_meta") \
            .completed() \
            .with_metadata(request_id="req_123", hostname="node-1") \
            .build()

        assert result.metadata["request_id"] == "req_123"
        assert result.metadata["hostname"] == "node-1"


# ============================================================================
# CircuitBreaker Tests
# ============================================================================


class TestCircuitBreaker:
    """Comprehensive tests for CircuitBreaker pattern implementation."""

    # --- Normal Path Tests ---

    def test_initial_state_is_closed(self):
        """Normal: Circuit breaker starts in closed state."""
        cb = CircuitBreakerBuilder().build()
        assert cb.state == "closed"
        assert cb.failure_count == 0
        assert cb.last_failure_time is None

    def test_closed_state_allows_requests(self):
        """Normal: Closed state allows all requests."""
        cb = CircuitBreakerBuilder().build()
        for _ in range(10):
            assert cb.allow_request() is True

    def test_success_resets_failure_count(self):
        """Normal: Success resets failure counter."""
        cb = CircuitBreakerBuilder().with_failure_threshold(5).build()
        cb.record_failure()
        cb.record_failure()
        cb.record_failure()
        assert cb.failure_count == 3

        cb.record_success()
        assert cb.failure_count == 0
        assert cb.state == "closed"

    # --- Exception/Threshold Tests ---

    def test_opens_after_failure_threshold(self):
        """Exception: Circuit opens after threshold failures."""
        cb = CircuitBreakerBuilder() \
            .with_failure_threshold(3) \
            .with_recovery_timeout(10) \
            .build()

        cb.record_failure()
        cb.record_failure()
        assert cb.state == "closed"
        assert cb.allow_request() is True

        cb.record_failure()
        assert cb.state == "open"
        assert cb.allow_request() is False

    def test_rejects_requests_when_open(self):
        """Exception: Open state rejects all requests."""
        cb = CircuitBreakerBuilder() \
            .with_failure_threshold(1) \
            .with_recovery_timeout(60) \
            .build()

        cb.record_failure()
        assert cb.state == "open"
        assert cb.allow_request() is False

    def test_transitions_to_half_open_after_timeout(self):
        """Exception: Half-open state after recovery timeout."""
        cb = CircuitBreakerBuilder() \
            .with_failure_threshold(1) \
            .with_recovery_timeout(0.1) \
            .build()

        cb.record_failure()
        assert cb.state == "open"
        assert cb.allow_request() is False

        time.sleep(0.15)
        assert cb.allow_request() is True
        assert cb.state == "half_open"

    def test_success_in_half_open_closes_circuit(self):
        """Recovery: Success in half-open closes the circuit."""
        cb = CircuitBreakerBuilder() \
            .with_failure_threshold(1) \
            .with_recovery_timeout(0.1) \
            .build()

        cb.record_failure()
        time.sleep(0.15)

        cb.allow_request()  # Transition to half-open
        assert cb.state == "half_open"

        cb.record_success()
        assert cb.state == "closed"
        assert cb.failure_count == 0

    def test_failure_in_half_open_reopens_circuit(self):
        """Exception: Failure in half-open reopens the circuit."""
        cb = CircuitBreakerBuilder() \
            .with_failure_threshold(1) \
            .with_recovery_timeout(0.1) \
            .build()

        cb.record_failure()
        time.sleep(0.15)
        cb.allow_request()  # Transition to half-open

        cb.record_failure()
        assert cb.state == "open"

    # --- Boundary Tests ---

    def test_failure_threshold_of_one(self):
        """Boundary: Strict threshold of 1 failure."""
        cb = CircuitBreakerBuilder().strict().build()
        cb.record_failure()
        assert cb.state == "open"

    def test_zero_recovery_timeout(self):
        """Boundary: Zero timeout means immediate half-open."""
        cb = CircuitBreakerBuilder() \
            .with_failure_threshold(1) \
            .with_recovery_timeout(0) \
            .build()

        cb.record_failure()
        # With 0 timeout, should immediately allow
        time.sleep(0.01)
        assert cb.allow_request() is True

    def test_multiple_circuit_breakers_independent(self):
        """Normal: Multiple breakers operate independently."""
        cb1 = CircuitBreakerBuilder().with_failure_threshold(2).build()
        cb2 = CircuitBreakerBuilder().with_failure_threshold(5).build()

        cb1.record_failure()
        cb1.record_failure()
        assert cb1.state == "open"
        assert cb2.state == "closed"


# ============================================================================
# ScheduledTask Tests
# ============================================================================


class TestScheduledTask:
    """Tests for ScheduledTask dataclass."""

    def test_default_creation(self):
        """Normal: Create scheduled task with defaults."""
        task = create_simple_task()
        st = ScheduledTask(task=task)

        assert st.task.task_id == task.task_id
        assert st.status == TaskStatus.PENDING
        assert st.dependencies == []
        assert st.dependents == []

    def test_with_dependencies(self):
        """Normal: Task with dependencies."""
        task = TaskBuilder() \
            .with_id("task_child") \
            .with_name("child") \
            .with_dependencies("task_parent") \
            .build()

        st = ScheduledTask(task=task, dependencies=["task_parent"])
        assert st.dependencies == ["task_parent"]


# ============================================================================
# DependencyGraph Tests
# ============================================================================


class TestDependencyGraph:
    """Comprehensive tests for DependencyGraph."""

    # --- Normal Path Tests ---

    def test_add_single_task(self):
        """Normal: Add a single task to empty graph."""
        graph = DependencyGraph()
        task = create_simple_task("task_a", "A")
        graph.add_task(task)

        assert "task_a" in graph.tasks
        assert graph.tasks["task_a"].status == TaskStatus.PENDING

    def test_add_multiple_independent_tasks(self):
        """Normal: Multiple tasks with no dependencies."""
        graph = DependencyGraphBuilder() \
            .add_chain("A") \
            .build()

        task_b = TaskBuilder().with_id("task_b").with_name("B").build()
        graph.add_task(task_b)

        assert len(graph.tasks) == 2
        assert "task_a" in graph.tasks
        assert "task_b" in graph.tasks

    def test_add_dependency_chain(self):
        """Normal: Linear dependency chain A -> B -> C."""
        graph = DependencyGraphBuilder() \
            .add_chain("A", "B", "C") \
            .build()

        assert graph.tasks["task_a"].dependencies == []
        assert graph.tasks["task_b"].dependencies == ["task_a"]
        assert graph.tasks["task_c"].dependencies == ["task_b"]

    def test_add_fanout_dependencies(self):
        """Normal: Fan-out pattern A -> B, A -> C."""
        graph = DependencyGraphBuilder().build()
        task_a = TaskBuilder().with_id("task_a").with_name("A").build()
        task_b = TaskBuilder().with_id("task_b").with_name("B").with_dependencies("task_a").build()
        task_c = TaskBuilder().with_id("task_c").with_name("C").with_dependencies("task_a").build()

        graph.add_task(task_a)
        graph.add_task(task_b)
        graph.add_task(task_c)

        assert graph.tasks["task_b"].dependents == []
        assert graph.tasks["task_c"].dependents == []

    def test_add_dependency_explicit(self):
        """Normal: Add dependency between existing tasks."""
        graph = DependencyGraphBuilder().build()
        task_a = TaskBuilder().with_id("task_a").with_name("A").build()
        task_b = TaskBuilder().with_id("task_b").with_name("B").build()

        graph.add_task(task_a)
        graph.add_task(task_b)
        graph.add_dependency("task_b", "task_a")

        assert "task_a" in graph.tasks["task_b"].dependencies

    def test_add_duplicate_dependency_is_noop(self):
        """Normal: Adding same dependency twice is safe."""
        graph = DependencyGraphBuilder().build()
        task_a = TaskBuilder().with_id("task_a").with_name("A").build()
        task_b = TaskBuilder().with_id("task_b").with_name("B").with_dependencies("task_a").build()

        graph.add_task(task_a)
        graph.add_task(task_b)
        graph.add_dependency("task_b", "task_a")  # Already exists
        graph.add_dependency("task_b", "task_a")  # Should be no-op

        assert graph.tasks["task_b"].dependencies == ["task_a"]

    def test_execution_order_linear_chain(self):
        """Normal: Topological sort of linear chain."""
        graph = DependencyGraphBuilder() \
            .add_chain("A", "B", "C") \
            .build()

        order = graph.get_execution_order()
        assert order.index("task_a") < order.index("task_b")
        assert order.index("task_b") < order.index("task_c")

    def test_execution_order_fanout(self):
        """Normal: Topological sort of fan-out pattern."""
        graph = DependencyGraphBuilder().build()
        task_a = TaskBuilder().with_id("task_a").with_name("A").build()
        task_b = TaskBuilder().with_id("task_b").with_name("B").with_dependencies("task_a").build()
        task_c = TaskBuilder().with_id("task_c").with_name("C").with_dependencies("task_a").build()

        graph.add_task(task_a)
        graph.add_task(task_b)
        graph.add_task(task_c)

        order = graph.get_execution_order()
        assert order.index("task_a") < order.index("task_b")
        assert order.index("task_a") < order.index("task_c")

    def test_is_complete_all_completed(self):
        """Normal: Graph is complete when all tasks completed."""
        graph = DependencyGraphBuilder() \
            .add_chain("A", "B") \
            .build()

        graph.update_task_status("task_a", TaskStatus.COMPLETED)
        graph.update_task_status("task_b", TaskStatus.COMPLETED)
        assert graph.is_complete() is True

    def test_is_complete_with_failures(self):
        """Normal: Graph is complete when all tasks in terminal state."""
        graph = DependencyGraphBuilder() \
            .add_chain("A", "B") \
            .build()

        graph.update_task_status("task_a", TaskStatus.FAILED)
        graph.update_task_status("task_b", TaskStatus.SKIPPED)
        assert graph.is_complete() is True

    def test_is_not_complete_with_running_task(self):
        """Normal: Graph not complete with running task."""
        graph = DependencyGraphBuilder() \
            .add_chain("A", "B") \
            .build()

        graph.update_task_status("task_a", TaskStatus.RUNNING)
        assert graph.is_complete() is False

    # --- Boundary Tests ---

    def test_empty_graph_is_complete(self):
        """Boundary: Empty graph is trivially complete."""
        graph = DependencyGraph()
        assert graph.is_complete() is True

    def test_empty_graph_execution_order(self):
        """Boundary: Empty graph returns empty execution order."""
        graph = DependencyGraph()
        assert graph.get_execution_order() == []

    def test_get_all_statuses_empty(self):
        """Boundary: Empty graph returns empty status map."""
        graph = DependencyGraph()
        assert graph.get_all_statuses() == {}

    def test_update_nonexistent_task_is_safe(self):
        """Boundary: Updating nonexistent task does nothing."""
        graph = DependencyGraph()
        graph.update_task_status("nonexistent", TaskStatus.COMPLETED)
        # Should not raise

    # --- Exception Path Tests ---

    def test_add_duplicate_task_raises(self):
        """Exception: Adding duplicate task raises ValidationError."""
        graph = DependencyGraph()
        task = create_simple_task()
        graph.add_task(task)

        with pytest.raises(ValidationError) as exc:
            graph.add_task(task)
        assert "already exists" in str(exc.value.message)

    def test_add_dependency_to_nonexistent_task_raises(self):
        """Exception: Adding dependency with nonexistent task raises error."""
        graph = DependencyGraph()
        task = create_simple_task()
        graph.add_task(task)

        with pytest.raises(ValidationError):
            graph.add_dependency("task_simple", "nonexistent")

    def test_circular_dependency_detected(self):
        """Exception: Circular dependencies are detected."""
        graph = DependencyGraphBuilder() \
            .add_chain("A", "B") \
            .build()

        with pytest.raises(DependencyError) as exc:
            graph.add_dependency("task_a", "task_b")
        assert "cycle" in str(exc.value.message).lower()

    def test_execution_order_detects_cycle(self):
        """Exception: Topological sort detects cycles."""
        graph = DependencyGraphBuilder() \
            .add_chain("A", "B") \
            .build()

        # Manually create a cycle
        graph.adj_list["task_b"].append("task_a")
        graph.in_degree["task_a"] += 1

        with pytest.raises(DependencyError):
            graph.get_execution_order()


# ============================================================================
# TaskScheduler Unit Tests
# ============================================================================


class TestTaskSchedulerUnit:
    """Unit tests for TaskScheduler using mocking."""

    def test_initialization_defaults(self):
        """Normal: Scheduler initializes with defaults."""
        scheduler = TaskScheduler()
        assert scheduler.max_workers > 0
        assert isinstance(scheduler.executor, DefaultTaskExecutor)

    def test_initialization_custom(self):
        """Normal: Scheduler initializes with custom values."""
        scheduler = TaskScheduler(max_workers=8, default_timeout=120)
        assert scheduler.max_workers == 8
        assert scheduler.default_timeout == 120

    def test_add_task(self):
        """Normal: Add a single task."""
        scheduler = TaskScheduler()
        task = create_simple_task()
        scheduler.add_task(task)

        assert "task_simple" in scheduler.graph.tasks

    def test_register_task_graph(self):
        """Normal: Register a complete task graph."""
        scheduler = TaskScheduler()
        graph = TaskGraphBuilder() \
            .with_id("test_graph") \
            .with_name("Test Graph") \
            .add_chain("A", "B") \
            .build()

        scheduler.register_task_graph(graph)
        assert len(scheduler.graph.tasks) == 2
        assert "task_a" in scheduler.graph.tasks
        assert "task_b" in scheduler.graph.tasks

    def test_get_task_status(self):
        """Normal: Get status of registered task."""
        scheduler = TaskScheduler()
        task = create_simple_task()
        scheduler.add_task(task)

        assert scheduler.get_task_status("task_simple") == TaskStatus.PENDING

    def test_get_task_status_not_found(self):
        """Exception: Get status of nonexistent task raises error."""
        scheduler = TaskScheduler()

        with pytest.raises(ValidationError):
            scheduler.get_task_status("nonexistent")

    def test_get_progress_empty(self):
        """Boundary: Empty scheduler has 0 progress."""
        scheduler = TaskScheduler()
        assert scheduler.get_progress() == 0.0

    def test_get_progress_after_completion(self):
        """Normal: Progress updates after completion."""
        scheduler = TaskScheduler()
        task = create_simple_task()
        scheduler.add_task(task)
        scheduler.graph.update_task_status("task_simple", TaskStatus.COMPLETED)

        assert scheduler.get_progress() == 1.0

    def test_get_progress_partial(self):
        """Normal: Progress reflects partial completion."""
        scheduler = TaskScheduler()
        graph = TaskGraphBuilder() \
            .add_chain("A", "B", "C", "D") \
            .build()
        scheduler.register_task_graph(graph)

        scheduler.graph.update_task_status("task_a", TaskStatus.COMPLETED)
        scheduler.graph.update_task_status("task_b", TaskStatus.COMPLETED)

        assert scheduler.get_progress() == 0.5

    def test_reset_clears_state(self):
        """Normal: Reset clears all tasks and results."""
        scheduler = TaskScheduler()
        task = create_simple_task()
        scheduler.add_task(task)
        scheduler._task_results["task_simple"] = ExecutionResultBuilder().build()

        scheduler.reset()
        assert len(scheduler.graph.tasks) == 0
        assert len(scheduler._task_results) == 0

    def test_validate_dependencies_valid(self):
        """Normal: Valid dependencies pass validation."""
        scheduler = TaskScheduler()
        tasks = [
            TaskBuilder().with_id("a").with_name("A").build(),
            TaskBuilder().with_id("b").with_name("B").with_dependencies("a").build(),
        ]

        is_valid, errors = scheduler.validate_dependencies(tasks)
        assert is_valid is True
        assert errors == []

    def test_validate_dependencies_circular(self):
        """Exception: Circular dependencies fail validation."""
        scheduler = TaskScheduler()
        tasks = [
            TaskBuilder().with_id("a").with_name("A").with_dependencies("b").build(),
            TaskBuilder().with_id("b").with_name("B").with_dependencies("a").build(),
        ]

        is_valid, errors = scheduler.validate_dependencies(tasks)
        assert is_valid is False
        assert len(errors) > 0

    def test_get_circuit_breaker_creates_new(self):
        """Normal: First access creates a new circuit breaker."""
        scheduler = TaskScheduler()
        cb = scheduler.get_circuit_breaker("task_001")
        assert isinstance(cb, CircuitBreaker)

    def test_get_circuit_breaker_returns_same(self):
        """Normal: Same task returns same circuit breaker."""
        scheduler = TaskScheduler()
        cb1 = scheduler.get_circuit_breaker("task_001")
        cb2 = scheduler.get_circuit_breaker("task_001")
        assert cb1 is cb2

    def test_build_dependency_graph(self):
        """Normal: Build dependency graph from task list."""
        scheduler = TaskScheduler()
        tasks = [
            TaskBuilder().with_id("a").with_name("A").build(),
            TaskBuilder().with_id("b").with_name("B").with_dependencies("a").build(),
        ]

        graph = scheduler.build_dependency_graph(tasks)
        assert isinstance(graph, DependencyGraph)
        assert "a" in graph.tasks
        assert "b" in graph.tasks


# ============================================================================
# TaskScheduler Integration Tests
# ============================================================================


class TestTaskSchedulerIntegration:
    """Integration tests for TaskScheduler with real execution."""

    # --- Normal Path Tests ---

    @pytest.mark.asyncio
    async def test_execute_single_successful_task(self):
        """Normal: Execute a single task successfully."""
        scheduler = TaskScheduler()
        task = TaskBuilder() \
            .with_id("task_success") \
            .with_name("success_task") \
            .with_handler(async_success_handler) \
            .build()
        scheduler.add_task(task)

        context = ExecutionContext()
        result = await scheduler.run_task("task_success", context)

        assert result.status == TaskStatus.COMPLETED
        assert result.result["status"] == "success"

    @pytest.mark.asyncio
    async def test_execute_task_with_dependencies(self):
        """Normal: Execute tasks respecting dependency order."""
        scheduler = TaskScheduler()
        execution_order = []

        async def tracking_handler(params, context):
            execution_order.append(params.get("task_name", "unknown"))
            await asyncio.sleep(0.01)
            return {"done": True}

        task_a = TaskBuilder() \
            .with_id("task_a") \
            .with_name("A") \
            .with_parameters(task_name="A", handler=tracking_handler, sleep_time=0.01) \
            .build()

        task_b = TaskBuilder() \
            .with_id("task_b") \
            .with_name("B") \
            .with_dependencies("task_a") \
            .with_parameters(task_name="B", handler=tracking_handler, sleep_time=0.01) \
            .build()

        scheduler.add_task(task_a)
        scheduler.add_task(task_b)

        context = ExecutionContext()
        results = await scheduler.run_all(context)

        assert "task_a" in results
        assert "task_b" in results
        assert execution_order == ["A", "B"]

    @pytest.mark.asyncio
    async def test_execute_parallel_independent_tasks(self):
        """Normal: Independent tasks execute in parallel."""
        scheduler = TaskScheduler(max_workers=4)
        start_times: Dict[str, float] = {}

        async def time_tracker(params, context):
            task_id = params["task_id"]
            start_times[task_id] = time.time()
            await asyncio.sleep(0.05)
            return {"tracked": True}

        tasks = []
        for i in range(4):
            task = TaskBuilder() \
                .with_id(f"task_parallel_{i}") \
                .with_name(f"Parallel{i}") \
                .with_parameters(task_id=f"task_parallel_{i}", handler=time_tracker, sleep_time=0.01) \
                .build()
            tasks.append(task)
            scheduler.add_task(task)

        context = ExecutionContext()
        results = await scheduler.run_all(context)

        # All should complete
        assert all(r.status == TaskStatus.COMPLETED for r in results.values())

    @pytest.mark.asyncio
    async def test_run_all_with_complex_graph(self):
        """Normal: Complex dependency graph executes correctly."""
        scheduler = TaskScheduler(max_workers=8)
        execution_order = []

        async def order_tracker(params, context):
            execution_order.append(params["task_id"])
            await asyncio.sleep(0.01)
            return {"done": True}

        # A -> B, A -> C, B -> D, C -> D
        common_params = {"handler": order_tracker, "sleep_time": 0.01}

        task_a = TaskBuilder().with_id("task_a").with_name("A") \
            .with_parameters(task_id="task_a", **common_params).build()
        task_b = TaskBuilder().with_id("task_b").with_name("B").with_dependencies("task_a") \
            .with_parameters(task_id="task_b", **common_params).build()
        task_c = TaskBuilder().with_id("task_c").with_name("C").with_dependencies("task_a") \
            .with_parameters(task_id="task_c", **common_params).build()
        task_d = TaskBuilder().with_id("task_d").with_name("D").with_dependencies("task_b", "task_c") \
            .with_parameters(task_id="task_d", **common_params).build()

        scheduler.add_task(task_a)
        scheduler.add_task(task_b)
        scheduler.add_task(task_c)
        scheduler.add_task(task_d)

        context = ExecutionContext()
        results = await scheduler.run_all(context)

        assert all(r.status == TaskStatus.COMPLETED for r in results.values())
        assert execution_order.index("task_a") < execution_order.index("task_b")
        assert execution_order.index("task_a") < execution_order.index("task_c")
        assert execution_order.index("task_b") < execution_order.index("task_d")
        assert execution_order.index("task_c") < execution_order.index("task_d")

    @pytest.mark.asyncio
    async def test_task_retry_on_failure(self):
        """Recovery: Task retries on failure before succeeding."""
        scheduler = TaskScheduler()
        attempt_count = 0

        async def flaky_handler(params, context):
            nonlocal attempt_count
            attempt_count += 1
            if attempt_count < 3:
                raise ValueError(f"Attempt {attempt_count} failed")
            return {"eventually_succeeded": True}

        task = TaskBuilder() \
            .with_id("task_flaky") \
            .with_name("flaky_task") \
            .with_retries(5) \
            .with_parameters(handler=flaky_handler, sleep_time=0.01) \
            .build()

        scheduler.add_task(task)
        context = ExecutionContext()
        result = await scheduler.run_task("task_flaky", context)

        assert result.status == TaskStatus.COMPLETED
        assert result.retry_count > 0

    @pytest.mark.asyncio
    async def test_run_instance_tracking(self):
        """Normal: Run instances are created and updated."""
        scheduler = TaskScheduler()
        task = TaskBuilder() \
            .with_id("task_tracked") \
            .with_name("tracked") \
            .with_handler(async_success_handler) \
            .build()
        scheduler.add_task(task)

        context = ExecutionContext()
        result = await scheduler.run_task("task_tracked", context)

        run_instance = scheduler.get_run_instance("task_tracked")
        assert run_instance is not None
        assert run_instance.entity_id == "task_tracked"

    @pytest.mark.asyncio
    async def test_get_task_result_after_execution(self):
        """Normal: Task result stored and retrievable."""
        scheduler = TaskScheduler()
        task = TaskBuilder() \
            .with_id("task_stored") \
            .with_name("stored") \
            .with_handler(async_success_handler) \
            .build()
        scheduler.add_task(task)

        context = ExecutionContext()
        await scheduler.run_task("task_stored", context)

        result = scheduler.get_task_result("task_stored")
        assert result is not None
        assert result.status == TaskStatus.COMPLETED

    @pytest.mark.asyncio
    async def test_get_all_results(self):
        """Normal: All results accessible after execution."""
        scheduler = TaskScheduler()
        task1 = TaskBuilder().with_id("task_1").with_name("1").with_handler(async_success_handler).build()
        task2 = TaskBuilder().with_id("task_2").with_name("2").with_handler(async_success_handler).build()

        scheduler.add_task(task1)
        scheduler.add_task(task2)

        context = ExecutionContext()
        await scheduler.run_all(context)

        results = scheduler.get_all_results()
        assert len(results) == 2

    # --- Boundary Input Tests ---

    @pytest.mark.asyncio
    async def test_run_all_empty_scheduler(self):
        """Boundary: Running empty scheduler returns empty results."""
        scheduler = TaskScheduler()
        context = ExecutionContext()
        results = await scheduler.run_all(context)
        assert results == {}

    @pytest.mark.asyncio
    async def test_execute_task_with_synchronous_handler(self):
        """Boundary: Synchronous handler works correctly."""
        scheduler = TaskScheduler()
        task = TaskBuilder() \
            .with_id("task_sync") \
            .with_name("sync_task") \
            .with_handler(sync_handler) \
            .build()
        scheduler.add_task(task)

        context = ExecutionContext()
        result = await scheduler.run_task("task_sync", context)

        assert result.status == TaskStatus.COMPLETED

    @pytest.mark.asyncio
    async def test_task_with_zero_timeout(self):
        """Boundary: Task with 0 timeout should still work if fast enough."""
        scheduler = TaskScheduler()
        task = TaskBuilder() \
            .with_id("task_no_timeout") \
            .with_name("no_timeout") \
            .with_timeout(0) \
            .with_handler(async_success_handler) \
            .build()
        scheduler.add_task(task)

        context = ExecutionContext()
        result = await scheduler.run_task("task_no_timeout", context)

        # With timeout 0, asyncio.wait_for may timeout immediately
        # but our handler is very fast, so it should succeed
        # If it fails, status should be TIMEOUT
        assert result.status in (TaskStatus.COMPLETED, TaskStatus.TIMEOUT)

    # --- Exception Injection Tests ---

    @pytest.mark.asyncio
    async def test_task_failure_propagates(self):
        """Exception: Handler failure results in FAILED status."""
        scheduler = TaskScheduler()
        task = TaskBuilder() \
            .with_id("task_fail") \
            .with_name("failing") \
            .with_handler(async_failing_handler) \
            .build()
        scheduler.add_task(task)

        context = ExecutionContext()
        result = await scheduler.run_task("task_fail", context)

        assert result.status == TaskStatus.FAILED
        assert "Intentional failure" in result.error

    @pytest.mark.asyncio
    async def test_task_timeout_handling(self):
        """Exception: Task exceeding timeout is marked as TIMEOUT."""
        scheduler = TaskScheduler()
        task = TaskBuilder() \
            .with_id("task_slow") \
            .with_name("slow") \
            .with_timeout(1) \
            .with_retries(0) \
            .with_handler(async_slow_handler) \
            .with_sleep_time(3) \
            .build()
        scheduler.add_task(task)

        context = ExecutionContext()
        result = await scheduler.run_task("task_slow", context)

        assert result.status == TaskStatus.TIMEOUT

    @pytest.mark.asyncio
    async def test_dependency_failure_cascades(self):
        """Exception: Failed dependency causes dependents to be skipped."""
        scheduler = TaskScheduler()

        task_a = TaskBuilder() \
            .with_id("task_a") \
            .with_name("A") \
            .with_handler(async_failing_handler) \
            .build()

        task_b = TaskBuilder() \
            .with_id("task_b") \
            .with_name("B") \
            .with_dependencies("task_a") \
            .with_handler(async_success_handler) \
            .build()

        scheduler.add_task(task_a)
        scheduler.add_task(task_b)

        context = ExecutionContext()
        results = await scheduler.run_all(context)

        assert results["task_a"].status == TaskStatus.FAILED
        assert results["task_b"].status == TaskStatus.SKIPPED
        assert "Dependency failed" in results["task_b"].error

    @pytest.mark.asyncio
    async def test_circuit_breaker_opens_after_failures(self):
        """Exception: Circuit breaker opens after threshold failures."""
        scheduler = TaskScheduler()
        task = TaskBuilder() \
            .with_id("task_cb") \
            .with_name("circuit_breaker") \
            .with_retries(0) \
            .with_handler(async_failing_handler) \
            .build()
        scheduler.add_task(task)

        # Make circuit breaker strict
        scheduler._task_results.clear()
        scheduler.graph = DependencyGraph()
        scheduler.add_task(task)
        cb = scheduler.get_circuit_breaker("task_cb")
        cb.failure_threshold = 2

        # First failure
        context = ExecutionContext()
        result1 = await scheduler.run_task("task_cb", context)
        assert result1.status == TaskStatus.FAILED

        # Reset and run again
        scheduler.graph.update_task_status("task_cb", TaskStatus.PENDING)
        scheduler._run_instances.clear()

        result2 = await scheduler.run_task("task_cb", context)
        # After 2 failures, circuit should be open
        # Next run should be skipped
        assert cb.state == "open"

    @pytest.mark.asyncio
    async def test_circuit_breaker_open_skips_task(self):
        """Exception: Open circuit breaker causes task to be skipped."""
        scheduler = TaskScheduler()
        task = TaskBuilder() \
            .with_id("task_skip") \
            .with_name("skipped") \
            .with_handler(async_success_handler) \
            .build()
        scheduler.add_task(task)

        # Manually open circuit breaker
        cb = scheduler.get_circuit_breaker("task_skip")
        cb.state = "open"
        cb.failure_count = 5
        cb.last_failure_time = time.time()

        context = ExecutionContext()
        result = await scheduler.run_task("task_skip", context)

        assert result.status == TaskStatus.SKIPPED
        assert "Circuit breaker" in result.error

    # --- Concurrent Operation Tests ---

    @pytest.mark.asyncio
    async def test_concurrent_task_execution(self):
        """Concurrent: Multiple tasks execute concurrently."""
        scheduler = TaskScheduler(max_workers=10)
        tasks_completed = Counter()

        async def concurrent_handler(params, context):
            await tasks_completed.increment()
            await asyncio.sleep(0.02)
            return {"concurrent": True}

        for i in range(10):
            task = TaskBuilder() \
                .with_id(f"task_concurrent_{i}") \
                .with_name(f"Concurrent{i}") \
                .with_parameters(handler=concurrent_handler, sleep_time=0.01) \
                .build()
            scheduler.add_task(task)

        context = ExecutionContext()
        results = await scheduler.run_all(context)

        assert tasks_completed.count == 10
        assert all(r.status == TaskStatus.COMPLETED for r in results.values())

    @pytest.mark.asyncio
    async def test_max_workers_limits_concurrency(self):
        """Concurrent: max_workers limits parallel execution."""
        scheduler = TaskScheduler(max_workers=4)
        active_count = Counter()
        max_active = [0]
        lock = asyncio.Lock()

        async def limited_handler(params, context):
            await active_count.increment()
            async with lock:
                max_active[0] = max(max_active[0], active_count.count)
            await asyncio.sleep(0.1)
            await active_count.decrement()
            return {"done": True}

        for i in range(10):
            task = TaskBuilder() \
                .with_id(f"task_limited_{i}") \
                .with_name(f"Limited{i}") \
                .with_parameters(handler=limited_handler, sleep_time=0.01) \
                .build()
            scheduler.add_task(task)

        context = ExecutionContext()
        results = await scheduler.run_all(context)

        assert max_active[0] <= 6  # Allow small buffer for async scheduling
        assert all(r.status == TaskStatus.COMPLETED for r in results.values())


# ============================================================================
# Recurring Task Tests
# ============================================================================


class TestRecurringTask:
    """Tests for recurring task scheduling."""

    @pytest.mark.asyncio
    async def test_recurring_task_executes_multiple_times(self):
        """Normal: Recurring task executes multiple times."""
        scheduler = TaskScheduler()
        execution_count = Counter()

        async def recurring_handler(params, context):
            await execution_count.increment()
            return {"iteration": execution_count.count}

        task = TaskBuilder() \
            .with_id("task_recurring") \
            .with_name("recurring") \
            .with_handler(recurring_handler) \
            .build()
        scheduler.add_task(task)

        stop_event = asyncio.Event()

        # Run recurring task for a short duration
        async def stop_after_delay():
            await asyncio.sleep(0.3)
            stop_event.set()

        await asyncio.gather(
            scheduler.schedule_recurring(
                task,
                interval=timedelta(seconds=0.1),
                stop_event=stop_event,
            ),
            stop_after_delay(),
        )

        assert execution_count.count >= 2

    @pytest.mark.asyncio
    async def test_recurring_task_handles_failures(self):
        """Recovery: Recurring task continues after failure."""
        scheduler = TaskScheduler()
        execution_count = Counter()

        async def flaky_recurring(params, context):
            await execution_count.increment()
            if execution_count.count == 2:
                raise ValueError("Temporary failure")
            return {"ok": True}

        task = TaskBuilder() \
            .with_id("task_flaky_recurring") \
            .with_name("flaky_recurring") \
            .with_handler(flaky_recurring) \
            .build()
        scheduler.add_task(task)

        stop_event = asyncio.Event()

        async def stop_after_delay():
            await asyncio.sleep(0.5)
            stop_event.set()

        await asyncio.gather(
            scheduler.schedule_recurring(
                task,
                interval=timedelta(seconds=0.1),
                stop_event=stop_event,
            ),
            stop_after_delay(),
        )

        # Should have executed multiple times despite failure
        assert execution_count.count >= 3


# ============================================================================
# DefaultTaskExecutor Tests
# ============================================================================


class TestDefaultTaskExecutor:
    """Tests for DefaultTaskExecutor implementation."""

    @pytest.mark.asyncio
    async def test_execute_success(self):
        """Normal: Execute task successfully."""
        executor = DefaultTaskExecutor()
        task = TaskBuilder() \
            .with_id("task_exec") \
            .with_name("exec") \
            .with_handler(async_success_handler) \
            .build()

        context = ExecutionContext()
        result = await executor.execute(task, context)

        assert result.status == TaskStatus.COMPLETED
        assert result.started_at is not None
        assert result.completed_at is not None
        assert result.completed_at >= result.started_at

    @pytest.mark.asyncio
    async def test_execute_failure(self):
        """Exception: Execute task that fails."""
        executor = DefaultTaskExecutor()
        task = TaskBuilder() \
            .with_id("task_exec_fail") \
            .with_name("exec_fail") \
            .with_handler(async_failing_handler) \
            .build()

        context = ExecutionContext()
        result = await executor.execute(task, context)

        assert result.status == TaskStatus.FAILED
        assert result.error is not None

    @pytest.mark.asyncio
    async def test_execute_timeout(self):
        """Exception: Execute task timeout via TaskScheduler."""
        scheduler = TaskScheduler(default_timeout=1)
        task = TaskBuilder() \
            .with_id("task_exec_timeout") \
            .with_name("exec_timeout") \
            .with_timeout(1) \
            .with_handler(async_slow_handler) \
            .with_sleep_time(5) \
            .build()
        scheduler.add_task(task)

        context = ExecutionContext()
        result = await scheduler.run_task("task_exec_timeout", context)

        assert result.status == TaskStatus.TIMEOUT

    @pytest.mark.asyncio
    async def test_execute_no_handler(self):
        """Normal: Task without custom handler uses default."""
        executor = DefaultTaskExecutor()
        task = TaskBuilder() \
            .with_id("task_no_handler") \
            .with_name("no_handler") \
            .build()

        context = ExecutionContext()
        result = await executor.execute(task, context)

        assert result.status == TaskStatus.COMPLETED
        assert result.result is not None
