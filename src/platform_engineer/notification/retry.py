import asyncio
import random
from abc import ABC, abstractmethod
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Optional


class RetryPolicy(ABC):
    def __init__(self, max_attempts: int = 3, jitter: float = 0.0):
        self.max_attempts = max_attempts
        self.jitter = jitter

    @abstractmethod
    def get_delay(self, attempt: int) -> float:
        pass

    def should_retry(self, attempt: int, exception: Exception) -> bool:
        return attempt < self.max_attempts

    def _apply_jitter(self, base_delay: float) -> float:
        if self.jitter <= 0:
            return base_delay
        jitter_amount = base_delay * self.jitter
        return base_delay + random.uniform(-jitter_amount, jitter_amount)


class FixedIntervalPolicy(RetryPolicy):
    def __init__(
        self,
        interval_seconds: float = 5.0,
        max_attempts: int = 3,
        jitter: float = 0.1,
    ):
        super().__init__(max_attempts, jitter)
        self.interval_seconds = interval_seconds

    def get_delay(self, attempt: int) -> float:
        return self._apply_jitter(self.interval_seconds)


class ExponentialBackoffPolicy(RetryPolicy):
    def __init__(
        self,
        initial_delay: float = 1.0,
        multiplier: float = 2.0,
        max_delay: float = 60.0,
        max_attempts: int = 5,
        jitter: float = 0.1,
    ):
        super().__init__(max_attempts, jitter)
        self.initial_delay = initial_delay
        self.multiplier = multiplier
        self.max_delay = max_delay

    def get_delay(self, attempt: int) -> float:
        base_delay = self.initial_delay * (self.multiplier ** attempt)
        base_delay = min(base_delay, self.max_delay)
        return self._apply_jitter(base_delay)


class RetryExecutor:
    def __init__(self, policy: RetryPolicy, logger=None):
        self._policy = policy
        self._logger = logger
        self._attempts: List[Dict[str, Any]] = []

    async def execute(
        self,
        func: Callable,
        *args,
        on_retry: Optional[Callable[[int, Exception], Any]] = None,
        **kwargs,
    ) -> Any:
        last_exception: Optional[Exception] = None
        for attempt in range(self._policy.max_attempts):
            try:
                self._attempts.append({
                    "attempt": attempt + 1,
                    "started_at": datetime.now(timezone.utc),
                    "status": "in_progress",
                })
                result = func(*args, **kwargs)
                if asyncio.iscoroutine(result):
                    result = await result
                self._attempts[-1]["status"] = "success"
                self._attempts[-1]["completed_at"] = datetime.now(timezone.utc)
                return result
            except Exception as e:
                last_exception = e
                self._attempts[-1]["status"] = "failed"
                self._attempts[-1]["error"] = str(e)
                self._attempts[-1]["completed_at"] = datetime.now(timezone.utc)
                if self._policy.should_retry(attempt + 1, e):
                    delay = self._policy.get_delay(attempt)
                    if self._logger:
                        self._logger.warning(
                            f"Attempt {attempt + 1}/{self._policy.max_attempts} failed: {e}, "
                            f"retrying in {delay:.2f}s"
                        )
                    if on_retry:
                        retry_cb = on_retry(attempt + 1, e)
                        if asyncio.iscoroutine(retry_cb):
                            await retry_cb
                    await asyncio.sleep(delay)
                else:
                    break
        if last_exception:
            raise last_exception
        raise RuntimeError("Retry executor failed without exception")

    def get_attempts(self) -> List[Dict[str, Any]]:
        return list(self._attempts)

    def get_total_attempts(self) -> int:
        return len(self._attempts)

    def get_success_attempt(self) -> Optional[Dict[str, Any]]:
        for attempt in reversed(self._attempts):
            if attempt["status"] == "success":
                return attempt
        return None
