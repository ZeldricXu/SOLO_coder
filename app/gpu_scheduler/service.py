from typing import Optional, List, Dict, Any, Tuple, Deque
from uuid import UUID
from datetime import datetime, timezone
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, and_, or_, func, update
from collections import deque
import asyncio
import heapq

from app.models import GPUTask, GPUResource, TaskStatus, TaskPriority
from app.schemas import GPUTaskCreate, GPUTaskStatusUpdate
from app.exceptions import NotFoundError, ResourceExhaustedError, ConflictError, ValidationError
from app.logging import get_logger
from app.config import settings

logger = get_logger(__name__)


class GPUResourceManager:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def register_gpu(
        self,
        node_id: str,
        gpu_index: int,
        total_memory_gb: float,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> GPUResource:
        stmt = select(GPUResource).where(
            and_(
                GPUResource.node_id == node_id,
                GPUResource.gpu_index == gpu_index,
            )
        )
        result = await self.db.execute(stmt)
        existing = result.scalar_one_or_none()

        if existing:
            raise ConflictError(f"GPU already registered at node {node_id} index {gpu_index}")

        gpu = GPUResource(
            node_id=node_id,
            gpu_index=gpu_index,
            total_memory_gb=total_memory_gb,
            metadata=metadata or {},
        )
        self.db.add(gpu)
        await self.db.commit()
        await self.db.refresh(gpu)

        logger.info(
            "GPU registered",
            gpu_id=str(gpu.id),
            node_id=node_id,
            gpu_index=gpu_index,
            total_memory_gb=total_memory_gb,
        )
        return gpu

    async def list_gpus(
        self,
        node_id: Optional[str] = None,
        is_healthy: Optional[str] = None,
        only_available: bool = False,
    ) -> List[GPUResource]:
        stmt = select(GPUResource)
        conditions = []

        if node_id:
            conditions.append(GPUResource.node_id == node_id)
        if is_healthy:
            conditions.append(GPUResource.is_healthy == is_healthy)
        if only_available:
            conditions.append(GPUResource.current_task_id.is_(None))

        if conditions:
            stmt = stmt.where(and_(*conditions))

        result = await self.db.execute(stmt)
        return list(result.scalars().all())

    async def get_gpu(self, gpu_id: UUID) -> GPUResource:
        stmt = select(GPUResource).where(GPUResource.id == gpu_id)
        result = await self.db.execute(stmt)
        gpu = result.scalar_one_or_none()

        if not gpu:
            raise NotFoundError(f"GPU resource {gpu_id} not found")

        return gpu

    async def update_gpu_metrics(
        self,
        gpu_id: UUID,
        utilization: float,
        used_memory_gb: float,
        temperature: Optional[float] = None,
        is_healthy: str = "healthy",
    ) -> GPUResource:
        gpu = await self.get_gpu(gpu_id)
        gpu.utilization = utilization
        gpu.used_memory_gb = used_memory_gb
        gpu.temperature = temperature
        gpu.is_healthy = is_healthy

        await self.db.commit()
        await self.db.refresh(gpu)
        return gpu

    async def get_available_gpu(self, required_memory_gb: float) -> Optional[GPUResource]:
        stmt = select(GPUResource).where(
            and_(
                GPUResource.current_task_id.is_(None),
                GPUResource.is_healthy == "healthy",
                (GPUResource.total_memory_gb - GPUResource.used_memory_gb) >= required_memory_gb,
            )
        ).order_by(GPUResource.total_memory_gb.asc())

        result = await self.db.execute(stmt)
        return result.scalars().first()

    async def allocate_gpu(self, gpu_id: UUID, task_id: UUID, memory_gb: float) -> GPUResource:
        gpu = await self.get_gpu(gpu_id)

        if gpu.current_task_id is not None:
            raise ConflictError(f"GPU {gpu_id} is already in use")

        available_memory = gpu.total_memory_gb - gpu.used_memory_gb
        if available_memory < memory_gb:
            raise ResourceExhaustedError(
                f"GPU {gpu_id} has insufficient memory: {available_memory}GB available, {memory_gb}GB required"
            )

        gpu.current_task_id = task_id
        gpu.used_memory_gb += memory_gb
        await self.db.commit()
        await self.db.refresh(gpu)

        logger.info(
            "GPU allocated",
            gpu_id=str(gpu_id),
            task_id=str(task_id),
            memory_gb=memory_gb,
        )
        return gpu

    async def release_gpu(self, gpu_id: UUID, task_id: UUID) -> GPUResource:
        gpu = await self.get_gpu(gpu_id)

        if gpu.current_task_id != task_id:
            raise ConflictError(f"GPU {gpu_id} is not allocated to task {task_id}")

        stmt = select(GPUTask).where(GPUTask.id == task_id)
        result = await self.db.execute(stmt)
        task = result.scalar_one_or_none()

        if task:
            gpu.used_memory_gb = max(0, gpu.used_memory_gb - task.allocated_memory_gb)

        gpu.current_task_id = None
        await self.db.commit()
        await self.db.refresh(gpu)

        logger.info(
            "GPU released",
            gpu_id=str(gpu_id),
            task_id=str(task_id),
        )
        return gpu


class GPUTaskScheduler:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.resource_manager = GPUResourceManager(db)
        self._task_queue: List[Tuple[int, float, UUID]] = []
        self._preemption_enabled = True

    async def submit_task(self, task_in: GPUTaskCreate, user_id: UUID) -> GPUTask:
        total_gpu_memory = await self._get_total_available_memory()

        if task_in.required_memory_gb > total_gpu_memory:
            raise ValidationError(
                f"Required memory {task_in.required_memory_gb}GB exceeds total cluster capacity {total_gpu_memory}GB"
            )

        task = GPUTask(
            name=task_in.name,
            user_id=user_id,
            priority=task_in.priority.value,
            required_memory_gb=task_in.required_memory_gb,
            command=task_in.command,
            container_image=task_in.container_image,
            is_preemptible=task_in.is_preemptible,
            checkpoint_path=task_in.checkpoint_path,
            meta_data=task_in.metadata,
        )

        self.db.add(task)
        await self.db.commit()
        await self.db.refresh(task)

        logger.info(
            "GPU task submitted",
            task_id=str(task.id),
            name=task.name,
            priority=task.priority,
            required_memory_gb=task.required_memory_gb,
        )

        await self._enqueue_task(task)
        return task

    async def _enqueue_task(self, task: GPUTask) -> None:
        task.status = TaskStatus.QUEUED.value
        task.queued_at = datetime.now(timezone.utc)
        await self.db.commit()

        heapq.heappush(
            self._task_queue,
            (-task.priority, task.created_at.timestamp(), task.id),
        )

        asyncio.create_task(self._try_allocate())

    async def _get_total_available_memory(self) -> float:
        stmt = select(
            func.sum(
                GPUResource.total_memory_gb - GPUResource.used_memory_gb
            )
        ).where(GPUResource.is_healthy == "healthy")
        result = await self.db.execute(stmt)
        total = result.scalar_one() or 0.0
        return total

    async def _try_allocate(self) -> None:
        while self._task_queue:
            neg_priority, _, task_id = self._task_queue[0]

            stmt = select(GPUTask).where(GPUTask.id == task_id)
            result = await self.db.execute(stmt)
            task = result.scalar_one_or_none()

            if not task or task.status != TaskStatus.QUEUED.value:
                heapq.heappop(self._task_queue)
                continue

            available_gpu = await self.resource_manager.get_available_gpu(task.required_memory_gb)

            if available_gpu:
                heapq.heappop(self._task_queue)
                await self._allocate_task(task, available_gpu)
            else:
                if self._preemption_enabled and neg_priority < -TaskPriority.MEDIUM.value:
                    preempted = await self._try_preempt(task.required_memory_gb, -neg_priority)
                    if not preempted:
                        break
                else:
                    break

    async def _allocate_task(self, task: GPUTask, gpu: GPUResource) -> None:
        await self.resource_manager.allocate_gpu(gpu.id, task.id, task.required_memory_gb)

        task.gpu_resource_id = gpu.id
        task.allocated_memory_gb = task.required_memory_gb
        task.status = TaskStatus.RUNNING.value
        task.started_at = datetime.now(timezone.utc)

        await self.db.commit()

        logger.info(
            "GPU task started",
            task_id=str(task.id),
            gpu_id=str(gpu.id),
            node_id=gpu.node_id,
            gpu_index=gpu.gpu_index,
        )

    async def _try_preempt(self, required_memory_gb: float, priority: int) -> bool:
        stmt = select(GPUTask).where(
            and_(
                GPUTask.status == TaskStatus.RUNNING.value,
                GPUTask.is_preemptible == True,
                GPUTask.priority < priority,
            )
        ).order_by(GPUTask.priority.asc())

        result = await self.db.execute(stmt)
        preemptible_tasks = list(result.scalars().all())

        memory_to_free = 0.0
        tasks_to_preempt = []

        for task in preemptible_tasks:
            tasks_to_preempt.append(task)
            memory_to_free += task.allocated_memory_gb
            if memory_to_free >= required_memory_gb:
                break

        if memory_to_free < required_memory_gb:
            return False

        for task in tasks_to_preempt:
            await self._preempt_task(task)

        return True

    async def _preempt_task(self, task: GPUTask) -> None:
        if task.gpu_resource_id:
            await self.resource_manager.release_gpu(task.gpu_resource_id, task.id)

        task.status = TaskStatus.PREEMPTED.value
        task.preemption_count += 1
        task.completed_at = datetime.now(timezone.utc)
        if task.started_at:
            task.duration_seconds = (task.completed_at - task.started_at).total_seconds()

        await self.db.commit()

        logger.info(
            "GPU task preempted",
            task_id=str(task.id),
            preemption_count=task.preemption_count,
        )

        await self._enqueue_task(task)

    async def get_task(self, task_id: UUID) -> GPUTask:
        stmt = select(GPUTask).where(GPUTask.id == task_id)
        result = await self.db.execute(stmt)
        task = result.scalar_one_or_none()

        if not task:
            raise NotFoundError(f"GPU task {task_id} not found")

        return task

    async def list_tasks(
        self,
        user_id: Optional[UUID] = None,
        status: Optional[str] = None,
        priority: Optional[int] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[GPUTask], int]:
        stmt = select(GPUTask)
        conditions = []

        if user_id:
            conditions.append(GPUTask.user_id == user_id)
        if status:
            conditions.append(GPUTask.status == status)
        if priority is not None:
            conditions.append(GPUTask.priority == priority)

        if conditions:
            stmt = stmt.where(and_(*conditions))

        count_stmt = (
            select(func.count(GPUTask.id)).where(and_(*conditions))
            if conditions
            else select(func.count(GPUTask.id))
        )
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(GPUTask.created_at.desc())
        result = await self.db.execute(stmt)
        tasks = result.scalars().all()

        return list(tasks), total

    async def update_task_status(self, task_id: UUID, update_in: GPUTaskStatusUpdate) -> GPUTask:
        task = await self.get_task(task_id)

        task.status = update_in.status
        if update_in.progress is not None:
            task.progress = update_in.progress
        if update_in.error_message:
            task.error_message = update_in.error_message
        if update_in.exit_code is not None:
            task.exit_code = update_in.exit_code

        if update_in.status in [TaskStatus.COMPLETED.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value]:
            task.completed_at = datetime.now(timezone.utc)
            if task.started_at:
                task.duration_seconds = (task.completed_at - task.started_at).total_seconds()

            if task.gpu_resource_id:
                await self.resource_manager.release_gpu(task.gpu_resource_id, task_id)

        await self.db.commit()
        await self.db.refresh(task)

        logger.info(
            "GPU task status updated",
            task_id=str(task_id),
            status=update_in.status,
            progress=update_in.progress,
        )

        if update_in.status in [TaskStatus.COMPLETED.value, TaskStatus.FAILED.value]:
            asyncio.create_task(self._try_allocate())

        return task

    async def cancel_task(self, task_id: UUID) -> GPUTask:
        task = await self.get_task(task_id)

        if task.status in [TaskStatus.COMPLETED.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value]:
            raise ConflictError(f"Task {task_id} is already in terminal state")

        if task.status == TaskStatus.QUEUED.value:
            task.status = TaskStatus.CANCELLED.value
            task.completed_at = datetime.now(timezone.utc)
        elif task.status == TaskStatus.RUNNING.value and task.gpu_resource_id:
            await self.resource_manager.release_gpu(task.gpu_resource_id, task_id)
            task.status = TaskStatus.CANCELLED.value
            task.completed_at = datetime.now(timezone.utc)
            if task.started_at:
                task.duration_seconds = (task.completed_at - task.started_at).total_seconds()

        await self.db.commit()
        await self.db.refresh(task)

        logger.info("GPU task cancelled", task_id=str(task_id))
        return task

    async def get_queue_status(self) -> Dict[str, Any]:
        stmt = select(func.count(GPUTask.id)).where(GPUTask.status == TaskStatus.QUEUED.value)
        result = await self.db.execute(stmt)
        queued_count = result.scalar_one()

        stmt = select(func.count(GPUTask.id)).where(GPUTask.status == TaskStatus.RUNNING.value)
        result = await self.db.execute(stmt)
        running_count = result.scalar_one()

        gpus = await self.resource_manager.list_gpus()
        total_memory = sum(g.total_memory_gb for g in gpus)
        used_memory = sum(g.used_memory_gb for g in gpus)

        return {
            "queued_tasks": queued_count,
            "running_tasks": running_count,
            "total_gpus": len(gpus),
            "total_memory_gb": total_memory,
            "used_memory_gb": used_memory,
            "available_memory_gb": total_memory - used_memory,
            "utilization": (used_memory / total_memory * 100) if total_memory > 0 else 0,
        }
