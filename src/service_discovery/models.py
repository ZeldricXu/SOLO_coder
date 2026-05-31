from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

from src.common.models import generate_id, utc_now


class ServiceType(str, Enum):
    SERVICE = "service"
    LIBRARY = "library"
    DATABASE = "database"
    QUEUE = "queue"
    CACHE = "cache"
    STORAGE = "storage"
    GATEWAY = "gateway"


class ServiceStatus(str, Enum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    DEPRECATED = "deprecated"
    MAINTENANCE = "maintenance"


class HealthStatus(str, Enum):
    HEALTHY = "healthy"
    DEGRADED = "degraded"
    UNHEALTHY = "unhealthy"
    UNKNOWN = "unknown"


class ServiceEndpoint(BaseModel):
    name: str
    url: str
    protocol: str = "http"
    version: str = "v1"


class ServiceDependency(BaseModel):
    service_id: str
    version_constraint: str = "*"
    relationship: str = "uses"


class ServiceContact(BaseModel):
    name: str
    email: str
    role: str = "owner"


class ServiceMetadata(BaseModel):
    service_id: str = Field(default_factory=lambda: generate_id("svc"))
    name: str
    description: str = ""
    type: ServiceType = ServiceType.SERVICE
    status: ServiceStatus = ServiceStatus.ACTIVE
    version: str = "0.1.0"
    owner: str = ""
    tags: List[str] = Field(default_factory=list)
    endpoints: List[ServiceEndpoint] = Field(default_factory=list)
    dependencies: List[ServiceDependency] = Field(default_factory=list)
    contacts: List[ServiceContact] = Field(default_factory=list)
    labels: Dict[str, str] = Field(default_factory=dict)
    documentation_url: Optional[str] = None
    repository_url: Optional[str] = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class ServiceHealth(BaseModel):
    service_id: str
    health_status: HealthStatus = HealthStatus.UNKNOWN
    last_check: datetime = Field(default_factory=utc_now)
    metrics: Dict[str, Any] = Field(default_factory=dict)
    message: str = ""


class ServiceQuery(BaseModel):
    name: Optional[str] = None
    type: Optional[ServiceType] = None
    status: Optional[ServiceStatus] = None
    tags: List[str] = Field(default_factory=list)
    owner: Optional[str] = None
    labels: Dict[str, str] = Field(default_factory=dict)


class ServiceRegistrationRequest(BaseModel):
    name: str
    description: str = ""
    type: ServiceType = ServiceType.SERVICE
    status: ServiceStatus = ServiceStatus.ACTIVE
    version: str = "0.1.0"
    owner: str = ""
    tags: List[str] = Field(default_factory=list)
    endpoints: List[ServiceEndpoint] = Field(default_factory=list)
    dependencies: List[ServiceDependency] = Field(default_factory=list)
    contacts: List[ServiceContact] = Field(default_factory=list)
    labels: Dict[str, str] = Field(default_factory=dict)
    documentation_url: Optional[str] = None
    repository_url: Optional[str] = None


class DependencyGraph(BaseModel):
    nodes: List[Dict[str, Any]]
    edges: List[Dict[str, Any]]


class ServiceSearchResult(BaseModel):
    service: ServiceMetadata
    health: Optional[ServiceHealth] = None
    dependents: List[str] = Field(default_factory=list)
    dependencies: List[str] = Field(default_factory=list)
