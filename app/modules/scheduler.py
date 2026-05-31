import asyncio
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional
from sqlalchemy import select, and_
from sqlalchemy.ext.asyncio import AsyncSession
from app.models import ScheduledTask
from app.logger import logger


class TaskDependencyError(Exception):
    pass


class TaskExecutor:
    def __init__(self):
        self._handlers: Dict[str, Callable] = {}
    
    def register_handler(self, task_type: str, handler: Callable):
        self._handlers[task_type] = handler
        logger.info("Registered task handler", task_type=task_type)
    
    async def execute_task(self, task_type: str, payload: Dict[str, Any]) -> Any:
        if task_type not in self._handlers:
            raise ValueError(f"No handler registered for task type: {task_type}")
        
        handler = self._handlers[task_type]
        if asyncio.iscoroutinefunction(handler):
            return await handler(payload)
        else:
            return handler(payload)


executor = TaskExecutor()


class TaskScheduler:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.executor = executor
    
    async def create_task(
        self,
        name: str,
        task_type: str,
        payload: Dict[str, Any] = None,
        dependencies: List[str] = None,
        priority: int = 0,
        scheduled_at: datetime = None
    ) -> ScheduledTask:
        if dependencies:
            await self._validate_dependencies(dependencies)
        
        task = ScheduledTask(
            name=name,
            task_type=task_type,
            payload=payload or {},
            dependencies=dependencies or [],
            status="pending",
            priority=priority,
            scheduled_at=scheduled_at
        )
        self.db.add(task)
        await self.db.flush()
        
        logger.info("Created scheduled task", task_id=task.id, name=name, task_type=task_type)
        return task
    
    async def _validate_dependencies(self, dependency_ids: List[str]):
        for dep_id in dependency_ids:
            stmt = select(ScheduledTask).where(ScheduledTask.id == dep_id)
            result = await self.db.execute(stmt)
            if not result.scalar_one_or_none():
                raise TaskDependencyError(f"Dependency task not found: {dep_id}")
    
    async def check_dependencies(self, task: ScheduledTask) -> bool:
        if not task.dependencies:
            return True
        
        for dep_id in task.dependencies:
            stmt = select(ScheduledTask).where(ScheduledTask.id == dep_id)
            result = await self.db.execute(stmt)
            dep = result.scalar_one_or_none()
            if not dep:
                return False
            if dep.status != "completed":
                return False
        
        return True
    
    async def get_next_task(self) -> Optional[ScheduledTask]:
        stmt = select(ScheduledTask).where(
            and_(
                ScheduledTask.status == "pending",
                (ScheduledTask.scheduled_at == None) | (ScheduledTask.scheduled_at <= datetime.utcnow())
            )
        ).order_by(ScheduledTask.priority.desc(), ScheduledTask.created_at.asc()).limit(1)
        
        result = await self.db.execute(stmt)
        task = result.scalar_one_or_none()
        
        if task and await self.check_dependencies(task):
            return task
        
        return None
    
    async def execute_task(self, task_id: str) -> Dict[str, Any]:
        stmt = select(ScheduledTask).where(ScheduledTask.id == task_id)
        result = await self.db.execute(stmt)
        task = result.scalar_one_or_none()
        
        if not task:
            raise ValueError(f"Task not found: {task_id}")
        
        if not await self.check_dependencies(task):
            raise TaskDependencyError(f"Task dependencies not satisfied: {task_id}")
        
        task.status = "running"
        task.started_at = datetime.utcnow()
        await self.db.flush()
        
        try:
            result_data = await self.executor.execute_task(task.task_type, task.payload)
            task.status = "completed"
            task.result = {"data": result_data}
            task.completed_at = datetime.utcnow()
            await self.db.flush()
            
            logger.info("Task completed successfully", task_id=task_id)
            return {"status": "completed", "result": result_data}
        
        except Exception as e:
            task.status = "failed"
            task.error_message = str(e)
            task.completed_at = datetime.utcnow()
            await self.db.flush()
            
            logger.error("Task execution failed", task_id=task_id, error=str(e))
            return {"status": "failed", "error": str(e)}
    
    async def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        stmt = select(ScheduledTask).where(ScheduledTask.id == task_id)
        result = await self.db.execute(stmt)
        task = result.scalar_one_or_none()
        
        if not task:
            return None
        
        return {
            "task_id": task.id,
            "name": task.name,
            "status": task.status,
            "priority": task.priority,
            "result": task.result,
            "error_message": task.error_message,
            "started_at": task.started_at.isoformat() if task.started_at else None,
            "completed_at": task.completed_at.isoformat() if task.completed_at else None
        }
    
    async def list_tasks(self, status: str = None, task_type: str = None, limit: int = 100) -> List[Dict[str, Any]]:
        conditions = []
        if status:
            conditions.append(ScheduledTask.status == status)
        if task_type:
            conditions.append(ScheduledTask.task_type == task_type)
        
        stmt = select(ScheduledTask).where(
            and_(*conditions) if conditions else True
        ).order_by(ScheduledTask.priority.desc(), ScheduledTask.created_at.desc()).limit(limit)
        
        result = await self.db.execute(stmt)
        tasks = result.scalars().all()
        
        return [
            {
                "task_id": t.id,
                "name": t.name,
                "task_type": t.task_type,
                "status": t.status,
                "priority": t.priority,
                "created_at": t.created_at.isoformat() if t.created_at else None
            }
            for t in tasks
        ]
    
    async def cancel_task(self, task_id: str) -> bool:
        stmt = select(ScheduledTask).where(ScheduledTask.id == task_id)
        result = await self.db.execute(stmt)
        task = result.scalar_one_or_none()
        
        if not task:
            return False
        
        if task.status in ["pending"]:
            task.status = "cancelled"
            await self.db.flush()
            logger.info("Task cancelled", task_id=task_id)
            return True
        
        return False
    
    async def build_dependency_graph(self, root_task_id: str) -> Dict[str, Any]:
        visited = set()
        graph = {"nodes": [], "edges": []}
        
        async def traverse(task_id: str):
            if task_id in visited:
                return
            visited.add(task_id)
            
            stmt = select(ScheduledTask).where(ScheduledTask.id == task_id)
            result = await self.db.execute(stmt)
            task = result.scalar_one_or_none()
            
            if task:
                graph["nodes"].append({
                    "id": task.id,
                    "name": task.name,
                    "status": task.status
                })
                
                for dep_id in task.dependencies:
                    graph["edges"].append({
                        "from": dep_id,
                        "to": task.id
                    })
                    await traverse(dep_id)
        
        await traverse(root_task_id)
        return graph
