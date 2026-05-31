"""
API网关实现
核心功能：
1. 请求日志记录
2. 分布式链路追踪
3. 中间件支持（限流、认证等）
"""

from __future__ import annotations

import asyncio
import time
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional
from uuid import uuid4

from src.core import (
    GatewayError,
    LoggerProtocol,
    Request,
    Response,
    TraceContext,
    TraceSpan,
)


@dataclass
class SimpleRequest(Request):
    """简单请求实现"""
    request_id: str = field(default_factory=lambda: str(uuid4()))
    method: str = "GET"
    path: str = "/"
    headers: Dict[str, str] = field(default_factory=dict)
    body: Optional[bytes] = None
    query_params: Dict[str, str] = field(default_factory=dict)


@dataclass
class SimpleResponse(Response):
    """简单响应实现"""
    status_code: int = 200
    headers: Dict[str, str] = field(default_factory=dict)
    body: Optional[bytes] = None


@dataclass
class SimpleTraceContext(TraceContext):
    """简单链路追踪上下文实现"""
    trace_id: str = field(default_factory=lambda: str(uuid4()))
    span_id: str = field(default_factory=lambda: str(uuid4()))
    parent_span_id: Optional[str] = None
    service_name: str = "gateway"
    tags: Dict[str, Any] = field(default_factory=dict)


HandlerFunc = Callable[[Request], Awaitable[Response]]


class GatewayMiddleware(ABC):
    """网关中间件抽象基类"""

    @abstractmethod
    async def process_request(
        self,
        request: Request,
        trace_ctx: TraceContext,
    ) -> Optional[Response]:
        """处理请求，返回Response则中断后续处理"""
        ...

    async def process_response(
        self,
        request: Request,
        response: Response,
        trace_ctx: TraceContext,
    ) -> Response:
        """处理响应，返回修改后的Response"""
        return response


class RateLimitMiddleware(GatewayMiddleware):
    """限流中间件"""

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
        self,
        request: Request,
        trace_ctx: TraceContext,
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


class AuthMiddleware(GatewayMiddleware):
    """认证中间件"""

    def __init__(
        self,
        api_keys: Optional[Dict[str, str]] = None,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._api_keys = api_keys or {}
        self._logger = logger

    async def process_request(
        self,
        request: Request,
        trace_ctx: TraceContext,
    ) -> Optional[Response]:
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return SimpleResponse(
                status_code=401,
                body=b"Unauthorized",
            )

        api_key = auth_header[7:]
        if api_key not in self._api_keys:
            if self._logger:
                self._logger.warning(
                    "Invalid API key",
                    request_id=request.request_id,
                )
            return SimpleResponse(
                status_code=403,
                body=b"Forbidden",
            )

        trace_ctx.tags["user"] = self._api_keys[api_key]
        return None


class RequestLogger:
    """请求日志记录器"""

    def __init__(self, logger: LoggerProtocol) -> None:
        self._logger = logger

    def log_request(self, request: Request, trace_ctx: TraceContext) -> None:
        logger = self._logger.with_trace(trace_ctx)
        logger.info(
            "Incoming request",
            method=request.method,
            path=request.path,
            request_id=request.request_id,
            query_params=request.query_params,
            content_length=len(request.body) if request.body else 0,
        )

    def log_response(
        self,
        request: Request,
        response: Response,
        trace_ctx: TraceContext,
        duration: float,
    ) -> None:
        logger = self._logger.with_trace(trace_ctx)
        log_method = logger.info if response.status_code < 400 else logger.error
        log_method(
            "Request completed",
            method=request.method,
            path=request.path,
            status_code=response.status_code,
            duration_ms=round(duration * 1000, 2),
            request_id=request.request_id,
            content_length=len(response.body) if response.body else 0,
        )


class TraceManager:
    """链路追踪管理器"""

    def __init__(self, service_name: str = "gateway") -> None:
        self.service_name = service_name
        self._spans: Dict[str, TraceSpan] = {}
        self._traces: Dict[str, List[TraceSpan]] = {}

    def create_context(
        self,
        request: Request,
    ) -> SimpleTraceContext:
        trace_id = request.headers.get("X-Trace-Id", str(uuid4()))
        parent_span_id = request.headers.get("X-Parent-Span-Id")

        ctx = SimpleTraceContext(
            trace_id=trace_id,
            span_id=str(uuid4()),
            parent_span_id=parent_span_id,
            service_name=self.service_name,
            tags={
                "http.method": request.method,
                "http.path": request.path,
                "request.id": request.request_id,
            },
        )
        return ctx

    def start_span(
        self,
        trace_ctx: TraceContext,
        operation_name: str,
    ) -> TraceSpan:
        span = TraceSpan(
            span_id=str(uuid4()),
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


class ApiGateway:
    """
    API网关 - 核心类
    整合请求日志、链路追踪、中间件处理
    """

    def __init__(
        self,
        logger: LoggerProtocol,
        service_name: str = "api-gateway",
    ) -> None:
        self._logger = logger
        self._request_logger = RequestLogger(logger)
        self._trace_manager = TraceManager(service_name)
        self._middlewares: List[GatewayMiddleware] = []
        self._handlers: Dict[str, HandlerFunc] = {}

    def add_middleware(self, middleware: GatewayMiddleware) -> None:
        self._middlewares.append(middleware)

    def register_handler(self, path: str, handler: HandlerFunc) -> None:
        self._handlers[path] = handler

    def _extract_trace_headers(
        self,
        response: Response,
        trace_ctx: TraceContext,
    ) -> Response:
        response.headers["X-Trace-Id"] = trace_ctx.trace_id
        response.headers["X-Span-Id"] = trace_ctx.span_id
        return response

    async def _dispatch(
        self,
        request: Request,
    ) -> Response:
        handler = self._handlers.get(request.path)
        if handler:
            return await handler(request)
        return SimpleResponse(
            status_code=404,
            body=b"Not Found",
        )

    async def process_request(
        self,
        request: Request,
    ) -> Response:
        start_time = time.time()

        trace_ctx = self._trace_manager.create_context(request)
        gateway_span = self._trace_manager.start_span(trace_ctx, "gateway.request")

        try:
            self._request_logger.log_request(request, trace_ctx)

            for middleware in self._middlewares:
                early_response = await middleware.process_request(request, trace_ctx)
                if early_response:
                    gateway_span.finish("early_return")
                    duration = time.time() - start_time
                    self._request_logger.log_response(
                        request, early_response, trace_ctx, duration
                    )
                    return self._extract_trace_headers(early_response, trace_ctx)

            handler_span = self._trace_manager.start_span(
                trace_ctx, f"handler.{request.path}"
            )
            try:
                response = await self._dispatch(request)
                handler_span.finish("success" if response.status_code < 400 else "error")
            except Exception as e:
                handler_span.finish("error")
                self._logger.with_trace(trace_ctx).error(
                    "Handler exception",
                    error=str(e),
                    path=request.path,
                )
                response = SimpleResponse(
                    status_code=500,
                    body=f"Internal Server Error: {e}".encode(),
                )

            for middleware in reversed(self._middlewares):
                response = await middleware.process_response(
                    request, response, trace_ctx
                )

            gateway_span.finish("success" if response.status_code < 400 else "error")

            duration = time.time() - start_time
            self._request_logger.log_response(request, response, trace_ctx, duration)

            return self._extract_trace_headers(response, trace_ctx)

        except GatewayError:
            gateway_span.finish("error")
            raise
        except Exception as e:
            gateway_span.finish("error")
            self._logger.with_trace(trace_ctx).error(
                "Gateway processing error",
                error=str(e),
                path=request.path,
            )
            response = SimpleResponse(
                status_code=500,
                body=b"Internal Server Error",
            )
            duration = time.time() - start_time
            self._request_logger.log_response(request, response, trace_ctx, duration)
            return self._extract_trace_headers(response, trace_ctx)

    def get_trace_info(self, trace_id: str) -> Dict[str, Any]:
        return self._trace_manager.get_trace_tree(trace_id)
