from typing import List, Dict, Any, Optional, Tuple
from datetime import datetime
import asyncio
import random
import time
from collections import defaultdict, deque

from .schemas import (
    ModelProvider,
    InferenceRequest,
    InferenceResponse,
    ChatMessage,
    ChatRole,
    LoadBalanceStrategy,
    FallbackPolicy,
    ProviderConfig,
    GatewayStats,
    RouteDecision,
)
from common.config import settings
from common.logger import get_logger
from common.utils import generate_id, utc_now, CircuitBreaker

logger = get_logger(__name__)


class InferenceGatewayService:
    def __init__(self):
        self.providers: Dict[ModelProvider, ProviderConfig] = {}
        self.provider_stats: Dict[ModelProvider, Dict[str, Any]] = defaultdict(lambda: {
            "requests": 0,
            "successes": 0,
            "failures": 0,
            "total_latency": 0.0,
            "latencies": deque(maxlen=1000),
            "concurrent": 0,
            "tokens": 0,
            "cost": 0.0,
        })
        self.circuit_breakers: Dict[ModelProvider, CircuitBreaker] = {}
        self.round_robin_index: Dict[str, int] = defaultdict(int)
        self._init_default_providers()

    def _init_default_providers(self):
        if settings.OPENAI_API_KEY:
            self.register_provider(ProviderConfig(
                provider=ModelProvider.OPENAI,
                api_key=settings.OPENAI_API_KEY,
                model_names=["gpt-3.5-turbo", "gpt-4", "gpt-4-turbo", "text-embedding-ada-002"],
                base_url="https://api.openai.com/v1",
                weight=80,
                cost_per_1k_tokens=0.0015,
            ))

        if settings.ANTHROPIC_API_KEY:
            self.register_provider(ProviderConfig(
                provider=ModelProvider.ANTHROPIC,
                api_key=settings.ANTHROPIC_API_KEY,
                model_names=["claude-3-opus", "claude-3-sonnet", "claude-3-haiku"],
                base_url="https://api.anthropic.com/v1",
                weight=60,
                cost_per_1k_tokens=0.003,
            ))

        if settings.ZHIPU_API_KEY:
            self.register_provider(ProviderConfig(
                provider=ModelProvider.ZHIPU,
                api_key=settings.ZHIPU_API_KEY,
                model_names=["glm-4", "glm-3-turbo", "chatglm3"],
                base_url="https://open.bigmodel.cn/api/paas/v4",
                weight=70,
                cost_per_1k_tokens=0.0005,
            ))

        for provider in self.providers:
            self.circuit_breakers[provider] = CircuitBreaker(failure_threshold=5, recovery_timeout=30)

        logger.info(f"Initialized {len(self.providers)} model providers")

    def register_provider(self, config: ProviderConfig):
        self.providers[config.provider] = config
        if config.provider not in self.circuit_breakers:
            self.circuit_breakers[config.provider] = CircuitBreaker()
        logger.info(f"Registered provider: {config.provider.value}")

    def unregister_provider(self, provider: ModelProvider):
        if provider in self.providers:
            del self.providers[provider]
            if provider in self.circuit_breakers:
                del self.circuit_breakers[provider]
            logger.info(f"Unregistered provider: {provider.value}")

    def get_enabled_providers(self, model: Optional[str] = None) -> List[Tuple[ModelProvider, ProviderConfig]]:
        result = []
        for provider, config in self.providers.items():
            if not config.enabled:
                continue
            if model and model not in config.model_names:
                continue
            if not self.circuit_breakers[provider].allow_request():
                continue
            result.append((provider, config))
        return result

    async def route_request(self, request: InferenceRequest) -> RouteDecision:
        if request.provider:
            if request.provider in self.providers and self.providers[request.provider].enabled:
                return RouteDecision(
                    provider=request.provider,
                    model=request.model,
                    reason="explicit_provider_selection",
                )
            raise ValueError(f"Provider {request.provider.value} not available")

        enabled = self.get_enabled_providers(request.model)
        if not enabled:
            raise ValueError(f"No available providers for model: {request.model}")

        strategy = request.load_balance_strategy

        if strategy == LoadBalanceStrategy.ROUND_ROBIN:
            provider, config = self._select_round_robin(request.model, enabled)
        elif strategy == LoadBalanceStrategy.LEAST_CONNECTIONS:
            provider, config = self._select_least_connections(enabled)
        elif strategy == LoadBalanceStrategy.LEAST_LATENCY:
            provider, config = self._select_least_latency(enabled)
        elif strategy == LoadBalanceStrategy.WEIGHTED_RANDOM:
            provider, config = self._select_weighted_random(enabled)
        elif strategy == LoadBalanceStrategy.PRIORITY_BASED:
            provider, config = self._select_priority_based(enabled)
        else:
            provider, config = enabled[0]

        stats = self.provider_stats[provider]
        estimated_cost = None
        if config.cost_per_1k_tokens > 0:
            estimated_tokens = sum(len(m.content) for m in request.messages) // 4
            estimated_cost = (estimated_tokens / 1000) * config.cost_per_1k_tokens

        return RouteDecision(
            provider=provider,
            model=request.model,
            reason=f"load_balance:{strategy.value}",
            latency_p95_ms=stats["latencies"][-1] if stats["latencies"] else None,
            estimated_cost=estimated_cost,
        )

    def _select_round_robin(self, model: str, providers: List[Tuple]) -> Tuple:
        key = model
        idx = self.round_robin_index[key] % len(providers)
        self.round_robin_index[key] += 1
        return providers[idx]

    def _select_least_connections(self, providers: List[Tuple]) -> Tuple:
        return min(providers, key=lambda p: self.provider_stats[p[0]]["concurrent"])

    def _select_least_latency(self, providers: List[Tuple]) -> Tuple:
        def avg_latency(p):
            stats = self.provider_stats[p[0]]
            return sum(stats["latencies"]) / len(stats["latencies"]) if stats["latencies"] else float("inf")
        return min(providers, key=avg_latency)

    def _select_weighted_random(self, providers: List[Tuple]) -> Tuple:
        total_weight = sum(p[1].weight for p in providers)
        r = random.uniform(0, total_weight)
        cumulative = 0
        for provider, config in providers:
            cumulative += config.weight
            if r <= cumulative:
                return provider, config
        return providers[0]

    def _select_priority_based(self, providers: List[Tuple]) -> Tuple:
        return min(providers, key=lambda p: p[1].priority)

    async def infer(self, request: InferenceRequest) -> InferenceResponse:
        start_time = time.time()
        response_id = generate_id("resp_")

        primary_decision = await self.route_request(request)
        fallback_used = False
        fallback_provider = None

        try:
            response = await self._execute_inference(request, primary_decision)
            latency = (time.time() - start_time) * 1000
            self._record_success(primary_decision.provider, latency, response.usage.get("total_tokens", 0))
            return InferenceResponse(
                response_id=response_id,
                model=request.model,
                provider=primary_decision.provider,
                message=response.message,
                usage=response.usage,
                latency_ms=latency,
                route_decision=primary_decision,
                fallback_used=False,
                timestamp=utc_now(),
            )
        except Exception as e:
            self._record_failure(primary_decision.provider)
            logger.warning(f"Primary provider {primary_decision.provider.value} failed: {str(e)}")

            if request.fallback_policy != FallbackPolicy.NONE:
                fallback_result = await self._try_fallback(request, primary_decision)
                if fallback_result:
                    fallback_used = True
                    fallback_provider = fallback_result[0]
                    response, provider = fallback_result
                    latency = (time.time() - start_time) * 1000
                    return InferenceResponse(
                        response_id=response_id,
                        model=request.model,
                        provider=primary_decision.provider,
                        message=response.message,
                        usage=response.usage,
                        latency_ms=latency,
                        route_decision=primary_decision,
                        fallback_used=True,
                        fallback_provider=provider,
                        timestamp=utc_now(),
                    )

            raise

    async def _execute_inference(self, request: InferenceRequest, decision: RouteDecision):
        provider = decision.provider
        stats = self.provider_stats[provider]
        stats["concurrent"] += 1

        try:
            mock_response = self._generate_mock_response(request)
            return mock_response
        finally:
            stats["concurrent"] -= 1

    def _generate_mock_response(self, request: InferenceRequest):
        last_message = request.messages[-1].content if request.messages else ""
        response_content = f"这是对'{last_message[:50]}...'的模拟响应。实际使用时会调用真实的模型API。"

        return type('MockResponse', (), {
            'message': ChatMessage(role=ChatRole.ASSISTANT, content=response_content),
            'usage': {'prompt_tokens': len(last_message) // 4, 'completion_tokens': len(response_content) // 4, 'total_tokens': (len(last_message) + len(response_content)) // 4}
        })()

    async def _try_fallback(self, request: InferenceRequest, failed_decision: RouteDecision):
        policy = request.fallback_policy

        if policy == FallbackPolicy.FAILOVER or policy == FallbackPolicy.RETRY_ON_DIFFERENT:
            enabled = self.get_enabled_providers(request.model)
            for provider, config in enabled:
                if provider == failed_decision.provider:
                    continue
                try:
                    decision = RouteDecision(provider=provider, model=request.model, reason="fallover")
                    response = await self._execute_inference(request, decision)
                    self._record_success(provider, 0, 0)
                    return response, provider
                except Exception as e:
                    self._record_failure(provider)
                    logger.warning(f"Fallback provider {provider.value} also failed: {str(e)}")
                    continue

        if policy == FallbackPolicy.RETRY_ON_SAME:
            try:
                response = await self._execute_inference(request, failed_decision)
                return response, failed_decision.provider
            except Exception as e:
                logger.warning(f"Retry on same provider failed: {str(e)}")

        return None

    def _record_success(self, provider: ModelProvider, latency: float, tokens: int):
        stats = self.provider_stats[provider]
        stats["requests"] += 1
        stats["successes"] += 1
        stats["total_latency"] += latency
        stats["latencies"].append(latency)
        stats["tokens"] += tokens

        config = self.providers.get(provider)
        if config and config.cost_per_1k_tokens > 0:
            stats["cost"] += (tokens / 1000) * config.cost_per_1k_tokens

        self.circuit_breakers[provider].record_success()

    def _record_failure(self, provider: ModelProvider):
        stats = self.provider_stats[provider]
        stats["requests"] += 1
        stats["failures"] += 1
        self.circuit_breakers[provider].record_failure()

    def get_gateway_stats(self) -> GatewayStats:
        total_requests = sum(s["requests"] for s in self.provider_stats.values())
        total_success = sum(s["successes"] for s in self.provider_stats.values())
        total_failures = sum(s["failures"] for s in self.provider_stats.values())
        total_latency = sum(s["total_latency"] for s in self.provider_stats.values())
        total_tokens = sum(s["tokens"] for s in self.provider_stats.values())
        total_cost = sum(s["cost"] for s in self.provider_stats.values())

        all_latencies = []
        for s in self.provider_stats.values():
            all_latencies.extend(s["latencies"])
        all_latencies.sort()

        p50 = p95 = p99 = 0.0
        if all_latencies:
            p50 = all_latencies[len(all_latencies) // 2]
            p95 = all_latencies[int(len(all_latencies) * 0.95)]
            p99 = all_latencies[int(len(all_latencies) * 0.99)]

        return GatewayStats(
            total_requests=total_requests,
            successful_requests=total_success,
            failed_requests=total_failures,
            total_latency_ms=total_latency,
            average_latency_ms=total_latency / total_requests if total_requests > 0 else 0,
            p50_latency_ms=p50,
            p95_latency_ms=p95,
            p99_latency_ms=p99,
            total_tokens=total_tokens,
            total_cost=total_cost,
            requests_per_provider={p.value: s["requests"] for p, s in self.provider_stats.items()},
            error_rate=total_failures / total_requests if total_requests > 0 else 0,
            last_updated=utc_now(),
        )

    def list_providers(self) -> List[Dict[str, Any]]:
        return [
            {
                "provider": p.value,
                "models": c.model_names,
                "enabled": c.enabled,
                "weight": c.weight,
                "priority": c.priority,
                "cost_per_1k_tokens": c.cost_per_1k_tokens,
                "circuit_breaker_state": self.circuit_breakers[p].state,
            }
            for p, c in self.providers.items()
        ]


inference_gateway_service = InferenceGatewayService()
