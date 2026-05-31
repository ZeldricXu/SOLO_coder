import asyncio
import uuid
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional
from pydantic import BaseModel, Field


class Event(BaseModel):
    event_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    event_type: str
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    source: str
    data: Dict[str, Any] = Field(default_factory=dict)
    trace_id: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class EventBus:
    def __init__(self):
        self._subscribers: Dict[str, List[Callable[[Event], Any]]] = {}
        self._history: List[Event] = []
        self._max_history = 1000

    def subscribe(self, event_type: str, handler: Callable[[Event], Any]) -> None:
        if event_type not in self._subscribers:
            self._subscribers[event_type] = []
        self._subscribers[event_type].append(handler)

    def unsubscribe(self, event_type: str, handler: Callable[[Event], Any]) -> None:
        if event_type in self._subscribers:
            self._subscribers[event_type].remove(handler)

    def publish(self, event: Event) -> None:
        self._history.append(event)
        if len(self._history) > self._max_history:
            self._history.pop(0)

        if event.event_type in self._subscribers:
            for handler in self._subscribers[event.event_type]:
                try:
                    if asyncio.iscoroutinefunction(handler):
                        asyncio.create_task(handler(event))
                    else:
                        handler(event)
                except Exception as e:
                    print(f"Error handling event {event.event_type}: {e}")

    def get_history(self, event_type: Optional[str] = None) -> List[Event]:
        if event_type:
            return [e for e in self._history if e.event_type == event_type]
        return list(self._history)


class EventTypes:
    TASK_CREATED = "task.created"
    TASK_COMPLETED = "task.completed"
    TASK_FAILED = "task.failed"
    DEVICE_REGISTERED = "device.registered"
    DEVICE_ONLINE = "device.online"
    DEVICE_OFFLINE = "device.offline"
    OTA_UPDATE_STARTED = "ota.update.started"
    OTA_UPDATE_COMPLETED = "ota.update.completed"
    OTA_UPDATE_FAILED = "ota.update.failed"
    RULE_TRIGGERED = "rule.triggered"
    RULE_EXECUTED = "rule.executed"
    ALERT_TRIGGERED = "alert.triggered"
    METRICS_REPORTED = "metrics.reported"


event_bus = EventBus()


def emit_event(
    event_type: str,
    source: str,
    data: Dict[str, Any],
    trace_id: Optional[str] = None,
) -> Event:
    event = Event(
        event_type=event_type,
        source=source,
        data=data,
        trace_id=trace_id,
    )
    event_bus.publish(event)
    return event
