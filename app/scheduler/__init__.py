"""
Scheduler Module.
Implements dependency-based task orchestration with DAG execution.
Supports async execution, callbacks, and event notifications.

Re-exports for backward compatibility.
"""

from app.scheduler.base import (
    TaskExecutor as TaskExecutorABC,
    WorkflowScheduler as WorkflowSchedulerABC
)
from app.scheduler.events import (
    TaskEventType,
    TaskEvent,
    TaskCallback,
    AsyncTaskCallback,
    EventBus,
    TaskFuture
)
from app.scheduler.models import (
    TaskStatus,
    ScheduleType,
    Task,
    Schedule
)
from app.scheduler.registry import TaskRegistry
from app.scheduler.resolver import DependencyResolver
from app.scheduler.executor import (
    DefaultTaskExecutor,
    TaskExecutorImpl
)
from app.scheduler.scheduler import (
    DefaultWorkflowScheduler,
    WorkflowSchedulerImpl
)

TaskExecutor = DefaultTaskExecutor
WorkflowScheduler = DefaultWorkflowScheduler
TaskExecutorClass = DefaultTaskExecutor
WorkflowSchedulerClass = DefaultWorkflowScheduler

__all__ = [
    "TaskExecutorABC",
    "WorkflowSchedulerABC",
    "TaskEventType",
    "TaskEvent",
    "TaskCallback",
    "AsyncTaskCallback",
    "EventBus",
    "TaskFuture",
    "TaskStatus",
    "ScheduleType",
    "Task",
    "Schedule",
    "TaskRegistry",
    "DependencyResolver",
    "DefaultTaskExecutor",
    "TaskExecutorImpl",
    "TaskExecutorClass",
    "TaskExecutor",
    "DefaultWorkflowScheduler",
    "WorkflowSchedulerImpl",
    "WorkflowSchedulerClass",
    "WorkflowScheduler",
]
