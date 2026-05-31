import asyncio
from typing import Dict, List, Callable, Any, Optional
from datetime import datetime
import uuid

from domain.models.event import DomainEvent, EventType
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)

EventHandler = Callable[[DomainEvent], None]


class EventBus:
    def __init__(self):
        self._handlers: Dict[EventType, List[EventHandler]] = {}
        self._global_handlers: List[EventHandler] = []
        self._event_history: List[DomainEvent] = []
        self._max_history_size: int = 10000

    def subscribe(self, event_type: EventType, handler: EventHandler) -> None:
        if event_type not in self._handlers:
            self._handlers[event_type] = []
        self._handlers[event_type].append(handler)
        logger.info(f"Subscribed handler to event type: {event_type}")

    def subscribe_all(self, handler: EventHandler) -> None:
        self._global_handlers.append(handler)
        logger.info("Subscribed global handler")

    def unsubscribe(self, event_type: EventType, handler: EventHandler) -> None:
        if event_type in self._handlers and handler in self._handlers[event_type]:
            self._handlers[event_type].remove(handler)
            logger.info(f"Unsubscribed handler from event type: {event_type}")

    def publish(self, event: DomainEvent) -> None:
        self._add_to_history(event)

        if event.event_type in self._handlers:
            for handler in self._handlers[event.event_type]:
                try:
                    handler(event)
                except Exception as e:
                    logger.error(f"Error handling event {event.event_id}: {str(e)}")

        for handler in self._global_handlers:
            try:
                handler(event)
            except Exception as e:
                logger.error(f"Error in global handler for event {event.event_id}: {str(e)}")

    async def publish_async(self, event: DomainEvent) -> None:
        self._add_to_history(event)

        tasks = []

        if event.event_type in self._handlers:
            for handler in self._handlers[event.event_type]:
                tasks.append(self._execute_handler_async(handler, event))

        for handler in self._global_handlers:
            tasks.append(self._execute_handler_async(handler, event))

        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _execute_handler_async(self, handler: EventHandler, event: DomainEvent) -> None:
        try:
            if asyncio.iscoroutinefunction(handler):
                await handler(event)
            else:
                handler(event)
        except Exception as e:
            logger.error(f"Error in async handler for event {event.event_id}: {str(e)}")

    def _add_to_history(self, event: DomainEvent) -> None:
        self._event_history.append(event)
        if len(self._event_history) > self._max_history_size:
            self._event_history = self._event_history[-self._max_history_size:]

    def get_history(self, event_type: Optional[EventType] = None, limit: int = 100) -> List[DomainEvent]:
        events = self._event_history
        if event_type:
            events = [e for e in events if e.event_type == event_type]
        return events[-limit:]

    def clear_history(self) -> None:
        self._event_history = []
        logger.info("Event history cleared")

    def create_event(
        self,
        event_type: EventType,
        device_id: Optional[str] = None,
        data: Optional[Dict[str, Any]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> DomainEvent:
        return DomainEvent(
            event_id=str(uuid.uuid4()),
            event_type=event_type,
            timestamp=datetime.utcnow(),
            device_id=device_id,
            data=data or {},
            metadata=metadata or {},
        )


_event_bus = EventBus()


def get_event_bus() -> EventBus:
    return _event_bus
