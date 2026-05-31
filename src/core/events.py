from typing import Any, Dict, Callable, List
from datetime import datetime
import asyncio
import logging

logger = logging.getLogger(__name__)


class Event:
    def __init__(self, name: str, payload: Dict[str, Any], source: str = "system"):
        self.name = name
        self.payload = payload
        self.source = source
        self.timestamp = datetime.utcnow()
        self.event_id = f"evt_{datetime.utcnow().strftime('%Y%m%d%H%M%S%f')}"


class EventEmitter:
    def __init__(self):
        self._handlers: Dict[str, List[Callable[[Event], None]]] = {}
        self._async_handlers: Dict[str, List[Callable[[Event], Any]]] = {}

    def on(self, event_name: str, handler: Callable[[Event], None]) -> None:
        if event_name not in self._handlers:
            self._handlers[event_name] = []
        self._handlers[event_name].append(handler)

    def on_async(self, event_name: str, handler: Callable[[Event], Any]) -> None:
        if event_name not in self._async_handlers:
            self._async_handlers[event_name] = []
        self._async_handlers[event_name].append(handler)

    def emit(self, event_name: str, payload: Dict[str, Any], source: str = "system") -> Event:
        event = Event(event_name, payload, source)
        logger.debug(f"Emitting event: {event_name}, id={event.event_id}")

        for handler in self._handlers.get(event_name, []):
            try:
                handler(event)
            except Exception as e:
                logger.error(f"Error in event handler for {event_name}: {e}")

        for handler in self._handlers.get("*", []):
            try:
                handler(event)
            except Exception as e:
                logger.error(f"Error in wildcard event handler: {e}")

        return event

    async def emit_async(self, event_name: str, payload: Dict[str, Any], source: str = "system") -> Event:
        event = Event(event_name, payload, source)
        logger.debug(f"Emitting async event: {event_name}, id={event.event_id}")

        sync_handlers = []
        for handler in self._handlers.get(event_name, []):
            try:
                handler(event)
            except Exception as e:
                logger.error(f"Error in event handler for {event_name}: {e}")

        async_tasks = []
        for handler in self._async_handlers.get(event_name, []):
            async_tasks.append(asyncio.create_task(handler(event)))

        for handler in self._async_handlers.get("*", []):
            async_tasks.append(asyncio.create_task(handler(event)))

        if async_tasks:
            await asyncio.gather(*async_tasks, return_exceptions=True)

        return event


_event_emitter = EventEmitter()


def get_event_emitter() -> EventEmitter:
    return _event_emitter


def emit_event(name: str, payload: Dict[str, Any], source: str = "system") -> Event:
    return _event_emitter.emit(name, payload, source)


async def emit_event_async(name: str, payload: Dict[str, Any], source: str = "system") -> Event:
    return await _event_emitter.emit_async(name, payload, source)
