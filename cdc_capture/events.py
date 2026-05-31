import uuid
import time
import json
from enum import Enum
from typing import Any, Dict, Optional, List, Tuple
from dataclasses import dataclass, field, asdict


class EventType(str, Enum):
    DATA = "data"
    SCHEMA_CHANGE = "schema_change"
    TRANSACTION = "transaction"
    HEARTBEAT = "heartbeat"


class OperationType(str, Enum):
    INSERT = "INSERT"
    UPDATE = "UPDATE"
    DELETE = "DELETE"
    CREATE = "CREATE"
    ALTER = "ALTER"
    DROP = "DROP"
    TRUNCATE = "TRUNCATE"
    BEGIN = "BEGIN"
    COMMIT = "COMMIT"
    ROLLBACK = "ROLLBACK"


@dataclass
class EventMetadata:
    event_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    timestamp: float = field(default_factory=time.time)
    source: str = ""
    database: str = ""
    schema: str = ""
    table: str = ""
    binlog_position: str = ""
    binlog_file: str = ""
    gtid: str = ""
    lsn: int = 0
    xid: int = 0
    server_id: int = 0
    thread_id: int = 0
    commit_timestamp: float = 0.0
    capture_timestamp: float = field(default_factory=time.time)
    offset: int = 0
    partition: int = 0
    headers: Dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "EventMetadata":
        return cls(**data)


@dataclass
class CDCEvent:
    metadata: EventMetadata
    event_type: EventType

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_type": self.event_type.value,
            "metadata": self.metadata.to_dict(),
            "payload": self.get_payload(),
        }

    def get_payload(self) -> Dict[str, Any]:
        raise NotImplementedError

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False, default=str)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "CDCEvent":
        event_type = EventType(data["event_type"])
        metadata = EventMetadata.from_dict(data["metadata"])
        payload = data["payload"]

        if event_type == EventType.DATA:
            op_type = OperationType(payload.get("operation", "INSERT"))
            if op_type == OperationType.INSERT:
                return InsertEvent(
                    metadata=metadata,
                    table=payload.get("table", ""),
                    schema=payload.get("schema", ""),
                    new_data=payload.get("new_data", {}),
                    columns=payload.get("columns", []),
                )
            elif op_type == OperationType.UPDATE:
                return UpdateEvent(
                    metadata=metadata,
                    table=payload.get("table", ""),
                    schema=payload.get("schema", ""),
                    old_data=payload.get("old_data", {}),
                    new_data=payload.get("new_data", {}),
                    updated_columns=payload.get("updated_columns", []),
                    columns=payload.get("columns", []),
                )
            elif op_type == OperationType.DELETE:
                return DeleteEvent(
                    metadata=metadata,
                    table=payload.get("table", ""),
                    schema=payload.get("schema", ""),
                    old_data=payload.get("old_data", {}),
                    columns=payload.get("columns", []),
                )
        elif event_type == EventType.SCHEMA_CHANGE:
            return SchemaChangeEvent(
                metadata=metadata,
                operation=OperationType(payload.get("operation", "ALTER")),
                schema_name=payload.get("schema_name", ""),
                table_name=payload.get("table_name", ""),
                ddl_sql=payload.get("ddl_sql", ""),
                old_schema=payload.get("old_schema", {}),
                new_schema=payload.get("new_schema", {}),
            )
        elif event_type == EventType.TRANSACTION:
            return TransactionEvent(
                metadata=metadata,
                operation=OperationType(payload.get("operation", "BEGIN")),
                transaction_id=payload.get("transaction_id", ""),
                events_count=payload.get("events_count", 0),
            )
        elif event_type == EventType.HEARTBEAT:
            return HeartbeatEvent(
                metadata=metadata,
                interval_ms=payload.get("interval_ms", 3000),
            )

        raise ValueError(f"Unknown event type: {event_type}")


@dataclass
class DataEvent(CDCEvent):
    table: str
    schema: str
    columns: List[str] = field(default_factory=list)
    event_type: EventType = EventType.DATA
    operation: OperationType = OperationType.INSERT

    def get_payload(self) -> Dict[str, Any]:
        return {
            "operation": self.operation.value,
            "table": self.table,
            "schema": self.schema,
            "columns": self.columns,
        }


@dataclass
class InsertEvent(DataEvent):
    new_data: Dict[str, Any] = field(default_factory=dict)
    operation: OperationType = OperationType.INSERT

    def get_payload(self) -> Dict[str, Any]:
        payload = super().get_payload()
        payload["new_data"] = self.new_data
        return payload


@dataclass
class UpdateEvent(DataEvent):
    old_data: Dict[str, Any] = field(default_factory=dict)
    new_data: Dict[str, Any] = field(default_factory=dict)
    updated_columns: List[str] = field(default_factory=list)
    operation: OperationType = OperationType.UPDATE

    def get_payload(self) -> Dict[str, Any]:
        payload = super().get_payload()
        payload["old_data"] = self.old_data
        payload["new_data"] = self.new_data
        payload["updated_columns"] = self.updated_columns
        return payload


@dataclass
class DeleteEvent(DataEvent):
    old_data: Dict[str, Any] = field(default_factory=dict)
    operation: OperationType = OperationType.DELETE

    def get_payload(self) -> Dict[str, Any]:
        payload = super().get_payload()
        payload["old_data"] = self.old_data
        return payload


@dataclass
class SchemaChangeEvent(CDCEvent):
    operation: OperationType
    schema_name: str
    table_name: str
    ddl_sql: str = ""
    old_schema: Dict[str, Any] = field(default_factory=dict)
    new_schema: Dict[str, Any] = field(default_factory=dict)
    event_type: EventType = EventType.SCHEMA_CHANGE

    def get_payload(self) -> Dict[str, Any]:
        return {
            "operation": self.operation.value,
            "schema_name": self.schema_name,
            "table_name": self.table_name,
            "ddl_sql": self.ddl_sql,
            "old_schema": self.old_schema,
            "new_schema": self.new_schema,
        }


@dataclass
class TransactionEvent(CDCEvent):
    operation: OperationType
    transaction_id: str
    events_count: int = 0
    event_type: EventType = EventType.TRANSACTION

    def get_payload(self) -> Dict[str, Any]:
        return {
            "operation": self.operation.value,
            "transaction_id": self.transaction_id,
            "events_count": self.events_count,
        }


@dataclass
class HeartbeatEvent(CDCEvent):
    interval_ms: int = 3000
    event_type: EventType = EventType.HEARTBEAT

    def get_payload(self) -> Dict[str, Any]:
        return {
            "interval_ms": self.interval_ms,
        }


def create_event_metadata(
    source: str,
    database: str,
    table: str,
    binlog_file: str = "",
    binlog_position: str = "",
    gtid: str = "",
    lsn: int = 0,
    xid: int = 0,
) -> EventMetadata:
    return EventMetadata(
        source=source,
        database=database,
        table=table,
        binlog_file=binlog_file,
        binlog_position=binlog_position,
        gtid=gtid,
        lsn=lsn,
        xid=xid,
        commit_timestamp=time.time(),
    )
