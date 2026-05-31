from pydantic import BaseModel, Field
from typing import Dict, Any, List, Optional
from enum import Enum
from datetime import datetime


class ModelStage(str, Enum):
    NONE = "none"
    STAGING = "staging"
    PRODUCTION = "production"
    ARCHIVED = "archived"


class ModelStatus(str, Enum):
    DRAFT = "draft"
    READY = "ready"
    DEPLOYED = "deployed"
    DEPRECATED = "deprecated"


class ModelFramework(str, Enum):
    PYTORCH = "pytorch"
    TENSORFLOW = "tensorflow"
    SKLEARN = "sklearn"
    XGBOOST = "xgboost"
    ONNX = "onnx"
    CUSTOM = "custom"


class ModelMetadata(BaseModel):
    model_id: Optional[str] = None
    name: str
    description: str = ""
    framework: ModelFramework
    framework_version: Optional[str] = None
    tags: List[str] = Field(default_factory=list)
    labels: Dict[str, str] = Field(default_factory=dict)
    created_by: str = "system"
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class ModelVersion(BaseModel):
    version_id: Optional[str] = None
    model_id: str
    version: str
    description: str = ""
    metrics: Dict[str, float] = Field(default_factory=dict)
    artifacts_uri: str = ""
    signature: Dict[str, Any] = Field(default_factory=dict)
    dependencies: List[str] = Field(default_factory=list)
    stage: ModelStage = ModelStage.NONE
    status: ModelStatus = ModelStatus.DRAFT
    created_by: str = "system"
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class ModelRegisterRequest(BaseModel):
    name: str
    description: str = ""
    framework: ModelFramework
    framework_version: Optional[str] = None
    tags: List[str] = Field(default_factory=list)
    labels: Dict[str, str] = Field(default_factory=dict)


class VersionCreateRequest(BaseModel):
    model_id: str
    version: str
    description: str = ""
    metrics: Dict[str, float] = Field(default_factory=dict)
    artifacts_uri: str = ""
    signature: Dict[str, Any] = Field(default_factory=dict)
    dependencies: List[str] = Field(default_factory=list)


class StageTransitionRequest(BaseModel):
    version_id: str
    target_stage: ModelStage
    comment: str = ""


class StageTransition(BaseModel):
    transition_id: str
    version_id: str
    from_stage: ModelStage
    to_stage: ModelStage
    comment: str = ""
    performed_by: str = "system"
    created_at: datetime = Field(default_factory=datetime.utcnow)


class ModelVersionSummary(BaseModel):
    model_id: str
    model_name: str
    total_versions: int
    latest_version: Optional[str] = None
    production_version: Optional[str] = None
    staging_version: Optional[str] = None
