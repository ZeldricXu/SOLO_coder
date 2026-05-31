from typing import Dict, Any, Optional, Callable, Awaitable
from datetime import datetime, timedelta
from enum import Enum
from ..models import CircuitBreakerState, CircuitBreakerConfig
from ..interfaces import CircuitBreakerManagerPort
import logging
import asyncio

logger = logging.getLogger(__name__)


class CircuitBreaker:
    def __init__(self, key: str, config: CircuitBreakerConfig):
        self.key = key
        self.config = config
        self.state = CircuitBreakerState.CLOSED
        self.failure_count = 0
        self.success_count = 0
        self.last_failure_time: Optional[datetime] = None
        self.opened_at: Optional[datetime] = None

    async def execute(self, func: Callable[..., Awaitable[Any]], *args, **kwargs) -> Any:
        if self.state == CircuitBreakerState.OPEN:
            if self._should_attempt_half_open():
                self.state = CircuitBreakerState.HALF_OPEN
                self.success_count = 0
                logger.info(f"Circuit breaker {self.key} transitioning to HALF_OPEN")
            else:
                raise Exception(f"Circuit breaker {self.key} is OPEN")

        try:
            result = await func(*args, **kwargs)
            self._on_success()
            return result
        except Exception as e:
            self._on_failure()
            raise

    def _should_attempt_half_open(self) -> bool:
        if not self.opened_at:
            return False
        elapsed = (datetime.utcnow() - self.opened_at).total_seconds()
        return elapsed >= self.config.timeout_seconds

    def _on_success(self) -> None:
        if self.state == CircuitBreakerState.HALF_OPEN:
            self.success_count += 1
            if self.success_count >= self.config.half_open_max_calls:
                self.state = CircuitBreakerState.CLOSED
                self.failure_count = 0
                self.opened_at = None
                logger.info(f"Circuit breaker {self.key} reset to CLOSED")
        else:
            self.failure_count = max(0, self.failure_count - 1)

    def _on_failure(self) -> None:
        self.failure_count += 1
        self.last_failure_time = datetime.utcnow()

        if self.state == CircuitBreakerState.HALF_OPEN:
            self._trip()
        elif self.failure_count >= self.config.failure_threshold:
            self._trip()

    def _trip(self) -> None:
        self.state = CircuitBreakerState.OPEN
        self.opened_at = datetime.utcnow()
        logger.warning(f"Circuit breaker {self.key} tripped to OPEN after {self.failure_count} failures")

    def get_status(self) -> Dict[str, Any]:
        return {
            "key": self.key,
            "state": self.state.value,
            "failure_count": self.failure_count,
            "success_count": self.success_count,
            "last_failure": self.last_failure_time.isoformat() if self.last_failure_time else None,
            "opened_at": self.opened_at.isoformat() if self.opened_at else None,
        }


class CircuitBreakerManager(CircuitBreakerManagerPort):
    def __init__(self):
        self._breakers: Dict[str, CircuitBreaker] = {}

    def get_breaker(self, key: str, config: CircuitBreakerConfig) -> CircuitBreaker:
        if key not in self._breakers:
            self._breakers[key] = CircuitBreaker(key, config)
        return self._breakers[key]

    def get_all_statuses(self) -> Dict[str, Dict[str, Any]]:
        return {key: breaker.get_status() for key, breaker in self._breakers.items()}

    def reset_breaker(self, key: str) -> bool:
        if key in self._breakers:
            del self._breakers[key]
            return True
        return False
