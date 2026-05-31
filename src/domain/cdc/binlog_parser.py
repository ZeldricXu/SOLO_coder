import struct
import logging
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, BinaryIO, Dict, Iterator, List, Optional, Tuple

from src.infrastructure.config.settings import CDCMySQLConfig

logger = logging.getLogger(__name__)


class BinlogEventType(Enum):
    UNKNOWN = "UNKNOWN"
    QUERY = "QUERY"
    TABLE_MAP = "TABLE_MAP"
    WRITE_ROWS = "WRITE_ROWS"
    UPDATE_ROWS = "UPDATE_ROWS"
    DELETE_ROWS = "DELETE_ROWS"
    XID = "XID"
    ROTATE = "ROTATE"
    FORMAT_DESC = "FORMAT_DESC"
    GTID = "GTID"


class RowEventType(Enum):
    INSERT = "INSERT"
    UPDATE = "UPDATE"
    DELETE = "DELETE"


@dataclass
class BinlogEvent:
    event_type: BinlogEventType = BinlogEventType.UNKNOWN
    timestamp: int = 0
    server_id: int = 0
    event_size: int = 0
    log_position: int = 0
    database: str = ""
    table: str = ""
    row_type: Optional[RowEventType] = None
    before_data: Dict[str, Any] = field(default_factory=dict)
    after_data: Dict[str, Any] = field(default_factory=dict)
    changed_columns: List[str] = field(default_factory=list)
    transaction_id: Optional[str] = None
    gtid: Optional[str] = None
    raw_data: bytes = b""

    @property
    def datetime(self) -> datetime:
        return datetime.utcfromtimestamp(self.timestamp)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_type": self.event_type.value,
            "timestamp": self.timestamp,
            "datetime": self.datetime.isoformat(),
            "server_id": self.server_id,
            "log_position": self.log_position,
            "database": self.database,
            "table": self.table,
            "row_type": self.row_type.value if self.row_type else None,
            "before_data": self.before_data,
            "after_data": self.after_data,
            "changed_columns": self.changed_columns,
            "transaction_id": self.transaction_id,
            "gtid": self.gtid,
        }


@dataclass
class TableMapInfo:
    database: str
    table: str
    column_count: int
    column_types: List[int] = field(default_factory=list)
    column_metadata: List[int] = field(default_factory=list)
    column_nullable: List[bool] = field(default_factory=list)


class BinlogParser:
    BINLOG_MAGIC = b"\xfe\x62\x69\x6e"

    EVENT_TYPE_MAP_V4 = {
        2: BinlogEventType.QUERY,
        6: BinlogEventType.ROTATE,
        15: BinlogEventType.FORMAT_DESC,
        16: BinlogEventType.XID,
        17: BinlogEventType.GTID,
        19: BinlogEventType.TABLE_MAP,
        30: BinlogEventType.WRITE_ROWS,
        31: BinlogEventType.UPDATE_ROWS,
        32: BinlogEventType.DELETE_ROWS,
    }

    def __init__(self, config: CDCMySQLConfig):
        self._config = config
        self._table_maps: Dict[int, TableMapInfo] = {}
        self._current_gtid: Optional[str] = None
        self._running = False

    def parse_stream(self, stream: BinaryIO) -> Iterator[BinlogEvent]:
        magic = stream.read(4)
        if magic != self.BINLOG_MAGIC:
            raise ValueError("Invalid binlog format: magic bytes mismatch")

        while self._running:
            header = stream.read(19)
            if len(header) < 19:
                break
            event = self._parse_event_header(header)
            if event is None:
                break

            payload_size = event.event_size - 19
            if payload_size > 0:
                payload = stream.read(payload_size)
                if len(payload) < payload_size:
                    break
                event.raw_data = payload
                self._parse_event_payload(event)

            yield event

    def parse_file(self, file_path: str) -> Iterator[BinlogEvent]:
        self._running = True
        with open(file_path, "rb") as f:
            yield from self.parse_stream(f)

    def _parse_event_header(self, header: bytes) -> Optional[BinlogEvent]:
        if len(header) < 19:
            return None
        timestamp, event_type_code, server_id, event_size, log_position, flags = struct.unpack(
            "<IBIIIH", header
        )

        event_type = self.EVENT_TYPE_MAP_V4.get(event_type_code, BinlogEventType.UNKNOWN)
        return BinlogEvent(
            event_type=event_type,
            timestamp=timestamp,
            server_id=server_id,
            event_size=event_size,
            log_position=log_position,
        )

    def _parse_event_payload(self, event: BinlogEvent) -> None:
        data = event.raw_data
        if event.event_type == BinlogEventType.TABLE_MAP:
            self._parse_table_map_event(event, data)
        elif event.event_type == BinlogEventType.WRITE_ROWS:
            self._parse_rows_event(event, data, RowEventType.INSERT)
        elif event.event_type == BinlogEventType.UPDATE_ROWS:
            self._parse_rows_event(event, data, RowEventType.UPDATE)
        elif event.event_type == BinlogEventType.DELETE_ROWS:
            self._parse_rows_event(event, data, RowEventType.DELETE)
        elif event.event_type == BinlogEventType.QUERY:
            self._parse_query_event(event, data)
        elif event.event_type == BinlogEventType.GTID:
            self._parse_gtid_event(event, data)
        elif event.event_type == BinlogEventType.XID:
            pass
        elif event.event_type == BinlogEventType.ROTATE:
            pass

    def _parse_table_map_event(self, event: BinlogEvent, data: bytes) -> None:
        pos = 0
        table_id = struct.unpack_from("<Q", data, pos)[0]
        pos += 8

        flags = struct.unpack_from("<H", data, pos)[0]
        pos += 2

        db_len = data[pos]
        pos += 1
        database = data[pos:pos + db_len].decode("utf-8")
        pos += db_len

        pos += 1

        tbl_len = data[pos]
        pos += 1
        table = data[pos:pos + tbl_len].decode("utf-8")
        pos += tbl_len

        pos += 1

        column_count = self._read_length_encoded_integer(data, pos)
        pos += self._length_encoded_integer_size(data, pos)

        column_types = list(data[pos:pos + column_count])
        pos += column_count

        event.database = database
        event.table = table

        table_map = TableMapInfo(
            database=database,
            table=table,
            column_count=column_count,
            column_types=column_types,
        )
        self._table_maps[table_id] = table_map

    def _parse_rows_event(self, event: BinlogEvent, data: bytes, row_type: RowEventType) -> None:
        pos = 0
        table_id = struct.unpack_from("<Q", data[:6] + b"\x00\x00")[0]
        pos = 6

        flags = struct.unpack_from("<H", data, pos)[0]
        pos += 2

        if len(data) > pos + 2:
            extra_data_len = struct.unpack_from("<H", data, pos)[0]
            pos += 2 + extra_data_len

        table_map = self._table_maps.get(table_id)
        if table_map:
            event.database = table_map.database
            event.table = table_map.table

        column_count = self._read_length_encoded_integer(data, pos)
        pos += self._length_encoded_integer_size(data, pos)

        null_bitmap_size = (column_count + 7) // 8

        if row_type == RowEventType.INSERT:
            pos += null_bitmap_size
            row_data = self._extract_row_data(data, pos, column_count, table_map)
            event.after_data = row_data
            event.row_type = row_type

        elif row_type == RowEventType.DELETE:
            pos += null_bitmap_size
            row_data = self._extract_row_data(data, pos, column_count, table_map)
            event.before_data = row_data
            event.row_type = row_type

        elif row_type == RowEventType.UPDATE:
            pos += null_bitmap_size
            before_data = self._extract_row_data(data, pos, column_count, table_map)
            pos += null_bitmap_size
            after_data = self._extract_row_data(data, pos, column_count, table_map)
            event.before_data = before_data
            event.after_data = after_data
            event.changed_columns = [
                k for k in before_data if before_data.get(k) != after_data.get(k)
            ]
            event.row_type = row_type

        event.gtid = self._current_gtid

    def _parse_query_event(self, event: BinlogEvent, data: bytes) -> None:
        pos = 0
        slave_proxy_id = struct.unpack_from("<I", data, pos)[0]
        pos += 4
        execution_time = struct.unpack_from("<I", data, pos)[0]
        pos += 4
        schema_length = data[pos]
        pos += 1
        error_code = struct.unpack_from("<H", data, pos)[0]
        pos += 2
        status_vars_length = struct.unpack_from("<H", data, pos)[0]
        pos += 2

        pos += status_vars_length
        database = data[pos:pos + schema_length].decode("utf-8", errors="replace")
        pos += schema_length + 1

        query = data[pos:].decode("utf-8", errors="replace") if pos < len(data) else ""
        event.database = database
        event.properties = {"query": query}

    def _parse_gtid_event(self, event: BinlogEvent, data: bytes) -> None:
        if len(data) < 25:
            return
        flags = data[0]
        source_id = data[1:17]
        seq_no = struct.unpack_from("<Q", data, 17)[0]
        hex_source = source_id.hex()
        self._current_gtid = f"{hex_source}:{seq_no}"
        event.gtid = self._current_gtid

    def _extract_row_data(self, data: bytes, pos: int, column_count: int, table_map: Optional[TableMapInfo]) -> Dict[str, Any]:
        row_data = {}
        for i in range(column_count):
            col_name = f"col_{i}"
            if table_map and i < len(table_map.column_types):
                col_type = table_map.column_types[i]
                value, new_pos = self._read_column_value(data, pos, col_type)
                row_data[col_name] = value
                pos = new_pos
            else:
                row_data[col_name] = None
        return row_data

    def _read_column_value(self, data: bytes, pos: int, col_type: int) -> Tuple[Any, int]:
        if pos >= len(data):
            return None, pos
        try:
            if col_type == 1:
                return data[pos], pos + 1
            elif col_type == 2:
                return struct.unpack_from("<b", data, pos)[0], pos + 1
            elif col_type == 3:
                return struct.unpack_from("<h", data, pos)[0], pos + 2
            elif col_type == 4:
                return struct.unpack_from("<i", data, pos)[0], pos + 4
            elif col_type == 5:
                return struct.unpack_from("<q", data, pos)[0], pos + 8
            elif col_type == 6:
                return struct.unpack_from("<f", data, pos)[0], pos + 4
            elif col_type == 7:
                return struct.unpack_from("<d", data, pos)[0], pos + 8
            elif col_type in (10, 14):
                date_len = data[pos]
                return data[pos + 1:pos + 1 + date_len].decode("utf-8", errors="replace"), pos + 1 + date_len
            elif col_type in (15, 16, 253, 254):
                if col_type == 254:
                    str_len = struct.unpack_from("<I", data, pos)[0]
                    pos += 4
                else:
                    str_len = data[pos]
                    pos += 1
                return data[pos:pos + str_len].decode("utf-8", errors="replace"), pos + str_len
            else:
                return None, pos
        except (struct.error, IndexError):
            return None, pos

    def _read_length_encoded_integer(self, data: bytes, pos: int) -> int:
        if pos >= len(data):
            return 0
        first = data[pos]
        if first < 251:
            return first
        elif first == 252:
            return struct.unpack_from("<H", data, pos + 1)[0]
        elif first == 253:
            return struct.unpack_from("<I", data, pos + 1)[0] & 0xFFFFFF
        elif first == 254:
            return struct.unpack_from("<Q", data, pos + 1)[0]
        return 0

    def _length_encoded_integer_size(self, data: bytes, pos: int) -> int:
        if pos >= len(data):
            return 1
        first = data[pos]
        if first < 251:
            return 1
        elif first == 252:
            return 3
        elif first == 253:
            return 4
        elif first == 254:
            return 9
        return 1

    def start(self) -> None:
        self._running = True

    def stop(self) -> None:
        self._running = False
