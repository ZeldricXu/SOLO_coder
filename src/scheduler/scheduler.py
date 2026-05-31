import asyncio
import time
from abc import ABC, abstractmethod
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Awaitable, Callable, Dict, List, Optional, Set, Tuple
from uuid import uuid4

from src.config import get_settings
from src.logging_ import get_logger
from src.models import RunPhase, RunInstance, Task, TaskGraph
from src.utils.errors import DependencyError, TimeoutError, ValidationError
from src.utils.helpers import ExecutionContext, retry_async

logger = get_logger(__name__)


class TaskStatus(str, Enum):
    PENDING = "pending"
    READY = "ready"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"
    TIMEOUT = "timeout"


@dataclass
class ExecutionResult:
    task_id: str
    status: TaskStatus
    result: Optional[Any] = None
    error: Optional[str] = None
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    retry_count: int = 0
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def duration(self) -> float:
        if self.started_at and self.completed_at:
            return self.completed_at - self.started_at
        return 0.0


@dataclass
class ScheduledTask:
    task: Task
    status: TaskStatus = TaskStatus.PENDING
    result: Optional[ExecutionResult] = None
    dependencies: List[str] = field(default_factory=list)
    dependents: List[str] = field(default_factory=list)
    scheduled_time: Optional[datetime] = None
    priority: int = 0


class CircuitBreaker:
    def __init__(self, failure_threshold: int = 5, recovery_timeout: int = 30):
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self.failure_count: int = 0
        self.last_failure_time: Optional[float] = None
        self.state: str = "closed"

    def allow_request(self) -> bool:
        if self.state == "open":
            if (
                self.last_failure_time
                and (time.time() - self.last_failure_time) > self.recovery_timeout
            ):
                self.state = "half_open"
                return True
            return False
        return True

    def record_success(self) -> None:
        if self.state == "half_open":
            self.state = "closed"
        self.failure_count = 0

    def record_failure(self) -> None:
        self.failure_count += 1
        self.last_failure_time = time.time()
        if self.failure_count >= self.failure_threshold:
            self.state = "open"
            logger.warning(
                "Circuit breaker opened after %d failures",
                self.failure_count,
            )


class DependencyGraph:
    def __init__(self):
        self.tasks: Dict[str, ScheduledTask] = {}
        self.adj_list: Dict[str, List[str]] = defaultdict(list)
        self.in_degree: Dict[str, int] = defaultdict(int)

    def add_task(self, task: Task) -> None:
        if task.task_id in self.tasks:
            raise ValidationError(f"Task {task.task_id} already exists")

        self.tasks[task.task_id] = ScheduledTask(
            task=task,
            dependencies=task.dependencies.copy(),
        )
        self.in_degree[task.task_id] = len(task.dependencies)

        for dep in task.dependencies:
            self.adj_list[dep].append(task.task_id)
            if dep in self.tasks:
                self.tasks[dep].dependents.append(task.task_id)

    def add_dependency(self, task_id: str, dependency_id: str) -> None:
        if task_id not in self.tasks or dependency_id not in self.tasks:
            raise ValidationError("Task not found")

        if dependency_id in self.tasks[task_id].dependencies:
            return

        if self._would_create_cycle(task_id, dependency_id):
            raise DependencyError("Adding this dependency would create a cycle")

        self.tasks[task_id].dependencies.append(dependency_id)
        self.tasks[dependency_id].dependents.append(task_id)
        self.adj_list[dependency_id].append(task_id)
        self.in_degree[task_id] += 1

    def _would_create_cycle(self, task_id: str, dependency_id: str) -> bool:
        visited: Set[str] = set()
        queue = deque([task_id])

        while queue:
            current = queue.popleft()
            if current == dependency_id:
                return True
            if current in visited:
                continue
            visited.add(current)
            queue.extend(self.adj_list.get(current, []))

        return False

    def get_ready_tasks(self) -> List[ScheduledTask]:
        return [
            task
            for task in self.tasks.values()
            if task.status == TaskStatus.PENDING
            and all(
                self.tasks[dep].status == TaskStatus.COMPLETED
                for dep in task.dependencies
                if dep in self.tasks
            )
        ]

    def get_execution_order(self) -> List[str]:
        result: List[str] = []
        temp_in_degree = dict(self.in_degree)
        queue = deque(
            [task_id for task_id, deg in temp_in_degree.items() if deg == 0]
        )

        while queue:
            task_id = queue.popleft()
            result.append(task_id)
            for dependent in self.adj_list[task_id]:
                temp_in_degree[dependent] -= 1
                if temp_in_degree[dependent] == 0:
                    queue.append(dependent)

        if len(result) != len(self.tasks):
            raise DependencyError("Graph has circular dependencies")

        return result

    def update_task_status(self, task_id: str, status: TaskStatus) -> None:
        if task_id in self.tasks:
            self.tasks[task_id].status = status

    def get_all_statuses(self) -> Dict[str, TaskStatus]:
        return {task_id: task.status for task_id, task in self.tasks.items()}

    def is_complete(self) -> bool:
        return all(
            task.status in (TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.SKIPPED)
            for task in self.tasks.values()
        )

    def _check_dependency_status(
        self,
        task_id: str,
        results: Dict[str, "ExecutionResult"],
        expected_status: TaskStatus,
    ) -> bool:
        return all(
            dep in results and results[dep].status == expected_status
            for dep in self.tasks[task_id].dependencies
        )

    def get_ready_tasks_from_results(
        self,
        pending_tasks: Set[str],
        results: Dict[str, "ExecutionResult"],
    ) -> List[str]:
        return [
            task_id
            for task_id in pending_tasks
            if self._check_dependency_status(task_id, results, TaskStatus.COMPLETED)
        ]

    def get_failed_dependency_tasks(
        self,
        pending_tasks: Set[str],
        results: Dict[str, "ExecutionResult"],
    ) -> List[str]:
        return [
            task_id
            for task_id in pending_tasks
            if any(
                dep in results and results[dep].status != TaskStatus.COMPLETED
                for dep in self.tasks[task_id].dependencies
            )
        ]


class TaskExecutor(ABC):
    @abstractmethod
    async def execute(self, task: Task, context: ExecutionContext) -> ExecutionResult:
        pass


class DefaultTaskExecutor(TaskExecutor):
    async def execute(self, task: Task, context: ExecutionContext) -> ExecutionResult:
        result = ExecutionResult(
            task_id=task.task_id,
            status=TaskStatus.RUNNING,
            started_at=time.time(),
        )

        try:
            logger.info(
                "Executing task %s with parameters: %s",
                task.name,
                task.parameters,
                extra={"trace_id": context.trace_id, "task_id": task.task_id},
            )

            await asyncio.sleep(task.parameters.get("sleep_time", 0.1))

            handler = task.parameters.get("handler")
            if handler and callable(handler):
                if asyncio.iscoroutinefunction(handler):
                    actual_result = await handler(task.parameters, context)
                else:
                    actual_result = handler(task.parameters, context)
            else:
                actual_result = {"status": "executed", "task_name": task.name}

            result.status = TaskStatus.COMPLETED
            result.result = actual_result

        except asyncio.TimeoutError:
            result.status = TaskStatus.TIMEOUT
            result.error = f"Task timed out after {task.timeout}s"

        except Exception as e:
            result.status = TaskStatus.FAILED
            result.error = str(e)
            logger.exception(
                "Task execution failed: %s",
                task.name,
                extra={"trace_id": context.trace_id, "task_id": task.task_id},
            )

        finally:
            result.completed_at = time.time()

        return result


class TaskScheduler:
    def __init__(
        self,
        max_workers: Optional[int] = None,
        default_timeout: int = 3600,
        executor: Optional[TaskExecutor] = None,
    ):
        self.settings = get_settings()
        self.max_workers = max_workers or self.settings.SCHEDULER_MAX_WORKERS
        self.default_timeout = default_timeout
        self.executor = executor or DefaultTaskExecutor()
        self.graph = DependencyGraph()
        self.circuit_breakers: Dict[str, CircuitBreaker] = {}
        self._run_instances: Dict[str, RunInstance] = {}
        self._task_results: Dict[str, ExecutionResult] = {}

    @staticmethod
    def _create_execution_result(
        task_id: str,
        status: TaskStatus,
        result: Optional[Any] = None,
        error: Optional[str] = None,
        retry_count: int = 0,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> ExecutionResult:
        now = time.time()
        return ExecutionResult(
            task_id=task_id,
            status=status,
            result=result,
            error=error,
            started_at=now,
            completed_at=now,
            retry_count=retry_count,
            metadata=metadata or {},
        )

    def _create_run_instance(self, task_id: str) -> RunInstance:
        return RunInstance(
            run_id=f"run_{uuid4().hex[:8]}",
            entity_id=task_id,
            phase=RunPhase.EXECUTING,
            progress=0.0,
        )

    def _update_run_instance(
        self,
        task_id: str,
        result: ExecutionResult,
    ) -> None:
        if task_id not in self._run_instances:
            return

        run_instance = self._run_instances[task_id]
        run_instance.phase = (
            RunPhase.COMPLETED if result.status == TaskStatus.COMPLETED else RunPhase.FAILED
        )
        run_instance.progress = 1.0
        run_instance.completed_at = datetime.utcnow()
        run_instance.error_detail = result.error

    def _record_task_metrics(self, task_id: str, result: ExecutionResult, context: ExecutionContext) -> None:
        context.record_metric(f"task_{task_id}_duration", result.duration)
        context.record_metric(f"task_{task_id}_status", result.status.value)

    def register_task_graph(self, task_graph: TaskGraph) -> None:
        for task in task_graph.tasks:
            self.graph.add_task(task)
        logger.info("Registered task graph: %s with %d tasks", task_graph.name, len(task_graph.tasks))

    def add_task(self, task: Task) -> None:
        self.graph.add_task(task)

    def get_circuit_breaker(self, task_id: str) -> CircuitBreaker:
        if task_id not in self.circuit_breakers:
            self.circuit_breakers[task_id] = CircuitBreaker()
        return self.circuit_breakers[task_id]

    async def _execute_task_with_retry(
        self,
        task: Task,
        context: ExecutionContext,
    ) -> ExecutionResult:
        circuit_breaker = self.get_circuit_breaker(task.task_id)

        if not circuit_breaker.allow_request():
            logger.warning(
                "Circuit breaker open for task %s, skipping",
                task.task_id,
                extra={"trace_id": context.trace_id},
            )
            return self._create_execution_result(
                task_id=task.task_id,
                status=TaskStatus.SKIPPED,
                error="Circuit breaker is open",
            )

        @retry_async(
            max_attempts=task.retries,
            delay=0.1,
            backoff=2.0,
            exceptions=(Exception,),
        )
        async def _execute() -> ExecutionResult:
            try:
                result = await asyncio.wait_for(
                    self.executor.execute(task, context),
                    timeout=task.timeout or self.default_timeout,
                )
                if result.status == TaskStatus.FAILED:
                    raise Exception(result.error or "Task execution failed")
                return result
            except asyncio.TimeoutError:
                raise TimeoutError(
                    f"Task {task.name} timed out after {task.timeout}s",
                    {"task_id": task.task_id, "timeout": task.timeout},
                )

        try:
            result = await _execute()
            result.retry_count = task.retries

            if result.status == TaskStatus.COMPLETED:
                circuit_breaker.record_success()
            else:
                circuit_breaker.record_failure()

            return result

        except TimeoutError as e:
            circuit_breaker.record_failure()
            return self._create_execution_result(
                task_id=task.task_id,
                status=TaskStatus.TIMEOUT,
                error=str(e),
            )

        except Exception as e:
            circuit_breaker.record_failure()
            return self._create_execution_result(
                task_id=task.task_id,
                status=TaskStatus.FAILED,
                error=str(e),
            )

    async def run_task(
        self,
        task_id: str,
        context: Optional[ExecutionContext] = None,
    ) -> ExecutionResult:
        if task_id not in self.graph.tasks:
            raise ValidationError(f"Task {task_id} not found")

        context = context or ExecutionContext()
        scheduled_task = self.graph.tasks[task_id]

        self._run_instances[task_id] = self._create_run_instance(task_id)
        self.graph.update_task_status(task_id, TaskStatus.RUNNING)

        result = await self._execute_task_with_retry(scheduled_task.task, context)
        self._task_results[task_id] = result

        self.graph.update_task_status(task_id, result.status)
        self._update_run_instance(task_id, result)
        self._record_task_metrics(task_id, result, context)

        return result

    async def run_all(self, context: Optional[ExecutionContext] = None) -> Dict[str, ExecutionResult]:
        context = context or ExecutionContext()
        semaphore = asyncio.Semaphore(self.max_workers)
        results: Dict[str, ExecutionResult] = {}

        async def _run_task(task_id: str) -> None:
            async with semaphore:
                result = await self.run_task(task_id, context)
                results[task_id] = result

        execution_order = self.graph.get_execution_order()
        pending_tasks = set(execution_order)

        while pending_tasks:
            ready_tasks = self.graph.get_ready_tasks_from_results(pending_tasks, results)

            if not ready_tasks:
                failed_tasks = self.graph.get_failed_dependency_tasks(pending_tasks, results)
                for task_id in failed_tasks:
                    results[task_id] = self._create_execution_result(
                        task_id=task_id,
                        status=TaskStatus.SKIPPED,
                        error="Dependency failed",
                    )
                    self.graph.update_task_status(task_id, TaskStatus.SKIPPED)
                    pending_tasks.discard(task_id)
                if not failed_tasks:
                    break
                continue

            tasks = [_run_task(task_id) for task_id in ready_tasks]
            await asyncio.gather(*tasks)

            for task_id in ready_tasks:
                pending_tasks.discard(task_id)

        return results

    def get_task_status(self, task_id: str) -> TaskStatus:
        if task_id in self.graph.tasks:
            return self.graph.tasks[task_id].status
        raise ValidationError(f"Task {task_id} not found")

    def get_task_result(self, task_id: str) -> Optional[ExecutionResult]:
        return self._task_results.get(task_id)

    def get_run_instance(self, task_id: str) -> Optional[RunInstance]:
        return self._run_instances.get(task_id)

    def get_all_results(self) -> Dict[str, ExecutionResult]:
        return self._task_results.copy()

    def get_progress(self) -> float:
        if not self.graph.tasks:
            return 0.0
        completed = sum(
            1
            for task in self.graph.tasks.values()
            if task.status in (TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.SKIPPED)
        )
        return completed / len(self.graph.tasks)

    def reset(self) -> None:
        self.graph = DependencyGraph()
        self._run_instances.clear()
        self._task_results.clear()

    async def schedule_recurring(
        self,
        task: Task,
        interval: timedelta,
        stop_event: Optional[asyncio.Event] = None,
    ) -> None:
        stop_event = stop_event or asyncio.Event()
        logger.info("Scheduling recurring task %s every %s", task.name, interval)

        while not stop_event.is_set():
            try:
                context = ExecutionContext()
                await self.run_task(task.task_id, context)
            except Exception as e:
                logger.exception("Recurring task failed: %s", e)

            try:
                await asyncio.wait_for(stop_event.wait(), timeout=interval.total_seconds())
            except asyncio.TimeoutError:
                pass

    def build_dependency_graph(self, tasks: List[Task]) -> DependencyGraph:
        graph = DependencyGraph()
        for task in tasks:
            graph.add_task(task)
        return graph

    def validate_dependencies(self, tasks: List[Task]) -> Tuple[bool, List[str]]:
        try:
            graph = self.build_dependency_graph(tasks)
            graph.get_execution_order()
            return True, []
        except DependencyError as e:
            return False, [e.message]
