"""Schema extractor for metadata crawler module."""
from __future__ import annotations

import csv
import json
import os
import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple
from uuid import uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import SchemaField, SchemaInfo
from ...infrastructure.logging.structured_logger import LogManager


@dataclass
class ExtractionResult:
    table_name: str
    schema: Optional[SchemaInfo] = None
    errors: List[str] = field(default_factory=list)
    source: str = ""
    extracted_at: datetime = field(default_factory=datetime.utcnow)


class SchemaExtractor:
    def __init__(self) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._type_patterns = {
            "datetime": [
                r"^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}",
                r"^\d{4}-\d{2}-\d{2}$",
                r"^\d{2}/\d{2}/\d{4}",
            ],
            "integer": [
                r"^-?\d+$",
            ],
            "float": [
                r"^-?\d+\.\d+$",
                r"^-?\d+(\.\d+)?[eE][+-]?\d+$",
            ],
            "boolean": [
                r"^(true|false|yes|no|1|0)$",
            ],
            "json": [
                r"^\{.*\}$",
                r"^\[.*\]$",
            ],
        }

    def extract_from_csv(
        self,
        file_path: str,
        table_name: Optional[str] = None,
        delimiter: str = ",",
        has_header: bool = True,
        encoding: str = "utf-8",
        sample_size: int = 100,
    ) -> ExtractionResult:
        if not os.path.exists(file_path):
            raise ValidationError(
                message=f"File not found: {file_path}",
                suggestion="Check that the file path is correct.",
            )

        table_name = table_name or os.path.splitext(os.path.basename(file_path))[0]
        result = ExtractionResult(table_name=table_name, source=file_path)

        try:
            with open(file_path, "r", encoding=encoding) as f:
                reader = csv.reader(f, delimiter=delimiter)
                rows = list(reader)

                if not rows:
                    result.errors.append("File is empty")
                    return result

                headers = rows[0] if has_header else [f"col_{i}" for i in range(len(rows[0]))]
                data_rows = rows[1:] if has_header else rows

                if not data_rows:
                    result.errors.append("No data rows found")
                    return result

                sample_rows = data_rows[:sample_size]
                fields = self._extract_fields(headers, sample_rows)

                schema = SchemaInfo(
                    table_name=table_name,
                    fields=fields,
                    row_count=len(data_rows),
                    size_bytes=os.path.getsize(file_path),
                    data_source=f"csv://{file_path}",
                    statistics={
                        "sample_size": len(sample_rows),
                        "has_header": has_header,
                        "delimiter": delimiter,
                        "encoding": encoding,
                    },
                )

                result.schema = schema
                self._logger.info(
                    f"Extracted schema from CSV: {table_name}",
                    fields_count=len(fields),
                    rows_count=len(data_rows),
                )

        except Exception as e:
            result.errors.append(f"Failed to extract schema: {str(e)}")
            self._logger.error(f"CSV schema extraction failed: {e}")

        return result

    def extract_from_json(
        self,
        file_path: str,
        table_name: Optional[str] = None,
        encoding: str = "utf-8",
        sample_size: int = 100,
    ) -> ExtractionResult:
        if not os.path.exists(file_path):
            raise ValidationError(
                message=f"File not found: {file_path}",
                suggestion="Check that the file path is correct.",
            )

        table_name = table_name or os.path.splitext(os.path.basename(file_path))[0]
        result = ExtractionResult(table_name=table_name, source=file_path)

        try:
            with open(file_path, "r", encoding=encoding) as f:
                data = json.load(f)

            if isinstance(data, dict):
                data_list = [data]
            elif isinstance(data, list):
                data_list = data
            else:
                result.errors.append("JSON data must be an object or array")
                return result

            if not data_list:
                result.errors.append("No data found in JSON")
                return result

            sample_data = data_list[:sample_size]
            fields = self._extract_fields_from_json(sample_data)

            schema = SchemaInfo(
                table_name=table_name,
                fields=fields,
                row_count=len(data_list),
                size_bytes=os.path.getsize(file_path),
                data_source=f"json://{file_path}",
                statistics={
                    "sample_size": len(sample_data),
                    "encoding": encoding,
                },
            )

            result.schema = schema
            self._logger.info(
                f"Extracted schema from JSON: {table_name}",
                fields_count=len(fields),
                rows_count=len(data_list),
            )

        except Exception as e:
            result.errors.append(f"Failed to extract schema: {str(e)}")
            self._logger.error(f"JSON schema extraction failed: {e}")

        return result

    def extract_from_dict(
        self,
        data: List[Dict[str, Any]],
        table_name: str,
        source: str = "dict",
    ) -> ExtractionResult:
        result = ExtractionResult(table_name=table_name, source=source)

        if not data:
            result.errors.append("No data provided")
            return result

        try:
            fields = self._extract_fields_from_json(data)

            schema = SchemaInfo(
                table_name=table_name,
                fields=fields,
                row_count=len(data),
                size_bytes=len(json.dumps(data).encode("utf-8")),
                data_source=source,
                statistics={
                    "sample_size": len(data),
                },
            )

            result.schema = schema
            self._logger.info(
                f"Extracted schema from dict: {table_name}",
                fields_count=len(fields),
                rows_count=len(data),
            )

        except Exception as e:
            result.errors.append(f"Failed to extract schema: {str(e)}")
            self._logger.error(f"Dict schema extraction failed: {e}")

        return result

    def _extract_fields(self, headers: List[str], sample_rows: List[List[str]]) -> List[SchemaField]:
        fields: List[SchemaField] = []
        col_count = len(headers)

        for i, header in enumerate(headers):
            col_values = [row[i] for row in sample_rows if i < len(row) and row[i].strip()]

            data_type, nullable = self._infer_type(col_values)

            field = SchemaField(
                name=header,
                data_type=data_type,
                nullable=nullable,
                description=f"Inferred type: {data_type}",
            )
            fields.append(field)

        return fields

    def _extract_fields_from_json(self, data: List[Dict[str, Any]]) -> List[SchemaField]:
        fields_map: Dict[str, Dict[str, Any]] = {}

        for row in data:
            for key, value in row.items():
                if key not in fields_map:
                    fields_map[key] = {
                        "types": set(),
                        "nullable": False,
                        "count": 0,
                    }

                fields_map[key]["count"] += 1

                if value is None:
                    fields_map[key]["nullable"] = True
                else:
                    fields_map[key]["types"].add(self._get_json_type(value))

        fields: List[SchemaField] = []
        total_rows = len(data)

        for name, info in fields_map.items():
            if info["count"] < total_rows:
                info["nullable"] = True

            data_type = self._resolve_type(info["types"])

            field = SchemaField(
                name=name,
                data_type=data_type,
                nullable=info["nullable"],
                description=f"Inferred type: {data_type}, present in {info['count']}/{total_rows} rows",
            )
            fields.append(field)

        return fields

    def _get_json_type(self, value: Any) -> str:
        if isinstance(value, bool):
            return "boolean"
        elif isinstance(value, int):
            return "integer"
        elif isinstance(value, float):
            return "float"
        elif isinstance(value, str):
            return self._detect_string_type(value)
        elif isinstance(value, (dict, list)):
            return "json"
        else:
            return "string"

    def _detect_string_type(self, value: str) -> str:
        for data_type, patterns in self._type_patterns.items():
            if data_type == "integer" and any(re.match(p, value) for p in self._type_patterns["float"]):
                continue
            if any(re.match(p, value, re.IGNORECASE) for p in patterns):
                return data_type
        return "string"

    def _infer_type(self, values: List[str]) -> Tuple[str, bool]:
        if not values:
            return "string", True

        nullable = len(values) < 100

        type_counts: Dict[str, int] = {}

        for value in values:
            if value is None or value.strip() == "":
                nullable = True
                continue

            detected_type = self._detect_string_type(value)
            type_counts[detected_type] = type_counts.get(detected_type, 0) + 1

        if not type_counts:
            return "string", nullable

        sorted_types = sorted(type_counts.items(), key=lambda x: x[1], reverse=True)
        return sorted_types[0][0], nullable

    def _resolve_type(self, types: set) -> str:
        if not types:
            return "string"

        type_order = ["json", "string", "float", "integer", "boolean", "datetime"]

        for t in type_order:
            if t in types:
                if t == "integer" and "float" in types:
                    return "float"
                return t

        return "string"

    def validate_schema(self, schema: SchemaInfo) -> Tuple[bool, List[str]]:
        errors: List[str] = []

        if not schema.table_name:
            errors.append("Table name is required")

        if not schema.fields:
            errors.append("At least one field is required")

        field_names = set()
        for i, field in enumerate(schema.fields):
            if not field.name:
                errors.append(f"Field {i} name is required")
            elif field.name in field_names:
                errors.append(f"Duplicate field name: {field.name}")
            else:
                field_names.add(field.name)

            if not field.data_type:
                errors.append(f"Field {field.name} data_type is required")

        return len(errors) == 0, errors
