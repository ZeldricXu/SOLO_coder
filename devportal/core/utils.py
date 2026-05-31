import json
import re
import hashlib
import asyncio
from datetime import datetime, timezone, timedelta
from typing import Any, Callable, Dict, List, Optional, Tuple
from uuid import uuid4
import logging
from contextvars import ContextVar
from contextlib import asynccontextmanager

logger = logging.getLogger(__name__)

trace_id_var: ContextVar[str] = ContextVar("trace_id", default="")


def generate_id(prefix: str = "id") -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def utc_now_iso() -> str:
    return utc_now().isoformat().replace("+00:00", "Z")


def parse_iso_datetime(dt_str: str) -> datetime:
    if dt_str.endswith("Z"):
        dt_str = dt_str[:-1] + "+00:00"
    return datetime.fromisoformat(dt_str)


def sha256_hash(data: str) -> str:
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


def md5_hash(data: str) -> str:
    return hashlib.md5(data.encode("utf-8")).hexdigest()


def get_trace_id() -> str:
    trace_id = trace_id_var.get()
    if not trace_id:
        trace_id = generate_id("trace")
        trace_id_var.set(trace_id)
    return trace_id


def set_trace_id(trace_id: str) -> None:
    trace_id_var.set(trace_id)


def validate_email(email: str) -> bool:
    pattern = r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"
    return bool(re.match(pattern, email))


def validate_url(url: str) -> bool:
    pattern = r"^https?://[^\s/$.?#].[^\s]*$"
    return bool(re.match(pattern, url))


def sanitize_filename(filename: str) -> str:
    return re.sub(r"[^\w\-.]", "_", filename)


def safe_json_loads(data: str, default: Any = None) -> Any:
    try:
        return json.loads(data)
    except (json.JSONDecodeError, TypeError):
        return default


def safe_json_dumps(data: Any, default: str = "{}") -> str:
    try:
        return json.dumps(data, default=str)
    except (TypeError, ValueError):
        return default


def dict_merge(base: Dict[str, Any], override: Dict[str, Any]) -> Dict[str, Any]:
    result = base.copy()
    for key, value in override.items():
        if isinstance(value, dict) and key in result and isinstance(result[key], dict):
            result[key] = dict_merge(result[key], value)
        else:
            result[key] = value
    return result


def chunk_list(lst: List[Any], chunk_size: int) -> List[List[Any]]:
    return [lst[i : i + chunk_size] for i in range(0, len(lst), chunk_size)]


class MetricsRecorder:
    def __init__(self):
        self._metrics: Dict[str, List[float]] = {}
        self._counters: Dict[str, int] = {}

    def record(self, name: str, value: float) -> None:
        if name not in self._metrics:
            self._metrics[name] = []
        self._metrics[name].append(value)

    def increment(self, name: str, by: int = 1) -> None:
        self._counters[name] = self._counters.get(name, 0) + by

    def get_summary(self) -> Dict[str, Any]:
        summary = {"counters": self._counters.copy(), "metrics": {}}
        for name, values in self._metrics.items():
            if values:
                summary["metrics"][name] = {
                    "count": len(values),
                    "avg": sum(values) / len(values),
                    "min": min(values),
                    "max": max(values),
                    "p50": sorted(values)[len(values) // 2],
                    "p95": sorted(values)[int(len(values) * 0.95)],
                    "p99": sorted(values)[int(len(values) * 0.99)],
                }
        return summary


metrics_recorder = MetricsRecorder()


class ProcessingContext:
    def __init__(self, trace_id: Optional[str] = None):
        self.trace_id = trace_id or get_trace_id()
        self.start_time = utc_now()
        self.metrics = MetricsRecorder()
        self.events: List[Dict[str, Any]] = []
        self.errors: List[Dict[str, Any]] = []

    def emit_event(self, event_type: str, data: Dict[str, Any]) -> None:
        self.events.append(
            {"type": event_type, "data": data, "timestamp": utc_now_iso()}
        )

    def record_error(self, error: Exception, context: Optional[Dict[str, Any]] = None) -> None:
        self.errors.append(
            {
                "error": str(error),
                "type": type(error).__name__,
                "context": context or {},
                "timestamp": utc_now_iso(),
            }
        )

    def get_duration(self) -> float:
        return (utc_now() - self.start_time).total_seconds()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "trace_id": self.trace_id,
            "duration": self.get_duration(),
            "metrics": self.metrics.get_summary(),
            "events": self.events,
            "errors": self.errors,
        }


@asynccontextmanager
async def processing_context(trace_id: Optional[str] = None):
    ctx = ProcessingContext(trace_id)
    old_trace_id = trace_id_var.get()
    trace_id_var.set(ctx.trace_id)
    try:
        yield ctx
    finally:
        trace_id_var.set(old_trace_id)
        logger.info(f"Request processed: {json.dumps(ctx.to_dict(), default=str)}")


async def with_retry(
    func: Callable,
    *args,
    max_retries: int = 3,
    delay: float = 1.0,
    backoff: float = 2.0,
    retry_on: Tuple[type, ...] = (Exception,),
    **kwargs,
) -> Any:
    last_exception = None
    for attempt in range(max_retries):
        try:
            if asyncio.iscoroutinefunction(func):
                return await func(*args, **kwargs)
            return func(*args, **kwargs)
        except retry_on as e:
            last_exception = e
            if attempt < max_retries - 1:
                wait_time = delay * (backoff ** attempt)
                logger.warning(f"Attempt {attempt + 1} failed, retrying in {wait_time}s: {e}")
                await asyncio.sleep(wait_time)
            else:
                logger.error(f"All {max_retries} attempts failed: {e}")
    if last_exception:
        raise last_exception
