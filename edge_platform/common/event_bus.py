import asyncio
import logging
from typing import Dict, List, Callable, Any, Optional
from dataclasses import dataclass, field
from datetime import datetime
import uuid

logger = logging.getLogger(__name__)


@dataclass
class Event:
    event_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    event_type: str = ""
    source: str = ""
    timestamp: datetime = field(default_factory=datetime.now)
    payload: Dict[str, Any] = field(default_factory=dict)
    version: str = "1.0"


class EventBus:
    def __init__(self):
        self._subscribers: Dict[str, List[Callable[[Event], None]]] = {}
        self._async_subscribers: Dict[str, List[Callable[[Event], Any]]] = {}
        self._event_history: List[Event] = []
        self._max_history = 1000

    def subscribe(self, event_type: str, handler: Callable[[Event], None]) -> None:
        if event_type not in self._subscribers:
            self._subscribers[event_type] = []
        self._subscribers[event_type].append(handler)
        logger.debug(f"Subscribed handler to event type: {event_type}")

    def subscribe_async(self, event_type: str, handler: Callable[[Event], Any]) -> None:
        if event_type not in self._async_subscribers:
            self._async_subscribers[event_type] = []
        self._async_subscribers[event_type].append(handler)
        logger.debug(f"Subscribed async handler to event type: {event_type}")

    def publish(self, event: Event) -> None:
        self._event_history.append(event)
        if len(self._event_history) > self._max_history:
            self._event_history.pop(0)

        handlers = self._subscribers.get(event.event_type, [])
        for handler in handlers:
            try:
                handler(event)
            except Exception as e:
                logger.error(f"Error handling event {event.event_type}: {e}")

        async_handlers = self._async_subscribers.get(event.event_type, [])
        for handler in async_handlers:
            asyncio.create_task(self._run_async_handler(handler, event))

    async def _run_async_handler(self, handler: Callable[[Event], Any], event: Event) -> None:
        try:
            await handler(event)
        except Exception as e:
            logger.error(f"Error in async handler for event {event.event_type}: {e}")

    def get_event_history(self, event_type: Optional[str] = None) -> List[Event]:
        if event_type:
            return [e for e in self._event_history if e.event_type == event_type]
        return self._event_history.copy()


event_bus = EventBus()
