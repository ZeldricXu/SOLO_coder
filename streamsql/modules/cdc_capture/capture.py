from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any, AsyncGenerator, Callable, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import Event, EventBus, EventType
from streamsql.core.exceptions import CDCCaptureError
from streamsql.core.models import generate_id

from streamsql.modules.cdc_capture.binlog_parser import BinlogParser, CDCEvent, OperationType
from streamsql.modules.cdc_capture.event_serializer import EventSerializer, SerializerFactory
from streamsql.modules.cdc_capture.output_adapter import OutputAdapter, ConsoleOutputAdapter
from streamsql.modules.cdc_capture.strategies import (
    ProcessingStrategy,
    ProcessingContext as StrategyContext,
    StrategyPipeline,
    StrategyRegistry,
)


class CDCCapture:
    """
    CDC Capture with support for pluggable processing strategies.

    Enhanced features:
    - Pluggable processing strategies via StrategyPipeline
    - Runtime strategy management (add/remove/switch)
    - Multiple built-in strategies (filter, transform, deduplicate, etc.)
    - Backward compatible with existing API
    """

    def __init__(
        self,
        datasource_config: dict[str, Any],
        context: Optional[ProcessingContext] = None,
        pipeline: Optional[StrategyPipeline] = None,
    ):
        self.datasource = datasource_config
        self.ds_name = datasource_config.get("name", "unknown")
        self.ds_type = datasource_config.get("type", "mysql")
        self.connection_config = datasource_config.get("connection", {})

        config = ConfigManager.get()
        self.batch_size = config.modules.cdc_capture.batch_size
        self.retry_attempts = config.modules.cdc_capture.retry_attempts

        self.context = context or ProcessingContext(trace_id=generate_id("trace"))
        self.event_bus = EventBus()
        self.parser = BinlogParser(self.ds_name)
        self.serializer: EventSerializer | None = None
        self.output_adapter: OutputAdapter | None = None

        self._pipeline = pipeline or StrategyPipeline()
        self._running = False
        self._last_position: dict[str, Any] = {}

    @property
    def pipeline(self) -> StrategyPipeline:
        """Get the strategy pipeline."""
        return self._pipeline

    def configure_serializer(self, format_type: str = "json", **kwargs: Any) -> None:
        self.serializer = SerializerFactory.get_serializer(format_type, **kwargs)

    def configure_output(self, adapter: OutputAdapter) -> None:
        self.output_adapter = adapter

    def add_strategy(self, strategy: ProcessingStrategy, position: Optional[int] = None) -> None:
        """Add a processing strategy to the pipeline."""
        self._pipeline.add_strategy(strategy, position)
        self.event_bus.emit(
            Event(
                EventType.CONFIG_UPDATED,
                {
                    "module": "cdc_capture",
                    "action": "strategy_added",
                    "strategy": strategy.name,
                    "position": position,
                },
            )
        )

    def remove_strategy(self, strategy_name: str) -> bool:
        """Remove a processing strategy from the pipeline."""
        result = self._pipeline.remove_strategy(strategy_name)
        if result:
            self.event_bus.emit(
                Event(
                    EventType.CONFIG_UPDATED,
                    {
                        "module": "cdc_capture",
                        "action": "strategy_removed",
                        "strategy": strategy_name,
                    },
                )
            )
        return result

    def get_strategy(self, strategy_name: str) -> Optional[ProcessingStrategy]:
        """Get a strategy from the pipeline by name."""
        return self._pipeline.get_strategy(strategy_name)

    def list_strategies(self) -> list[tuple[str, str]]:
        """List all strategies in the pipeline."""
        return self._pipeline.list_strategies()

    def clear_strategies(self) -> None:
        """Clear all strategies from the pipeline."""
        self._pipeline.clear()
        self.event_bus.emit(
            Event(
                EventType.CONFIG_UPDATED,
                {
                    "module": "cdc_capture",
                    "action": "strategies_cleared",
                },
            )
        )

    def create_strategy(self, strategy_name: str, **kwargs: Any) -> Optional[ProcessingStrategy]:
        """Create a strategy instance from the registry."""
        return StrategyRegistry.create(strategy_name, **kwargs)

    def get_available_strategies(self) -> list[str]:
        """Get list of available strategy types from registry."""
        return StrategyRegistry.list_names()

    def get_pipeline_stats(self) -> dict[str, Any]:
        """Get pipeline processing statistics."""
        ctx = self._pipeline.get_context()
        return {
            "event_count": ctx.event_count,
            "batch_count": ctx.batch_count,
            "error_count": ctx.error_count,
            "first_event_time": ctx.first_event_time.isoformat() if ctx.first_event_time else None,
            "last_event_time": ctx.last_event_time.isoformat() if ctx.last_event_time else None,
            "strategies": self.list_strategies(),
        }

    async def start_capture(
        self,
        event_handler: Optional[Callable[[CDCEvent], Any]] = None,
        include_tables: Optional[list[str]] = None,
        exclude_tables: Optional[list[str]] = None,
        include_operations: Optional[list[OperationType]] = None,
    ) -> AsyncGenerator[CDCEvent, None]:
        self._running = True
        self.event_bus.emit(
            Event(EventType.TASK_STARTED, {"module": "cdc_capture", "datasource": self.ds_name})
        )

        if self.output_adapter is None:
            self.output_adapter = ConsoleOutputAdapter()
        if self.serializer is None:
            self.serializer = SerializerFactory.get_serializer("json")

        await self._pipeline.start()

        event_count = 0
        batch: list[CDCEvent] = []

        try:
            async for raw_event in self._stream_raw_events():
                if not self._running:
                    break

                try:
                    cdc_event = await self._parse_and_filter(
                        raw_event, include_tables, exclude_tables, include_operations
                    )
                    if cdc_event is None:
                        continue

                    processed_event = await self._pipeline.process_event(cdc_event)
                    if processed_event is None:
                        continue

                    event_count += 1
                    batch.append(processed_event)

                    if event_handler:
                        result = event_handler(processed_event)
                        if asyncio.iscoroutine(result):
                            await result

                    if len(batch) >= self.batch_size:
                        await self._process_batch(batch)
                        batch = []

                    yield processed_event

                except Exception as e:
                    self.context.add_error("cdc_capture", str(e))
                    ctx = self._pipeline.get_context()
                    ctx.record_error()
                    continue

            if batch:
                await self._process_batch(batch)

            await self._pipeline.stop()

            self.event_bus.emit(
                Event(
                    EventType.TASK_COMPLETED,
                    {
                        "module": "cdc_capture",
                        "events_captured": event_count,
                        "pipeline_stats": self.get_pipeline_stats(),
                    },
                )
            )

        except Exception as e:
            await self._pipeline.stop()
            self.event_bus.emit(
                Event(EventType.TASK_FAILED, {"module": "cdc_capture", "error": str(e)})
            )
            raise CDCCaptureError(self.ds_name, "capture", str(e)) from e

    async def capture_once(
        self,
        include_tables: Optional[list[str]] = None,
        exclude_tables: Optional[list[str]] = None,
        include_operations: Optional[list[OperationType]] = None,
    ) -> list[CDCEvent]:
        events: list[CDCEvent] = []
        async for event in self.start_capture(
            include_tables=include_tables,
            exclude_tables=exclude_tables,
            include_operations=include_operations,
        ):
            events.append(event)
            if len(events) >= self.batch_size:
                break
        self.stop()
        return events

    def stop(self) -> None:
        self._running = False

    async def _stream_raw_events(self) -> AsyncGenerator[dict[str, Any], None]:
        mock_events = self._generate_mock_events()
        for event in mock_events:
            yield event
            await asyncio.sleep(0.001)

    def _generate_mock_events(self) -> list[dict[str, Any]]:
        events: list[dict[str, Any]] = []
        timestamp = int(datetime.utcnow().timestamp())

        for i in range(5):
            events.append({
                "event_type": "WRITE_ROWS_EVENT_V2",
                "database": "test_db",
                "table": "users",
                "timestamp": timestamp - (5 - i) * 60,
                "file": "mysql-bin.000001",
                "position": 1000 + i * 100,
                "after": {
                    "id": i + 1,
                    "name": f"User{i + 1}",
                    "email": f"user{i + 1}@example.com",
                    "age": 20 + i * 5,
                    "created_at": "2024-01-01",
                },
            })

        for i in range(3):
            events.append({
                "event_type": "UPDATE_ROWS_EVENT_V2",
                "database": "test_db",
                "table": "users",
                "timestamp": timestamp - (3 - i) * 30,
                "file": "mysql-bin.000001",
                "position": 2000 + i * 100,
                "before": {"id": i + 1, "name": f"User{i + 1}", "age": 20 + i * 5},
                "after": {"id": i + 1, "name": f"User{i + 1}_updated", "age": 21 + i * 5},
            })

        events.append({
            "event_type": "DELETE_ROWS_EVENT_V2",
            "database": "test_db",
            "table": "users",
            "timestamp": timestamp - 5,
            "file": "mysql-bin.000001",
            "position": 3000,
            "before": {"id": 1, "name": "User1_updated", "age": 21},
        })

        for i in range(3):
            events.append({
                "event_type": "WRITE_ROWS_EVENT_V2",
                "database": "test_db",
                "table": "orders",
                "timestamp": timestamp - (3 - i) * 15,
                "file": "mysql-bin.000001",
                "position": 4000 + i * 100,
                "after": {
                    "id": i + 1,
                    "user_id": i + 1,
                    "amount": 99.99 * (i + 1),
                    "status": "completed",
                    "created_at": "2024-01-10",
                },
            })

        return events

    async def _parse_and_filter(
        self,
        raw_event: dict[str, Any],
        include_tables: Optional[list[str]],
        exclude_tables: Optional[list[str]],
        include_operations: Optional[list[OperationType]],
    ) -> Optional[CDCEvent]:
        for attempt in range(self.retry_attempts):
            try:
                if self.ds_type == "mysql":
                    event = self.parser.parse_mysql_binlog(raw_event)
                elif self.ds_type == "postgresql":
                    event = self.parser.parse_postgresql_wal(raw_event)
                else:
                    event = self.parser.parse_custom(raw_event)

                if self.parser.filter_event(event, include_tables, exclude_tables, include_operations):
                    self._last_position = {
                        "file": raw_event.get("file"),
                        "position": raw_event.get("position"),
                        "timestamp": event.timestamp.isoformat(),
                    }
                    return event

                return None

            except Exception as e:
                if attempt == self.retry_attempts - 1:
                    raise e
                await asyncio.sleep(0.01 * (attempt + 1))

        return None

    async def _process_batch(self, batch: list[CDCEvent]) -> None:
        if self.output_adapter and self.serializer:
            self.output_adapter.serializer = self.serializer
            await self.output_adapter.send_batch(batch)

        self.event_bus.emit(
            Event(
                EventType.CDC_EVENT,
                {"source": self.ds_name, "batch_size": len(batch), "tables": list({e.table for e in batch})},
            )
        )

        self.context.add_metric("events_processed", len(batch))

    def get_last_position(self) -> dict[str, Any]:
        return self._last_position.copy()

    def is_running(self) -> bool:
        return self._running
