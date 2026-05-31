from datetime import datetime
from typing import List, Optional, Dict, Any
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class MetricType(str, Enum):
    ACCURACY = "accuracy"
    PRECISION = "precision"
    RECALL = "recall"
    F1_SCORE = "f1_score"
    BLEU = "bleu"
    ROUGE = "rouge"
    METEOR = "meteor"
    HALLUCINATION_RATE = "hallucination_rate"
    RELEVANCE = "relevance"
    COHERENCE = "coherence"
    LATENCY_P50 = "latency_p50"
    LATENCY_P95 = "latency_p95"
    LATENCY_P99 = "latency_p99"
    THROUGHPUT = "throughput"
    ERROR_RATE = "error_rate"
    COST_PER_1K_TOKENS = "cost_per_1k_tokens"
    TOTAL_COST = "total_cost"
    USER_SATISFACTION = "user_satisfaction"


class DriftType(str, Enum):
    DATA_DRIFT = "data_drift"
    CONCEPT_DRIFT = "concept_drift"
    PREDICTION_DRIFT = "prediction_drift"


class AlertSeverity(str, Enum):
    INFO = "info"
    WARNING = "warning"
    CRITICAL = "critical"


class EvaluationMetric(BaseModel):
    metric_name: MetricType
    metric_value: float
    baseline_value: Optional[float] = None
    unit: Optional[str] = None
    description: Optional[str] = None

    model_config = ConfigDict(from_attributes=True)


class OfflineEvaluationRequest(BaseModel):
    model_name: str
    model_version: str
    dataset_name: str
    metrics: List[MetricType]
    eval_config: Optional[Dict[str, Any]] = None
    reference_model: Optional[str] = None


class OfflineEvaluationResponse(BaseModel):
    evaluation_id: str
    model_name: str
    model_version: str
    dataset_name: str
    metrics: List[EvaluationMetric]
    comparison_with_baseline: Optional[Dict[str, Dict[str, float]]] = None
    started_at: datetime
    completed_at: datetime
    duration_seconds: float
    status: str


class OnlineMetrics(BaseModel):
    model_name: str
    timestamp: datetime
    metrics: List[EvaluationMetric]
    window_size: int
    start_time: datetime
    end_time: datetime


class DriftDetectionRequest(BaseModel):
    model_name: str
    drift_type: DriftType
    metric_name: Optional[str] = None
    lookback_days: int = Field(default=7, ge=1, le=30)
    threshold: float = Field(default=0.05, ge=0.01, le=0.5)


class DriftAlert(BaseModel):
    alert_id: str
    model_name: str
    drift_type: DriftType
    metric_name: str
    severity: AlertSeverity
    drift_score: float
    threshold: float
    detected_at: datetime
    description: str


class DriftDetectionResponse(BaseModel):
    detection_id: str
    model_name: str
    has_drift: bool
    alerts: List[DriftAlert]
    drift_scores: Dict[str, float]
    analyzed_from: datetime
    analyzed_to: datetime


class MetricTimeSeries(BaseModel):
    metric_name: str
    timestamps: List[datetime]
    values: List[float]
    model_name: Optional[str] = None


class DashboardResponse(BaseModel):
    dashboard_id: str
    model_name: str
    time_range: Dict[str, datetime]
    online_metrics: List[EvaluationMetric]
    offline_evaluations: List[Dict[str, Any]]
    active_alerts: List[DriftAlert]
    drift_status: str
    last_updated: datetime
