from datetime import datetime
from typing import Any, Dict, Optional
from contextvars import ContextVar
import uuid
from dataclasses import dataclass, field


_request_context_var: ContextVar[Optional["RequestContext"]] = ContextVar("request_context", default=None)


@dataclass
class RequestContext:
    trace_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    start_time: datetime = field(default_factory=datetime.utcnow)
    metrics: Dict[str, Any] = field(default_factory=dict)
    errors: list = field(default_factory=list)
    data: Dict[str, Any] = field(default_factory=dict)

    def record_metric(self, key: str, value: Any):
        self.metrics[key] = value

    def add_error(self, error: Exception):
        self.errors.append(str(error))

    def set(self, key: str, value: Any):
        self.data[key] = value

    def get(self, key: str, default: Any = None) -> Any:
        return self.data.get(key, default)

    def cleanup(self):
        self.data.clear()
        self.errors.clear()


def init_context(trace_id: Optional[str] = None) -> RequestContext:
    ctx = RequestContext(trace_id=trace_id) if trace_id else RequestContext()
    _request_context_var.set(ctx)
    return ctx


def get_current_context() -> Optional[RequestContext]:
    return _request_context_var.get()


def cleanup_context():
    ctx = _request_context_var.get()
    if ctx:
        ctx.cleanup()
    _request_context_var.set(None)
