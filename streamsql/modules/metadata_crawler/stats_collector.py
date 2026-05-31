from __future__ import annotations

import math
from collections import Counter
from datetime import datetime
from typing import Any, Optional

from streamsql.core.models import ColumnInfo, TableSchema


class StatsCollector:
    @staticmethod
    def collect_column_stats(
        values: list[Any],
        column_info: ColumnInfo,
        sample_size: int = 1000,
    ) -> ColumnInfo:
        non_null_values = [v for v in values if v is not None]

        if not non_null_values:
            column_info.stats = {
                "total_count": len(values),
                "null_count": len(values),
                "null_rate": 1.0,
                "unique_count": 0,
            }
            return column_info

        sampled = non_null_values[:sample_size]
        unique_values = set(sampled)

        stats: dict[str, Any] = {
            "total_count": len(values),
            "null_count": len(values) - len(non_null_values),
            "null_rate": (len(values) - len(non_null_values)) / len(values) if values else 0.0,
            "unique_count": len(unique_values),
            "unique_rate": len(unique_values) / len(sampled) if sampled else 0.0,
        }

        if StatsCollector._is_numeric(sampled):
            numeric_vals = [float(v) for v in sampled]
            stats.update({
                "min": min(numeric_vals),
                "max": max(numeric_vals),
                "mean": sum(numeric_vals) / len(numeric_vals),
                "median": StatsCollector._median(numeric_vals),
                "stddev": StatsCollector._stddev(numeric_vals),
                "percentiles": StatsCollector._percentiles(numeric_vals),
            })

        if StatsCollector._is_string(sampled):
            str_lengths = [len(str(v)) for v in sampled]
            stats.update({
                "min_length": min(str_lengths),
                "max_length": max(str_lengths),
                "avg_length": sum(str_lengths) / len(str_lengths),
            })

        if len(unique_values) <= min(50, len(sampled) * 0.1):
            counter = Counter(sampled)
            stats["top_values"] = counter.most_common(10)

        column_info.stats = stats
        column_info.sample_values = sampled[:10]
        return column_info

    @staticmethod
    def collect_table_stats(
        schema: TableSchema,
        data: list[dict[str, Any]],
        sample_size: int = 1000,
    ) -> TableSchema:
        sampled_data = data[:sample_size]

        for col in schema.columns:
            values = [row.get(col.name) for row in sampled_data]
            StatsCollector.collect_column_stats(values, col, sample_size)

        schema.row_count = len(data)
        schema.last_analyzed = datetime.utcnow()

        return schema

    @staticmethod
    def estimate_cardinality(values: list[Any], epsilon: float = 0.01) -> int:
        non_null = [v for v in values if v is not None]
        if not non_null:
            return 0

        max_hash = 0
        for v in non_null:
            h = hash(str(v)) & 0xFFFFFFFF
            if h > max_hash:
                max_hash = h

        if max_hash == 0:
            return len(set(non_null))

        estimated = (2**32) / max_hash
        return int(estimated * (1 + epsilon))

    @staticmethod
    def _is_numeric(values: list[Any]) -> bool:
        for v in values:
            if not isinstance(v, (int, float)):
                return False
        return True

    @staticmethod
    def _is_string(values: list[Any]) -> bool:
        for v in values:
            if not isinstance(v, str):
                return False
        return True

    @staticmethod
    def _median(values: list[float]) -> float:
        sorted_vals = sorted(values)
        n = len(sorted_vals)
        mid = n // 2
        if n % 2 == 0:
            return (sorted_vals[mid - 1] + sorted_vals[mid]) / 2
        return sorted_vals[mid]

    @staticmethod
    def _stddev(values: list[float]) -> float:
        if len(values) < 2:
            return 0.0
        mean = sum(values) / len(values)
        variance = sum((v - mean) ** 2 for v in values) / len(values)
        return math.sqrt(variance)

    @staticmethod
    def _percentiles(values: list[float], percentiles: list[int] = [25, 50, 75, 90, 95, 99]) -> dict[int, float]:
        sorted_vals = sorted(values)
        n = len(sorted_vals)
        result: dict[int, float] = {}

        for p in percentiles:
            idx = int(math.ceil((p / 100) * n)) - 1
            idx = max(0, min(idx, n - 1))
            result[p] = sorted_vals[idx]

        return result

    @staticmethod
    def estimate_size_bytes(schema: TableSchema, row_count: int) -> int:
        if row_count == 0:
            return 0

        bytes_per_row = 0
        for col in schema.columns:
            if col.type in ["integer", "bigint"]:
                bytes_per_row += 8
            elif col.type in ["float", "double"]:
                bytes_per_row += 8
            elif col.type == "boolean":
                bytes_per_row += 1
            elif col.type == "date":
                bytes_per_row += 4
            elif col.type in ["datetime", "timestamp"]:
                bytes_per_row += 8
            elif col.type == "string":
                avg_len = col.stats.get("avg_length", 32)
                bytes_per_row += int(avg_len) + 4
            elif col.type == "json":
                bytes_per_row += 256
            else:
                bytes_per_row += 64

        return bytes_per_row * row_count

    @staticmethod
    def detect_anomalies(column_info: ColumnInfo, threshold: float = 3.0) -> list[str]:
        anomalies: list[str] = []
        stats = column_info.stats

        if stats.get("null_rate", 0) > 0.9:
            anomalies.append(f"High null rate: {stats['null_rate']:.2%}")

        if stats.get("unique_rate", 0) > 0.95:
            anomalies.append("Near-unique column")

        if "stddev" in stats and stats["stddev"] > 0:
            mean = stats["mean"]
            stddev = stats["stddev"]
            for val in column_info.sample_values:
                if isinstance(val, (int, float)):
                    z_score = abs((val - mean) / stddev) if stddev > 0 else 0
                    if z_score > threshold:
                        anomalies.append(f"Outlier detected: {val}")

        return anomalies
