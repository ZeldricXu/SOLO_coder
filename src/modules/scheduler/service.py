from typing import Dict, List, Optional, Callable, Any
from datetime import datetime, timedelta
from collections import defaultdict
import asyncio
import heapq
from .types import (
    TaskDefinition,
    TaskExecution,
    TaskStatus,
    TaskPhase,
    TaskPriority,
    TaskLogEntry,
    TaskSummary,
    TaskCreateRequest,
    TaskUpdateRequest,
)
from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    NotFoundError,
    PlatformError,
    generate_id,
)
import logging

logger = logging.getLogger(__name__)


class SchedulerService:
    def __init__(self):
        self._tasks: Dict[str, TaskDefinition] = {}
        self._executions: Dict[str, TaskExecution] = {}
        self._task_executions: Dict[str, List[str]] = defaultdict(list)
        self._queue: List[tuple] = []
        self._running_tasks: Dict[str, asyncio.Task] = {}
        self._task_handlers: Dict[str, Callable] = {}
        self._worker_id = f"worker_{generate_id('')}"
        self._metrics = get_metrics_collector()
        self._lock = asyncio.Lock()

    def register_handler(self, task_type: str, handler: Callable) -> None:
        self._task_handlers[task_type] = handler
        logger.info(f"Registered handler for task type: {task_type}")

    async def create_task(
        self,
        request: TaskCreateRequest,
        trace_id: Optional[str] = None,
    ) -> TaskDefinition:
        with init_context(trace_id, operation="create_task"):
            try:
                task_id = generate_id("task")
                task = TaskDefinition(
                    task_id=task_id,
                    name=request.name,
                    type=request.type,
                    priority=request.priority,
                    payload=request.payload,
                    callback_url=request.callback_url,
                    timeout_seconds=request.timeout_seconds,
                    max_retries=request.max_retries,
                    dependencies=request.dependencies,
                    scheduled_at=request.scheduled_at,
                )

                self._tasks[task_id] = task

                execution = TaskExecution(
                    execution_id=generate_id("exec"),
                    task_id=task_id,
                )
                self._executions[execution.execution_id] = execution
                self._task_executions[task_id].append(execution.execution_id)

                if not request.scheduled_at or request.scheduled_at <= datetime.utcnow():
                    await self._enqueue_task(task)

                emit_event(
                    "task.created",
                    {"task_id": task_id, "name": request.name, "type": request.type},
                    source="scheduler",
                )

                self._metrics.increment("scheduler_tasks_created")
                return task

            except Exception as e:
                logger.error(f"Failed to create task: {e}")
                raise PlatformError(f"任务创建失败: {str(e)}")

    async def _enqueue_task(self, task: TaskDefinition) -> None:
        async with self._lock:
            priority = -task.priority.value
            scheduled_ts = task.scheduled_at.timestamp() if task.scheduled_at else 0
            heapq.heappush(self._queue, (priority, scheduled_ts, task.task_id, task))
            task.status = TaskStatus.QUEUED
            logger.info(f"Task {task.task_id} enqueued with priority {task.priority}")

    async def execute_tasks(self, max_concurrent: int = 10) -> None:
        while True:
            if len(self._running_tasks) >= max_concurrent:
                await asyncio.sleep(0.1)
                continue

            async with self._lock:
                if not self._queue:
                    break

                now = datetime.utcnow().timestamp()
                ready_tasks = []
                while self._queue:
                    priority, scheduled_ts, task_id, task = self._queue[0]
                    if scheduled_ts > now:
                        break
                    heapq.heappop(self._queue)
                    if self._check_dependencies(task):
                        ready_tasks.append(task)
                    else:
                        heapq.heappush(self._queue, (priority, scheduled_ts + 5, task_id, task))

            for task in ready_tasks:
                if len(self._running_tasks) >= max_concurrent:
                    async with self._lock:
                        priority = -task.priority.value
                        heapq.heappush(self._queue, (priority, now + 1, task.task_id, task))
                    continue

                self._running_tasks[task.task_id] = asyncio.create_task(
                    self._execute_task(task)
                )

            await asyncio.sleep(0.01)

    def _check_dependencies(self, task: TaskDefinition) -> bool:
        for dep_id in task.dependencies:
            dep_task = self._tasks.get(dep_id)
            if not dep_task:
                return False
            execution_ids = self._task_executions.get(dep_id, [])
            if not execution_ids:
                return False
            last_execution = self._executions.get(execution_ids[-1])
            if not last_execution or last_execution.status != TaskStatus.COMPLETED:
                return False
        return True

    async def _execute_task(self, task: TaskDefinition) -> None:
        execution_id = self._task_executions[task.task_id][-1]
        execution = self._executions[execution_id]

        execution.status = TaskStatus.RUNNING
        execution.phase = TaskPhase.INITIALIZING
        execution.started_at = datetime.utcnow()
        execution.worker_id = self._worker_id
        self._update_execution(execution)

        emit_event(
            "task.started",
            {"task_id": task.task_id, "execution_id": execution_id},
            source="scheduler",
        )

        self._metrics.increment("scheduler_tasks_running")
        self._add_log(execution, "INFO", f"Task started on worker {self._worker_id}")

        try:
            await self._update_task_status(task.task_id, phase=TaskPhase.PREPARING, progress=0.1)
            self._add_log(execution, "INFO", "Preparing task execution environment")

            handler = self._task_handlers.get(task.type)
            if not handler:
                raise ValueError(f"No handler registered for task type: {task.type}")

            await self._update_task_status(task.task_id, phase=TaskPhase.PROCESSING, progress=0.3)
            self._add_log(execution, "INFO", f"Executing handler for type: {task.type}")

            try:
                result = await asyncio.wait_for(
                    handler(task.payload, execution),
                    timeout=task.timeout_seconds,
                )

                await self._update_task_status(
                    task.task_id,
                    status=TaskStatus.COMPLETED,
                    phase=TaskPhase.FINALIZING,
                    progress=0.9,
                    result=result,
                )
                self._add_log(execution, "INFO", "Task execution completed successfully")

                execution.status = TaskStatus.COMPLETED
                execution.phase = TaskPhase.COMPLETED
                execution.progress = 1.0
                execution.completed_at = datetime.utcnow()
                self._update_execution(execution)

                emit_event(
                    "task.completed",
                    {"task_id": task.task_id, "execution_id": execution_id},
                    source="scheduler",
                )

                self._metrics.increment("scheduler_tasks_completed")

            except asyncio.TimeoutError:
                execution.status = TaskStatus.TIMEOUT
                execution.error_detail = f"Task timed out after {task.timeout_seconds}s"
                execution.completed_at = datetime.utcnow()
                self._update_execution(execution)
                self._add_log(execution, "ERROR", f"Task timed out: {execution.error_detail}")
                self._metrics.increment("scheduler_tasks_timeout")
                emit_event(
                    "task.timeout",
                    {"task_id": task.task_id, "execution_id": execution_id},
                    source="scheduler",
                )

        except Exception as e:
            execution.retry_count += 1
            if execution.retry_count < task.max_retries:
                execution.status = TaskStatus.PENDING
                self._add_log(
                    execution,
                    "WARNING",
                    f"Task failed, retry {execution.retry_count}/{task.max_retries}: {e}",
                )
                self._update_execution(execution)
                await self._enqueue_task(task)
                emit_event(
                    "task.retrying",
                    {"task_id": task.task_id, "retry": execution.retry_count},
                    source="scheduler",
                )
            else:
                execution.status = TaskStatus.FAILED
                execution.error_detail = str(e)
                execution.completed_at = datetime.utcnow()
                self._update_execution(execution)
                self._add_log(execution, "ERROR", f"Task failed permanently: {e}")
                self._metrics.increment("scheduler_tasks_failed")
                emit_event(
                    "task.failed",
                    {"task_id": task.task_id, "execution_id": execution_id, "error": str(e)},
                    source="scheduler",
                )

        finally:
            if task.task_id in self._running_tasks:
                del self._running_tasks[task.task_id]
            self._metrics.gauge(
                "scheduler_running_tasks",
                len(self._running_tasks),
            )

    async def _update_task_status(
        self,
        task_id: str,
        status: Optional[TaskStatus] = None,
        phase: Optional[TaskPhase] = None,
        progress: Optional[float] = None,
        current_step: Optional[str] = None,
        result: Optional[Dict[str, Any]] = None,
        error_detail: Optional[str] = None,
    ) -> None:
        execution_ids = self._task_executions.get(task_id, [])
        if not execution_ids:
            return

        execution = self._executions[execution_ids[-1]]
        if status:
            execution.status = status
        if phase:
            execution.phase = phase
        if progress is not None:
            execution.progress = max(0.0, min(1.0, progress))
        if current_step:
            execution.current_step = current_step
        if result:
            execution.result = result
        if error_detail:
            execution.error_detail = error_detail
        execution.updated_at = datetime.utcnow()

        self._update_execution(execution)

        emit_event(
            "task.updated",
            {
                "task_id": task_id,
                "status": execution.status.value,
                "phase": execution.phase.value,
                "progress": execution.progress,
            },
            source="scheduler",
        )

    def _update_execution(self, execution: TaskExecution) -> None:
        execution.updated_at = datetime.utcnow()
        self._executions[execution.execution_id] = execution

    def _add_log(self, execution: TaskExecution, level: str, message: str, details: Optional[Dict[str, Any]] = None) -> None:
        execution.logs.append(
            TaskLogEntry(level=level, message=message, details=details).model_dump()
        )

    async def get_task(self, task_id: str, trace_id: Optional[str] = None) -> TaskDefinition:
        with init_context(trace_id, operation="get_task"):
            task = self._tasks.get(task_id)
            if not task:
                raise NotFoundError(f"Task not found: {task_id}")
            return task

    async def get_task_execution(
        self,
        execution_id: str,
        trace_id: Optional[str] = None,
    ) -> TaskExecution:
        with init_context(trace_id, operation="get_task_execution"):
            execution = self._executions.get(execution_id)
            if not execution:
                raise NotFoundError(f"Execution not found: {execution_id}")
            return execution

    async def get_task_executions(
        self,
        task_id: str,
        trace_id: Optional[str] = None,
    ) -> List[TaskExecution]:
        with init_context(trace_id, operation="get_task_executions"):
            execution_ids = self._task_executions.get(task_id, [])
            return [self._executions[eid] for eid in execution_ids]

    async def list_tasks(
        self,
        status: Optional[TaskStatus] = None,
        task_type: Optional[str] = None,
        limit: int = 100,
        trace_id: Optional[str] = None,
    ) -> List[TaskDefinition]:
        with init_context(trace_id, operation="list_tasks"):
            tasks = list(self._tasks.values())
            if status:
                tasks = [t for t in tasks if self._get_latest_status(t.task_id) == status]
            if task_type:
                tasks = [t for t in tasks if t.type == task_type]
            return sorted(tasks, key=lambda t: t.created_at, reverse=True)[:limit]

    def _get_latest_status(self, task_id: str) -> Optional[TaskStatus]:
        execution_ids = self._task_executions.get(task_id, [])
        if not execution_ids:
            return None
        return self._executions[execution_ids[-1]].status

    async def cancel_task(self, task_id: str, trace_id: Optional[str] = None) -> bool:
        with init_context(trace_id, operation="cancel_task"):
            if task_id in self._running_tasks:
                self._running_tasks[task_id].cancel()
                del self._running_tasks[task_id]

            execution_ids = self._task_executions.get(task_id, [])
            if execution_ids:
                execution = self._executions[execution_ids[-1]]
                execution.status = TaskStatus.CANCELLED
                execution.completed_at = datetime.utcnow()
                self._update_execution(execution)

            self._queue = [(p, s, tid, t) for p, s, tid, t in self._queue if tid != task_id]

            emit_event("task.cancelled", {"task_id": task_id}, source="scheduler")
            return True

    async def get_summary(self, trace_id: Optional[str] = None) -> TaskSummary:
        with init_context(trace_id, operation="get_summary"):
            summary = TaskSummary(total=len(self._tasks))
            for task in self._tasks.values():
                status = self._get_latest_status(task.task_id)
                if status == TaskStatus.PENDING or status == TaskStatus.QUEUED:
                    summary.pending += 1
                elif status == TaskStatus.RUNNING:
                    summary.running += 1
                elif status == TaskStatus.COMPLETED:
                    summary.completed += 1
                elif status == TaskStatus.FAILED or status == TaskStatus.TIMEOUT:
                    summary.failed += 1
                elif status == TaskStatus.CANCELLED:
                    summary.cancelled += 1
            return summary
