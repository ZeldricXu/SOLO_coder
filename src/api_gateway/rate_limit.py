from __future__ import annotations

import logging
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, Optional
from uuid import uuid4

from src.common.exceptions import RateLimitError

logger = logging.getLogger(__name__)


@dataclass
class RateLimitConfig:
    requests: int = 100
    window_seconds: int = 60
    burst_multiplier: float = 1.5
    enabled: bool = True


@dataclass
class WindowState:
    requests: deque[float] = field(default_factory=deque)
    blocked: bool = False
    blocked_until: float = 0.0


class RateLimiter:
    def __init__(self, default_config: Optional[RateLimitConfig] = None) -> None:
        self.default_config = default_config or RateLimitConfig()
        self._windows: Dict[str, Dict[str, WindowState]] = defaultdict(lambda: defaultdict(WindowState))
        self._configs: Dict[str, RateLimitConfig] = {}

    def set_config(self, endpoint: str, config: RateLimitConfig) -> None:
        self._configs[endpoint] = config

    def _get_config(self, endpoint: str) -> RateLimitConfig:
        return self._configs.get(endpoint, self.default_config)

    def _get_client_id(self, headers: Dict[str, Any], ip_address: str) -> str:
        if "x-api-key" in headers:
            return f"apikey:{headers['x-api-key']}"
        if "x-user-id" in headers:
            return f"user:{headers['x-user-id']}"
        return f"ip:{ip_address}"

    def _cleanup_old_requests(self, window: WindowState, cutoff: float) -> None:
        while window.requests and window.requests[0] < cutoff:
            window.requests.popleft()

    def check_rate_limit(
        self,
        endpoint: str,
        headers: Dict[str, Any],
        ip_address: str,
    ) -> Dict[str, Any]:
        config = self._get_config(endpoint)
        if not config.enabled:
            return {"allowed": True, "remaining": config.requests, "reset": int(time.time() + config.window_seconds)}

        client_id = self._get_client_id(headers, ip_address)
        window = self._windows[endpoint][client_id]
        now = time.time()
        cutoff = now - config.window_seconds

        if window.blocked and now < window.blocked_until:
            retry_after = int(window.blocked_until - now)
            raise RateLimitError(
                message="Rate limit exceeded",
                details={"retry_after": retry_after, "limit": config.requests},
            )

        self._cleanup_old_requests(window, cutoff)

        if len(window.requests) >= config.requests:
            window.blocked = True
            window.blocked_until = now + config.window_seconds
            retry_after = config.window_seconds
            raise RateLimitError(
                message="Rate limit exceeded",
                details={"retry_after": retry_after, "limit": config.requests},
            )

        window.requests.append(now)
        remaining = config.requests - len(window.requests)
        reset_time = int(window.requests[0] + config.window_seconds) if window.requests else int(now + config.window_seconds)

        return {
            "allowed": True,
            "remaining": remaining,
            "reset": reset_time,
            "limit": config.requests,
        }

    def get_stats(self, endpoint: Optional[str] = None) -> Dict[str, Any]:
        stats: Dict[str, Any] = {}
        endpoints = [endpoint] if endpoint else list(self._windows.keys())
        for ep in endpoints:
            endpoint_windows = self._windows.get(ep, {})
            stats[ep] = {
                "total_clients": len(endpoint_windows),
                "blocked_clients": sum(1 for w in endpoint_windows.values() if w.blocked),
                "active_requests": sum(len(w.requests) for w in endpoint_windows.values()),
            }
        return stats

    def reset_client(self, endpoint: str, client_id: str) -> None:
        if endpoint in self._windows and client_id in self._windows[endpoint]:
            del self._windows[endpoint][client_id]


class TokenBucketRateLimiter:
    def __init__(self, rate: float = 10.0, capacity: float = 50.0) -> None:
        self.rate = rate
        self.capacity = capacity
        self._buckets: Dict[str, Dict[str, float]] = defaultdict(lambda: {"tokens": capacity, "last_update": time.time()})

    def _refill(self, bucket: Dict[str, float]) -> None:
        now = time.time()
        elapsed = now - bucket["last_update"]
        bucket["tokens"] = min(self.capacity, bucket["tokens"] + elapsed * self.rate)
        bucket["last_update"] = now

    def acquire(self, key: str, tokens: float = 1.0) -> bool:
        bucket = self._buckets[key]
        self._refill(bucket)
        if bucket["tokens"] >= tokens:
            bucket["tokens"] -= tokens
            return True
        return False

    def try_acquire(self, key: str, tokens: float = 1.0) -> Dict[str, Any]:
        if self.acquire(key, tokens):
            bucket = self._buckets[key]
            return {"allowed": True, "remaining_tokens": bucket["tokens"]}
        bucket = self._buckets[key]
        return {"allowed": False, "remaining_tokens": bucket["tokens"], "retry_after": (tokens - bucket["tokens"]) / self.rate}
