import re
import json
import os
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Any, Iterator, Tuple, Callable
from dataclasses import dataclass, field
from collections import defaultdict
from enum import Enum
from abc import ABC, abstractmethod

from .parser import LogEntry, LogLevel


class ScoreStrategyType(Enum):
    KEYWORD_WEIGHT = "keyword_weight"
    CONTEXT_ANALYSIS = "context_analysis"
    FREQUENCY_BASED = "frequency_based"
    HYBRID = "hybrid"


@dataclass
class ConfidenceThreshold:
    anomaly_type: str
    min_threshold: float
    critical_threshold: float

    def to_dict(self) -> Dict[str, Any]:
        return {
            "anomaly_type": self.anomaly_type,
            "min_threshold": self.min_threshold,
            "critical_threshold": self.critical_threshold
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ConfidenceThreshold":
        return cls(
            anomaly_type=data["anomaly_type"],
            min_threshold=data["min_threshold"],
            critical_threshold=data["critical_threshold"]
        )


@dataclass
class ScoreStrategyConfig:
    strategy_type: ScoreStrategyType = ScoreStrategyType.KEYWORD_WEIGHT
    keyword_weights: Dict[str, float] = field(default_factory=dict)
    context_window_size: int = 3
    frequency_decay_factor: float = 0.9
    level_weights: Dict[str, float] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "strategy_type": self.strategy_type.value,
            "keyword_weights": self.keyword_weights,
            "context_window_size": self.context_window_size,
            "frequency_decay_factor": self.frequency_decay_factor,
            "level_weights": self.level_weights
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "ScoreStrategyConfig":
        return cls(
            strategy_type=ScoreStrategyType(data.get("strategy_type", "keyword_weight")),
            keyword_weights=data.get("keyword_weights", {}),
            context_window_size=data.get("context_window_size", 3),
            frequency_decay_factor=data.get("frequency_decay_factor", 0.9),
            level_weights=data.get("level_weights", {})
        )


@dataclass
class AnomalyDetectionConfig:
    thresholds: Dict[str, ConfidenceThreshold] = field(default_factory=dict)
    score_strategy: ScoreStrategyConfig = field(default_factory=ScoreStrategyConfig)
    default_min_threshold: float = 0.3
    default_critical_threshold: float = 0.8
    enable_event_collection: bool = True
    max_events: int = 1000

    def to_dict(self) -> Dict[str, Any]:
        return {
            "thresholds": {k: v.to_dict() for k, v in self.thresholds.items()},
            "score_strategy": self.score_strategy.to_dict(),
            "default_min_threshold": self.default_min_threshold,
            "default_critical_threshold": self.default_critical_threshold,
            "enable_event_collection": self.enable_event_collection,
            "max_events": self.max_events
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "AnomalyDetectionConfig":
        thresholds = {}
        for key, threshold_data in data.get("thresholds", {}).items():
            thresholds[key] = ConfidenceThreshold.from_dict(threshold_data)
        
        score_strategy = ScoreStrategyConfig()
        if data.get("score_strategy"):
            score_strategy = ScoreStrategyConfig.from_dict(data["score_strategy"])
        
        return cls(
            thresholds=thresholds,
            score_strategy=score_strategy,
            default_min_threshold=data.get("default_min_threshold", 0.3),
            default_critical_threshold=data.get("default_critical_threshold", 0.8),
            enable_event_collection=data.get("enable_event_collection", True),
            max_events=data.get("max_events", 1000)
        )

    def get_min_threshold(self, anomaly_type: str) -> float:
        if anomaly_type in self.thresholds:
            return self.thresholds[anomaly_type].min_threshold
        return self.default_min_threshold

    def get_critical_threshold(self, anomaly_type: str) -> float:
        if anomaly_type in self.thresholds:
            return self.thresholds[anomaly_type].critical_threshold
        return self.default_critical_threshold

    def is_anomaly(self, anomaly_type: str, confidence: float) -> bool:
        return confidence >= self.get_min_threshold(anomaly_type)

    def is_critical(self, anomaly_type: str, confidence: float) -> bool:
        return confidence >= self.get_critical_threshold(anomaly_type)


def load_anomaly_config(file_path: str) -> AnomalyDetectionConfig:
    if not os.path.exists(file_path):
        return get_default_anomaly_config()
    
    with open(file_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    
    return AnomalyDetectionConfig.from_dict(data)


def get_default_anomaly_config() -> AnomalyDetectionConfig:
    return AnomalyDetectionConfig(
        thresholds={
            "critical_error": ConfidenceThreshold(
                anomaly_type="critical_error",
                min_threshold=0.8,
                critical_threshold=0.95
            ),
            "exception": ConfidenceThreshold(
                anomaly_type="exception",
                min_threshold=0.5,
                critical_threshold=0.9
            ),
            "error": ConfidenceThreshold(
                anomaly_type="error",
                min_threshold=0.3,
                critical_threshold=0.8
            ),
            "warning": ConfidenceThreshold(
                anomaly_type="warning",
                min_threshold=0.3,
                critical_threshold=0.7
            ),
            "potential_anomaly": ConfidenceThreshold(
                anomaly_type="potential_anomaly",
                min_threshold=0.2,
                critical_threshold=0.6
            ),
        },
        score_strategy=ScoreStrategyConfig(
            strategy_type=ScoreStrategyType.HYBRID,
            keyword_weights={
                "exception": 0.3,
                "error": 0.25,
                "fail": 0.2,
                "timeout": 0.25,
                "denied": 0.2,
                "refused": 0.2,
                "nullpointer": 0.3,
                "outofmemory": 0.35,
                "stack": 0.15,
            },
            level_weights={
                "CRITICAL": 1.0,
                "ERROR": 0.8,
                "WARNING": 0.5,
                "INFO": 0.1,
                "DEBUG": 0.05,
                "UNKNOWN": 0.3,
            },
            context_window_size=3,
            frequency_decay_factor=0.9
        ),
        default_min_threshold=0.3,
        default_critical_threshold=0.8,
        enable_event_collection=True,
        max_events=1000
    )


class ScoreStrategy(ABC):
    def __init__(self, config: ScoreStrategyConfig):
        self.config = config

    @abstractmethod
    def calculate_score(
        self,
        entry: LogEntry,
        exception_types: List[str],
        context: Optional[Dict[str, Any]] = None
    ) -> Tuple[float, Dict[str, float]]:
        pass

    @abstractmethod
    def get_anomaly_type(self, entry: LogEntry, score: float, exception_types: List[str]) -> Tuple[str, float]:
        pass


class KeywordWeightStrategy(ScoreStrategy):
    def __init__(self, config: ScoreStrategyConfig):
        super().__init__(config)
        self._compile_keywords()

    def _compile_keywords(self):
        self._keyword_patterns = {}
        for keyword, weight in self.config.keyword_weights.items():
            self._keyword_patterns[keyword] = re.compile(
                re.escape(keyword), re.IGNORECASE
            )

    def calculate_score(
        self,
        entry: LogEntry,
        exception_types: List[str],
        context: Optional[Dict[str, Any]] = None
    ) -> Tuple[float, Dict[str, float]]:
        scores: Dict[str, float] = {}
        total_score = 0.0

        level_value = entry.level.value if hasattr(entry.level, "value") else str(entry.level)
        level_weight = self.config.level_weights.get(level_value, 0.3)
        scores["level_weight"] = level_weight
        total_score += level_weight

        message = entry.message.lower()
        for keyword, weight in self.config.keyword_weights.items():
            if keyword.lower() in message:
                scores[f"keyword_{keyword}"] = weight
                total_score += weight

        if exception_types:
            exception_score = min(len(exception_types) * 0.15, 0.6)
            scores["exception_types"] = exception_score
            total_score += exception_score

        total_score = min(total_score, 1.0)
        return total_score, scores

    def get_anomaly_type(self, entry: LogEntry, score: float, exception_types: List[str]) -> Tuple[str, float]:
        if entry.level == LogLevel.CRITICAL:
            return "critical_error", 1.0
        elif entry.level == LogLevel.ERROR:
            if exception_types:
                if any("Exception" in e for e in exception_types):
                    return "exception", min(score * 1.1, 1.0)
                if any("Error" in e for e in exception_types):
                    return "error", min(score * 1.05, 1.0)
            return "error", min(score * 0.9, 1.0)
        elif entry.level == LogLevel.WARNING:
            return "warning", min(score * 0.7, 0.7)
        elif entry.level == LogLevel.UNKNOWN:
            return "potential_anomaly", min(score * 0.5, 0.5)
        else:
            return "normal", 0.0


class ContextAnalysisStrategy(ScoreStrategy):
    def __init__(self, config: ScoreStrategyConfig):
        super().__init__(config)
        self._context_buffer: List[Dict[str, Any]] = []
        self._exception_patterns = [
            (re.compile(r"(\w+Exception):", re.IGNORECASE), 0.3),
            (re.compile(r"(\w+Error):", re.IGNORECASE), 0.25),
            (re.compile(r"Failed to", re.IGNORECASE), 0.2),
            (re.compile(r"Could not", re.IGNORECASE), 0.2),
            (re.compile(r"Connection (refused|reset|timeout)", re.IGNORECASE), 0.3),
            (re.compile(r"Out of memory", re.IGNORECASE), 0.4),
            (re.compile(r"Stack overflow", re.IGNORECASE), 0.35),
            (re.compile(r"NullPointerException", re.IGNORECASE), 0.35),
        ]

    def _add_to_context(self, entry: LogEntry, score: float):
        self._context_buffer.append({
            "timestamp": entry.timestamp,
            "level": entry.level,
            "score": score
        })
        
        while len(self._context_buffer) > self.config.context_window_size:
            self._context_buffer.pop(0)

    def _calculate_context_score(self) -> float:
        if not self._context_buffer:
            return 0.0
        
        recent_errors = sum(
            1 for item in self._context_buffer
            if item["level"] in (LogLevel.ERROR, LogLevel.CRITICAL)
        )
        
        avg_score = sum(item["score"] for item in self._context_buffer) / len(self._context_buffer)
        
        context_score = min(
            (recent_errors * 0.15) + (avg_score * 0.5),
            0.5
        )
        
        return context_score

    def calculate_score(
        self,
        entry: LogEntry,
        exception_types: List[str],
        context: Optional[Dict[str, Any]] = None
    ) -> Tuple[float, Dict[str, float]]:
        scores: Dict[str, float] = {}
        total_score = 0.0

        level_value = entry.level.value if hasattr(entry.level, "value") else str(entry.level)
        level_weight = self.config.level_weights.get(level_value, 0.3)
        scores["level_weight"] = level_weight
        total_score += level_weight

        message = entry.message
        for pattern, weight in self._exception_patterns:
            if pattern.search(message):
                scores[f"pattern_{pattern.pattern[:20]}"] = weight
                total_score += weight

        if exception_types:
            exception_score = min(len(exception_types) * 0.15, 0.5)
            scores["exception_types"] = exception_score
            total_score += exception_score

        context_score = self._calculate_context_score()
        if context_score > 0:
            scores["context"] = context_score
            total_score += context_score

        total_score = min(total_score, 1.0)
        self._add_to_context(entry, total_score)
        
        return total_score, scores

    def get_anomaly_type(self, entry: LogEntry, score: float, exception_types: List[str]) -> Tuple[str, float]:
        if entry.level == LogLevel.CRITICAL:
            return "critical_error", 1.0
        elif entry.level == LogLevel.ERROR:
            if exception_types:
                if any("Exception" in e for e in exception_types):
                    return "exception", min(score * 1.1, 1.0)
                if any("Error" in e for e in exception_types):
                    return "error", min(score * 1.05, 1.0)
            return "error", min(score * 0.9, 1.0)
        elif entry.level == LogLevel.WARNING:
            return "warning", min(score * 0.7, 0.7)
        elif entry.level == LogLevel.UNKNOWN:
            return "potential_anomaly", min(score * 0.5, 0.5)
        else:
            return "normal", 0.0


class FrequencyBasedStrategy(ScoreStrategy):
    def __init__(self, config: ScoreStrategyConfig):
        super().__init__(config)
        self._error_frequencies: Dict[str, List[datetime]] = {}
        self._decay_factor = config.frequency_decay_factor

    def _decay_frequencies(self, current_time: datetime):
        for error_type in list(self._error_frequencies.keys()):
            filtered_times = [
                t for t in self._error_frequencies[error_type]
                if (current_time - t).total_seconds() < 3600
            ]
            if filtered_times:
                self._error_frequencies[error_type] = filtered_times
            else:
                del self._error_frequencies[error_type]

    def _calculate_frequency_score(self, entry: LogEntry, exception_types: List[str]) -> float:
        if not entry.timestamp:
            return 0.0
        
        self._decay_frequencies(entry.timestamp)
        
        frequency_score = 0.0
        for exc_type in exception_types:
            if exc_type in self._error_frequencies:
                count = len(self._error_frequencies[exc_type])
                recent_count = sum(
                    1 for t in self._error_frequencies[exc_type]
                    if (entry.timestamp - t).total_seconds() < 300
                )
                frequency_score += min(recent_count * 0.1, 0.5)
        
        return min(frequency_score, 0.6)

    def _update_frequencies(self, entry: LogEntry, exception_types: List[str]):
        if not entry.timestamp:
            return
        
        for exc_type in exception_types:
            if exc_type not in self._error_frequencies:
                self._error_frequencies[exc_type] = []
            self._error_frequencies[exc_type].append(entry.timestamp)

    def calculate_score(
        self,
        entry: LogEntry,
        exception_types: List[str],
        context: Optional[Dict[str, Any]] = None
    ) -> Tuple[float, Dict[str, float]]:
        scores: Dict[str, float] = {}
        total_score = 0.0

        level_value = entry.level.value if hasattr(entry.level, "value") else str(entry.level)
        level_weight = self.config.level_weights.get(level_value, 0.3)
        scores["level_weight"] = level_weight
        total_score += level_weight

        frequency_score = self._calculate_frequency_score(entry, exception_types)
        if frequency_score > 0:
            scores["frequency"] = frequency_score
            total_score += frequency_score

        if exception_types:
            exception_score = min(len(exception_types) * 0.15, 0.5)
            scores["exception_types"] = exception_score
            total_score += exception_score

        total_score = min(total_score, 1.0)
        self._update_frequencies(entry, exception_types)
        
        return total_score, scores

    def get_anomaly_type(self, entry: LogEntry, score: float, exception_types: List[str]) -> Tuple[str, float]:
        if entry.level == LogLevel.CRITICAL:
            return "critical_error", 1.0
        elif entry.level == LogLevel.ERROR:
            if exception_types:
                if any("Exception" in e for e in exception_types):
                    return "exception", min(score * 1.1, 1.0)
                if any("Error" in e for e in exception_types):
                    return "error", min(score * 1.05, 1.0)
            return "error", min(score * 0.9, 1.0)
        elif entry.level == LogLevel.WARNING:
            return "warning", min(score * 0.7, 0.7)
        elif entry.level == LogLevel.UNKNOWN:
            return "potential_anomaly", min(score * 0.5, 0.5)
        else:
            return "normal", 0.0


class HybridStrategy(ScoreStrategy):
    def __init__(self, config: ScoreStrategyConfig):
        super().__init__(config)
        self._keyword_strategy = KeywordWeightStrategy(config)
        self._context_strategy = ContextAnalysisStrategy(config)
        self._frequency_strategy = FrequencyBasedStrategy(config)

    def calculate_score(
        self,
        entry: LogEntry,
        exception_types: List[str],
        context: Optional[Dict[str, Any]] = None
    ) -> Tuple[float, Dict[str, float]]:
        keyword_score, keyword_details = self._keyword_strategy.calculate_score(entry, exception_types, context)
        context_score, context_details = self._context_strategy.calculate_score(entry, exception_types, context)
        frequency_score, frequency_details = self._frequency_strategy.calculate_score(entry, exception_types, context)

        scores: Dict[str, float] = {}
        for k, v in keyword_details.items():
            scores[f"keyword_{k}"] = v
        for k, v in context_details.items():
            scores[f"context_{k}"] = v
        for k, v in frequency_details.items():
            scores[f"frequency_{k}"] = v

        weights = {
            "keyword": 0.4,
            "context": 0.35,
            "frequency": 0.25
        }
        
        total_score = (
            keyword_score * weights["keyword"] +
            context_score * weights["context"] +
            frequency_score * weights["frequency"]
        )

        return min(total_score, 1.0), scores

    def get_anomaly_type(self, entry: LogEntry, score: float, exception_types: List[str]) -> Tuple[str, float]:
        if entry.level == LogLevel.CRITICAL:
            return "critical_error", 1.0
        elif entry.level == LogLevel.ERROR:
            if exception_types:
                if any("Exception" in e for e in exception_types):
                    return "exception", min(score * 1.2, 1.0)
                if any("Error" in e for e in exception_types):
                    return "error", min(score * 1.1, 1.0)
            return "error", min(score, 1.0)
        elif entry.level == LogLevel.WARNING:
            return "warning", min(score * 0.8, 0.8)
        elif entry.level == LogLevel.UNKNOWN:
            return "potential_anomaly", min(score * 0.6, 0.6)
        else:
            return "normal", 0.0


def create_score_strategy(config: ScoreStrategyConfig) -> ScoreStrategy:
    if config.strategy_type == ScoreStrategyType.KEYWORD_WEIGHT:
        return KeywordWeightStrategy(config)
    elif config.strategy_type == ScoreStrategyType.CONTEXT_ANALYSIS:
        return ContextAnalysisStrategy(config)
    elif config.strategy_type == ScoreStrategyType.FREQUENCY_BASED:
        return FrequencyBasedStrategy(config)
    elif config.strategy_type == ScoreStrategyType.HYBRID:
        return HybridStrategy(config)
    else:
        return KeywordWeightStrategy(config)


@dataclass
class ExceptionTypeStat:
    exception_type: str
    count: int
    first_occurrence: Optional[datetime]
    last_occurrence: Optional[datetime]
    peak_time: Optional[str]
    sources: List[str]
    sample_messages: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "exception_type": self.exception_type,
            "count": self.count,
            "first_occurrence": self.first_occurrence.isoformat() if self.first_occurrence else None,
            "last_occurrence": self.last_occurrence.isoformat() if self.last_occurrence else None,
            "peak_time": self.peak_time,
            "sources": list(set(self.sources)),
            "sample_messages": self.sample_messages[:5]
        }


@dataclass
class CriticalPeriod:
    start_time: datetime
    end_time: datetime
    error_count: int
    total_count: int
    error_rate: float
    top_exceptions: List[Tuple[str, int]]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "start": self.start_time.strftime("%H:%M") if self.start_time else None,
            "end": self.end_time.strftime("%H:%M") if self.end_time else None,
            "start_time": self.start_time.isoformat() if self.start_time else None,
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "error_count": self.error_count,
            "total_count": self.total_count,
            "error_rate": self.error_rate,
            "top_exceptions": [{"type": t, "count": c} for t, c in self.top_exceptions]
        }


@dataclass
class AnomalyEvent:
    log_entry: LogEntry
    anomaly_type: str
    confidence: float
    score_details: Dict[str, float]
    details: Dict[str, Any]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "log_entry": self.log_entry.to_dict(),
            "anomaly_type": self.anomaly_type,
            "confidence": self.confidence,
            "score_details": self.score_details,
            "details": self.details
        }


@dataclass
class AnomalyReport:
    analysis_id: str
    total_logs: int
    error_count: int
    warning_count: int
    critical_count: int
    exception_types: List[ExceptionTypeStat]
    error_rate: float
    critical_periods: List[CriticalPeriod]
    anomaly_events: List[AnomalyEvent]
    time_range: Tuple[Optional[datetime], Optional[datetime]]
    source_distribution: Dict[str, int]
    level_distribution: Dict[str, int]
    detection_config: Optional[AnomalyDetectionConfig] = None
    score_strategy_used: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result = {
            "analysis_id": self.analysis_id,
            "total_logs": self.total_logs,
            "error_count": self.error_count,
            "warning_count": self.warning_count,
            "critical_count": self.critical_count,
            "exception_types": [et.to_dict() for et in self.exception_types],
            "error_rate": self.error_rate,
            "critical_periods": [cp.to_dict() for cp in self.critical_periods],
            "anomaly_events": [ae.to_dict() for ae in self.anomaly_events],
            "time_range": {
                "start": self.time_range[0].isoformat() if self.time_range[0] else None,
                "end": self.time_range[1].isoformat() if self.time_range[1] else None
            },
            "source_distribution": self.source_distribution,
            "level_distribution": self.level_distribution
        }
        if self.score_strategy_used:
            result["score_strategy_used"] = self.score_strategy_used
        return result


class StreamingAnomalyAccumulator:
    def __init__(self, window_minutes: int = 5):
        self.window_minutes = window_minutes
        
        self.total_logs = 0
        self.error_count = 0
        self.warning_count = 0
        self.critical_count = 0
        
        self.min_time: Optional[datetime] = None
        self.max_time: Optional[datetime] = None
        
        self.exception_stats: Dict[str, ExceptionTypeStat] = {}
        self.exception_hour_counts: Dict[str, Dict[str, int]] = defaultdict(lambda: defaultdict(int))
        
        self.source_distribution: Dict[str, int] = defaultdict(int)
        self.level_distribution: Dict[str, int] = defaultdict(int)
        
        self.time_windows: Dict[str, Dict[str, Any]] = defaultdict(
            lambda: {"errors": 0, "total": 0, "exceptions": defaultdict(int)}
        )
        
        self.anomaly_events: List[AnomalyEvent] = []

    def _get_window_key(self, timestamp: datetime) -> str:
        minutes = (timestamp.minute // self.window_minutes) * self.window_minutes
        return timestamp.strftime(f"%Y-%m-%d %H:{minutes:02d}")

    def accumulate(
        self, 
        entry: LogEntry, 
        exception_types: List[str], 
        anomaly_type: str, 
        confidence: float,
        score_details: Dict[str, float],
        max_events: int,
        detection_config: Optional[AnomalyDetectionConfig] = None
    ):
        self.total_logs += 1
        
        if entry.timestamp:
            if self.min_time is None or entry.timestamp < self.min_time:
                self.min_time = entry.timestamp
            if self.max_time is None or entry.timestamp > self.max_time:
                self.max_time = entry.timestamp
            
            window_key = self._get_window_key(entry.timestamp)
            self.time_windows[window_key]["total"] += 1
            
            for exc_type in exception_types:
                if entry.timestamp:
                    hour_key = entry.timestamp.strftime("%H:%M")
                    self.exception_hour_counts[exc_type][hour_key] += 1
        
        self.level_distribution[entry.level.value] += 1
        self.source_distribution[entry.source] += 1
        
        is_error = False
        if entry.level == LogLevel.CRITICAL:
            self.critical_count += 1
            self.error_count += 1
            is_error = True
        elif entry.level == LogLevel.ERROR:
            self.error_count += 1
            is_error = True
        elif entry.level == LogLevel.WARNING:
            self.warning_count += 1
        
        for exc_type in exception_types:
            if exc_type not in self.exception_stats:
                self.exception_stats[exc_type] = ExceptionTypeStat(
                    exception_type=exc_type,
                    count=0,
                    first_occurrence=None,
                    last_occurrence=None,
                    peak_time=None,
                    sources=[],
                    sample_messages=[]
                )
            
            stat = self.exception_stats[exc_type]
            stat.count += 1
            stat.sources.append(entry.source)
            
            if entry.timestamp:
                if stat.first_occurrence is None or entry.timestamp < stat.first_occurrence:
                    stat.first_occurrence = entry.timestamp
                if stat.last_occurrence is None or entry.timestamp > stat.last_occurrence:
                    stat.last_occurrence = entry.timestamp
            
            if len(stat.sample_messages) < 10:
                stat.sample_messages.append(entry.message)
        
        if is_error and entry.timestamp:
            window_key = self._get_window_key(entry.timestamp)
            self.time_windows[window_key]["errors"] += 1
            for exc_type in exception_types:
                self.time_windows[window_key]["exceptions"][exc_type] += 1
        
        if detection_config:
            if detection_config.is_anomaly(anomaly_type, confidence) and len(self.anomaly_events) < max_events:
                event = AnomalyEvent(
                    log_entry=entry,
                    anomaly_type=anomaly_type,
                    confidence=confidence,
                    score_details=score_details,
                    details={
                        "exception_types": exception_types,
                        "source": entry.source,
                        "is_critical": detection_config.is_critical(anomaly_type, confidence)
                    }
                )
                self.anomaly_events.append(event)
        else:
            if confidence >= 0.3 and len(self.anomaly_events) < max_events:
                event = AnomalyEvent(
                    log_entry=entry,
                    anomaly_type=anomaly_type,
                    confidence=confidence,
                    score_details=score_details,
                    details={
                        "exception_types": exception_types,
                        "source": entry.source
                    }
                )
                self.anomaly_events.append(event)

    def calculate_peak_times(self):
        for stat in self.exception_stats.values():
            hour_counts = self.exception_hour_counts.get(stat.exception_type, {})
            if not hour_counts:
                stat.peak_time = None
                continue
            
            peak_hour = max(hour_counts.items(), key=lambda x: x[1])
            stat.peak_time = peak_hour[0]


class AnomalyDetector:
    EXCEPTION_PATTERNS = [
        (r"(\w+Exception):", "Exception"),
        (r"(\w+Error):", "Error"),
        (r"Exception in thread [^\\s]+\\s+([\\w.]+)", "ThreadException"),
        (r"([A-Z][a-zA-Z]*Exception)", "ExceptionClass"),
        (r"([A-Z][a-zA-Z]*Error)", "ErrorClass"),
        (r"Failed to [^\\s]+", "FailedAction"),
        (r"Could not [^\\s]+", "FailedAction"),
        (r"Unable to [^\\s]+", "FailedAction"),
        (r"Connection (refused|reset|timeout|lost)", "ConnectionIssue"),
        (r"Timeout (exceeded|occurred)", "TimeoutIssue"),
        (r"Out of memory", "OutOfMemory"),
        (r"Stack overflow", "StackOverflow"),
        (r"NullPointerException", "NullPointerException"),
        (r"IndexOutOfBoundsException", "IndexOutOfBounds"),
        (r"FileNotFoundException", "FileNotFound"),
        (r"IOException", "IOError"),
        (r"SQLException", "SQLError"),
        (r"HTTP (\\d{3})", "HTTPCode"),
    ]

    STACK_TRACE_START = re.compile(r"^\s*(?:at|Caused by:|Exception in thread)")

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        self.config = config or {}
        self.error_threshold = self.config.get("error_threshold", 0.05)
        self.window_minutes = self.config.get("window_minutes", 5)
        self.peak_threshold = self.config.get("peak_threshold", 2.0)
        
        self.detection_config: AnomalyDetectionConfig
        if "detection_config" in self.config and isinstance(self.config["detection_config"], AnomalyDetectionConfig):
            self.detection_config = self.config["detection_config"]
        elif "anomaly_config_path" in self.config:
            self.detection_config = load_anomaly_config(self.config["anomaly_config_path"])
        else:
            self.detection_config = get_default_anomaly_config()
        
        self.score_strategy = create_score_strategy(self.detection_config.score_strategy)
        
        self.compiled_patterns = [
            (re.compile(pattern, re.IGNORECASE), name)
            for pattern, name in self.EXCEPTION_PATTERNS
        ]

    def extract_exception_type(self, entry: LogEntry) -> List[str]:
        exception_types = []
        message = entry.message
        
        for pattern, name in self.compiled_patterns:
            matches = pattern.findall(message)
            for match in matches:
                if isinstance(match, tuple):
                    exception_types.append(match[0])
                else:
                    exception_types.append(match)
        
        if not exception_types:
            if entry.level == LogLevel.ERROR:
                exception_types.append("UnknownError")
            elif entry.level == LogLevel.CRITICAL:
                exception_types.append("CriticalError")
            elif entry.level == LogLevel.WARNING:
                exception_types.append("Warning")
        
        return list(set(exception_types))

    def is_stack_trace_line(self, line: str) -> bool:
        return bool(self.STACK_TRACE_START.match(line))

    def detect_anomaly_type(self, entry: LogEntry) -> Tuple[str, float]:
        exception_types = self.extract_exception_type(entry)
        score, score_details = self.score_strategy.calculate_score(entry, exception_types)
        anomaly_type, confidence = self.score_strategy.get_anomaly_type(entry, score, exception_types)
        return anomaly_type, confidence

    def detect_anomaly_with_details(
        self, 
        entry: LogEntry
    ) -> Tuple[str, float, Dict[str, float], List[str]]:
        exception_types = self.extract_exception_type(entry)
        score, score_details = self.score_strategy.calculate_score(entry, exception_types)
        anomaly_type, confidence = self.score_strategy.get_anomaly_type(entry, score, exception_types)
        return anomaly_type, confidence, score_details, exception_types

    def _parse_window_key(self, key: str) -> datetime:
        try:
            return datetime.strptime(key, "%Y-%m-%d %H:%M")
        except ValueError:
            return datetime.now()

    def _identify_critical_periods(
        self,
        time_windows: Dict[str, Dict[str, Any]]
    ) -> List[CriticalPeriod]:
        if not time_windows:
            return []

        total_errors = sum(w["errors"] for w in time_windows.values())
        total_logs = sum(w["total"] for w in time_windows.values())
        
        avg_error_rate = total_errors / total_logs if total_logs > 0 else 0.0
        threshold_rate = max(avg_error_rate * self.peak_threshold, self.error_threshold)

        critical_periods: List[CriticalPeriod] = []
        
        sorted_windows = sorted(time_windows.keys())
        
        current_period: Optional[Dict[str, Any]] = None

        for window_key in sorted_windows:
            window_data = time_windows[window_key]
            window_time = self._parse_window_key(window_key)
            
            error_rate = (
                window_data["errors"] / window_data["total"] 
                if window_data["total"] > 0 else 0.0
            )

            if error_rate >= threshold_rate and window_data["errors"] > 0:
                if current_period is None:
                    current_period = {
                        "start": window_time,
                        "end": window_time,
                        "errors": window_data["errors"],
                        "total": window_data["total"],
                        "exceptions": defaultdict(int)
                    }
                    for exc_type, count in window_data["exceptions"].items():
                        current_period["exceptions"][exc_type] += count
                else:
                    current_period["end"] = window_time
                    current_period["errors"] += window_data["errors"]
                    current_period["total"] += window_data["total"]
                    for exc_type, count in window_data["exceptions"].items():
                        current_period["exceptions"][exc_type] += count
            else:
                if current_period is not None:
                    critical_periods.append(current_period)
                    current_period = None

        if current_period is not None:
            critical_periods.append(current_period)

        result: List[CriticalPeriod] = []
        for period in critical_periods:
            period_error_rate = (
                period["errors"] / period["total"] 
                if period["total"] > 0 else 0.0
            )
            
            top_exceptions = sorted(
                period["exceptions"].items(),
                key=lambda x: x[1],
                reverse=True
            )[:5]

            result.append(CriticalPeriod(
                start_time=period["start"],
                end_time=period["end"] + timedelta(minutes=self.window_minutes),
                error_count=period["errors"],
                total_count=period["total"],
                error_rate=period_error_rate,
                top_exceptions=top_exceptions
            ))

        return result

    def analyze(
        self,
        entries: Iterator[LogEntry],
        collect_events: bool = True,
        max_events: int = 1000
    ) -> AnomalyReport:
        accumulator = StreamingAnomalyAccumulator(
            window_minutes=self.window_minutes
        )

        max_events = self.detection_config.max_events if self.detection_config.enable_event_collection else 0

        for entry in entries:
            anomaly_type, confidence, score_details, exception_types = self.detect_anomaly_with_details(entry)
            accumulator.accumulate(
                entry, 
                exception_types, 
                anomaly_type, 
                confidence,
                score_details,
                max_events,
                self.detection_config
            )

        accumulator.calculate_peak_times()
        critical_periods = self._identify_critical_periods(accumulator.time_windows)
        error_rate = accumulator.error_count / accumulator.total_logs if accumulator.total_logs > 0 else 0.0

        sorted_exceptions = sorted(
            accumulator.exception_stats.values(),
            key=lambda x: x.count,
            reverse=True
        )

        return AnomalyReport(
            analysis_id=f"analysis_{datetime.now().strftime('%Y%m%d%H%M%S')}",
            total_logs=accumulator.total_logs,
            error_count=accumulator.error_count,
            warning_count=accumulator.warning_count,
            critical_count=accumulator.critical_count,
            exception_types=sorted_exceptions,
            error_rate=error_rate,
            critical_periods=critical_periods,
            anomaly_events=accumulator.anomaly_events,
            time_range=(accumulator.min_time, accumulator.max_time),
            source_distribution=dict(accumulator.source_distribution),
            level_distribution=dict(accumulator.level_distribution),
            detection_config=self.detection_config,
            score_strategy_used=self.detection_config.score_strategy.strategy_type.value
        )


def detect_anomalies(
    entries: Iterator[LogEntry],
    config: Optional[Dict[str, Any]] = None
) -> AnomalyReport:
    detector = AnomalyDetector(config)
    return detector.analyze(entries)
