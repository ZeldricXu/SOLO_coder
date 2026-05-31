import asyncio
import json
import logging
import threading
import time
import uuid
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional
from pythonjsonlogger import jsonlogger

from app.monitoring.metrics import get_metrics_collector
from app.monitoring.tracing import get_tracer, SpanStatus


class CircuitState(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass
class RequestLogEntry:
    timestamp: datetime
    trace_id: str
    span_id: str
    method: str
    path: str
    status_code: int
    duration_ms: float
    client_ip: str
    user_agent: str
    request_size: int = 0
    response_size: int = 0
    error: Optional[str] = None
    tags: Dict[str, str] = field(default_factory=dict)


class RequestLogger:
    def __init__(self, log_dir: str = "./logs", log_level: str = "INFO"):
        self.log_dir = Path(log_dir)
        self.log_dir.mkdir(parents=True, exist_ok=True)
        self.logger = logging.getLogger("api_gateway")
        self.logger.setLevel(getattr(logging, log_level.upper(), logging.INFO))
        self.logger.propagate = False

        if not self.logger.handlers:
            file_handler = RotatingFileHandler(
                self.log_dir / "access.log",
                maxBytes=10 * 1024 * 1024,
                backupCount=5,
                encoding="utf-8"
            )
            formatter = jsonlogger.JsonFormatter(
                "%(asctime)s %(levelname)s %(trace_id)s %(span_id)s %(method)s %(path)s "
                "%(status_code)s %(duration_ms)s %(client_ip)s"
            )
            file_handler.setFormatter(formatter)
            self.logger.addHandler(file_handler)

            console_handler = logging.StreamHandler()
            console_formatter = logging.Formatter(
                "%(asctime)s [%(levelname)s] %(trace_id)s - %(method)s %(path)s - %(status_code)s (%(duration_ms).2fms)"
            )
            console_handler.setFormatter(console_formatter)
            self.logger.addHandler(console_handler)

        self._buffer: deque = deque(maxlen=1000)
        self._lock = threading.Lock()

    def log_request(self, entry: RequestLogEntry) -> None:
        with self._lock:
            self._buffer.append(entry)

        extra = {
            "trace_id": entry.trace_id,
            "span_id": entry.span_id,
            "method": entry.method,
            "path": entry.path,
            "status_code": entry.status_code,
            "duration_ms": f"{entry.duration_ms:.2f}",
            "client_ip": entry.client_ip
        }

        log_func = self.logger.info if entry.status_code < 400 else \
                   self.logger.warning if entry.status_code < 500 else \
                   self.logger.error
        log_func(
            f"{entry.method} {entry.path} -> {entry.status_code}",
            extra=extra
        )

    def get_recent_logs(self, limit: int = 100) -> List[RequestLogEntry]:
        with self._lock:
            return list(self._buffer)[-limit:]

    def get_logs_by_trace_id(self, trace_id: str) -> List[RequestLogEntry]:
        with self._lock:
            return [entry for entry in self._buffer if entry.trace_id == trace_id]


class TraceMiddleware:
    def __init__(self):
        self.tracer = get_tracer()

    def extract_trace_context(self, headers: Dict[str, str]) -> Optional[str]:
        trace_headers = [
            "traceparent",
            "x-trace-id",
            "x-request-id",
            "x-b3-traceid"
        ]
        for header in trace_headers:
            if header in headers:
                value = headers[header]
                if header == "traceparent":
                    parts = value.split("-")
                    if len(parts) >= 2:
                        return parts[1]
                return value
        return None

    def start_request_span(
        self,
        method: str,
        path: str,
        trace_id: Optional[str] = None,
        attributes: Optional[Dict[str, Any]] = None
    ):
        span_name = f"{method.upper()} {path}"
        return self.tracer.span(
            span_name,
            trace_id=trace_id,
            attributes=attributes or {}
        )


class RateLimiter:
    def __init__(self, requests_per_minute: int = 1000, window_seconds: int = 60):
        self.requests_per_minute = requests_per_minute
        self.window_seconds = window_seconds
        self._requests: Dict[str, List[float]] = defaultdict(list)
        self._lock = threading.Lock()

    def is_allowed(self, key: str) -> bool:
        now = time.time()
        window_start = now - self.window_seconds

        with self._lock:
            requests = self._requests[key]
            self._requests[key] = [t for t in requests if t > window_start]

            if len(self._requests[key]) < self.requests_per_minute:
                self._requests[key].append(now)
                return True
            return False

    def get_remaining(self, key: str) -> int:
        now = time.time()
        window_start = now - self.window_seconds
        with self._lock:
            requests = [t for t in self._requests.get(key, []) if t > window_start]
            return max(0, self.requests_per_minute - len(requests))

    def reset(self, key: Optional[str] = None) -> None:
        with self._lock:
            if key:
                self._requests.pop(key, None)
            else:
                self._requests.clear()


class CircuitBreaker:
    def __init__(
        self,
        name: str,
        failure_threshold: int = 5,
        recovery_timeout: int = 30,
        half_open_max_calls: int = 3
    ):
        self.name = name
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self.half_open_max_calls = half_open_max_calls

        self._state: CircuitState = CircuitState.CLOSED
        self._failure_count = 0
        self._success_count = 0
        self._last_failure_time: Optional[float] = None
        self._lock = threading.Lock()

    @property
    def state(self) -> CircuitState:
        with self._lock:
            if self._state == CircuitState.OPEN:
                if self._last_failure_time:
                    elapsed = time.time() - self._last_failure_time
                    if elapsed >= self.recovery_timeout:
                        self._state = CircuitState.HALF_OPEN
                        self._success_count = 0
            return self._state

    def record_success(self) -> None:
        with self._lock:
            if self._state == CircuitState.HALF_OPEN:
                self._success_count += 1
                if self._success_count >= self.half_open_max_calls:
                    self._state = CircuitState.CLOSED
                    self._failure_count = 0
                    self._success_count = 0
            elif self._state == CircuitState.CLOSED:
                self._failure_count = 0

    def record_failure(self) -> None:
        with self._lock:
            if self._state == CircuitState.HALF_OPEN:
                self._state = CircuitState.OPEN
                self._last_failure_time = time.time()
                self._success_count = 0
            elif self._state == CircuitState.CLOSED:
                self._failure_count += 1
                if self._failure_count >= self.failure_threshold:
                    self._state = CircuitState.OPEN
                    self._last_failure_time = time.time()

    def can_execute(self) -> bool:
        return self.state != CircuitState.OPEN

    def reset(self) -> None:
        with self._lock:
            self._state = CircuitState.CLOSED
            self._failure_count = 0
            self._success_count = 0
            self._last_failure_time = None


class APIGateway:
    def __init__(self):
        self.request_logger = RequestLogger()
        self.trace_middleware = TraceMiddleware()
        self.rate_limiter = RateLimiter()
        self._circuit_breakers: Dict[str, CircuitBreaker] = {}
        self._lock = threading.Lock()
        self.metrics = get_metrics_collector()

    def get_circuit_breaker(self, name: str) -> CircuitBreaker:
        with self._lock:
            if name not in self._circuit_breakers:
                self._circuit_breakers[name] = CircuitBreaker(name)
            return self._circuit_breakers[name]

    def extract_trace_context(self, headers: Dict[str, str]) -> Optional[str]:
        return self.trace_middleware.extract_trace_context(headers)

    async def process_request(
        self,
        method: str,
        path: str,
        headers: Dict[str, str],
        client_ip: str,
        user_agent: str,
        handler: Callable,
        body: Any = None,
        body_size: int = 0
    ) -> Dict[str, Any]:
        start_time = time.perf_counter()
        trace_id = self.extract_trace_context(headers) or uuid.uuid4().hex

        client_key = f"{client_ip}"
        if not self.rate_limiter.is_allowed(client_key):
            return {
                "status_code": 429,
                "body": {"error": "Too Many Requests", "message": "Rate limit exceeded"},
                "headers": {"X-RateLimit-Remaining": "0"}
            }

        tracer = get_tracer()
        with tracer.span("gateway_request", trace_id=trace_id):
            current_span = tracer.get_current_span()
            span_id = current_span.span_id if current_span else "unknown"

            try:
                if asyncio.iscoroutinefunction(handler):
                    result = await handler(method, path, headers, body)
                else:
                    result = handler(method, path, headers, body)

                status_code = result.get("status_code", 200)
                response_body = result.get("body", {})
                response_size = len(json.dumps(response_body)) if response_body else 0

                end_time = time.perf_counter()
                duration_ms = (end_time - start_time) * 1000

                entry = RequestLogEntry(
                    timestamp=datetime.utcnow(),
                    trace_id=trace_id,
                    span_id=span_id,
                    method=method,
                    path=path,
                    status_code=status_code,
                    duration_ms=duration_ms,
                    client_ip=client_ip,
                    user_agent=user_agent,
                    request_size=body_size,
                    response_size=response_size
                )
                self.request_logger.log_request(entry)
                self._record_metrics(method, path, status_code, duration_ms)

                return {
                    "status_code": status_code,
                    "body": response_body,
                    "headers": {
                        **result.get("headers", {}),
                        "X-Trace-ID": trace_id
                    }
                }

            except Exception as e:
                end_time = time.perf_counter()
                duration_ms = (end_time - start_time) * 1000

                entry = RequestLogEntry(
                    timestamp=datetime.utcnow(),
                    trace_id=trace_id,
                    span_id=span_id,
                    method=method,
                    path=path,
                    status_code=500,
                    duration_ms=duration_ms,
                    client_ip=client_ip,
                    user_agent=user_agent,
                    request_size=body_size,
                    error=str(e)
                )
                self.request_logger.log_request(entry)
                self.metrics.increment_counter("gateway_errors", labels={"method": method, "path": path})

                return {
                    "status_code": 500,
                    "body": {"error": "Internal Server Error", "message": str(e)},
                    "headers": {"X-Trace-ID": trace_id}
                }

    def _record_metrics(self, method: str, path: str, status_code: int, duration_ms: float) -> None:
        self.metrics.increment_counter(
            "gateway_requests_total",
            labels={"method": method, "path": path, "status": str(status_code)}
        )
        self.metrics.record_histogram(
            "gateway_request_duration_ms",
            duration_ms,
            labels={"method": method, "path": path}
        )

    def get_recent_logs(self, limit: int = 100) -> List[Dict[str, Any]]:
        logs = self.request_logger.get_recent_logs(limit)
        return [
            {
                "timestamp": entry.timestamp.isoformat(),
                "trace_id": entry.trace_id,
                "method": entry.method,
                "path": entry.path,
                "status_code": entry.status_code,
                "duration_ms": entry.duration_ms,
                "client_ip": entry.client_ip,
                "error": entry.error
            }
            for entry in logs
        ]


_gateway_instance: Optional[APIGateway] = None
_gateway_lock = threading.Lock()


def get_gateway() -> APIGateway:
    global _gateway_instance
    if _gateway_instance is None:
        with _gateway_lock:
            if _gateway_instance is None:
                _gateway_instance = APIGateway()
    return _gateway_instance


def log_request(
    method: str,
    path: str,
    status_code: int,
    duration_ms: float,
    trace_id: str,
    client_ip: str,
    user_agent: str = ""
) -> None:
    gateway = get_gateway()
    entry = RequestLogEntry(
        timestamp=datetime.utcnow(),
        trace_id=trace_id,
        span_id=uuid.uuid4().hex[:8],
        method=method,
        path=path,
        status_code=status_code,
        duration_ms=duration_ms,
        client_ip=client_ip,
        user_agent=user_agent
    )
    gateway.request_logger.log_request(entry)


def extract_trace_context(headers: Dict[str, str]) -> Optional[str]:
    gateway = get_gateway()
    return gateway.extract_trace_context(headers)
