from datetime import datetime
from typing import Any, Callable, Dict, List, Optional
from dataclasses import dataclass, field
from enum import Enum
import asyncio


class EventType(str, Enum):
    TASK_STARTED = "task.started"
    TASK_COMPLETED = "task.completed"
    TASK_FAILED = "task.failed"
    DATA_MIGRATED = "data.migrated"
    BACKUP_CREATED = "backup.created"
    BACKUP_RESTORED = "backup.restored"
    CONFIG_UPDATED = "config.updated"
    CONFIG_ROLLED_BACK = "config.rolled_back"
    AUDIT_LOGGED = "audit.logged"
    NOTIFICATION_SENT = "notification.sent"
    MPC_COMPUTED = "mpc.computed"
    PRIVACY_BUDGET_CONSUMED = "privacy.budget_consumed"


@dataclass
class Event:
    event_type: EventType
    timestamp: datetime = field(default_factory=datetime.utcnow)
    payload: Dict[str, Any] = field(default_factory=dict)
    source: Optional[str] = None


class EventBus:
    def __init__(self):
        self._handlers: Dict[EventType, List[Callable[[Event], Any]]] = {}
        self._async_handlers: Dict[EventType, List[Callable[[Event], Any]]] = {}

    def subscribe(self, event_type: EventType, handler: Callable[[Event], Any], is_async: bool = False):
        if is_async:
            if event_type not in self._async_handlers:
                self._async_handlers[event_type] = []
            self._async_handlers[event_type].append(handler)
        else:
            if event_type not in self._handlers:
                self._handlers[event_type] = []
            self._handlers[event_type].append(handler)

    def unsubscribe(self, event_type: EventType, handler: Callable[[Event], Any]):
        if event_type in self._handlers and handler in self._handlers[event_type]:
            self._handlers[event_type].remove(handler)
        if event_type in self._async_handlers and handler in self._async_handlers[event_type]:
            self._async_handlers[event_type].remove(handler)

    def emit(self, event: Event):
        for handler in self._handlers.get(event.event_type, []):
            try:
                handler(event)
            except Exception:
                pass

    async def emit_async(self, event: Event):
        self.emit(event)
        tasks = []
        for handler in self._async_handlers.get(event.event_type, []):
            task = asyncio.create_task(self._safe_handle(handler, event))
            tasks.append(task)
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _safe_handle(self, handler: Callable[[Event], Any], event: Event):
        try:
            result = handler(event)
            if asyncio.iscoroutine(result):
                await result
        except Exception:
            pass


event_bus = EventBus()


def on(event_type: EventType, is_async: bool = False):
    def decorator(func: Callable[[Event], Any]):
        event_bus.subscribe(event_type, func, is_async)
        return func
    return decorator


def build_event(event_type: EventType, data: Any, source: Optional[str] = None) -> Event:
    payload = {}
    if hasattr(data, "model_dump"):
        payload = data.model_dump()
    elif isinstance(data, dict):
        payload = data.copy()
    else:
        payload = {"data": data}
    return Event(event_type=event_type, payload=payload, source=source)
