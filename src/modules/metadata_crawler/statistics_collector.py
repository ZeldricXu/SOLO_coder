"""Statistics collector for metadata crawler module."""
from __future__ import annotations

import csv
import json
import os
import statistics
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple
from uuid import uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import SchemaField, SchemaInfo
from ...infrastructure.logging.structured_logger import LogManager


@dataclass
class FieldStatistics:
    field_name: str
    data_type: str
    count: int = 0
    null_count: int = 0
    unique_count: int = 0
    min_value: Optional[Any] = None
    max_value: Optional[Any] = None
    mean: Optional[float] = None
    median: Optional[float] = None
    std_dev: Optional[float] = None
    top_values: List[Tuple[Any, int]] = field(default_factory=list)
    sample_values: List[Any] = field(default_factory=list)


@dataclass
class TableStatistics:
    table_name: str
    row_count: int = 0
    col_count: int = 0
    size_bytes: int = 0
    field_stats: Dict[str, FieldStatistics] = field(default_factory=dict)
    sample_data: List[Dict[str, Any]] = field(default_factory=list)
    collected_at: datetime = field(default_factory=datetime.utcnow)


class StatisticsCollector:
    def __init__(self, top_n_values: int = 10, sample_size: int = 100) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._top_n_values = top_n_values
        self._sample_size = sample_size

    def collect_from_csv(
        self,
        file_path: str,
        schema: Optional[SchemaInfo] = None,
        delimiter: str = ",",
        has_header: bool = True,
        encoding: str = "utf-8",
    ) -> TableStatistics:
        if not os.path.exists(file_path):
            raise ValidationError(
                message=f"File not found: {file_path}",
                suggestion="Check that the file path is correct.",
            )

        table_name = os.path.splitext(os.path.basename(file_path))[0]
        stats = TableStatistics(
            table_name=table_name,
            size_bytes=os.path.getsize(file_path),
        )

        try:
            with open(file_path, "r", encoding=encoding) as f:
                reader = csv.reader(f, delimiter=delimiter)
                rows = list(reader)

                if not rows:
                    return stats

                headers = rows[0] if has_header else [f"col_{i}" for i in range(len(rows[0]))]
                data_rows = rows[1:] if has_header else rows

                stats.row_count = len(data_rows)
                stats.col_count = len(headers)

                field_stats_map = self._initialize_field_stats(headers, schema)

                for i, row in enumerate(data_rows):
                    for j, header in enumerate(headers):
                        value = row[j] if j < len(row) else None
                        self._update_field_stats(field_stats_map[header], value)

                    if i < self._sample_size:
                        sample_row = {headers[j]: row[j] if j < len(row) else None for j in range(len(headers))}
                        stats.sample_data.append(sample_row)

                stats.field_stats = field_stats_map
                self._finalize_field_stats(stats.field_stats)

                self._logger.info(
                    f"Collected statistics from CSV: {table_name}",
                    rows=stats.row_count,
                    cols=stats.col_count,
                )

        except Exception as e:
            self._logger.error(f"CSV statistics collection failed: {e}")
            raise

        return stats

    def collect_from_json(
        self,
        file_path: str,
        schema: Optional[SchemaInfo] = None,
        encoding: str = "utf-8",
    ) -> TableStatistics:
        if not os.path.exists(file_path):
            raise ValidationError(
                message=f"File not found: {file_path}",
                suggestion="Check that the file path is correct.",
            )

        table_name = os.path.splitext(os.path.basename(file_path))[0]
        stats = TableStatistics(
            table_name=table_name,
            size_bytes=os.path.getsize(file_path),
        )

        try:
            with open(file_path, "r", encoding=encoding) as f:
                data = json.load(f)

            if isinstance(data, dict):
                data_list = [data]
            elif isinstance(data, list):
                data_list = data
            else:
                return stats

            stats.row_count = len(data_list)

            all_keys = set()
            for row in data_list:
                if isinstance(row, dict):
                    all_keys.update(row.keys())

            headers = list(all_keys)
            stats.col_count = len(headers)

            field_stats_map = self._initialize_field_stats(headers, schema)

            for i, row in enumerate(data_list):
                if isinstance(row, dict):
                    for header in headers:
                        value = row.get(header)
                        self._update_field_stats(field_stats_map[header], value)

                    if i < self._sample_size:
                        stats.sample_data.append(row.copy())

            stats.field_stats = field_stats_map
            self._finalize_field_stats(stats.field_stats)

            self._logger.info(
                f"Collected statistics from JSON: {table_name}",
                rows=stats.row_count,
                cols=stats.col_count,
            )

        except Exception as e:
            self._logger.error(f"JSON statistics collection failed: {e}")
            raise

        return stats

    def collect_from_dict(
        self,
        data: List[Dict[str, Any]],
        table_name: str,
        schema: Optional[SchemaInfo] = None,
    ) -> TableStatistics:
        stats = TableStatistics(
            table_name=table_name,
            size_bytes=len(json.dumps(data).encode("utf-8")),
        )

        if not data:
            return stats

        try:
            stats.row_count = len(data)

            all_keys = set()
            for row in data:
                if isinstance(row, dict):
                    all_keys.update(row.keys())

            headers = list(all_keys)
            stats.col_count = len(headers)

            field_stats_map = self._initialize_field_stats(headers, schema)

            for i, row in enumerate(data):
                if isinstance(row, dict):
                    for header in headers:
                        value = row.get(header)
                        self._update_field_stats(field_stats_map[header], value)

                    if i < self._sample_size:
                        stats.sample_data.append(row.copy())

            stats.field_stats = field_stats_map
            self._finalize_field_stats(stats.field_stats)

            self._logger.info(
                f"Collected statistics from dict: {table_name}",
                rows=stats.row_count,
                cols=stats.col_count,
            )

        except Exception as e:
            self._logger.error(f"Dict statistics collection failed: {e}")
            raise

        return stats

    def _initialize_field_stats(
        self,
        headers: List[str],
        schema: Optional[SchemaInfo],
    ) -> Dict[str, FieldStatistics]:
        field_map = {}
        if schema:
            field_map = {f.name: f for f in schema.fields}

        field_stats: Dict[str, FieldStatistics] = {}
        for header in headers:
            field_info = field_map.get(header)
            field_stats[header] = FieldStatistics(
                field_name=header,
                data_type=field_info.data_type if field_info else "string",
            )

        return field_stats

    def _update_field_stats(self, field_stat: FieldStatistics, value: Any) -> None:
        field_stat.count += 1

        if value is None or (isinstance(value, str) and value.strip() == ""):
            field_stat.null_count += 1
            return

        str_value = str(value)

        if len(field_stat.sample_values) < self._sample_size:
            field_stat.sample_values.append(value)

        try:
            num_value = float(value)
            if field_stat.min_value is None or num_value < field_stat.min_value:
                field_stat.min_value = num_value
            if field_stat.max_value is None or num_value > field_stat.max_value:
                field_stat.max_value = num_value

            if field_stat.mean is None:
                field_stat.mean = num_value
            else:
                field_stat.mean = (field_stat.mean * (field_stat.count - field_stat.null_count - 1) + num_value) / (field_stat.count - field_stat.null_count)

        except (ValueError, TypeError):
            if field_stat.min_value is None or str_value < str(field_stat.min_value):
                field_stat.min_value = str_value
            if field_stat.max_value is None or str_value > str(field_stat.max_value):
                field_stat.max_value = str_value

        found = False
        for i, (val, count) in enumerate(field_stat.top_values):
            if val == value:
                field_stat.top_values[i] = (val, count + 1)
                found = True
                break

        if not found:
            if len(field_stat.top_values) < self._top_n_values:
                field_stat.top_values.append((value, 1))
            else:
                min_idx = min(range(len(field_stat.top_values)), key=lambda i: field_stat.top_values[i][1])
                if 1 > field_stat.top_values[min_idx][1]:
                    field_stat.top_values[min_idx] = (value, 1)

        field_stat.top_values.sort(key=lambda x: x[1], reverse=True)

    def _finalize_field_stats(self, field_stats: Dict[str, FieldStatistics]) -> None:
        for field_stat in field_stats.values():
            non_null_count = field_stat.count - field_stat.null_count
            field_stat.unique_count = len(set(field_stat.sample_values))

            if non_null_count > 1 and field_stat.data_type in ["integer", "float"] and field_stat.mean is not None:
                try:
                    values = [float(v) for v in field_stat.sample_values if v is not None]
                    if len(values) > 1:
                        field_stat.median = statistics.median(values)
                        field_stat.std_dev = statistics.stdev(values)
                except (ValueError, TypeError, statistics.StatisticsError):
                    pass

    def get_data_quality_report(self, stats: TableStatistics) -> Dict[str, Any]:
        report = {
            "table_name": stats.table_name,
            "row_count": stats.row_count,
            "col_count": stats.col_count,
            "size_bytes": stats.size_bytes,
            "completeness": {},
            "cardinality": {},
            "numerical_summary": {},
            "issues": [],
        }

        for field_name, field_stat in stats.field_stats.items():
            if field_stat.count > 0:
                completeness = (1 - field_stat.null_count / field_stat.count) * 100
                report["completeness"][field_name] = round(completeness, 2)

            if field_stat.null_count > 0:
                report["issues"].append({
                    "field": field_name,
                    "type": "missing_values",
                    "count": field_stat.null_count,
                    "percentage": round(field_stat.null_count / field_stat.count * 100, 2),
                })

            if field_stat.count > 0:
                unique_ratio = field_stat.unique_count / field_stat.count
                if unique_ratio == 1:
                    report["cardinality"][field_name] = "unique"
                elif unique_ratio > 0.5:
                    report["cardinality"][field_name] = "high"
                elif unique_ratio > 0.1:
                    report["cardinality"][field_name] = "medium"
                else:
                    report["cardinality"][field_name] = "low"

            if field_stat.data_type in ["integer", "float"]:
                report["numerical_summary"][field_name] = {
                    "min": field_stat.min_value,
                    "max": field_stat.max_value,
                    "mean": round(field_stat.mean, 4) if field_stat.mean else None,
                    "median": round(field_stat.median, 4) if field_stat.median else None,
                    "std_dev": round(field_stat.std_dev, 4) if field_stat.std_dev else None,
                }

        return report

    def export_statistics(self, stats: TableStatistics, format: str = "json") -> str:
        data = {
            "table_name": stats.table_name,
            "row_count": stats.row_count,
            "col_count": stats.col_count,
            "size_bytes": stats.size_bytes,
            "collected_at": stats.collected_at.isoformat(),
            "field_statistics": {
                name: {
                    "field_name": fs.field_name,
                    "data_type": fs.data_type,
                    "count": fs.count,
                    "null_count": fs.null_count,
                    "null_percentage": round(fs.null_count / fs.count * 100, 2) if fs.count > 0 else 0,
                    "unique_count": fs.unique_count,
                    "min_value": str(fs.min_value) if fs.min_value else None,
                    "max_value": str(fs.max_value) if fs.max_value else None,
                    "mean": round(fs.mean, 4) if fs.mean else None,
                    "median": round(fs.median, 4) if fs.median else None,
                    "std_dev": round(fs.std_dev, 4) if fs.std_dev else None,
                    "top_values": [
                        {"value": str(v), "count": c}
                        for v, c in fs.top_values
                    ],
                    "sample_values": [str(v) for v in fs.sample_values],
                }
                for name, fs in stats.field_stats.items()
            },
            "sample_data": stats.sample_data,
        }

        if format == "json":
            return json.dumps(data, indent=2, ensure_ascii=False)
        elif format == "csv":
            lines = ["field,data_type,count,null_count,unique_count,min,max,mean,median,std_dev"]
            for name, fs in stats.field_stats.items():
                lines.append(
                    f"{name},{fs.data_type},{fs.count},{fs.null_count},{fs.unique_count},"
                    f"{fs.min_value},{fs.max_value},{fs.mean},{fs.median},{fs.std_dev}"
                )
            return "\n".join(lines)
        else:
            raise ValidationError(
                message=f"Unsupported format: {format}",
                suggestion="Use 'json' or 'csv' format.",
            )
