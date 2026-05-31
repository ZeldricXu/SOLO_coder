from __future__ import annotations

import asyncio
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Callable, Union
from uuid import uuid4


class EventType(str, Enum):
    TASK_STARTED = "task.started"
    TASK_COMPLETED = "task.completed"
    TASK_FAILED = "task.failed"
    SCHEMA_UPDATED = "schema.updated"
    CDC_EVENT = "cdc.event"
    QUALITY_ALERT = "quality.alert"
    LIFECYCLE_MIGRATION = "lifecycle.migration"
    LINEAGE_UPDATED = "lineage.updated"
    VECTOR_INDEX_UPDATED = "vector_index.updated"
    SERVICE_STARTED = "service.started"
    SERVICE_STOPPED = "service.stopped"
    COMPRESSION_STARTED = "compression.started"
    COMPRESSION_COMPLETED = "compression.completed"
    COMPRESSION_FAILED = "compression.failed"
    VALIDATION_STARTED = "validation.started"
    VALIDATION_PASSED = "validation.passed"
    VALIDATION_FAILED = "validation.failed"
    ANOMALY_DETECTED = "anomaly.detected"
    RULE_CREATED = "rule.created"
    RULE_DELETED = "rule.deleted"
    CONFIG_UPDATED = "config.updated"
    QUERY_SUBMITTED = "query.submitted"
    QUERY_PARSING = "query.parsing"
    QUERY_OPTIMIZING = "query.optimizing"
    QUERY_COMPLETED = "query.completed"
    QUERY_FAILED = "query.failed"


@dataclass
class Event:
    event_type: EventType
    payload: dict[str, Any]
    event_id: str = field(default_factory=lambda: uuid4().hex)
    timestamp: datetime = field(default_factory=datetime.utcnow)
    source: str = "streamsql"

    def to_dict(self) -> dict[str, Any]:
        return {
            "event_id": self.event_id,
            "event_type": self.event_type.value,
            "payload": self.payload,
            "timestamp": self.timestamp.isoformat(),
            "source": self.source,
        }


EventHandler = Callable[["Event"], Any]
AsyncEventHandler = Callable[["Event"], Any]


class EventBus:
    _instance: "Optional[EventBus]" = None

    def __new__(cls) -> "EventBus":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._sync_handlers: dict[EventType, list[EventHandler]] = defaultdict(list)
            cls._instance._async_handlers: dict[EventType, list[AsyncEventHandler]] = defaultdict(list)
        return cls._instance

    def subscribe(self, event_type: EventType, handler: EventHandler) -> None:
        self._sync_handlers[event_type].append(handler)

    def subscribe_async(self, event_type: EventType, handler: AsyncEventHandler) -> None:
        self._async_handlers[event_type].append(handler)

    def unsubscribe(self, event_type: EventType, handler: EventHandler) -> None:
        if handler in self._sync_handlers[event_type]:
            self._sync_handlers[event_type].remove(handler)

    def emit(self, event: Event) -> list[Any]:
        results: list[Any] = []
        for handler in self._sync_handlers.get(event.event_type, []):
            try:
                result = handler(event)
                results.append(result)
            except Exception:
                pass
        return results

    async def emit_async(self, event: Event) -> list[Any]:
        results: list[Any] = []
        for handler in self._sync_handlers.get(event.event_type, []):
            try:
                results.append(handler(event))
            except Exception:
                pass

        async_handlers = self._async_handlers.get(event.event_type, [])
        if async_handlers:
            coros = [handler(event) for handler in async_handlers]
            async_results = await asyncio.gather(*coros, return_exceptions=True)
            results.extend([r for r in async_results if not isinstance(r, Exception)])

        return results

    def clear(self) -> None:
        self._sync_handlers.clear()
        self._async_handlers.clear()
