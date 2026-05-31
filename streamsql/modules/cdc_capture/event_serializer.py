from __future__ import annotations

import json
import pickle
from abc import ABC, abstractmethod
from typing import Any

import zstandard as zstd

from streamsql.modules.cdc_capture.binlog_parser import CDCEvent


class EventSerializer(ABC):
    @abstractmethod
    def serialize(self, event: CDCEvent) -> bytes: ...

    @abstractmethod
    def deserialize(self, data: bytes) -> CDCEvent: ...

    @abstractmethod
    def serialize_batch(self, events: list[CDCEvent]) -> bytes: ...

    @abstractmethod
    def deserialize_batch(self, data: bytes) -> list[CDCEvent]: ...


class JSONEventSerializer(EventSerializer):
    def serialize(self, event: CDCEvent) -> bytes:
        return json.dumps(self._event_to_dict(event)).encode("utf-8")

    def deserialize(self, data: bytes) -> CDCEvent:
        data_dict = json.loads(data.decode("utf-8"))
        return self._dict_to_event(data_dict)

    def serialize_batch(self, events: list[CDCEvent]) -> bytes:
        return json.dumps([self._event_to_dict(e) for e in events]).encode("utf-8")

    def deserialize_batch(self, data: bytes) -> list[CDCEvent]:
        events_list = json.loads(data.decode("utf-8"))
        return [self._dict_to_event(d) for d in events_list]

    def _event_to_dict(self, event: CDCEvent) -> dict[str, Any]:
        d = event.model_dump()
        d["timestamp"] = event.timestamp.isoformat()
        return d

    def _dict_to_event(self, data: dict[str, Any]) -> CDCEvent:
        from datetime import datetime
        data["timestamp"] = datetime.fromisoformat(data["timestamp"])
        return CDCEvent(**data)


class CompressedJSONSerializer(EventSerializer):
    def __init__(self, compression_level: int = 3):
        self.json_serializer = JSONEventSerializer()
        self.compression_level = compression_level
        self._cctx = zstd.ZstdCompressor(level=compression_level)
        self._dctx = zstd.ZstdDecompressor()

    def serialize(self, event: CDCEvent) -> bytes:
        json_data = self.json_serializer.serialize(event)
        return self._cctx.compress(json_data)

    def deserialize(self, data: bytes) -> CDCEvent:
        json_data = self._dctx.decompress(data)
        return self.json_serializer.deserialize(json_data)

    def serialize_batch(self, events: list[CDCEvent]) -> bytes:
        json_data = self.json_serializer.serialize_batch(events)
        return self._cctx.compress(json_data)

    def deserialize_batch(self, data: bytes) -> list[CDCEvent]:
        json_data = self._dctx.decompress(data)
        return self.json_serializer.deserialize_batch(json_data)


class AvroEventSerializer(EventSerializer):
    def __init__(self, schema: dict[str, Any] | None = None):
        self.schema = schema or self._default_schema()

    def _default_schema(self) -> dict[str, Any]:
        return {
            "type": "record",
            "name": "CDCEvent",
            "fields": [
                {"name": "event_id", "type": "string"},
                {"name": "source", "type": "string"},
                {"name": "database", "type": "string"},
                {"name": "table", "type": "string"},
                {"name": "operation", "type": "string"},
                {"name": "timestamp", "type": "long"},
                {"name": "before", "type": ["null", "string"], "default": None},
                {"name": "after", "type": ["null", "string"], "default": None},
                {"name": "metadata", "type": "string", "default": "{}"},
            ],
        }

    def serialize(self, event: CDCEvent) -> bytes:
        return json.dumps({
            "event_id": event.event_id,
            "source": event.source,
            "database": event.database,
            "table": event.table,
            "operation": event.operation.value,
            "timestamp": int(event.timestamp.timestamp() * 1000),
            "before": json.dumps(event.before) if event.before else None,
            "after": json.dumps(event.after) if event.after else None,
            "metadata": json.dumps(event.metadata),
        }).encode("utf-8")

    def deserialize(self, data: bytes) -> CDCEvent:
        from datetime import datetime
        d = json.loads(data.decode("utf-8"))
        return CDCEvent(
            event_id=d["event_id"],
            source=d["source"],
            database=d["database"],
            table=d["table"],
            operation=d["operation"],
            timestamp=datetime.fromtimestamp(d["timestamp"] / 1000),
            before=json.loads(d["before"]) if d["before"] else None,
            after=json.loads(d["after"]) if d["after"] else None,
            metadata=json.loads(d["metadata"]),
        )

    def serialize_batch(self, events: list[CDCEvent]) -> bytes:
        return json.dumps([{
            "event_id": e.event_id,
            "source": e.source,
            "database": e.database,
            "table": e.table,
            "operation": e.operation.value,
            "timestamp": int(e.timestamp.timestamp() * 1000),
            "before": json.dumps(e.before) if e.before else None,
            "after": json.dumps(e.after) if e.after else None,
            "metadata": json.dumps(e.metadata),
        } for e in events]).encode("utf-8")

    def deserialize_batch(self, data: bytes) -> list[CDCEvent]:
        from datetime import datetime
        items = json.loads(data.decode("utf-8"))
        return [CDCEvent(
            event_id=d["event_id"],
            source=d["source"],
            database=d["database"],
            table=d["table"],
            operation=d["operation"],
            timestamp=datetime.fromtimestamp(d["timestamp"] / 1000),
            before=json.loads(d["before"]) if d["before"] else None,
            after=json.loads(d["after"]) if d["after"] else None,
            metadata=json.loads(d["metadata"]),
        ) for d in items]


class PickleSerializer(EventSerializer):
    def serialize(self, event: CDCEvent) -> bytes:
        return pickle.dumps(event)

    def deserialize(self, data: bytes) -> CDCEvent:
        return pickle.loads(data)

    def serialize_batch(self, events: list[CDCEvent]) -> bytes:
        return pickle.dumps(events)

    def deserialize_batch(self, data: bytes) -> list[CDCEvent]:
        return pickle.loads(data)


class SerializerFactory:
    @staticmethod
    def get_serializer(format_type: str = "json", **kwargs: Any) -> EventSerializer:
        format_lower = format_type.lower()
        if format_lower == "json":
            return JSONEventSerializer()
        elif format_lower in ["compressed_json", "zstd_json"]:
            return CompressedJSONSerializer(**kwargs)
        elif format_lower == "avro":
            return AvroEventSerializer(**kwargs)
        elif format_lower == "pickle":
            return PickleSerializer()
        else:
            return JSONEventSerializer()
