import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional


class SpanStatus(str, Enum):
    OK = "OK"
    ERROR = "ERROR"
    CANCELLED = "CANCELLED"


@dataclass
class Span:
    trace_id: str
    span_id: str
    parent_span_id: Optional[str]
    name: str
    status: SpanStatus = SpanStatus.OK
    start_time: float = field(default_factory=time.perf_counter)
    end_time: Optional[float] = None
    attributes: Dict[str, Any] = field(default_factory=dict)
    events: List[Dict[str, Any]] = field(default_factory=list)
    error: Optional[str] = None

    @property
    def duration_ms(self) -> Optional[float]:
        if self.end_time is not None:
            return (self.end_time - self.start_time) * 1000
        return None

    def add_event(self, name: str, attributes: Optional[Dict[str, Any]] = None) -> None:
        self.events.append({
            "name": name,
            "timestamp": time.perf_counter(),
            "attributes": attributes or {}
        })

    def set_attribute(self, key: str, value: Any) -> None:
        self.attributes[key] = value

    def end(self, status: SpanStatus = SpanStatus.OK, error: Optional[str] = None) -> None:
        self.end_time = time.perf_counter()
        self.status = status
        self.error = error


@dataclass
class TraceContext:
    trace_id: str
    spans: Dict[str, Span] = field(default_factory=dict)
    active_span: Optional[str] = None
    created_at: datetime = field(default_factory=datetime.utcnow)

    def get_active_span(self) -> Optional[Span]:
        if self.active_span:
            return self.spans.get(self.active_span)
        return None


class Tracer:
    def __init__(self):
        self._contexts: Dict[str, TraceContext] = {}
        self._local = threading.local()
        self._lock = threading.Lock()
        self._max_spans_per_trace = 1000
        self._export_listeners: List = []

    @staticmethod
    def _generate_id() -> str:
        return uuid.uuid4().hex

    def create_trace(self) -> TraceContext:
        trace_id = self._generate_id()
        context = TraceContext(trace_id=trace_id)
        with self._lock:
            self._contexts[trace_id] = context
        return context

    def get_or_create_trace(self, trace_id: Optional[str] = None) -> TraceContext:
        if trace_id:
            with self._lock:
                if trace_id in self._contexts:
                    return self._contexts[trace_id]
        return self.create_trace()

    def start_span(
        self,
        name: str,
        trace_id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        attributes: Optional[Dict[str, Any]] = None
    ) -> Span:
        context = self.get_or_create_trace(trace_id)
        parent_id = parent_span_id or context.active_span

        span = Span(
            trace_id=context.trace_id,
            span_id=self._generate_id(),
            parent_span_id=parent_id,
            name=name,
            attributes=attributes or {}
        )

        with self._lock:
            if len(context.spans) < self._max_spans_per_trace:
                context.spans[span.span_id] = span
            context.active_span = span.span_id
            self._local.current_trace_id = context.trace_id
            self._local.current_span_id = span.span_id

        return span

    def end_span(
        self,
        span: Optional[Span] = None,
        status: SpanStatus = SpanStatus.OK,
        error: Optional[str] = None
    ) -> Optional[Span]:
        if span is None:
            current_span_id = getattr(self._local, "current_span_id", None)
            current_trace_id = getattr(self._local, "current_trace_id", None)
            if current_trace_id and current_span_id:
                with self._lock:
                    context = self._contexts.get(current_trace_id)
                    if context:
                        span = context.spans.get(current_span_id)
                        if span:
                            parent = context.spans.get(span.parent_span_id) if span.parent_span_id else None
                            context.active_span = span.parent_span_id
                            if parent:
                                self._local.current_span_id = parent.span_id
                            else:
                                self._local.current_span_id = None

        if span:
            span.end(status, error)
        return span

    @contextmanager
    def span(
        self,
        name: str,
        trace_id: Optional[str] = None,
        attributes: Optional[Dict[str, Any]] = None
    ):
        span = self.start_span(name, trace_id=trace_id, attributes=attributes)
        try:
            yield span
            self.end_span(span, SpanStatus.OK)
        except Exception as e:
            self.end_span(span, SpanStatus.ERROR, str(e))
            raise

    def get_current_trace_id(self) -> Optional[str]:
        return getattr(self._local, "current_trace_id", None)

    def get_current_span(self) -> Optional[Span]:
        span_id = getattr(self._local, "current_span_id", None)
        trace_id = getattr(self._local, "current_trace_id", None)
        if trace_id and span_id:
            with self._lock:
                context = self._contexts.get(trace_id)
                if context:
                    return context.spans.get(span_id)
        return None

    def get_trace(self, trace_id: str) -> Optional[TraceContext]:
        with self._lock:
            return self._contexts.get(trace_id)

    def get_all_traces(self) -> List[TraceContext]:
        with self._lock:
            return list(self._contexts.values())

    def export_trace(self, trace_id: str) -> Optional[Dict[str, Any]]:
        context = self.get_trace(trace_id)
        if not context:
            return None
        return {
            "trace_id": context.trace_id,
            "created_at": context.created_at.isoformat(),
            "spans": [
                {
                    "span_id": s.span_id,
                    "parent_span_id": s.parent_span_id,
                    "name": s.name,
                    "status": s.status,
                    "start_time": s.start_time,
                    "end_time": s.end_time,
                    "duration_ms": s.duration_ms,
                    "attributes": s.attributes,
                    "events": s.events,
                    "error": s.error
                }
                for s in context.spans.values()
            ]
        }

    def cleanup_old_traces(self, max_age_seconds: int = 3600) -> int:
        cutoff = datetime.utcnow().timestamp() - max_age_seconds
        removed = 0
        with self._lock:
            to_remove = [
                tid for tid, ctx in self._contexts.items()
                if ctx.created_at.timestamp() < cutoff
            ]
            for tid in to_remove:
                del self._contexts[tid]
                removed += 1
        return removed


_tracer_instance: Optional[Tracer] = None
_tracer_lock = threading.Lock()


def get_tracer() -> Tracer:
    global _tracer_instance
    if _tracer_instance is None:
        with _tracer_lock:
            if _tracer_instance is None:
                _tracer_instance = Tracer()
    return _tracer_instance


def start_span(
    name: str,
    trace_id: Optional[str] = None,
    attributes: Optional[Dict[str, Any]] = None
) -> Span:
    return get_tracer().start_span(name, trace_id, attributes=attributes)


def end_span(
    span: Optional[Span] = None,
    status: SpanStatus = SpanStatus.OK,
    error: Optional[str] = None
) -> Optional[Span]:
    return get_tracer().end_span(span, status, error)


def get_current_trace_id() -> Optional[str]:
    return get_tracer().get_current_trace_id()
