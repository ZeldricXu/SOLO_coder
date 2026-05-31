import json
import time
import hashlib
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Union, Tuple
from dataclasses import dataclass, field

from .events import CDCEvent, EventType, OperationType


class SchemaRegistry(ABC):
    @abstractmethod
    def register(self, subject: str, schema: Dict[str, Any]) -> int:
        pass

    @abstractmethod
    def get_schema(self, subject: str, version: Optional[int] = None) -> Dict[str, Any]:
        pass

    @abstractmethod
    def get_schema_by_id(self, schema_id: int) -> Dict[str, Any]:
        pass

    @abstractmethod
    def get_versions(self, subject: str) -> List[int]:
        pass

    @abstractmethod
    def check_compatibility(self, subject: str, new_schema: Dict[str, Any]) -> bool:
        pass


class InMemorySchemaRegistry(SchemaRegistry):
    def __init__(self, compatibility_mode: str = "BACKWARD"):
        self._schemas: Dict[int, Dict[str, Any]] = {}
        self._subject_versions: Dict[str, List[int]] = {}
        self._subject_to_id: Dict[Tuple[str, int], int] = {}
        self._next_id = 1
        self.compatibility_mode = compatibility_mode

    def register(self, subject: str, schema: Dict[str, Any]) -> int:
        schema_hash = hashlib.sha256(json.dumps(schema, sort_keys=True).encode()).hexdigest()

        for schema_id, existing_schema in self._schemas.items():
            existing_hash = hashlib.sha256(
                json.dumps(existing_schema, sort_keys=True).encode()
            ).hexdigest()
            if existing_hash == schema_hash:
                existing_versions = self._subject_versions.get(subject, [])
                for version in existing_versions:
                    if self._subject_to_id.get((subject, version)) == schema_id:
                        return schema_id

        if subject in self._subject_versions:
            if not self.check_compatibility(subject, schema):
                raise ValueError(f"Schema not compatible with existing schema for {subject}")

        schema_id = self._next_id
        self._next_id += 1

        self._schemas[schema_id] = schema

        if subject not in self._subject_versions:
            self._subject_versions[subject] = []

        new_version = len(self._subject_versions[subject]) + 1
        self._subject_versions[subject].append(new_version)
        self._subject_to_id[(subject, new_version)] = schema_id

        return schema_id

    def get_schema(self, subject: str, version: Optional[int] = None) -> Dict[str, Any]:
        if subject not in self._subject_versions:
            raise ValueError(f"Subject {subject} not found")

        if version is None:
            version = max(self._subject_versions[subject])

        if version not in self._subject_versions[subject]:
            raise ValueError(f"Version {version} not found for subject {subject}")

        schema_id = self._subject_to_id[(subject, version)]
        return self._schemas[schema_id]

    def get_schema_by_id(self, schema_id: int) -> Dict[str, Any]:
        if schema_id not in self._schemas:
            raise ValueError(f"Schema ID {schema_id} not found")
        return self._schemas[schema_id]

    def get_versions(self, subject: str) -> List[int]:
        return self._subject_versions.get(subject, []).copy()

    def check_compatibility(self, subject: str, new_schema: Dict[str, Any]) -> bool:
        if self.compatibility_mode == "NONE":
            return True

        if subject not in self._subject_versions:
            return True

        latest_version = max(self._subject_versions[subject])
        schema_id = self._subject_to_id[(subject, latest_version)]
        existing_schema = self._schemas[schema_id]

        if self.compatibility_mode == "BACKWARD":
            return self._check_backward_compatibility(existing_schema, new_schema)
        elif self.compatibility_mode == "FORWARD":
            return self._check_backward_compatibility(new_schema, existing_schema)
        elif self.compatibility_mode == "FULL":
            return self._check_backward_compatibility(
                existing_schema, new_schema
            ) and self._check_backward_compatibility(new_schema, existing_schema)

        return True

    def _check_backward_compatibility(
        self, old_schema: Dict[str, Any], new_schema: Dict[str, Any]
    ) -> bool:
        old_fields = {f["name"]: f for f in old_schema.get("fields", [])}
        new_fields = {f["name"]: f for f in new_schema.get("fields", [])}

        for name, old_field in old_fields.items():
            if name not in new_fields:
                if "default" not in old_field:
                    return False

        for name, new_field in new_fields.items():
            if name in old_fields:
                old_type = old_fields[name].get("type")
                new_type = new_field.get("type")
                if old_type != new_type:
                    if not self._check_type_compatibility(old_type, new_type):
                        return False

        return True

    def _check_type_compatibility(self, old_type: Any, new_type: Any) -> bool:
        if old_type == new_type:
            return True

        if isinstance(old_type, list) and isinstance(new_type, list):
            return set(old_type) == set(new_type)

        if isinstance(old_type, list) and "null" in old_type:
            old_non_null = [t for t in old_type if t != "null"]
            if len(old_non_null) == 1:
                return self._check_type_compatibility(old_non_null[0], new_type)

        if isinstance(new_type, list) and "null" in new_type:
            new_non_null = [t for t in new_type if t != "null"]
            if len(new_non_null) == 1:
                return self._check_type_compatibility(old_type, new_non_null[0])

        numeric_types = ["int", "long", "float", "double"]
        if old_type in numeric_types and new_type in numeric_types:
            return numeric_types.index(new_type) >= numeric_types.index(old_type)

        return False


class Serializer(ABC):
    def __init__(self, schema_registry: Optional[SchemaRegistry] = None):
        self.schema_registry = schema_registry
        self._schema_cache: Dict[str, int] = {}

    @abstractmethod
    def serialize(self, event: CDCEvent) -> bytes:
        pass

    @abstractmethod
    def deserialize(self, data: bytes) -> CDCEvent:
        pass

    def _get_subject(self, event: CDCEvent) -> str:
        metadata = event.metadata
        subject_parts = []
        if metadata.database:
            subject_parts.append(metadata.database)
        if metadata.schema:
            subject_parts.append(metadata.schema)
        if metadata.table:
            subject_parts.append(metadata.table)
        subject_parts.append(event.event_type.value)
        return "-".join(subject_parts) if subject_parts else "generic-event"

    def _generate_avro_schema(self, event: CDCEvent) -> Dict[str, Any]:
        payload = event.get_payload()
        fields = []

        fields.append({"name": "event_id", "type": "string"})
        fields.append({"name": "timestamp", "type": "long"})
        fields.append({"name": "source", "type": "string"})
        fields.append({"name": "database", "type": "string"})
        fields.append({"name": "table", "type": "string"})
        fields.append({"name": "event_type", "type": "string"})

        if event.event_type == EventType.DATA:
            fields.append({"name": "operation", "type": "string"})

            if hasattr(event, "new_data"):
                for key, value in event.new_data.items():
                    fields.append(self._avro_field_from_value(key, value))
            if hasattr(event, "old_data"):
                for key, value in event.old_data.items():
                    if not any(f["name"] == f"old_{key}" for f in fields):
                        fields.append(self._avro_field_from_value(f"old_{key}", value))

        elif event.event_type == EventType.SCHEMA_CHANGE:
            fields.append({"name": "operation", "type": "string"})
            fields.append({"name": "ddl_sql", "type": "string"})

        elif event.event_type == EventType.TRANSACTION:
            fields.append({"name": "operation", "type": "string"})
            fields.append({"name": "transaction_id", "type": "string"})

        return {
            "type": "record",
            "name": f"{event.event_type.value}_event".replace("-", "_"),
            "namespace": "cdc.events",
            "fields": fields,
        }

    def _avro_field_from_value(self, name: str, value: Any) -> Dict[str, Any]:
        if value is None:
            return {"name": name, "type": ["null", "string"], "default": None}
        elif isinstance(value, bool):
            return {"name": name, "type": "boolean"}
        elif isinstance(value, int):
            if value.bit_length() <= 32:
                return {"name": name, "type": "int"}
            else:
                return {"name": name, "type": "long"}
        elif isinstance(value, float):
            return {"name": name, "type": "double"}
        elif isinstance(value, bytes):
            return {"name": name, "type": "bytes"}
        elif isinstance(value, dict):
            return {"name": name, "type": {"type": "map", "values": "string"}}
        elif isinstance(value, list):
            return {"name": name, "type": {"type": "array", "items": "string"}}
        else:
            return {"name": name, "type": "string"}


class JSONSerializer(Serializer):
    def __init__(
        self,
        schema_registry: Optional[SchemaRegistry] = None,
        pretty: bool = False,
        include_schema: bool = False,
    ):
        super().__init__(schema_registry)
        self.pretty = pretty
        self.include_schema = include_schema

    def serialize(self, event: CDCEvent) -> bytes:
        data = event.to_dict()

        if self.include_schema and self.schema_registry:
            subject = self._get_subject(event)
            try:
                schema = self.schema_registry.get_schema(subject)
                data["$schema"] = schema
            except ValueError:
                schema = self._generate_avro_schema(event)
                schema_id = self.schema_registry.register(subject, schema)
                data["$schema_id"] = schema_id

        indent = 2 if self.pretty else None
        return json.dumps(data, ensure_ascii=False, indent=indent, default=str).encode("utf-8")

    def deserialize(self, data: bytes) -> CDCEvent:
        json_data = json.loads(data.decode("utf-8"))
        return CDCEvent.from_dict(json_data)


class AvroSerializer(Serializer):
    def __init__(self, schema_registry: SchemaRegistry, use_schema_id: bool = True):
        super().__init__(schema_registry)
        self.use_schema_id = use_schema_id

    def serialize(self, event: CDCEvent) -> bytes:
        if not self.schema_registry:
            raise ValueError("SchemaRegistry is required for AvroSerializer")

        subject = self._get_subject(event)
        schema = self._generate_avro_schema(event)

        try:
            schema_id = self.schema_registry.register(subject, schema)
        except ValueError:
            schema_id = self._schema_cache.get(subject)
            if schema_id is None:
                schema = self.schema_registry.get_schema(subject)
                for sid, s in self.schema_registry._schemas.items():
                    if s == schema:
                        schema_id = sid
                        break
                if schema_id is None:
                    raise

        self._schema_cache[subject] = schema_id

        record = self._event_to_avro_record(event)

        output = bytearray()
        output.append(0)
        output.extend(schema_id.to_bytes(4, byteorder="big"))

        avro_bytes = self._encode_avro_binary(record, schema)
        output.extend(avro_bytes)

        return bytes(output)

    def deserialize(self, data: bytes) -> CDCEvent:
        if data[0] != 0:
            raise ValueError("Invalid magic byte for Avro message")

        schema_id = int.from_bytes(data[1:5], byteorder="big")
        payload = data[5:]

        if not self.schema_registry:
            raise ValueError("SchemaRegistry is required for AvroSerializer")

        schema = self.schema_registry.get_schema_by_id(schema_id)
        record = self._decode_avro_binary(payload, schema)

        event_dict = {
            "event_type": record.get("event_type", "data"),
            "metadata": {
                "event_id": record.get("event_id", ""),
                "timestamp": record.get("timestamp", 0),
                "source": record.get("source", ""),
                "database": record.get("database", ""),
                "table": record.get("table", ""),
            },
            "payload": self._avro_record_to_payload(record, schema),
        }

        return CDCEvent.from_dict(event_dict)

    def _event_to_avro_record(self, event: CDCEvent) -> Dict[str, Any]:
        metadata = event.metadata
        record = {
            "event_id": metadata.event_id,
            "timestamp": int(metadata.timestamp * 1000),
            "source": metadata.source,
            "database": metadata.database,
            "table": metadata.table,
            "event_type": event.event_type.value,
        }

        payload = event.get_payload()
        for key, value in payload.items():
            if isinstance(value, dict):
                for k, v in value.items():
                    if key == "old_data":
                        record[f"old_{k}"] = v
                    elif key == "new_data":
                        record[k] = v
            elif key == "operation":
                record["operation"] = value.value if hasattr(value, "value") else str(value)
            else:
                record[key] = value

        return record

    def _encode_avro_binary(self, record: Dict[str, Any], schema: Dict[str, Any]) -> bytes:
        import struct
        output = bytearray()

        for field in schema.get("fields", []):
            field_name = field["name"]
            field_type = field["type"]
            value = record.get(field_name, field.get("default"))

            output.extend(self._encode_avro_value(value, field_type))

        return bytes(output)

    def _encode_avro_value(self, value: Any, field_type: Any) -> bytes:
        import struct

        if isinstance(field_type, list):
            if value is None and "null" in field_type:
                return b"\x00"
            for t in field_type:
                if t != "null":
                    return b"\x01" + self._encode_avro_value(value, t)
            return b"\x00"

        if value is None:
            return b"\x00"

        if field_type == "null":
            return b"\x00"
        elif field_type == "boolean":
            return b"\x01" if value else b"\x00"
        elif field_type == "int":
            return self._encode_varint((value << 1) ^ (value >> 31))
        elif field_type == "long":
            return self._encode_varint((value << 1) ^ (value >> 63))
        elif field_type == "float":
            return struct.pack("<f", value)
        elif field_type == "double":
            return struct.pack("<d", value)
        elif field_type == "string":
            s = str(value).encode("utf-8")
            return self._encode_varint(len(s)) + s
        elif field_type == "bytes":
            b = value if isinstance(value, bytes) else str(value).encode("utf-8")
            return self._encode_varint(len(b)) + b
        else:
            s = str(value).encode("utf-8")
            return self._encode_varint(len(s)) + s

    def _encode_varint(self, n: int) -> bytes:
        output = bytearray()
        while True:
            to_write = n & 0x7F
            n >>= 7
            if n:
                output.append(to_write | 0x80)
            else:
                output.append(to_write)
                break
        return bytes(output)

    def _decode_avro_binary(self, data: bytes, schema: Dict[str, Any]) -> Dict[str, Any]:
        record = {}
        offset = 0

        for field in schema.get("fields", []):
            field_name = field["name"]
            field_type = field["type"]
            value, offset = self._decode_avro_value(data, offset, field_type)
            record[field_name] = value

        return record

    def _decode_avro_value(self, data: bytes, offset: int, field_type: Any) -> Tuple[Any, int]:
        import struct

        if isinstance(field_type, list):
            if offset >= len(data):
                return None, offset
            type_index = data[offset]
            offset += 1
            if type_index == 0:
                return None, offset
            else:
                for t in field_type:
                    if t != "null":
                        return self._decode_avro_value(data, offset, t)
                return None, offset

        if field_type == "null":
            return None, offset
        elif field_type == "boolean":
            if offset >= len(data):
                return False, offset
            val = data[offset] != 0
            return val, offset + 1
        elif field_type in ("int", "long"):
            value, offset = self._decode_varint(data, offset)
            if field_type == "int":
                value = (value >> 1) ^ -(value & 1)
            else:
                value = (value >> 1) ^ -(value & 1)
            return value, offset
        elif field_type == "float":
            if offset + 4 > len(data):
                return 0.0, offset
            val = struct.unpack_from("<f", data, offset)[0]
            return val, offset + 4
        elif field_type == "double":
            if offset + 8 > len(data):
                return 0.0, offset
            val = struct.unpack_from("<d", data, offset)[0]
            return val, offset + 8
        elif field_type in ("string", "bytes"):
            length, offset = self._decode_varint(data, offset)
            if offset + length > len(data):
                return "", offset
            val = data[offset:offset + length]
            if field_type == "string":
                val = val.decode("utf-8", errors="replace")
            return val, offset + length
        else:
            length, offset = self._decode_varint(data, offset)
            if offset + length > len(data):
                return "", offset
            val = data[offset:offset + length].decode("utf-8", errors="replace")
            return val, offset + length

    def _decode_varint(self, data: bytes, offset: int) -> Tuple[int, int]:
        result = 0
        shift = 0
        while True:
            if offset >= len(data):
                break
            byte = data[offset]
            offset += 1
            result |= (byte & 0x7F) << shift
            if not (byte & 0x80):
                break
            shift += 7
        return result, offset

    def _avro_record_to_payload(self, record: Dict[str, Any], schema: Dict[str, Any]) -> Dict[str, Any]:
        payload = {}
        payload["operation"] = record.get("operation", "INSERT")

        old_data = {}
        new_data = {}

        for key, value in record.items():
            if key.startswith("old_"):
                old_data[key[4:]] = value
            elif key not in (
                "event_id", "timestamp", "source", "database", "table",
                "event_type", "operation", "ddl_sql", "transaction_id", "events_count"
            ):
                new_data[key] = value

        if old_data:
            payload["old_data"] = old_data
        if new_data:
            payload["new_data"] = new_data

        if "ddl_sql" in record:
            payload["ddl_sql"] = record["ddl_sql"]
        if "transaction_id" in record:
            payload["transaction_id"] = record["transaction_id"]
        if "events_count" in record:
            payload["events_count"] = record["events_count"]

        return payload


class ProtobufSerializer(Serializer):
    def __init__(self, schema_registry: Optional[SchemaRegistry] = None):
        super().__init__(schema_registry)

    def serialize(self, event: CDCEvent) -> bytes:
        import struct

        event_dict = event.to_dict()
        json_bytes = json.dumps(event_dict, ensure_ascii=False, default=str).encode("utf-8")

        output = bytearray()
        output.append(0)

        if self.schema_registry:
            subject = self._get_subject(event)
            schema = self._generate_avro_schema(event)
            try:
                schema_id = self.schema_registry.register(subject, schema)
            except ValueError:
                schema_id = self._schema_cache.get(subject, 0)
            output.extend(schema_id.to_bytes(4, byteorder="big"))

        output.extend(struct.pack("<I", len(json_bytes)))
        output.extend(json_bytes)

        return bytes(output)

    def deserialize(self, data: bytes) -> CDCEvent:
        import struct

        offset = 0
        magic = data[offset]
        offset += 1

        if magic == 0 and len(data) >= 5:
            schema_id = int.from_bytes(data[offset:offset + 4], byteorder="big")
            offset += 4

        json_len = struct.unpack_from("<I", data, offset)[0]
        offset += 4

        json_bytes = data[offset:offset + json_len]
        json_data = json.loads(json_bytes.decode("utf-8"))

        return CDCEvent.from_dict(json_data)


class MessagePackSerializer(Serializer):
    def __init__(
        self,
        schema_registry: Optional[SchemaRegistry] = None,
        use_bin_type: bool = True,
    ):
        super().__init__(schema_registry)
        self.use_bin_type = use_bin_type

    def serialize(self, event: CDCEvent) -> bytes:
        import msgpack

        event_dict = event.to_dict()
        packed = msgpack.packb(event_dict, use_bin_type=self.use_bin_type, default=str)

        if self.schema_registry:
            subject = self._get_subject(event)
            schema = self._generate_avro_schema(event)
            try:
                schema_id = self.schema_registry.register(subject, schema)
                output = bytearray()
                output.append(1)
                output.extend(schema_id.to_bytes(4, byteorder="big"))
                output.extend(packed)
                return bytes(output)

        return packed

    def deserialize(self, data: bytes) -> CDCEvent:
        import msgpack

        offset = 0
        if len(data) > 0 and data[0] == 1:
            offset = 5
            data = data[offset:]

        event_dict = msgpack.unpackb(data, raw=False)
        return CDCEvent.from_dict(event_dict)
