"""Schema version manager for data access module."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import SchemaField, SchemaInfo
from ...infrastructure.logging.structured_logger import LogManager


@dataclass
class SchemaVersion:
    version: str
    schema: SchemaInfo
    created_at: datetime = field(default_factory=datetime.utcnow)
    migration_script: Optional[str] = None
    backward_compatible: bool = True
    notes: Optional[str] = None


class SchemaVersionManager:
    def __init__(self) -> None:
        self._schemas: Dict[str, List[SchemaVersion]] = {}
        self._current_versions: Dict[str, str] = {}
        self._logger = LogManager().get_logger(__name__)

    def register_schema(
        self,
        schema: SchemaInfo,
        migration_script: Optional[str] = None,
        backward_compatible: bool = True,
        notes: Optional[str] = None,
    ) -> SchemaVersion:
        table_name = schema.table_name
        version = schema.version

        if table_name not in self._schemas:
            self._schemas[table_name] = []

        existing = [s for s in self._schemas[table_name] if s.version == version]
        if existing:
            raise ValidationError(
                message=f"Schema version {version} already exists for table {table_name}",
                suggestion="Use a different version number or update the existing schema.",
            )

        schema_version = SchemaVersion(
            version=version,
            schema=schema,
            migration_script=migration_script,
            backward_compatible=backward_compatible,
            notes=notes,
        )

        self._schemas[table_name].append(schema_version)
        self._schemas[table_name].sort(key=lambda s: self._version_to_tuple(s.version))

        if table_name not in self._current_versions:
            self._current_versions[table_name] = version

        self._logger.info(
            f"Registered schema version {version} for table {table_name}",
            fields_count=len(schema.fields),
            backward_compatible=backward_compatible,
        )

        return schema_version

    def get_schema(self, table_name: str, version: Optional[str] = None) -> Optional[SchemaInfo]:
        if table_name not in self._schemas:
            return None

        if version is None:
            version = self._current_versions.get(table_name)
            if version is None:
                return None

        for schema_version in self._schemas[table_name]:
            if schema_version.version == version:
                return schema_version.schema

        return None

    def get_current_version(self, table_name: str) -> Optional[str]:
        return self._current_versions.get(table_name)

    def set_current_version(self, table_name: str, version: str) -> bool:
        if table_name not in self._schemas:
            return False

        versions = [s.version for s in self._schemas[table_name]]
        if version not in versions:
            return False

        self._current_versions[table_name] = version
        self._logger.info(f"Set current schema version for {table_name} to {version}")
        return True

    def get_all_versions(self, table_name: str) -> List[SchemaVersion]:
        return self._schemas.get(table_name, [])

    def list_tables(self) -> List[str]:
        return list(self._schemas.keys())

    def compare_schemas(self, table_name: str, version1: str, version2: str) -> Dict[str, Any]:
        schema1 = self.get_schema(table_name, version1)
        schema2 = self.get_schema(table_name, version2)

        if schema1 is None or schema2 is None:
            raise ValidationError(
                message="One or both schema versions not found",
                suggestion="Check that both schema versions exist for the table.",
            )

        fields1 = {f.name: f for f in schema1.fields}
        fields2 = {f.name: f for f in schema2.fields}

        added_fields = [name for name in fields2 if name not in fields1]
        removed_fields = [name for name in fields1 if name not in fields2]
        modified_fields = []

        for name in fields1:
            if name in fields2:
                f1 = fields1[name]
                f2 = fields2[name]
                if f1.data_type != f2.data_type or f1.nullable != f2.nullable:
                    modified_fields.append({
                        "name": name,
                        "old": {"data_type": f1.data_type, "nullable": f1.nullable},
                        "new": {"data_type": f2.data_type, "nullable": f2.nullable},
                    })

        return {
            "table_name": table_name,
            "version1": version1,
            "version2": version2,
            "added_fields": added_fields,
            "removed_fields": removed_fields,
            "modified_fields": modified_fields,
            "is_backward_compatible": len(removed_fields) == 0 and all(
                f["old"]["nullable"] or not f["new"]["nullable"] for f in modified_fields
            ),
        }

    def validate_data_against_schema(
        self,
        data: Dict[str, Any],
        table_name: str,
        version: Optional[str] = None,
    ) -> tuple[bool, List[str]]:
        schema = self.get_schema(table_name, version)
        if schema is None:
            return False, [f"Schema not found for table {table_name}"]

        errors: List[str] = []
        field_map = {f.name: f for f in schema.fields}

        for field_name, field in field_map.items():
            if field_name not in data:
                if not field.nullable:
                    errors.append(f"Missing required field: {field_name}")
            else:
                value = data[field_name]
                if not self._validate_type(value, field.data_type):
                    errors.append(
                        f"Field '{field_name}' has invalid type. Expected {field.data_type}, got {type(value).__name__}"
                    )

        for key in data:
            if key not in field_map:
                errors.append(f"Unknown field: {key}")

        return len(errors) == 0, errors

    def _validate_type(self, value: Any, expected_type: str) -> bool:
        type_map = {
            "string": str,
            "integer": int,
            "float": (int, float),
            "boolean": bool,
            "datetime": (str, datetime),
            "json": (dict, list),
            "array": list,
        }

        expected_py_type = type_map.get(expected_type.lower())
        if expected_py_type is None:
            return True

        if value is None:
            return True

        return isinstance(value, expected_py_type)

    def _version_to_tuple(self, version: str) -> tuple:
        parts = version.split(".")
        return tuple(int(p) for p in parts if p.isdigit())

    def get_migration_path(self, table_name: str, from_version: str, to_version: str) -> List[SchemaVersion]:
        if table_name not in self._schemas:
            return []

        versions = sorted(
            self._schemas[table_name],
            key=lambda s: self._version_to_tuple(s.version),
        )

        from_idx = next(
            (i for i, s in enumerate(versions) if s.version == from_version),
            -1,
        )
        to_idx = next(
            (i for i, s in enumerate(versions) if s.version == to_version),
            -1,
        )

        if from_idx == -1 or to_idx == -1:
            return []

        if from_idx < to_idx:
            return versions[from_idx + 1 : to_idx + 1]
        else:
            return list(reversed(versions[to_idx:from_idx]))

    def export_schema(self, table_name: str, version: Optional[str] = None) -> Optional[Dict[str, Any]]:
        schema = self.get_schema(table_name, version)
        if schema is None:
            return None

        return {
            "table_name": schema.table_name,
            "version": schema.version,
            "fields": [
                {
                    "name": f.name,
                    "data_type": f.data_type,
                    "nullable": f.nullable,
                    "description": f.description,
                }
                for f in schema.fields
            ],
            "row_count": schema.row_count,
            "size_bytes": schema.size_bytes,
            "statistics": schema.statistics,
        }

    def import_schema(self, schema_data: Dict[str, Any]) -> SchemaVersion:
        fields = [
            SchemaField(
                name=f["name"],
                data_type=f["data_type"],
                nullable=f.get("nullable", True),
                description=f.get("description"),
            )
            for f in schema_data.get("fields", [])
        ]

        schema = SchemaInfo(
            table_name=schema_data["table_name"],
            fields=fields,
            row_count=schema_data.get("row_count", 0),
            size_bytes=schema_data.get("size_bytes", 0),
            statistics=schema_data.get("statistics", {}),
            data_source=schema_data.get("data_source", "import"),
            version=schema_data.get("version", "1.0"),
        )

        return self.register_schema(schema)
