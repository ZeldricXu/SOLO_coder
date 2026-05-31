import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from uuid import uuid4

from ..core.events import DomainEvent, EventBus, get_global_event_bus


@dataclass
class Span:
    trace_id: str
    span_id: str
    name: str
    service_name: str
    start_time: datetime
    end_time: Optional[datetime] = None
    parent_span_id: Optional[str] = None
    status: str = "OK"
    status_message: Optional[str] = None
    attributes: Dict[str, Any] = field(default_factory=dict)
    events: List[Dict[str, Any]] = field(default_factory=list)
    links: List[Dict[str, Any]] = field(default_factory=list)
    kind: str = "INTERNAL"

    @property
    def duration_ms(self) -> float:
        if not self.end_time:
            return 0.0
        return (self.end_time - self.start_time).total_seconds() * 1000

    def to_dict(self) -> Dict[str, Any]:
        return {
            "trace_id": self.trace_id,
            "span_id": self.span_id,
            "name": self.name,
            "service_name": self.service_name,
            "start_time": self.start_time.isoformat(),
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "parent_span_id": self.parent_span_id,
            "status": self.status,
            "status_message": self.status_message,
            "attributes": self.attributes,
            "events": self.events,
            "links": self.links,
            "kind": self.kind,
            "duration_ms": self.duration_ms,
        }

    def add_event(self, name: str, timestamp: Optional[datetime] = None, **attributes) -> None:
        self.events.append({
            "name": name,
            "timestamp": (timestamp or datetime.now(timezone.utc)).isoformat(),
            "attributes": attributes,
        })

    def set_attribute(self, key: str, value: Any) -> None:
        self.attributes[key] = value

    def set_status(self, status: str, message: Optional[str] = None) -> None:
        self.status = status
        self.status_message = message

    def end(self, status: Optional[str] = None, message: Optional[str] = None) -> None:
        self.end_time = datetime.now(timezone.utc)
        if status:
            self.set_status(status, message)


@dataclass
class Trace:
    trace_id: str
    spans: List[Span] = field(default_factory=list)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def add_span(self, span: Span) -> None:
        self.spans.append(span)

    def get_root_span(self) -> Optional[Span]:
        for span in self.spans:
            if span.parent_span_id is None:
                return span
        return self.spans[0] if self.spans else None

    def get_span_by_id(self, span_id: str) -> Optional[Span]:
        for span in self.spans:
            if span.span_id == span_id:
                return span
        return None

    def get_duration_ms(self) -> float:
        if not self.spans:
            return 0.0
        starts = [s.start_time for s in self.spans if s.start_time]
        ends = [s.end_time for s in self.spans if s.end_time]
        if not ends:
            return 0.0
        return (max(ends) - min(starts)).total_seconds() * 1000

    def get_error_count(self) -> int:
        return sum(1 for s in self.spans if s.status == "ERROR")

    def to_dict(self) -> Dict[str, Any]:
        return {
            "trace_id": self.trace_id,
            "created_at": self.created_at.isoformat(),
            "span_count": len(self.spans),
            "spans": [s.to_dict() for s in self.spans],
            "duration_ms": self.get_duration_ms(),
            "error_count": self.get_error_count(),
        }


class TraceCollector:
    def __init__(
        self,
        service_name: str = "platform-engineer",
        event_bus: Optional[EventBus] = None,
        logger=None,
        max_spans_per_trace: int = 10000,
        max_traces: int = 10000,
    ):
        self._service_name = service_name
        self._event_bus = event_bus or get_global_event_bus()
        self._logger = logger
        self._traces: Dict[str, Trace] = {}
        self._active_spans: Dict[str, Span] = {}
        self._max_spans_per_trace = max_spans_per_trace
        self._max_traces = max_traces
        self._samplers: List[Any] = []
        self._metrics = {
            "spans_received": 0,
            "spans_dropped": 0,
            "traces_created": 0,
            "spans_sampled": 0,
        }

    def register_sampler(self, sampler: Any) -> None:
        self._samplers.append(sampler)

    def should_sample(self, span: Span) -> bool:
        if not self._samplers:
            return True
        for sampler in self._samplers:
            if hasattr(sampler, "should_sample"):
                decision = sampler.should_sample(span)
                if hasattr(decision, "sampled") and not decision.sampled:
                    return False
        return True

    def start_span(
        self,
        name: str,
        trace_id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        service_name: Optional[str] = None,
        **attributes,
    ) -> Span:
        if trace_id is None:
            trace_id = self._generate_id()
        span_id = self._generate_id()
        span = Span(
            trace_id=trace_id,
            span_id=span_id,
            name=name,
            service_name=service_name or self._service_name,
            start_time=datetime.now(timezone.utc),
            parent_span_id=parent_span_id,
            attributes=attributes,
        )
        self._active_spans[span_id] = span
        if trace_id not in self._traces:
            self._traces[trace_id] = Trace(trace_id=trace_id)
            self._metrics["traces_created"] += 1
        trace = self._traces[trace_id]
        if len(trace.spans) < self._max_spans_per_trace:
            trace.add_span(span)
        else:
            self._metrics["spans_dropped"] += 1
        self._metrics["spans_received"] += 1
        if self._logger and self._logger.isEnabledFor(10):
            self._logger.debug(f"Started span: {span.name} [{span.span_id}]")
        return span

    def end_span(self, span: Span, status: str = "OK", message: Optional[str] = None) -> Optional[Trace]:
        span.end(status, message)
        if span.span_id in self._active_spans:
            del self._active_spans[span.span_id]
        if self.should_sample(span):
            self._metrics["spans_sampled"] += 1
            event = DomainEvent(
                event_type="span.completed",
                payload=span.to_dict(),
                source="trace_collector",
            )
            asyncio.create_task(self._event_bus.publish(event))
        if span.trace_id in self._traces:
            return self._traces[span.trace_id]
        return None

    def record_span(
        self,
        name: str,
        trace_id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        service_name: Optional[str] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        status: str = "OK",
        **attributes,
    ) -> Span:
        span_id = self._generate_id()
        if trace_id is None:
            trace_id = self._generate_id()
        span = Span(
            trace_id=trace_id,
            span_id=span_id,
            name=name,
            service_name=service_name or self._service_name,
            start_time=start_time or datetime.now(timezone.utc),
            end_time=end_time or datetime.now(timezone.utc),
            parent_span_id=parent_span_id,
            status=status,
            attributes=attributes,
        )
        if trace_id not in self._traces:
            self._traces[trace_id] = Trace(trace_id=trace_id)
            self._metrics["traces_created"] += 1
        trace = self._traces[trace_id]
        if len(trace.spans) < self._max_spans_per_trace:
            trace.add_span(span)
        else:
            self._metrics["spans_dropped"] += 1
        self._metrics["spans_received"] += 1
        if self.should_sample(span):
            self._metrics["spans_sampled"] += 1
        return span

    def get_trace(self, trace_id: str) -> Optional[Trace]:
        return self._traces.get(trace_id)

    def list_traces(self, limit: int = 100) -> List[Trace]:
        traces = list(self._traces.values())
        return sorted(traces, key=lambda t: t.created_at, reverse=True)[:limit]

    def get_active_span_count(self) -> int:
        return len(self._active_spans)

    def get_metrics(self) -> Dict[str, int]:
        return dict(self._metrics)

    def _generate_id(self) -> str:
        return uuid4().hex[:16]

    def _cleanup_old_traces(self) -> None:
        if len(self._traces) > self._max_traces:
            sorted_traces = sorted(self._traces.values(), key=lambda t: t.created_at)
            remove_count = len(self._traces) - self._max_traces + int(self._max_traces * 0.1)
            for trace in sorted_traces[:remove_count]:
                del self._traces[trace.trace_id]


_global_collector: Optional[TraceCollector] = None


def get_global_collector() -> TraceCollector:
    global _global_collector
    if _global_collector is None:
        _global_collector = TraceCollector()
    return _global_collector


def set_global_collector(collector: TraceCollector) -> None:
    global _global_collector
    _global_collector = collector
