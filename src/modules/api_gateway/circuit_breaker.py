from typing import Dict, Any, Optional, Callable
from datetime import datetime, timedelta
from .types import CircuitBreakerState, CircuitBreakerConfig
from src.core import CircuitBreakerOpenError
import logging
import asyncio

logger = logging.getLogger(__name__)


class CircuitBreaker:
    def __init__(
        self,
        name: str,
        config: Optional[CircuitBreakerConfig] = None,
        fallback_handler: Optional[Callable] = None,
    ):
        self.name = name
        self.config = config or CircuitBreakerConfig()
        self.fallback_handler = fallback_handler

        self.state = CircuitBreakerState.CLOSED
        self.failure_count = 0
        self.success_count = 0
        self.total_requests = 0
        self.opened_at: Optional[datetime] = None
        self._lock = asyncio.Lock()

    async def execute(self, func: Callable, *args, **kwargs) -> Any:
        if self.state == CircuitBreakerState.OPEN:
            if self._should_try_close():
                self.state = CircuitBreakerState.HALF_OPEN
                self.success_count = 0
                logger.info(f"Circuit breaker {self.name} transitioning to HALF_OPEN")
            else:
                remaining = (self.opened_at + timedelta(seconds=self.config.timeout_seconds) - datetime.utcnow()).total_seconds()
                raise CircuitBreakerOpenError(
                    f"Circuit breaker is open for {self.name}, try again in {remaining:.1f}s"
                )

        self.total_requests += 1

        try:
            result = await func(*args, **kwargs)
            await self._on_success()
            return result
        except Exception as e:
            await self._on_failure()
            if self.fallback_handler and self.state != CircuitBreakerState.CLOSED:
                logger.warning(f"Using fallback for {self.name} due to: {e}")
                return await self.fallback_handler(*args, **kwargs)
            raise

    async def _on_success(self) -> None:
        async with self._lock:
            if self.state == CircuitBreakerState.HALF_OPEN:
                self.success_count += 1
                if self.success_count >= self.config.success_threshold:
                    self.state = CircuitBreakerState.CLOSED
                    self.failure_count = 0
                    self.success_count = 0
                    logger.info(f"Circuit breaker {self.name} closed")
            elif self.state == CircuitBreakerState.CLOSED:
                self.failure_count = max(0, self.failure_count - 1)

    async def _on_failure(self) -> None:
        async with self._lock:
            self.failure_count += 1
            failure_rate = self.failure_count / max(self.total_requests, 1)

            if self.state == CircuitBreakerState.HALF_OPEN:
                self._open()
            elif self.state == CircuitBreakerState.CLOSED:
                if (
                    self.failure_count >= self.config.failure_threshold
                    or failure_rate >= self.config.failure_rate_threshold
                ):
                    self._open()

    def _open(self) -> None:
        self.state = CircuitBreakerState.OPEN
        self.opened_at = datetime.utcnow()
        self.failure_count = 0
        self.success_count = 0
        logger.warning(
            f"Circuit breaker {self.name} opened after {self.failure_count} failures"
        )

    def _should_try_close(self) -> bool:
        if self.opened_at is None:
            return True
        return (datetime.utcnow() - self.opened_at).total_seconds() >= self.config.timeout_seconds

    def get_status(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "state": self.state.value,
            "failure_count": self.failure_count,
            "success_count": self.success_count,
            "total_requests": self.total_requests,
            "opened_at": self.opened_at.isoformat() if self.opened_at else None,
            "config": self.config.model_dump(),
        }

    async def reset(self) -> None:
        async with self._lock:
            self.state = CircuitBreakerState.CLOSED
            self.failure_count = 0
            self.success_count = 0
            self.total_requests = 0
            self.opened_at = None
            logger.info(f"Circuit breaker {self.name} reset")


class CircuitBreakerManager:
    def __init__(self, default_config: Optional[CircuitBreakerConfig] = None):
        self._breakers: Dict[str, CircuitBreaker] = {}
        self.default_config = default_config or CircuitBreakerConfig()

    def get_breaker(self, name: str, config: Optional[CircuitBreakerConfig] = None) -> CircuitBreaker:
        if name not in self._breakers:
            self._breakers[name] = CircuitBreaker(name, config or self.default_config)
        return self._breakers[name]

    def get_all_statuses(self) -> Dict[str, Dict[str, Any]]:
        return {name: breaker.get_status() for name, breaker in self._breakers.items()}

    async def reset_all(self) -> None:
        for breaker in self._breakers.values():
            await breaker.reset()
