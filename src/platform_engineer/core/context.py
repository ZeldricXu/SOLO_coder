from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Any, Dict, Optional
from uuid import uuid4


class Context:
    def __init__(self, trace_id: Optional[str] = None, logger=None):
        self.trace_id = trace_id or uuid4().hex[:16]
        self.data: Dict[str, Any] = {}
        self.metrics: Dict[str, Any] = {}
        self._started_at = datetime.now(timezone.utc)
        self._cleanup_calls = []
        self._logger = logger

    def set(self, key: str, value: Any) -> None:
        self.data[key] = value

    def get(self, key: str, default: Any = None) -> Any:
        return self.data.get(key, default)

    def record_metric(self, name: str, value: float) -> None:
        self.metrics[name] = value

    def get_metrics(self) -> Dict[str, Any]:
        duration = (datetime.now(timezone.utc) - self._started_at).total_seconds()
        return {**self.metrics, "duration_seconds": duration}

    def on_cleanup(self, callback) -> None:
        self._cleanup_calls.append(callback)

    def cleanup(self) -> None:
        for callback in reversed(self._cleanup_calls):
            try:
                callback()
            except Exception:
                if self._logger:
                    self._logger.exception("Cleanup callback failed")
        self._cleanup_calls.clear()

    def log(self, level: str, message: str, **kwargs) -> None:
        if self._logger:
            log_method = getattr(self._logger, level.lower(), self._logger.info)
            log_method(f"[{self.trace_id}] {message}", **kwargs)


@contextmanager
def create_context(trace_id: Optional[str] = None, logger=None):
    ctx = Context(trace_id, logger)
    try:
        yield ctx
    finally:
        ctx.cleanup()
