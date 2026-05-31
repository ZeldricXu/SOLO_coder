from contextvars import ContextVar
from typing import Any, Dict, Optional
from datetime import datetime
import uuid
import logging

logger = logging.getLogger(__name__)

_trace_id: ContextVar[Optional[str]] = ContextVar("trace_id", default=None)
_request_context: ContextVar[Optional[Dict[str, Any]]] = ContextVar("request_context", default=None)


def get_trace_id() -> str:
    trace_id = _trace_id.get()
    if trace_id is None:
        trace_id = str(uuid.uuid4())
        _trace_id.set(trace_id)
    return trace_id


def set_trace_id(trace_id: str) -> None:
    _trace_id.set(trace_id)


class RequestContext:
    def __init__(self, trace_id: Optional[str] = None, **kwargs):
        self.trace_id = trace_id or get_trace_id()
        self.start_time = datetime.utcnow()
        self.extra: Dict[str, Any] = kwargs
        self.metrics: Dict[str, Any] = {}
        self._token = None

    def __enter__(self):
        self._token = _request_context.set({
            "trace_id": self.trace_id,
            "start_time": self.start_time,
            **self.extra
        })
        _trace_id.set(self.trace_id)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self._token is not None:
            _request_context.reset(self._token)
        duration = (datetime.utcnow() - self.start_time).total_seconds()
        self.metrics["duration"] = duration
        if exc_type is not None:
            self.metrics["error"] = str(exc_val)
        logger.info(f"Request completed: trace_id={self.trace_id}, duration={duration:.3f}s")

    def set_metric(self, key: str, value: Any) -> None:
        self.metrics[key] = value

    def get(self, key: str, default: Any = None) -> Any:
        return self.extra.get(key, default)


def init_context(trace_id: Optional[str] = None, **kwargs) -> RequestContext:
    return RequestContext(trace_id=trace_id, **kwargs)
