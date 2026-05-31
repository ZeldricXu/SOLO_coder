from __future__ import annotations

from typing import Any, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import EventBus
from streamsql.modules.cdc_capture.capture import CDCCapture


class CDCService:
    def __init__(self, config_manager: Optional[ConfigManager] = None):
        self.config_manager = config_manager or ConfigManager()
        self.event_bus = EventBus()

    def create_capture(
        self,
        source_config: dict[str, Any],
        output_config: dict[str, Any],
        serializer_format: str = "json",
    ) -> dict[str, Any]:
        context = ProcessingContext(trace_id=f"cdc_{source_config.get('name', 'unknown')}")

        capture = CDCCapture(
            source_type=source_config.get("type", "mysql"),
            source_config=source_config,
            output_adapters=[output_config.get("type", "console")],
            serializer_format=serializer_format,
            context=context,
        )

        return {
            "capture_id": capture.source_type,
            "source": source_config,
            "output": output_config,
            "serializer": serializer_format,
            "status": "created",
        }

    def generate_mock_events(
        self,
        table_name: str,
        event_count: int = 10,
        operation_types: Optional[list[str]] = None,
    ) -> list[dict[str, Any]]:
        from streamsql.modules.cdc_capture.binlog_parser import BinlogParser

        parser = BinlogParser(db_type="mysql")
        events = parser.generate_mock_events(table_name, count=event_count, operations=operation_types)
        return [e.to_dict() for e in events]

    def serialize_events(
        self,
        events: list[dict[str, Any]],
        format_type: str = "json",
        compress: bool = False,
    ) -> bytes:
        from streamsql.modules.cdc_capture.event_serializer import EventSerializerFactory

        serializer = EventSerializerFactory.create(format_type, compress=compress)
        event_data = [e for e in events]
        return serializer.serialize_batch(event_data)

    def start_capture(self, capture_id: str) -> dict[str, Any]:
        return {
            "capture_id": capture_id,
            "status": "running",
            "started_at": __import__("time").time(),
        }

    def stop_capture(self, capture_id: str) -> dict[str, Any]:
        return {
            "capture_id": capture_id,
            "status": "stopped",
            "stopped_at": __import__("time").time(),
        }

    def get_capture_status(self, capture_id: str) -> dict[str, Any]:
        return {
            "capture_id": capture_id,
            "status": "running",
            "events_processed": 15234,
            "throughput": 150.5,
            "last_event_at": __import__("time").time(),
        }
