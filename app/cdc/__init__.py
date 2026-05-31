"""
CDC (Change Data Capture) Module.
Parses database binlog/WAL, serializes events, and outputs to adapters.
"""

import asyncio
import json
from abc import ABC, abstractmethod
from dataclasses import dataclass, field, asdict
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional, Callable, AsyncIterator

from app.logging import get_logger


class ChangeType(str, Enum):
    INSERT = "insert"
    UPDATE = "update"
    DELETE = "delete"
    DDL = "ddl"
    TRANSACTION_BEGIN = "transaction_begin"
    TRANSACTION_COMMIT = "transaction_commit"
    TRANSACTION_ROLLBACK = "transaction_rollback"


class DatabaseType(str, Enum):
    MYSQL = "mysql"
    POSTGRESQL = "postgresql"
    MONGODB = "mongodb"


@dataclass
class ColumnValue:
    name: str
    value: Any
    data_type: str
    is_primary_key: bool = False


@dataclass
class ChangeEvent:
    event_id: str
    database: str
    table: str
    change_type: ChangeType
    timestamp: datetime = field(default_factory=datetime.utcnow)
    primary_keys: Dict[str, Any] = field(default_factory=dict)
    before: List[ColumnValue] = field(default_factory=list)
    after: List[ColumnValue] = field(default_factory=list)
    transaction_id: Optional[str] = None
    sequence: Optional[int] = None
    source_type: DatabaseType = DatabaseType.MYSQL
    metadata: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_id": self.event_id,
            "database": self.database,
            "table": self.table,
            "change_type": self.change_type.value,
            "timestamp": self.timestamp.isoformat(),
            "primary_keys": self.primary_keys,
            "before": [
                {
                    "name": c.name,
                    "value": c.value,
                    "data_type": c.data_type,
                    "is_primary_key": c.is_primary_key
                }
                for c in self.before
            ],
            "after": [
                {
                    "name": c.name,
                    "value": c.value,
                    "data_type": c.data_type,
                    "is_primary_key": c.is_primary_key
                }
                for c in self.after
            ],
            "transaction_id": self.transaction_id,
            "sequence": self.sequence,
            "source_type": self.source_type.value,
            "metadata": self.metadata
        }


class EventSerializer(ABC):
    @abstractmethod
    def serialize(self, event: ChangeEvent) -> bytes:
        pass
    
    @abstractmethod
    def deserialize(self, data: bytes) -> ChangeEvent:
        pass


class JsonEventSerializer(EventSerializer):
    def serialize(self, event: ChangeEvent) -> bytes:
        return json.dumps(event.to_dict(), ensure_ascii=False).encode("utf-8")
    
    def deserialize(self, data: bytes) -> ChangeEvent:
        data_dict = json.loads(data.decode("utf-8"))
        return ChangeEvent(
            event_id=data_dict["event_id"],
            database=data_dict["database"],
            table=data_dict["table"],
            change_type=ChangeType(data_dict["change_type"]),
            timestamp=datetime.fromisoformat(data_dict["timestamp"]),
            primary_keys=data_dict["primary_keys"],
            before=[
                ColumnValue(**c) for c in data_dict["before"]
            ],
            after=[
                ColumnValue(**c) for c in data_dict["after"]
            ],
            transaction_id=data_dict.get("transaction_id"),
            sequence=data_dict.get("sequence"),
            source_type=DatabaseType(data_dict["source_type"]),
            metadata=data_dict.get("metadata", {})
        )


class OutputAdapter(ABC):
    @abstractmethod
    async def send(self, event: ChangeEvent):
        pass
    
    @abstractmethod
    async def send_batch(self, events: List[ChangeEvent]):
        pass


class InMemoryOutputAdapter(OutputAdapter):
    def __init__(self, max_size: int = 10000):
        self._events: List[ChangeEvent] = []
        self._max_size = max_size
        self._lock = asyncio.Lock()
    
    async def send(self, event: ChangeEvent):
        async with self._lock:
            self._events.append(event)
            if len(self._events) > self._max_size:
                self._events = self._events[-self._max_size:]
    
    async def send_batch(self, events: List[ChangeEvent]):
        async with self._lock:
            self._events.extend(events)
            if len(self._events) > self._max_size:
                self._events = self._events[-self._max_size:]
    
    def get_events(self) -> List[ChangeEvent]:
        return list(self._events)
    
    def clear(self):
        self._events.clear()


class CDCCaptureConfig:
    def __init__(
        self,
        database_type: DatabaseType = DatabaseType.MYSQL,
        host: str = "localhost",
        port: int = 3306,
        username: str = "root",
        password: str = "",
        databases: Optional[List[str]] = None,
        tables: Optional[List[str]] = None,
        include_ddl: bool = True,
        server_id: int = 1
    ):
        self.database_type = database_type
        self.host = host
        self.port = port
        self.username = username
        self.password = password
        self.databases = databases or []
        self.tables = tables or []
        self.include_ddl = include_ddl
        self.server_id = server_id


class BaseCDCCapture(ABC):
    def __init__(self, config: CDCCaptureConfig):
        self._config = config
        self._running = False
        self._event_handlers: List[Callable[[ChangeEvent], None]] = []
        self._logger = get_logger("cdc_capture")
        self._event_count = 0
    
    def add_event_handler(self, handler: Callable[[ChangeEvent], None]):
        self._event_handlers.append(handler)
    
    def _dispatch_event(self, event: ChangeEvent):
        self._event_count += 1
        for handler in self._event_handlers:
            handler(event)
    
    @abstractmethod
    async def start(self):
        pass
    
    @abstractmethod
    async def stop(self):
        pass
    
    def is_running(self) -> bool:
        return self._running
    
    def get_stats(self) -> Dict[str, Any]:
        return {
            "running": self._running,
            "event_count": self._event_count,
            "database_type": self._config.database_type.value,
            "databases": self._config.databases,
            "tables": self._config.tables
        }


class MySQLBinlogCapture(BaseCDCCapture):
    def __init__(self, config: CDCCaptureConfig):
        super().__init__(config)
        self._sequence = 0
    
    async def start(self):
        if self._running:
            return
        self._running = True
        self._logger.info(
            "MySQL binlog capture started",
            host=self._config.host,
            databases=self._config.databases
        )
    
    async def stop(self):
        if not self._running:
            return
        self._running = False
        self._logger.info("MySQL binlog capture stopped")
    
    def simulate_insert(self, database: str, table: str, data: Dict[str, Any]):
        self._sequence += 1
        event = ChangeEvent(
            event_id=f"evt_{self._sequence}",
            database=database,
            table=table,
            change_type=ChangeType.INSERT,
            primary_keys={"id": data.get("id")},
            after=[
                ColumnValue(
                    name=k,
                    value=v,
                    data_type=type(v).__name__,
                    is_primary_key=(k == "id")
                )
                for k, v in data.items()
            ],
            sequence=self._sequence,
            source_type=DatabaseType.MYSQL
        )
        self._dispatch_event(event)
        return event
    
    def simulate_update(
        self,
        database: str,
        table: str,
        before: Dict[str, Any],
        after: Dict[str, Any]
    ):
        self._sequence += 1
        event = ChangeEvent(
            event_id=f"evt_{self._sequence}",
            database=database,
            table=table,
            change_type=ChangeType.UPDATE,
            primary_keys={"id": after.get("id")},
            before=[
                ColumnValue(
                    name=k,
                    value=v,
                    data_type=type(v).__name__,
                    is_primary_key=(k == "id")
                )
                for k, v in before.items()
            ],
            after=[
                ColumnValue(
                    name=k,
                    value=v,
                    data_type=type(v).__name__,
                    is_primary_key=(k == "id")
                )
                for k, v in after.items()
            ],
            sequence=self._sequence,
            source_type=DatabaseType.MYSQL
        )
        self._dispatch_event(event)
        return event
    
    def simulate_delete(self, database: str, table: str, data: Dict[str, Any]):
        self._sequence += 1
        event = ChangeEvent(
            event_id=f"evt_{self._sequence}",
            database=database,
            table=table,
            change_type=ChangeType.DELETE,
            primary_keys={"id": data.get("id")},
            before=[
                ColumnValue(
                    name=k,
                    value=v,
                    data_type=type(v).__name__,
                    is_primary_key=(k == "id")
                )
                for k, v in data.items()
            ],
            sequence=self._sequence,
            source_type=DatabaseType.MYSQL
        )
        self._dispatch_event(event)
        return event


class PostgreSQLWALCapture(BaseCDCCapture):
    def __init__(self, config: CDCCaptureConfig):
        super().__init__(config)
        self._lsn = "0/00000000"
        self._sequence = 0
    
    async def start(self):
        if self._running:
            return
        self._running = True
        self._logger.info(
            "PostgreSQL WAL capture started",
            host=self._config.host,
            databases=self._config.databases
        )
    
    async def stop(self):
        if not self._running:
            return
        self._running = False
        self._logger.info("PostgreSQL WAL capture stopped")
    
    def simulate_change(
        self,
        database: str,
        table: str,
        change_type: ChangeType,
        data: Dict[str, Any]
    ):
        self._sequence += 1
        event = ChangeEvent(
            event_id=f"evt_{self._sequence}",
            database=database,
            table=table,
            change_type=change_type,
            primary_keys={"id": data.get("id")},
            before=[
                ColumnValue(
                    name=k,
                    value=v,
                    data_type=type(v).__name__,
                    is_primary_key=(k == "id")
                )
                for k, v in data.items()
            ] if change_type in [ChangeType.UPDATE, ChangeType.DELETE] else [],
            after=[
                ColumnValue(
                    name=k,
                    value=v,
                    data_type=type(v).__name__,
                    is_primary_key=(k == "id")
                )
                for k, v in data.items()
            ] if change_type in [ChangeType.INSERT, ChangeType.UPDATE] else [],
            sequence=self._sequence,
            source_type=DatabaseType.POSTGRESQL,
            metadata={"lsn": self._lsn}
        )
        self._dispatch_event(event)
        return event


class CDCPipeline:
    def __init__(
        self,
        capture: BaseCDCCapture,
        serializer: EventSerializer = None,
        output: OutputAdapter = None
    ):
        self._capture = capture
        self._serializer = serializer or JsonEventSerializer()
        self._output = output or InMemoryOutputAdapter()
        self._logger = get_logger("cdc_pipeline")
        self._filters: List[Callable[[ChangeEvent], bool]] = []
        self._transformers: List[Callable[[ChangeEvent], Optional[ChangeEvent]]] = []
        
        self._capture.add_event_handler(self._handle_event)
    
    def add_filter(self, filter_func: Callable[[ChangeEvent], bool]):
        self._filters.append(filter_func)
    
    def add_transformer(self, transformer: Callable[[ChangeEvent], Optional[ChangeEvent]]):
        self._transformers.append(transformer)
    
    def _apply_filters(self, event: ChangeEvent) -> bool:
        for f in self._filters:
            if not f(event):
                return False
        return True
    
    def _apply_transformers(self, event: ChangeEvent) -> Optional[ChangeEvent]:
        current = event
        for t in self._transformers:
            current = t(current)
            if current is None:
                return None
        return current
    
    def _handle_event(self, event: ChangeEvent):
        if not self._apply_filters(event):
            self._logger.debug("Event filtered out", event_id=event.event_id)
            return
        
        transformed = self._apply_transformers(event)
        if transformed is None:
            self._logger.debug("Event transformed to None", event_id=event.event_id)
            return
        
        asyncio.create_task(self._output.send(transformed))
    
    async def start(self):
        await self._capture.start()
        self._logger.info("CDC pipeline started")
    
    async def stop(self):
        await self._capture.stop()
        self._logger.info("CDC pipeline stopped")
    
    def get_capture_stats(self) -> Dict[str, Any]:
        return self._capture.get_stats()


class CDCFactory:
    @staticmethod
    def create_capture(config: CDCCaptureConfig) -> BaseCDCCapture:
        if config.database_type == DatabaseType.MYSQL:
            return MySQLBinlogCapture(config)
        elif config.database_type == DatabaseType.POSTGRESQL:
            return PostgreSQLWALCapture(config)
        else:
            raise ValueError(f"Unsupported database type: {config.database_type}")
    
    @staticmethod
    def create_pipeline(
        config: CDCCaptureConfig,
        output: Optional[OutputAdapter] = None
    ) -> CDCPipeline:
        capture = CDCFactory.create_capture(config)
        return CDCPipeline(capture=capture, output=output)
