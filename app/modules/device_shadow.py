import asyncio
from datetime import datetime
from typing import Any, Dict, List, Optional, Callable
from collections import deque
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.models import DeviceShadow
from app.config import settings
from app.logger import logger


class ShadowSyncTask:
    def __init__(
        self,
        task_id: str,
        device_id: str,
        operation: str,
        state: Dict[str, Any] = None,
        priority: int = 0
    ):
        self.task_id = task_id
        self.device_id = device_id
        self.operation = operation
        self.state = state or {}
        self.priority = priority
        self.status = "queued"
        self.result = None
        self.error = None
        self.retry_count = 0
        self.created_at = datetime.utcnow()
        self.started_at = None
        self.completed_at = None
        self._event = asyncio.Event()
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "device_id": self.device_id,
            "operation": self.operation,
            "status": self.status,
            "priority": self.priority,
            "retry_count": self.retry_count,
            "created_at": self.created_at.isoformat(),
            "started_at": self.started_at.isoformat() if self.started_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "result": self.result,
            "error": self.error
        }


class AsyncShadowExecutor:
    def __init__(
        self,
        max_concurrent: int = None,
        queue_timeout: int = None,
        retry_attempts: int = None,
        retry_delay: float = None
    ):
        self.max_concurrent = max_concurrent or settings.DEVICE_SHADOW_MAX_CONCURRENT
        self.queue_timeout = queue_timeout or settings.DEVICE_SHADOW_QUEUE_TIMEOUT
        self.retry_attempts = retry_attempts or settings.DEVICE_SHADOW_RETRY_ATTEMPTS
        self.retry_delay = retry_delay or settings.DEVICE_SHADOW_RETRY_DELAY
        
        self._semaphore = asyncio.Semaphore(self.max_concurrent)
        self._task_queue: List[ShadowSyncTask] = []
        self._completed_tasks: Dict[str, ShadowSyncTask] = {}
        self._processing_tasks: Dict[str, ShadowSyncTask] = {}
        self._device_locks: Dict[str, asyncio.Lock] = {}
        self._background_task: Optional[asyncio.Task] = None
        self._is_running = False
        self._metrics = {
            "total_submitted": 0,
            "total_completed": 0,
            "total_failed": 0,
            "total_retried": 0,
            "average_latency_ms": 0.0,
            "queue_size": 0
        }
    
    def start(self):
        if not self._is_running:
            self._is_running = True
            self._background_task = asyncio.create_task(self._process_queue())
            logger.info("AsyncShadowExecutor started", max_concurrent=self.max_concurrent)
    
    def stop(self):
        self._is_running = False
        if self._background_task:
            self._background_task.cancel()
            self._background_task = None
        logger.info("AsyncShadowExecutor stopped")
    
    def submit(
        self,
        device_id: str,
        operation: str,
        state: Dict[str, Any] = None,
        priority: int = 0
    ) -> ShadowSyncTask:
        import uuid
        task_id = str(uuid.uuid4())
        task = ShadowSyncTask(task_id, device_id, operation, state, priority)
        
        self._task_queue.append(task)
        self._task_queue.sort(key=lambda t: (-t.priority, t.created_at))
        self._metrics["total_submitted"] += 1
        self._metrics["queue_size"] = len(self._task_queue)
        
        logger.debug(
            "Shadow task submitted",
            task_id=task_id,
            device_id=device_id,
            operation=operation,
            priority=priority
        )
        
        return task
    
    async def wait_for_task(self, task_id: str, timeout: float = None) -> ShadowSyncTask:
        if task_id in self._completed_tasks:
            return self._completed_tasks[task_id]
        
        if task_id in self._processing_tasks:
            task = self._processing_tasks[task_id]
            await asyncio.wait_for(task._event.wait(), timeout=timeout if timeout else self.queue_timeout)
            return task
        
        raise ValueError(f"Task not found: {task_id}")
    
    def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        if task_id in self._completed_tasks:
            return self._completed_tasks[task_id].to_dict()
        if task_id in self._processing_tasks:
            return self._processing_tasks[task_id].to_dict()
        
        for task in self._task_queue:
            if task.task_id == task_id:
                return task.to_dict()
        
        return None
    
    def cancel_task(self, task_id: str) -> bool:
        for i, task in enumerate(self._task_queue):
            if task.task_id == task_id:
                self._task_queue.pop(i)
                task.status = "cancelled"
                self._metrics["queue_size"] = len(self._task_queue)
                logger.info("Shadow task cancelled", task_id=task_id)
                return True
        
        return False
    
    def get_metrics(self) -> Dict[str, Any]:
        return {
            **self._metrics,
            "current_queue_size": len(self._task_queue),
            "processing_count": len(self._processing_tasks),
            "completed_count": len(self._completed_tasks),
            "max_concurrent": self.max_concurrent,
            "is_running": self._is_running
        }
    
    def get_queued_tasks(self, limit: int = 100) -> List[Dict[str, Any]]:
        return [task.to_dict() for task in self._task_queue[:limit]]
    
    async def _process_queue(self):
        while self._is_running:
            try:
                if self._task_queue:
                    task = self._task_queue.pop(0)
                    self._metrics["queue_size"] = len(self._task_queue)
                    
                    if task.device_id not in self._device_locks:
                        self._device_locks[task.device_id] = asyncio.Lock()
                    
                    async with self._semaphore:
                        asyncio.create_task(self._execute_task(task))
                
                await asyncio.sleep(0.01)
            
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Queue processing error", error=str(e))
                await asyncio.sleep(1)
    
    async def _execute_task(self, task: ShadowSyncTask):
        task.started_at = datetime.utcnow()
        task.status = "processing"
        self._processing_tasks[task.task_id] = task
        
        device_lock = self._device_locks.get(task.device_id)
        
        async with device_lock if device_lock else asyncio.Lock():
            try:
                for attempt in range(self.retry_attempts):
                    task.retry_count = attempt
                    
                    try:
                        result = await self._handler(task)
                        task.result = result
                        task.status = "completed"
                        self._metrics["total_completed"] += 1
                        
                        latency = (datetime.utcnow() - task.created_at).total_seconds() * 1000
                        self._update_latency(latency)
                        
                        logger.info(
                            "Shadow task completed",
                            task_id=task.task_id,
                            device_id=task.device_id,
                            operation=task.operation,
                            attempts=attempt + 1,
                            latency_ms=round(latency, 2)
                        )
                        break
                    
                    except Exception as e:
                        task.error = str(e)
                        
                        if attempt < self.retry_attempts - 1:
                            self._metrics["total_retried"] += 1
                            logger.warning(
                                "Shadow task retry",
                                task_id=task.task_id,
                                device_id=task.device_id,
                                attempt=attempt + 1,
                                max_attempts=self.retry_attempts,
                                error=str(e)
                            )
                            await asyncio.sleep(self.retry_delay * (attempt + 1))
                        else:
                            task.status = "failed"
                            self._metrics["total_failed"] += 1
                            logger.error(
                                "Shadow task failed",
                                task_id=task.task_id,
                                device_id=task.device_id,
                                operation=task.operation,
                                error=str(e)
                            )
            
            finally:
                task.completed_at = datetime.utcnow()
                self._completed_tasks[task.task_id] = task
                if task.task_id in self._processing_tasks:
                    del self._processing_tasks[task.task_id]
                task._event.set()
                
                if len(self._completed_tasks) > 10000:
                    old_keys = list(self._completed_tasks.keys())[:-1000]
                    for key in old_keys:
                        del self._completed_tasks[key]
    
    def _update_latency(self, latency_ms: float):
        current_avg = self._metrics["average_latency_ms"]
        total = self._metrics["total_completed"]
        if total <= 1:
            self._metrics["average_latency_ms"] = latency_ms
        else:
            self._metrics["average_latency_ms"] = (
                current_avg * (total - 1) + latency_ms
            ) / total
    
    async def _handler(self, task: ShadowSyncTask) -> Dict[str, Any]:
        raise NotImplementedError("Handler must be set by DeviceShadowManager")


class DeviceShadowManager:
    def __init__(self, db: AsyncSession):
        self.db = db
        self._executor: Optional[AsyncShadowExecutor] = None
    
    def _get_executor(self) -> AsyncShadowExecutor:
        if self._executor is None:
            self._executor = AsyncShadowExecutor()
            self._executor.start()
            self._executor._handler = self._execute_operation
        return self._executor
    
    async def _execute_operation(self, task: ShadowSyncTask) -> Dict[str, Any]:
        if task.operation == "update_desired":
            shadow = await self._sync_update_desired(task.device_id, task.state)
            return self._shadow_to_dict(shadow)
        elif task.operation == "update_reported":
            shadow = await self._sync_update_reported(task.device_id, task.state)
            return self._shadow_to_dict(shadow)
        elif task.operation == "sync":
            return await self._sync_shadow_sync(task.device_id)
        elif task.operation == "delete":
            success = await self._sync_delete(task.device_id)
            return {"deleted": success}
        else:
            raise ValueError(f"Unknown operation: {task.operation}")
    
    async def submit_async_operation(
        self,
        device_id: str,
        operation: str,
        state: Dict[str, Any] = None,
        priority: int = 0
    ) -> Dict[str, Any]:
        executor = self._get_executor()
        task = executor.submit(device_id, operation, state, priority)
        return task.to_dict()
    
    async def wait_for_operation(self, task_id: str, timeout: float = None) -> Dict[str, Any]:
        executor = self._get_executor()
        task = await executor.wait_for_task(task_id, timeout)
        return task.to_dict()
    
    def get_operation_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        executor = self._get_executor()
        return executor.get_task_status(task_id)
    
    def cancel_operation(self, task_id: str) -> bool:
        executor = self._get_executor()
        return executor.cancel_task(task_id)
    
    def get_executor_metrics(self) -> Dict[str, Any]:
        executor = self._get_executor()
        return executor.get_metrics()
    
    def list_queued_operations(self, limit: int = 100) -> List[Dict[str, Any]]:
        executor = self._get_executor()
        return executor.get_queued_tasks(limit)
    
    async def get_or_create_shadow(self, device_id: str) -> DeviceShadow:
        stmt = select(DeviceShadow).where(DeviceShadow.device_id == device_id)
        result = await self.db.execute(stmt)
        shadow = result.scalar_one_or_none()
        
        if not shadow:
            shadow = DeviceShadow(
                device_id=device_id,
                desired={},
                reported={},
                delta={},
                version=1
            )
            self.db.add(shadow)
            await self.db.flush()
            logger.info("Created device shadow", device_id=device_id)
        
        return shadow
    
    async def update_desired(self, device_id: str, desired_state: Dict[str, Any], async_mode: bool = False, priority: int = 0) -> Dict[str, Any]:
        if async_mode:
            return await self.submit_async_operation(device_id, "update_desired", desired_state, priority)
        
        shadow = await self._sync_update_desired(device_id, desired_state)
        return self._shadow_to_dict(shadow)
    
    async def _sync_update_desired(self, device_id: str, desired_state: Dict[str, Any]) -> DeviceShadow:
        shadow = await self.get_or_create_shadow(device_id)
        
        shadow.desired = {**shadow.desired, **desired_state}
        shadow.version += 1
        shadow.delta = self._calculate_delta(shadow.desired, shadow.reported)
        shadow.last_sync_at = datetime.utcnow()
        
        await self.db.flush()
        logger.info("Updated desired state", device_id=device_id, version=shadow.version)
        return shadow
    
    async def update_reported(self, device_id: str, reported_state: Dict[str, Any], async_mode: bool = False, priority: int = 0) -> Dict[str, Any]:
        if async_mode:
            return await self.submit_async_operation(device_id, "update_reported", reported_state, priority)
        
        shadow = await self._sync_update_reported(device_id, reported_state)
        return self._shadow_to_dict(shadow)
    
    async def _sync_update_reported(self, device_id: str, reported_state: Dict[str, Any]) -> DeviceShadow:
        shadow = await self.get_or_create_shadow(device_id)
        
        shadow.reported = {**shadow.reported, **reported_state}
        shadow.version += 1
        shadow.delta = self._calculate_delta(shadow.desired, shadow.reported)
        shadow.last_sync_at = datetime.utcnow()
        
        await self.db.flush()
        logger.info("Updated reported state", device_id=device_id, version=shadow.version)
        return shadow
    
    async def sync_shadow(self, device_id: str, async_mode: bool = False, priority: int = 0) -> Dict[str, Any]:
        if async_mode:
            return await self.submit_async_operation(device_id, "sync", priority=priority)
        
        return await self._sync_shadow_sync(device_id)
    
    async def _sync_shadow_sync(self, device_id: str) -> Dict[str, Any]:
        shadow = await self.get_or_create_shadow(device_id)
        
        sync_result = {
            "device_id": device_id,
            "version": shadow.version,
            "desired": shadow.desired,
            "reported": shadow.reported,
            "delta": shadow.delta,
            "needs_sync": len(shadow.delta) > 0
        }
        
        logger.info("Synced device shadow", device_id=device_id, needs_sync=sync_result["needs_sync"])
        return sync_result
    
    async def delete_shadow(self, device_id: str, async_mode: bool = False, priority: int = 0) -> Dict[str, Any]:
        if async_mode:
            return await self.submit_async_operation(device_id, "delete", priority=priority)
        
        success = await self._sync_delete(device_id)
        return {"deleted": success}
    
    async def _sync_delete(self, device_id: str) -> bool:
        stmt = select(DeviceShadow).where(DeviceShadow.device_id == device_id)
        result = await self.db.execute(stmt)
        shadow = result.scalar_one_or_none()
        
        if shadow:
            await self.db.delete(shadow)
            await self.db.flush()
            logger.info("Deleted device shadow", device_id=device_id)
            return True
        
        return False
    
    async def list_shadows(self, limit: int = 100, offset: int = 0) -> list:
        stmt = select(DeviceShadow).order_by(DeviceShadow.updated_at.desc()).offset(offset).limit(limit)
        result = await self.db.execute(stmt)
        shadows = result.scalars().all()
        return [self._shadow_to_dict(s) for s in shadows]
    
    async def batch_update_async(
        self,
        operations: List[Dict[str, Any]],
        priority: int = 0
    ) -> List[Dict[str, Any]]:
        executor = self._get_executor()
        tasks = []
        
        for op in operations:
            device_id = op.get("device_id")
            operation = op.get("operation")
            state = op.get("state", {})
            task = executor.submit(device_id, operation, state, priority)
            tasks.append(task)
        
        return [task.to_dict() for task in tasks]
    
    def _calculate_delta(self, desired: Dict[str, Any], reported: Dict[str, Any]) -> Dict[str, Any]:
        delta = {}
        
        all_keys = set(desired.keys()) | set(reported.keys())
        for key in all_keys:
            if key in desired and key in reported:
                if desired[key] != reported[key]:
                    delta[key] = {
                        "desired": desired[key],
                        "reported": reported[key]
                    }
            elif key in desired:
                delta[key] = {
                    "desired": desired[key],
                    "reported": None
                }
        
        return delta
    
    def _shadow_to_dict(self, shadow: DeviceShadow) -> Dict[str, Any]:
        return {
            "device_id": shadow.device_id,
            "desired": shadow.desired,
            "reported": shadow.reported,
            "delta": shadow.delta,
            "version": shadow.version,
            "last_sync_at": shadow.last_sync_at.isoformat() if shadow.last_sync_at else None,
            "updated_at": shadow.updated_at.isoformat() if shadow.updated_at else None
        }
