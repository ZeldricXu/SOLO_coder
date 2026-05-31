from __future__ import annotations

import threading
import time
from abc import ABC, abstractmethod
from collections import defaultdict, deque
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Optional

from top.core.models import BaseModel


class RateLimitAlgorithm(str, Enum):
    TOKEN_BUCKET = "token_bucket"
    SLIDING_WINDOW = "sliding_window"
    FIXED_WINDOW = "fixed_window"


class RateLimitResult(BaseModel):
    allowed: bool
    remaining: int
    limit: int
    retry_after: float = 0.0
    reason: Optional[str] = None


class RateLimitPolicy(BaseModel):
    limit: int
    window_seconds: int
    algorithm: RateLimitAlgorithm = RateLimitAlgorithm.SLIDING_WINDOW
    burst_limit: Optional[int] = None
    refill_rate: Optional[float] = None


class RateLimiter(ABC):
    @abstractmethod
    def check(self, key: str, amount: int = 1) -> RateLimitResult:
        pass

    @abstractmethod
    def reset(self, key: str) -> None:
        pass

    @abstractmethod
    def get_stats(self, key: str) -> Dict[str, Any]:
        pass


class TokenBucketLimiter(RateLimiter):
    def __init__(
        self,
        limit: int,
        window_seconds: int,
        burst_limit: Optional[int] = None,
        refill_rate: Optional[float] = None,
    ):
        self._limit = limit
        self._window_seconds = window_seconds
        self._burst_limit = burst_limit or limit
        self._refill_rate = refill_rate or (limit / window_seconds)

        self._buckets: Dict[str, Dict[str, float]] = {}
        self._lock = threading.Lock()

    def _get_or_create_bucket(self, key: str) -> Dict[str, float]:
        if key not in self._buckets:
            self._buckets[key] = {
                "tokens": float(self._burst_limit),
                "last_refill": time.time(),
            }
        return self._buckets[key]

    def check(self, key: str, amount: int = 1) -> RateLimitResult:
        with self._lock:
            bucket = self._get_or_create_bucket(key)
            now = time.time()

            time_passed = now - bucket["last_refill"]
            new_tokens = time_passed * self._refill_rate
            bucket["tokens"] = min(self._burst_limit, bucket["tokens"] + new_tokens)
            bucket["last_refill"] = now

            if bucket["tokens"] >= amount:
                bucket["tokens"] -= amount
                return RateLimitResult(
                    allowed=True,
                    remaining=int(bucket["tokens"]),
                    limit=self._limit,
                )

            tokens_needed = amount - bucket["tokens"]
            retry_after = tokens_needed / self._refill_rate

            return RateLimitResult(
                allowed=False,
                remaining=int(bucket["tokens"]),
                limit=self._limit,
                retry_after=retry_after,
                reason="Token bucket exhausted",
            )

    def reset(self, key: str) -> None:
        with self._lock:
            if key in self._buckets:
                self._buckets[key]["tokens"] = float(self._burst_limit)
                self._buckets[key]["last_refill"] = time.time()

    def get_stats(self, key: str) -> Dict[str, Any]:
        with self._lock:
            bucket = self._buckets.get(key, {})
            return {
                "tokens": bucket.get("tokens", 0),
                "limit": self._limit,
                "burst_limit": self._burst_limit,
                "refill_rate": self._refill_rate,
                "last_refill": bucket.get("last_refill"),
            }


class SlidingWindowLimiter(RateLimiter):
    def __init__(
        self,
        limit: int,
        window_seconds: int,
    ):
        self._limit = limit
        self._window_seconds = window_seconds
        self._timestamps: Dict[str, deque] = {}
        self._lock = threading.Lock()

    def _cleanup_old_timestamps(self, key: str, now: float) -> None:
        if key not in self._timestamps:
            return

        timestamps = self._timestamps[key]
        window_start = now - self._window_seconds

        while timestamps and timestamps[0] < window_start:
            timestamps.popleft()

        if not timestamps:
            del self._timestamps[key]

    def check(self, key: str, amount: int = 1) -> RateLimitResult:
        with self._lock:
            now = time.time()
            self._cleanup_old_timestamps(key, now)

            timestamps = self._timestamps.get(key, deque())
            current_count = len(timestamps)

            if current_count + amount <= self._limit:
                for _ in range(amount):
                    timestamps.append(now)
                self._timestamps[key] = timestamps

                return RateLimitResult(
                    allowed=True,
                    remaining=self._limit - current_count - amount,
                    limit=self._limit,
                )

            if timestamps:
                retry_after = self._window_seconds - (now - timestamps[0])
            else:
                retry_after = self._window_seconds

            return RateLimitResult(
                allowed=False,
                remaining=max(0, self._limit - current_count),
                limit=self._limit,
                retry_after=max(0, retry_after),
                reason="Rate limit exceeded",
            )

    def reset(self, key: str) -> None:
        with self._lock:
            self._timestamps.pop(key, None)

    def get_stats(self, key: str) -> Dict[str, Any]:
        with self._lock:
            now = time.time()
            self._cleanup_old_timestamps(key, now)
            timestamps = self._timestamps.get(key, deque())

            return {
                "count": len(timestamps),
                "limit": self._limit,
                "window_seconds": self._window_seconds,
                "oldest": timestamps[0] if timestamps else None,
            }


class FixedWindowLimiter(RateLimiter):
    def __init__(
        self,
        limit: int,
        window_seconds: int,
    ):
        self._limit = limit
        self._window_seconds = window_seconds
        self._counters: Dict[str, Dict[int, int]] = defaultdict(lambda: defaultdict(int))
        self._lock = threading.Lock()

    def _get_window_key(self, timestamp: float) -> int:
        return int(timestamp // self._window_seconds)

    def check(self, key: str, amount: int = 1) -> RateLimitResult:
        with self._lock:
            now = time.time()
            window_key = self._get_window_key(now)

            current_count = self._counters[key][window_key]

            if current_count + amount <= self._limit:
                self._counters[key][window_key] += amount

                for old_window in list(self._counters[key].keys()):
                    if old_window < window_key - 2:
                        del self._counters[key][old_window]

                return RateLimitResult(
                    allowed=True,
                    remaining=self._limit - current_count - amount,
                    limit=self._limit,
                )

            next_window_start = (window_key + 1) * self._window_seconds
            retry_after = next_window_start - now

            return RateLimitResult(
                allowed=False,
                remaining=max(0, self._limit - current_count),
                limit=self._limit,
                retry_after=max(0, retry_after),
                reason="Rate limit exceeded",
            )

    def reset(self, key: str) -> None:
        with self._lock:
            self._counters[key].clear()

    def get_stats(self, key: str) -> Dict[str, Any]:
        with self._lock:
            now = time.time()
            window_key = self._get_window_key(now)

            return {
                "count": self._counters[key][window_key],
                "limit": self._limit,
                "window_seconds": self._window_seconds,
                "current_window": window_key,
            }


@dataclass
class PolicyRule:
    resource_pattern: str
    policy: RateLimitPolicy
    priority: int = 0


class RateLimitRouter:
    def __init__(self):
        self._global_limiter: Optional[RateLimiter] = None
        self._limiters: Dict[str, RateLimiter] = {}
        self._policy_rules: list[PolicyRule] = []
        self._lock = threading.Lock()

    def configure_global_limit(self, policy: RateLimitPolicy) -> None:
        self._global_limiter = self._create_limiter(policy)

    def configure_resource_limit(
        self,
        resource: str,
        policy: RateLimitPolicy,
        priority: int = 0,
    ) -> None:
        with self._lock:
            rule = PolicyRule(
                resource_pattern=resource,
                policy=policy,
                priority=priority,
            )
            self._policy_rules.append(rule)
            self._policy_rules.sort(key=lambda r: (r.priority, len(r.resource_pattern)), reverse=True)

    def _create_limiter(self, policy: RateLimitPolicy) -> RateLimiter:
        if policy.algorithm == RateLimitAlgorithm.TOKEN_BUCKET:
            return TokenBucketLimiter(
                limit=policy.limit,
                window_seconds=policy.window_seconds,
                burst_limit=policy.burst_limit,
                refill_rate=policy.refill_rate,
            )
        elif policy.algorithm == RateLimitAlgorithm.FIXED_WINDOW:
            return FixedWindowLimiter(
                limit=policy.limit,
                window_seconds=policy.window_seconds,
            )
        else:
            return SlidingWindowLimiter(
                limit=policy.limit,
                window_seconds=policy.window_seconds,
            )

    def _get_limiter_for_resource(self, resource: str) -> Optional[RateLimiter]:
        import fnmatch

        for rule in self._policy_rules:
            if fnmatch.fnmatch(resource, rule.resource_pattern):
                limiter_key = f"{rule.resource_pattern}:{rule.policy.limit}:{rule.policy.window_seconds}"
                if limiter_key not in self._limiters:
                    self._limiters[limiter_key] = self._create_limiter(rule.policy)
                return self._limiters[limiter_key]

        return self._global_limiter

    def check(self, key: str, resource: str = "*", amount: int = 1) -> RateLimitResult:
        limiter = self._get_limiter_for_resource(resource)
        if limiter is None:
            return RateLimitResult(
                allowed=True,
                remaining=-1,
                limit=-1,
            )

        combined_key = f"{key}:{resource}"
        return limiter.check(combined_key, amount)

    def reset(self, key: str, resource: str = "*") -> None:
        limiter = self._get_limiter_for_resource(resource)
        if limiter:
            combined_key = f"{key}:{resource}"
            limiter.reset(combined_key)

    def get_stats(self, key: str, resource: str = "*") -> Dict[str, Any]:
        limiter = self._get_limiter_for_resource(resource)
        if limiter:
            combined_key = f"{key}:{resource}"
            return limiter.get_stats(combined_key)
        return {}


_rate_limiter_instance: Optional[RateLimitRouter] = None


def get_rate_limiter() -> RateLimitRouter:
    global _rate_limiter_instance

    if _rate_limiter_instance is None:
        _rate_limiter_instance = RateLimitRouter()

    return _rate_limiter_instance
