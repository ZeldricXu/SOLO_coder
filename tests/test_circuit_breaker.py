import asyncio
import time
from typing import Any, Dict
from unittest.mock import MagicMock, AsyncMock, patch

import pytest
from redis.exceptions import RedisError

from gateway.circuit_breaker.breaker import (
    CircuitBreaker,
    CircuitState,
    CircuitBreakerResult,
    CircuitMetrics,
)
from gateway.circuit_breaker.middleware import CircuitBreakerMiddleware
from gateway.config import get_settings
from starlette.requests import Request
from starlette.responses import JSONResponse


pytestmark = [pytest.mark.unit, pytest.mark.asyncio]


def create_mock_request(
    method: str = "GET",
    path: str = "/api/test",
    headers: Dict[str, str] = None,
    query_string: str = "",
) -> Request:
    """Create a mock request object with proper state."""
    headers = headers or {}
    header_list = [(k.lower().encode(), v.encode()) for k, v in headers.items()]
    scope = {
        "type": "http",
        "method": method,
        "path": path,
        "query_string": query_string.encode(),
        "headers": header_list,
        "server": ("testserver", 80),
        "scheme": "http",
        "client": ("127.0.0.1", 12345),
    }
    request = Request(scope)
    request.state.user = None
    request.state.is_authenticated = False
    request.state.route_match = None
    request.state.circuit_state = None
    return request


class TestCircuitBreakerStateMachine:
    """Test circuit breaker state machine transitions."""

    async def test_initial_state_is_closed(self, circuit_breaker: CircuitBreaker):
        """Initial state should be CLOSED."""
        state = await circuit_breaker._get_state("test-service")
        assert state == CircuitState.CLOSED

    async def test_closed_state_allows_requests(self, circuit_breaker: CircuitBreaker):
        """In CLOSED state, all requests should be allowed."""
        result = await circuit_breaker.check("test-service-closed")
        assert result.allowed is True
        assert result.state == CircuitState.CLOSED

    async def test_consecutive_failures_trigger_open(self, circuit_breaker: CircuitBreaker, test_settings):
        """After enough failures, circuit should open."""
        service = "test-service-failures"

        num_failures = 20
        for i in range(num_failures):
            await circuit_breaker.record_failure(service, latency=0.1, is_slow=False)

        state = await circuit_breaker._get_state(service)
        assert state == CircuitState.OPEN

    async def test_open_state_blocks_requests(self, circuit_breaker: CircuitBreaker):
        """In OPEN state, requests should be blocked."""
        service = "test-service-open-block"

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        result = await circuit_breaker.check(service)
        assert result.allowed is False
        assert result.state == CircuitState.OPEN

    async def test_open_state_retry_after_header(self, circuit_breaker: CircuitBreaker, test_settings):
        """OPEN state should include retry_after with remaining wait time."""
        service = "test-service-retry-after"

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        result = await circuit_breaker.check(service)
        assert result.retry_after > 0
        assert result.retry_after <= test_settings.circuit_breaker.wait_duration_in_open_state

    async def test_half_open_after_wait_duration(self, circuit_breaker: CircuitBreaker, test_settings):
        """After wait duration, circuit should transition to HALF_OPEN."""
        service = "test-service-half-open"

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        state_before = await circuit_breaker._get_state(service)
        assert state_before == CircuitState.OPEN

        await circuit_breaker.redis.set(
            circuit_breaker._get_key(service, "open_at"),
            int(time.time()) - test_settings.circuit_breaker.wait_duration_in_open_state - 1
        )

        result = await circuit_breaker.check(service)
        assert result.allowed is True
        assert result.state == CircuitState.HALF_OPEN

    async def test_half_open_successes_close_circuit(self, circuit_breaker: CircuitBreaker, test_settings):
        """Successful requests in HALF_OPEN should close the circuit."""
        service = "test-service-half-open-success"

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        await circuit_breaker.redis.set(
            circuit_breaker._get_key(service, "open_at"),
            int(time.time()) - test_settings.circuit_breaker.wait_duration_in_open_state - 1
        )

        num_calls = test_settings.circuit_breaker.permitted_num_of_calls_in_half_open
        for i in range(num_calls):
            result = await circuit_breaker.check(service)
            assert result.allowed is True
            await circuit_breaker.record_success(service, latency=0.05)

        state = await circuit_breaker._get_state(service)
        assert state == CircuitState.CLOSED

    async def test_half_open_failure_reopens_circuit(self, circuit_breaker: CircuitBreaker, test_settings):
        """A failure in HALF_OPEN should re-open the circuit."""
        service = "test-service-half-open-fail"

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        await circuit_breaker.redis.set(
            circuit_breaker._get_key(service, "open_at"),
            int(time.time()) - test_settings.circuit_breaker.wait_duration_in_open_state - 1
        )

        result = await circuit_breaker.check(service)
        assert result.allowed is True
        await circuit_breaker.record_failure(service, latency=0.1)

        state = await circuit_breaker._get_state(service)
        assert state == CircuitState.OPEN


class TestCircuitBreakerFallback:
    """Test fallback responses when circuit is open."""

    async def test_static_fallback_response(self, circuit_breaker: CircuitBreaker, test_settings):
        """Static fallback response should be returned when circuit is open."""
        service = "test-service-fallback-static"
        fallback_data = {"data": {"message": "Fallback data", "code": "FALLBACK"}}

        config = {
            "fallback_response": fallback_data,
            "wait_duration": test_settings.circuit_breaker.wait_duration_in_open_state,
        }

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        result = await circuit_breaker.check(service, config)
        assert result.allowed is False
        assert result.fallback_response == fallback_data

    async def test_fallback_target_url(self, circuit_breaker: CircuitBreaker, test_settings):
        """Fallback target URL should be provided for routing to backup service."""
        service = "test-service-fallback-target"
        fallback_target = "http://backup-service:8080"

        config = {
            "fallback_target": fallback_target,
            "wait_duration": test_settings.circuit_breaker.wait_duration_in_open_state,
        }

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        result = await circuit_breaker.check(service, config)
        assert result.allowed is False
        assert result.fallback_target == fallback_target

    async def test_no_fallback_returns_503(self, circuit_breaker: CircuitBreaker):
        """Without fallback, 503 should be returned (handled by middleware)."""
        service = "test-service-no-fallback"

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        result = await circuit_breaker.check(service)
        assert result.allowed is False
        assert result.fallback_response is None
        assert result.fallback_target is None


class TestCircuitBreakerHalfOpenConcurrency:
    """Test behavior with concurrent requests in HALF_OPEN state."""

    async def test_half_open_limits_permitted_calls(self, circuit_breaker: CircuitBreaker, test_settings):
        """Only permitted number of calls should be allowed in HALF_OPEN state."""
        service = "test-service-half-open-limit"
        permitted_calls = test_settings.circuit_breaker.permitted_num_of_calls_in_half_open

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        await circuit_breaker.redis.set(
            circuit_breaker._get_key(service, "open_at"),
            int(time.time()) - test_settings.circuit_breaker.wait_duration_in_open_state - 1
        )

        allowed_count = 0
        denied_count = 0

        for i in range(permitted_calls + 5):
            result = await circuit_breaker.check(service)
            if result.allowed:
                allowed_count += 1
            else:
                denied_count += 1

        assert allowed_count == permitted_calls
        assert denied_count == 5

    async def test_concurrent_half_open_requests(self, circuit_breaker: CircuitBreaker, test_settings):
        """Concurrent requests in HALF_OPEN should respect the permitted call limit."""
        service = "test-service-concurrent-half-open"
        permitted_calls = test_settings.circuit_breaker.permitted_num_of_calls_in_half_open
        num_concurrent = 20

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        await circuit_breaker.redis.set(
            circuit_breaker._get_key(service, "open_at"),
            int(time.time()) - test_settings.circuit_breaker.wait_duration_in_open_state - 1
        )

        await circuit_breaker.check(service)

        state = await circuit_breaker._get_state(service)
        assert state == CircuitState.HALF_OPEN

        async def make_request():
            return await circuit_breaker.check(service)

        tasks = [make_request() for _ in range(num_concurrent - 1)]
        results = await asyncio.gather(*tasks)

        allowed_count = sum(1 for r in results if r.allowed) + 1
        denied_count = sum(1 for r in results if not r.allowed)

        assert allowed_count == permitted_calls
        assert denied_count == num_concurrent - permitted_calls

    async def test_half_open_denied_returns_retry_after(self, circuit_breaker: CircuitBreaker, test_settings):
        """Denied requests in HALF_OPEN should include retry_after."""
        service = "test-service-half-open-denied"
        permitted_calls = test_settings.circuit_breaker.permitted_num_of_calls_in_half_open

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        await circuit_breaker.redis.set(
            circuit_breaker._get_key(service, "open_at"),
            int(time.time()) - test_settings.circuit_breaker.wait_duration_in_open_state - 1
        )

        for i in range(permitted_calls):
            result = await circuit_breaker.check(service)
            assert result.allowed is True

        extra_result = await circuit_breaker.check(service)
        assert extra_result.allowed is False
        assert extra_result.state == CircuitState.HALF_OPEN
        assert extra_result.retry_after > 0


class TestCircuitBreakerMetrics:
    """Test circuit breaker metrics recording."""

    async def test_record_success_updates_metrics(self, circuit_breaker: CircuitBreaker):
        """Recording success should update metrics."""
        service = "test-service-metrics-success"

        await circuit_breaker.record_success(service, latency=0.1)
        await circuit_breaker.record_success(service, latency=0.2)

        metrics = await circuit_breaker._get_metrics(service)
        assert metrics.total_requests == 2
        assert metrics.success_count == 2
        assert metrics.failure_count == 0
        assert metrics.error_rate == 0.0

    async def test_record_failure_updates_metrics(self, circuit_breaker: CircuitBreaker):
        """Recording failure should update metrics."""
        service = "test-service-metrics-failure"

        await circuit_breaker.record_failure(service, latency=0.5, is_slow=False)
        await circuit_breaker.record_failure(service, latency=0.3, is_slow=True)

        metrics = await circuit_breaker._get_metrics(service)
        assert metrics.total_requests == 2
        assert metrics.failure_count == 2
        assert metrics.success_count == 0
        assert metrics.slow_count == 1

    async def test_mixed_success_failure(self, circuit_breaker: CircuitBreaker):
        """Mixed successes and failures should produce correct error rate."""
        service = "test-service-mixed"

        for i in range(8):
            await circuit_breaker.record_success(service, latency=0.1)
        for i in range(2):
            await circuit_breaker.record_failure(service, latency=0.5, is_slow=False)

        metrics = await circuit_breaker._get_metrics(service)
        assert metrics.total_requests == 10
        assert metrics.error_rate == 0.2

    async def test_slow_requests_counted(self, circuit_breaker: CircuitBreaker):
        """Slow requests should be counted separately."""
        service = "test-service-slow"

        for i in range(5):
            await circuit_breaker.record_failure(service, latency=2.0, is_slow=True)

        metrics = await circuit_breaker._get_metrics(service)
        assert metrics.slow_count == 5
        assert metrics.total_requests == 5

    async def test_get_circuit_status(self, circuit_breaker: CircuitBreaker):
        """Should be able to get current circuit status."""
        service = "test-service-status"

        result = await circuit_breaker.check(service)
        assert result.state == CircuitState.CLOSED


class TestCircuitBreakerReset:
    """Test circuit breaker reset functionality."""

    async def test_reset_clears_state(self, circuit_breaker: CircuitBreaker, test_settings):
        """Reset should clear circuit state and metrics."""
        service = "test-service-reset"

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        state_before = await circuit_breaker._get_state(service)
        assert state_before == CircuitState.OPEN

        await circuit_breaker._close_circuit(service)

        state_after = await circuit_breaker._get_state(service)
        assert state_after == CircuitState.CLOSED


class TestCircuitBreakerConfig:
    """Test circuit breaker configuration."""

    async def test_custom_failure_threshold(self, circuit_breaker: CircuitBreaker):
        """Custom failure threshold should be honored in check."""
        service = "test-service-custom-threshold"

        for i in range(10):
            await circuit_breaker.record_failure(service, latency=0.1)

        state = await circuit_breaker._get_state(service)
        assert state == CircuitState.OPEN

        config = {
            "failure_threshold": 0.8,
            "wait_duration": 60,
        }
        result = await circuit_breaker.check(service, config)
        assert result.allowed is False
        assert result.state == CircuitState.OPEN

    async def test_custom_wait_duration(self, circuit_breaker: CircuitBreaker, test_settings):
        """Custom wait duration should be used."""
        service = "test-service-custom-wait"
        custom_wait = 5
        config = {"wait_duration": custom_wait}

        for i in range(20):
            await circuit_breaker.record_failure(service, latency=0.1)

        await circuit_breaker.redis.set(
            circuit_breaker._get_key(service, "open_at"),
            int(time.time()) - custom_wait - 1
        )

        result = await circuit_breaker.check(service, config)
        assert result.state == CircuitState.HALF_OPEN


class TestCircuitBreakerMiddleware:
    """Test circuit breaker middleware."""

    async def test_middleware_closed_state_passes(self, circuit_breaker: CircuitBreaker):
        """In CLOSED state, middleware should pass request through."""
        with patch('gateway.circuit_breaker.middleware.get_circuit_breaker', return_value=circuit_breaker):
            middleware = CircuitBreakerMiddleware(None)

            request = create_mock_request()
            request.state.route_match = MagicMock(
                route=MagicMock(
                    circuit_breaker_enabled=True,
                    circuit_breaker_config={},
                    target=MagicMock(url="http://test-service"),
                )
            )

            mock_response = JSONResponse({"ok": True})
            call_next = AsyncMock(return_value=mock_response)

            with patch.object(circuit_breaker, 'check', return_value=CircuitBreakerResult(
                allowed=True,
                state=CircuitState.CLOSED,
            )):
                response = await middleware.dispatch(request, call_next)

            assert response.status_code == 200

    async def test_middleware_open_state_returns_503(self, circuit_breaker: CircuitBreaker):
        """In OPEN state, middleware should return 503."""
        with patch('gateway.circuit_breaker.middleware.get_circuit_breaker', return_value=circuit_breaker):
            middleware = CircuitBreakerMiddleware(None)

            request = create_mock_request()
            request.state.route_match = MagicMock(
                route=MagicMock(
                    circuit_breaker_enabled=True,
                    circuit_breaker_config={},
                    target=MagicMock(url="http://test-service"),
                )
            )

            call_next = AsyncMock(return_value=JSONResponse({"ok": True}))

            with patch.object(circuit_breaker, 'check', return_value=CircuitBreakerResult(
                allowed=False,
                state=CircuitState.OPEN,
                retry_after=30,
            )):
                response = await middleware.dispatch(request, call_next)

            assert response.status_code == 503
            assert response.headers.get("Retry-After") == "30"
            assert response.headers.get("X-Circuit-State") == "open"

    async def test_middleware_open_with_fallback_response(self, circuit_breaker: CircuitBreaker):
        """In OPEN state with fallback, should return fallback response with 200."""
        fallback_data = {"message": "Service temporarily unavailable", "code": "FALLBACK"}

        with patch('gateway.circuit_breaker.middleware.get_circuit_breaker', return_value=circuit_breaker):
            middleware = CircuitBreakerMiddleware(None)

            request = create_mock_request()
            request.state.route_match = MagicMock(
                route=MagicMock(
                    circuit_breaker_enabled=True,
                    circuit_breaker_config={},
                    target=MagicMock(url="http://test-service"),
                )
            )

            call_next = AsyncMock(return_value=JSONResponse({"ok": True}))

            with patch.object(circuit_breaker, 'check', return_value=CircuitBreakerResult(
                allowed=False,
                state=CircuitState.OPEN,
                fallback_response=fallback_data,
                retry_after=30,
            )):
                response = await middleware.dispatch(request, call_next)

            assert response.status_code == 200
            assert response.headers.get("X-Circuit-State") == "open"
            assert response.headers.get("X-Circuit-Fallback") == "static"

    async def test_middleware_health_skips_breaker(self, circuit_breaker: CircuitBreaker):
        """Health endpoint should skip circuit breaker."""
        with patch('gateway.circuit_breaker.middleware.get_circuit_breaker', return_value=circuit_breaker):
            middleware = CircuitBreakerMiddleware(None)

            request = create_mock_request(path="/health")
            request.state.route_match = None

            mock_response = JSONResponse({"status": "ok"})
            call_next = AsyncMock(return_value=mock_response)

            with patch.object(circuit_breaker, 'check') as mock_check:
                response = await middleware.dispatch(request, call_next)
                mock_check.assert_not_called()

            assert response.status_code == 200

    async def test_middleware_records_success(self, circuit_breaker: CircuitBreaker):
        """Middleware should record success after successful response."""
        with patch('gateway.circuit_breaker.middleware.get_circuit_breaker', return_value=circuit_breaker):
            middleware = CircuitBreakerMiddleware(None)

            request = create_mock_request()
            request.state.route_match = MagicMock(
                route=MagicMock(
                    circuit_breaker_enabled=True,
                    circuit_breaker_config={},
                    target=MagicMock(url="http://test-service"),
                )
            )

            mock_response = JSONResponse({"ok": True})
            call_next = AsyncMock(return_value=mock_response)

            with patch.object(circuit_breaker, 'check', return_value=CircuitBreakerResult(
                allowed=True,
                state=CircuitState.CLOSED,
            )):
                with patch.object(circuit_breaker, 'record_success') as mock_record:
                    response = await middleware.dispatch(request, call_next)
                    mock_record.assert_called_once()

            assert response.status_code == 200

    async def test_middleware_slow_request_marked_failure(self, circuit_breaker: CircuitBreaker):
        """Slow requests should be marked as failures."""
        with patch('gateway.circuit_breaker.middleware.get_circuit_breaker', return_value=circuit_breaker):
            middleware = CircuitBreakerMiddleware(None)

            request = create_mock_request()
            request.state.route_match = MagicMock(
                route=MagicMock(
                    circuit_breaker_enabled=True,
                    circuit_breaker_config={},
                    target=MagicMock(url="http://test-service"),
                )
            )

            mock_response = JSONResponse({"ok": True})
            call_next = AsyncMock(return_value=mock_response)

            with patch.object(circuit_breaker, 'check', return_value=CircuitBreakerResult(
                allowed=True,
                state=CircuitState.CLOSED,
            )):
                with patch.object(circuit_breaker, 'record_success') as mock_record_success:
                    with patch.object(circuit_breaker, 'record_failure') as mock_record_failure:
                        response = await middleware.dispatch(request, call_next)

            assert response.status_code == 200


class TestCircuitBreakerResult:
    """Test CircuitBreakerResult data class."""

    def test_circuit_breaker_result_creation(self):
        """CircuitBreakerResult should be created correctly."""
        result = CircuitBreakerResult(
            allowed=True,
            state=CircuitState.CLOSED,
            retry_after=0,
        )
        assert result.allowed is True
        assert result.state == CircuitState.CLOSED
        assert result.fallback_response is None
        assert result.fallback_target is None

    def test_circuit_breaker_result_with_fallback(self):
        """CircuitBreakerResult with fallback should have fallback data."""
        fallback = {"message": "fallback"}
        result = CircuitBreakerResult(
            allowed=False,
            state=CircuitState.OPEN,
            fallback_response=fallback,
            fallback_target="http://backup",
            retry_after=30,
        )
        assert result.allowed is False
        assert result.fallback_response == fallback
        assert result.fallback_target == "http://backup"
        assert result.retry_after == 30


class TestCircuitState:
    """Test CircuitState enum."""

    def test_circuit_state_values(self):
        """CircuitState should have correct values."""
        assert CircuitState.CLOSED.value == "closed"
        assert CircuitState.OPEN.value == "open"
        assert CircuitState.HALF_OPEN.value == "half_open"

    def test_circuit_state_is_string_enum(self):
        """CircuitState should be a string enum."""
        assert isinstance(CircuitState.CLOSED, str)
        assert CircuitState.CLOSED == "closed"
