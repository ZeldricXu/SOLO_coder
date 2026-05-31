"""
单元测试: API网关模块 - 含数据一致性保障
"""

import pytest
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.infra.logging import StructuredLogger, ConsoleHandler
from src.modules.gateway import ApiGateway
from src.modules.gateway.middleware import (
    SimpleRequest,
    SimpleResponse,
    AuthMiddleware,
    RateLimitMiddleware,
    ConsistencyGuard,
)
from src.domain.contracts.gateway import ConsistencyPolicy
from src.domain.models.gateway import ConsistencyCheckResult


@pytest.fixture
def logger():
    return StructuredLogger(service_name="test-gateway", handlers=[ConsoleHandler()])


@pytest.fixture
def gateway(logger):
    gw = ApiGateway(logger=logger, consistency_policy=ConsistencyPolicy.AT_LEAST_ONCE)

    async def hello_handler(request):
        return SimpleResponse(status_code=200, body=b'{"message": "hello"}')

    gw.register_handler("/api/hello", hello_handler)
    return gw


@pytest.mark.asyncio
async def test_gateway_success_request(gateway):
    request = SimpleRequest(method="GET", path="/api/hello", headers={})
    response = await gateway.process_request(request)
    assert response.status_code == 200
    assert "X-Trace-Id" in response.headers


@pytest.mark.asyncio
async def test_gateway_404(gateway):
    request = SimpleRequest(method="GET", path="/api/nonexistent", headers={})
    response = await gateway.process_request(request)
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_auth_middleware():
    logger = StructuredLogger(service_name="test", handlers=[ConsoleHandler()])
    gw = ApiGateway(logger=logger)
    gw.add_middleware(AuthMiddleware(api_keys={"valid-key": "user1"}, logger=logger))

    async def handler(request):
        return SimpleResponse(status_code=200, body=b"ok")

    gw.register_handler("/api/protected", handler)

    response = await gw.process_request(
        SimpleRequest(method="GET", path="/api/protected", headers={"Authorization": "Bearer valid-key"})
    )
    assert response.status_code == 200

    response = await gw.process_request(
        SimpleRequest(method="GET", path="/api/protected", headers={})
    )
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_rate_limit_middleware():
    logger = StructuredLogger(service_name="test", handlers=[ConsoleHandler()])
    gw = ApiGateway(logger=logger)
    gw.add_middleware(RateLimitMiddleware(max_requests=2, window_seconds=60, logger=logger))

    async def handler(request):
        return SimpleResponse(status_code=200, body=b"ok")

    gw.register_handler("/api/test", handler)

    for _ in range(2):
        response = await gw.process_request(
            SimpleRequest(method="GET", path="/api/test", headers={"X-Forwarded-For": "1.2.3.4"})
        )
        assert response.status_code == 200

    response = await gw.process_request(
        SimpleRequest(method="GET", path="/api/test", headers={"X-Forwarded-For": "1.2.3.4"})
    )
    assert response.status_code == 429


@pytest.mark.asyncio
async def test_trace_propagation(gateway):
    request = SimpleRequest(
        method="GET", path="/api/hello",
        headers={"X-Trace-Id": "existing-trace-123", "X-Parent-Span-Id": "parent-456"},
    )
    response = await gateway.process_request(request)
    assert response.headers["X-Trace-Id"] == "existing-trace-123"

    trace_info = gateway.get_trace_info("existing-trace-123")
    assert trace_info["trace_id"] == "existing-trace-123"


def test_consistency_check_with_idempotency_key():
    guard = ConsistencyGuard(policy=ConsistencyPolicy.EXACTLY_ONCE)
    request = SimpleRequest(
        method="POST", path="/api/orders",
        headers={"Idempotency-Key": "order-123"},
        body=b'{"item": "widget"}',
    )
    result = guard.check_consistency(request)
    assert result.consistent is True
    assert result.idempotency_key == "order-123"
    assert result.checksum is not None


def test_consistency_check_missing_key():
    guard = ConsistencyGuard(policy=ConsistencyPolicy.EXACTLY_ONCE)
    request = SimpleRequest(method="POST", path="/api/orders", body=b'{"item": "widget"}')
    result = guard.check_consistency(request)
    assert result.consistent is False
    assert len(result.violations) > 0


@pytest.mark.asyncio
async def test_idempotent_replay():
    logger = StructuredLogger(service_name="test", handlers=[ConsoleHandler()])
    gw = ApiGateway(logger=logger, consistency_policy=ConsistencyPolicy.EXACTLY_ONCE)
    gw.add_middleware(ConsistencyGuard(policy=ConsistencyPolicy.EXACTLY_ONCE, logger=logger))

    async def handler(request):
        return SimpleResponse(status_code=200, body=b"created")

    gw.register_handler("/api/orders", handler)

    request = SimpleRequest(
        method="POST", path="/api/orders",
        headers={"Idempotency-Key": "order-456"},
        body=b'{"item": "widget"}',
    )

    response1 = await gw.process_request(request)
    assert response1.status_code == 200

    response2 = await gw.process_request(request)
    assert response2.status_code == 200
    assert response2.headers.get("X-Idempotent-Replay") == "true"


def test_consistency_policy_levels():
    for policy in ConsistencyPolicy:
        guard = ConsistencyGuard(policy=policy)
        request = SimpleRequest(method="GET", path="/api/test")
        result = guard.check_consistency(request)
        assert isinstance(result, ConsistencyCheckResult)
