from pydantic import BaseModel, Field
from typing import Dict, Any, List, Optional
from enum import Enum
from datetime import datetime, timedelta


class EvaluationType(str, Enum):
    OFFLINE = "offline"
    ONLINE = "online"
    ABLATION = "ablation"


class MetricType(str, Enum):
    ACCURACY = "accuracy"
    PRECISION = "precision"
    RECALL = "recall"
    F1 = "f1"
    AUC = "auc"
    MAPE = "mape"
    RMSE = "rmse"
    LATENCY = "latency"
    THROUGHPUT = "throughput"
    CUSTOM = "custom"


class DriftType(str, Enum):
    DATA_DRIFT = "data_drift"
    CONCEPT_DRIFT = "concept_drift"
    PREDICTION_DRIFT = "prediction_drift"


class MetricDefinition(BaseModel):
    metric_id: Optional[str] = None
    name: str
    type: MetricType
    description: str = ""
    threshold: Optional[float] = None
    unit: str = ""
    higher_is_better: bool = True


class EvaluationResult(BaseModel):
    evaluation_id: Optional[str] = None
    model_id: str
    version_id: str
    evaluation_type: EvaluationType
    metrics: Dict[str, float] = Field(default_factory=dict)
    dataset: str = ""
    start_time: datetime = Field(default_factory=datetime.utcnow)
    end_time: Optional[datetime] = None
    created_by: str = "system"
    created_at: datetime = Field(default_factory=datetime.utcnow)


class OnlineMetricPoint(BaseModel):
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    model_id: str
    version_id: str
    metric_name: str
    value: float
    dimensions: Dict[str, str] = Field(default_factory=dict)


class DriftDetectionResult(BaseModel):
    drift_id: Optional[str] = None
    model_id: str
    version_id: str
    drift_type: DriftType
    feature_name: Optional[str] = None
    is_drift: bool
    p_value: float
    statistic: float
    threshold: float
    window_start: datetime
    window_end: datetime
    created_at: datetime = Field(default_factory=datetime.utcnow)


class ModelComparisonRequest(BaseModel):
    model_ids: List[str]
    version_ids: List[str]
    metrics: List[str]
    evaluation_type: EvaluationType = EvaluationType.OFFLINE


class ModelComparisonResult(BaseModel):
    comparison_id: str
    models: List[Dict[str, Any]]
    metrics: List[str]
    created_at: datetime = Field(default_factory=datetime.utcnow)


class DashboardSummary(BaseModel):
    total_models: int
    total_evaluations: int
    active_drifts: int
    avg_accuracy: float
    avg_latency_ms: float
    p99_latency_ms: float
