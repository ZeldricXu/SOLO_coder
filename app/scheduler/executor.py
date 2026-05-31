"""
Task executor implementation with async support and callbacks.
"""

import asyncio
from concurrent.futures import ThreadPoolExecutor as SyncThreadPoolExecutor
from datetime import datetime
from typing import Optional

from app.logging import get_logger
from app.models import RunPhase
from app.scheduler.base import TaskExecutor
from app.scheduler.events import (
    EventBus,
    TaskEvent,
    TaskEventType
)
from app.scheduler.models import Task, TaskStatus


class DefaultTaskExecutor(TaskExecutor):
    def __init__(
        self,
        max_workers: int = 4,
        event_bus: Optional[EventBus] = None
    ):
        self._max_workers = max_workers
        self._logger = get_logger("scheduler")
        self._event_bus = event_bus or EventBus()
    
    @property
    def event_bus(self) -> EventBus:
        return self._event_bus
    
    async def execute(self, task: Task) -> Task:
        task.status = TaskStatus.RUNNING
        task.started_at = datetime.utcnow()
        run_instance = task.create_run_instance()
        run_instance.phase = RunPhase.EXECUTING
        run_instance.started_at = datetime.utcnow()
        
        self._event_bus.emit(TaskEvent(
            event_type=TaskEventType.TASK_STARTED,
            task_id=task.task_id,
            task_name=task.name,
            status=TaskStatus.RUNNING
        ))
        
        self._logger.info(
            "Starting task execution",
            task_id=task.task_id,
            task_name=task.name
        )
        
        attempt = 0
        while attempt <= task.retries:
            try:
                if asyncio.iscoroutinefunction(task.func):
                    if task.timeout:
                        result = await asyncio.wait_for(
                            task.func(*task.args, **task.kwargs),
                            timeout=task.timeout
                        )
                    else:
                        result = await task.func(*task.args, **task.kwargs)
                else:
                    loop = asyncio.get_event_loop()
                    with SyncThreadPoolExecutor(max_workers=1) as executor:
                        if task.timeout:
                            result = await asyncio.wait_for(
                                loop.run_in_executor(
                                    executor, task.func, *task.args, **task.kwargs
                                ),
                                timeout=task.timeout
                            )
                        else:
                            result = await loop.run_in_executor(
                                executor, task.func, *task.args, **task.kwargs
                            )
                
                task.result = result
                task.status = TaskStatus.COMPLETED
                task.completed_at = datetime.utcnow()
                run_instance.phase = RunPhase.COMPLETED
                run_instance.progress = 1.0
                run_instance.completed_at = datetime.utcnow()
                
                self._event_bus.emit(TaskEvent(
                    event_type=TaskEventType.TASK_COMPLETED,
                    task_id=task.task_id,
                    task_name=task.name,
                    status=TaskStatus.COMPLETED,
                    result=result
                ))
                
                self._event_bus.invoke_success_callback(task)
                await self._event_bus.invoke_async_success_callback(task)
                
                self._logger.info(
                    "Task completed successfully",
                    task_id=task.task_id,
                    duration=(task.completed_at - task.started_at).total_seconds()
                )
                return task
                
            except asyncio.TimeoutError:
                task.error = f"Task timed out after {task.timeout}s"
                self._event_bus.emit(TaskEvent(
                    event_type=TaskEventType.TASK_TIMEOUT,
                    task_id=task.task_id,
                    task_name=task.name,
                    attempt=attempt + 1,
                    error=task.error
                ))
                self._logger.warning(
                    "Task timeout",
                    task_id=task.task_id,
                    attempt=attempt + 1
                )
                
            except Exception as e:
                task.error = str(e)
                self._event_bus.emit(TaskEvent(
                    event_type=TaskEventType.TASK_FAILED if attempt >= task.retries else TaskEventType.TASK_RETRY,
                    task_id=task.task_id,
                    task_name=task.name,
                    attempt=attempt + 1,
                    error=task.error
                ))
                if attempt < task.retries:
                    self._event_bus.invoke_retry_callback(task, attempt + 1)
                    await self._event_bus.invoke_async_retry_callback(task, attempt + 1)
                self._logger.error(
                    "Task execution failed",
                    task_id=task.task_id,
                    error=str(e),
                    attempt=attempt + 1
                )
            
            attempt += 1
            if attempt <= task.retries:
                await asyncio.sleep(task.retry_delay)
        
        task.status = TaskStatus.FAILED
        task.completed_at = datetime.utcnow()
        run_instance.phase = RunPhase.FAILED
        run_instance.error_detail = task.error
        run_instance.completed_at = datetime.utcnow()
        
        self._event_bus.emit(TaskEvent(
            event_type=TaskEventType.TASK_FAILED,
            task_id=task.task_id,
            task_name=task.name,
            status=TaskStatus.FAILED,
            error=task.error
        ))
        
        self._event_bus.invoke_failure_callback(task, task.error or "")
        await self._event_bus.invoke_async_failure_callback(task, task.error or "")
        
        self._logger.error(
            "Task failed permanently",
            task_id=task.task_id,
            error=task.error
        )
        return task


TaskExecutorImpl = DefaultTaskExecutor
