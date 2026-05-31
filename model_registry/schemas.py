from datetime import datetime
from typing import List, Optional, Dict, Any
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class ModelStage(str, Enum):
    NONE = "none"
    STAGING = "staging"
    PRODUCTION = "production"
    ARCHIVED = "archived"
    DEPRECATED = "deprecated"


class ModelVersionStatus(str, Enum):
    PENDING = "pending"
    READY = "ready"
    DEPLOYING = "deploying"
    DEPLOYED = "deployed"
    FAILED = "failed"
    DELETED = "deleted"


class ModelFramework(str, Enum):
    PYTORCH = "pytorch"
    TENSORFLOW = "tensorflow"
    ONNX = "onnx"
    SKLEARN = "sklearn"
    HUGGINGFACE = "huggingface"
    CUSTOM = "custom"


class ModelArtifact(BaseModel):
    artifact_id: str
    artifact_type: str
    uri: str
    size_bytes: Optional[int] = None
    checksum: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None
    created_at: datetime


class ModelTag(BaseModel):
    tag_id: str
    name: str
    value: str
    created_at: datetime


class ModelMetric(BaseModel):
    metric_id: str
    metric_name: str
    metric_value: float
    dataset: Optional[str] = None
    metric_type: Optional[str] = None
    timestamp: datetime


class ModelVersion(BaseModel):
    version_id: str
    model_id: str
    version: str
    description: Optional[str] = None
    status: ModelVersionStatus
    stage: ModelStage
    framework: Optional[ModelFramework] = None
    artifacts: List[ModelArtifact] = Field(default_factory=list)
    metrics: List[ModelMetric] = Field(default_factory=list)
    tags: List[ModelTag] = Field(default_factory=list)
    created_by: Optional[str] = None
    created_at: datetime
    updated_at: datetime
    deployed_at: Optional[datetime] = None
    training_run_id: Optional[str] = None
    source_code_uri: Optional[str] = None


class ModelMetadata(BaseModel):
    model_id: str
    name: str
    display_name: Optional[str] = None
    description: Optional[str] = None
    owner: Optional[str] = None
    task_type: Optional[str] = None
    latest_version: Optional[str] = None
    production_version: Optional[str] = None
    staging_version: Optional[str] = None
    versions: List[ModelVersion] = Field(default_factory=list)
    tags: List[ModelTag] = Field(default_factory=list)
    created_by: Optional[str] = None
    created_at: datetime
    updated_at: datetime
    is_active: bool = True


class ModelRegistrationRequest(BaseModel):
    name: str
    display_name: Optional[str] = None
    description: Optional[str] = None
    owner: Optional[str] = None
    task_type: Optional[str] = None
    tags: Optional[Dict[str, str]] = None
    metadata: Optional[Dict[str, Any]] = None


class ModelVersionCreateRequest(BaseModel):
    model_id: str
    version: Optional[str] = None
    description: Optional[str] = None
    framework: Optional[ModelFramework] = None
    artifacts: Optional[List[ModelArtifact]] = None
    metrics: Optional[List[ModelMetric]] = None
    tags: Optional[Dict[str, str]] = None
    training_run_id: Optional[str] = None
    source_code_uri: Optional[str] = None
    created_by: Optional[str] = None


class StageTransitionRequest(BaseModel):
    model_id: str
    version: str
    target_stage: ModelStage
    comment: Optional[str] = None
    transition_by: Optional[str] = None


class ModelSearchRequest(BaseModel):
    name: Optional[str] = None
    owner: Optional[str] = None
    task_type: Optional[str] = None
    stage: Optional[ModelStage] = None
    tags: Optional[Dict[str, str]] = None
    include_versions: bool = True
    limit: int = Field(default=50, ge=1, le=200)
    offset: int = Field(default=0, ge=0)


class ModelSearchResponse(BaseModel):
    total: int
    limit: int
    offset: int
    models: List[ModelMetadata]
