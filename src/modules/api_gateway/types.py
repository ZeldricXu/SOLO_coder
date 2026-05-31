from pydantic import BaseModel, Field
from typing import Dict, Any, List, Optional, Union
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
    timeout_seconds: int = 30
    retry_count: int = 3
    protocol_in: ProtocolType = ProtocolType.HTTP
    protocol_out: ProtocolType = ProtocolType.HTTP
    rate_limit: Optional[int] = None
    circuit_breaker_enabled: bool = True
    request_transform: Optional[Dict[str, Any]] = None
    response_transform: Optional[Dict[str, Any]] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)


class GatewayRequest(BaseModel):
    request_id: Optional[str] = None
    path: str
    method: HTTPMethod
    headers: Dict[str, str] = Field(default_factory=dict)
    body: Optional[Union[Dict[str, Any], bytes, str]] = None
    query_params: Dict[str, Any] = Field(default_factory=dict)
    source_protocol: ProtocolType = ProtocolType.HTTP


class GatewayResponse(BaseModel):
    request_id: str
    status_code: int
    headers: Dict[str, str] = Field(default_factory=dict)
    body: Optional[Union[Dict[str, Any], bytes, str]] = None
    target_url: Optional[str] = None
    latency_ms: float = 0.0
    protocol: ProtocolType


class CircuitBreakerState(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


class CircuitBreakerConfig(BaseModel):
    failure_threshold: int = 5
    success_threshold: int = 3
    timeout_seconds: int = 30
    failure_rate_threshold: float = 0.5


class LoadBalanceStrategy(str, Enum):
    ROUND_ROBIN = "round_robin"
    LEAST_CONNECTIONS = "least_connections"
    WEIGHTED_ROUND_ROBIN = "weighted_round_robin"
    RANDOM = "random"


class GatewayMetrics(BaseModel):
    total_requests: int = 0
    success_requests: int = 0
    failed_requests: int = 0
    average_latency_ms: float = 0.0
    p95_latency_ms: float = 0.0
    p99_latency_ms: float = 0.0
    active_connections: int = 0
