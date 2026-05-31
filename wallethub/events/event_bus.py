import asyncio
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional
from uuid import uuid4
from datetime import datetime, timezone


logger = logging.getLogger(__name__)


@dataclass
class Event:
    event_id: str = field(default_factory=lambda: str(uuid4()))
    event_type: str = field(default="")
    timestamp: datetime = field(
        default_factory=lambda: datetime.now(timezone.utc)
    )
    payload: Dict[str, Any] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.event_type:
            self.event_type = self.__class__.__name__


class EventHandler(ABC):
    @abstractmethod
    async def handle(self, event: Event) -> None:
        pass


class FunctionalHandler(EventHandler):
    def __init__(
        self,
        func: Callable[[Event], Awaitable[None]],
        event_type: Optional[str] = None,
    ) -> None:
        self._func = func
        self._event_type = event_type

    async def handle(self, event: Event) -> None:
        if self._event_type and event.event_type != self._event_type:
            return
        await self._func(event)


class EventBus:
    _instance: Optional["EventBus"] = None

    def __new__(cls) -> "EventBus":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._handlers: Dict[str, List[EventHandler]] = {}
            cls._instance._global_handlers: List[EventHandler] = []
            cls._instance._queue: asyncio.Queue = asyncio.Queue()
            cls._instance._running = False
            cls._instance._worker_task: Optional[asyncio.Task] = None
        return cls._instance

    def subscribe(
        self,
        event_type: str,
        handler: Callable[[Event], Awaitable[None]] | EventHandler,
    ) -> None:
        if isinstance(handler, EventHandler):
            event_handler = handler
        else:
            event_handler = FunctionalHandler(handler, event_type)

        if event_type not in self._handlers:
            self._handlers[event_type] = []
        self._handlers[event_type].append(event_handler)
        logger.info(f"Subscribed handler for event type: {event_type}")

    def subscribe_all(self, handler: EventHandler) -> None:
        self._global_handlers.append(handler)
        logger.info("Subscribed global handler")

    def unsubscribe(
        self,
        event_type: str,
        handler: Callable[[Event], Awaitable[None]] | EventHandler,
    ) -> None:
        if event_type not in self._handlers:
            return

        handlers = self._handlers[event_type]
        handlers[:] = [
            h
            for h in handlers
            if not (
                isinstance(h, FunctionalHandler)
                and h._func == handler
                or h is handler
            )
        ]

    async def publish(self, event: Event) -> None:
        await self._queue.put(event)
        logger.debug(f"Published event: {event.event_type} (id: {event.event_id})")

    async def publish_now(self, event: Event) -> None:
        await self._dispatch(event)

    async def _dispatch(self, event: Event) -> None:
        handlers = self._handlers.get(event.event_type, []) + self._global_handlers

        if not handlers:
            logger.debug(f"No handlers for event type: {event.event_type}")
            return

        tasks = [handler.handle(event) for handler in handlers]
        try:
            await asyncio.gather(*tasks, return_exceptions=True)
        except Exception as e:
            logger.error(
                f"Error dispatching event {event.event_type}: {str(e)}",
                exc_info=True,
            )

    async def _worker(self) -> None:
        while self._running:
            try:
                event = await self._queue.get()
                await self._dispatch(event)
                self._queue.task_done()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Event bus worker error: {str(e)}", exc_info=True)

    def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._worker_task = asyncio.create_task(self._worker())
        logger.info("Event bus started")

    async def stop(self) -> None:
        if not self._running:
            return
        self._running = False
        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass
        await self._queue.join()
        logger.info("Event bus stopped")


def get_event_bus() -> EventBus:
    return EventBus()
