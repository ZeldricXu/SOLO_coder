from datetime import datetime
from typing import Optional, Dict, Any
from pydantic import BaseModel, Field


class ConfigDefinition(BaseModel):
    config_id: str = Field(..., description="Configuration identifier")
    namespace: str = Field(..., description="Configuration namespace")
    version: int = Field(default=1, description="Configuration version")
    parameters: Dict[str, Any] = Field(default_factory=dict, description="Configuration parameters")
    enabled: bool = Field(default=True, description="Whether the config is enabled")
    applied_at: Optional[datetime] = Field(default=None, description="When the config was applied")
    created_at: datetime = Field(default_factory=datetime.utcnow, description="Creation timestamp")
    updated_at: datetime = Field(default_factory=datetime.utcnow, description="Last update timestamp")

    class Config:
        from_attributes = True
