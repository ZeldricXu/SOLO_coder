import os
import sys
import asyncio
import uuid
from typing import Any, AsyncGenerator, Dict, Generator, List, Optional
from unittest.mock import MagicMock, AsyncMock, patch

import pytest
import pytest_asyncio
from fakeredis import FakeRedis
from redis.asyncio import Redis as AsyncRedis

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src")))

from gateway.config import get_settings
from gateway.routing.router import Router
from gateway.routing.models import RouteConfig, RouteTarget
from gateway.routing.proxy import ProxyClient, convert_to_starlette_response
from gateway.auth.jwt import JWTValidator
from gateway.auth.middleware import AuthMiddleware
from gateway.auth.oauth2 import KeycloakPlugin, Auth0Plugin, CustomOAuthPlugin
from gateway.rate_limit.limiter import RateLimiter, RateLimitResult, TOKEN_BUCKET_SCRIPT
from gateway.circuit_breaker.breaker import CircuitBreaker, CircuitState, CircuitBreakerResult
from gateway.db.models import Route, APIKey
from tests.factories import RouteFactory, JWTFactory, APIKeyFactory, MockDownstreamService


pytest_plugins = ("pytest_asyncio",)


def pytest_configure(config):
    config.addinivalue_line("markers", "unit: Unit tests")
    config.addinivalue_line("markers", "integration: Integration tests")
    config.addinivalue_line("markers", "slow: Slow running tests")
    config.addinivalue_line("markers", "concurrent: Concurrent tests")


@pytest.fixture(scope="session")
def event_loop_policy():
    try:
        import uvloop
        return uvloop.EventLoopPolicy()
    except ImportError:
        return asyncio.DefaultEventLoopPolicy()


@pytest.fixture
def event_loop(event_loop_policy):
    loop = event_loop_policy.new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
def fake_redis() -> FakeRedis:
    redis = FakeRedis(decode_responses=True)
    return redis


@pytest.fixture
async def async_fake_redis() -> AsyncGenerator[AsyncRedis, None]:
    from fakeredis.aioredis import FakeRedis as AsyncFakeRedis
    redis = AsyncFakeRedis(decode_responses=True)
    yield redis
    await redis.close()


@pytest.fixture
def test_settings():
    from gateway.config import Settings, RateLimitSettings, CircuitBreakerSettings, \
        GatewaySettings, JWTSettings, RedisSettings

    settings = Settings(
        jwt=JWTSettings(
            secret_key="test-secret-key-for-testing-purposes",
            algorithm="HS256",
            issuer="test-gateway",
            audience="test-services",
        ),
        redis=RedisSettings(
            host="localhost",
            port=6379,
            db=0,
        ),
        rate_limit=RateLimitSettings(
            default_user_limit=100,
            default_api_limit=1000,
            burst_multiplier=2.0,
            window_seconds=60,
            redis_key_prefix="test:rate_limit:",
        ),
        circuit_breaker=CircuitBreakerSettings(
            failure_threshold=0.5,
            slow_request_threshold=0.5,
            slow_request_duration=0.5,
            wait_duration_in_open_state=10,
            permitted_num_of_calls_in_half_open=3,
            rolling_window_size=20,
            redis_key_prefix="test:circuit_breaker:",
        ),
        gateway=GatewaySettings(
            port=8080,
            log_level="debug",
            route_reload_interval=5,
            request_timeout=10,
        ),
    )

    with patch("gateway.config.get_settings", return_value=settings):
        yield settings


@pytest.fixture
def sample_routes() -> List[Dict[str, Any]]:
    return [
        RouteFactory.create_prefix_route(
            name="users-api",
            path="/api/users",
            target_url="http://users-service:8080",
        ),
        RouteFactory.create_regex_route(
            name="user-detail-api",
            path="/api/users/",
            pattern=r"^/api/users/(?P<user_id>[a-zA-Z0-9-]+)$",
            target_url="http://users-service:8080",
        ),
        RouteFactory.create_prefix_route(
            name="orders-api",
            path="/api/orders",
            target_url="http://orders-service:8080",
        ),
        RouteFactory.create_weighted_route(
            name="weighted-api",
            path="/api/weighted",
            targets=[
                {"url": "http://service-v1:8080", "weight": 3},
                {"url": "http://service-v2:8080", "weight": 7},
            ],
        ),
        RouteFactory.create_prefix_route(
            name="public-api",
            path="/api/public",
            target_url="http://public-service:8080",
        ),
        RouteFactory.create_prefix_route(
            name="internal-api",
            path="/api/internal",
            target_url="http://internal-service:8080",
        ),
    ]


@pytest.fixture
def router_with_routes(sample_routes) -> Router:
    router = Router()
    route_configs = []

    for route_data in sample_routes:
        targets = [RouteTarget(**t) for t in route_data["targets"]]
        route_config = RouteConfig(
            id=route_data["id"],
            name=route_data["name"],
            path=route_data["path"],
            match_type=route_data["match_type"],
            path_pattern=route_data["path_pattern"],
            targets=targets,
            weight_rules=route_data["weight_rules"],
            methods=route_data["methods"],
            auth_required=route_data["auth_required"],
            auth_strategy=route_data["auth_strategy"],
            rate_limit_enabled=route_data["rate_limit_enabled"],
            rate_limit_per_user=route_data["rate_limit_per_user"],
            rate_limit_per_api=route_data["rate_limit_per_api"],
            circuit_breaker_enabled=route_data["circuit_breaker_enabled"],
            circuit_breaker_config=route_data["circuit_breaker_config"],
            transform_request=route_data["transform_request"],
            transform_response=route_data["transform_response"],
            timeout=route_data["timeout"],
            retry_count=route_data["retry_count"],
            version=route_data["version"],
            strip_prefix=route_data["path"] if route_data["match_type"] == "prefix" else "",
        )
        route_config.compile()
        route_configs.append(route_config)

    router._routes = route_configs
    router._version = 1

    for rc in route_configs:
        if rc.match_type == "prefix":
            router._prefix_routes[rc.path] = rc
        elif rc.match_type == "regex":
            router._regex_routes.append(rc)
        elif rc.match_type == "weighted":
            router._weighted_routes.append(rc)

    router._routes.sort(key=lambda r: len(r.path), reverse=True)

    return router


@pytest.fixture
def jwt_validator(test_settings) -> JWTValidator:
    validator = JWTValidator()
    validator.settings = test_settings
    validator.jwt_settings = test_settings.jwt
    return validator


@pytest.fixture
def valid_jwt_token(test_settings) -> str:
    return JWTFactory.create_token(
        user_id="test-user-123",
        username="testuser",
        email="test@example.com",
        issuer="test-gateway",
        audience="test-services",
        secret_key="test-secret-key-for-testing-purposes",
    )


@pytest.fixture
def expired_jwt_token(test_settings) -> str:
    return JWTFactory.create_token(
        user_id="test-user-123",
        username="testuser",
        expired=True,
        issuer="test-gateway",
        audience="test-services",
        secret_key="test-secret-key-for-testing-purposes",
    )


@pytest.fixture
def rate_limiter(test_settings) -> RateLimiter:
    from fakeredis.aioredis import FakeRedis as AsyncFakeRedis
    redis = AsyncFakeRedis(decode_responses=True)
    limiter = RateLimiter()
    limiter.redis = redis
    limiter._script_sha = None
    limiter.settings = test_settings
    limiter.rate_limit_settings = test_settings.rate_limit
    return limiter


@pytest.fixture
def circuit_breaker(test_settings) -> CircuitBreaker:
    from fakeredis.aioredis import FakeRedis as AsyncFakeRedis
    redis = AsyncFakeRedis(decode_responses=True)
    breaker = CircuitBreaker()
    breaker.redis = redis
    breaker.settings = test_settings
    breaker.cb_settings = test_settings.circuit_breaker
    return breaker


@pytest.fixture
def mock_route_repository(sample_routes):
    repo = MagicMock()

    async def get_all_active():
        from gateway.db.models import Route
        routes = []
        for r in sample_routes:
            route = Route(**r)
            routes.append(route)
        return routes

    async def get_max_version():
        return max(r.get("version", 1) for r in sample_routes)

    repo.get_all_active = AsyncMock(side_effect=get_all_active)
    repo.get_max_version = AsyncMock(side_effect=get_max_version)

    return repo


@pytest.fixture
async def mock_downstream_service() -> AsyncGenerator[MockDownstreamService, None]:
    service = MockDownstreamService(port=0, delay=0.0, status_code=200)
    await service.start()
    yield service
    await service.stop()


@pytest.fixture
async def slow_downstream_service() -> AsyncGenerator[MockDownstreamService, None]:
    service = MockDownstreamService(port=0, delay=2.0, status_code=200)
    await service.start()
    yield service
    await service.stop()


@pytest.fixture
async def failing_downstream_service() -> AsyncGenerator[MockDownstreamService, None]:
    service = MockDownstreamService(port=0, delay=0.0, status_code=500)
    await service.start()
    yield service
    await service.stop()


@pytest.fixture
def sample_api_key_data() -> Dict[str, Any]:
    return APIKeyFactory.create_api_key_dict(
        status="approved",
        user_id="test-user-456",
        key="test-api-key-abc123",
    )


@pytest_asyncio.fixture
async def proxy_client() -> AsyncGenerator[ProxyClient, None]:
    client = ProxyClient()
    yield client
    await client.close()


@pytest.fixture
def gateway_app(router_with_routes, rate_limiter, circuit_breaker, test_settings):
    from fastapi import FastAPI, Request
    from starlette.middleware.base import BaseHTTPMiddleware
    import time
    import uuid as uuid_lib

    app = FastAPI()

    class TestSetupMiddleware(BaseHTTPMiddleware):
        async def dispatch(self, request: Request, call_next):
            request.state.request_id = str(uuid_lib.uuid4())
            request.state.start_time = time.time()
            request.state.user = None
            request.state.is_authenticated = False
            request.state.rate_limited = False
            request.state.circuit_broken = False
            request.state.upstream_latency = 0
            return await call_next(request)

    app.add_middleware(TestSetupMiddleware)

    return app


@pytest.fixture
def auth_middleware_instance(test_settings):
    with patch("gateway.auth.middleware.get_settings", return_value=test_settings):
        middleware = AuthMiddleware(None)
        return middleware
