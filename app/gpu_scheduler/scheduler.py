import asyncio
import uuid
import time
import heapq
from typing import Dict, List, Optional, Any, Callable
from dataclasses import dataclass, field
from collections import defaultdict, deque
from datetime import datetime
from app.logging_module import get_logger
from app.config import settings
from .models import (
    TaskRequest, TaskResponse, GPUAllocation,
    GPUStatusReport, TaskPriority
)


logger = get_logger(__name__)


@dataclass(order=True)
class GPUTask:
    priority: int
    task_id: str = field(compare=False)
    name: str = field(compare=False)
    gpu_count: int = field(compare=False)
    gpu_memory_mb: int = field(compare=False)
    preemptible: bool = field(compare=False)
    estimated_duration: float = field(compare=False)
    status: str = field(default="queued", compare=False)
    queued_at: float = field(default_factory=time.time, compare=False)
    started_at: Optional[float] = field(default=None, compare=False)
    completed_at: Optional[float] = field(default=None, compare=False)
    gpu_ids: List[int] = field(default_factory=list, compare=False)
    memory_allocated: int = field(default=0, compare=False)
    command: Optional[str] = field(default=None, compare=False)
    parameters: Dict[str, Any] = field(default_factory=dict, compare=False)
    callback_url: Optional[str] = field(default=None, compare=False)
    metadata: Dict[str, Any] = field(default_factory=dict, compare=False)
    error_message: Optional[str] = field(default=None, compare=False)
    preempt_count: int = field(default=0, compare=False)


@dataclass
class GPUResource:
    gpu_id: int
    total_memory_mb: int
    available_memory_mb: int
    status: str = "idle"
    current_tasks: Dict[str, GPUTask] = field(default_factory=dict)
    utilization_percent: float = 0.0
    temperature_celsius: Optional[float] = None
    
    @property
    def utilized_memory_mb(self) -> int:
        return self.total_memory_mb - self.available_memory_mb
    
    def can_allocate(self, memory_mb: int) -> bool:
        return self.available_memory_mb >= memory_mb and self.status != "unavailable"
    
    def allocate(self, task: GPUTask, memory_mb: int) -> bool:
        if not self.can_allocate(memory_mb):
            return False
        
        self.available_memory_mb -= memory_mb
        self.current_tasks[task.task_id] = task
        self._update_status()
        return True
    
    def release(self, task_id: str) -> int:
        task = self.current_tasks.pop(task_id, None)
        if task:
            released = task.memory_allocated
            self.available_memory_mb += released
            self._update_status()
            return released
        return 0
    
    def _update_status(self):
        if self.status == "unavailable":
            return
        
        utilization = 1 - (self.available_memory_mb / self.total_memory_mb)
        self.utilization_percent = utilization * 100
        
        if self.available_memory_mb == self.total_memory_mb:
            self.status = "idle"
        elif self.available_memory_mb == 0:
            self.status = "full"
        else:
            self.status = "partial"


class PriorityQueue:
    def __init__(self):
        self._heap: List[GPUTask] = []
        self._task_map: Dict[str, GPUTask] = {}
        self._counter = 0
    
    def push(self, task: GPUTask):
        heapq.heappush(self._heap, task)
        self._task_map[task.task_id] = task
        self._counter += 1
    
    def pop(self) -> Optional[GPUTask]:
        while self._heap:
            task = heapq.heappop(self._heap)
            if task.task_id in self._task_map:
                del self._task_map[task.task_id]
                return task
        return None
    
    def peek(self) -> Optional[GPUTask]:
        while self._heap:
            if self._heap[0].task_id in self._task_map:
                return self._heap[0]
            heapq.heappop(self._heap)
        return None
    
    def remove(self, task_id: str) -> Optional[GPUTask]:
        task = self._task_map.pop(task_id, None)
        return task
    
    def get(self, task_id: str) -> Optional[GPUTask]:
        return self._task_map.get(task_id)
    
    def __len__(self) -> int:
        return len(self._task_map)
    
    def __iter__(self):
        return iter(list(self._task_map.values()))


class GPUScheduler:
    def __init__(self, gpu_count: int = None, memory_per_gpu_mb: int = None):
        self._gpu_count = gpu_count or settings.GPU_COUNT
        self._memory_per_gpu = memory_per_gpu_mb or settings.GPU_MEMORY_PER_DEVICE
        
        self._gpus: Dict[int, GPUResource] = {}
        for i in range(self._gpu_count):
            self._gpus[i] = GPUResource(
                gpu_id=i,
                total_memory_mb=self._memory_per_gpu,
                available_memory_mb=self._memory_per_gpu
            )
        
        self._queue = PriorityQueue()
        self._running_tasks: Dict[str, GPUTask] = {}
        self._completed_tasks: Dict[str, GPUTask] = {}
        
        self._task_executors: Dict[str, Callable] = {}
        self._scheduler_task: Optional[asyncio.Task] = None
        self._monitor_task: Optional[asyncio.Task] = None
        self._running = False
        
        self._preemption_enabled = True
        self._task_callbacks: Dict[str, List[Callable]] = defaultdict(list)
    
    async def start(self):
        if self._running:
            return
        
        self._running = True
        self._scheduler_task = asyncio.create_task(self._scheduling_loop())
        self._monitor_task = asyncio.create_task(self._monitoring_loop())
        logger.info(f"GPU scheduler started", gpu_count=self._gpu_count)
    
    async def stop(self):
        self._running = False
        
        if self._scheduler_task:
            self._scheduler_task.cancel()
            try:
                await self._scheduler_task
            except asyncio.CancelledError:
                pass
        
        if self._monitor_task:
            self._monitor_task.cancel()
            try:
                await self._monitor_task
            except asyncio.CancelledError:
                pass
        
        logger.info("GPU scheduler stopped")
    
    def register_executor(self, task_type: str, executor: Callable):
        self._task_executors[task_type] = executor
        logger.info(f"Registered executor", task_type=task_type)
    
    def add_task_callback(self, event: str, callback: Callable):
        self._task_callbacks[event].append(callback)
    
    async def submit_task(self, request: TaskRequest) -> TaskResponse:
        task = GPUTask(
            priority=11 - request.priority,
            task_id=f"task_{uuid.uuid4().hex[:12]}",
            name=request.name,
            gpu_count=request.gpu_count,
            gpu_memory_mb=request.gpu_memory_mb,
            preemptible=request.preemptible,
            estimated_duration=request.estimated_duration_seconds,
            command=request.command,
            parameters=request.parameters.copy(),
            callback_url=request.callback_url,
            metadata=request.metadata.copy()
        )
        
        self._queue.push(task)
        
        logger.info(
            f"Task submitted",
            task_id=task.task_id,
            name=task.name,
            priority=request.priority,
            gpu_count=task.gpu_count
        )
        
        await self._notify_callbacks("submitted", task)
        
        return TaskResponse(
            task_id=task.task_id,
            name=task.name,
            status="queued",
            queue_position=self._get_queue_position(task.task_id),
            estimated_wait_seconds=self._estimate_wait_time(task)
        )
    
    async def cancel_task(self, task_id: str) -> bool:
        task = self._queue.remove(task_id)
        if task:
            task.status = "cancelled"
            logger.info(f"Task cancelled", task_id=task_id)
            await self._notify_callbacks("cancelled", task)
            return True
        
        task = self._running_tasks.get(task_id)
        if task:
            if task.preemptible:
                await self._preempt_task(task, "cancelled")
                return True
            else:
                logger.warning(f"Cannot cancel non-preemptible task", task_id=task_id)
                return False
        
        return False
    
    def get_task_status(self, task_id: str) -> Optional[TaskResponse]:
        task = self._running_tasks.get(task_id)
        if not task:
            task = self._queue.get(task_id)
        if not task:
            task = self._completed_tasks.get(task_id)
        
        if not task:
            return None
        
        return TaskResponse(
            task_id=task.task_id,
            name=task.name,
            status=task.status,
            gpu_ids=task.gpu_ids if task.gpu_ids else None,
            queued_at=datetime.fromtimestamp(task.queued_at) if task.queued_at else None,
            started_at=datetime.fromtimestamp(task.started_at) if task.started_at else None,
            completed_at=datetime.fromtimestamp(task.completed_at) if task.completed_at else None,
            error_message=task.error_message,
            queue_position=self._get_queue_position(task_id)
        )
    
    def get_gpu_status(self) -> List[GPUStatusReport]:
        reports = []
        for gpu_id, gpu in self._gpus.items():
            reports.append(GPUStatusReport(
                gpu_id=gpu_id,
                status=gpu.status,
                total_memory_mb=gpu.total_memory_mb,
                available_memory_mb=gpu.available_memory_mb,
                utilized_memory_mb=gpu.utilized_memory_mb,
                utilization_percent=gpu.utilization_percent,
                current_tasks=list(gpu.current_tasks.keys()),
                temperature_celsius=gpu.temperature_celsius
            ))
        return reports
    
    def get_queue_stats(self) -> Dict[str, Any]:
        by_priority = defaultdict(int)
        for task in self._queue:
            priority = 11 - task.priority
            by_priority[priority] += 1
        
        return {
            "queued_tasks": len(self._queue),
            "running_tasks": len(self._running_tasks),
            "completed_tasks": len(self._completed_tasks),
            "by_priority": dict(by_priority),
            "total_gpu_memory_gb": (self._gpu_count * self._memory_per_gpu) / 1024,
            "available_gpu_memory_gb": sum(g.available_memory_mb for g in self._gpus.values()) / 1024
        }
    
    async def _scheduling_loop(self):
        while self._running:
            await self._try_schedule()
            await asyncio.sleep(0.1)
    
    async def _try_schedule(self):
        while True:
            next_task = self._queue.peek()
            if not next_task:
                break
            
            allocation = self._find_allocation(next_task)
            if allocation:
                task = self._queue.pop()
                if task:
                    await self._allocate_and_run(task, allocation)
            else:
                if self._preemption_enabled:
                    preempted = await self._try_preempt(next_task)
                    if not preempted:
                        break
                else:
                    break
    
    def _find_allocation(self, task: GPUTask) -> Optional[List[tuple]]:
        required_per_gpu = task.gpu_memory_mb
        required_gpus = task.gpu_count
        
        candidates = []
        for gpu_id, gpu in self._gpus.items():
            if gpu.can_allocate(required_per_gpu):
                candidates.append((gpu_id, gpu.available_memory_mb))
        
        candidates.sort(key=lambda x: x[1], reverse=True)
        
        if len(candidates) >= required_gpus:
            return [(c[0], required_per_gpu) for c in candidates[:required_gpus]]
        
        return None
    
    async def _allocate_and_run(self, task: GPUTask, allocation: List[tuple]):
        for gpu_id, memory in allocation:
            gpu = self._gpus[gpu_id]
            gpu.allocate(task, memory)
            task.gpu_ids.append(gpu_id)
        
        task.memory_allocated = sum(m for _, m in allocation)
        task.status = "scheduled"
        task.started_at = time.time()
        
        self._running_tasks[task.task_id] = task
        
        logger.info(
            f"Task scheduled",
            task_id=task.task_id,
            gpu_ids=task.gpu_ids,
            memory_mb=task.memory_allocated
        )
        
        await self._notify_callbacks("scheduled", task)
        
        asyncio.create_task(self._execute_task(task))
    
    async def _execute_task(self, task: GPUTask):
        try:
            task.status = "running"
            await self._notify_callbacks("started", task)
            
            executor = self._task_executors.get(task.metadata.get("task_type", "default"))
            
            if executor:
                try:
                    if asyncio.iscoroutinefunction(executor):
                        result = await executor(task)
                    else:
                        result = executor(task)
                    
                    task.status = "completed"
                    logger.info(f"Task completed", task_id=task.task_id)
                except Exception as e:
                    task.status = "failed"
                    task.error_message = str(e)
                    logger.error(f"Task failed", task_id=task.task_id, error=str(e))
            else:
                await asyncio.sleep(min(task.estimated_duration, 60))
                task.status = "completed"
            
            task.completed_at = time.time()
            
        except asyncio.CancelledError:
            task.status = "preempted"
            task.error_message = "Task was preempted"
            logger.info(f"Task preempted", task_id=task.task_id)
        except Exception as e:
            task.status = "failed"
            task.error_message = str(e)
            logger.error(f"Task execution error", task_id=task.task_id, error=str(e))
        finally:
            await self._release_resources(task)
            self._running_tasks.pop(task.task_id, None)
            self._completed_tasks[task.task_id] = task
            await self._notify_callbacks(task.status, task)
    
    async def _release_resources(self, task: GPUTask):
        for gpu_id in task.gpu_ids:
            if gpu_id in self._gpus:
                self._gpus[gpu_id].release(task.task_id)
    
    async def _try_preempt(self, incoming_task: GPUTask) -> bool:
        incoming_priority = incoming_task.priority
        
        candidates = []
        for task in self._running_tasks.values():
            if not task.preemptible:
                continue
            
            if task.priority > incoming_priority:
                candidates.append(task)
        
        candidates.sort(key=lambda t: (t.priority, t.started_at or 0))
        
        required_memory = incoming_task.gpu_count * incoming_task.gpu_memory_mb
        freed_memory = 0
        tasks_to_preempt = []
        
        for candidate in candidates:
            freed_memory += candidate.memory_allocated
            tasks_to_preempt.append(candidate)
            
            if freed_memory >= required_memory:
                break
        
        if freed_memory < required_memory:
            return False
        
        for task in tasks_to_preempt:
            await self._preempt_task(task, "preemption")
        
        return True
    
    async def _preempt_task(self, task: GPUTask, reason: str):
        task.preempt_count += 1
        task.status = "preempted"
        
        logger.info(
            f"Preempting task",
            task_id=task.task_id,
            reason=reason,
            preempt_count=task.preempt_count
        )
        
        await self._release_resources(task)
        self._running_tasks.pop(task.task_id, None)
        
        if task.preempt_count < 3:
            task.status = "queued"
            task.started_at = None
            task.gpu_ids = []
            self._queue.push(task)
            logger.info(f"Task requeued after preemption", task_id=task.task_id)
        else:
            task.status = "failed"
            task.error_message = f"Preempted too many times ({task.preempt_count})"
            self._completed_tasks[task.task_id] = task
            logger.warning(f"Task failed after multiple preemptions", task_id=task.task_id)
    
    async def _monitoring_loop(self):
        while self._running:
            await asyncio.sleep(10)
            stats = self.get_queue_stats()
            logger.debug(
                f"Scheduler stats",
                queued=stats["queued_tasks"],
                running=stats["running_tasks"],
                completed=stats["completed_tasks"]
            )
    
    def _get_queue_position(self, task_id: str) -> Optional[int]:
        tasks = list(self._queue)
        tasks.sort(key=lambda t: (t.priority, t.queued_at))
        
        for i, task in enumerate(tasks):
            if task.task_id == task_id:
                return i + 1
        return None
    
    def _estimate_wait_time(self, task: GPUTask) -> Optional[float]:
        position = self._get_queue_position(task.task_id)
        if position is None:
            return None
        
        avg_duration = 60.0
        running_durations = [
            time.time() - (t.started_at or time.time())
            for t in self._running_tasks.values()
        ]
        if running_durations:
            avg_duration = sum(running_durations) / len(running_durations)
        
        return position * avg_duration / max(1, self._gpu_count)
    
    async def _notify_callbacks(self, event: str, task: GPUTask):
        for callback in self._task_callbacks.get(event, []):
            try:
                if asyncio.iscoroutinefunction(callback):
                    await callback(event, task)
                else:
                    callback(event, task)
            except Exception as e:
                logger.error(f"Callback error", event=event, error=str(e))
