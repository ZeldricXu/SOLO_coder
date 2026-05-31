import json
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Dict, Iterator, List, Optional, Tuple

from src.infrastructure.config.settings import CDCPostgreSQLConfig

logger = logging.getLogger(__name__)


class WALEventType(Enum):
    BEGIN = "BEGIN"
    COMMIT = "COMMIT"
    INSERT = "INSERT"
    UPDATE = "UPDATE"
    DELETE = "DELETE"
    RELATION = "RELATION"
    TYPE = "TYPE"
    ORIGIN = "ORIGIN"
    UNKNOWN = "UNKNOWN"


@dataclass
class WALEvent:
    event_type: WALEventType = WALEventType.UNKNOWN
    lsn: str = ""
    transaction_id: str = ""
    timestamp: int = 0
    database: str = ""
    schema: str = ""
    table: str = ""
    before_data: Dict[str, Any] = field(default_factory=dict)
    after_data: Dict[str, Any] = field(default_factory=dict)
    changed_columns: List[str] = field(default_factory=list)
    raw_payload: str = ""

    @property
    def datetime(self) -> datetime:
        return datetime.utcfromtimestamp(self.timestamp) if self.timestamp else datetime.utcnow()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_type": self.event_type.value,
            "lsn": self.lsn,
            "transaction_id": self.transaction_id,
            "timestamp": self.timestamp,
            "datetime": self.datetime.isoformat(),
            "database": self.database,
            "schema": self.schema,
            "table": self.table,
            "before_data": self.before_data,
            "after_data": self.after_data,
            "changed_columns": self.changed_columns,
        }


@dataclass
class RelationInfo:
    schema: str
    table: str
    columns: List[Dict[str, Any]] = field(default_factory=list)
    relation_id: int = 0


class WALParser:
    BEGIN_PATTERN = re.compile(r"^BEGIN\s+(\d+)", re.IGNORECASE)
    COMMIT_PATTERN = re.compile(r"^COMMIT\s+(\d+)", re.IGNORECASE)
    RELATION_PATTERN = re.compile(r"^RELATION\s+(\d+)\s+public\s+(\w+)\s*\.\s*(\w+)", re.IGNORECASE)
    INSERT_PATTERN = re.compile(r"^table\s+public\.(\w+):\s+INSERT:\s+(.+)$", re.IGNORECASE)
    UPDATE_PATTERN = re.compile(r"^table\s+public\.(\w+):\s+UPDATE:\s+(.+?)(?:\s+WHERE\s+(.+))?$", re.IGNORECASE)
    DELETE_PATTERN = re.compile(r"^table\s+public\.(\w+):\s+DELETE:\s+(.+)$", re.IGNORECASE)
    KEY_VALUE_PATTERN = re.compile(r"(\w+)\[(\w+)\]:(\w+|NULL)")
    LSN_PATTERN = re.compile(r"^([0-9A-Fa-f]+/[0-9A-Fa-f]+)")

    def __init__(self, config: CDCPostgreSQLConfig):
        self._config = config
        self._relations: Dict[int, RelationInfo] = {}
        self._current_lsn: str = ""
        self._current_transaction: str = ""

    def parse_message(self, message: str) -> Optional[WALEvent]:
        message = message.strip()
        if not message:
            return None

        lsn_match = self.LSN_PATTERN.match(message)
        if lsn_match:
            self._current_lsn = lsn_match.group(1)

        begin_match = self.BEGIN_PATTERN.match(message)
        if begin_match:
            self._current_transaction = begin_match.group(1)
            return WALEvent(
                event_type=WALEventType.BEGIN,
                lsn=self._current_lsn,
                transaction_id=self._current_transaction,
            )

        commit_match = self.COMMIT_PATTERN.match(message)
        if commit_match:
            event = WALEvent(
                event_type=WALEventType.COMMIT,
                lsn=self._current_lsn,
                transaction_id=commit_match.group(1),
            )
            self._current_transaction = ""
            return event

        insert_match = self.INSERT_PATTERN.match(message)
        if insert_match:
            table = insert_match.group(1)
            data_str = insert_match.group(2)
            after_data = self._parse_key_values(data_str)
            return WALEvent(
                event_type=WALEventType.INSERT,
                lsn=self._current_lsn,
                transaction_id=self._current_transaction,
                database=self._config.database,
                schema="public",
                table=table,
                after_data=after_data,
            )

        update_match = self.UPDATE_PATTERN.match(message)
        if update_match:
            table = update_match.group(1)
            new_data_str = update_match.group(2)
            old_data_str = update_match.group(3)
            after_data = self._parse_key_values(new_data_str)
            before_data = self._parse_key_values(old_data_str) if old_data_str else {}
            changed = [k for k in after_data if after_data.get(k) != before_data.get(k)]
            return WALEvent(
                event_type=WALEventType.UPDATE,
                lsn=self._current_lsn,
                transaction_id=self._current_transaction,
                database=self._config.database,
                schema="public",
                table=table,
                before_data=before_data,
                after_data=after_data,
                changed_columns=changed,
            )

        delete_match = self.DELETE_PATTERN.match(message)
        if delete_match:
            table = delete_match.group(1)
            data_str = delete_match.group(2)
            before_data = self._parse_key_values(data_str)
            return WALEvent(
                event_type=WALEventType.DELETE,
                lsn=self._current_lsn,
                transaction_id=self._current_transaction,
                database=self._config.database,
                schema="public",
                table=table,
                before_data=before_data,
            )

        return WALEvent(
            event_type=WALEventType.UNKNOWN,
            lsn=self._current_lsn,
            raw_payload=message,
        )

    def parse_stream(self, messages: Iterator[str]) -> Iterator[WALEvent]:
        for msg in messages:
            event = self.parse_message(msg)
            if event is not None:
                yield event

    def _parse_key_values(self, data_str: str) -> Dict[str, Any]:
        result = {}
        if not data_str:
            return result

        pairs = re.split(r"\s+", data_str.strip())
        for pair in pairs:
            match = self.KEY_VALUE_PATTERN.match(pair)
            if match:
                col_name = match.group(1)
                col_type = match.group(2)
                value = match.group(3)
                if value == "NULL":
                    result[col_name] = None
                else:
                    result[col_name] = self._cast_value(value, col_type)

        return result

    def _cast_value(self, value: str, type_hint: str) -> Any:
        type_lower = type_hint.lower()
        try:
            if type_lower in ("int2", "int4", "int8", "integer", "bigint", "smallint"):
                return int(value)
            elif type_lower in ("float4", "float8", "numeric", "decimal", "real", "double precision"):
                return float(value)
            elif type_lower in ("bool", "boolean"):
                return value.lower() in ("t", "true", "1", "yes")
            elif type_lower in ("text", "varchar", "char", "bpchar", "name"):
                return value
            elif type_lower in ("timestamp", "timestamptz", "date", "time", "timetz"):
                return value
            elif type_lower in ("json", "jsonb"):
                return json.loads(value)
            else:
                return value
        except (ValueError, json.JSONDecodeError):
            return value

    def get_current_lsn(self) -> str:
        return self._current_lsn

    def get_relations(self) -> Dict[int, RelationInfo]:
        return self._relations.copy()
