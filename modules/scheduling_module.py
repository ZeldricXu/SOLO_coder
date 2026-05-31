import asyncio
import uuid
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Callable, Set
from datetime import datetime
from enum import Enum
from abc import ABC, abstractmethod
from .logging_module import get_logger
from .monitoring_module import get_monitoring

logger = get_logger(__name__)
monitoring = get_monitoring()


class TaskStatus(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    BLOCKED = "blocked"


class TaskType(str, Enum):
    SIMPLE = "simple"
    COMPOUND = "compound"
    WORKFLOW = "workflow"


@dataclass
class Task:
    task_id: str
    name: str
    type: TaskType
    payload: Dict[str, Any] = field(default_factory=dict)
    dependencies: List[str] = field(default_factory=list)
    status: TaskStatus = TaskStatus.PENDING
    priority: int = 0
    retries: int = 0
    max_retries: int = 3
    timeout: int = 30
    created_at: datetime = field(default_factory=datetime.utcnow)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    error: Optional[str] = None
    result: Optional[Dict[str, Any]] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class TaskResult:
    task_id: str
    success: bool
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None
    execution_time_ms: float = 0.0


class TaskExecutor(ABC):
    @abstractmethod
    async def execute(self, task: Task) -> TaskResult:
        pass


class DefaultTaskExecutor(TaskExecutor):
    async def execute(self, task: Task) -> TaskResult:
        start_time = time.time()
        try:
            await asyncio.sleep(0.1)
            result = {
                "task_id": task.task_id,
                "processed": True,
                "timestamp": datetime.utcnow().isoformat(),
                "payload": task.payload,
            }
            return TaskResult(
                task_id=task.task_id,
                success=True,
                result=result,
                execution_time_ms=(time.time() - start_time) * 1000,
            )
        except Exception as e:
            return TaskResult(
                task_id=task.task_id,
                success=False,
                error=str(e),
                execution_time_ms=(time.time() - start_time) * 1000,
            )


class TaskScheduler:
    def __init__(self):
        self._tasks: Dict[str, Task] = {}
        self._task_graph: Dict[str, List[str]] = {}
        self._reverse_deps: Dict[str, List[str]] = {}
        self._executors: Dict[str, TaskExecutor] = {"default": DefaultTaskExecutor()}
        self._completed_tasks: Set[str] = set()
        self._failed_tasks: Set[str] = set()
        self._queue: asyncio.Queue[Task] = asyncio.Queue()
        self._results: Dict[str, TaskResult] = {}
        self._running: bool = False
        self._workers: List[asyncio.Task] = []
        self._max_workers: int = 10

    def add_task(self, name: str, payload: Optional[Dict] = None,
                 dependencies: Optional[List[str]] = None,
                 task_type: TaskType = TaskType.SIMPLE,
                 priority: int = 0, max_retries: int = 3, timeout: int = 30) -> Task:
        task_id = f"task_{uuid.uuid4().hex[:8]}"
        task = Task(
            task_id=task_id, name=name, type=task_type,
            payload=payload or {}, dependencies=dependencies or [],
            priority=priority, max_retries=max_retries, timeout=timeout,
        )
        self._tasks[task_id] = task
        self._task_graph[task_id] = task.dependencies

        for dep in task.dependencies:
            if dep not in self._reverse_deps:
                self._reverse_deps[dep] = []
            self._reverse_deps[dep].append(task_id)

        if not task.dependencies:
            task.status = TaskStatus.QUEUED
            asyncio.create_task(self._enqueue_task(task))

        logger.info(f"Added task {task_id}: {name}")
        monitoring.collector.increment("tasks.created")
        return task

    def add_dependency(self, task_id: str, dependency_id: str) -> bool:
        if task_id not in self._tasks or dependency_id not in self._tasks:
            return False
        if self._would_create_cycle(task_id, dependency_id):
            logger.warning(f"Cycle detected: {task_id} -> {dependency_id}")
            return False

        if dependency_id not in self._tasks[task_id].dependencies:
            self._tasks[task_id].dependencies.append(dependency_id)
            self._task_graph[task_id].append(dependency_id)
            if dependency_id not in self._reverse_deps:
                self._reverse_deps[dependency_id] = []
            self._reverse_deps[dependency_id].append(task_id)
            self._tasks[task_id].status = TaskStatus.BLOCKED

        logger.info(f"Added dependency: {task_id} depends on {dependency_id}")
        return True

    def _would_create_cycle(self, task_id: str, dependency_id: str) -> bool:
        visited: Set[str] = set()

        def dfs(node: str) -> bool:
            if node == task_id:
                return True
            if node in visited:
                return False
            visited.add(node)
            for dep in self._task_graph.get(node, []):
                if dfs(dep):
                    return True
            return False

        return dfs(dependency_id)

    def get_task(self, task_id: str) -> Optional[Task]:
        return self._tasks.get(task_id)

    def list_tasks(self, status: Optional[TaskStatus] = None) -> List[Task]:
        tasks = list(self._tasks.values())
        if status:
            tasks = [t for t in tasks if t.status == status]
        return sorted(tasks, key=lambda t: (-t.priority, t.created_at))

    def get_ready_tasks(self) -> List[Task]:
        ready = []
        for task in self._tasks.values():
            if task.status == TaskStatus.BLOCKED:
                deps_met = all(dep in self._completed_tasks for dep in task.dependencies)
                deps_failed = any(dep in self._failed_tasks for dep in task.dependencies)
                if deps_failed:
                    task.status = TaskStatus.FAILED
                    task.error = "Dependency failed"
                    self._failed_tasks.add(task.task_id)
                elif deps_met:
                    task.status = TaskStatus.QUEUED
                    ready.append(task)
        return sorted(ready, key=lambda t: (-t.priority, t.created_at))

    def cancel_task(self, task_id: str) -> bool:
        task = self._tasks.get(task_id)
        if not task or task.status in [TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED]:
            return False
        task.status = TaskStatus.CANCELLED
        logger.info(f"Cancelled task {task_id}")
        return True

    def register_executor(self, name: str, executor: TaskExecutor) -> None:
        self._executors[name] = executor

    async def _enqueue_task(self, task: Task) -> None:
        await self._queue.put(task)
        task.status = TaskStatus.QUEUED

    async def _worker(self, worker_id: int) -> None:
        logger.info(f"Worker {worker_id} started")
        while self._running:
            try:
                task = await asyncio.wait_for(self._queue.get(), timeout=1.0)
            except asyncio.TimeoutError:
                continue

            if task.status == TaskStatus.CANCELLED:
                self._queue.task_done()
                continue

            task.status = TaskStatus.RUNNING
            task.started_at = datetime.utcnow()
            monitoring.collector.increment("tasks.started")

            try:
                executor_name = task.metadata.get("executor", "default")
                executor = self._executors.get(executor_name, self._executors["default"])

                result = await asyncio.wait_for(
                    executor.execute(task),
                    timeout=task.timeout,
                )
                self._results[task.task_id] = result

                if result.success:
                    task.status = TaskStatus.COMPLETED
                    task.result = result.result
                    task.completed_at = datetime.utcnow()
                    self._completed_tasks.add(task.task_id)
                    monitoring.collector.increment("tasks.completed")
                    logger.info(f"Task {task.task_id} completed in {result.execution_time_ms:.2f}ms")

                    for dependent_id in self._reverse_deps.get(task.task_id, []):
                        dependent = self._tasks.get(dependent_id)
                        if dependent and dependent.status == TaskStatus.BLOCKED:
                            deps_met = all(d in self._completed_tasks for d in dependent.dependencies)
                            if deps_met:
                                dependent.status = TaskStatus.QUEUED
                                await self._queue.put(dependent)
                else:
                    task.retries += 1
                    if task.retries < task.max_retries:
                        logger.warning(f"Task {task.task_id} failed, retry {task.retries}/{task.max_retries}")
                        task.status = TaskStatus.QUEUED
                        await asyncio.sleep(0.5 * task.retries)
                        await self._queue.put(task)
                    else:
                        task.status = TaskStatus.FAILED
                        task.error = result.error
                        self._failed_tasks.add(task.task_id)
                        monitoring.collector.increment("tasks.failed")
                        logger.error(f"Task {task.task_id} failed after {task.max_retries} retries: {result.error}")

            except asyncio.TimeoutError:
                task.retries += 1
                if task.retries < task.max_retries:
                    task.status = TaskStatus.QUEUED
                    await self._queue.put(task)
                else:
                    task.status = TaskStatus.FAILED
                    task.error = f"Timeout after {task.timeout}s"
                    self._failed_tasks.add(task.task_id)
                    logger.error(f"Task {task.task_id} timed out")
            except Exception as e:
                task.status = TaskStatus.FAILED
                task.error = str(e)
                self._failed_tasks.add(task.task_id)
                logger.error(f"Task {task.task_id} error: {e}")
            finally:
                self._queue.task_done()

    async def start(self, max_workers: int = 10) -> None:
        self._running = True
        self._max_workers = max_workers
        for i in range(max_workers):
            self._workers.append(asyncio.create_task(self._worker(i)))
        logger.info(f"Scheduler started with {max_workers} workers")

    async def stop(self) -> None:
        self._running = False
        for worker in self._workers:
            worker.cancel()
        await asyncio.gather(*self._workers, return_exceptions=True)
        self._workers.clear()
        logger.info("Scheduler stopped")

    async def wait_all(self) -> None:
        await self._queue.join()

    def get_stats(self) -> Dict[str, Any]:
        return {
            "total_tasks": len(self._tasks),
            "completed": len(self._completed_tasks),
            "failed": len(self._failed_tasks),
            "pending": len([t for t in self._tasks.values() if t.status == TaskStatus.PENDING]),
            "queued": len([t for t in self._tasks.values() if t.status == TaskStatus.QUEUED]),
            "running": len([t for t in self._tasks.values() if t.status == TaskStatus.RUNNING]),
            "blocked": len([t for t in self._tasks.values() if t.status == TaskStatus.BLOCKED]),
        }


_scheduler: Optional[TaskScheduler] = None


def get_scheduler() -> TaskScheduler:
    global _scheduler
    if _scheduler is None:
        _scheduler = TaskScheduler()
    return _scheduler
