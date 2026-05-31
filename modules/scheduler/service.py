from typing import Any, Dict, List, Optional
from sqlalchemy import select, desc
from sqlalchemy.ext.asyncio import AsyncSession

from core import BaseRepository, NotFoundError
from models import generate_uuid, utc_now
from .models import ScheduledTask, TaskExecution
from .schemas import ScheduledTaskCreate, ScheduledTaskUpdate
from .engine import scheduler_engine, TaskStatus


class ScheduledTaskRepository(BaseRepository):
    async def create(self, data: Dict[str, Any]) -> ScheduledTask:
        task = ScheduledTask(**data)
        self.db.add(task)
        await self.db.flush()
        return task

    async def get_by_id(self, task_id: str) -> Optional[ScheduledTask]:
        result = await self.db.execute(
            select(ScheduledTask).where(ScheduledTask.id == task_id)
        )
        return result.scalar_one_or_none()

    async def list(
        self,
        skip: int = 0,
        limit: int = 100,
        enabled: Optional[bool] = None,
        task_type: Optional[str] = None,
    ) -> List[ScheduledTask]:
        query = select(ScheduledTask)
        if enabled is not None:
            query = query.where(ScheduledTask.enabled == enabled)
        if task_type:
            query = query.where(ScheduledTask.task_type == task_type)
        query = query.offset(skip).limit(limit).order_by(desc(ScheduledTask.created_at))
        result = await self.db.execute(query)
        return list(result.scalars().all())

    async def update(
        self, task: ScheduledTask, data: Dict[str, Any]
    ) -> ScheduledTask:
        for key, value in data.items():
            if value is not None:
                setattr(task, key, value)
        await self.db.flush()
        return task

    async def delete(self, task: ScheduledTask) -> None:
        await self.db.delete(task)


class TaskExecutionRepository(BaseRepository):
    async def create(self, data: Dict[str, Any]) -> TaskExecution:
        execution = TaskExecution(**data)
        self.db.add(execution)
        await self.db.flush()
        return execution

    async def list_by_task_id(
        self, task_id: str, skip: int = 0, limit: int = 50
    ) -> List[TaskExecution]:
        result = await self.db.execute(
            select(TaskExecution)
            .where(TaskExecution.task_id == task_id)
            .order_by(desc(TaskExecution.started_at))
            .offset(skip)
            .limit(limit)
        )
        return list(result.scalars().all())


class SchedulerService:
    def __init__(self, db: AsyncSession):
        self.task_repo = ScheduledTaskRepository(db)
        self.execution_repo = TaskExecutionRepository(db)
        self.engine = scheduler_engine

    async def create_task(self, data: ScheduledTaskCreate) -> ScheduledTask:
        task_dict = data.model_dump()
        task_dict["type"] = "scheduled_task"
        task_dict["status"] = TaskStatus.PENDING

        task = await self.task_repo.create(task_dict)
        self.engine.add_task(task.to_dict())

        return task

    async def get_task(self, task_id: str) -> ScheduledTask:
        task = await self.task_repo.get_by_id(task_id)
        if not task:
            raise NotFoundError("ScheduledTask", task_id)
        return task

    async def list_tasks(
        self,
        page: int = 1,
        page_size: int = 20,
        enabled: Optional[bool] = None,
        task_type: Optional[str] = None,
    ) -> List[ScheduledTask]:
        skip = (page - 1) * page_size
        return await self.task_repo.list(skip, page_size, enabled, task_type)

    async def update_task(
        self, task_id: str, data: ScheduledTaskUpdate
    ) -> ScheduledTask:
        task = await self.get_task(task_id)
        update_dict = data.model_dump(exclude_unset=True)
        updated_task = await self.task_repo.update(task, update_dict)
        self.engine.add_task(updated_task.to_dict())
        return updated_task

    async def delete_task(self, task_id: str) -> None:
        task = await self.get_task(task_id)
        await self.task_repo.delete(task)
        self.engine.remove_task(task_id)

    async def trigger_task(self, task_id: str) -> bool:
        task = await self.get_task(task_id)
        result = await self.engine.trigger_task(task_id)

        if result:
            task.status = TaskStatus.RUNNING
            await self.db.flush()

            await self.execution_repo.create({
                "task_id": task_id,
                "execution_id": generate_uuid(),
                "status": TaskStatus.RUNNING,
                "started_at": utc_now(),
            })

        return result

    async def pause_task(self, task_id: str) -> ScheduledTask:
        task = await self.get_task(task_id)
        task.enabled = False
        task.status = TaskStatus.PAUSED
        await self.db.flush()
        self.engine.remove_task(task_id)
        return task

    async def resume_task(self, task_id: str) -> ScheduledTask:
        task = await self.get_task(task_id)
        task.enabled = True
        task.status = TaskStatus.PENDING
        await self.db.flush()
        self.engine.add_task(task.to_dict())
        return task

    async def get_task_executions(
        self, task_id: str, page: int = 1, page_size: int = 50
    ) -> List[TaskExecution]:
        await self.get_task(task_id)
        skip = (page - 1) * page_size
        return await self.execution_repo.list_by_task_id(task_id, skip, page_size)

    async def start_scheduler(self) -> None:
        tasks = await self.task_repo.list(limit=1000, enabled=True)
        for task in tasks:
            self.engine.add_task(task.to_dict())
        await self.engine.start()

    async def stop_scheduler(self) -> None:
        await self.engine.stop()
