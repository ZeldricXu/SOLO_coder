from typing import Dict, List, Optional, Callable, Any
from datetime import datetime, timedelta
from collections import defaultdict
import asyncio
import heapq
from .types import (
    GpuDevice,
    GpuTask,
    GpuTaskExecution,
    GpuTaskStatus,
    GpuPriority,
    GpuAllocation,
    GpuClusterStats,
    GpuTaskSubmitRequest,
)
from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    NotFoundError,
    PlatformError,
    generate_id,
    settings,
)
import logging

logger = logging.getLogger(__name__)


class GpuSchedulerService:
    def __init__(self, total_gpus: int = 8, total_memory_gb: Optional[float] = None):
        self._total_gpus = total_gpus
        self._total_memory = total_memory_gb or (total_gpus * settings.gpu_total_memory_gb / max(total_gpus, 1))
        self._gpu_memory_per_gpu = self._total_memory / total_gpus if total_gpus > 0 else 0

        self._devices: Dict[str, GpuDevice] = {}
        self._init_devices()

        self._tasks: Dict[str, GpuTask] = {}
        self._executions: Dict[str, GpuTaskExecution] = {}
        self._task_executions: Dict[str, List[str]] = defaultdict(list)

        self._queue: List[tuple] = []
        self._running_tasks: Dict[str, asyncio.Task] = {}
        self._allocations: Dict[str, List[GpuAllocation]] = defaultdict(list)
        self._task_handlers: Dict[str, Callable] = {}

        self._metrics = get_metrics_collector()
        self._lock = asyncio.Lock()

    def _init_devices(self) -> None:
        for i in range(self._total_gpus):
            gpu_id = f"gpu_{i}"
            self._devices[gpu_id] = GpuDevice(
                gpu_id=gpu_id,
                index=i,
                name=f"NVIDIA A100-{i}",
                total_memory_gb=self._gpu_memory_per_gpu,
                available_memory_gb=self._gpu_memory_per_gpu,
            )
        logger.info(f"Initialized {self._total_gpus} GPUs with {self._total_memory:.1f}GB total memory")

    def register_handler(self, task_type: str, handler: Callable) -> None:
        self._task_handlers[task_type] = handler
        logger.info(f"Registered GPU task handler for type: {task_type}")

    async def submit_task(
        self,
        request: GpuTaskSubmitRequest,
        trace_id: Optional[str] = None,
    ) -> GpuTask:
        with init_context(trace_id, operation="submit_gpu_task"):
            try:
                if request.required_memory_gb > self._gpu_memory_per_gpu * request.required_gpus:
                    raise ValueError(
                        f"Requested memory {request.required_memory_gb}GB exceeds "
                        f"available per-GPU memory {self._gpu_memory_per_gpu}GB * {request.required_gpus}"
                    )

                task_id = generate_id("gtask")
                task = GpuTask(
                    task_id=task_id,
                    name=request.name,
                    priority=request.priority,
                    required_memory_gb=request.required_memory_gb,
                    required_gpus=request.required_gpus,
                    allow_preemption=request.allow_preemption,
                    payload=request.payload,
                    callback_url=request.callback_url,
                    max_runtime_seconds=request.max_runtime_seconds,
                )

                self._tasks[task_id] = task

                execution = GpuTaskExecution(
                    execution_id=generate_id("gexec"),
                    task_id=task_id,
                )
                self._executions[execution.execution_id] = execution
                self._task_executions[task_id].append(execution.execution_id)

                await self._enqueue_task(task)

                emit_event(
                    "gpu.task.submitted",
                    {"task_id": task_id, "memory_gb": request.required_memory_gb},
                    source="gpu_scheduler",
                )

                self._metrics.increment("gpu_tasks_submitted")
                return task

            except Exception as e:
                logger.error(f"Failed to submit GPU task: {e}")
                raise PlatformError(f"GPU任务提交失败: {str(e)}")

    async def _enqueue_task(self, task: GpuTask) -> None:
        async with self._lock:
            priority = -task.priority.value
            heapq.heappush(self._queue, (priority, task.created_at.timestamp(), task.task_id, task))
            execution_id = self._task_executions[task.task_id][-1]
            self._executions[execution_id].status = GpuTaskStatus.QUEUED
            logger.info(f"GPU task {task.task_id} enqueued with priority {task.priority}")

    async def schedule_tasks(self, max_concurrent: int = 8) -> None:
        while True:
            if len(self._running_tasks) >= max_concurrent:
                await asyncio.sleep(0.1)
                continue

            async with self._lock:
                if not self._queue:
                    break

                scheduled = []
                remaining = []

                while self._queue:
                    priority, ts, task_id, task = heapq.heappop(self._queue)
                    allocated = await self._try_allocate(task)
                    if allocated:
                        scheduled.append((priority, ts, task_id, task))
                    else:
                        remaining.append((priority, ts, task_id, task))
                        if len(scheduled) == 0:
                            higher_priority_can_preempt = await self._check_preemption(task)
                            if higher_priority_can_preempt:
                                break

                for item in remaining:
                    heapq.heappush(self._queue, item)

            for priority, ts, task_id, task in scheduled:
                self._running_tasks[task_id] = asyncio.create_task(
                    self._execute_gpu_task(task)
                )

            await asyncio.sleep(0.01)

    async def _try_allocate(self, task: GpuTask) -> bool:
        memory_per_gpu = task.required_memory_gb / task.required_gpus
        available_gpus = [
            dev for dev in self._devices.values()
            if dev.available_memory_gb >= memory_per_gpu and dev.healthy
        ]

        if len(available_gpus) < task.required_gpus:
            return False

        sorted_gpus = sorted(available_gpus, key=lambda g: g.available_memory_gb)
        selected_gpus = sorted_gpus[:task.required_gpus]

        for gpu in selected_gpus:
            gpu.available_memory_gb -= memory_per_gpu
            gpu.used_memory_gb += memory_per_gpu

            allocation = GpuAllocation(
                allocation_id=generate_id("alloc"),
                task_id=task.task_id,
                gpu_id=gpu.gpu_id,
                memory_gb=memory_per_gpu,
                priority=task.priority,
                start_time=datetime.utcnow(),
            )
            self._allocations[task.task_id].append(allocation)

        execution_id = self._task_executions[task.task_id][-1]
        execution = self._executions[execution_id]
        execution.gpu_ids = [g.gpu_id for g in selected_gpus]
        execution.allocated_memory_gb = task.required_memory_gb
        execution.status = GpuTaskStatus.SCHEDULED

        return True

    async def _check_preemption(self, task: GpuTask) -> bool:
        if not task.allow_preemption:
            return False

        lower_priority_allocations = [
            (task_id, allocs)
            for task_id, allocs in self._allocations.items()
            if allocs and allocs[0].priority < task.priority
        ]

        if not lower_priority_allocations:
            return False

        sorted_by_priority = sorted(
            lower_priority_allocations,
            key=lambda x: x[1][0].priority.value
        )

        for preempt_task_id, _ in sorted_by_priority:
            await self._preempt_task(preempt_task_id, task.task_id)

            total_freed = sum(
                alloc.memory_gb for alloc in self._allocations.get(preempt_task_id, [])
            )
            if total_freed >= task.required_memory_gb:
                return True

        return False

    async def _preempt_task(self, task_id: str, preempted_by: str) -> None:
        logger.warning(f"Preempting GPU task {task_id} for higher priority task {preempted_by}")

        if task_id in self._running_tasks:
            self._running_tasks[task_id].cancel()
            del self._running_tasks[task_id]

        allocations = self._allocations.pop(task_id, [])
        for alloc in allocations:
            gpu = self._devices.get(alloc.gpu_id)
            if gpu:
                gpu.available_memory_gb += alloc.memory_gb
                gpu.used_memory_gb -= alloc.memory_gb

        execution_id = self._task_executions.get(task_id, [None])[-1]
        if execution_id:
            execution = self._executions[execution_id]
            execution.status = GpuTaskStatus.PREEMPTED
            execution.preemption_count += 1
            self._executions[execution_id] = execution

            preempted_task = self._tasks.get(task_id)
            if preempted_task and preempted_task.allow_preemption:
                await self._enqueue_task(preempted_task)

        emit_event(
            "gpu.task.preempted",
            {"task_id": task_id, "preempted_by": preempted_by},
            source="gpu_scheduler",
        )
        self._metrics.increment("gpu_tasks_preempted")

    async def _execute_gpu_task(self, task: GpuTask) -> None:
        execution_id = self._task_executions[task.task_id][-1]
        execution = self._executions[execution_id]

        execution.status = GpuTaskStatus.RUNNING
        execution.started_at = datetime.utcnow()
        self._executions[execution_id] = execution

        emit_event(
            "gpu.task.started",
            {"task_id": task.task_id, "gpus": execution.gpu_ids},
            source="gpu_scheduler",
        )

        self._metrics.increment("gpu_tasks_running")

        try:
            task_type = task.payload.get("type", "default")
            handler = self._task_handlers.get(task_type, self._default_handler)

            result = await asyncio.wait_for(
                handler(task.payload, execution),
                timeout=task.max_runtime_seconds,
            )

            execution.status = GpuTaskStatus.COMPLETED
            execution.result = result
            execution.completed_at = datetime.utcnow()
            self._executions[execution_id] = execution

            emit_event(
                "gpu.task.completed",
                {"task_id": task.task_id, "runtime": (datetime.utcnow() - execution.started_at).total_seconds()},
                source="gpu_scheduler",
            )

            self._metrics.increment("gpu_tasks_completed")

        except asyncio.TimeoutError:
            execution.status = GpuTaskStatus.FAILED
            execution.error_detail = f"Task timed out after {task.max_runtime_seconds}s"
            execution.completed_at = datetime.utcnow()
            self._executions[execution_id] = execution
            self._metrics.increment("gpu_tasks_timeout")
            emit_event("gpu.task.timeout", {"task_id": task.task_id}, source="gpu_scheduler")

        except Exception as e:
            execution.status = GpuTaskStatus.FAILED
            execution.error_detail = str(e)
            execution.completed_at = datetime.utcnow()
            self._executions[execution_id] = execution
            self._metrics.increment("gpu_tasks_failed")
            emit_event("gpu.task.failed", {"task_id": task.task_id, "error": str(e)}, source="gpu_scheduler")

        finally:
            await self._release_resources(task.task_id)
            if task.task_id in self._running_tasks:
                del self._running_tasks[task.task_id]

    async def _default_handler(self, payload: Dict[str, Any], execution: GpuTaskExecution) -> Dict[str, Any]:
        await asyncio.sleep(0.1)
        return {"status": "completed", "processed": True}

    async def _release_resources(self, task_id: str) -> None:
        allocations = self._allocations.pop(task_id, [])
        for alloc in allocations:
            gpu = self._devices.get(alloc.gpu_id)
            if gpu:
                gpu.available_memory_gb += alloc.memory_gb
                gpu.used_memory_gb -= alloc.memory_gb

    async def get_task(self, task_id: str, trace_id: Optional[str] = None) -> GpuTask:
        with init_context(trace_id, operation="get_gpu_task"):
            task = self._tasks.get(task_id)
            if not task:
                raise NotFoundError(f"GPU task not found: {task_id}")
            return task

    async def get_task_execution(self, execution_id: str, trace_id: Optional[str] = None) -> GpuTaskExecution:
        with init_context(trace_id, operation="get_gpu_task_execution"):
            execution = self._executions.get(execution_id)
            if not execution:
                raise NotFoundError(f"GPU execution not found: {execution_id}")
            return execution

    async def get_cluster_stats(self, trace_id: Optional[str] = None) -> GpuClusterStats:
        with init_context(trace_id, operation="get_gpu_cluster_stats"):
            total_gpus = len(self._devices)
            available_gpus = sum(1 for d in self._devices.values() if d.available_memory_gb > 0.1)
            total_memory = sum(d.total_memory_gb for d in self._devices.values())
            used_memory = sum(d.used_memory_gb for d in self._devices.values())
            avg_utilization = (
                sum(d.utilization for d in self._devices.values()) / total_gpus
                if total_gpus > 0
                else 0.0
            )

            return GpuClusterStats(
                total_gpus=total_gpus,
                available_gpus=available_gpus,
                total_memory_gb=total_memory,
                used_memory_gb=used_memory,
                pending_tasks=len(self._queue),
                running_tasks=len(self._running_tasks),
                avg_utilization=avg_utilization,
            )

    async def list_devices(self, trace_id: Optional[str] = None) -> List[GpuDevice]:
        with init_context(trace_id, operation="list_gpu_devices"):
            return list(self._devices.values())

    async def cancel_task(self, task_id: str, trace_id: Optional[str] = None) -> bool:
        with init_context(trace_id, operation="cancel_gpu_task"):
            if task_id in self._running_tasks:
                self._running_tasks[task_id].cancel()
                del self._running_tasks[task_id]

            self._queue = [(p, t, tid, tsk) for p, t, tid, tsk in self._queue if tid != task_id]
            await self._release_resources(task_id)

            emit_event("gpu.task.cancelled", {"task_id": task_id}, source="gpu_scheduler")
            return True
