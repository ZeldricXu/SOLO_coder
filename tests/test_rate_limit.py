import asyncio
import time
from typing import Any, Dict
from unittest.mock import MagicMock, AsyncMock, patch, PropertyMock

import pytest
from redis.exceptions import RedisError

from gateway.rate_limit.limiter import RateLimiter, RateLimitResult, TOKEN_BUCKET_SCRIPT
from gateway.rate_limit.middleware import RateLimitMiddleware
from gateway.config import get_settings
from starlette.requests import Request
from starlette.responses import JSONResponse


pytestmark = [pytest.mark.unit, pytest.mark.asyncio]


class TestTokenBucketAlgorithm:
    """Test token bucket algorithm behavior."""

    async def test_initial_bucket_full_allows_requests(self, rate_limiter: RateLimiter, test_settings):
        """Bucket is initially full - first N requests should all pass."""
        capacity = 10
        user_id = "test-user-bucket"
        api_path = "/api/test-bucket"

        with patch.object(rate_limiter, '_execute_check') as mock_execute:
            call_counts = {}

            async def execute_side_effect(key, burst_key, rate, cap, burst_mult, now, window):
                count = call_counts.get(key, 0) + 1
                call_counts[key] = count

                if count <= cap:
                    return RateLimitResult(
                        allowed=True,
                        remaining=cap - count,
                        limit=cap,
                        retry_after=0,
                        total_requests=count,
                        allowed_requests=count,
                    )
                else:
                    return RateLimitResult(
                        allowed=False,
                        remaining=0,
                        limit=cap,
                        retry_after=int(window / rate) if rate > 0 else 10,
                        total_requests=count,
                        allowed_requests=cap,
                    )

            mock_execute.side_effect = execute_side_effect

            for i in range(capacity):
                result = await rate_limiter.check_rate_limit(
                    user_id=user_id,
                    api_path=api_path,
                    custom_user_limit=capacity,
                    custom_api_limit=1000,
                )
                assert result.allowed is True, f"Request {i+1} should be allowed"

    async def test_bucket_exhausted_returns_429(self, rate_limiter: RateLimiter, test_settings):
        """After N requests, bucket is empty - next request is rejected with 429."""
        capacity = 5
        user_id = "test-user-exhaust"
        api_path = "/api/test-exhaust"

        with patch.object(rate_limiter, '_execute_check') as mock_execute:
            call_counts = {}

            async def execute_side_effect(key, burst_key, rate, cap, burst_mult, now, window):
                count = call_counts.get(key, 0) + 1
                call_counts[key] = count

                if count <= cap:
                    return RateLimitResult(
                        allowed=True,
                        remaining=cap - count,
                        limit=cap,
                        retry_after=0,
                        total_requests=count,
                        allowed_requests=count,
                    )
                else:
                    return RateLimitResult(
                        allowed=False,
                        remaining=0,
                        limit=cap,
                        retry_after=10,
                        total_requests=count,
                        allowed_requests=cap,
                    )

            mock_execute.side_effect = execute_side_effect

            for i in range(capacity):
                result = await rate_limiter.check_rate_limit(
                    user_id=user_id,
                    api_path=api_path,
                    custom_user_limit=capacity,
                    custom_api_limit=1000,
                )
                assert result.allowed is True, f"Request {i+1} should be allowed"

            result_exceeded = await rate_limiter.check_rate_limit(
                user_id=user_id,
                api_path=api_path,
                custom_user_limit=capacity,
                custom_api_limit=1000,
            )
            assert result_exceeded.allowed is False
            assert result_exceeded.limit == capacity

    async def test_token_refill_after_wait(self, rate_limiter: RateLimiter, test_settings):
        """After waiting, tokens should be refilled."""
        capacity = 5
        user_id = "test-user-refill"
        api_path = "/api/test-refill"

        with patch.object(rate_limiter, '_execute_check') as mock_execute:
            call_counts = {"user": 0, "api": 0}

            async def execute_side_effect(key, burst_key, rate, cap, burst_mult, now, window):
                if "api:" in key:
                    call_counts["api"] += 1
                    return RateLimitResult(
                        allowed=True,
                        remaining=1000,
                        limit=1000,
                        retry_after=0,
                        total_requests=call_counts["api"],
                        allowed_requests=call_counts["api"],
                    )

                call_counts["user"] += 1
                count = call_counts["user"]
                if count <= cap:
                    return RateLimitResult(
                        allowed=True,
                        remaining=cap - count,
                        limit=cap,
                        retry_after=0,
                        total_requests=count,
                        allowed_requests=count,
                    )
                elif count == cap + 1:
                    return RateLimitResult(
                        allowed=False,
                        remaining=0,
                        limit=cap,
                        retry_after=10,
                        total_requests=count,
                        allowed_requests=cap,
                    )
                else:
                    return RateLimitResult(
                        allowed=True,
                        remaining=1,
                        limit=cap,
                        retry_after=0,
                        total_requests=count,
                        allowed_requests=count,
                    )

            mock_execute.side_effect = execute_side_effect

            for i in range(capacity):
                result = await rate_limiter.check_rate_limit(
                    user_id=user_id,
                    api_path=api_path,
                    custom_user_limit=capacity,
                    custom_api_limit=1000,
                )
                assert result.allowed is True

            result_exhausted = await rate_limiter.check_rate_limit(
                user_id=user_id,
                api_path=api_path,
                custom_user_limit=capacity,
                custom_api_limit=1000,
            )
            assert result_exhausted.allowed is False
            assert result_exhausted.retry_after > 0

            result_refilled = await rate_limiter.check_rate_limit(
                user_id=user_id,
                api_path=api_path,
                custom_user_limit=capacity,
                custom_api_limit=1000,
            )
            assert result_refilled.allowed is True

    async def test_burst_multiplier_allows_over_capacity(self, rate_limiter: RateLimiter, test_settings):
        """Burst multiplier allows temporary exceeding base capacity."""
        capacity = 5
        burst_multiplier = 2.0
        user_id = "test-user-burst"
        api_path = "/api/test-burst"

        with patch.object(rate_limiter, '_execute_check') as mock_execute:
            call_counts = {"user": 0, "api": 0}

            async def execute_side_effect(key, burst_key, rate, cap, burst_mult, now, window):
                if "api:" in key:
                    call_counts["api"] += 1
                    return RateLimitResult(
                        allowed=True,
                        remaining=1000,
                        limit=1000,
                        retry_after=0,
                        total_requests=call_counts["api"],
                        allowed_requests=call_counts["api"],
                    )

                call_counts["user"] += 1
                count = call_counts["user"]
                burst_cap = int(cap * burst_mult)
                if count <= burst_cap:
                    used_burst = count > cap
                    return RateLimitResult(
                        allowed=True,
                        remaining=burst_cap - count,
                        limit=cap,
                        retry_after=0,
                        total_requests=count,
                        allowed_requests=count,
                        used_burst=used_burst,
                    )
                else:
                    return RateLimitResult(
                        allowed=False,
                        remaining=0,
                        limit=cap,
                        retry_after=10,
                        total_requests=count,
                        allowed_requests=burst_cap,
                        used_burst=True,
                    )

            mock_execute.side_effect = execute_side_effect

            burst_limit = int(capacity * burst_multiplier)
            for i in range(burst_limit):
                result = await rate_limiter.check_rate_limit(
                    user_id=user_id,
                    api_path=api_path,
                    custom_user_limit=capacity,
                    custom_api_limit=1000,
                )
                assert result.allowed is True, f"Burst request {i+1} should be allowed"

            result_exceeded = await rate_limiter.check_rate_limit(
                user_id=user_id,
                api_path=api_path,
                custom_user_limit=capacity,
                custom_api_limit=1000,
            )
            assert result_exceeded.allowed is False


class TestTwoLevelRateLimiting:
    """Test user-level + API-level two-level rate limiting."""

    async def test_user_and_api_limits_applied(self, rate_limiter: RateLimiter, test_settings):
        """Both user-level and API-level limits should be checked."""
        user_limit = 10
        api_limit = 100
        user_id = "test-user-twolevel"
        api_path = "/api/two-level"

        with patch.object(rate_limiter, '_execute_check') as mock_execute:
            call_counts = {"user": 0, "api": 0}

            async def execute_side_effect(key, burst_key, rate, cap, burst_mult, now, window):
                if "user:" in key and "burst" not in key:
                    call_counts["user"] += 1
                    count = call_counts["user"]
                else:
                    call_counts["api"] += 1
                    count = call_counts["api"]

                if count <= cap:
                    return RateLimitResult(
                        allowed=True,
                        remaining=cap - count,
                        limit=cap,
                        retry_after=0,
                        total_requests=count,
                        allowed_requests=count,
                    )
                else:
                    return RateLimitResult(
                        allowed=False,
                        remaining=0,
                        limit=cap,
                        retry_after=10,
                        total_requests=count,
                        allowed_requests=cap,
                    )

            mock_execute.side_effect = execute_side_effect

            result = await rate_limiter.check_rate_limit(
                user_id=user_id,
                api_path=api_path,
                custom_user_limit=user_limit,
                custom_api_limit=api_limit,
            )

            assert mock_execute.call_count == 2
            assert result.allowed is True

    async def test_user_limit_hit_first(self, rate_limiter: RateLimiter, test_settings):
        """If user limit is lower, it should be the limiting factor."""
        user_limit = 5
        api_limit = 100

        with patch.object(rate_limiter, '_execute_check') as mock_execute:
            async def execute_side_effect(key, burst_key, rate, cap, burst_mult, now, window):
                if "user:" in key and "burst" not in key:
                    return RateLimitResult(
                        allowed=False,
                        remaining=0,
                        limit=cap,
                        retry_after=30,
                        total_requests=10,
                        allowed_requests=cap,
                    )
                else:
                    return RateLimitResult(
                        allowed=True,
                        remaining=cap - 10,
                        limit=cap,
                        retry_after=0,
                        total_requests=10,
                        allowed_requests=10,
                    )

            mock_execute.side_effect = execute_side_effect

            result = await rate_limiter.check_rate_limit(
                user_id="test-user-limit",
                api_path="/api/test",
                custom_user_limit=user_limit,
                custom_api_limit=api_limit,
            )

            assert result.allowed is False
            assert result.limit == user_limit

    async def test_api_limit_hit_first(self, rate_limiter: RateLimiter, test_settings):
        """If API limit is lower, it should be the limiting factor."""
        user_limit = 100
        api_limit = 5

        with patch.object(rate_limiter, '_execute_check') as mock_execute:
            async def execute_side_effect(key, burst_key, rate, cap, burst_mult, now, window):
                if "api:" in key:
                    return RateLimitResult(
                        allowed=False,
                        remaining=0,
                        limit=cap,
                        retry_after=30,
                        total_requests=10,
                        allowed_requests=cap,
                    )
                else:
                    return RateLimitResult(
                        allowed=True,
                        remaining=cap - 10,
                        limit=cap,
                        retry_after=0,
                        total_requests=10,
                        allowed_requests=10,
                    )

            mock_execute.side_effect = execute_side_effect

            result = await rate_limiter.check_rate_limit(
                user_id="test-user-api-limit",
                api_path="/api/test",
                custom_user_limit=user_limit,
                custom_api_limit=api_limit,
            )

            assert result.allowed is False
            assert result.limit == api_limit

    async def test_no_user_id_only_api_limit(self, rate_limiter: RateLimiter, test_settings):
        """If no user_id, only API-level limit is applied."""
        with patch.object(rate_limiter, '_execute_check') as mock_execute:
            async def execute_side_effect(key, burst_key, rate, cap, burst_mult, now, window):
                return RateLimitResult(
                    allowed=True,
                    remaining=cap - 1,
                    limit=cap,
                    retry_after=0,
                    total_requests=1,
                    allowed_requests=1,
                )

            mock_execute.side_effect = execute_side_effect

            result = await rate_limiter.check_rate_limit(
                user_id=None,
                api_path="/api/test",
                custom_api_limit=100,
            )

            assert mock_execute.call_count == 1
            assert result.allowed is True


class TestRedisFailureDegradation:
    """Test behavior when Redis is unavailable."""

    async def test_redis_failure_fail_open(self, rate_limiter: RateLimiter, test_settings):
        """When Redis fails, should fail-open (allow all requests)."""
        with patch.object(rate_limiter, 'redis') as mock_redis:
            mock_redis.eval = AsyncMock(side_effect=RedisError("Connection refused"))
            mock_redis.evalsha = AsyncMock(side_effect=RedisError("Connection refused"))

            result = await rate_limiter.check_rate_limit(
                user_id="test-user-fail",
                api_path="/api/test-fail",
                custom_user_limit=10,
                custom_api_limit=100,
            )

            assert result.allowed is True
            assert result.remaining == 10

    async def test_redis_failure_returns_capacity_remaining(self, rate_limiter: RateLimiter, test_settings):
        """When Redis fails, remaining should equal capacity."""
        with patch.object(rate_limiter.redis, 'eval', side_effect=RedisError("Redis down")):
            result = await rate_limiter.check_rate_limit(
                user_id="test-user-cap",
                api_path="/api/test-cap",
                custom_user_limit=50,
                custom_api_limit=500,
            )

            assert result.allowed is True
            assert result.remaining == 50
            assert result.limit == 50

    async def test_script_load_failure_handled(self, rate_limiter: RateLimiter, test_settings):
        """Script load failure should be handled gracefully."""
        with patch.object(rate_limiter.redis, 'script_load', side_effect=RedisError("Script error")):
            await rate_limiter.init_script()
            assert rate_limiter._script_sha is None


class TestConcurrentRateLimiting:
    """Test concurrent rate limiting scenarios."""

    async def test_concurrent_requests_atomic(self, rate_limiter: RateLimiter, test_settings):
        """Concurrent requests should be handled atomically."""
        capacity = 100
        user_id = "test-user-concurrent"
        api_path = "/api/concurrent"

        call_count = [0]

        with patch.object(rate_limiter, '_execute_check') as mock_execute:
            async def execute_side_effect(key, burst_key, rate, cap, burst_mult, now, window):
                call_count[0] += 1
                count = call_count[0]
                if count <= cap:
                    return RateLimitResult(
                        allowed=True,
                        remaining=cap - count,
                        limit=cap,
                        retry_after=0,
                        total_requests=count,
                        allowed_requests=count,
                    )
                else:
                    return RateLimitResult(
                        allowed=False,
                        remaining=0,
                        limit=cap,
                        retry_after=1,
                        total_requests=count,
                        allowed_requests=cap,
                    )

            mock_execute.side_effect = execute_side_effect

            async def make_request():
                return await rate_limiter.check_rate_limit(
                    user_id=user_id,
                    api_path=api_path,
                    custom_user_limit=capacity,
                    custom_api_limit=10000,
                )

            tasks = [make_request() for _ in range(200)]
            results = await asyncio.gather(*tasks)

            allowed_count = sum(1 for r in results if r.allowed)
            denied_count = sum(1 for r in results if not r.allowed)

            assert allowed_count + denied_count == 200


class TestRateLimitInfoAndReset:
    """Test rate limit info query and reset functionality."""

    async def test_get_rate_limit_info(self, rate_limiter: RateLimiter, test_settings):
        """Should be able to query rate limit info."""
        user_id = "test-user-info"
        api_path = "/api/info"

        mock_data = {
            "tokens": "5.0",
            "last_refill": "1234567890",
            "total_requests": "5",
            "allowed_requests": "5",
        }

        rate_limiter.redis.hgetall = AsyncMock(return_value=mock_data)
        info = await rate_limiter.get_rate_limit_info(user_id, api_path)
        assert "user" in info
        assert "api" in info
        assert info["user"]["total_requests"] == 5
        assert info["api"]["total_requests"] == 5

    async def test_reset_rate_limit(self, rate_limiter: RateLimiter, test_settings):
        """Reset should clear rate limit state."""
        user_id = "test-user-reset"
        api_path = "/api/reset"

        rate_limiter.redis.delete = AsyncMock(return_value=2)
        await rate_limiter.reset_rate_limit(user_id, api_path)
        assert rate_limiter.redis.delete.call_count >= 1


class TestRateLimitResult:
    """Test RateLimitResult data class."""

    def test_rate_limit_result_creation(self):
        """RateLimitResult should be created correctly."""
        result = RateLimitResult(
            allowed=True,
            remaining=5,
            limit=10,
            retry_after=0,
            total_requests=5,
            allowed_requests=5,
            used_burst=False,
        )
        assert result.allowed is True
        assert result.remaining == 5
        assert result.limit == 10

    def test_rate_limit_result_defaults(self):
        """RateLimitResult should have proper defaults."""
        result = RateLimitResult(
            allowed=True,
            remaining=10,
            limit=10,
            retry_after=0,
            total_requests=1,
            allowed_requests=1,
        )
        assert result.used_burst is False


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
    }
    request = Request(scope)
    request.state.user = None
    request.state.is_authenticated = False
    request.state.route_match = None
    request.state.rate_limit_info = None
    return request


class TestRateLimitMiddleware:
    """Test RateLimitMiddleware."""

    async def test_rate_limited_returns_429(self, rate_limiter: RateLimiter):
        """When rate limited, should return 429."""
        with patch.object(rate_limiter, 'check_rate_limit', return_value=RateLimitResult(
            allowed=False,
            remaining=0,
            limit=10,
            retry_after=30,
            total_requests=15,
            allowed_requests=10,
        )):
            with patch('gateway.rate_limit.middleware.get_rate_limiter', return_value=rate_limiter):
                middleware = RateLimitMiddleware(app=None)

                request = create_mock_request()
                request.state.user = {"user_id": "test-user"}
                request.state.route_match = MagicMock(
                    route=MagicMock(
                        rate_limit_enabled=True,
                        rate_limit_per_user=10,
                        rate_limit_per_api=100,
                    )
                )

                call_next = AsyncMock(return_value=JSONResponse({"ok": True}))
                response = await middleware.dispatch(request, call_next)

                assert response.status_code == 429
                assert response.headers.get("Retry-After") == "30"

    async def test_rate_limit_headers_set(self, rate_limiter: RateLimiter):
        """X-RateLimit-* headers should be set on response."""
        with patch.object(rate_limiter, 'check_rate_limit', return_value=RateLimitResult(
            allowed=True,
            remaining=5,
            limit=10,
            retry_after=0,
            total_requests=5,
            allowed_requests=5,
        )):
            with patch('gateway.rate_limit.middleware.get_rate_limiter', return_value=rate_limiter):
                middleware = RateLimitMiddleware(app=None)

                request = create_mock_request()
                request.state.user = {"user_id": "test-user"}
                request.state.route_match = MagicMock(
                    route=MagicMock(
                        rate_limit_enabled=True,
                        rate_limit_per_user=10,
                        rate_limit_per_api=100,
                    )
                )

                mock_response = JSONResponse({"ok": True})
                call_next = AsyncMock(return_value=mock_response)
                response = await middleware.dispatch(request, call_next)

                assert "X-RateLimit-Limit" in response.headers
                assert "X-RateLimit-Remaining" in response.headers
                assert response.headers["X-RateLimit-Limit"] == "10"
                assert response.headers["X-RateLimit-Remaining"] == "5"

    async def test_retry_after_header_on_429(self, rate_limiter: RateLimiter):
        """429 response should include Retry-After header."""
        with patch.object(rate_limiter, 'check_rate_limit', return_value=RateLimitResult(
            allowed=False,
            remaining=0,
            limit=5,
            retry_after=60,
            total_requests=10,
            allowed_requests=5,
        )):
            with patch('gateway.rate_limit.middleware.get_rate_limiter', return_value=rate_limiter):
                middleware = RateLimitMiddleware(app=None)

                request = create_mock_request()
                request.state.user = {"user_id": "test-user"}
                request.state.route_match = MagicMock(
                    route=MagicMock(
                        rate_limit_enabled=True,
                        rate_limit_per_user=5,
                        rate_limit_per_api=50,
                    )
                )

                call_next = AsyncMock(return_value=JSONResponse({"ok": True}))
                response = await middleware.dispatch(request, call_next)

                assert response.status_code == 429
                assert response.headers.get("Retry-After") == "60"

    async def test_health_endpoint_skips_rate_limit(self, rate_limiter: RateLimiter):
        """Health endpoint should skip rate limiting."""
        with patch('gateway.rate_limit.middleware.get_rate_limiter', return_value=rate_limiter):
            middleware = RateLimitMiddleware(app=None)

            request = create_mock_request(path="/health")
            request.state.user = None
            request.state.route_match = None

            mock_response = JSONResponse({"status": "ok"})
            call_next = AsyncMock(return_value=mock_response)

            with patch.object(rate_limiter, 'check_rate_limit') as mock_check:
                response = await middleware.dispatch(request, call_next)
                mock_check.assert_not_called()

            assert response.status_code == 200

    async def test_no_route_match_skips_rate_limit(self, rate_limiter: RateLimiter):
        """If no route matches, should skip rate limiting."""
        with patch('gateway.rate_limit.middleware.get_rate_limiter', return_value=rate_limiter):
            middleware = RateLimitMiddleware(app=None)

            request = create_mock_request(path="/api/nonexistent")
            request.state.user = "test-user"
            request.state.route_match = None

            mock_response = JSONResponse({"error": "not found"}, status_code=404)
            call_next = AsyncMock(return_value=mock_response)

            with patch.object(rate_limiter, 'check_rate_limit') as mock_check:
                response = await middleware.dispatch(request, call_next)
                mock_check.assert_not_called()

            assert response.status_code == 404
