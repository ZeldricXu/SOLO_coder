from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Optional

from streamsql.modules.cdc_capture.binlog_parser import CDCEvent, OperationType


class ProcessingStage(str, Enum):
    FILTER = "filter"
    TRANSFORM = "transform"
    AGGREGATE = "aggregate"
    DEDUPLICATE = "deduplicate"
    ROUTE = "route"
    VALIDATE = "validate"
    ENRICH = "enrich"


@dataclass
class ProcessingContext:
    event_count: int = 0
    batch_count: int = 0
    error_count: int = 0
    first_event_time: Optional[datetime] = None
    last_event_time: Optional[datetime] = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def record_event(self, event: CDCEvent) -> None:
        self.event_count += 1
        self.last_event_time = datetime.utcnow()
        if self.first_event_time is None:
            self.first_event_time = self.last_event_time

    def record_batch(self) -> None:
        self.batch_count += 1

    def record_error(self) -> None:
        self.error_count += 1


class ProcessingStrategy(ABC):
    name: str
    description: str
    stage: ProcessingStage

    @abstractmethod
    async def process(
        self,
        event: CDCEvent,
        context: ProcessingContext,
    ) -> Optional[CDCEvent]:
        """
        Process a single CDC event.

        Args:
            event: The CDC event to process
            context: Processing context with statistics

        Returns:
            Processed event or None if the event should be dropped
        """
        pass

    async def process_batch(
        self,
        events: list[CDCEvent],
        context: ProcessingContext,
    ) -> list[CDCEvent]:
        """
        Process a batch of CDC events. Default implementation processes each event individually.

        Args:
            events: List of CDC events to process
            context: Processing context with statistics

        Returns:
            List of processed events
        """
        result: list[CDCEvent] = []
        for event in events:
            processed = await self.process(event, context)
            if processed is not None:
                result.append(processed)
        return result

    async def on_start(self, context: ProcessingContext) -> None:
        """Called when processing starts."""
        pass

    async def on_batch_start(self, context: ProcessingContext) -> None:
        """Called when a new batch starts."""
        pass

    async def on_batch_complete(self, context: ProcessingContext) -> None:
        """Called when a batch completes."""
        pass

    async def on_stop(self, context: ProcessingContext) -> None:
        """Called when processing stops."""
        pass


class FilterStrategy(ProcessingStrategy):
    """Filters events based on various criteria."""

    name = "filter"
    description = "Filter events by table, operation type, or custom predicate"
    stage = ProcessingStage.FILTER

    def __init__(
        self,
        include_tables: Optional[list[str]] = None,
        exclude_tables: Optional[list[str]] = None,
        include_operations: Optional[list[OperationType]] = None,
        custom_filter: Optional[Callable[[CDCEvent], bool]] = None,
    ):
        self.include_tables = include_tables
        self.exclude_tables = exclude_tables
        self.include_operations = include_operations
        self.custom_filter = custom_filter

    async def process(
        self,
        event: CDCEvent,
        context: ProcessingContext,
    ) -> Optional[CDCEvent]:
        if self.include_tables and event.table not in self.include_tables:
            return None

        if self.exclude_tables and event.table in self.exclude_tables:
            return None

        if self.include_operations and event.operation not in [op.value for op in self.include_operations]:
            return None

        if self.custom_filter and not self.custom_filter(event):
            return None

        return event


class TransformStrategy(ProcessingStrategy):
    """Transforms event data (field mapping, type conversion, etc."""

    name = "transform"
    description = "Transform event data with field mapping and type conversion"
    stage = ProcessingStage.TRANSFORM

    def __init__(
        self,
        field_mapping: Optional[dict[str, str]] = None,
        type_converters: Optional[dict[str, Callable[[Any], Any]]] = None,
        add_fields: Optional[dict[str, Any]] = None,
        remove_fields: Optional[list[str]] = None,
    ):
        self.field_mapping = field_mapping or {}
        self.type_converters = type_converters or {}
        self.add_fields = add_fields or {}
        self.remove_fields = remove_fields or []

    async def process(
        self,
        event: CDCEvent,
        context: ProcessingContext,
    ) -> Optional[CDCEvent]:
        if event.after:
            event.after = self._transform_data(event.after)
        if event.before:
            event.before = self._transform_data(event.before)

        return event

    def _transform_data(self, data: dict[str, Any]) -> dict[str, Any]:
        result = data.copy()

        for old_name, new_name in self.field_mapping.items():
            if old_name in result:
                result[new_name] = result.pop(old_name)

        for field, converter in self.type_converters.items():
            if field in result:
                result[field] = converter(result[field])

        for field, value in self.add_fields.items():
            if callable(value):
                result[field] = value()
            else:
                result[field] = value

        for field in self.remove_fields:
            result.pop(field, None)

        return result


class DeduplicationStrategy(ProcessingStrategy):
    """Removes duplicate events based on key fields."""

    name = "deduplicate"
    description = "Remove duplicate events based on key fields"
    stage = ProcessingStage.DEDUPLICATE

    def __init__(
        self,
        key_fields: Optional[list[str]] = None,
        window_seconds: int = 60,
        max_cache_size: int = 10000,
    ):
        self.key_fields = key_fields or ["id"]
        self.window_seconds = window_seconds
        self.max_cache_size = max_cache_size
        self._seen_keys: dict[str, datetime] = {}

    async def process(
        self,
        event: CDCEvent,
        context: ProcessingContext,
    ) -> Optional[CDCEvent]:
        key = self._make_key(event)
        now = datetime.utcnow()

        self._cleanup_expired(now)

        if key in self._seen_keys:
            return None

        if len(self._seen_keys) >= self.max_cache_size:
            oldest_key = min(self._seen_keys, key=self._seen_keys.get)
            del self._seen_keys[oldest_key]

        self._seen_keys[key] = now
        return event

    def _make_key(self, event: CDCEvent) -> str:
        data = event.after or event.before or {}
        key_parts = [event.table, event.operation]
        for field in self.key_fields:
            if field in data:
                key_parts.append(str(data[field]))
        return "|".join(key_parts)

    def _cleanup_expired(self, now: datetime) -> None:
        cutoff = now - timedelta(seconds=self.window_seconds)
        expired = [k for k, v in self._seen_keys.items() if v < cutoff]
        for k in expired:
            del self._seen_keys[k]


class ThrottlingStrategy(ProcessingStrategy):
    """Throttles event processing rate."""

    name = "throttling"
    description = "Throttle event processing rate"
    stage = ProcessingStage.TRANSFORM

    def __init__(self, max_events_per_second: int = 1000):
        self.max_events_per_second = max_events_per_second
        self._event_times: list[datetime] = []

    async def process(
        self,
        event: CDCEvent,
        context: ProcessingContext,
    ) -> Optional[CDCEvent]:
        import asyncio

        now = datetime.utcnow()
        self._event_times = [
            t for t in self._event_times
            if (now - t).total_seconds() < 1.0
        ]

        if len(self._event_times) >= self.max_events_per_second:
            sleep_time = 1.0 - (now - self._event_times[0]).total_seconds()
            if sleep_time > 0:
                await asyncio.sleep(sleep_time)
            self._event_times = [datetime.utcnow()]
        else:
            self._event_times.append(now)

        return event


class MaskingStrategy(ProcessingStrategy):
    """Masks sensitive data in events."""

    name = "masking"
    description = "Mask sensitive data in events"
    stage = ProcessingStage.TRANSFORM

    def __init__(
        self,
        sensitive_fields: Optional[list[str]] = None,
        mask_char: str = "*",
        mask_function: Optional[Callable[[str], str]] = None,
    ):
        self.sensitive_fields = sensitive_fields or []
        self.mask_char = mask_char
        self.mask_function = mask_function or self._default_mask

    async def process(
        self,
        event: CDCEvent,
        context: ProcessingContext,
    ) -> Optional[CDCEvent]:
        if event.after:
            event.after = self._mask_data(event.after)
        if event.before:
            event.before = self._mask_data(event.before)
        return event

    def _mask_data(self, data: dict[str, Any]) -> dict[str, Any]:
        result = data.copy()
        for field in self.sensitive_fields:
            if field in result and isinstance(result[field], str):
                result[field] = self.mask_function(result[field])
        return result

    def _default_mask(self, value: str) -> str:
        if len(value) <= 4:
            return self.mask_char * len(value)
        return value[:2] + self.mask_char * (len(value) - 4) + value[-2:]


class RouteStrategy(ProcessingStrategy):
    """Routes events to different outputs based on rules."""

    name = "route"
    description = "Route events to different outputs based on rules"
    stage = ProcessingStage.ROUTE

    def __init__(
        self,
        routing_rules: Optional[dict[str, Callable[[CDCEvent], bool]]] = None,
        default_output: str = "default",
    ):
        self.routing_rules = routing_rules or {}
        self.default_output = default_output

    async def process(
        self,
        event: CDCEvent,
        context: ProcessingContext,
    ) -> Optional[CDCEvent]:
        for output, rule in self.routing_rules.items():
            if rule(event):
                event.metadata = event.metadata or {}
                event.metadata["route_target"] = output
                return event

        event.metadata = event.metadata or {}
        event.metadata["route_target"] = self.default_output
        return event


class StrategyRegistry:
    """Registry for CDC processing strategies."""

    _strategies: dict[str, type[ProcessingStrategy]] = {}

    @classmethod
    def register(cls, strategy_class: type[ProcessingStrategy]) -> None:
        cls._strategies[strategy_class.name] = strategy_class

    @classmethod
    def get(cls, name: str) -> Optional[type[ProcessingStrategy]]:
        return cls._strategies.get(name)

    @classmethod
    def create(cls, name: str, **kwargs: Any) -> Optional[ProcessingStrategy]:
        strategy_class = cls.get(name)
        if strategy_class:
            return strategy_class(**kwargs)
        return None

    @classmethod
    def list_names(cls) -> list[str]:
        return list(cls._strategies.keys())

    @classmethod
    def get_all(cls) -> list[type[ProcessingStrategy]]:
        return list(cls._strategies.values())


StrategyRegistry.register(FilterStrategy)
StrategyRegistry.register(TransformStrategy)
StrategyRegistry.register(DeduplicationStrategy)
StrategyRegistry.register(ThrottlingStrategy)
StrategyRegistry.register(MaskingStrategy)
StrategyRegistry.register(RouteStrategy)


class StrategyPipeline:
    """Pipeline for executing multiple processing strategies in sequence."""

    def __init__(self, strategies: Optional[list[ProcessingStrategy]] = None):
        self._strategies: list[ProcessingStrategy] = strategies or []
        self._context = ProcessingContext()

    def add_strategy(self, strategy: ProcessingStrategy, position: Optional[int] = None) -> None:
        if position is None:
            self._strategies.append(strategy)
        else:
            self._strategies.insert(position, strategy)

    def remove_strategy(self, strategy_name: str) -> bool:
        for i, s in enumerate(self._strategies):
            if s.name == strategy_name:
                del self._strategies[i]
                return True
        return False

    def get_strategy(self, strategy_name: str) -> Optional[ProcessingStrategy]:
        for s in self._strategies:
            if s.name == strategy_name:
                return s
        return None

    def list_strategies(self) -> list[tuple[str, str]]:
        return [(s.name, s.description) for s in self._strategies]

    def clear(self) -> None:
        self._strategies.clear()

    async def process_event(self, event: CDCEvent) -> Optional[CDCEvent]:
        current = event
        for strategy in self._strategies:
            if current is None:
                break
            current = await strategy.process(current, self._context)
        if current:
            self._context.record_event(current)
        return current

    async def process_batch(self, events: list[CDCEvent]) -> list[CDCEvent]:
        for strategy in self._strategies:
            await strategy.on_batch_start(self._context)
            events = await strategy.process_batch(events, self._context)
            await strategy.on_batch_complete(self._context)
        for event in events:
            self._context.record_event(event)
        self._context.record_batch()
        return events

    async def start(self) -> None:
        for strategy in self._strategies:
            await strategy.on_start(self._context)

    async def stop(self) -> None:
        for strategy in self._strategies:
            await strategy.on_stop(self._context)

    def get_context(self) -> ProcessingContext:
        return self._context
