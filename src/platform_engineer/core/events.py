import asyncio
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional
from uuid import uuid4


class DomainEvent:
    def __init__(
        self,
        event_type: str,
        payload: Dict[str, Any],
        source: Optional[str] = None,
        trace_id: Optional[str] = None,
    ):
        self.event_id = uuid4().hex[:16]
        self.event_type = event_type
        self.payload = payload
        self.source = source
        self.trace_id = trace_id or uuid4().hex[:16]
        self.timestamp = datetime.now(timezone.utc)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_id": self.event_id,
            "event_type": self.event_type,
            "payload": self.payload,
            "source": self.source,
            "trace_id": self.trace_id,
            "timestamp": self.timestamp.isoformat(),
        }


class EventHandler(ABC):
    @abstractmethod
    async def handle(self, event: DomainEvent) -> None:
        pass


class SimpleEventHandler(EventHandler):
    def __init__(self, callback: Callable[[DomainEvent], Any]):
        self.callback = callback

    async def handle(self, event: DomainEvent) -> None:
        result = self.callback(event)
        if asyncio.iscoroutine(result):
            await result


class EventBus:
    def __init__(self):
        self._subscribers: Dict[str, List[EventHandler]] = {}
        self._all_handlers: List[EventHandler] = []
        self._event_history: List[DomainEvent] = []

    def subscribe(self, event_type: str, handler: EventHandler) -> None:
        if event_type not in self._subscribers:
            self._subscribers[event_type] = []
        self._subscribers[event_type].append(handler)

    def subscribe_all(self, handler: EventHandler) -> None:
        self._all_handlers.append(handler)

    def unsubscribe(self, event_type: str, handler: EventHandler) -> bool:
        if event_type in self._subscribers and handler in self._subscribers[event_type]:
            self._subscribers[event_type].remove(handler)
            return True
        return False

    async def publish(self, event: DomainEvent) -> int:
        self._event_history.append(event)
        handlers = [*self._all_handlers]
        if event.event_type in self._subscribers:
            handlers.extend(self._subscribers[event.event_type])

        results = await asyncio.gather(
            *[self._safe_handle(h, event) for h in handlers],
            return_exceptions=True,
        )

        success_count = sum(1 for r in results if r is True)
        return success_count

    async def _safe_handle(self, handler: EventHandler, event: DomainEvent) -> bool:
        try:
            await handler.handle(event)
            return True
        except Exception:
            return False

    def get_history(self, event_type: Optional[str] = None, limit: int = 100) -> List[DomainEvent]:
        events = self._event_history
        if event_type:
            events = [e for e in events if e.event_type == event_type]
        return events[-limit:]

    def clear_history(self) -> None:
        self._event_history.clear()


_global_event_bus = EventBus()


def get_global_event_bus() -> EventBus:
    return _global_event_bus


def emit_event(event_type: str, payload: Dict[str, Any], source: Optional[str] = None) -> DomainEvent:
    event = DomainEvent(event_type=event_type, payload=payload, source=source)
    asyncio.create_task(_global_event_bus.publish(event))
    return event


async def emit_event_async(
    event_type: str, payload: Dict[str, Any], source: Optional[str] = None
) -> int:
    event = DomainEvent(event_type=event_type, payload=payload, source=source)
    return await _global_event_bus.publish(event)
