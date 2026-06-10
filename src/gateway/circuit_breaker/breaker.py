from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional
import time
import json

from redis.asyncio import Redis
from redis.exceptions import RedisError

from gateway.config import get_settings
from gateway.db.redis_client import get_redis
from gateway.logger import get_logger

logger = get_logger("circuit-breaker")


class CircuitState(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass
class CircuitBreakerResult:
    allowed: bool
    state: CircuitState
    fallback_response: Optional[Dict[str, Any]] = None
    fallback_target: Optional[str] = None
    retry_after: int = 0


@dataclass
class CircuitMetrics:
    total_requests: int = 0
    success_count: int = 0
    failure_count: int = 0
    slow_count: int = 0
    total_latency: float = 0.0
    error_rate: float = 0.0
    slow_rate: float = 0.0
    avg_latency: float = 0.0


class CircuitBreaker:
    def __init__(self):
        self.settings = get_settings()
        self.cb_settings = self.settings.circuit_breaker
        self.redis: Redis = get_redis()
        self._circuits: Dict[str, Dict[str, Any]] = {}

    def _get_key(self, service_name: str, metric_type: str) -> str:
        return f"{self.cb_settings.redis_key_prefix}{service_name}:{metric_type}"

    async def check(self, service_name: str, config: Optional[Dict[str, Any]] = None) -> CircuitBreakerResult:
        config = config or {}
        failure_threshold = config.get("failure_threshold", self.cb_settings.failure_threshold)
        slow_threshold = config.get("slow_request_threshold", self.cb_settings.slow_request_threshold)
        slow_duration = config.get("slow_request_duration", self.cb_settings.slow_request_duration)
        wait_duration = config.get("wait_duration", self.cb_settings.wait_duration_in_open_state)
        half_open_calls = config.get("half_open_calls", self.cb_settings.permitted_num_of_calls_in_half_open)

        current_state = await self._get_state(service_name)
        now = int(time.time())

        if current_state == CircuitState.OPEN:
            open_at = await self.redis.get(self._get_key(service_name, "open_at"))
            if open_at and (now - int(open_at)) >= wait_duration:
                await self._set_state(service_name, CircuitState.HALF_OPEN)
                await self._reset_half_open_metrics(service_name, half_open_calls)
                remaining = await self.redis.decr(self._get_key(service_name, "half_open_remaining"))
                return CircuitBreakerResult(
                    allowed=True,
                    state=CircuitState.HALF_OPEN,
                )

            retry_after = wait_duration - (now - (int(open_at) if open_at else now))
            return CircuitBreakerResult(
                allowed=False,
                state=CircuitState.OPEN,
                fallback_response=config.get("fallback_response"),
                fallback_target=config.get("fallback_target"),
                retry_after=max(0, retry_after),
            )

        if current_state == CircuitState.HALF_OPEN:
            remaining = await self.redis.decr(self._get_key(service_name, "half_open_remaining"))
            if remaining < 0:
                return CircuitBreakerResult(
                    allowed=False,
                    state=CircuitState.HALF_OPEN,
                    fallback_response=config.get("fallback_response"),
                    fallback_target=config.get("fallback_target"),
                    retry_after=1,
                )
            return CircuitBreakerResult(
                allowed=True,
                state=CircuitState.HALF_OPEN,
            )

        return CircuitBreakerResult(
            allowed=True,
            state=CircuitState.CLOSED,
        )

    async def record_success(self, service_name: str, latency: float) -> None:
        pipe = self.redis.pipeline()
        key = self._get_key(service_name, "metrics")

        pipe.hincrby(key, "total_requests", 1)
        pipe.hincrby(key, "success_count", 1)
        pipe.hincrbyfloat(key, "total_latency", latency)
        pipe.lpush(self._get_key(service_name, "latency_window"), latency)
        pipe.ltrim(self._get_key(service_name, "latency_window"), 0, self.cb_settings.rolling_window_size - 1)

        await pipe.execute()

        current_state = await self._get_state(service_name)
        if current_state == CircuitState.HALF_OPEN:
            await self._check_half_open_success(service_name)

    async def record_failure(self, service_name: str, latency: float, is_slow: bool = False) -> None:
        pipe = self.redis.pipeline()
        key = self._get_key(service_name, "metrics")

        pipe.hincrby(key, "total_requests", 1)
        pipe.hincrby(key, "failure_count", 1)
        pipe.hincrbyfloat(key, "total_latency", latency)
        if is_slow:
            pipe.hincrby(key, "slow_count", 1)
        pipe.lpush(self._get_key(service_name, "latency_window"), latency)
        pipe.ltrim(self._get_key(service_name, "latency_window"), 0, self.cb_settings.rolling_window_size - 1)

        await pipe.execute()

        await self._check_and_open(service_name)

        current_state = await self._get_state(service_name)
        if current_state == CircuitState.HALF_OPEN:
            await self._open_circuit(service_name)

    async def _check_and_open(self, service_name: str) -> None:
        metrics = await self._get_metrics(service_name)

        if metrics.total_requests >= 10:
            if metrics.error_rate >= self.cb_settings.failure_threshold:
                logger.warning("Circuit breaker opening due to high error rate",
                               service_name=service_name,
                               error_rate=metrics.error_rate,
                               threshold=self.cb_settings.failure_threshold)
                await self._open_circuit(service_name)

            elif metrics.slow_rate >= self.cb_settings.slow_request_threshold:
                logger.warning("Circuit breaker opening due to high slow request rate",
                               service_name=service_name,
                               slow_rate=metrics.slow_rate,
                               threshold=self.cb_settings.slow_request_threshold)
                await self._open_circuit(service_name)

    async def _open_circuit(self, service_name: str) -> None:
        pipe = self.redis.pipeline()
        pipe.set(self._get_key(service_name, "state"), CircuitState.OPEN.value)
        pipe.set(self._get_key(service_name, "open_at"), int(time.time()))
        pipe.delete(self._get_key(service_name, "metrics"))
        pipe.delete(self._get_key(service_name, "latency_window"))
        await pipe.execute()

        logger.info("Circuit opened", service_name=service_name)

    async def _set_state(self, service_name: str, state: CircuitState) -> None:
        await self.redis.set(self._get_key(service_name, "state"), state.value)
        logger.info("Circuit state changed", service_name=service_name, state=state.value)

    async def _get_state(self, service_name: str) -> CircuitState:
        state = await self.redis.get(self._get_key(service_name, "state"))
        return CircuitState(state) if state else CircuitState.CLOSED

    async def _reset_half_open_metrics(self, service_name: str, permitted_calls: int) -> None:
        pipe = self.redis.pipeline()
        pipe.set(self._get_key(service_name, "half_open_remaining"), permitted_calls)
        pipe.set(self._get_key(service_name, "half_open_successes"), 0)
        pipe.set(self._get_key(service_name, "half_open_failures"), 0)
        await pipe.execute()

    async def _check_half_open_success(self, service_name: str) -> None:
        successes = await self.redis.incr(self._get_key(service_name, "half_open_successes"))
        remaining = await self.redis.get(self._get_key(service_name, "half_open_remaining"))

        if remaining and int(remaining) <= 0:
            required = self.cb_settings.permitted_num_of_calls_in_half_open
            if successes >= required:
                await self._close_circuit(service_name)

    async def _close_circuit(self, service_name: str) -> None:
        pipe = self.redis.pipeline()
        pipe.set(self._get_key(service_name, "state"), CircuitState.CLOSED.value)
        pipe.delete(self._get_key(service_name, "metrics"))
        pipe.delete(self._get_key(service_name, "latency_window"))
        pipe.delete(self._get_key(service_name, "half_open_remaining"))
        pipe.delete(self._get_key(service_name, "half_open_successes"))
        pipe.delete(self._get_key(service_name, "half_open_failures"))
        await pipe.execute()

        logger.info("Circuit closed", service_name=service_name)

    async def _get_metrics(self, service_name: str) -> CircuitMetrics:
        key = self._get_key(service_name, "metrics")
        data = await self.redis.hgetall(key)

        total = int(data.get("total_requests", 0))
        success = int(data.get("success_count", 0))
        failure = int(data.get("failure_count", 0))
        slow = int(data.get("slow_count", 0))
        total_latency = float(data.get("total_latency", 0))

        return CircuitMetrics(
            total_requests=total,
            success_count=success,
            failure_count=failure,
            slow_count=slow,
            total_latency=total_latency,
            error_rate=(failure / total) if total > 0 else 0.0,
            slow_rate=(slow / total) if total > 0 else 0.0,
            avg_latency=(total_latency / total) if total > 0 else 0.0,
        )

    async def get_circuit_status(self, service_name: str) -> Dict[str, Any]:
        state = await self._get_state(service_name)
        metrics = await self._get_metrics(service_name)
        open_at = await self.redis.get(self._get_key(service_name, "open_at"))

        return {
            "service_name": service_name,
            "state": state.value,
            "open_at": int(open_at) if open_at else None,
            "metrics": {
                "total_requests": metrics.total_requests,
                "success_count": metrics.success_count,
                "failure_count": metrics.failure_count,
                "slow_count": metrics.slow_count,
                "error_rate": metrics.error_rate,
                "slow_rate": metrics.slow_rate,
                "avg_latency_ms": round(metrics.avg_latency * 1000, 2),
            },
            "config": {
                "failure_threshold": self.cb_settings.failure_threshold,
                "slow_threshold": self.cb_settings.slow_request_threshold,
                "slow_duration": self.cb_settings.slow_request_duration,
                "wait_duration": self.cb_settings.wait_duration_in_open_state,
            },
        }

    async def reset(self, service_name: str) -> None:
        pipe = self.redis.pipeline()
        pipe.delete(self._get_key(service_name, "state"))
        pipe.delete(self._get_key(service_name, "open_at"))
        pipe.delete(self._get_key(service_name, "metrics"))
        pipe.delete(self._get_key(service_name, "latency_window"))
        pipe.delete(self._get_key(service_name, "half_open_remaining"))
        pipe.delete(self._get_key(service_name, "half_open_successes"))
        pipe.delete(self._get_key(service_name, "half_open_failures"))
        await pipe.execute()

        logger.info("Circuit reset", service_name=service_name)


_breaker_instance: Optional[CircuitBreaker] = None


def get_circuit_breaker() -> CircuitBreaker:
    global _breaker_instance
    if _breaker_instance is None:
        _breaker_instance = CircuitBreaker()
    return _breaker_instance
