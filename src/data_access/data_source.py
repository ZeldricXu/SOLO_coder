from __future__ import annotations

import asyncio
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from src.common.exceptions import TimeoutError
from src.common.utils import async_retry

logger = logging.getLogger(__name__)


@dataclass
class DataSourceConfig:
    name: str
    type: str
    connection_string: str
    timeout: int = 30
    max_retries: int = 3
    retry_delay: float = 0.5
    pool_size: int = 10
    options: Dict[str, Any] = field(default_factory=dict)


class DataSource(ABC):
    def __init__(self, config: DataSourceConfig) -> None:
        self.config = config
        self._connected = False

    @abstractmethod
    async def connect(self) -> None:
        ...

    @abstractmethod
    async def disconnect(self) -> None:
        ...

    @abstractmethod
    async def execute(self, query: str, params: Optional[Dict[str, Any]] = None) -> Any:
        ...

    async def health_check(self) -> bool:
        try:
            await self.execute("SELECT 1" if self.config.type == "sql" else "ping")
            return True
        except Exception as e:
            logger.warning(f"Health check failed for {self.config.name}: {e}")
            return False


class DataSourceManager:
    def __init__(self) -> None:
        self._sources: Dict[str, DataSource] = {}
        self._circuit_breakers: Dict[str, "CircuitBreaker"] = {}

    def register(self, source: DataSource) -> None:
        self._sources[source.config.name] = source
        self._circuit_breakers[source.config.name] = CircuitBreaker(
            failure_threshold=5,
            recovery_timeout=30,
        )

    def get(self, name: str) -> DataSource:
        if name not in self._sources:
            raise ValueError(f"Unknown data source: {name}")
        return self._sources[name]

    async def execute_with_fallback(
        self,
        source_name: str,
        query: str,
        params: Optional[Dict[str, Any]] = None,
        fallback: Optional[Callable[[], Any]] = None,
    ) -> Any:
        breaker = self._circuit_breakers.get(source_name)
        if breaker and not breaker.allow_request():
            logger.warning(f"Circuit breaker OPEN for {source_name}, using fallback")
            if fallback:
                return fallback()
            raise TimeoutError(f"Service {source_name} is unavailable")

        source = self.get(source_name)
        try:
            result = await self._execute_safe(source, query, params)
            if breaker:
                breaker.record_success()
            return result
        except Exception as e:
            if breaker:
                breaker.record_failure()
            logger.error(f"Query failed on {source_name}: {e}")
            if fallback:
                return fallback()
            raise

    @async_retry(max_attempts=3)
    async def _execute_safe(
        self,
        source: DataSource,
        query: str,
        params: Optional[Dict[str, Any]] = None,
    ) -> Any:
        return await source.execute(query, params)

    async def health_check_all(self) -> Dict[str, bool]:
        results = {}
        for name, source in self._sources.items():
            results[name] = await source.health_check()
        return results

    async def connect_all(self) -> None:
        await asyncio.gather(*[s.connect() for s in self._sources.values()])

    async def disconnect_all(self) -> None:
        await asyncio.gather(*[s.disconnect() for s in self._sources.values()])


class CircuitBreaker:
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"

    def __init__(self, failure_threshold: int = 5, recovery_timeout: int = 30) -> None:
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self.state = self.CLOSED
        self.failure_count = 0
        self.last_failure_time: Optional[float] = None

    def allow_request(self) -> bool:
        import time
        if self.state == self.CLOSED:
            return True
        if self.state == self.OPEN:
            if self.last_failure_time and (time.time() - self.last_failure_time) > self.recovery_timeout:
                self.state = self.HALF_OPEN
                return True
            return False
        return True

    def record_success(self) -> None:
        if self.state == self.HALF_OPEN:
            self.state = self.CLOSED
            self.failure_count = 0
        elif self.state == self.CLOSED:
            self.failure_count = max(0, self.failure_count - 1)

    def record_failure(self) -> None:
        import time
        self.failure_count += 1
        if self.failure_count >= self.failure_threshold:
            self.state = self.OPEN
            self.last_failure_time = time.time()
