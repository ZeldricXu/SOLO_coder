from pydantic import BaseModel, Field
from typing import Optional, Dict, Any, List
from datetime import datetime
from enum import Enum


class MetricType(str, Enum):
    ACCURACY = "accuracy"
    PRECISION = "precision"
    RECALL = "recall"
    F1 = "f1"
    AUC_ROC = "auc_roc"
    BLEU = "bleu"
    ROUGE = "rouge"
    PERPLEXITY = "perplexity"
    LATENCY = "latency"
    THROUGHPUT = "throughput"
    ERROR_RATE = "error_rate"
    USER_SATISFACTION = "user_satisfaction"


class DriftType(str, Enum):
    DATA_DRIFT = "data_drift"
    CONCEPT_DRIFT = "concept_drift"
    PREDICTION_DRIFT = "prediction_drift"


class DriftSeverity(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class OfflineMetric(BaseModel):
    metric_name: str
    metric_type: MetricType
    value: float = Field(ge=0.0, le=1.0)
    model_version: str
    dataset_name: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    confidence_interval: Optional[Dict[str, float]] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class OnlineMetric(BaseModel):
    metric_name: str
    metric_type: MetricType
    value: float
    window_seconds: int
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    sample_count: int
    metadata: Dict[str, Any] = Field(default_factory=dict)


class ModelComparison(BaseModel):
    model_a: str
    model_b: str
    metrics: Dict[str, Dict[str, float]]
    winner: Optional[str] = None
    statistical_significance: Optional[bool] = None


class DriftAlert(BaseModel):
    alert_id: str
    drift_type: DriftType
    severity: DriftSeverity
    metric_name: str
    p_value: float
    effect_size: float
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    details: Dict[str, Any] = Field(default_factory=dict)
    acknowledged: bool = False


class ModelEvaluation(BaseModel):
    evaluation_id: str
    model_name: str
    model_version: str
    offline_metrics: List[OfflineMetric]
    online_metrics: List[OnlineMetric]
    drift_alerts: List[DriftAlert]
    generated_at: datetime = Field(default_factory=datetime.utcnow)
    overall_score: Optional[float] = None


class DashboardSnapshot(BaseModel):
    snapshot_id: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    models: Dict[str, Dict[str, Any]]
    active_alerts: List[DriftAlert]
    overall_health: str
    summary: Dict[str, Any]
