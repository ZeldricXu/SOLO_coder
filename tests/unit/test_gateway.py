"""
单元测试: API网关模块
"""

import pytest
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.infrastructure.logging import StructuredLogger, ConsoleHandler
from src.modules.api_gateway import (
    ApiGateway,
    SimpleRequest,
    SimpleResponse,
    RateLimitMiddleware,
    AuthMiddleware,
)


@pytest.fixture
def logger():
    return StructuredLogger(
        service_name="test-gateway",
        handlers=[ConsoleHandler()],
    )


@pytest.fixture
def gateway(logger):
    gw = ApiGateway(logger=logger)

    async def hello_handler(request):
        return SimpleResponse(
            status_code=200,
            body=b'{"message": "hello"}',
        )

    gw.register_handler("/api/hello", hello_handler)
    return gw


@pytest.mark.asyncio
async def test_gateway_success_request(gateway):
    """测试成功的请求处理"""
    request = SimpleRequest(
        method="GET",
        path="/api/hello",
        headers={},
    )

    response = await gateway.process_request(request)

    assert response.status_code == 200
    assert response.body == b'{"message": "hello"}'
    assert "X-Trace-Id" in response.headers
    assert "X-Span-Id" in response.headers


@pytest.mark.asyncio
async def test_gateway_404(gateway):
    """测试404响应"""
    request = SimpleRequest(
        method="GET",
        path="/api/nonexistent",
        headers={},
    )

    response = await gateway.process_request(request)

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_gateway_auth_middleware():
    """测试认证中间件"""
    logger = StructuredLogger(service_name="test")
    gw = ApiGateway(logger=logger)

    auth_middleware = AuthMiddleware(
        api_keys={"valid-key": "user1"},
        logger=logger,
    )
    gw.add_middleware(auth_middleware)

    async def handler(request):
        return SimpleResponse(status_code=200, body=b"ok")

    gw.register_handler("/api/protected", handler)

    request = SimpleRequest(
        method="GET",
        path="/api/protected",
        headers={"Authorization": "Bearer valid-key"},
    )
    response = await gw.process_request(request)
    assert response.status_code == 200

    request = SimpleRequest(
        method="GET",
        path="/api/protected",
        headers={},
    )
    response = await gw.process_request(request)
    assert response.status_code == 401

    request = SimpleRequest(
        method="GET",
        path="/api/protected",
        headers={"Authorization": "Bearer invalid-key"},
    )
    response = await gw.process_request(request)
    assert response.status_code == 403


@pytest.mark.asyncio
async def test_gateway_rate_limit_middleware():
    """测试限流中间件"""
    logger = StructuredLogger(service_name="test")
    gw = ApiGateway(logger=logger)

    rate_middleware = RateLimitMiddleware(
        max_requests=2,
        window_seconds=60,
        logger=logger,
    )
    gw.add_middleware(rate_middleware)

    async def handler(request):
        return SimpleResponse(status_code=200, body=b"ok")

    gw.register_handler("/api/test", handler)

    for i in range(2):
        request = SimpleRequest(
            method="GET",
            path="/api/test",
            headers={"X-Forwarded-For": "1.2.3.4"},
        )
        response = await gw.process_request(request)
        assert response.status_code == 200

    request = SimpleRequest(
        method="GET",
        path="/api/test",
        headers={"X-Forwarded-For": "1.2.3.4"},
    )
    response = await gw.process_request(request)
    assert response.status_code == 429


@pytest.mark.asyncio
async def test_gateway_trace_propagation(gateway):
    """测试链路追踪传播"""
    request = SimpleRequest(
        method="GET",
        path="/api/hello",
        headers={
            "X-Trace-Id": "existing-trace-id-123",
            "X-Parent-Span-Id": "parent-span-456",
        },
    )

    response = await gateway.process_request(request)

    assert response.headers["X-Trace-Id"] == "existing-trace-id-123"

    trace_info = gateway.get_trace_info("existing-trace-id-123")
    assert trace_info["trace_id"] == "existing-trace-id-123"
    assert len(trace_info["spans"]) >= 1
