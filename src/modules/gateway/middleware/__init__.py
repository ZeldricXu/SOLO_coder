"""
中间件组件 - 认证、限流、数据一致性校验
"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from src.domain.contracts.tracing import LoggerProtocol, Request, Response, TraceContext
from src.domain.contracts.gateway import GatewayMiddleware, ConsistencyPolicy
from src.domain.models.gateway import ConsistencyCheckResult


@dataclass
class SimpleRequest(Request):
    request_id: str = field(default_factory=lambda: str(__import__("uuid").uuid4()))
    method: str = "GET"
    path: str = "/"
    headers: Dict[str, str] = field(default_factory=dict)
    body: Optional[bytes] = None
    query_params: Dict[str, str] = field(default_factory=dict)


@dataclass
class SimpleResponse(Response):
    status_code: int = 200
    headers: Dict[str, str] = field(default_factory=dict)
    body: Optional[bytes] = None


class AuthMiddleware(GatewayMiddleware):
    def __init__(
        self,
        api_keys: Optional[Dict[str, str]] = None,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._api_keys = api_keys or {}
        self._logger = logger

    async def process_request(
        self, request: Request, trace_ctx: TraceContext
    ) -> Optional[Response]:
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return SimpleResponse(status_code=401, body=b"Unauthorized")
        api_key = auth_header[7:]
        if api_key not in self._api_keys:
            if self._logger:
                self._logger.warning("Invalid API key", request_id=request.request_id)
            return SimpleResponse(status_code=403, body=b"Forbidden")
        trace_ctx.tags["user"] = self._api_keys[api_key]
        return None


class RateLimitMiddleware(GatewayMiddleware):
    def __init__(
        self,
        max_requests: int = 100,
        window_seconds: int = 60,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._request_times: Dict[str, List[float]] = {}
        self._logger = logger

    async def process_request(
        self, request: Request, trace_ctx: TraceContext
    ) -> Optional[Response]:
        client_id = request.headers.get("X-Forwarded-For", "unknown")
        now = time.time()
        times = self._request_times.get(client_id, [])
        times = [t for t in times if now - t < self.window_seconds]
        self._request_times[client_id] = times
        if len(times) >= self.max_requests:
            if self._logger:
                self._logger.warning(
                    "Rate limit exceeded",
                    client_id=client_id,
                    request_id=request.request_id,
                )
            return SimpleResponse(
                status_code=429,
                body=b"Rate limit exceeded",
                headers={"Retry-After": str(self.window_seconds)},
            )
        times.append(now)
        return None


class ConsistencyGuard(GatewayMiddleware):
    """
    数据一致性保障中间件
    - 幂等性检查（基于Idempotency-Key）
    - 请求校验和
    - 写操作一致性策略
    """

    def __init__(
        self,
        policy: ConsistencyPolicy = ConsistencyPolicy.AT_LEAST_ONCE,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._policy = policy
        self._logger = logger
        self._idempotency_cache: Dict[str, Dict[str, Any]] = {}

    async def process_request(
        self, request: Request, trace_ctx: TraceContext
    ) -> Optional[Response]:
        idempotency_key = request.headers.get("Idempotency-Key")

        if idempotency_key and idempotency_key in self._idempotency_cache:
            cached = self._idempotency_cache[idempotency_key]
            if self._logger:
                self._logger.info(
                    "Idempotent request detected",
                    request_id=request.request_id,
                    idempotency_key=idempotency_key,
                )
            return SimpleResponse(
                status_code=cached.get("status_code", 200),
                body=cached.get("body"),
                headers={"X-Idempotent-Replay": "true"},
            )

        return None

    async def process_response(
        self,
        request: Request,
        response: Response,
        trace_ctx: TraceContext,
    ) -> Response:
        idempotency_key = request.headers.get("Idempotency-Key")

        if idempotency_key and self._policy != ConsistencyPolicy.NONE:
            self._idempotency_cache[idempotency_key] = {
                "status_code": response.status_code,
                "body": response.body,
            }

        if self._policy in (ConsistencyPolicy.EXACTLY_ONCE, ConsistencyPolicy.AT_LEAST_ONCE):
            checksum = self._compute_checksum(request)
            response.headers["X-Request-Checksum"] = checksum

        return response

    def check_consistency(self, request: Request) -> ConsistencyCheckResult:
        result = ConsistencyCheckResult(
            request_id=request.request_id,
            policy=self._policy.value,
        )

        if self._policy == ConsistencyPolicy.NONE:
            return result

        idempotency_key = request.headers.get("Idempotency-Key")
        if not idempotency_key and request.method in ("POST", "PUT", "PATCH"):
            if self._policy == ConsistencyPolicy.EXACTLY_ONCE:
                result.add_violation(
                    "Idempotency-Key required for write operations under EXACTLY_ONCE policy"
                )

        result.idempotency_key = idempotency_key
        result.checksum = self._compute_checksum(request)

        return result

    def _compute_checksum(self, request: Request) -> str:
        content = f"{request.method}:{request.path}:{request.body or b''}"
        return hashlib.sha256(content.encode()).hexdigest()[:16]
