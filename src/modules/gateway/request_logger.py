"""Request logging middleware for API Gateway."""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, Optional
from uuid import UUID, uuid4

from ...infrastructure.logging.structured_logger import LogManager


@dataclass
class RequestLogEntry:
    request_id: UUID = field(default_factory=uuid4)
    timestamp: datetime = field(default_factory=datetime.utcnow)
    method: str = ""
    path: str = ""
    status_code: int = 0
    duration_ms: float = 0.0
    client_ip: str = ""
    user_agent: str = ""
    content_length: int = 0
    correlation_id: Optional[str] = None
    error_message: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "request_id": str(self.request_id),
            "timestamp": self.timestamp.isoformat(),
            "method": self.method,
            "path": self.path,
            "status_code": self.status_code,
            "duration_ms": self.duration_ms,
            "client_ip": self.client_ip,
            "user_agent": self.user_agent,
            "content_length": self.content_length,
            "correlation_id": self.correlation_id,
            "error_message": self.error_message,
            "metadata": self.metadata,
        }


class RequestLogger:
    def __init__(self, log_body: bool = False, max_body_size: int = 1024) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._log_body = log_body
        self._max_body_size = max_body_size
        self._request_times: Dict[UUID, float] = {}

    async def start_request(
        self,
        method: str,
        path: str,
        client_ip: str = "",
        user_agent: str = "",
        correlation_id: Optional[str] = None,
    ) -> UUID:
        request_id = uuid4()
        self._request_times[request_id] = time.time()

        entry = RequestLogEntry(
            request_id=request_id,
            method=method,
            path=path,
            client_ip=client_ip,
            user_agent=user_agent,
            correlation_id=correlation_id,
        )

        self._logger.info(
            f"Request started: {method} {path}",
            request_id=str(request_id),
            correlation_id=correlation_id,
            client_ip=client_ip,
        )

        return request_id

    async def end_request(
        self,
        request_id: UUID,
        status_code: int,
        content_length: int = 0,
        error_message: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> RequestLogEntry:
        start_time = self._request_times.pop(request_id, time.time())
        duration_ms = (time.time() - start_time) * 1000

        entry = RequestLogEntry(
            request_id=request_id,
            status_code=status_code,
            duration_ms=duration_ms,
            content_length=content_length,
            error_message=error_message,
            metadata=metadata or {},
        )

        log_level = "info" if status_code < 400 else ("warning" if status_code < 500 else "error")
        log_method = getattr(self._logger, log_level)

        log_method(
            f"Request completed: {entry.method} {entry.path} - {status_code} ({duration_ms:.2f}ms)",
            request_id=str(request_id),
            status_code=status_code,
            duration_ms=duration_ms,
            error=error_message,
        )

        return entry

    async def log_request_body(
        self,
        request_id: UUID,
        body: bytes,
        content_type: str = "",
    ) -> None:
        if not self._log_body:
            return

        if len(body) > self._max_body_size:
            body_preview = body[:self._max_body_size] + b"..."
        else:
            body_preview = body

        try:
            body_str = body_preview.decode("utf-8")
        except UnicodeDecodeError:
            body_str = f"<binary data, {len(body)} bytes>"

        self._logger.debug(
            "Request body",
            request_id=str(request_id),
            content_type=content_type,
            body_size=len(body),
            body=body_str,
        )

    async def log_response_body(
        self,
        request_id: UUID,
        body: bytes,
        content_type: str = "",
    ) -> None:
        if not self._log_body:
            return

        if len(body) > self._max_body_size:
            body_preview = body[:self._max_body_size] + b"..."
        else:
            body_preview = body

        try:
            body_str = body_preview.decode("utf-8")
        except UnicodeDecodeError:
            body_str = f"<binary data, {len(body)} bytes>"

        self._logger.debug(
            "Response body",
            request_id=str(request_id),
            content_type=content_type,
            body_size=len(body),
            body=body_str,
        )
