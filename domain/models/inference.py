from enum import Enum
from datetime import datetime
from typing import Dict, Any, Optional, List
from pydantic import BaseModel, Field


class InferenceStatus(str, Enum):
    PENDING = "pending"
    SCHEDULED = "scheduled"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    TIMEOUT = "timeout"


class ModelType(str, Enum):
    CLASSIFICATION = "classification"
    DETECTION = "detection"
    SEGMENTATION = "segmentation"
    ANOMALY_DETECTION = "anomaly_detection"
    PREDICTION = "prediction"
    CUSTOM = "custom"


class ModelFramework(str, Enum):
    TENSORFLOW = "tensorflow"
    PYTORCH = "pytorch"
    ONNX = "onnx"
    TFLITE = "tflite"
    TENSORRT = "tensorrt"
    CUSTOM = "custom"


class AIModel(BaseModel):
    model_id: str
    model_name: str
    model_version: str
    model_type: ModelType
    framework: ModelFramework

    model_path: str
    input_schema: Dict[str, Any] = Field(default_factory=dict)
    output_schema: Dict[str, Any] = Field(default_factory=dict)

    description: Optional[str] = None
    labels: List[str] = Field(default_factory=list)

    size_bytes: int = 0
    checksum: Optional[str] = None

    gpu_required: bool = False
    min_memory_mb: int = 256
    inference_timeout_ms: int = 30000

    metadata: Dict[str, Any] = Field(default_factory=dict)

    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class InferenceTask(BaseModel):
    task_id: str
    model_id: str
    input_data: Dict[str, Any]

    status: InferenceStatus = InferenceStatus.PENDING
    priority: int = 0

    device_id: Optional[str] = None
    source: Optional[str] = None

    scheduled_at: Optional[datetime] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    timeout_at: Optional[datetime] = None

    error_message: Optional[str] = None
    retry_count: int = 0
    max_retries: int = 3

    callback_url: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)

    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    def start(self) -> None:
        self.status = InferenceStatus.RUNNING
        self.started_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def complete(self, result: Optional[Dict[str, Any]] = None) -> None:
        self.status = InferenceStatus.COMPLETED
        self.completed_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def fail(self, error_message: str) -> None:
        self.status = InferenceStatus.FAILED
        self.error_message = error_message
        self.completed_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def cancel(self) -> None:
        self.status = InferenceStatus.CANCELLED
        self.completed_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def is_timed_out(self) -> bool:
        if not self.timeout_at:
            return False
        return datetime.utcnow() > self.timeout_at

    def should_retry(self) -> bool:
        return self.retry_count < self.max_retries


class InferenceResult(BaseModel):
    result_id: str
    task_id: str
    model_id: str

    predictions: List[Dict[str, Any]] = Field(default_factory=list)
    confidence_scores: List[float] = Field(default_factory=list)
    raw_output: Optional[Dict[str, Any]] = None

    inference_time_ms: int = 0
    memory_usage_mb: int = 0

    success: bool = True
    error_message: Optional[str] = None

    metadata: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)

    def get_top_prediction(self) -> Optional[Dict[str, Any]]:
        if not self.predictions:
            return None
        if self.confidence_scores:
            max_idx = self.confidence_scores.index(max(self.confidence_scores))
            return self.predictions[max_idx]
        return self.predictions[0]

    def get_predictions_above_threshold(self, threshold: float) -> List[Dict[str, Any]]:
        if not self.confidence_scores:
            return self.predictions
        return [
            pred for pred, score in zip(self.predictions, self.confidence_scores)
            if score >= threshold
        ]
