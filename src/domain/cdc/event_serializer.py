import json
import logging
import hashlib
import time
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional, Union

logger = logging.getLogger(__name__)


class SerializationFormat(Enum):
    JSON = "JSON"
    AVRO = "AVRO"
    PROTOBUF = "PROTOBUF"
    DEBEZIUM_JSON = "DEBEZIUM_JSON"


class CDCOperation(Enum):
    CREATE = "c"
    UPDATE = "u"
    DELETE = "d"
    SNAPSHOT = "r"
    READ = "r"


@dataclass
class CDCEvent:
    event_id: str = ""
    operation: CDCOperation = CDCOperation.CREATE
    source_database: str = ""
    source_schema: str = ""
    source_table: str = ""
    timestamp: int = 0
    before: Dict[str, Any] = field(default_factory=dict)
    after: Dict[str, Any] = field(default_factory=dict)
    changed_columns: List[str] = field(default_factory=list)
    transaction_id: str = ""
    lsn: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def datetime(self) -> datetime:
        return datetime.utcfromtimestamp(self.timestamp / 1000) if self.timestamp else datetime.utcnow()

    @property
    def topic_name(self) -> str:
        return f"{self.source_database}.{self.source_schema}.{self.source_table}"


class CDCEventSerializer:
    def __init__(self, format_type: SerializationFormat = SerializationFormat.DEBEZIUM_JSON):
        self._format = format_type

    def serialize(self, event: CDCEvent) -> bytes:
        if not event.event_id:
            event.event_id = self._generate_event_id(event)

        serializer_map = {
            SerializationFormat.JSON: self._serialize_json,
            SerializationFormat.DEBEZIUM_JSON: self._serialize_debezium_json,
            SerializationFormat.AVRO: self._serialize_json,
            SerializationFormat.PROTOBUF: self._serialize_json,
        }
        serializer = serializer_map.get(self._format, self._serialize_json)
        return serializer(event)

    def deserialize(self, data: bytes) -> CDCEvent:
        deserializer_map = {
            SerializationFormat.JSON: self._deserialize_json,
            SerializationFormat.DEBEZIUM_JSON: self._deserialize_debezium_json,
        }
        deserializer = deserializer_map.get(self._format, self._deserialize_json)
        return deserializer(data)

    def _serialize_json(self, event: CDCEvent) -> bytes:
        payload = {
            "event_id": event.event_id,
            "operation": event.operation.value,
            "source": {
                "database": event.source_database,
                "schema": event.source_schema,
                "table": event.source_table,
            },
            "timestamp": event.timestamp,
            "before": event.before,
            "after": event.after,
            "changed_columns": event.changed_columns,
            "transaction_id": event.transaction_id,
            "lsn": event.lsn,
            "metadata": event.metadata,
        }
        return json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")

    def _serialize_debezium_json(self, event: CDCEvent) -> bytes:
        payload = {
            "schema": {},
            "payload": {
                "before": event.before if event.before else None,
                "after": event.after if event.after else None,
                "source": {
                    "version": "1.0",
                    "connector": "streamsql",
                    "name": "streamsql",
                    "db": event.source_database,
                    "schema": event.source_schema,
                    "table": event.source_table,
                    "ts_ms": event.timestamp,
                    "lsn": event.lsn,
                },
                "op": event.operation.value,
                "ts_ms": int(time.time() * 1000),
                "transaction": {
                    "id": event.transaction_id,
                } if event.transaction_id else None,
            },
        }
        return json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")

    def _deserialize_json(self, data: bytes) -> CDCEvent:
        payload = json.loads(data.decode("utf-8"))
        source = payload.get("source", {})
        return CDCEvent(
            event_id=payload.get("event_id", ""),
            operation=CDCOperation(payload.get("operation", "c")),
            source_database=source.get("database", ""),
            source_schema=source.get("schema", ""),
            source_table=source.get("table", ""),
            timestamp=payload.get("timestamp", 0),
            before=payload.get("before", {}),
            after=payload.get("after", {}),
            changed_columns=payload.get("changed_columns", []),
            transaction_id=payload.get("transaction_id", ""),
            lsn=payload.get("lsn", ""),
            metadata=payload.get("metadata", {}),
        )

    def _deserialize_debezium_json(self, data: bytes) -> CDCEvent:
        payload = json.loads(data.decode("utf-8"))
        inner = payload.get("payload", {})
        source = inner.get("source", {})
        transaction = inner.get("transaction", {}) or {}
        return CDCEvent(
            event_id="",
            operation=CDCOperation(inner.get("op", "c")),
            source_database=source.get("db", ""),
            source_schema=source.get("schema", ""),
            source_table=source.get("table", ""),
            timestamp=source.get("ts_ms", 0),
            before=inner.get("before") or {},
            after=inner.get("after") or {},
            transaction_id=transaction.get("id", ""),
            lsn=source.get("lsn", ""),
            metadata={"connector": source.get("connector", ""), "name": source.get("name", "")},
        )

    def _generate_event_id(self, event: CDCEvent) -> str:
        raw = f"{event.source_database}.{event.source_table}.{event.timestamp}.{event.lsn}.{event.transaction_id}"
        return hashlib.md5(raw.encode("utf-8")).hexdigest()

    def batch_serialize(self, events: List[CDCEvent]) -> List[bytes]:
        return [self.serialize(e) for e in events]

    def batch_deserialize(self, data_list: List[bytes]) -> List[CDCEvent]:
        return [self.deserialize(d) for d in data_list]

    @property
    def format(self) -> SerializationFormat:
        return self._format
