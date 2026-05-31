"""
API网关契约
中间件、处理器、数据一致性策略
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from enum import Enum
from typing import Any, Awaitable, Callable, Dict, Optional

from .tracing import Request, Response, TraceContext

HandlerFunc = Callable[[Request], Awaitable[Response]]


class ConsistencyPolicy(str, Enum):
    NONE = "none"
    AT_MOST_ONCE = "at_most_once"
    AT_LEAST_ONCE = "at_least_once"
    EXACTLY_ONCE = "exactly_once"


class GatewayMiddleware(ABC):
    @abstractmethod
    async def process_request(
        self,
        request: Request,
        trace_ctx: TraceContext,
    ) -> Optional[Response]:
        ...

    async def process_response(
        self,
        request: Request,
        response: Response,
        trace_ctx: TraceContext,
    ) -> Response:
        return response
