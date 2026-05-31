import uuid
import time
import re
from contextvars import ContextVar
from datetime import datetime, timezone
from typing import Any, Dict, Optional
from core import get_db_context
from .models import TraceSpan, RequestLog

current_trace_id: ContextVar[Optional[str]] = ContextVar("trace_id", default=None)
current_span_id: ContextVar[Optional[str]] = ContextVar("span_id", default=None)

MAX_ID_LENGTH = 128
MAX_NAME_LENGTH = 256
MAX_ATTRIBUTE_KEY_LENGTH = 128
MAX_ATTRIBUTE_VALUE_LENGTH = 1024
MAX_BODY_LENGTH = 1024 * 1024

UUID_PATTERN = re.compile(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
    re.IGNORECASE
)


def _validate_id(value: Optional[str], default_generator) -> str:
    if value is None:
        return default_generator()
    if not isinstance(value, str):
        return default_generator()
    if len(value) > MAX_ID_LENGTH:
        return default_generator()
    if len(value.strip()) == 0:
        return default_generator()
    return value


def _sanitize_string(value: Optional[str], max_length: int) -> Optional[str]:
    if value is None:
        return None
    if not isinstance(value, str):
        value = str(value)
    if len(value) > max_length:
        value = value[:max_length]
    return value


def generate_trace_id() -> str:
    return str(uuid.uuid4())


def generate_span_id() -> str:
    return str(uuid.uuid4())


def get_current_trace_id() -> Optional[str]:
    return current_trace_id.get()


def get_current_span_id() -> Optional[str]:
    return current_span_id.get()


class Span:
    def __init__(
        self,
        name: str,
        service_name: str,
        trace_id: Optional[str] = None,
        span_id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        kind: Optional[str] = None,
        attributes: Optional[Dict[str, Any]] = None,
    ):
        self.name = _sanitize_string(name, MAX_NAME_LENGTH) or "unknown"
        self.service_name = _sanitize_string(service_name, MAX_NAME_LENGTH) or "unknown"
        self.trace_id = _validate_id(trace_id, generate_trace_id)
        self.span_id = _validate_id(span_id, generate_span_id)
        self.parent_span_id = _sanitize_string(parent_span_id, MAX_ID_LENGTH)
        self.kind = _sanitize_string(kind, MAX_NAME_LENGTH)
        self.attributes: Dict[str, Any] = {}
        if attributes:
            for key, value in attributes.items():
                sanitized_key = _sanitize_string(key, MAX_ATTRIBUTE_KEY_LENGTH)
                if sanitized_key:
                    if isinstance(value, str):
                        sanitized_value = _sanitize_string(value, MAX_ATTRIBUTE_VALUE_LENGTH)
                    else:
                        sanitized_value = value
                    self.attributes[sanitized_key] = sanitized_value
        self.started_at = datetime.now(timezone.utc)
        self.ended_at: Optional[datetime] = None
        self.status: Optional[str] = None
        self.status_message: Optional[str] = None
        self._token_trace = None
        self._token_span = None

    def __enter__(self):
        self._token_trace = current_trace_id.set(self.trace_id)
        self._token_span = current_span_id.set(self.span_id)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if exc_type is not None:
            self.set_status("error", str(exc_val))
        else:
            self.set_status("ok")
        self.end()
        if self._token_trace:
            current_trace_id.reset(self._token_trace)
        if self._token_span:
            current_span_id.reset(self._token_span)

    async def __aenter__(self):
        self._token_trace = current_trace_id.set(self.trace_id)
        self._token_span = current_span_id.set(self.span_id)
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if exc_type is not None:
            self.set_status("error", str(exc_val))
        else:
            self.set_status("ok")
        self.end()
        if self._token_trace:
            current_trace_id.reset(self._token_trace)
        if self._token_span:
            current_span_id.reset(self._token_span)

    def set_attribute(self, key: str, value: Any) -> None:
        sanitized_key = _sanitize_string(key, MAX_ATTRIBUTE_KEY_LENGTH)
        if not sanitized_key:
            return
        if isinstance(value, str):
            sanitized_value = _sanitize_string(value, MAX_ATTRIBUTE_VALUE_LENGTH)
        else:
            sanitized_value = value
        self.attributes[sanitized_key] = sanitized_value

    def set_status(self, status: str, message: Optional[str] = None) -> None:
        self.status = _sanitize_string(status, MAX_NAME_LENGTH)
        self.status_message = _sanitize_string(message, MAX_ATTRIBUTE_VALUE_LENGTH)

    def end(self) -> None:
        if self.ended_at is None:
            self.ended_at = datetime.now(timezone.utc)
        self._save_async()

    def _save_async(self) -> None:
        import asyncio

        async def save():
            try:
                async with get_db_context() as db:
                    duration = None
                    if self.ended_at and self.started_at:
                        duration = (self.ended_at - self.started_at).total_seconds() * 1000

                    span = TraceSpan(
                        trace_id=self.trace_id,
                        span_id=self.span_id,
                        parent_span_id=self.parent_span_id,
                        name=self.name,
                        service_name=self.service_name,
                        kind=self.kind,
                        attributes=self.attributes,
                        status=self.status,
                        status_message=self.status_message,
                        started_at=self.started_at,
                        ended_at=self.ended_at,
                        duration_ms=duration,
                    )
                    db.add(span)
            except Exception as e:
                print(f"Failed to save span: {e}")

        asyncio.create_task(save())

    @property
    def duration_ms(self) -> Optional[float]:
        if self.ended_at and self.started_at:
            return (self.ended_at - self.started_at).total_seconds() * 1000
        return None


def start_span(
    name: str,
    service_name: str,
    parent_span: Optional[Span] = None,
    kind: Optional[str] = None,
    attributes: Optional[Dict[str, Any]] = None,
) -> Span:
    trace_id = parent_span.trace_id if parent_span else None
    parent_span_id = parent_span.span_id if parent_span else get_current_span_id()

    return Span(
        name=name,
        service_name=service_name,
        trace_id=trace_id,
        parent_span_id=parent_span_id,
        kind=kind,
        attributes=attributes,
    )


async def log_request(
    trace_id: str,
    span_id: str,
    service_name: str,
    method: str,
    path: str,
    request_headers: Dict[str, Any],
    request_body: Optional[str],
    client_ip: Optional[str],
    user_agent: Optional[str],
    user_id: Optional[str],
) -> RequestLog:
    sanitized_trace_id = _validate_id(trace_id, generate_trace_id)
    sanitized_span_id = _validate_id(span_id, generate_span_id)
    sanitized_service_name = _sanitize_string(service_name, MAX_NAME_LENGTH) or "unknown"
    sanitized_method = _sanitize_string(method, 16) or "UNKNOWN"
    sanitized_path = _sanitize_string(path, 2048) or "/"
    sanitized_client_ip = _sanitize_string(client_ip, 45)
    sanitized_user_agent = _sanitize_string(user_agent, 512)
    sanitized_user_id = _sanitize_string(user_id, 128)

    sanitized_headers = {}
    if request_headers:
        for key, value in request_headers.items():
            sanitized_key = _sanitize_string(key, MAX_ATTRIBUTE_KEY_LENGTH)
            if sanitized_key:
                if isinstance(value, str):
                    sanitized_headers[sanitized_key] = _sanitize_string(value, MAX_ATTRIBUTE_VALUE_LENGTH)
                else:
                    sanitized_headers[sanitized_key] = value

    sanitized_body = _sanitize_string(request_body, MAX_BODY_LENGTH)

    async with get_db_context() as db:
        log = RequestLog(
            trace_id=sanitized_trace_id,
            span_id=sanitized_span_id,
            parent_span_id=get_current_span_id(),
            service_name=sanitized_service_name,
            method=sanitized_method,
            path=sanitized_path,
            request_headers=sanitized_headers,
            request_body=sanitized_body,
            client_ip=sanitized_client_ip,
            user_agent=sanitized_user_agent,
            user_id=sanitized_user_id,
        )
        db.add(log)
        return log


async def update_request_log(
    log_id: str,
    status_code: Optional[int],
    response_headers: Optional[Dict[str, Any]],
    response_body: Optional[str],
    error_message: Optional[str],
    duration_ms: Optional[float],
) -> None:
    try:
        sanitized_log_id = _sanitize_string(log_id, MAX_ID_LENGTH)
        if not sanitized_log_id:
            return

        if status_code is not None:
            try:
                status_code = int(status_code)
                if status_code < 100 or status_code > 599:
                    status_code = 500
            except (ValueError, TypeError):
                status_code = 500

        sanitized_headers = {}
        if response_headers:
            for key, value in response_headers.items():
                sanitized_key = _sanitize_string(key, MAX_ATTRIBUTE_KEY_LENGTH)
                if sanitized_key:
                    if isinstance(value, str):
                        sanitized_headers[sanitized_key] = _sanitize_string(value, MAX_ATTRIBUTE_VALUE_LENGTH)
                    else:
                        sanitized_headers[sanitized_key] = value

        sanitized_body = _sanitize_string(response_body, MAX_BODY_LENGTH)
        sanitized_error = _sanitize_string(error_message, MAX_ATTRIBUTE_VALUE_LENGTH)

        if duration_ms is not None:
            try:
                duration_ms = float(duration_ms)
                if duration_ms < 0:
                    duration_ms = 0.0
            except (ValueError, TypeError):
                duration_ms = None

        async with get_db_context() as db:
            from sqlalchemy import select

            result = await db.execute(select(RequestLog).where(RequestLog.id == sanitized_log_id))
            log = result.scalar_one_or_none()
            if log:
                log.status_code = status_code
                log.response_headers = sanitized_headers or {}
                log.response_body = sanitized_body
                log.error_message = sanitized_error
                log.duration_ms = duration_ms
                log.completed_at = datetime.now(timezone.utc)
    except Exception as e:
        print(f"Failed to update request log: {e}")
