from typing import List, Optional, Dict, Any
from datetime import datetime
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class ModelTypeEnum(str, Enum):
    EXTRACTION = "extraction"
    LAYOUT_ANALYSIS = "layout_analysis"
    TABLE_DETECTION = "table_detection"
    OCR = "ocr"
    MULTIMODAL = "multimodal"


class ModelStatusEnum(str, Enum):
    DRAFT = "draft"
    TESTING = "testing"
    STAGING = "staging"
    PRODUCTION = "production"
    ARCHIVED = "archived"


class ModelVersionBase(BaseModel):
    model_name: str
    model_type: ModelTypeEnum
    version: str
    description: Optional[str] = None
    architecture: Optional[str] = None
    framework: Optional[str] = None
    framework_version: Optional[str] = None
    minio_bucket: Optional[str] = None
    minio_path: Optional[str] = None
    local_path: Optional[str] = None


class ModelVersionCreate(ModelVersionBase):
    status: ModelStatusEnum = ModelStatusEnum.DRAFT
    is_default: bool = False
    metrics: Optional[Dict[str, Any]] = None
    training_dataset: Optional[str] = None
    requirements: Optional[Dict[str, Any]] = None
    hardware_requirements: Optional[Dict[str, Any]] = None
    deployment_config: Optional[Dict[str, Any]] = None


class ModelVersionUpdate(BaseModel):
    status: Optional[ModelStatusEnum] = None
    is_default: Optional[bool] = None
    description: Optional[str] = None
    metrics: Optional[Dict[str, Any]] = None
    validation_metrics: Optional[Dict[str, Any]] = None
    test_metrics: Optional[Dict[str, Any]] = None
    deployed_by: Optional[str] = None
    deployment_config: Optional[Dict[str, Any]] = None


class ModelVersionResponse(ModelVersionBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    status: ModelStatusEnum
    is_default: bool
    status: ModelStatusEnum
    training_dataset: Optional[str] = None
    training_start_date: Optional[datetime] = None
    training_end_date: Optional[datetime] = None
    training_duration_hours: Optional[float] = None
    metrics: Optional[Dict[str, Any]] = None
    validation_metrics: Optional[Dict[str, Any]] = None
    test_metrics: Optional[Dict[str, Any]] = None
    requirements: Optional[Dict[str, Any]] = None
    hardware_requirements: Optional[Dict[str, Any]] = None
    deployed_at: Optional[datetime] = None
    deployed_by: Optional[str] = None
    deployment_config: Optional[Dict[str, Any]] = None
    created_at: datetime
    updated_at: datetime


class ABTestExperimentBase(BaseModel):
    name: str
    description: Optional[str] = None
    model_type: ModelTypeEnum
    control_model_version: Optional[str] = None
    treatment_model_versions: Optional[List[str]] = None
    traffic_split: Optional[Dict[str, float]] = None
    target_metrics: Optional[List[str]] = None
    start_date: Optional[datetime] = None
    end_date: Optional[datetime] = None
    sample_size: Optional[int] = None
    confidence_level: float = 0.95


class ABTestExperimentCreate(ABTestExperimentBase):
    created_by: Optional[str] = None


class ABTestExperimentUpdate(BaseModel):
    status: Optional[str] = None
    is_active: Optional[bool] = None
    results_summary: Optional[Dict[str, Any]] = None
    winner_model: Optional[str] = None
    approved_by: Optional[str] = None
    approved_at: Optional[datetime] = None
    stopped_reason: Optional[str] = None


class ABTestExperimentResponse(ABTestExperimentBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    status: str
    is_active: bool
    created_by: Optional[str] = None
    approved_by: Optional[str] = None
    approved_at: Optional[datetime] = None
    results_summary: Optional[Dict[str, Any]] = None
    winner_model: Optional[str] = None
    stopped_at: Optional[datetime] = None
    stopped_reason: Optional[str] = None
    created_at: datetime
    updated_at: datetime


class ABTestResultBase(BaseModel):
    experiment_id: int
    model_version_id: int
    document_id: int
    extraction_result_id: int
    group: str
    metrics: Optional[Dict[str, Any]] = None
    review_rate: Optional[float] = None
    average_confidence: Optional[float] = None
    processing_time: Optional[float] = None
    field_accuracy: Optional[Dict[str, float]] = None


class ABTestResultCreate(ABTestResultBase):
    pass


class ABTestResultResponse(ABTestResultBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    human_evaluated: bool = False
    human_score: Optional[float] = None
    evaluated_by: Optional[str] = None
    evaluated_at: Optional[datetime] = None
    created_at: datetime
    updated_at: datetime


class ModelMetrics(BaseModel):
    model_id: int
    model_name: str
    version: str
    total_extractions: int = 0
    average_confidence: float = 0.0
    review_rate: float = 0.0
    average_processing_time: float = 0.0
    field_accuracies: Dict[str, float] = Field(default_factory=dict)
    field_accuracies: Optional[Dict[str, float]] = None
