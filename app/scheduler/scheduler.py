"""
Workflow scheduler implementation with async support.
"""

import asyncio
from datetime import datetime, timedelta
from typing import Dict, List, Optional

from app.logging import get_logger
from app.scheduler.base import TaskExecutor, WorkflowScheduler
from app.scheduler.events import (
    EventBus,
    TaskEvent,
    TaskEventType,
    TaskFuture
)
from app.scheduler.models import Schedule, Task, TaskStatus
from app.scheduler.registry import TaskRegistry
from app.scheduler.resolver import DependencyResolver


class DefaultWorkflowScheduler(WorkflowScheduler):
    def __init__(
        self,
        max_workers: int = 4,
        executor: Optional[TaskExecutor] = None,
        event_bus: Optional[EventBus] = None
    ):
        self._registry = TaskRegistry()
        self._event_bus = event_bus or EventBus()
        self._executor = executor or self._create_default_executor(max_workers, self._event_bus)
        self._schedules: Dict[str, Schedule] = {}
        self._task_futures: Dict[str, TaskFuture] = {}
        self._logger = get_logger("workflow_scheduler")
        self._running = False
        self._schedule_task: Optional[asyncio.Task] = None
    
    def _create_default_executor(
        self,
        max_workers: int,
        event_bus: EventBus
    ) -> TaskExecutor:
        from app.scheduler.executor import DefaultTaskExecutor
        return DefaultTaskExecutor(max_workers, event_bus)
    
    @property
    def event_bus(self) -> EventBus:
        return self._event_bus
    
    def add_task(self, task: Task):
        self._registry.register(task)
        self._task_futures[task.task_id] = TaskFuture(task.task_id)
        
        self._event_bus.emit(TaskEvent(
            event_type=TaskEventType.TASK_SUBMITTED,
            task_id=task.task_id,
            task_name=task.name,
            status=TaskStatus.PENDING
        ))
    
    def add_schedule(self, schedule: Schedule):
        self._schedules[schedule.schedule_id] = schedule
        self._calculate_next_run(schedule)
    
    def _calculate_next_run(self, schedule: Schedule):
        now = datetime.utcnow()
        
        if schedule.schedule_type == "once" or schedule.schedule_type.value == "once":
            schedule.next_run = schedule.start_time or now
            
        elif schedule.schedule_type == "interval" or schedule.schedule_type.value == "interval":
            if schedule.last_run and schedule.interval_seconds:
                schedule.next_run = schedule.last_run + timedelta(
                    seconds=schedule.interval_seconds
                )
            else:
                schedule.next_run = schedule.start_time or now
                
        elif schedule.schedule_type == "cron" or schedule.schedule_type.value == "cron":
            schedule.next_run = schedule.start_time or now + timedelta(minutes=1)
    
    def _parse_cron(self, expr: str, base_time: datetime) -> datetime:
        parts = expr.split()
        if len(parts) != 5:
            return base_time + timedelta(minutes=1)
        
        return base_time + timedelta(minutes=1)
    
    def submit_task_async(self, task_id: str) -> Optional[TaskFuture]:
        task = self._registry.get(task_id)
        if not task:
            return None
        
        future = self._task_futures.get(task_id)
        if future is None:
            future = TaskFuture(task_id)
            self._task_futures[task_id] = future
        
        if task.status == TaskStatus.PENDING:
            asyncio.create_task(self._execute_and_complete(task))
        
        return future
    
    async def _execute_and_complete(self, task: Task):
        await self._executor.execute(task)
        
        future = self._task_futures.get(task.task_id)
        if future:
            if task.status == TaskStatus.COMPLETED:
                future.set_result(task.result)
            else:
                future.set_error(task.error or "Unknown error")
    
    def submit_workflow_async(self) -> TaskFuture:
        workflow_future = TaskFuture("workflow")
        
        async def run_and_complete():
            try:
                result = await self.run_workflow()
                workflow_future.set_result(result)
            except Exception as e:
                workflow_future.set_error(str(e))
        
        asyncio.create_task(run_and_complete())
        return workflow_future
    
    async def run_workflow(self) -> Dict[str, Task]:
        self._event_bus.emit(TaskEvent(
            event_type=TaskEventType.WORKFLOW_STARTED,
            task_id="workflow",
            status=TaskStatus.RUNNING
        ))
        
        resolver = DependencyResolver(self._registry.tasks)
        
        if resolver.check_circular_dependency():
            self._logger.error("Circular dependency detected in workflow")
            self._event_bus.emit(TaskEvent(
                event_type=TaskEventType.WORKFLOW_FAILED,
                task_id="workflow",
                status=TaskStatus.FAILED,
                error="Circular dependency detected"
            ))
            raise ValueError("Circular dependency detected")
        
        ready_tasks = resolver.get_ready_tasks()
        
        while ready_tasks:
            tasks_to_run = [
                self._registry.get(tid) for tid in ready_tasks
                if self._registry.get(tid)
            ]
            
            coroutines = [self._executor.execute(task) for task in tasks_to_run]
            await asyncio.gather(*coroutines)
            
            for task in tasks_to_run:
                future = self._task_futures.get(task.task_id)
                if future:
                    if task.status == TaskStatus.COMPLETED:
                        future.set_result(task.result)
                    else:
                        future.set_error(task.error or "Unknown error")
                
                if task.status != TaskStatus.COMPLETED:
                    self._logger.warning(
                        "Task failed, stopping workflow",
                        task_id=task.task_id
                    )
                    self._event_bus.emit(TaskEvent(
                        event_type=TaskEventType.WORKFLOW_FAILED,
                        task_id="workflow",
                        status=TaskStatus.FAILED,
                        error=f"Task {task.task_id} failed"
                    ))
                    return self._registry.tasks
            
            ready_tasks = resolver.get_ready_tasks()
        
        self._event_bus.emit(TaskEvent(
            event_type=TaskEventType.WORKFLOW_COMPLETED,
            task_id="workflow",
            status=TaskStatus.COMPLETED
        ))
        
        return self._registry.tasks
    
    async def _schedule_loop(self):
        while self._running:
            now = datetime.utcnow()
            
            for schedule in self._schedules.values():
                if not schedule.enabled:
                    continue
                
                if schedule.end_time and now > schedule.end_time:
                    schedule.enabled = False
                    continue
                
                if schedule.next_run and now >= schedule.next_run:
                    task = self._registry.get(schedule.task_id)
                    if task:
                        self._logger.info(
                            "Triggering scheduled task",
                            task_id=task.task_id,
                            schedule_id=schedule.schedule_id
                        )
                        self._event_bus.emit(TaskEvent(
                            event_type=TaskEventType.SCHEDULE_TRIGGERED,
                            task_id=task.task_id,
                            task_name=task.name,
                            metadata={"schedule_id": schedule.schedule_id}
                        ))
                        asyncio.create_task(self._execute_and_complete(task))
                    
                    schedule.last_run = now
                    self._calculate_next_run(schedule)
            
            await asyncio.sleep(1.0)
    
    def start(self):
        self._running = True
        self._schedule_task = asyncio.create_task(self._schedule_loop())
        self._logger.info("Workflow scheduler started")
    
    def stop(self):
        self._running = False
        if self._schedule_task:
            self._schedule_task.cancel()
        self._logger.info("Workflow scheduler stopped")
    
    def get_task_status(self, task_id: str) -> Optional[Task]:
        return self._registry.get(task_id)
    
    def get_task_future(self, task_id: str) -> Optional[TaskFuture]:
        return self._task_futures.get(task_id)
    
    def list_schedules(self) -> List[Schedule]:
        return list(self._schedules.values())


WorkflowSchedulerImpl = DefaultWorkflowScheduler
