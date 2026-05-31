"""
链路追踪组件 - 独立于网关核心
TraceManager: 创建/管理span，构建trace树
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from src.domain.contracts.tracing import TraceContext, Request
from src.domain.models.tracing import TraceSpan


@dataclass
class SimpleTraceContext(TraceContext):
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    span_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    parent_span_id: Optional[str] = None
    service_name: str = "gateway"
    tags: Dict[str, Any] = field(default_factory=dict)


class TraceManager:
    def __init__(self, service_name: str = "gateway") -> None:
        self.service_name = service_name
        self._spans: Dict[str, TraceSpan] = {}
        self._traces: Dict[str, List[TraceSpan]] = {}

    def create_context(self, request: Request) -> SimpleTraceContext:
        trace_id = request.headers.get("X-Trace-Id", str(uuid.uuid4()))
        parent_span_id = request.headers.get("X-Parent-Span-Id")
        return SimpleTraceContext(
            trace_id=trace_id,
            span_id=str(uuid.uuid4()),
            parent_span_id=parent_span_id,
            service_name=self.service_name,
            tags={
                "http.method": request.method,
                "http.path": request.path,
                "request.id": request.request_id,
            },
        )

    def start_span(self, trace_ctx: TraceContext, operation_name: str) -> TraceSpan:
        span = TraceSpan(
            span_id=str(uuid.uuid4()),
            parent_span_id=trace_ctx.span_id,
            service_name=trace_ctx.service_name,
            operation_name=operation_name,
            tags={**trace_ctx.tags},
        )
        self._spans[span.span_id] = span
        if trace_ctx.trace_id not in self._traces:
            self._traces[trace_ctx.trace_id] = []
        self._traces[trace_ctx.trace_id].append(span)
        return span

    def finish_span(self, span: TraceSpan, status: str = "success") -> None:
        span.finish(status)

    def get_trace_spans(self, trace_id: str) -> List[TraceSpan]:
        return self._traces.get(trace_id, [])

    def get_trace_tree(self, trace_id: str) -> Dict[str, Any]:
        spans = self.get_trace_spans(trace_id)
        if not spans:
            return {}

        span_map = {s.span_id: s for s in spans}
        children: Dict[str, List[TraceSpan]] = {}
        roots: List[TraceSpan] = []

        for span in spans:
            if span.parent_span_id and span.parent_span_id in span_map:
                if span.parent_span_id not in children:
                    children[span.parent_span_id] = []
                children[span.parent_span_id].append(span)
            else:
                roots.append(span)

        def build_tree(span: TraceSpan) -> Dict[str, Any]:
            return {
                "span_id": span.span_id,
                "operation": span.operation_name,
                "duration_ms": round(span.duration() * 1000, 2),
                "status": span.status,
                "tags": span.tags,
                "children": [build_tree(c) for c in children.get(span.span_id, [])],
            }

        return {
            "trace_id": trace_id,
            "spans": [build_tree(root) for root in roots],
        }
