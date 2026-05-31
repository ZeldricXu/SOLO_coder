from pydantic import BaseModel, Field
from typing import Optional, Dict, Any, List
from datetime import datetime


class RouteConfig(BaseModel):
    path: str
    target_url: str
    method: str = Field(default="GET")
    timeout_seconds: float = Field(default=30.0, gt=0)
    retry_count: int = Field(default=3, ge=0)
    protocol: str = Field(default="http")
    auth_required: bool = Field(default=False)
    rate_limit: Optional[int] = None
    request_transformer: Optional[str] = None
    response_transformer: Optional[str] = None
    enabled: bool = Field(default=True)


class GatewayRequest(BaseModel):
    method: str
    path: str
    headers: Dict[str, str] = Field(default_factory=dict)
    query_params: Dict[str, Any] = Field(default_factory=dict)
    body: Optional[Any] = None
    client_ip: Optional[str] = None
    trace_id: Optional[str] = None
    user_id: Optional[str] = None
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class GatewayResponse(BaseModel):
    status_code: int
    headers: Dict[str, str] = Field(default_factory=dict)
    body: Optional[Any] = None
    latency_ms: float
    from_cache: bool = False
    error: Optional[str] = None


class BatchOperation(BaseModel):
    action: str
    id: str
    parameters: Optional[Dict[str, Any]] = None


class BatchRequest(BaseModel):
    operations: List[BatchOperation]


class BatchResult(BaseModel):
    id: str
    success: bool
    status_code: int
    data: Optional[Any] = None
    error: Optional[str] = None


class BatchResponse(BaseModel):
    batch_id: str
    results: List[BatchResult]
    total_count: int
    success_count: int
    failed_count: int
