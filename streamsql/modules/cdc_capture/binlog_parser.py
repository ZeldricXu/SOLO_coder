from __future__ import annotations

import json
from datetime import datetime
from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field

from streamsql.core.exceptions import CDCCaptureError


class OperationType(str, Enum):
    INSERT = "insert"
    UPDATE = "update"
    DELETE = "delete"
    CREATE = "create"
    ALTER = "alter"
    DROP = "drop"
    TRUNCATE = "truncate"


class CDCEvent(BaseModel):
    event_id: str
    source: str
    database: str
    table: str
    operation: OperationType
    timestamp: datetime
    before: Optional[dict[str, Any]] = None
    after: Optional[dict[str, Any]] = None
    schema: Optional[dict[str, Any]] = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class BinlogPosition(BaseModel):
    file: str
    position: int
    gtid: Optional[str] = None
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class BinlogParser:
    def __init__(self, source_name: str):
        self.source_name = source_name
        self._event_counter = 0

    def parse_mysql_binlog(self, raw_event: dict[str, Any]) -> CDCEvent:
        try:
            event_type = raw_event.get("event_type", "").upper()
            table = raw_event.get("table", "")
            database = raw_event.get("database", "")

            operation = self._map_mysql_operation(event_type)

            before = raw_event.get("before")
            after = raw_event.get("after")

            return CDCEvent(
                event_id=self._generate_event_id(),
                source=self.source_name,
                database=database,
                table=table,
                operation=operation,
                timestamp=datetime.fromtimestamp(raw_event.get("timestamp", datetime.utcnow().timestamp())),
                before=before,
                after=after,
                schema=raw_event.get("schema"),
                metadata={
                    "binlog_file": raw_event.get("file"),
                    "binlog_position": raw_event.get("position"),
                    "server_id": raw_event.get("server_id"),
                },
            )
        except Exception as e:
            raise CDCCaptureError(self.source_name, "parse", f"Failed to parse MySQL binlog: {e}") from e

    def parse_postgresql_wal(self, raw_event: dict[str, Any]) -> CDCEvent:
        try:
            action = raw_event.get("action", "").upper()
            schema = raw_event.get("schema", "")
            table = raw_event.get("table", "")

            operation = self._map_pg_operation(action)

            data = raw_event.get("data", {})
            old_data = raw_event.get("old_data", {})

            return CDCEvent(
                event_id=self._generate_event_id(),
                source=self.source_name,
                database=raw_event.get("database", ""),
                table=f"{schema}.{table}",
                operation=operation,
                timestamp=datetime.fromisoformat(raw_event.get("timestamp", datetime.utcnow().isoformat())),
                before=old_data if operation in [OperationType.UPDATE, OperationType.DELETE] else None,
                after=data if operation in [OperationType.INSERT, OperationType.UPDATE] else None,
                schema=raw_event.get("columns"),
                metadata={
                    "lsn": raw_event.get("lsn"),
                    "xid": raw_event.get("xid"),
                },
            )
        except Exception as e:
            raise CDCCaptureError(self.source_name, "parse", f"Failed to parse PostgreSQL WAL: {e}") from e

    def parse_kafka_message(self, raw_message: dict[str, Any]) -> CDCEvent:
        try:
            payload = raw_message.get("payload", {})
            source = payload.get("source", {})

            op_code = payload.get("op", "")
            operation = self._map_debezium_operation(op_code)

            return CDCEvent(
                event_id=self._generate_event_id(),
                source=self.source_name,
                database=source.get("db", ""),
                table=source.get("table", ""),
                operation=operation,
                timestamp=datetime.fromtimestamp(payload.get("ts_ms", 0) / 1000),
                before=payload.get("before"),
                after=payload.get("after"),
                schema=raw_message.get("schema"),
                metadata={
                    "source": source,
                    "partition": raw_message.get("partition"),
                    "offset": raw_message.get("offset"),
                },
            )
        except Exception as e:
            raise CDCCaptureError(self.source_name, "parse", f"Failed to parse Kafka message: {e}") from e

    def parse_custom(self, raw_event: dict[str, Any]) -> CDCEvent:
        try:
            return CDCEvent(
                event_id=raw_event.get("event_id", self._generate_event_id()),
                source=raw_event.get("source", self.source_name),
                database=raw_event.get("database", ""),
                table=raw_event.get("table", ""),
                operation=OperationType(raw_event.get("operation", "insert")),
                timestamp=raw_event.get("timestamp", datetime.utcnow()),
                before=raw_event.get("before"),
                after=raw_event.get("after"),
                schema=raw_event.get("schema"),
                metadata=raw_event.get("metadata", {}),
            )
        except Exception as e:
            raise CDCCaptureError(self.source_name, "parse", f"Failed to parse custom event: {e}") from e

    def _map_mysql_operation(self, event_type: str) -> OperationType:
        mapping = {
            "WRITE_ROWS_EVENT_V1": OperationType.INSERT,
            "WRITE_ROWS_EVENT_V2": OperationType.INSERT,
            "UPDATE_ROWS_EVENT_V1": OperationType.UPDATE,
            "UPDATE_ROWS_EVENT_V2": OperationType.UPDATE,
            "DELETE_ROWS_EVENT_V1": OperationType.DELETE,
            "DELETE_ROWS_EVENT_V2": OperationType.DELETE,
            "QUERY_EVENT": OperationType.ALTER,
            "CREATE_TABLE": OperationType.CREATE,
            "DROP_TABLE": OperationType.DROP,
            "TRUNCATE_TABLE": OperationType.TRUNCATE,
        }
        return mapping.get(event_type, OperationType.INSERT)

    def _map_pg_operation(self, action: str) -> OperationType:
        mapping = {
            "I": OperationType.INSERT,
            "U": OperationType.UPDATE,
            "D": OperationType.DELETE,
            "C": OperationType.CREATE,
            "A": OperationType.ALTER,
            "T": OperationType.TRUNCATE,
        }
        return mapping.get(action, OperationType.INSERT)

    def _map_debezium_operation(self, op_code: str) -> OperationType:
        mapping = {
            "c": OperationType.INSERT,
            "r": OperationType.INSERT,
            "u": OperationType.UPDATE,
            "d": OperationType.DELETE,
            "t": OperationType.TRUNCATE,
        }
        return mapping.get(op_code, OperationType.INSERT)

    def _generate_event_id(self) -> str:
        self._event_counter += 1
        return f"cdc_{self.source_name}_{self._event_counter}_{datetime.utcnow().strftime('%Y%m%d%H%M%S')}"

    def filter_event(self, event: CDCEvent, include_tables: Optional[list[str]] = None,
                     exclude_tables: Optional[list[str]] = None,
                     include_operations: Optional[list[OperationType]] = None) -> bool:
        if include_tables and event.table not in include_tables:
            return False

        if exclude_tables and event.table in exclude_tables:
            return False

        if include_operations and event.operation not in include_operations:
            return False

        return True

    def batch_parse(self, events: list[dict[str, Any]], source_type: str = "custom") -> list[CDCEvent]:
        parsed: list[CDCEvent] = []
        for raw_event in events:
            try:
                if source_type == "mysql":
                    parsed.append(self.parse_mysql_binlog(raw_event))
                elif source_type == "postgresql":
                    parsed.append(self.parse_postgresql_wal(raw_event))
                elif source_type == "kafka":
                    parsed.append(self.parse_kafka_message(raw_event))
                else:
                    parsed.append(self.parse_custom(raw_event))
            except Exception:
                continue
        return parsed
