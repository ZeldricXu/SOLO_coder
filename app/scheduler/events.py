"""
Event system for scheduler module.
Provides event bus for async task notifications.
"""

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Set

from app.scheduler.models import Task, TaskStatus


class TaskEventType(str, Enum):
    TASK_SUBMITTED = "task.submitted"
    TASK_STARTED = "task.started"
    TASK_PROGRESS = "task.progress"
    TASK_COMPLETED = "task.completed"
    TASK_FAILED = "task.failed"
    TASK_RETRY = "task.retry"
    TASK_CANCELLED = "task.cancelled"
    TASK_TIMEOUT = "task.timeout"
    WORKFLOW_STARTED = "workflow.started"
    WORKFLOW_COMPLETED = "workflow.completed"
    WORKFLOW_FAILED = "workflow.failed"
    SCHEDULE_TRIGGERED = "schedule.triggered"


@dataclass
class TaskEvent:
    event_type: TaskEventType
    task_id: str
    task_name: Optional[str] = None
    status: Optional[TaskStatus] = None
    result: Optional[Any] = None
    error: Optional[str] = None
    progress: float = 0.0
    attempt: int = 0
    timestamp: datetime = field(default_factory=datetime.utcnow)
    metadata: Dict[str, Any] = field(default_factory=dict)


class TaskCallback:
    def __init__(
        self,
        on_success: Optional[Callable[[Task], None]] = None,
        on_failure: Optional[Callable[[Task, str], None]] = None,
        on_complete: Optional[Callable[[Task], None]] = None,
        on_retry: Optional[Callable[[Task, int], None]] = None
    ):
        self.on_success = on_success
        self.on_failure = on_failure
        self.on_complete = on_complete
        self.on_retry = on_retry


class AsyncTaskCallback:
    def __init__(
        self,
        on_success: Optional[Callable[[Task], Any]] = None,
        on_failure: Optional[Callable[[Task, str], Any]] = None,
        on_complete: Optional[Callable[[Task], Any]] = None,
        on_retry: Optional[Callable[[Task, int], Any]] = None
    ):
        self.on_success = on_success
        self.on_failure = on_failure
        self.on_complete = on_complete
        self.on_retry = on_retry


class EventBus:
    def __init__(self):
        self._listeners: Dict[str, List[Callable[[TaskEvent], Any]]] = {}
        self._async_listeners: Dict[str, List[Callable[[TaskEvent], Any]]] = {}
        self._task_callbacks: Dict[str, TaskCallback] = {}
        self._task_async_callbacks: Dict[str, AsyncTaskCallback] = {}
    
    def subscribe(
        self,
        event_type: TaskEventType,
        listener: Callable[[TaskEvent], Any]
    ):
        event_key = event_type.value if isinstance(event_type, TaskEventType) else event_type
        if event_key not in self._listeners:
            self._listeners[event_key] = []
        self._listeners[event_key].append(listener)
    
    def subscribe_async(
        self,
        event_type: TaskEventType,
        listener: Callable[[TaskEvent], Any]
    ):
        event_key = event_type.value if isinstance(event_type, TaskEventType) else event_type
        if event_key not in self._async_listeners:
            self._async_listeners[event_key] = []
        self._async_listeners[event_key].append(listener)
    
    def unsubscribe(
        self,
        event_type: TaskEventType,
        listener: Callable[[TaskEvent], Any]
    ):
        event_key = event_type.value if isinstance(event_type, TaskEventType) else event_type
        if event_key in self._listeners and listener in self._listeners[event_key]:
            self._listeners[event_key].remove(listener)
    
    def register_task_callback(self, task_id: str, callback: TaskCallback):
        self._task_callbacks[task_id] = callback
    
    def register_async_task_callback(self, task_id: str, callback: AsyncTaskCallback):
        self._task_async_callbacks[task_id] = callback
    
    def emit(self, event: TaskEvent):
        event_key = event.event_type.value if isinstance(event.event_type, TaskEventType) else event.event_type
        
        listeners = self._listeners.get(event_key, [])
        for listener in listeners:
            try:
                listener(event)
            except Exception:
                pass
        
        wildcard_listeners = self._listeners.get("*", [])
        for listener in wildcard_listeners:
            try:
                listener(event)
            except Exception:
                pass
    
    async def emit_async(self, event: TaskEvent):
        event_key = event.event_type.value if isinstance(event.event_type, TaskEventType) else event.event_type
        
        self.emit(event)
        
        async_listeners = self._async_listeners.get(event_key, [])
        for listener in async_listeners:
            try:
                result = listener(event)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass
        
        wildcard_async = self._async_listeners.get("*", [])
        for listener in wildcard_async:
            try:
                result = listener(event)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass
    
    def invoke_success_callback(self, task: Task):
        callback = self._task_callbacks.get(task.task_id)
        if callback and callback.on_success:
            try:
                callback.on_success(task)
            except Exception:
                pass
        if callback and callback.on_complete:
            try:
                callback.on_complete(task)
            except Exception:
                pass
    
    def invoke_failure_callback(self, task: Task, error: str):
        callback = self._task_callbacks.get(task.task_id)
        if callback and callback.on_failure:
            try:
                callback.on_failure(task, error)
            except Exception:
                pass
        if callback and callback.on_complete:
            try:
                callback.on_complete(task)
            except Exception:
                pass
    
    def invoke_retry_callback(self, task: Task, attempt: int):
        callback = self._task_callbacks.get(task.task_id)
        if callback and callback.on_retry:
            try:
                callback.on_retry(task, attempt)
            except Exception:
                pass
    
    async def invoke_async_success_callback(self, task: Task):
        callback = self._task_async_callbacks.get(task.task_id)
        if callback and callback.on_success:
            try:
                result = callback.on_success(task)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass
        if callback and callback.on_complete:
            try:
                result = callback.on_complete(task)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass
    
    async def invoke_async_failure_callback(self, task: Task, error: str):
        callback = self._task_async_callbacks.get(task.task_id)
        if callback and callback.on_failure:
            try:
                result = callback.on_failure(task, error)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass
        if callback and callback.on_complete:
            try:
                result = callback.on_complete(task)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass
    
    async def invoke_async_retry_callback(self, task: Task, attempt: int):
        callback = self._task_async_callbacks.get(task.task_id)
        if callback and callback.on_retry:
            try:
                result = callback.on_retry(task, attempt)
                if asyncio.iscoroutine(result):
                    await result
            except Exception:
                pass


class TaskFuture:
    def __init__(self, task_id: str):
        self.task_id = task_id
        self._done = False
        self._result: Optional[Any] = None
        self._error: Optional[str] = None
        self._event: asyncio.Event = asyncio.Event()
        self._callbacks: List[Callable[["TaskFuture"], Any]] = []
    
    def done(self) -> bool:
        return self._done
    
    def result(self) -> Optional[Any]:
        return self._result
    
    def error(self) -> Optional[str]:
        return self._error
    
    def set_result(self, result: Any):
        self._result = result
        self._done = True
        self._event.set()
        self._invoke_callbacks()
    
    def set_error(self, error: str):
        self._error = error
        self._done = True
        self._event.set()
        self._invoke_callbacks()
    
    def add_done_callback(self, callback: Callable[["TaskFuture"], Any]):
        if self._done:
            callback(self)
        else:
            self._callbacks.append(callback)
    
    def _invoke_callbacks(self):
        for callback in self._callbacks:
            try:
                callback(self)
            except Exception:
                pass
    
    async def wait(self, timeout: Optional[float] = None) -> bool:
        if timeout is None:
            await self._event.wait()
            return True
        try:
            await asyncio.wait_for(self._event.wait(), timeout=timeout)
            return True
        except asyncio.TimeoutError:
            return False
