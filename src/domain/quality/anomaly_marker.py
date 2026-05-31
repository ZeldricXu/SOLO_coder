import logging
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional, Set

import numpy as np

from src.domain.quality.rule_engine import RuleViolation, Strictness
from src.domain.quality.validator import ValidationResult
from src.infrastructure.config.settings import QualityConfig

logger = logging.getLogger(__name__)


@dataclass
class AnomalyRecord:
    database_name: str
    table_name: str
    column_name: str
    anomaly_type: str
    anomaly_value: Any
    expected_range: Optional[Dict[str, Any]] = None
    z_score: Optional[float] = None
    detected_at: str = ""
    row_data: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "database_name": self.database_name,
            "table_name": self.table_name,
            "column_name": self.column_name,
            "anomaly_type": self.anomaly_type,
            "anomaly_value": self.anomaly_value,
            "expected_range": self.expected_range,
            "z_score": round(self.z_score, 4) if self.z_score else None,
            "detected_at": self.detected_at,
        }


@dataclass
class AnomalyReport:
    database_name: str
    table_name: str
    total_records: int
    anomaly_count: int
    anomaly_ratio: float
    anomalies: List[AnomalyRecord] = field(default_factory=list)
    timestamp: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "database_name": self.database_name,
            "table_name": self.table_name,
            "total_records": self.total_records,
            "anomaly_count": self.anomaly_count,
            "anomaly_ratio": round(self.anomaly_ratio, 4),
            "anomalies": [a.to_dict() for a in self.anomalies],
            "timestamp": self.timestamp,
        }


class AnomalyMarker:
    def __init__(self, config: Optional[QualityConfig] = None):
        self._config = config or QualityConfig()
        self._threshold = self._config.anomaly_threshold
        self._baseline_stats: Dict[str, Dict[str, Any]] = {}
        self._anomaly_history: Dict[str, List[AnomalyReport]] = {}

    def compute_baseline(self, column: str, values: List[Any]) -> Dict[str, Any]:
        numeric_values = []
        for v in values:
            if v is not None:
                try:
                    numeric_values.append(float(v))
                except (ValueError, TypeError):
                    pass

        if not numeric_values:
            self._baseline_stats[column] = {"count": 0, "mean": 0, "std": 0, "min": None, "max": None}
            return self._baseline_stats[column]

        arr = np.array(numeric_values)
        stats = {
            "count": len(arr),
            "mean": float(np.mean(arr)),
            "std": float(np.std(arr)),
            "min": float(np.min(arr)),
            "max": float(np.max(arr)),
            "q1": float(np.percentile(arr, 25)),
            "q3": float(np.percentile(arr, 75)),
            "median": float(np.median(arr)),
        }
        iqr = stats["q3"] - stats["q1"]
        stats["lower_fence"] = stats["q1"] - 1.5 * iqr
        stats["upper_fence"] = stats["q3"] + 1.5 * iqr

        self._baseline_stats[column] = stats
        return stats

    def detect_anomalies(
        self,
        database_name: str,
        table_name: str,
        data: List[Dict[str, Any]],
        columns: Optional[List[str]] = None,
        method: str = "zscore",
    ) -> AnomalyReport:
        if not data:
            return AnomalyReport(
                database_name=database_name,
                table_name=table_name,
                total_records=0,
                anomaly_count=0,
                anomaly_ratio=0.0,
                timestamp=datetime.utcnow().isoformat(),
            )

        target_columns = columns or list(data[0].keys())
        anomalies: List[AnomalyRecord] = []

        for col in target_columns:
            values = [row.get(col) for row in data]
            baseline = self._baseline_stats.get(col)
            if baseline is None or baseline.get("count", 0) == 0:
                baseline = self.compute_baseline(col, values)

            if method == "zscore":
                col_anomalies = self._detect_zscore(database_name, table_name, col, data, baseline)
            elif method == "iqr":
                col_anomalies = self._detect_iqr(database_name, table_name, col, data, baseline)
            elif method == "isolation":
                col_anomalies = self._detect_isolation(database_name, table_name, col, data)
            else:
                col_anomalies = self._detect_zscore(database_name, table_name, col, data, baseline)

            anomalies.extend(col_anomalies)

        report = AnomalyReport(
            database_name=database_name,
            table_name=table_name,
            total_records=len(data),
            anomaly_count=len(anomalies),
            anomaly_ratio=len(anomalies) / max(len(data) * len(target_columns), 1),
            anomalies=anomalies,
            timestamp=datetime.utcnow().isoformat(),
        )

        key = f"{database_name}.{table_name}"
        if key not in self._anomaly_history:
            self._anomaly_history[key] = []
        self._anomaly_history[key].append(report)

        return report

    def _detect_zscore(
        self,
        database_name: str,
        table_name: str,
        column: str,
        data: List[Dict[str, Any]],
        baseline: Dict[str, Any],
    ) -> List[AnomalyRecord]:
        anomalies = []
        mean = baseline.get("mean", 0)
        std = baseline.get("std", 0)

        if std == 0:
            return anomalies

        for row in data:
            val = row.get(column)
            if val is None:
                continue
            try:
                numeric_val = float(val)
            except (ValueError, TypeError):
                continue

            z_score = abs(numeric_val - mean) / std
            if z_score > self._threshold:
                anomalies.append(AnomalyRecord(
                    database_name=database_name,
                    table_name=table_name,
                    column_name=column,
                    anomaly_type="zscore_outlier",
                    anomaly_value=numeric_val,
                    expected_range={"mean": mean, "std": std},
                    z_score=z_score,
                    detected_at=datetime.utcnow().isoformat(),
                    row_data={k: v for k, v in row.items() if k != column},
                ))

        return anomalies

    def _detect_iqr(
        self,
        database_name: str,
        table_name: str,
        column: str,
        data: List[Dict[str, Any]],
        baseline: Dict[str, Any],
    ) -> List[AnomalyRecord]:
        anomalies = []
        lower = baseline.get("lower_fence", float("-inf"))
        upper = baseline.get("upper_fence", float("inf"))

        for row in data:
            val = row.get(column)
            if val is None:
                continue
            try:
                numeric_val = float(val)
            except (ValueError, TypeError):
                continue

            if numeric_val < lower or numeric_val > upper:
                anomalies.append(AnomalyRecord(
                    database_name=database_name,
                    table_name=table_name,
                    column_name=column,
                    anomaly_type="iqr_outlier",
                    anomaly_value=numeric_val,
                    expected_range={"lower_fence": lower, "upper_fence": upper},
                    detected_at=datetime.utcnow().isoformat(),
                    row_data={k: v for k, v in row.items() if k != column},
                ))

        return anomalies

    def _detect_isolation(
        self,
        database_name: str,
        table_name: str,
        column: str,
        data: List[Dict[str, Any]],
    ) -> List[AnomalyRecord]:
        anomalies = []
        values = []
        for row in data:
            val = row.get(column)
            if val is not None:
                try:
                    values.append(float(val))
                except (ValueError, TypeError):
                    pass

        if len(values) < 10:
            return anomalies

        arr = np.array(values)
        mean = np.mean(arr)
        std = np.std(arr)
        if std == 0:
            return anomalies

        sorted_vals = np.sort(arr)
        n = len(sorted_vals)
        gaps = np.diff(sorted_vals)
        max_gap_idx = np.argmax(gaps)
        gap_size = gaps[max_gap_idx]
        gap_ratio = gap_size / max(std, 1e-10)

        if gap_ratio > 3.0:
            split_point = (sorted_vals[max_gap_idx] + sorted_vals[max_gap_idx + 1]) / 2
            smaller_group = arr[arr < split_point] if np.sum(arr < split_point) < np.sum(arr >= split_point) else arr[arr >= split_point]
            for val in smaller_group:
                anomalies.append(AnomalyRecord(
                    database_name=database_name,
                    table_name=table_name,
                    column_name=column,
                    anomaly_type="isolation_outlier",
                    anomaly_value=float(val),
                    expected_range={"split_point": float(split_point)},
                    detected_at=datetime.utcnow().isoformat(),
                ))

        return anomalies

    def mark_data(
        self,
        data: List[Dict[str, Any]],
        report: AnomalyReport,
        marker_column: str = "_is_anomaly",
    ) -> List[Dict[str, Any]]:
        anomaly_rows: Set[int] = set()

        for anomaly in report.anomalies:
            for i, row in enumerate(data):
                col = anomaly.column_name
                if row.get(col) == anomaly.anomaly_value:
                    anomaly_rows.add(i)

        marked_data = []
        for i, row in enumerate(data):
            marked_row = dict(row)
            marked_row[marker_column] = i in anomaly_rows
            if i in anomaly_rows:
                matching = [a for a in report.anomalies if a.anomaly_value == row.get(a.column_name)]
                if matching:
                    marked_row["_anomaly_details"] = [a.to_dict() for a in matching[:3]]
            marked_data.append(marked_row)

        return marked_data

    def get_anomaly_history(
        self,
        database_name: str,
        table_name: str,
        limit: int = 10,
    ) -> List[AnomalyReport]:
        key = f"{database_name}.{table_name}"
        history = self._anomaly_history.get(key, [])
        return history[-limit:]

    def get_baseline(self, column: str) -> Optional[Dict[str, Any]]:
        return self._baseline_stats.get(column)

    def set_threshold(self, threshold: float) -> None:
        self._threshold = threshold
