from pydantic import BaseModel, Field
from typing import List, Dict, Any, Optional
from enum import Enum
from datetime import datetime


class ProtocolType(str, Enum):
    HTTP = "http"
    GRPC = "grpc"
    WEBSOCKET = "websocket"
    MQTT = "mqtt"
    AMQP = "amqp"


class HTTPMethod(str, Enum):
    GET = "GET"
    POST = "POST"
    PUT = "PUT"
    DELETE = "DELETE"
    PATCH = "PATCH"
    HEAD = "HEAD"
    OPTIONS = "OPTIONS"


class CircuitBreakerState(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


class LoadBalanceStrategy(str, Enum):
    ROUND_ROBIN = "round_robin"
    WEIGHTED_ROUND_ROBIN = "weighted_round_robin"
    LEAST_CONN = "least_conn"
    RANDOM = "random"


class CircuitBreakerConfig(BaseModel):
    failure_threshold: int = 5
    timeout_seconds: int = 30
    half_open_max_calls: int = 3


class RouteTarget(BaseModel):
    host: str
    port: int
    protocol: ProtocolType = ProtocolType.HTTP
    path: str = "/"
    weight: int = 1
    healthy: bool = True


class RouteDefinition(BaseModel):
    route_id: Optional[str] = None
    path: str
    methods: List[HTTPMethod] = Field(default_factory=lambda: [HTTPMethod.POST])
    service_name: str
    targets: List[RouteTarget]
    protocol_in: ProtocolType = ProtocolType.HTTP
    protocol_out: ProtocolType = ProtocolType.HTTP
    timeout_seconds: int = 30
    retry_count: int = 3
    rate_limit: Optional[int] = None
    circuit_breaker_enabled: bool = True
    request_transform: Optional[Dict[str, Any]] = None
    response_transform: Optional[Dict[str, Any]] = None
    priority: int = 0
    created_at: datetime = Field(default_factory=datetime.utcnow)


class GatewayRequest(BaseModel):
    request_id: Optional[str] = None
    path: str
    method: HTTPMethod
    headers: Dict[str, str] = Field(default_factory=dict)
    body: Any = None
    query_params: Dict[str, Any] = Field(default_factory=dict)
    source_protocol: ProtocolType = ProtocolType.HTTP


class GatewayResponse(BaseModel):
    request_id: str
    status_code: int
    body: Any
    protocol: ProtocolType
    headers: Dict[str, str] = Field(default_factory=dict)
    latency_ms: Optional[float] = None


class GatewayMetrics(BaseModel):
    total_requests: int = 0
    success_requests: int = 0
    failed_requests: int = 0
    average_latency_ms: float = 0.0
    p95_latency_ms: float = 0.0
    p99_latency_ms: float = 0.0
    active_connections: int = 0
