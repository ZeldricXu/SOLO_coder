from datetime import datetime
from typing import Optional, Dict, Any
from pydantic import BaseModel, Field
from enum import Enum


class RunPhase(str, Enum):
    PENDING = "pending"
    INITIALIZING = "initializing"
    RUNNING = "running"
    FINALIZING = "finalizing"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class RunInstance(BaseModel):
    run_id: str = Field(..., description="Run instance identifier")
    entity_id: str = Field(..., description="Associated entity identifier")
    phase: RunPhase = Field(default=RunPhase.PENDING, description="Current execution phase")
    progress: float = Field(default=0.0, ge=0.0, le=1.0, description="Progress percentage (0-1)")
    started_at: Optional[datetime] = Field(default=None, description="Start timestamp")
    completed_at: Optional[datetime] = Field(default=None, description="Completion timestamp")
    error_detail: Optional[str] = Field(default=None, description="Error details if failed")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="Additional metadata")
    created_at: datetime = Field(default_factory=datetime.utcnow, description="Creation timestamp")

    class Config:
        from_attributes = True
