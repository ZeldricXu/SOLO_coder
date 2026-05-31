from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
import random
import time

from .collector import Span


@dataclass
class SamplingDecision:
    sampled: bool
    reason: str = "default"
    attributes: Dict[str, Any] = None

    def __post_init__(self):
        if self.attributes is None:
            self.attributes = {}


class SamplingStrategy(ABC):
    @abstractmethod
    def should_sample(self, span: Span) -> SamplingDecision:
        pass


class HeadBasedSampler(SamplingStrategy):
    def __init__(self, sample_rate: float = 1.0):
        self._sample_rate = max(0.0, min(1.0, sample_rate))

    def should_sample(self, span: Span) -> SamplingDecision:
        if span.parent_span_id is not None:
            return SamplingDecision(sampled=True, reason="parent_based")
        if random.random() < self._sample_rate:
            return SamplingDecision(sampled=True, reason="head_based")
        return SamplingDecision(sampled=False, reason="head_based_not_sampled")


class ProbabilisticSampler(SamplingStrategy):
    def __init__(
        self,
        default_rate: float = 0.1,
        min_rate: float = 0.001,
        max_rate: float = 1.0,
    ):
        self._default_rate = max(0.0, min(1.0, default_rate))
        self._min_rate = max(0.0, min(1.0, min_rate))
        self._max_rate = max(0.0, min(1.0, max_rate))
        self._service_rates: Dict[str, float] = {}

    def set_service_rate(self, service_name: str, rate: float) -> None:
        self._service_rates[service_name] = max(self._min_rate, min(self._max_rate, rate))

    def should_sample(self, span: Span) -> SamplingDecision:
        rate = self._service_rates.get(span.service_name, self._default_rate)
        if random.random() < rate:
            return SamplingDecision(sampled=True, reason="probabilistic", attributes={"rate": rate})
        return SamplingDecision(sampled=False, reason="probabilistic_not_sampled", attributes={"rate": rate})


class RateLimitingSampler(SamplingStrategy):
    def __init__(self, max_spans_per_second: int = 100, window_seconds: float = 1.0):
        self._max_spans = max_spans_per_second
        self._window = window_seconds
        self._tokens = max_spans_per_second
        self._last_refill = time.time()
        self._lock = None

    def _refill(self) -> None:
        now = time.time()
        elapsed = now - self._last_refill
        if elapsed >= self._window:
            self._tokens = self._max_spans
            self._last_refill = now
        else:
            self._tokens = min(self._max_spans, int(self._tokens + (elapsed / self._window) * self._max_spans))
            self._last_refill = now

    def should_sample(self, span: Span) -> SamplingDecision:
        self._refill()
        if self._tokens > 0:
            self._tokens -= 1
            return SamplingDecision(sampled=True, reason="rate_limited", attributes={"remaining_tokens": self._tokens})
        return SamplingDecision(sampled=False, reason="rate_limit_exceeded", attributes={"max_rate": self._max_spans})


class TailSampler(SamplingStrategy):
    def __init__(
        self,
        error_rate_threshold: float = 1.0,
        duration_threshold_ms: float = 5000.0,
        max_traces: int = 1000,
    ):
        self._error_rate_threshold = error_rate_threshold
        self._duration_threshold = duration_threshold_ms
        self._candidates: Dict[str, List[Span]] = {}
        self._decisions: Dict[str, bool] = {}

    def should_sample(self, span: Span) -> SamplingDecision:
        trace_id = span.trace_id
        if trace_id in self._decisions:
            return SamplingDecision(sampled=self._decisions[trace_id], reason="tail_decision")
        if trace_id not in self._candidates:
            self._candidates[trace_id] = []
        self._candidates[trace_id].append(span)
        if span.status == "ERROR":
            self._decisions[trace_id] = True
            return SamplingDecision(sampled=True, reason="error")
        if span.duration_ms > self._duration_threshold:
            self._decisions[trace_id] = True
            return SamplingDecision(sampled=True, reason="slow_span", attributes={"duration_ms": span.duration_ms})
        if len(self._candidates) > self._max_traces * 2:
            oldest = list(self._candidates.keys())
            for tid in oldest[:int(self._max_traces)]:
                del self._candidates[tid]
        return SamplingDecision(sampled=True, reason="candidate")

    def decide_trace(self, trace_id: str, spans: List[Span]) -> bool:
        if trace_id in self._decisions:
            return self._decisions[trace_id]
        has_error = any(s.status == "ERROR" for s in spans)
        total_duration = max(s.end_time for s in spans if s.end_time) - min(s.start_time for s in spans)
        total_duration_ms = total_duration.total_seconds() * 1000
        should_keep = has_error or total_duration_ms > self._duration_threshold
        self._decisions[trace_id] = should_keep
        if trace_id in self._candidates:
            del self._candidates[trace_id]
        return should_keep
