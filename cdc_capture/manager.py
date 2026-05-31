import time
import json
import os
import threading
from abc import ABC, abstractmethod
from enum import Enum
from typing import Any, Dict, List, Optional, Callable, Iterator
from dataclasses import dataclass, field, asdict
from collections import defaultdict
from queue import Queue, Empty

from .events import (
    CDCEvent,
    InsertEvent,
    UpdateEvent,
    DeleteEvent,
    SchemaChangeEvent,
    TransactionEvent,
    HeartbeatEvent,
    EventMetadata,
    OperationType,
    EventType,
    create_event_metadata,
)
from .parsers import BaseParser, MockBinlogParser
from .connector import (
    DatabaseConnector,
    ConnectionConfig,
    Offset,
)
from .adapters import OutputAdapter
from .serializer import Serializer


class CDCStatus(str, Enum):
    STOPPED = "stopped"
    STARTING = "starting"
    RUNNING = "running"
    PAUSED = "paused"
    STOPPING = "stopping"
    ERROR = "error"
    RECOVERING = "recovering"


class CDCError(Exception):
    def __init__(self, message: str, code: Optional[str] = None, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.retryable = retryable
        self.timestamp = time.time()


@dataclass
class RetryPolicy:
    max_retries: int = 3
    initial_delay_ms: int = 1000
    max_delay_ms: int = 30000
    backoff_multiplier: float = 2.0
    retry_on_timeout: bool = True
    retry_on_connection_error: bool = True
    retry_on_parse_error: bool = False

    def get_delay(self, attempt: int) -> float:
        delay = self.initial_delay_ms * (self.backoff_multiplier ** attempt)
        return min(delay, self.max_delay_ms) / 1000.0


@dataclass
class TaskConfig:
    task_id: str
    connector_type: str
    connection_config: ConnectionConfig
    tables: List[str]
    parser_config: Optional[Dict[str, Any]] = None
    output_adapters: List[str] = field(default_factory=list)
    serializer: Optional[str] = "json"
    batch_size: int = 100
    commit_interval_ms: int = 5000
    heartbeat_interval_ms: int = 3000
    start_from_offset: Optional[Offset] = None
    filter_config: Optional[Dict[str, Any]] = None
    transform_config: Optional[Dict[str, Any]] = None
    retry_policy: RetryPolicy = field(default_factory=RetryPolicy)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "connector_type": self.connector_type,
            "connection_config": self.connection_config.to_dict(),
            "tables": self.tables,
            "parser_config": self.parser_config,
            "output_adapters": self.output_adapters,
            "serializer": self.serializer,
            "batch_size": self.batch_size,
            "commit_interval_ms": self.commit_interval_ms,
            "heartbeat_interval_ms": self.heartbeat_interval_ms,
            "start_from_offset": self.start_from_offset.to_dict() if self.start_from_offset else None,
            "filter_config": self.filter_config,
            "transform_config": self.transform_config,
            "retry_policy": asdict(self.retry_policy),
        }


@dataclass
class TaskState:
    task_id: str
    status: CDCStatus = CDCStatus.STOPPED
    current_offset: Optional[Offset] = None
    last_commit_offset: Optional[Offset] = None
    last_heartbeat: float = 0.0
    start_time: float = 0.0
    stop_time: float = 0.0
    error_count: int = 0
    last_error: Optional[str] = None
    last_error_time: Optional[float] = None
    metrics: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "status": self.status.value,
            "current_offset": self.current_offset.to_dict() if self.current_offset else None,
            "last_commit_offset": self.last_commit_offset.to_dict() if self.last_commit_offset else None,
            "last_heartbeat": self.last_heartbeat,
            "start_time": self.start_time,
            "stop_time": self.stop_time,
            "error_count": self.error_count,
            "last_error": self.last_error,
            "last_error_time": self.last_error_time,
            "metrics": self.metrics,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "TaskState":
        return cls(
            task_id=data["task_id"],
            status=CDCStatus(data.get("status", "stopped")),
            current_offset=Offset.from_dict(data["current_offset"]) if data.get("current_offset") else None,
            last_commit_offset=Offset.from_dict(data["last_commit_offset"]) if data.get("last_commit_offset") else None,
            last_heartbeat=data.get("last_heartbeat", 0.0),
            start_time=data.get("start_time", 0.0),
            stop_time=data.get("stop_time", 0.0),
            error_count=data.get("error_count", 0),
            last_error=data.get("last_error"),
            last_error_time=data.get("last_error_time"),
            metrics=data.get("metrics", {}),
        )


class MetricsCollector:
    def __init__(self):
        self._metrics: Dict[str, Any] = defaultdict(int)
        self._gauges: Dict[str, float] = {}
        self._histograms: Dict[str, List[float]] = defaultdict(list)
        self._timers: Dict[str, float] = {}
        self._lock = threading.Lock()

    def increment(self, name: str, value: int = 1, labels: Optional[Dict[str, str]] = None) -> None:
        with self._lock:
            key = self._build_key(name, labels)
            self._metrics[key] += value

    def decrement(self, name: str, value: int = 1, labels: Optional[Dict[str, str]] = None) -> None:
        with self._lock:
            key = self._build_key(name, labels)
            self._metrics[key] -= value

    def set_gauge(self, name: str, value: float, labels: Optional[Dict[str, str]] = None) -> None:
        with self._lock:
            key = self._build_key(name, labels)
            self._gauges[key] = value

    def observe(self, name: str, value: float, labels: Optional[Dict[str, str]] = None) -> None:
        with self._lock:
            key = self._build_key(name, labels)
            self._histograms[key].append(value)
            if len(self._histograms[key]) > 10000:
                self._histograms[key] = self._histograms[key][-10000:]

    def start_timer(self, name: str, labels: Optional[Dict[str, str]] = None) -> None:
        key = self._build_key(name, labels)
        self._timers[key] = time.time()

    def stop_timer(self, name: str, labels: Optional[Dict[str, str]] = None) -> float:
        key = self._build_key(name, labels)
        if key in self._timers:
            duration = time.time() - self._timers.pop(key)
            self.observe(name, duration, labels)
            return duration
        return 0.0

    def _build_key(self, name: str, labels: Optional[Dict[str, str]]) -> str:
        if not labels:
            return name
        label_str = ",".join(f"{k}={v}" for k, v in sorted(labels.items()))
        return f"{name}[{label_str}]"

    def get_counter(self, name: str, labels: Optional[Dict[str, str]] = None) -> int:
        key = self._build_key(name, labels)
        return self._metrics.get(key, 0)

    def get_gauge(self, name: str, labels: Optional[Dict[str, str]] = None) -> Optional[float]:
        key = self._build_key(name, labels)
        return self._gauges.get(key)

    def get_histogram_stats(self, name: str, labels: Optional[Dict[str, str]] = None) -> Dict[str, float]:
        key = self._build_key(name, labels)
        values = self._histograms.get(key, [])
        if not values:
            return {"count": 0, "avg": 0, "min": 0, "max": 0, "p50": 0, "p95": 0, "p99": 0}

        sorted_values = sorted(values)
        count = len(sorted_values)

        def percentile(p: float) -> float:
            idx = int(count * p / 100)
            return sorted_values[min(idx, count - 1)]

        return {
            "count": count,
            "avg": sum(values) / count,
            "min": sorted_values[0],
            "max": sorted_values[-1],
            "p50": percentile(50),
            "p95": percentile(95),
            "p99": percentile(99),
        }

    def get_all(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "counters": dict(self._metrics),
                "gauges": dict(self._gauges),
                "histograms": {k: self.get_histogram_stats(k) for k in self._histograms},
            }

    def reset(self) -> None:
        with self._lock:
            self._metrics.clear()
            self._gauges.clear()
            self._histograms.clear()
            self._timers.clear()


class StateStore(ABC):
    @abstractmethod
    def save_state(self, task_id: str, state: TaskState) -> None:
        pass

    @abstractmethod
    def load_state(self, task_id: str) -> Optional[TaskState]:
        pass

    @abstractmethod
    def delete_state(self, task_id: str) -> None:
        pass

    @abstractmethod
    def list_tasks(self) -> List[str]:
        pass


class FileStateStore(StateStore):
    def __init__(self, state_dir: str = "cdc_state"):
        self.state_dir = state_dir
        os.makedirs(state_dir, exist_ok=True)

    def _get_state_file(self, task_id: str) -> str:
        return os.path.join(self.state_dir, f"{task_id}.json")

    def save_state(self, task_id: str, state: TaskState) -> None:
        state_file = self._get_state_file(task_id)
        with open(state_file, "w", encoding="utf-8") as f:
            json.dump(state.to_dict(), f, ensure_ascii=False, indent=2, default=str)

    def load_state(self, task_id: str) -> Optional[TaskState]:
        state_file = self._get_state_file(task_id)
        if not os.path.exists(state_file):
            return None

        with open(state_file, "r", encoding="utf-8") as f:
            data = json.load(f)
            return TaskState.from_dict(data)

    def delete_state(self, task_id: str) -> None:
        state_file = self._get_state_file(task_id)
        if os.path.exists(state_file):
            os.remove(state_file)

    def list_tasks(self) -> List[str]:
        tasks = []
        if os.path.exists(self.state_dir):
            for filename in os.listdir(self.state_dir):
                if filename.endswith(".json"):
                    tasks.append(filename[:-5])
        return sorted(tasks)


class CDCTask:
    def __init__(
        self,
        config: TaskConfig,
        connector: DatabaseConnector,
        parser: BaseParser,
        adapters: Dict[str, OutputAdapter],
        serializer: Serializer,
        state_store: StateStore,
        metrics: MetricsCollector,
    ):
        self.config = config
        self.connector = connector
        self.parser = parser
        self.adapters = adapters
        self.serializer = serializer
        self.state_store = state_store
        self.metrics = metrics

        self.state = TaskState(task_id=config.task_id)
        self._event_queue: Queue = Queue(maxsize=config.batch_size * 10)
        self._running: threading.Event = threading.Event()
        self._paused: threading.Event = threading.Event()
        self._capture_thread: Optional[threading.Thread] = None
        self._dispatch_thread: Optional[threading.Thread] = None
        self._last_commit_time: float = 0.0
        self._event_buffer: List[CDCEvent] = []

        saved_state = state_store.load_state(config.task_id)
        if saved_state:
            self.state = saved_state
            if self.state.current_offset:
                self.connector.set_offset(self.state.current_offset)

    def start(self) -> None:
        if self._running.is_set():
            return

        self.state.status = CDCStatus.STARTING
        self.state.start_time = time.time()
        self._running.set()
        self._paused.clear()

        self.connector.connect()
        self.parser.start()

        for adapter in self.adapters.values():
            adapter.connect()

        self._capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
        self._dispatch_thread = threading.Thread(target=self._dispatch_loop, daemon=True)

        self._capture_thread.start()
        self._dispatch_thread.start()

        self.state.status = CDCStatus.RUNNING
        self._save_state()

    def stop(self) -> None:
        if not self._running.is_set():
            return

        self.state.status = CDCStatus.STOPPING
        self._save_state()

        self._running.clear()
        self.parser.stop()
        self.connector.stop_capture()

        if self._capture_thread:
            self._capture_thread.join(timeout=5)
        if self._dispatch_thread:
            self._dispatch_thread.join(timeout=5)

        self.connector.disconnect()

        for adapter in self.adapters.values():
            adapter.disconnect()

        self.state.status = CDCStatus.STOPPED
        self.state.stop_time = time.time()
        self._save_state()

    def pause(self) -> None:
        self._paused.set()
        self.state.status = CDCStatus.PAUSED
        self._save_state()

    def resume(self) -> None:
        self._paused.clear()
        self.state.status = CDCStatus.RUNNING
        self._save_state()

    def _capture_loop(self) -> None:
        retry_attempt = 0

        while self._running.is_set():
            try:
                if self._paused.is_set():
                    time.sleep(0.1)
                    continue

                self.metrics.set_gauge("cdc_task_status", 1, {"task_id": self.config.task_id})

                for raw_event in self.connector.start_capture(self.config.tables):
                    if not self._running.is_set():
                        break

                    while self._paused.is_set() and self._running.is_set():
                        time.sleep(0.1)

                    try:
                        self.metrics.start_timer("event_parse_duration", {"task_id": self.config.task_id})
                        events = self._parse_raw_event(raw_event)
                        self.metrics.stop_timer("event_parse_duration", {"task_id": self.config.task_id})

                        for event in events:
                            if self._filter_event(event):
                                event = self._transform_event(event)
                                self._event_queue.put(event)
                                self.metrics.increment("events_received", {"task_id": self.config.task_id})

                        retry_attempt = 0

                    except Exception as e:
                        print(f"Error parsing event: {e}")
                        self.metrics.increment("events_parse_errors", {"task_id": self.config.task_id})
                        if not self.config.retry_policy.retry_on_parse_error:
                            raise

                self.state.last_error = None
                self.state.last_error_time = None

            except Exception as e:
                self.state.error_count += 1
                self.state.last_error = str(e)
                self.state.last_error_time = time.time()
                self.metrics.increment("task_errors", {"task_id": self.config.task_id})

                retry_policy = self.config.retry_policy
                if retry_attempt < retry_policy.max_retries and retry_policy.retry_on_connection_error:
                    delay = retry_policy.get_delay(retry_attempt)
                    print(f"Capture error, retry {retry_attempt + 1}/{retry_policy.max_retries} in {delay}s: {e}")
                    self.state.status = CDCStatus.RECOVERING
                    self._save_state()
                    time.sleep(delay)
                    retry_attempt += 1

                    try:
                        self.connector.disconnect()
                        self.connector.connect()
                    except Exception as conn_e:
                        print(f"Reconnect error: {conn_e}")
                else:
                    self.state.status = CDCStatus.ERROR
                    self._save_state()
                    self._running.clear()
                    break

        self._event_queue.put(None)

    def _parse_raw_event(self, raw_event: Dict[str, Any]) -> List[CDCEvent]:
        events: List[CDCEvent] = []
        operation = raw_event.get("operation", "")

        metadata = EventMetadata(
            source=self.config.connector_type,
            database=self.config.connection_config.database,
            schema=self.config.connection_config.schema,
            table=raw_event.get("table", ""),
            timestamp=raw_event.get("timestamp", time.time()),
            commit_timestamp=raw_event.get("timestamp", time.time()),
        )

        if "log_file" in raw_event:
            metadata.binlog_file = raw_event["log_file"]
            metadata.binlog_position = str(raw_event.get("log_pos", ""))
        if "gtid" in raw_event:
            metadata.gtid = raw_event["gtid"]
        if "lsn" in raw_event:
            metadata.lsn = raw_event.get("lsn_int", 0)
        if "xid" in raw_event:
            metadata.xid = raw_event["xid"]
        if "frame_number" in raw_event:
            metadata.offset = raw_event["frame_number"]

        if operation in ("INSERT", "UPDATE", "DELETE"):
            op_type = OperationType(operation)
            rows = raw_event.get("rows", [])

            for row in rows:
                if op_type == OperationType.INSERT:
                    event = InsertEvent(
                        metadata=metadata,
                        table=metadata.table,
                        schema=metadata.schema,
                        new_data=row.get("values", row.get("after_values", {})),
                        columns=list(row.get("values", row.get("after_values", {})).keys()),
                    )
                elif op_type == OperationType.UPDATE:
                    event = UpdateEvent(
                        metadata=metadata,
                        table=metadata.table,
                        schema=metadata.schema,
                        old_data=row.get("before_values", row.get("values", {})),
                        new_data=row.get("after_values", row.get("values", {})),
                        updated_columns=list(row.get("after_values", {}).keys()),
                        columns=list(set(list(row.get("before_values", {}).keys()) + list(row.get("after_values", {}).keys()))),
                    )
                else:
                    event = DeleteEvent(
                        metadata=metadata,
                        table=metadata.table,
                        schema=metadata.schema,
                        old_data=row.get("values", row.get("before_values", {})),
                        columns=list(row.get("values", row.get("before_values", {})).keys()),
                    )
                events.append(event)

        elif operation in ("BEGIN", "COMMIT", "ROLLBACK"):
            event = TransactionEvent(
                metadata=metadata,
                operation=OperationType(operation),
                transaction_id=raw_event.get("gtid", str(raw_event.get("xid", ""))),
                events_count=0,
            )
            events.append(event)

        elif operation in ("CREATE", "ALTER", "DROP", "TRUNCATE"):
            event = SchemaChangeEvent(
                metadata=metadata,
                operation=OperationType(operation),
                schema_name=metadata.schema,
                table_name=metadata.table,
                ddl_sql=raw_event.get("query", ""),
            )
            events.append(event)

        if not events and isinstance(self.parser, MockBinlogParser):
            events = list(self.parser.parse_event(raw_event)) if hasattr(self.parser.parse_event(raw_event), '__iter__') else [self.parser.parse_event(raw_event)]

        return events

    def _filter_event(self, event: CDCEvent) -> bool:
        if not self.config.filter_config:
            return True

        include_tables = self.config.filter_config.get("include_tables")
        if include_tables:
            if event.metadata.table not in include_tables:
                return False

        exclude_tables = self.config.filter_config.get("exclude_tables")
        if exclude_tables:
            if event.metadata.table in exclude_tables:
                return False

        include_operations = self.config.filter_config.get("include_operations")
        if include_operations:
            op = event.get_payload().get("operation")
            if op not in include_operations:
                return False

        return True

    def _transform_event(self, event: CDCEvent) -> CDCEvent:
        if not self.config.transform_config:
            return event

        add_metadata = self.config.transform_config.get("add_metadata", {})
        for key, value in add_metadata.items():
            event.metadata.headers[key] = str(value)

        rename_columns = self.config.transform_config.get("rename_columns", {})
        if isinstance(event, (InsertEvent, UpdateEvent, DeleteEvent)):
            if hasattr(event, "new_data") and event.new_data:
                for old, new in rename_columns.items():
                    if old in event.new_data:
                        event.new_data[new] = event.new_data.pop(old)
            if hasattr(event, "old_data") and event.old_data:
                for old, new in rename_columns.items():
                    if old in event.old_data:
                        event.old_data[new] = event.old_data.pop(old)
            if hasattr(event, "columns") and event.columns:
                event.columns = [rename_columns.get(c, c) for c in event.columns]

        return event

    def _dispatch_loop(self) -> None:
        while self._running.is_set() or not self._event_queue.empty():
            try:
                event = self._event_queue.get(timeout=0.1)
                if event is None:
                    break

                self._event_buffer.append(event)

                if len(self._event_buffer) >= self.config.batch_size:
                    self._flush_buffer()

                current_time = time.time()
                if current_time - self._last_commit_time >= self.config.commit_interval_ms / 1000:
                    if self._event_buffer:
                        self._flush_buffer()
                    self._commit_offset()
                    self._last_commit_time = current_time

            except Empty:
                if self._event_buffer:
                    self._flush_buffer()
                continue
            except Exception as e:
                print(f"Dispatch error: {e}")
                self.metrics.increment("dispatch_errors", {"task_id": self.config.task_id})

        if self._event_buffer:
            self._flush_buffer()
        self._commit_offset()

    def _flush_buffer(self) -> None:
        if not self._event_buffer:
            return

        for adapter_name in self.config.output_adapters:
            adapter = self.adapters.get(adapter_name)
            if not adapter:
                continue

            try:
                self.metrics.start_timer("event_dispatch_duration", {
                    "task_id": self.config.task_id,
                    "adapter": adapter_name,
                })

                if len(self._event_buffer) > 1:
                    results = adapter.send_batch(self._event_buffer)
                    success = results.get("success", 0)
                    failed = results.get("failed", 0)
                else:
                    success = 1 if adapter.send(self._event_buffer[0]) else 0
                    failed = 0 if success else 1

                self.metrics.stop_timer("event_dispatch_duration", {
                    "task_id": self.config.task_id,
                    "adapter": adapter_name,
                })
                self.metrics.increment("events_dispatched", {"task_id": self.config.task_id, "adapter": adapter_name}, success)
                if failed:
                    self.metrics.increment("events_dispatch_failed", {"task_id": self.config.task_id, "adapter": adapter_name}, failed)

            except Exception as e:
                print(f"Adapter {adapter_name} error: {e}")
                self.metrics.increment("adapter_errors", {"task_id": self.config.task_id, "adapter": adapter_name})

        self._event_buffer = []

    def _commit_offset(self) -> None:
        try:
            self.connector.commit_offset()
            current_offset = self.connector.get_offset()
            self.state.current_offset = current_offset
            self.state.last_commit_offset = current_offset
            self.state.last_heartbeat = time.time()
            self._save_state()
            self.metrics.set_gauge(
                "last_committed_offset_timestamp",
                current_offset.timestamp,
                {"task_id": self.config.task_id},
            )
        except Exception as e:
            print(f"Commit offset error: {e}")

    def _save_state(self) -> None:
        try:
            self.state.metrics = {
                "events_received": self.metrics.get_counter("events_received", {"task_id": self.config.task_id}),
                "events_dispatched": self.metrics.get_counter("events_dispatched", {"task_id": self.config.task_id}),
                "events_parse_errors": self.metrics.get_counter("events_parse_errors", {"task_id": self.config.task_id}),
                "task_errors": self.metrics.get_counter("task_errors", {"task_id": self.config.task_id}),
            }
            self.state_store.save_state(self.config.task_id, self.state)
        except Exception as e:
            print(f"Save state error: {e}")

    def get_status(self) -> Dict[str, Any]:
        return {
            "task_id": self.config.task_id,
            "status": self.state.status.value,
            "is_running": self._running.is_set(),
            "is_paused": self._paused.is_set(),
            "start_time": self.state.start_time,
            "current_offset": self.state.current_offset.to_dict() if self.state.current_offset else None,
            "last_commit_offset": self.state.last_commit_offset.to_dict() if self.state.last_commit_offset else None,
            "error_count": self.state.error_count,
            "last_error": self.state.last_error,
            "queue_size": self._event_queue.qsize(),
            "buffer_size": len(self._event_buffer),
            "metrics": self.metrics.get_all(),
            "connector_stats": self.connector.get_stats(),
        }


class CDCManager:
    def __init__(
        self,
        state_store: Optional[StateStore] = None,
        metrics: Optional[MetricsCollector] = None,
    ):
        self.state_store = state_store or FileStateStore()
        self.metrics = metrics or MetricsCollector()
        self._tasks: Dict[str, CDCTask] = {}
        self._connectors: Dict[str, DatabaseConnector] = {}
        self._adapters: Dict[str, OutputAdapter] = {}
        self._parsers: Dict[str, BaseParser] = {}
        self._serializers: Dict[str, Serializer] = {}
        self._lock = threading.RLock()

    def register_connector(self, name: str, connector: DatabaseConnector) -> None:
        with self._lock:
            self._connectors[name] = connector

    def register_adapter(self, name: str, adapter: OutputAdapter) -> None:
        with self._lock:
            self._adapters[name] = adapter

    def register_parser(self, name: str, parser: BaseParser) -> None:
        with self._lock:
            self._parsers[name] = parser

    def register_serializer(self, name: str, serializer: Serializer) -> None:
        with self._lock:
            self._serializers[name] = serializer

    def create_task(self, config: TaskConfig) -> CDCTask:
        with self._lock:
            if config.task_id in self._tasks:
                raise ValueError(f"Task {config.task_id} already exists")

            connector = self._connectors.get(config.connector_type)
            if not connector:
                raise ValueError(f"Connector {config.connector_type} not registered")

            parser = self._parsers.get(config.connector_type, MockBinlogParser(config.parser_config or {}))
            serializer = self._serializers.get(config.serializer)
            if not serializer:
                from .serializer import JSONSerializer
                serializer = JSONSerializer()

            adapters = {name: self._adapters[name] for name in config.output_adapters if name in self._adapters}

            task = CDCTask(
                config=config,
                connector=connector,
                parser=parser,
                adapters=adapters,
                serializer=serializer,
                state_store=self.state_store,
                metrics=self.metrics,
            )

            self._tasks[config.task_id] = task
            return task

    def start_task(self, task_id: str) -> None:
        task = self._get_task(task_id)
        task.start()

    def stop_task(self, task_id: str) -> None:
        task = self._get_task(task_id)
        task.stop()

    def pause_task(self, task_id: str) -> None:
        task = self._get_task(task_id)
        task.pause()

    def resume_task(self, task_id: str) -> None:
        task = self._get_task(task_id)
        task.resume()

    def delete_task(self, task_id: str) -> None:
        with self._lock:
            if task_id in self._tasks:
                self._tasks[task_id].stop()
                del self._tasks[task_id]
            self.state_store.delete_state(task_id)

    def get_task(self, task_id: str) -> CDCTask:
        return self._get_task(task_id)

    def _get_task(self, task_id: str) -> CDCTask:
        with self._lock:
            task = self._tasks.get(task_id)
            if not task:
                raise ValueError(f"Task {task_id} not found")
            return task

    def list_tasks(self) -> List[str]:
        with self._lock:
            return sorted(self._tasks.keys())

    def get_task_status(self, task_id: str) -> Dict[str, Any]:
        task = self._get_task(task_id)
        return task.get_status()

    def get_all_status(self) -> Dict[str, Dict[str, Any]]:
        with self._lock:
            return {task_id: task.get_status() for task_id, task in self._tasks.items()}

    def get_metrics(self) -> Dict[str, Any]:
        return self.metrics.get_all()

    def start_all(self) -> None:
        with self._lock:
            for task_id in self._tasks:
                try:
                    self._tasks[task_id].start()
                except Exception as e:
                    print(f"Error starting task {task_id}: {e}")

    def stop_all(self) -> None:
        with self._lock:
            for task_id in self._tasks:
                try:
                    self._tasks[task_id].stop()
                except Exception as e:
                    print(f"Error stopping task {task_id}: {e}")

    def recover_tasks(self) -> List[str]:
        recovered = []
        saved_tasks = self.state_store.list_tasks()

        for task_id in saved_tasks:
            if task_id not in self._tasks:
                try:
                    state = self.state_store.load_state(task_id)
                    if state and state.status in (CDCStatus.RUNNING, CDCStatus.ERROR, CDCStatus.RECOVERING):
                        print(f"Recovering task {task_id} from state: {state.status.value}")
                        recovered.append(task_id)
                except Exception as e:
                    print(f"Error recovering task {task_id}: {e}")

        return recovered
