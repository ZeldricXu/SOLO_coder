from datetime import datetime
from typing import List, Optional, Dict, Any, Union
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class ModelProvider(str, Enum):
    OPENAI = "openai"
    ANTHROPIC = "anthropic"
    ZHIPU = "zhipu"
    QWEN = "qwen"
    DEEPSEEK = "deepseek"
    LOCAL = "local"
    CUSTOM = "custom"


class LoadBalanceStrategy(str, Enum):
    ROUND_ROBIN = "round_robin"
    LEAST_CONNECTIONS = "least_connections"
    LEAST_LATENCY = "least_latency"
    WEIGHTED_RANDOM = "weighted_random"
    PRIORITY_BASED = "priority_based"


class FallbackPolicy(str, Enum):
    NONE = "none"
    FAILOVER = "failover"
    RETRY_ON_SAME = "retry_same"
    RETRY_ON_DIFFERENT = "retry_different"
    ALL_PROVIDERS = "all_providers"


class ChatRole(str, Enum):
    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"
    FUNCTION = "function"


class ChatMessage(BaseModel):
    role: ChatRole
    content: str
    name: Optional[str] = None
    function_call: Optional[Dict[str, Any]] = None


class ProviderConfig(BaseModel):
    provider: ModelProvider
    api_key: str
    base_url: Optional[str] = None
    model_names: List[str] = Field(default_factory=list)
    weight: int = Field(default=1, ge=1, le=100)
    max_concurrent_requests: int = 100
    timeout_ms: int = 30000
    retry_count: int = 2
    enabled: bool = True
    priority: int = Field(default=1, ge=1, le=10)
    cost_per_1k_tokens: float = Field(default=0.0, ge=0.0)
    rate_limit_per_minute: Optional[int] = None
    daily_quota: Optional[int] = None


class InferenceRequest(BaseModel):
    model: str
    messages: List[ChatMessage]
    provider: Optional[ModelProvider] = None
    temperature: float = Field(default=0.7, ge=0.0, le=2.0)
    max_tokens: int = Field(default=2048, ge=1)
    top_p: float = Field(default=1.0, ge=0.0, le=1.0)
    frequency_penalty: float = Field(default=0.0, ge=-2.0, le=2.0)
    presence_penalty: float = Field(default=0.0, ge=-2.0, le=2.0)
    stop_sequences: Optional[List[str]] = None
    stream: bool = False
    load_balance_strategy: LoadBalanceStrategy = LoadBalanceStrategy.ROUND_ROBIN
    fallback_policy: FallbackPolicy = FallbackPolicy.FAILOVER
    priority: Optional[int] = None
    user_id: Optional[str] = None
    session_id: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None


class RouteDecision(BaseModel):
    provider: ModelProvider
    model: str
    reason: str
    latency_p95_ms: Optional[float] = None
    estimated_cost: Optional[float] = None


class InferenceResponse(BaseModel):
    response_id: str
    model: str
    provider: ModelProvider
    message: ChatMessage
    usage: Dict[str, int]
    latency_ms: float
    route_decision: RouteDecision
    fallback_used: bool = False
    fallback_provider: Optional[ModelProvider] = None
    timestamp: datetime


class GatewayStats(BaseModel):
    total_requests: int
    successful_requests: int
    failed_requests: int
    total_latency_ms: float
    average_latency_ms: float
    p50_latency_ms: float
    p95_latency_ms: float
    p99_latency_ms: float
    total_tokens: int
    total_cost: float
    requests_per_provider: Dict[str, int]
    error_rate: float
    last_updated: datetime
