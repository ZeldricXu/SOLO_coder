from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from ..core.events import DomainEvent, EventBus, get_global_event_bus
from .algorithms import (
    AnomalyAlgorithm,
    AlgorithmConfig,
    ZScoreAlgorithm,
    create_algorithm,
)


@dataclass
class AnomalyResult:
    metric_name: str
    value: float
    timestamp: datetime
    is_anomaly: bool
    score: float
    algorithm: str
    details: Dict[str, Any] = field(default_factory=dict)
    severity: str = "low"

    def to_dict(self) -> Dict[str, Any]:
        return {
            "metric_name": self.metric_name,
            "value": self.value,
            "timestamp": self.timestamp.isoformat(),
            "is_anomaly": self.is_anomaly,
            "score": self.score,
            "algorithm": self.algorithm,
            "details": self.details,
            "severity": self.severity,
        }


@dataclass
class BaselineProfile:
    metric_name: str
    algorithm: str
    created_at: datetime
    updated_at: datetime
    baseline_data: Dict[str, Any] = field(default_factory=dict)
    sample_count: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "metric_name": self.metric_name,
            "algorithm": self.algorithm,
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
            "baseline_data": self.baseline_data,
            "sample_count": self.sample_count,
        }


class AnomalyDetector:
    def __init__(
        self,
        default_algorithm: str = "zscore",
        event_bus: Optional[EventBus] = None,
        logger=None,
    ):
        self._default_algorithm = default_algorithm
        self._event_bus = event_bus or get_global_event_bus()
        self._logger = logger
        self._algorithms: Dict[str, Dict[str, AnomalyAlgorithm]] = {}
        self._baselines: Dict[str, BaselineProfile] = {}
        self._history: Dict[str, List[float]] = {}
        self._anomalies: Dict[str, List[AnomalyResult]] = {}
        self._configs: Dict[str, AlgorithmConfig] = {}
        self._severity_thresholds = {"low": 1.0, "medium": 2.0, "high": 3.0}

    def register_algorithm(
        self,
        metric_name: str,
        algorithm: AnomalyAlgorithm,
        config: Optional[AlgorithmConfig] = None,
    ) -> None:
        if metric_name not in self._algorithms:
            self._algorithms[metric_name] = {}
        self._algorithms[metric_name][algorithm.get_name()] = algorithm
        if config:
            self._configs[metric_name] = config

    def create_baseline(self, metric_name: str, data: List[float], algorithm_name: Optional[str] = None) -> BaselineProfile:
        algo_name = algorithm_name or self._default_algorithm
        if metric_name not in self._algorithms:
            self._algorithms[metric_name] = {}
        if algo_name not in self._algorithms[metric_name]:
            self._algorithms[metric_name][algo_name] = create_algorithm(algo_name)
        algorithm = self._algorithms[metric_name][algo_name]
        algorithm.fit(data)
        baseline = BaselineProfile(
            metric_name=metric_name,
            algorithm=algo_name,
            created_at=datetime.now(timezone.utc),
            updated_at=datetime.now(timezone.utc),
            baseline_data=algorithm.get_baseline() or {},
            sample_count=len(data),
        )
        self._baselines[metric_name] = baseline
        self._history[metric_name] = list(data)
        return baseline

    def update_baseline(self, metric_name: str, new_data: List[float]) -> Optional[BaselineProfile]:
        if metric_name not in self._baselines:
            return None
        baseline = self._baselines[metric_name]
        if metric_name not in self._history:
            self._history[metric_name] = []
        self._history[metric_name].extend(new_data)
        if metric_name in self._algorithms and baseline.algorithm in self._algorithms[metric_name]:
            algorithm = self._algorithms[metric_name][baseline.algorithm]
            algorithm.fit(self._history[metric_name])
            baseline.baseline_data = algorithm.get_baseline() or {}
        baseline.sample_count = len(self._history[metric_name])
        baseline.updated_at = datetime.now(timezone.utc)
        return baseline

    def _calculate_severity(self, score: float) -> str:
        if score >= self._severity_thresholds["high"]:
            return "high"
        if score >= self._severity_thresholds["medium"]:
            return "medium"
        if score >= self._severity_thresholds["low"]:
            return "low"
        return "info"

    def detect(
        self,
        metric_name: str,
        value: float,
        timestamp: Optional[datetime] = None,
        algorithm_names: Optional[List[str]] = None,
    ) -> List[AnomalyResult]:
        if metric_name not in self._algorithms:
            if metric_name in self._baselines:
                algo_name = self._baselines[metric_name].algorithm
                self._algorithms[metric_name] = {algo_name: create_algorithm(algo_name)}
                if metric_name in self._history:
                    self._algorithms[metric_name][algo_name].fit(self._history[metric_name])
            else:
                return []
        if metric_name not in self._anomalies:
            self._anomalies[metric_name] = []
        ts = timestamp or datetime.now(timezone.utc)
        results = []
        algos_to_use = algorithm_names or list(self._algorithms[metric_name].keys())
        for algo_name in algos_to_use:
            if algo_name not in self._algorithms[metric_name]:
                continue
            algorithm = self._algorithms[metric_name][algo_name]
            detection = algorithm.detect(value)
            severity = self._calculate_severity(detection.get("score", 0.0))
            result = AnomalyResult(
                metric_name=metric_name,
                value=value,
                timestamp=ts,
                is_anomaly=detection.get("is_anomaly", False),
                score=detection.get("score", 0.0),
                algorithm=algo_name,
                details=detection,
                severity=severity,
            )
            results.append(result)
            if result.is_anomaly:
                self._anomalies[metric_name].append(result)
                event = DomainEvent(
                    event_type="anomaly.detected",
                    payload=result.to_dict(),
                    source="anomaly_detector",
                )
                from asyncio import create_task
                create_task(self._event_bus.publish(event))
        if metric_name not in self._history:
            self._history[metric_name] = []
        self._history[metric_name].append(value)
        return results

    def batch_detect(
        self,
        metric_name: str,
        values: List[float],
        timestamps: Optional[List[datetime]] = None,
    ) -> List[List[AnomalyResult]]:
        results = []
        for idx, value in enumerate(values):
            ts = timestamps[idx] if timestamps and idx < len(timestamps) else None
            results.append(self.detect(metric_name, value, ts))
        return results

    def get_anomalies(
        self,
        metric_name: str,
        limit: int = 100,
        min_severity: Optional[str] = None,
    ) -> List[AnomalyResult]:
        if metric_name not in self._anomalies:
            return []
        anomalies = list(self._anomalies[metric_name])
        severity_order = {"low": 0, "medium": 1, "high": 2}
        if min_severity:
            min_level = severity_order.get(min_severity, 0)
            anomalies = [a for a in anomalies if severity_order.get(a.severity, 0) >= min_level]
        anomalies.sort(key=lambda a: a.timestamp, reverse=True)
        return anomalies[:limit]

    def get_baseline(self, metric_name: str) -> Optional[BaselineProfile]:
        return self._baselines.get(metric_name)

    def get_history(self, metric_name: str, limit: int = 1000) -> List[float]:
        if metric_name not in self._history:
            return []
        return self._history[metric_name][-limit:]

    def get_stats(self) -> Dict[str, Any]:
        total_anomalies = sum(len(a) for a in self._anomalies.values())
        return {
            "metrics_tracked": len(self._algorithms),
            "baselines_count": len(self._baselines),
            "total_anomalies": total_anomalies,
            "by_metric": {
                metric: {
                    "algorithm_count": len(algos),
                    "history_size": len(self._history.get(metric, [])),
                    "anomaly_count": len(self._anomalies.get(metric, [])),
                }
                for metric, algos in self._algorithms.items()
            },
        }

    def set_severity_thresholds(self, low: float = 1.0, medium: float = 2.0, high: float = 3.0) -> None:
        self._severity_thresholds = {"low": low, "medium": medium, "high": high}

    def clear_history(self, metric_name: Optional[str] = None) -> None:
        if metric_name:
            if metric_name in self._history:
                self._history[metric_name] = []
            if metric_name in self._anomalies:
                self._anomalies[metric_name] = []
        else:
            self._history = {}
            self._anomalies = {}


_global_detector: Optional[AnomalyDetector] = None


def get_global_detector() -> AnomalyDetector:
    global _global_detector
    if _global_detector is None:
        _global_detector = AnomalyDetector()
    return _global_detector


def set_global_detector(detector: AnomalyDetector) -> None:
    global _global_detector
    _global_detector = detector
