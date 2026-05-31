"""Anomaly detector for data quality module."""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager


@dataclass
class AnomalyScore:
    id: UUID = field(default_factory=uuid4)
    field_name: str
    row_index: int
    score: float
    threshold: float
    is_anomaly: bool
    reason: str
    value: Any
    timestamp: datetime = field(default_factory=datetime.utcnow)


@dataclass
class FieldStats:
    field_name: str
    mean: float = 0.0
    std_dev: float = 0.0
    min: float = 0.0
    max: float = 0.0
    median: float = 0.0
    q1: float = 0.0
    q3: float = 0.0
    iqr: float = 0.0
    count: int = 0
    null_count: int = 0


class AnomalyDetector:
    def __init__(self, z_score_threshold: float = 3.0, iqr_multiplier: float = 1.5) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._z_score_threshold = z_score_threshold
        self._iqr_multiplier = iqr_multiplier
        self._field_stats: Dict[str, FieldStats] = {}
        self._anomaly_history: List[AnomalyScore] = []

    def fit(self, data: List[Dict[str, Any]], numeric_fields: Optional[List[str]] = None) -> Dict[str, FieldStats]:
        self._field_stats.clear()

        if not data:
            return self._field_stats

        if numeric_fields is None:
            numeric_fields = self._detect_numeric_fields(data)

        for field_name in numeric_fields:
            field_stats = self._calculate_field_stats(data, field_name)
            self._field_stats[field_name] = field_stats

        self._logger.info(
            f"Fitted anomaly detector on {len(data)} records",
            fields_count=len(numeric_fields),
        )

        return self._field_stats

    def _detect_numeric_fields(self, data: List[Dict[str, Any]]) -> List[str]:
        numeric_fields = []
        all_keys = set()
        for row in data:
            all_keys.update(row.keys())

        for key in all_keys:
            numeric_count = 0
            for row in data:
                value = row.get(key)
                if value is not None:
                    try:
                        float(value)
                        numeric_count += 1
                    except (ValueError, TypeError):
                        pass

            if numeric_count / len(data) > 0.5:
                numeric_fields.append(key)

        return numeric_fields

    def _calculate_field_stats(self, data: List[Dict[str, Any]], field_name: str) -> FieldStats:
        values: List[float] = []
        null_count = 0

        for row in data:
            value = row.get(field_name)
            if value is None or (isinstance(value, str) and value.strip() == ""):
                null_count += 1
                continue

            try:
                num_value = float(value)
                values.append(num_value)
            except (ValueError, TypeError):
                null_count += 1

        if not values:
            return FieldStats(field_name=field_name, null_count=null_count)

        values.sort()
        count = len(values)

        mean = sum(values) / count
        variance = sum((v - mean) ** 2 for v in values) / count
        std_dev = math.sqrt(variance)

        median = values[count // 2] if count % 2 else (values[count // 2 - 1] + values[count // 2]) / 2
        q1 = values[int(count * 0.25)]
        q3 = values[int(count * 0.75)]
        iqr = q3 - q1

        return FieldStats(
            field_name=field_name,
            mean=mean,
            std_dev=std_dev,
            min=min(values),
            max=max(values),
            median=median,
            q1=q1,
            q3=q3,
            iqr=iqr,
            count=count,
            null_count=null_count,
        )

    def detect(
        self,
        data: List[Dict[str, Any]],
        methods: Optional[List[str]] = None,
        fields: Optional[List[str]] = None,
    ) -> List[AnomalyScore]:
        if not self._field_stats:
            raise ValidationError(
                message="Anomaly detector not fitted",
                suggestion="Call fit() before detect()",
            )

        if methods is None:
            methods = ["z_score", "iqr"]

        if fields is None:
            fields = list(self._field_stats.keys())

        anomalies: List[AnomalyScore] = []

        for row_idx, row in enumerate(data):
            for field_name in fields:
                if field_name not in self._field_stats:
                    continue

                value = row.get(field_name)
                if value is None or (isinstance(value, str) and value.strip() == ""):
                    continue

                try:
                    num_value = float(value)
                except (ValueError, TypeError):
                    continue

                field_stats = self._field_stats[field_name]
                score = 0.0
                reasons = []

                if "z_score" in methods and field_stats.std_dev > 0:
                    z_score = abs((num_value - field_stats.mean) / field_stats.std_dev)
                    if z_score > self._z_score_threshold:
                        score = max(score, z_score)
                        reasons.append(f"Z-score {z_score:.2f} > threshold {self._z_score_threshold}")

                if "iqr" in methods:
                    lower_bound = field_stats.q1 - self._iqr_multiplier * field_stats.iqr
                    upper_bound = field_stats.q3 + self._iqr_multiplier * field_stats.iqr

                    if num_value < lower_bound or num_value > upper_bound:
                        iqr_score = max(
                            abs(num_value - lower_bound) / (field_stats.iqr + 1e-10),
                            abs(num_value - upper_bound) / (field_stats.iqr + 1e-10),
                        )
                        score = max(score, iqr_score)
                        reasons.append(f"Value outside IQR range [{lower_bound:.2f}, {upper_bound:.2f}]")

                if "range" in methods:
                    if num_value < field_stats.min or num_value > field_stats.max:
                        range_score = max(
                            abs(num_value - field_stats.min) / (abs(field_stats.min) + 1e-10),
                            abs(num_value - field_stats.max) / (abs(field_stats.max) + 1e-10),
                        )
                        score = max(score, range_score)
                        reasons.append(f"Value outside historical range [{field_stats.min:.2f}, {field_stats.max:.2f}]")

                if score > 0:
                    threshold = self._z_score_threshold if "z_score" in methods else self._iqr_multiplier
                    is_anomaly = score >= threshold

                    anomaly = AnomalyScore(
                        field_name=field_name,
                        row_index=row_idx,
                        score=score,
                        threshold=threshold,
                        is_anomaly=is_anomaly,
                        reason="; ".join(reasons),
                        value=num_value,
                    )

                    anomalies.append(anomaly)
                    if is_anomaly:
                        self._anomaly_history.append(anomaly)

        self._logger.info(
            f"Detected {len([a for a in anomalies if a.is_anomaly])} anomalies in {len(data)} records",
        )

        return anomalies

    def detect_streaming(
        self,
        value: Any,
        field_name: str,
        methods: Optional[List[str]] = None,
    ) -> Optional[AnomalyScore]:
        if field_name not in self._field_stats:
            return None

        if value is None or (isinstance(value, str) and value.strip() == ""):
            return None

        try:
            num_value = float(value)
        except (ValueError, TypeError):
            return None

        if methods is None:
            methods = ["z_score", "iqr"]

        field_stats = self._field_stats[field_name]
        score = 0.0
        reasons = []

        if "z_score" in methods and field_stats.std_dev > 0:
            z_score = abs((num_value - field_stats.mean) / field_stats.std_dev)
            if z_score > self._z_score_threshold:
                score = max(score, z_score)
                reasons.append(f"Z-score {z_score:.2f} > threshold {self._z_score_threshold}")

        if "iqr" in methods:
            lower_bound = field_stats.q1 - self._iqr_multiplier * field_stats.iqr
            upper_bound = field_stats.q3 + self._iqr_multiplier * field_stats.iqr

            if num_value < lower_bound or num_value > upper_bound:
                iqr_score = max(
                    abs(num_value - lower_bound) / (field_stats.iqr + 1e-10),
                    abs(num_value - upper_bound) / (field_stats.iqr + 1e-10),
                )
                score = max(score, iqr_score)
                reasons.append(f"Value outside IQR range [{lower_bound:.2f}, {upper_bound:.2f}]")

        threshold = self._z_score_threshold if "z_score" in methods else self._iqr_multiplier
        is_anomaly = score >= threshold

        anomaly = AnomalyScore(
            field_name=field_name,
            row_index=-1,
            score=score,
            threshold=threshold,
            is_anomaly=is_anomaly,
            reason="; ".join(reasons) if reasons else "No anomaly detected",
            value=num_value,
        )

        if is_anomaly:
            self._anomaly_history.append(anomaly)

        return anomaly

    def get_field_stats(self, field_name: Optional[str] = None) -> Dict[str, Any]:
        if field_name:
            stats = self._field_stats.get(field_name)
            if not stats:
                return {}
            return self._stats_to_dict(stats)

        return {
            name: self._stats_to_dict(stats)
            for name, stats in self._field_stats.items()
        }

    def _stats_to_dict(self, stats: FieldStats) -> Dict[str, Any]:
        return {
            "field_name": stats.field_name,
            "mean": stats.mean,
            "std_dev": stats.std_dev,
            "min": stats.min,
            "max": stats.max,
            "median": stats.median,
            "q1": stats.q1,
            "q3": stats.q3,
            "iqr": stats.iqr,
            "count": stats.count,
            "null_count": stats.null_count,
            "z_score_threshold": self._z_score_threshold,
            "iqr_multiplier": self._iqr_multiplier,
            "z_score_bounds": [
                stats.mean - self._z_score_threshold * stats.std_dev,
                stats.mean + self._z_score_threshold * stats.std_dev,
            ],
            "iqr_bounds": [
                stats.q1 - self._iqr_multiplier * stats.iqr,
                stats.q3 + self._iqr_multiplier * stats.iqr,
            ],
        }

    def get_anomaly_history(
        self,
        field_name: Optional[str] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        limit: Optional[int] = None,
    ) -> List[Dict[str, Any]]:
        history = self._anomaly_history

        if field_name:
            history = [a for a in history if a.field_name == field_name]

        if start_time:
            history = [a for a in history if a.timestamp >= start_time]

        if end_time:
            history = [a for a in history if a.timestamp <= end_time]

        history.sort(key=lambda a: a.timestamp, reverse=True)

        if limit:
            history = history[:limit]

        return [
            {
                "id": str(a.id),
                "field_name": a.field_name,
                "row_index": a.row_index,
                "score": a.score,
                "threshold": a.threshold,
                "is_anomaly": a.is_anomaly,
                "reason": a.reason,
                "value": a.value,
                "timestamp": a.timestamp.isoformat(),
            }
            for a in history
        ]

    def get_anomaly_summary(
        self,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        history = self._anomaly_history

        if start_time:
            history = [a for a in history if a.timestamp >= start_time]

        if end_time:
            history = [a for a in history if a.timestamp <= end_time]

        anomalies = [a for a in history if a.is_anomaly]

        field_counts: Dict[str, int] = {}
        for a in anomalies:
            field_counts[a.field_name] = field_counts.get(a.field_name, 0) + 1

        return {
            "total_anomalies": len(anomalies),
            "total_scored": len(history),
            "anomaly_rate": len(anomalies) / len(history) if history else 0,
            "field_distribution": field_counts,
            "average_score": sum(a.score for a in anomalies) / len(anomalies) if anomalies else 0,
            "max_score": max((a.score for a in anomalies), default=0),
            "period_start": start_time.isoformat() if start_time else None,
            "period_end": end_time.isoformat() if end_time else None,
        }

    def mark_anomalies(
        self,
        data: List[Dict[str, Any]],
        anomalies: List[AnomalyScore],
        mark_field: str = "_is_anomaly",
        anomaly_details_field: str = "_anomaly_details",
    ) -> List[Dict[str, Any]]:
        marked_data = [row.copy() for row in data]

        for anomaly in anomalies:
            if anomaly.row_index >= 0 and anomaly.row_index < len(marked_data):
                marked_data[anomaly.row_index][mark_field] = anomaly.is_anomaly
                if anomaly.is_anomaly:
                    marked_data[anomaly.row_index][anomaly_details_field] = {
                        "field": anomaly.field_name,
                        "score": anomaly.score,
                        "reason": anomaly.reason,
                        "value": anomaly.value,
                    }

        for row in marked_data:
            if mark_field not in row:
                row[mark_field] = False

        return marked_data

    def clear_history(self) -> None:
        self._anomaly_history.clear()

    def set_thresholds(self, z_score_threshold: float, iqr_multiplier: float) -> None:
        self._z_score_threshold = z_score_threshold
        self._iqr_multiplier = iqr_multiplier
        self._logger.info(
            "Updated anomaly detection thresholds",
            z_score_threshold=z_score_threshold,
            iqr_multiplier=iqr_multiplier,
        )
