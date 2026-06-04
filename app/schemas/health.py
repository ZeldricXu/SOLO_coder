from datetime import datetime
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field


class HealthStatus(BaseModel):
    service_id: int
    service_name: str
    service_type: str
    status: str
    response_time_ms: Optional[int] = None
    last_check: Optional[datetime] = None
    details: Optional[Dict[str, Any]] = None


class HealthCheckResult(BaseModel):
    service_id: int
    status: str
    response_time_ms: Optional[int] = None
    details: Optional[str] = None


class ServiceCreate(BaseModel):
    name: str
    service_type: str
    health_endpoint: str
    check_interval: int = 30


class ServiceUpdate(BaseModel):
    name: Optional[str] = None
    service_type: Optional[str] = None
    health_endpoint: Optional[str] = None
    check_interval: Optional[int] = None
    status: Optional[str] = None
