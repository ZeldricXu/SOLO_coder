import time
import struct
import random
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Iterator, Tuple
from dataclasses import dataclass

from .events import (
    EventMetadata,
    CDCEvent,
    InsertEvent,
    UpdateEvent,
    DeleteEvent,
    SchemaChangeEvent,
    TransactionEvent,
    OperationType,
    create_event_metadata,
)


class BaseParser(ABC):
    def __init__(self, config: Optional[Dict[str, Any]] = None):
        self.config = config or {}
        self.is_running = False
        self.current_position: Dict[str, Any] = {}

    @abstractmethod
    def parse(self, data: bytes) -> Iterator[CDCEvent]:
        pass

    @abstractmethod
    def parse_event(self, raw_event: Dict[str, Any]) -> CDCEvent:
        pass

    def start(self):
        self.is_running = True

    def stop(self):
        self.is_running = False

    def get_current_position(self) -> Dict[str, Any]:
        return self.current_position.copy()

    def set_position(self, position: Dict[str, Any]):
        self.current_position = position.copy()


class MySQLBinlogParser(BaseParser):
    EVENT_TYPE_MAP = {
        30: OperationType.INSERT,
        31: OperationType.UPDATE,
        32: OperationType.DELETE,
        2: OperationType.QUERY,
        16: OperationType.BEGIN,
        17: OperationType.COMMIT,
    }

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)
        self.server_id = self.config.get("server_id", 1)
        self.binlog_file = self.config.get("binlog_file", "")
        self.binlog_position = self.config.get("binlog_position", 4)
        self.current_gtid = ""

    def parse(self, data: bytes) -> Iterator[CDCEvent]:
        offset = 0
        while offset < len(data) and self.is_running:
            if offset + 19 > len(data):
                break

            header = data[offset:offset + 19]
            if len(header) < 19:
                break

            timestamp, event_type, server_id, event_size, next_position, flags = struct.unpack(
                "<IBIHII", header
            )

            if offset + event_size > len(data):
                break

            event_data = data[offset + 19:offset + event_size]

            raw_event = {
                "timestamp": timestamp,
                "event_type": event_type,
                "server_id": server_id,
                "event_size": event_size,
                "next_position": next_position,
                "flags": flags,
                "data": event_data,
            }

            try:
                event = self.parse_event(raw_event)
                if event:
                    self.binlog_position = next_position
                    self.current_position = {
                        "binlog_file": self.binlog_file,
                        "binlog_position": next_position,
                        "gtid": self.current_gtid,
                        "timestamp": timestamp,
                    }
                    yield event
            except Exception as e:
                print(f"Error parsing binlog event: {e}")

            offset += event_size

    def parse_event(self, raw_event: Dict[str, Any]) -> Optional[CDCEvent]:
        event_type = raw_event["event_type"]
        event_data = raw_event["data"]
        timestamp = raw_event["timestamp"]

        if event_type == 33:
            gtid_data = self._parse_gtid_event(event_data)
            self.current_gtid = gtid_data["gtid"]
            return None

        op_type = self.EVENT_TYPE_MAP.get(event_type)

        if op_type == OperationType.INSERT:
            return self._parse_rows_event(event_data, OperationType.INSERT, timestamp)
        elif op_type == OperationType.UPDATE:
            return self._parse_rows_event(event_data, OperationType.UPDATE, timestamp)
        elif op_type == OperationType.DELETE:
            return self._parse_rows_event(event_data, OperationType.DELETE, timestamp)
        elif op_type == OperationType.QUERY:
            return self._parse_query_event(event_data, timestamp)
        elif op_type in (OperationType.BEGIN, OperationType.COMMIT):
            return self._parse_transaction_event(op_type, timestamp)

        return None

    def _parse_gtid_event(self, data: bytes) -> Dict[str, Any]:
        flags = data[0]
        sid = data[1:17].hex()
        gno = struct.unpack("<Q", data[17:25])[0]
        return {
            "gtid": f"{sid[:8]}-{sid[8:12]}-{sid[12:16]}-{sid[16:20]}-{sid[20:32]}:{gno}"
        }

    def _parse_rows_event(self, data: bytes, op_type: OperationType, timestamp: float) -> Optional[CDCEvent]:
        if len(data) < 8:
            return None

        table_id = struct.unpack("<Q", data[:8])[0]
        flags = struct.unpack("<H", data[8:10])[0]

        extra_data_len = struct.unpack("<H", data[10:12])[0]
        offset = 12 + extra_data_len

        columns_count = self._decode_length_encoded_integer(data, offset)
        offset += self._get_length_encoded_integer_length(data, offset)

        columns_present = self._parse_bitmap(data, offset, columns_count)
        offset += (columns_count + 7) // 8

        table_map = self.config.get("table_map", {})
        table_info = table_map.get(table_id, {"table": "unknown", "schema": "unknown"})

        columns = table_info.get("columns", [])
        row_data = {}

        for i, present in enumerate(columns_present):
            if present and i < len(columns):
                col_name = columns[i]
                if offset + 4 <= len(data):
                    value = struct.unpack("<I", data[offset:offset + 4])[0]
                    row_data[col_name] = value
                    offset += 4

        metadata = create_event_metadata(
            source="mysql",
            database=table_info.get("schema", ""),
            table=table_info.get("table", ""),
            binlog_file=self.binlog_file,
            binlog_position=str(raw_event["next_position"] if "next_position" in locals() else self.binlog_position),
            gtid=self.current_gtid,
            xid=table_id,
        )
        metadata.timestamp = timestamp
        metadata.server_id = self.server_id

        if op_type == OperationType.INSERT:
            return InsertEvent(
                metadata=metadata,
                table=table_info.get("table", ""),
                schema=table_info.get("schema", ""),
                new_data=row_data,
                columns=[c for i, c in enumerate(columns) if columns_present[i]] if columns else [],
            )
        elif op_type == OperationType.UPDATE:
            return UpdateEvent(
                metadata=metadata,
                table=table_info.get("table", ""),
                schema=table_info.get("schema", ""),
                old_data=row_data,
                new_data=row_data,
                updated_columns=[c for i, c in enumerate(columns) if columns_present[i]] if columns else [],
                columns=columns,
            )
        elif op_type == OperationType.DELETE:
            return DeleteEvent(
                metadata=metadata,
                table=table_info.get("table", ""),
                schema=table_info.get("schema", ""),
                old_data=row_data,
                columns=[c for i, c in enumerate(columns) if columns_present[i]] if columns else [],
            )

        return None

    def _parse_query_event(self, data: bytes, timestamp: float) -> Optional[CDCEvent]:
        if len(data) < 13:
            return None

        slave_proxy_id = struct.unpack("<I", data[:4])[0]
        execution_time = struct.unpack("<I", data[4:8])[0]
        schema_length = data[8]
        error_code = struct.unpack("<H", data[9:11])[0]
        status_vars_length = struct.unpack("<H", data[11:13])[0]

        offset = 13 + status_vars_length
        schema = data[offset:offset + schema_length].decode("utf-8", errors="ignore")
        offset += schema_length + 1

        query = data[offset:].decode("utf-8", errors="ignore")

        ddl_ops = ["CREATE", "ALTER", "DROP", "TRUNCATE"]
        for ddl_op in ddl_ops:
            if query.upper().startswith(ddl_op):
                op_enum = OperationType(ddl_op)
                table_name = self._extract_table_name(query)

                metadata = create_event_metadata(
                    source="mysql",
                    database=schema,
                    table=table_name,
                    binlog_file=self.binlog_file,
                    gtid=self.current_gtid,
                )
                metadata.timestamp = timestamp

                return SchemaChangeEvent(
                    metadata=metadata,
                    operation=op_enum,
                    schema_name=schema,
                    table_name=table_name,
                    ddl_sql=query,
                    new_schema={"sql": query},
                )

        return None

    def _parse_transaction_event(self, op_type: OperationType, timestamp: float) -> TransactionEvent:
        metadata = create_event_metadata(
            source="mysql",
            database=self.config.get("database", ""),
            table="",
            binlog_file=self.binlog_file,
            gtid=self.current_gtid,
        )
        metadata.timestamp = timestamp

        return TransactionEvent(
            metadata=metadata,
            operation=op_type,
            transaction_id=self.current_gtid or str(int(time.time())),
            events_count=0,
        )

    def _decode_length_encoded_integer(self, data: bytes, offset: int) -> int:
        if offset >= len(data):
            return 0
        first_byte = data[offset]
        if first_byte < 251:
            return first_byte
        elif first_byte == 251:
            return 0
        elif first_byte == 252:
            return struct.unpack("<H", data[offset + 1:offset + 3])[0]
        elif first_byte == 253:
            return struct.unpack("<I", data[offset + 1:offset + 4] + b'\x00')[0]
        else:
            return struct.unpack("<Q", data[offset + 1:offset + 9])[0]

    def _get_length_encoded_integer_length(self, data: bytes, offset: int) -> int:
        if offset >= len(data):
            return 1
        first_byte = data[offset]
        if first_byte < 251:
            return 1
        elif first_byte == 251:
            return 1
        elif first_byte == 252:
            return 3
        elif first_byte == 253:
            return 4
        else:
            return 9

    def _parse_bitmap(self, data: bytes, offset: int, num_bits: int) -> List[bool]:
        num_bytes = (num_bits + 7) // 8
        if offset + num_bytes > len(data):
            return [False] * num_bits

        bitmap = []
        for i in range(num_bits):
            byte_idx = offset + (i // 8)
            bit_idx = i % 8
            bitmap.append(bool(data[byte_idx] & (1 << bit_idx)))
        return bitmap

    def _extract_table_name(self, query: str) -> str:
        import re
        match = re.search(r"TABLE\s+`?(\w+)`?\.`?(\w+)`?|TABLE\s+`?(\w+)`?", query, re.IGNORECASE)
        if match:
            return match.group(2) or match.group(3) or ""
        return ""


class PostgreSQLWALParser(BaseParser):
    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)
        self.system_identifier = self.config.get("system_identifier", "")
        self.timeline = self.config.get("timeline", 1)
        self.current_lsn = self.config.get("start_lsn", 0)
        self.current_xid = 0

    def parse(self, data: bytes) -> Iterator[CDCEvent]:
        offset = 0
        while offset < len(data) and self.is_running:
            if offset + 24 > len(data):
                break

            record_header = data[offset:offset + 24]
            lsn, prev_lsn, xid, record_length, record_type = struct.unpack(
                "<QQQIB", record_header
            )

            if offset + record_length > len(data):
                break

            record_data = data[offset + 24:offset + record_length]

            raw_event = {
                "lsn": lsn,
                "prev_lsn": prev_lsn,
                "xid": xid,
                "record_type": record_type,
                "data": record_data,
            }

            try:
                event = self.parse_event(raw_event)
                if event:
                    self.current_lsn = lsn
                    self.current_xid = xid
                    self.current_position = {
                        "lsn": lsn,
                        "xid": xid,
                        "timeline": self.timeline,
                    }
                    yield event
            except Exception as e:
                print(f"Error parsing WAL record: {e}")

            offset += record_length

    def parse_event(self, raw_event: Dict[str, Any]) -> Optional[CDCEvent]:
        record_type = raw_event["record_type"]
        record_data = raw_event["data"]
        lsn = raw_event["lsn"]
        xid = raw_event["xid"]

        record_type_map = {
            0x49: OperationType.INSERT,
            0x55: OperationType.UPDATE,
            0x44: OperationType.DELETE,
            0x54: OperationType.TRUNCATE,
            0x42: OperationType.BEGIN,
            0x43: OperationType.COMMIT,
        }

        op_type = record_type_map.get(record_type)

        if op_type in (OperationType.INSERT, OperationType.UPDATE, OperationType.DELETE):
            return self._parse_data_record(record_data, op_type, lsn, xid)
        elif op_type in (OperationType.BEGIN, OperationType.COMMIT):
            return self._parse_transaction_record(op_type, lsn, xid)
        elif op_type == OperationType.TRUNCATE:
            return self._parse_truncate_record(record_data, lsn, xid)

        return None

    def _parse_data_record(self, data: bytes, op_type: OperationType, lsn: int, xid: int) -> Optional[CDCEvent]:
        if len(data) < 4:
            return None

        relfilenode = struct.unpack("<I", data[:4])[0]
        schema, table = self._get_table_info(relfilenode)

        offset = 4
        tuple_data = {}
        columns = []

        while offset + 2 < len(data):
            col_len = struct.unpack("<H", data[offset:offset + 2])[0]
            offset += 2

            if offset + col_len <= len(data):
                col_name = f"col_{len(columns)}"
                columns.append(col_name)

                if col_len == 8:
                    value = struct.unpack("<Q", data[offset:offset + col_len])[0]
                elif col_len == 4:
                    value = struct.unpack("<I", data[offset:offset + col_len])[0]
                else:
                    try:
                        value = data[offset:offset + col_len].decode("utf-8", errors="ignore")
                    except:
                        value = data[offset:offset + col_len].hex()

                tuple_data[col_name] = value
                offset += col_len

        metadata = create_event_metadata(
            source="postgresql",
            database=self.config.get("database", ""),
            table=table,
            gtid="",
            lsn=lsn,
            xid=xid,
        )
        metadata.schema = schema

        if op_type == OperationType.INSERT:
            return InsertEvent(
                metadata=metadata,
                table=table,
                schema=schema,
                new_data=tuple_data,
                columns=columns,
            )
        elif op_type == OperationType.UPDATE:
            return UpdateEvent(
                metadata=metadata,
                table=table,
                schema=schema,
                old_data=tuple_data,
                new_data=tuple_data,
                updated_columns=columns,
                columns=columns,
            )
        elif op_type == OperationType.DELETE:
            return DeleteEvent(
                metadata=metadata,
                table=table,
                schema=schema,
                old_data=tuple_data,
                columns=columns,
            )

        return None

    def _parse_transaction_record(self, op_type: OperationType, lsn: int, xid: int) -> TransactionEvent:
        metadata = create_event_metadata(
            source="postgresql",
            database=self.config.get("database", ""),
            table="",
            lsn=lsn,
            xid=xid,
        )

        return TransactionEvent(
            metadata=metadata,
            operation=op_type,
            transaction_id=str(xid),
            events_count=0,
        )

    def _parse_truncate_record(self, data: bytes, lsn: int, xid: int) -> SchemaChangeEvent:
        if len(data) >= 4:
            relfilenode = struct.unpack("<I", data[:4])[0]
            schema, table = self._get_table_info(relfilenode)
        else:
            schema, table = "", ""

        metadata = create_event_metadata(
            source="postgresql",
            database=self.config.get("database", ""),
            table=table,
            lsn=lsn,
            xid=xid,
        )
        metadata.schema = schema

        return SchemaChangeEvent(
            metadata=metadata,
            operation=OperationType.TRUNCATE,
            schema_name=schema,
            table_name=table,
            ddl_sql=f"TRUNCATE TABLE {schema}.{table}",
        )

    def _get_table_info(self, relfilenode: int) -> Tuple[str, str]:
        table_map = self.config.get("table_map", {})
        info = table_map.get(relfilenode, {"schema": "public", "table": f"table_{relfilenode}"})
        return info.get("schema", "public"), info.get("table", f"table_{relfilenode}")


class SQLiteWALParser(BaseParser):
    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)
        self.database_path = self.config.get("database_path", "")
        self.current_frame = self.config.get("start_frame", 0)
        self.page_size = self.config.get("page_size", 4096)

    def parse(self, data: bytes) -> Iterator[CDCEvent]:
        magic = data[:4]
        if magic not in (b"\x37\x7f\x06\x82", b"\x37\x7f\x06\x83"):
            raise ValueError("Invalid WAL file magic number")

        file_format_version = struct.unpack("<I", data[4:8])[0]
        page_size = struct.unpack("<I", data[8:12])[0]
        checkpoint_seq = struct.unpack("<I", data[12:16])[0]
        salt_1 = struct.unpack("<I", data[16:20])[0]
        salt_2 = struct.unpack("<I", data[20:24])[0]
        checksum_1 = struct.unpack("<I", data[24:28])[0]
        checksum_2 = struct.unpack("<I", data[28:32])[0]

        self.page_size = page_size
        frame_size = page_size + 24

        offset = 32
        frame_number = 0

        while offset + frame_size <= len(data) and self.is_running:
            frame_header = data[offset:offset + 24]
            page_number = struct.unpack(">I", frame_header[0:4])[0]
            db_size = struct.unpack(">I", frame_header[4:8])[0]
            salt_1_frame = struct.unpack("<I", frame_header[8:12])[0]
            salt_2_frame = struct.unpack("<I", frame_header[12:16])[0]
            checksum_1_frame = struct.unpack("<I", frame_header[16:20])[0]
            checksum_2_frame = struct.unpack("<I", frame_header[20:24])[0]

            page_data = data[offset + 24:offset + frame_size]

            if salt_1_frame == salt_1 and salt_2_frame == salt_2:
                raw_event = {
                    "frame_number": frame_number,
                    "page_number": page_number,
                    "db_size": db_size,
                    "page_data": page_data,
                }

                try:
                    event = self.parse_event(raw_event)
                    if event:
                        self.current_frame = frame_number
                        self.current_position = {
                            "frame_number": frame_number,
                            "page_number": page_number,
                            "db_size": db_size,
                        }
                        yield event
                except Exception as e:
                    print(f"Error parsing WAL frame: {e}")

            offset += frame_size
            frame_number += 1

    def parse_event(self, raw_event: Dict[str, Any]) -> Optional[CDCEvent]:
        page_data = raw_event["page_data"]
        frame_number = raw_event["frame_number"]
        page_number = raw_event["page_number"]

        page_type = page_data[0]
        page_type_map = {
            0x02: "index_leaf",
            0x05: "table_leaf",
            0x0a: "index_interior",
            0x0d: "table_interior",
        }

        if page_type == 0x05:
            return self._parse_table_leaf_page(page_data, page_number, frame_number)

        return None

    def _parse_table_leaf_page(self, page_data: bytes, page_number: int, frame_number: int) -> Optional[CDCEvent]:
        first_freeblock = struct.unpack(">H", page_data[1:3])[0]
        num_cells = struct.unpack(">H", page_data[3:5])[0]
        cell_content_start = struct.unpack(">H", page_data[5:7])[0]
        num_fragmented_bytes = page_data[7]

        table_name = self._get_table_name(page_number)
        schema = "main"

        cells = []
        for i in range(num_cells):
            cell_ptr_offset = 8 + i * 2
            if cell_ptr_offset + 2 <= len(page_data):
                cell_ptr = struct.unpack(">H", page_data[cell_ptr_offset:cell_ptr_offset + 2])[0]
                if cell_ptr < len(page_data):
                    payload_length, h = self._decode_varint(page_data, cell_ptr)
                    cell_ptr += h
                    rowid, h = self._decode_varint(page_data, cell_ptr)
                    cell_ptr += h

                    payload = page_data[cell_ptr:cell_ptr + payload_length]
                    row_data = self._parse_row_payload(payload)

                    cells.append({
                        "rowid": rowid,
                        "data": row_data,
                    })

        if not cells:
            return None

        row_data = cells[0]["data"]
        columns = list(row_data.keys())

        metadata = create_event_metadata(
            source="sqlite",
            database=self.database_path,
            table=table_name,
            xid=frame_number,
        )
        metadata.schema = schema
        metadata.offset = page_number

        op_type = OperationType.INSERT if frame_number % 3 != 0 else (
            OperationType.UPDATE if frame_number % 3 == 1 else OperationType.DELETE
        )

        if op_type == OperationType.INSERT:
            return InsertEvent(
                metadata=metadata,
                table=table_name,
                schema=schema,
                new_data=row_data,
                columns=columns,
            )
        elif op_type == OperationType.UPDATE:
            return UpdateEvent(
                metadata=metadata,
                table=table_name,
                schema=schema,
                old_data=row_data,
                new_data={**row_data, "_updated": True},
                updated_columns=columns,
                columns=columns,
            )
        else:
            return DeleteEvent(
                metadata=metadata,
                table=table_name,
                schema=schema,
                old_data=row_data,
                columns=columns,
            )

    def _decode_varint(self, data: bytes, offset: int) -> Tuple[int, int]:
        result = 0
        bytes_read = 0

        for i in range(8):
            if offset + i >= len(data):
                break
            byte = data[offset + i]
            bytes_read += 1
            result = (result << 7) | (byte & 0x7f)
            if not (byte & 0x80):
                return result, bytes_read

        if offset + 8 < len(data):
            byte = data[offset + 8]
            result = (result << 8) | byte
            bytes_read += 1

        return result, bytes_read

    def _parse_row_payload(self, payload: bytes) -> Dict[str, Any]:
        offset = 0
        record_header_length, h = self._decode_varint(payload, offset)
        offset += h

        header_end = offset + record_header_length - h
        serial_types = []

        while offset < header_end and offset < len(payload):
            serial_type, h = self._decode_varint(payload, offset)
            serial_types.append(serial_type)
            offset += h

        row_data = {}
        for i, serial_type in enumerate(serial_types):
            col_name = f"column_{i}"

            if serial_type == 0:
                value = None
            elif serial_type == 1:
                if offset + 1 <= len(payload):
                    value = struct.unpack(">b", payload[offset:offset + 1])[0]
                    offset += 1
                else:
                    value = None
            elif serial_type == 2:
                if offset + 2 <= len(payload):
                    value = struct.unpack(">h", payload[offset:offset + 2])[0]
                    offset += 2
                else:
                    value = None
            elif serial_type == 3:
                if offset + 3 <= len(payload):
                    b1, b2, b3 = payload[offset:offset + 3]
                    value = (b1 << 16) | (b2 << 8) | b3
                    if value & 0x800000:
                        value -= 0x1000000
                    offset += 3
                else:
                    value = None
            elif serial_type == 4:
                if offset + 4 <= len(payload):
                    value = struct.unpack(">i", payload[offset:offset + 4])[0]
                    offset += 4
                else:
                    value = None
            elif serial_type == 5:
                if offset + 6 <= len(payload):
                    b1, b2, b3, b4, b5, b6 = payload[offset:offset + 6]
                    value = (b1 << 40) | (b2 << 32) | (b3 << 24) | (b4 << 16) | (b5 << 8) | b6
                    if value & 0x800000000000:
                        value -= 0x1000000000000
                    offset += 6
                else:
                    value = None
            elif serial_type == 6:
                if offset + 8 <= len(payload):
                    value = struct.unpack(">q", payload[offset:offset + 8])[0]
                    offset += 8
                else:
                    value = None
            elif serial_type == 7:
                if offset + 8 <= len(payload):
                    value = struct.unpack(">d", payload[offset:offset + 8])[0]
                    offset += 8
                else:
                    value = None
            elif serial_type == 8:
                value = 0
            elif serial_type == 9:
                value = 1
            elif serial_type >= 12 and serial_type % 2 == 0:
                blob_length = (serial_type - 12) // 2
                if offset + blob_length <= len(payload):
                    value = payload[offset:offset + blob_length]
                    try:
                        value = value.decode("utf-8")
                    except:
                        value = value.hex()
                    offset += blob_length
                else:
                    value = None
            elif serial_type >= 13 and serial_type % 2 == 1:
                text_length = (serial_type - 13) // 2
                if offset + text_length <= len(payload):
                    value = payload[offset:offset + text_length].decode("utf-8", errors="ignore")
                    offset += text_length
                else:
                    value = None
            else:
                value = None

            row_data[col_name] = value

        return row_data

    def _get_table_name(self, page_number: int) -> str:
        table_map = self.config.get("table_map", {})
        return table_map.get(page_number, f"table_page_{page_number}")


class MockBinlogParser(BaseParser):
    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)
        self.tables = self.config.get("tables", [])
        self.event_interval = self.config.get("event_interval", 0.1)
        self.events_per_table = self.config.get("events_per_table", 10)
        self.current_table_idx = 0
        self.event_counter = 0
        self.binlog_file = self.config.get("binlog_file", "mysql-bin.000001")
        self.binlog_position = 4
        self.gtid_counter = 1

    def generate_mock_events(self, count: Optional[int] = None) -> Iterator[CDCEvent]:
        target_count = count or (len(self.tables) * self.events_per_table)
        generated = 0

        while generated < target_count:
            for table_info in self.tables:
                if generated >= target_count:
                    break

                table = table_info.get("name", "unknown_table")
                schema = table_info.get("schema", "test")
                columns = table_info.get("columns", ["id", "name", "created_at"])

                if self.event_counter % 50 == 0:
                    yield self._generate_transaction_event(OperationType.BEGIN, schema, table)

                op_type = self._get_random_operation()
                event = self._generate_data_event(op_type, schema, table, columns)
                yield event
                generated += 1

                if self.event_counter % 50 == 49:
                    yield self._generate_transaction_event(OperationType.COMMIT, schema, table)

                self.event_counter += 1

                if self.event_counter % 100 == 0:
                    yield self._generate_schema_change_event(schema, table)

            self.current_table_idx = (self.current_table_idx + 1) % max(1, len(self.tables))

    def parse(self, data: bytes) -> Iterator[CDCEvent]:
        return iter([])

    def parse_event(self, raw_event: Dict[str, Any]) -> CDCEvent:
        event_type = raw_event.get("event_type", "data")
        if event_type == "data":
            return self._generate_data_event(
                OperationType(raw_event.get("operation", "INSERT")),
                raw_event.get("schema", "test"),
                raw_event.get("table", "test_table"),
                raw_event.get("columns", ["id", "name"]),
            )
        elif event_type == "transaction":
            return self._generate_transaction_event(
                OperationType(raw_event.get("operation", "BEGIN")),
                raw_event.get("schema", ""),
                raw_event.get("table", ""),
            )
        elif event_type == "schema_change":
            return self._generate_schema_change_event(
                raw_event.get("schema", "test"),
                raw_event.get("table", "test_table"),
            )
        raise ValueError(f"Unknown event type: {event_type}")

    def _get_random_operation(self) -> OperationType:
        r = random.random()
        if r < 0.6:
            return OperationType.INSERT
        elif r < 0.85:
            return OperationType.UPDATE
        else:
            return OperationType.DELETE

    def _generate_data_event(
        self,
        op_type: OperationType,
        schema: str,
        table: str,
        columns: List[str],
    ) -> CDCEvent:
        self.binlog_position += random.randint(50, 200)
        gtid = f"3E11FA47-71CA-11E1-9E33-C80AA9429562:{self.gtid_counter}"
        self.gtid_counter += 1

        metadata = create_event_metadata(
            source="mock",
            database=schema,
            table=table,
            binlog_file=self.binlog_file,
            binlog_position=str(self.binlog_position),
            gtid=gtid,
            xid=random.randint(1000, 9999),
        )
        metadata.server_id = 1
        metadata.thread_id = random.randint(100, 999)

        row_data = self._generate_row_data(columns)

        self.current_position = {
            "binlog_file": self.binlog_file,
            "binlog_position": self.binlog_position,
            "gtid": gtid,
        }

        if op_type == OperationType.INSERT:
            return InsertEvent(
                metadata=metadata,
                table=table,
                schema=schema,
                new_data=row_data,
                columns=columns,
            )
        elif op_type == OperationType.UPDATE:
            updated_columns = random.sample(columns, max(1, len(columns) // 2))
            new_data = {**row_data}
            for col in updated_columns:
                new_data[col] = self._generate_column_value(col)

            return UpdateEvent(
                metadata=metadata,
                table=table,
                schema=schema,
                old_data=row_data,
                new_data=new_data,
                updated_columns=updated_columns,
                columns=columns,
            )
        else:
            return DeleteEvent(
                metadata=metadata,
                table=table,
                schema=schema,
                old_data=row_data,
                columns=columns,
            )

    def _generate_row_data(self, columns: List[str]) -> Dict[str, Any]:
        row_data = {}
        for col in columns:
            row_data[col] = self._generate_column_value(col)
        return row_data

    def _generate_column_value(self, column_name: str) -> Any:
        col_lower = column_name.lower()

        if "id" in col_lower:
            return random.randint(1, 1000000)
        elif "name" in col_lower:
            return f"User_{random.randint(1, 10000)}"
        elif "email" in col_lower:
            return f"user_{random.randint(1, 10000)}@example.com"
        elif "created" in col_lower or "updated" in col_lower or "time" in col_lower or "date" in col_lower:
            return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() - random.randint(0, 86400 * 30)))
        elif "status" in col_lower:
            return random.choice(["active", "inactive", "pending", "deleted"])
        elif "amount" in col_lower or "price" in col_lower or "total" in col_lower:
            return round(random.uniform(1.0, 10000.0), 2)
        elif "count" in col_lower or "num" in col_lower or "age" in col_lower:
            return random.randint(0, 1000)
        elif "description" in col_lower or "content" in col_lower or "text" in col_lower:
            return f"This is a sample description text {random.randint(1, 1000)}"
        elif "is_" in col_lower or "has_" in col_lower or "flag" in col_lower:
            return random.choice([True, False])
        else:
            return f"value_{random.randint(1, 10000)}"

    def _generate_transaction_event(
        self,
        op_type: OperationType,
        schema: str,
        table: str,
    ) -> TransactionEvent:
        self.binlog_position += random.randint(20, 50)
        gtid = f"3E11FA47-71CA-11E1-9E33-C80AA9429562:{self.gtid_counter}"
        self.gtid_counter += 1

        metadata = create_event_metadata(
            source="mock",
            database=schema,
            table=table,
            binlog_file=self.binlog_file,
            binlog_position=str(self.binlog_position),
            gtid=gtid,
            xid=random.randint(1000, 9999),
        )

        return TransactionEvent(
            metadata=metadata,
            operation=op_type,
            transaction_id=gtid,
            events_count=random.randint(10, 100),
        )

    def _generate_schema_change_event(self, schema: str, table: str) -> SchemaChangeEvent:
        self.binlog_position += random.randint(100, 300)
        gtid = f"3E11FA47-71CA-11E1-9E33-C80AA9429562:{self.gtid_counter}"
        self.gtid_counter += 1

        metadata = create_event_metadata(
            source="mock",
            database=schema,
            table=table,
            binlog_file=self.binlog_file,
            binlog_position=str(self.binlog_position),
            gtid=gtid,
        )

        ddl_ops = [
            (OperationType.ALTER, f"ALTER TABLE `{schema}`.`{table}` ADD COLUMN `new_col` VARCHAR(255)"),
            (OperationType.CREATE, f"CREATE TABLE `{schema}`.`{table}_new` (id INT PRIMARY KEY, name VARCHAR(255))"),
            (OperationType.ALTER, f"ALTER TABLE `{schema}`.`{table}` MODIFY COLUMN `name` VARCHAR(512)"),
        ]
        op, ddl = random.choice(ddl_ops)

        return SchemaChangeEvent(
            metadata=metadata,
            operation=op,
            schema_name=schema,
            table_name=table,
            ddl_sql=ddl,
            old_schema={"columns": ["id", "name"]},
            new_schema={"columns": ["id", "name", "new_col"]},
        )
