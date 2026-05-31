import time
import json
import threading
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Iterator, Set, Tuple
from dataclasses import dataclass, field, asdict
from collections import defaultdict


@dataclass
class ConnectionConfig:
    host: str
    port: int
    username: str
    password: str
    database: str
    schema: str = "public"
    charset: str = "utf8mb4"
    connect_timeout: int = 30
    read_timeout: int = 60
    heartbeat_interval: int = 3000
    retry_interval: int = 5000
    max_retries: int = 10
    ssl: bool = False
    ssl_ca: Optional[str] = None
    ssl_cert: Optional[str] = None
    ssl_key: Optional[str] = None
    extra_params: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ConnectionConfig":
        return cls(**data)


@dataclass
class GTID:
    uuid: str
    interval_start: int
    interval_end: Optional[int] = None

    def __str__(self) -> str:
        if self.interval_end:
            return f"{self.uuid}:{self.interval_start}-{self.interval_end}"
        return f"{self.uuid}:{self.interval_start}"

    @classmethod
    def parse(cls, gtid_str: str) -> "GTID":
        parts = gtid_str.split(":")
        uuid = parts[0]
        interval = parts[1]
        if "-" in interval:
            start, end = interval.split("-")
            return cls(uuid, int(start), int(end))
        return cls(uuid, int(interval))


@dataclass
class Offset:
    binlog_file: str = ""
    binlog_position: int = 4
    gtid_set: str = ""
    lsn: int = 0
    xlog_location: str = ""
    timestamp: float = 0.0
    frame_number: int = 0
    custom: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Offset":
        return cls(**data)

    def update(self, **kwargs) -> None:
        for key, value in kwargs.items():
            if hasattr(self, key):
                setattr(self, key, value)
        self.timestamp = time.time()


class HeartbeatManager:
    def __init__(self, interval_ms: int = 3000, timeout_ms: int = 10000):
        self.interval_ms = interval_ms
        self.timeout_ms = timeout_ms
        self._last_heartbeat: float = 0
        self._is_healthy: bool = True
        self._heartbeat_callbacks: List[callable] = []
        self._timeout_callbacks: List[callable] = []
        self._thread: Optional[threading.Thread] = None
        self._stop_event: threading.Event = threading.Event()
        self._heartbeat_count: int = 0
        self._missed_count: int = 0

    def start(self) -> None:
        self._stop_event.clear()
        self._last_heartbeat = time.time()
        self._thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=1)

    def _monitor_loop(self) -> None:
        while not self._stop_event.is_set():
            elapsed = (time.time() - self._last_heartbeat) * 1000

            if elapsed >= self.timeout_ms and self._is_healthy:
                self._is_healthy = False
                self._missed_count += 1
                for callback in self._timeout_callbacks:
                    try:
                        callback()
                    except Exception as e:
                        print(f"Heartbeat timeout callback error: {e}")

            self._stop_event.wait(self.interval_ms / 1000)

    def beat(self) -> None:
        self._last_heartbeat = time.time()
        self._heartbeat_count += 1
        if not self._is_healthy:
            self._is_healthy = True
        for callback in self._heartbeat_callbacks:
            try:
                callback()
            except Exception as e:
                print(f"Heartbeat callback error: {e}")

    def is_healthy(self) -> bool:
        elapsed = (time.time() - self._last_heartbeat) * 1000
        return self._is_healthy and elapsed < self.timeout_ms

    def add_heartbeat_callback(self, callback: callable) -> None:
        self._heartbeat_callbacks.append(callback)

    def add_timeout_callback(self, callback: callable) -> None:
        self._timeout_callbacks.append(callback)

    def get_stats(self) -> Dict[str, Any]:
        return {
            "heartbeat_count": self._heartbeat_count,
            "missed_count": self._missed_count,
            "last_heartbeat": self._last_heartbeat,
            "is_healthy": self.is_healthy(),
            "elapsed_ms": (time.time() - self._last_heartbeat) * 1000,
        }


class OffsetManager:
    def __init__(self, initial_offset: Optional[Offset] = None):
        self._current_offset: Offset = initial_offset or Offset()
        self._offset_lock: threading.Lock = threading.Lock()
        self._committed_offset: Offset = Offset()
        self._offset_history: List[Offset] = []
        self._max_history: int = 1000

    def get_current_offset(self) -> Offset:
        with self._offset_lock:
            return Offset.from_dict(self._current_offset.to_dict())

    def update_offset(self, **kwargs) -> None:
        with self._offset_lock:
            self._current_offset.update(**kwargs)
            history_entry = Offset.from_dict(self._current_offset.to_dict())
            self._offset_history.append(history_entry)
            if len(self._offset_history) > self._max_history:
                self._offset_history.pop(0)

    def commit(self) -> None:
        with self._offset_lock:
            self._committed_offset = Offset.from_dict(self._current_offset.to_dict())

    def get_committed_offset(self) -> Offset:
        with self._offset_lock:
            return Offset.from_dict(self._committed_offset.to_dict())

    def rollback(self) -> None:
        with self._offset_lock:
            self._current_offset = Offset.from_dict(self._committed_offset.to_dict())

    def get_history(self, limit: Optional[int] = None) -> List[Offset]:
        with self._offset_lock:
            if limit:
                return self._offset_history[-limit:].copy()
            return self._offset_history.copy()

    def parse_gtid_set(self, gtid_set: str) -> Dict[str, Set[Tuple[int, Optional[int]]]]:
        result: Dict[str, Set[Tuple[int, Optional[int]]]] = defaultdict(set)
        if not gtid_set:
            return result

        for gtid_str in gtid_set.split(","):
            gtid_str = gtid_str.strip()
            if not gtid_str:
                continue

            parts = gtid_str.split(":")
            uuid = parts[0]
            intervals = parts[1:]

            for interval in intervals:
                if "-" in interval:
                    start, end = interval.split("-")
                    result[uuid].add((int(start), int(end)))
                else:
                    result[uuid].add((int(interval), None))

        return result

    def add_gtid(self, gtid_str: str) -> None:
        gtid = GTID.parse(gtid_str)
        current = self.parse_gtid_set(self._current_offset.gtid_set)

        merged = False
        new_intervals: List[Tuple[int, Optional[int]]] = []

        for start, end in sorted(current.get(gtid.uuid, [])):
            if gtid.interval_end:
                if start <= gtid.interval_end + 1 and (end is None or gtid.interval_start <= end + 1):
                    new_start = min(start, gtid.interval_start)
                    new_end = max(end if end else start, gtid.interval_end)
                    new_intervals.append((new_start, new_end))
                    merged = True
                else:
                    new_intervals.append((start, end))
            else:
                if end is None:
                    if start == gtid.interval_start + 1:
                        new_intervals.append((gtid.interval_start, None))
                        merged = True
                    else:
                        new_intervals.append((start, end))
                elif gtid.interval_start == end + 1:
                    new_intervals.append((start, gtid.interval_start))
                    merged = True
                elif gtid.interval_start == start - 1:
                    new_intervals.append((gtid.interval_start, end))
                    merged = True
                else:
                    new_intervals.append((start, end))

        if not merged:
            new_intervals.append((gtid.interval_start, gtid.interval_end))

        current[gtid.uuid] = set(new_intervals)
        self._current_offset.gtid_set = self._format_gtid_set(current)

    def _format_gtid_set(self, gtid_dict: Dict[str, Set[Tuple[int, Optional[int]]]]) -> str:
        parts = []
        for uuid in sorted(gtid_dict.keys()):
            intervals = sorted(gtid_dict[uuid])
            interval_strs = []
            for start, end in intervals:
                if end:
                    interval_strs.append(f"{start}-{end}")
                else:
                    interval_strs.append(f"{start}")
            parts.append(f"{uuid}:{':'.join(interval_strs)}")
        return ",".join(parts)

    def contains_gtid(self, gtid_str: str) -> bool:
        gtid = GTID.parse(gtid_str)
        current = self.parse_gtid_set(self._current_offset.gtid_set)

        for start, end in current.get(gtid.uuid, []):
            if gtid.interval_end:
                if start <= gtid.interval_start and (end is None or end >= gtid.interval_end):
                    return True
            else:
                if start <= gtid.interval_start and (end is None or end >= gtid.interval_start):
                    return True

        return False


class DatabaseConnector(ABC):
    def __init__(self, config: ConnectionConfig):
        self.config = config
        self._connection = None
        self._is_connected: bool = False
        self._heartbeat_manager: HeartbeatManager = HeartbeatManager(
            interval_ms=config.heartbeat_interval,
            timeout_ms=config.heartbeat_interval * 3,
        )
        self._offset_manager: OffsetManager = OffsetManager()
        self._retry_count: int = 0
        self._last_error: Optional[Exception] = None

    @abstractmethod
    def connect(self) -> bool:
        pass

    @abstractmethod
    def disconnect(self) -> None:
        pass

    @abstractmethod
    def is_connected(self) -> bool:
        pass

    @abstractmethod
    def start_capture(self, tables: List[str]) -> Iterator[Any]:
        pass

    @abstractmethod
    def stop_capture(self) -> None:
        pass

    @abstractmethod
    def get_heartbeat(self) -> Optional[float]:
        pass

    def get_offset(self) -> Offset:
        return self._offset_manager.get_current_offset()

    def set_offset(self, offset: Offset) -> None:
        self._offset_manager = OffsetManager(offset)

    def commit_offset(self) -> None:
        self._offset_manager.commit()

    def get_heartbeat_manager(self) -> HeartbeatManager:
        return self._heartbeat_manager

    def get_offset_manager(self) -> OffsetManager:
        return self._offset_manager

    def get_stats(self) -> Dict[str, Any]:
        return {
            "is_connected": self.is_connected(),
            "retry_count": self._retry_count,
            "last_error": str(self._last_error) if self._last_error else None,
            "heartbeat": self._heartbeat_manager.get_stats(),
            "offset": self._offset_manager.get_current_offset().to_dict(),
        }

    def _retry(self, func, *args, **kwargs) -> Any:
        last_exception = None
        for attempt in range(self.config.max_retries):
            try:
                result = func(*args, **kwargs)
                self._retry_count = 0
                return result
            except Exception as e:
                last_exception = e
                self._last_error = e
                self._retry_count += 1
                wait_time = self.config.retry_interval / 1000 * (2 ** attempt)
                print(f"Retry attempt {attempt + 1}/{self.config.max_retries}, error: {e}, waiting {wait_time}s")
                time.sleep(wait_time)

        self._last_error = last_exception
        raise last_exception or Exception("Max retries exceeded")


class MySQLConnector(DatabaseConnector):
    def __init__(self, config: ConnectionConfig):
        super().__init__(config)
        self._stream = None
        self._capture_thread: Optional[threading.Thread] = None
        self._stop_capture: threading.Event = threading.Event()
        self._tables: List[str] = []
        self._server_id: int = config.extra_params.get("server_id", 1)

    def connect(self) -> bool:
        try:
            import pymysql

            self._connection = pymysql.connect(
                host=self.config.host,
                port=self.config.port,
                user=self.config.username,
                password=self.config.password,
                database=self.config.database,
                charset=self.config.charset,
                connect_timeout=self.config.connect_timeout,
                read_timeout=self.config.read_timeout,
                ssl={
                    "ca": self.config.ssl_ca,
                    "cert": self.config.ssl_cert,
                    "key": self.config.ssl_key,
                } if self.config.ssl else None,
            )

            with self._connection.cursor() as cursor:
                cursor.execute("SELECT VERSION()")
                version = cursor.fetchone()
                print(f"Connected to MySQL {version[0]}")

                cursor.execute("SHOW MASTER STATUS")
                master_status = cursor.fetchone()
                if master_status:
                    self._offset_manager.update_offset(
                        binlog_file=master_status[0],
                        binlog_position=master_status[1],
                    )

            self._is_connected = True
            self._heartbeat_manager.start()
            return True

        except ImportError:
            print("pymysql not installed, using mock mode")
            self._is_connected = True
            self._heartbeat_manager.start()
            return True
        except Exception as e:
            print(f"MySQL connection error: {e}")
            self._last_error = e
            return False

    def disconnect(self) -> None:
        self.stop_capture()
        self._heartbeat_manager.stop()
        if self._connection:
            try:
                self._connection.close()
            except Exception:
                pass
            self._connection = None
        self._is_connected = False

    def is_connected(self) -> bool:
        if self._connection is None:
            return False
        try:
            self._connection.ping()
            return True
        except Exception:
            return False

    def start_capture(self, tables: List[str]) -> Iterator[Dict[str, Any]]:
        try:
            from pymysqlreplication import BinLogStreamReader
            from pymysqlreplication.row_event import (
                DeleteRowsEvent,
                UpdateRowsEvent,
                WriteRowsEvent,
            )
            from pymysqlreplication.event import (
                QueryEvent,
                XidEvent,
                GtidEvent,
            )

            self._tables = tables
            self._stop_capture.clear()

            current_offset = self._offset_manager.get_current_offset()

            self._stream = BinLogStreamReader(
                connection_settings={
                    "host": self.config.host,
                    "port": self.config.port,
                    "user": self.config.username,
                    "passwd": self.config.password,
                    "db": self.config.database,
                },
                server_id=self._server_id,
                blocking=True,
                resume_stream=True,
                only_events=[
                    DeleteRowsEvent,
                    UpdateRowsEvent,
                    WriteRowsEvent,
                    QueryEvent,
                    XidEvent,
                    GtidEvent,
                ],
                only_tables=tables if tables else None,
                only_schemas=[self.config.database],
                log_file=current_offset.binlog_file or None,
                log_pos=current_offset.binlog_position or 4,
                auto_position=bool(current_offset.gtid_set),
            )

            for binlogevent in self._stream:
                if self._stop_capture.is_set():
                    break

                self._heartbeat_manager.beat()

                event_dict = {
                    "timestamp": binlogevent.timestamp,
                    "log_file": self._stream.log_file,
                    "log_pos": self._stream.log_pos,
                }

                if isinstance(binlogevent, GtidEvent):
                    event_dict["gtid"] = binlogevent.gtid
                    self._offset_manager.add_gtid(binlogevent.gtid)
                elif isinstance(binlogevent, XidEvent):
                    event_dict["xid"] = binlogevent.xid
                    event_dict["operation"] = "COMMIT"
                elif isinstance(binlogevent, QueryEvent):
                    event_dict["query"] = binlogevent.query
                    event_dict["schema"] = binlogevent.schema
                    event_dict["operation"] = "QUERY"
                elif isinstance(binlogevent, (WriteRowsEvent, UpdateRowsEvent, DeleteRowsEvent)):
                    event_dict["schema"] = binlogevent.schema
                    event_dict["table"] = binlogevent.table
                    event_dict["rows"] = []

                    for row in binlogevent.rows:
                        if isinstance(binlogevent, WriteRowsEvent):
                            event_dict["operation"] = "INSERT"
                            event_dict["rows"].append({"values": row["values"]})
                        elif isinstance(binlogevent, UpdateRowsEvent):
                            event_dict["operation"] = "UPDATE"
                            event_dict["rows"].append({
                                "before_values": row["before_values"],
                                "after_values": row["after_values"],
                            })
                        elif isinstance(binlogevent, DeleteRowsEvent):
                            event_dict["operation"] = "DELETE"
                            event_dict["rows"].append({"values": row["values"]})

                self._offset_manager.update_offset(
                    binlog_file=self._stream.log_file,
                    binlog_position=self._stream.log_pos,
                )

                yield event_dict

            self._stream.close()

        except ImportError:
            print("mysql-replication not installed, using mock mode")
            self._tables = tables
            self._stop_capture.clear()

            event_counter = 0
            while not self._stop_capture.is_set():
                self._heartbeat_manager.beat()
                event_counter += 1

                mock_event = {
                    "timestamp": int(time.time()),
                    "log_file": f"mysql-bin.{event_counter // 1000:06d}",
                    "log_pos": event_counter * 100,
                    "schema": self.config.database,
                    "table": tables[event_counter % len(tables)] if tables else "mock_table",
                    "operation": ["INSERT", "UPDATE", "DELETE"][event_counter % 3],
                    "rows": [{"values": {"id": event_counter, "name": f"user_{event_counter}"}}],
                }

                if event_counter % 50 == 0:
                    mock_event["gtid"] = f"3E11FA47-71CA-11E1-9E33-C80AA9429562:{event_counter}"
                    self._offset_manager.add_gtid(mock_event["gtid"])

                self._offset_manager.update_offset(
                    binlog_file=mock_event["log_file"],
                    binlog_position=mock_event["log_pos"],
                )

                yield mock_event
                time.sleep(0.1)

        except Exception as e:
            print(f"MySQL capture error: {e}")
            self._last_error = e

    def stop_capture(self) -> None:
        self._stop_capture.set()
        if self._stream:
            try:
                self._stream.close()
            except Exception:
                pass
            self._stream = None

    def get_heartbeat(self) -> Optional[float]:
        if not self.is_connected():
            return None

        try:
            with self._connection.cursor() as cursor:
                cursor.execute("SELECT NOW()")
                result = cursor.fetchone()
                self._heartbeat_manager.beat()
                return time.time()
        except Exception:
            return None


class PostgreSQLConnector(DatabaseConnector):
    def __init__(self, config: ConnectionConfig):
        super().__init__(config)
        self._replication_slot: str = config.extra_params.get("replication_slot", "cdc_slot")
        self._plugin: str = config.extra_params.get("plugin", "pgoutput")
        self._publication: str = config.extra_params.get("publication", "cdc_publication")
        self._stop_capture: threading.Event = threading.Event()

    def connect(self) -> bool:
        try:
            import psycopg2

            self._connection = psycopg2.connect(
                host=self.config.host,
                port=self.config.port,
                user=self.config.username,
                password=self.config.password,
                dbname=self.config.database,
                connect_timeout=self.config.connect_timeout,
                sslmode="require" if self.config.ssl else "disable",
            )

            self._connection.autocommit = True

            with self._connection.cursor() as cursor:
                cursor.execute("SELECT version()")
                version = cursor.fetchone()
                print(f"Connected to PostgreSQL {version[0]}")

                cursor.execute("SELECT pg_current_wal_lsn()")
                lsn = cursor.fetchone()
                if lsn:
                    self._offset_manager.update_offset(lsn=self._parse_lsn(lsn[0]))

            self._is_connected = True
            self._heartbeat_manager.start()
            return True

        except ImportError:
            print("psycopg2 not installed, using mock mode")
            self._is_connected = True
            self._heartbeat_manager.start()
            return True
        except Exception as e:
            print(f"PostgreSQL connection error: {e}")
            self._last_error = e
            return False

    def disconnect(self) -> None:
        self.stop_capture()
        self._heartbeat_manager.stop()
        if self._connection:
            try:
                self._connection.close()
            except Exception:
                pass
            self._connection = None
        self._is_connected = False

    def is_connected(self) -> bool:
        if self._connection is None:
            return False
        try:
            with self._connection.cursor() as cursor:
                cursor.execute("SELECT 1")
                cursor.fetchone()
            return True
        except Exception:
            return False

    def start_capture(self, tables: List[str]) -> Iterator[Dict[str, Any]]:
        try:
            import psycopg2
            from psycopg2.extras import LogicalReplicationConnection

            self._stop_capture.clear()

            conn = psycopg2.connect(
                host=self.config.host,
                port=self.config.port,
                user=self.config.username,
                password=self.config.password,
                dbname=self.config.database,
                connection_factory=LogicalReplicationConnection,
            )

            cursor = conn.cursor()

            try:
                cursor.create_replication_slot(
                    self._replication_slot,
                    slot_type="logical",
                    output_plugin=self._plugin,
                )
            except psycopg2.errors.DuplicateObject:
                pass

            current_offset = self._offset_manager.get_current_offset()
            start_lsn = self._format_lsn(current_offset.lsn) if current_offset.lsn else None

            cursor.start_replication(
                slot_name=self._replication_slot,
                decode=True,
                start_lsn=start_lsn,
                options={"publication_names": self._publication},
            )

            def consume(msg):
                if self._stop_capture.is_set():
                    cursor.send_feedback(reply=True)
                    return

                self._heartbeat_manager.beat()

                lsn_int = self._parse_lsn(msg.data_start)
                self._offset_manager.update_offset(lsn=lsn_int)

                event_dict = {
                    "timestamp": time.time(),
                    "lsn": msg.data_start,
                    "lsn_int": lsn_int,
                    "payload": msg.payload,
                }

                yield event_dict
                cursor.send_feedback(flush_lsn=msg.data_start)

            try:
                for msg in cursor:
                    if self._stop_capture.is_set():
                        break
                    for event in consume(msg):
                        yield event
            finally:
                cursor.close()
                conn.close()

        except ImportError:
            print("psycopg2 not installed, using mock mode")
            self._stop_capture.clear()

            event_counter = 0
            lsn = self._offset_manager.get_current_offset().lsn or 0

            while not self._stop_capture.is_set():
                self._heartbeat_manager.beat()
                event_counter += 1
                lsn += 100

                mock_event = {
                    "timestamp": time.time(),
                    "lsn": self._format_lsn(lsn),
                    "lsn_int": lsn,
                    "schema": self.config.schema,
                    "table": tables[event_counter % len(tables)] if tables else "mock_table",
                    "operation": ["INSERT", "UPDATE", "DELETE"][event_counter % 3],
                    "xid": event_counter * 1000,
                    "new_tuple": {"id": event_counter, "name": f"user_{event_counter}"},
                }

                self._offset_manager.update_offset(lsn=lsn, xid=mock_event["xid"])
                yield mock_event
                time.sleep(0.1)

        except Exception as e:
            print(f"PostgreSQL capture error: {e}")
            self._last_error = e

    def stop_capture(self) -> None:
        self._stop_capture.set()

    def get_heartbeat(self) -> Optional[float]:
        if not self.is_connected():
            return None

        try:
            with self._connection.cursor() as cursor:
                cursor.execute("SELECT NOW()")
                result = cursor.fetchone()
                self._heartbeat_manager.beat()
                return time.time()
        except Exception:
            return None

    def _parse_lsn(self, lsn_str: str) -> int:
        if isinstance(lsn_str, int):
            return lsn_str
        parts = lsn_str.split("/")
        return (int(parts[0], 16) << 32) | int(parts[1], 16)

    def _format_lsn(self, lsn_int: int) -> str:
        return f"{(lsn_int >> 32) & 0xFFFFFFFF:X}/{lsn_int & 0xFFFFFFFF:X}"


class SQLiteConnector(DatabaseConnector):
    def __init__(self, config: ConnectionConfig):
        super().__init__(config)
        self._wal_file: str = config.extra_params.get("wal_file", f"{config.database}-wal")
        self._poll_interval: float = config.extra_params.get("poll_interval", 0.5)
        self._stop_capture: threading.Event = threading.Event()
        self._last_frame: int = 0

    def connect(self) -> bool:
        try:
            import sqlite3

            db_path = self.config.extra_params.get("db_path", self.config.database)
            self._connection = sqlite3.connect(
                db_path,
                timeout=self.config.connect_timeout,
            )
            self._connection.execute("PRAGMA journal_mode=WAL")
            self._connection.execute("PRAGMA wal_autocheckpoint=1000")

            with self._connection.cursor() as cursor:
                cursor.execute("SELECT sqlite_version()")
                version = cursor.fetchone()
                print(f"Connected to SQLite {version[0]}")

            self._is_connected = True
            self._heartbeat_manager.start()
            return True

        except ImportError:
            print("sqlite3 not available, using mock mode")
            self._is_connected = True
            self._heartbeat_manager.start()
            return True
        except Exception as e:
            print(f"SQLite connection error: {e}")
            self._last_error = e
            return False

    def disconnect(self) -> None:
        self.stop_capture()
        self._heartbeat_manager.stop()
        if self._connection:
            try:
                self._connection.close()
            except Exception:
                pass
            self._connection = None
        self._is_connected = False

    def is_connected(self) -> bool:
        if self._connection is None:
            return False
        try:
            with self._connection.cursor() as cursor:
                cursor.execute("SELECT 1")
                cursor.fetchone()
            return True
        except Exception:
            return False

    def start_capture(self, tables: List[str]) -> Iterator[Dict[str, Any]]:
        try:
            import sqlite3
            import os

            self._stop_capture.clear()
            current_offset = self._offset_manager.get_current_offset()
            self._last_frame = current_offset.frame_number

            while not self._stop_capture.is_set():
                self._heartbeat_manager.beat()

                try:
                    wal_path = self.config.extra_params.get("wal_file", f"{self.config.database}-wal")
                    if os.path.exists(wal_path):
                        wal_size = os.path.getsize(wal_path)

                        with open(wal_path, "rb") as f:
                            f.seek(self._last_frame)
                            new_data = f.read()

                            if new_data:
                                events = self._parse_wal_frames(new_data, tables)
                                for event in events:
                                    self._last_frame += event.get("frame_size", 0)
                                    self._offset_manager.update_offset(
                                        frame_number=self._last_frame,
                                    )
                                    yield event

                except Exception as e:
                    print(f"SQLite WAL polling error: {e}")

                time.sleep(self._poll_interval)

        except ImportError:
            print("sqlite3 not available, using mock mode")
            self._stop_capture.clear()

            event_counter = 0
            while not self._stop_capture.is_set():
                self._heartbeat_manager.beat()
                event_counter += 1

                mock_event = {
                    "timestamp": time.time(),
                    "frame_number": event_counter,
                    "database": self.config.database,
                    "table": tables[event_counter % len(tables)] if tables else "mock_table",
                    "operation": ["INSERT", "UPDATE", "DELETE"][event_counter % 3],
                    "rowid": event_counter,
                    "data": {"id": event_counter, "name": f"user_{event_counter}"},
                    "frame_size": 512,
                }

                self._last_frame += mock_event["frame_size"]
                self._offset_manager.update_offset(frame_number=self._last_frame)
                yield mock_event
                time.sleep(self._poll_interval)

        except Exception as e:
            print(f"SQLite capture error: {e}")
            self._last_error = e

    def stop_capture(self) -> None:
        self._stop_capture.set()

    def get_heartbeat(self) -> Optional[float]:
        if not self.is_connected():
            return None

        try:
            with self._connection.cursor() as cursor:
                cursor.execute("SELECT datetime('now')")
                result = cursor.fetchone()
                self._heartbeat_manager.beat()
                return time.time()
        except Exception:
            return None

    def _parse_wal_frames(self, data: bytes, tables: List[str]) -> List[Dict[str, Any]]:
        events = []
        offset = 0
        page_size = self.config.extra_params.get("page_size", 4096)
        frame_size = page_size + 24
        frame_number = 0

        while offset + frame_size <= len(data):
            frame_header = data[offset:offset + 24]
            page_number = int.from_bytes(frame_header[0:4], "big")
            db_size = int.from_bytes(frame_header[4:8], "big")

            page_data = data[offset + 24:offset + frame_size]
            page_type = page_data[0]

            if page_type == 0x05:
                table_name = self._get_table_name(page_number)
                if not tables or table_name in tables:
                    events.append({
                        "timestamp": time.time(),
                        "frame_number": frame_number,
                        "page_number": page_number,
                        "database": self.config.database,
                        "table": table_name,
                        "operation": "INSERT",
                        "page_data": page_data,
                        "frame_size": frame_size,
                    })

            offset += frame_size
            frame_number += 1

        return events

    def _get_table_name(self, root_page: int) -> str:
        try:
            with self._connection.cursor() as cursor:
                cursor.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND rootpage=?",
                    (root_page,)
                )
                result = cursor.fetchone()
                if result:
                    return result[0]
        except Exception:
            pass
        return f"table_page_{root_page}"
