from datetime import datetime
from typing import Optional, Dict, Any
from pydantic import BaseModel, Field
from enum import Enum


class EntityType(str, Enum):
    TASK = "task"
    PIPELINE = "pipeline"
    RESOURCE = "resource"
    WORKFLOW = "workflow"


class EntityStatus(str, Enum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    PAUSED = "paused"
    DELETED = "deleted"


class Entity(BaseModel):
    id: str = Field(..., description="Unique entity identifier")
    type: EntityType = Field(..., description="Entity type")
    status: EntityStatus = Field(default=EntityStatus.ACTIVE, description="Entity status")
    attributes: Dict[str, Any] = Field(default_factory=dict, description="Custom attributes")
    created_at: datetime = Field(default_factory=datetime.utcnow, description="Creation timestamp")
    updated_at: datetime = Field(default_factory=datetime.utcnow, description="Last update timestamp")

    class Config:
        from_attributes = True
