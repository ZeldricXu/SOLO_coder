"""Distributed tracing implementation for API Gateway."""
from __future__ import annotations

import time
from contextvars import ContextVar
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import UUID, uuid4

from ...infrastructure.config.settings import Settings
from ...infrastructure.logging.structured_logger import LogManager


class SpanKind(str, Enum):
    SERVER = "server"
    CLIENT = "client"
    PRODUCER = "producer"
    CONSUMER = "consumer"
    INTERNAL = "internal"


class SpanStatus(str, Enum):
    UNSET = "unset"
    OK = "ok"
    ERROR = "error"


@dataclass
class Span:
    trace_id: str
    span_id: str
    parent_span_id: Optional[str]
    name: str
    kind: SpanKind = SpanKind.INTERNAL
    start_time: float = field(default_factory=time.time)
    end_time: Optional[float] = None
    status: SpanStatus = SpanStatus.UNSET
    attributes: Dict[str, Any] = field(default_factory=dict)
    events: List[Dict[str, Any]] = field(default_factory=list)
    links: List[Dict[str, Any]] = field(default_factory=list)

    def add_attribute(self, key: str, value: Any) -> None:
        self.attributes[key] = value

    def add_event(self, name: str, attributes: Optional[Dict[str, Any]] = None) -> None:
        self.events.append({
            "timestamp": time.time(),
            "name": name,
            "attributes": attributes or {},
        })

    def add_link(self, trace_id: str, span_id: str, attributes: Optional[Dict[str, Any]] = None) -> None:
        self.links.append({
            "trace_id": trace_id,
            "span_id": span_id,
            "attributes": attributes or {},
        })

    def set_status(self, status: SpanStatus, description: Optional[str] = None) -> None:
        self.status = status
        if description:
            self.attributes["status_description"] = description

    def end(self) -> None:
        self.end_time = time.time()

    def duration_ms(self) -> float:
        end = self.end_time or time.time()
        return (end - self.start_time) * 1000

    def to_dict(self) -> Dict[str, Any]:
        return {
            "trace_id": self.trace_id,
            "span_id": self.span_id,
            "parent_span_id": self.parent_span_id,
            "name": self.name,
            "kind": self.kind.value,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "duration_ms": self.duration_ms(),
            "status": self.status.value,
            "attributes": self.attributes,
            "events": self.events,
            "links": self.links,
        }


_current_span: ContextVar[Optional[Span]] = ContextVar("current_span", default=None)


class TracingManager:
    def __init__(self, service_name: str = "file-storage-service", enabled: bool = True) -> None:
        self._service_name = service_name
        self._enabled = enabled
        self._logger = LogManager().get_logger(__name__)
        self._spans: Dict[str, List[Span]] = {}
        self._sampling_rate = 1.0

    def generate_trace_id(self) -> str:
        return uuid4().hex

    def generate_span_id(self) -> str:
        return uuid4().hex[:16]

    def start_span(
        self,
        name: str,
        kind: SpanKind = SpanKind.INTERNAL,
        trace_id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        attributes: Optional[Dict[str, Any]] = None,
    ) -> Span:
        if not self._enabled:
            trace_id = trace_id or self.generate_trace_id()
            span_id = self.generate_span_id()
            span = Span(
                trace_id=trace_id,
                span_id=span_id,
                parent_span_id=parent_span_id,
                name=name,
                kind=kind,
                attributes=attributes or {},
            )
            return span

        trace_id = trace_id or self.generate_trace_id()
        span_id = self.generate_span_id()

        span = Span(
            trace_id=trace_id,
            span_id=span_id,
            parent_span_id=parent_span_id,
            name=name,
            kind=kind,
            attributes={
                "service.name": self._service_name,
                **(attributes or {}),
            },
        )

        if trace_id not in self._spans:
            self._spans[trace_id] = []
        self._spans[trace_id].append(span)

        _current_span.set(span)

        self._logger.debug(
            f"Span started: {name}",
            trace_id=trace_id,
            span_id=span_id,
            parent_span_id=parent_span_id,
        )

        return span

    def end_span(self, span: Span, status: SpanStatus = SpanStatus.OK, description: Optional[str] = None) -> None:
        if not self._enabled:
            span.end()
            return

        span.set_status(status, description)
        span.end()

        self._logger.debug(
            f"Span ended: {span.name}",
            trace_id=span.trace_id,
            span_id=span.span_id,
            duration_ms=span.duration_ms(),
            status=status.value,
        )

        current = _current_span.get()
        if current and current.span_id == span.span_id:
            _current_span.set(None)

    def get_current_span(self) -> Optional[Span]:
        return _current_span.get()

    def set_current_span(self, span: Optional[Span]) -> None:
        _current_span.set(span)

    def extract_trace_context(self, headers: Dict[str, str]) -> Dict[str, Optional[str]]:
        traceparent = headers.get("traceparent")
        if not traceparent:
            return {"trace_id": None, "span_id": None}

        try:
            parts = traceparent.split("-")
            if len(parts) >= 3:
                return {
                    "trace_id": parts[1],
                    "span_id": parts[2],
                }
        except Exception:
            pass

        return {"trace_id": None, "span_id": None}

    def inject_trace_context(self, span: Span, headers: Dict[str, str]) -> Dict[str, str]:
        headers["traceparent"] = f"00-{span.trace_id}-{span.span_id}-01"
        headers["tracestate"] = f"service={self._service_name}"
        return headers

    def get_trace_spans(self, trace_id: str) -> List[Span]:
        return self._spans.get(trace_id, [])

    def clear_trace(self, trace_id: str) -> None:
        self._spans.pop(trace_id, None)

    def get_trace_tree(self, trace_id: str) -> Dict[str, Any]:
        spans = self.get_trace_spans(trace_id)
        if not spans:
            return {}

        span_map = {span.span_id: span.to_dict() for span in spans}
        root_span = None

        for span in spans:
            if span.parent_span_id is None:
                root_span = span_map[span.span_id]
                break

        if root_span is None:
            root_span = list(span_map.values())[0]

        def build_tree(span_dict: Dict[str, Any]) -> Dict[str, Any]:
            children = [
                child for child in span_map.values()
                if child.get("parent_span_id") == span_dict["span_id"]
            ]
            for child in children:
                build_tree(child)
            if children:
                span_dict["children"] = children
            return span_dict

        return build_tree(root_span)

    async def export_traces(self) -> List[Dict[str, Any]]:
        all_spans = []
        for spans in self._spans.values():
            for span in spans:
                if span.end_time is not None:
                    all_spans.append(span.to_dict())
        return all_spans


class Tracer:
    def __init__(self, tracing_manager: TracingManager, name: str = "") -> None:
        self._manager = tracing_manager
        self._name = name

    def start_span(
        self,
        name: str,
        kind: SpanKind = SpanKind.INTERNAL,
        attributes: Optional[Dict[str, Any]] = None,
    ) -> Span:
        current = self._manager.get_current_span()
        return self._manager.start_span(
            name=name,
            kind=kind,
            trace_id=current.trace_id if current else None,
            parent_span_id=current.span_id if current else None,
            attributes=attributes,
        )

    def end_span(self, span: Span, status: SpanStatus = SpanStatus.OK) -> None:
        self._manager.end_span(span, status)

    def get_current_span(self) -> Optional[Span]:
        return self._manager.get_current_span()

    def add_event(self, name: str, attributes: Optional[Dict[str, Any]] = None) -> None:
        current = self._manager.get_current_span()
        if current:
            current.add_event(name, attributes)

    def add_attribute(self, key: str, value: Any) -> None:
        current = self._manager.get_current_span()
        if current:
            current.add_attribute(key, value)
