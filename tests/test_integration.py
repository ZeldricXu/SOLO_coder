import os
import sys
import time
import uuid
import asyncio
from typing import Any, AsyncGenerator, Dict, List, Optional
from unittest.mock import patch

import pytest
import pytest_asyncio
import httpx

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src")))

from gateway.config import Settings, DatabaseSettings, RedisSettings, \
    JWTSettings, RateLimitSettings, CircuitBreakerSettings, GatewaySettings, \
    AuthStrategy, get_settings
from gateway.db.database import get_engine, init_db, get_session_factory
from gateway.db.models import Route
from gateway.db.repository import RouteRepository
from gateway.routing.router import Router
from gateway.routing.models import RouteConfig, RouteTarget
from gateway.routing.proxy import ProxyClient
from gateway.auth.jwt import JWTValidator
from gateway.rate_limit.limiter import RateLimiter
from gateway.circuit_breaker.breaker import CircuitBreaker
from tests.factories import RouteFactory, JWTFactory, MockDownstreamService, \
    CircuitBreakerConfigFactory


pytestmark = pytest.mark.integration


class TestcontainersManager:
    _postgres_container = None
    _redis_container = None
    _postgres_port = None
    _redis_port = None

    @classmethod
    async def start_containers(cls):
        if cls._postgres_container and cls._redis_container:
            return

        from testcontainers.postgres import PostgresContainer
        from testcontainers.redis import RedisContainer

        cls._postgres_container = PostgresContainer("postgres:15-alpine")
        cls._postgres_container.start()
        cls._postgres_port = cls._postgres_container.get_exposed_port(5432)

        cls._redis_container = RedisContainer("redis:7-alpine")
        cls._redis_container.start()
        cls._redis_port = cls._redis_container.get_exposed_port(6379)

        await asyncio.sleep(2)

    @classmethod
    async def stop_containers(cls):
        if cls._postgres_container:
            cls._postgres_container.stop()
            cls._postgres_container = None
        if cls._redis_container:
            cls._redis_container.stop()
            cls._redis_container = None

    @classmethod
    def get_postgres_dsn(cls) -> str:
        if not cls._postgres_container:
            raise RuntimeError("PostgreSQL container not started")
        return cls._postgres_container.get_connection_url()

    @classmethod
    def get_redis_host(cls) -> str:
        return "localhost"

    @classmethod
    def get_redis_port(cls) -> int:
        return int(cls._redis_port) if cls._redis_port else 6379


@pytest.fixture(scope="session")
def event_loop_policy():
    try:
        import uvloop
        return uvloop.EventLoopPolicy()
    except ImportError:
        return asyncio.DefaultEventLoopPolicy()


@pytest.fixture(scope="session")
def event_loop(event_loop_policy):
    loop = event_loop_policy.new_event_loop()
    yield loop
    loop.close()


@pytest.fixture(scope="session")
async def testcontainers_setup():
    await TestcontainersManager.start_containers()
    yield
    await TestcontainersManager.stop_containers()


@pytest.fixture(scope="session")
def integration_settings(testcontainers_setup):
    settings = Settings(
        db=DatabaseSettings(
            host="localhost",
            port=5432,
            user="test",
            password="test",
            database="test",
        ),
        redis=RedisSettings(
            host=TestcontainersManager.get_redis_host(),
            port=TestcontainersManager.get_redis_port(),
            db=0,
        ),
        jwt=JWTSettings(
            secret_key="integration-test-secret-key-12345",
            algorithm="HS256",
            issuer="test-gateway",
            audience="test-services",
        ),
        rate_limit=RateLimitSettings(
            default_user_limit=10,
            default_api_limit=100,
            burst_multiplier=1.5,
            window_seconds=10,
            redis_key_prefix="integration:rate_limit:",
        ),
        circuit_breaker=CircuitBreakerSettings(
            failure_threshold=0.5,
            slow_request_threshold=0.5,
            slow_request_duration=0.3,
            wait_duration_in_open_state=3,
            permitted_num_of_calls_in_half_open=2,
            rolling_window_size=10,
            redis_key_prefix="integration:circuit_breaker:",
        ),
        gateway=GatewaySettings(
            port=8080,
            log_level="debug",
            route_reload_interval=5,
            request_timeout=5,
            auth_strategies=[
                AuthStrategy(path_prefix="/api/public", strategy="jwt", idp="default"),
                AuthStrategy(path_prefix="/api/internal", strategy="mtls"),
            ],
        ),
    )

    pg_dsn = TestcontainersManager.get_postgres_dsn()
    from urllib.parse import urlparse
    parsed = urlparse(pg_dsn.replace("postgresql://", "postgresql+asyncpg://"))
    settings.db.host = parsed.hostname or "localhost"
    settings.db.port = parsed.port or 5432
    settings.db.user = parsed.username or "test"
    settings.db.password = parsed.password or "test"
    settings.db.database = parsed.path.lstrip("/") or "test"

    with patch("gateway.config.get_settings", return_value=settings):
        yield settings


@pytest.fixture(scope="session")
async def setup_database(integration_settings):
    await init_db()
    yield


@pytest.fixture
async def db_session(setup_database):
    session_factory = get_session_factory()
    async with session_factory() as session:
        yield session
        await session.rollback()
        await session.close()


@pytest.fixture
async def real_redis_client(integration_settings):
    import redis.asyncio as redis
    client = redis.Redis(
        host=integration_settings.redis.host,
        port=integration_settings.redis.port,
        db=integration_settings.redis.db,
        decode_responses=True,
    )
    yield client
    await client.flushdb()
    await client.close()


@pytest.fixture
async def downstream_service() -> AsyncGenerator[MockDownstreamService, None]:
    service = MockDownstreamService(port=0, delay=0.0, status_code=200)
    service.response_data = {"message": "hello from downstream", "service": "test-service"}
    await service.start()
    yield service
    await service.stop()


@pytest.fixture
async def slow_downstream_service() -> AsyncGenerator[MockDownstreamService, None]:
    service = MockDownstreamService(port=0, delay=1.0, status_code=200)
    service.response_data = {"message": "slow response"}
    await service.start()
    yield service
    await service.stop()


@pytest.fixture
async def failing_downstream_service() -> AsyncGenerator[MockDownstreamService, None]:
    service = MockDownstreamService(port=0, delay=0.0, status_code=500)
    service.response_data = {"error": "internal server error"}
    await service.start()
    yield service
    await service.stop()


@pytest.fixture
async def integration_rate_limiter(real_redis_client, integration_settings):
    limiter = RateLimiter()
    limiter.redis = real_redis_client
    limiter._script_sha = None
    await limiter.init_script()
    return limiter


@pytest.fixture
async def integration_circuit_breaker(real_redis_client, integration_settings):
    breaker = CircuitBreaker()
    breaker.redis = real_redis_client
    return breaker


@pytest.fixture
async def test_route(db_session, downstream_service):
    route_data = RouteFactory.create_prefix_route(
        name="integration-test-route",
        path="/api/public/test",
        target_url=downstream_service.base_url,
    )
    route_data["auth_strategy"] = "jwt"
    route_data["rate_limit_enabled"] = True
    route_data["rate_limit_per_user"] = 5
    route_data["rate_limit_per_api"] = 100
    route_data["circuit_breaker_enabled"] = True
    route_data["circuit_breaker_config"] = CircuitBreakerConfigFactory.create_config(
        failure_threshold=0.5,
        slow_request_threshold=0.5,
        slow_request_duration=0.3,
        wait_duration=3,
        half_open_calls=2,
    )

    repo = RouteRepository(db_session)
    route = await repo.create(route_data)
    return route


@pytest.fixture
def integration_router(test_route):
    router = Router()
    targets = [RouteTarget(**t) for t in test_route.targets]
    route_config = RouteConfig(
        id=test_route.id,
        name=test_route.name,
        path=test_route.path,
        match_type=test_route.match_type,
        path_pattern=test_route.path_pattern,
        targets=targets,
        weight_rules=test_route.weight_rules,
        methods=test_route.methods,
        auth_required=test_route.auth_required,
        auth_strategy=test_route.auth_strategy,
        rate_limit_enabled=test_route.rate_limit_enabled,
        rate_limit_per_user=test_route.rate_limit_per_user,
        rate_limit_per_api=test_route.rate_limit_per_api,
        circuit_breaker_enabled=test_route.circuit_breaker_enabled,
        circuit_breaker_config=test_route.circuit_breaker_config,
        transform_request=test_route.transform_request,
        transform_response=test_route.transform_response,
        timeout=test_route.timeout,
        retry_count=test_route.retry_count,
        version=test_route.version,
        strip_prefix=test_route.path if test_route.match_type == "prefix" else "",
    )
    route_config.compile()
    router._routes = [route_config]
    router._version = 1
    router._prefix_routes[route_config.path] = route_config
    return router


@pytest.fixture
def valid_jwt(integration_settings) -> str:
    return JWTFactory.create_token(
        user_id="integration-user-123",
        username="integration-test-user",
        email="integration@test.com",
        issuer="test-gateway",
        audience="test-services",
        secret_key="integration-test-secret-key-12345",
    )


class TestMainFlow:
    @pytest.mark.asyncio
    async def test_full_request_flow(
        self, integration_router, integration_rate_limiter,
        integration_circuit_breaker, valid_jwt, downstream_service,
        integration_settings
    ):
        from fastapi import FastAPI, Request
        from starlette.middleware.base import BaseHTTPMiddleware
        from gateway.auth.middleware import AuthMiddleware
        from gateway.rate_limit.middleware import RateLimitMiddleware
        from gateway.circuit_breaker.middleware import CircuitBreakerMiddleware
        from gateway.routing.proxy import convert_to_starlette_response

        app = FastAPI()

        class TestSetupMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                request.state.request_id = str(uuid.uuid4())
                request.state.start_time = time.time()
                request.state.user = None
                request.state.is_authenticated = False
                request.state.rate_limited = False
                request.state.circuit_broken = False
                request.state.upstream_latency = 0
                return await call_next(request)

        class RouteMatchingMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                path = request.url.path
                if path in ["/health", "/docs"]:
                    return await call_next(request)

                route_match = await integration_router.match(path, request.method, None)
                if not route_match:
                    from fastapi.responses import JSONResponse
                    return JSONResponse(status_code=404, content={"error": "Not Found"})

                request.state.route_match = route_match
                return await call_next(request)

        class ProxyMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                route_match = getattr(request.state, "route_match", None)
                if not route_match:
                    return await call_next(request)

                proxy_client = ProxyClient()
                try:
                    response, latency = await proxy_client.forward(request, route_match)
                    request.state.upstream_latency = latency
                    return convert_to_starlette_response(response)
                finally:
                    await proxy_client.close()

        app.add_middleware(ProxyMiddleware)
        app.add_middleware(CircuitBreakerMiddleware)
        app.add_middleware(RateLimitMiddleware)
        app.add_middleware(AuthMiddleware)
        app.add_middleware(RouteMatchingMiddleware)
        app.add_middleware(TestSetupMiddleware)

        with patch("gateway.auth.middleware.get_settings", return_value=integration_settings):
            with patch("gateway.rate_limit.middleware.get_rate_limiter", return_value=integration_rate_limiter):
                with patch("gateway.circuit_breaker.middleware.get_circuit_breaker", return_value=integration_circuit_breaker):
                    async with httpx.AsyncClient(app=app, base_url="http://testserver") as client:
                        response = await client.get(
                            "/api/public/test/hello",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )

                        assert response.status_code == 200
                        data = response.json()
                        assert data["message"] == "hello from downstream"
                        assert data["service"] == "test-service"
                        assert "X-RateLimit-Limit" in response.headers
                        assert "X-RateLimit-Remaining" in response.headers
                        assert "X-Circuit-State" in response.headers

                        assert downstream_service.request_count == 1

    @pytest.mark.asyncio
    async def test_no_auth_header_returns_401_with_www_authenticate(
        self, integration_router, integration_settings
    ):
        from fastapi import FastAPI, Request
        from starlette.middleware.base import BaseHTTPMiddleware
        from gateway.auth.middleware import AuthMiddleware

        app = FastAPI()

        class TestSetupMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                request.state.request_id = str(uuid.uuid4())
                request.state.start_time = time.time()
                request.state.user = None
                request.state.is_authenticated = False
                request.state.rate_limited = False
                request.state.circuit_broken = False
                request.state.upstream_latency = 0
                return await call_next(request)

        class RouteMatchingMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                path = request.url.path
                route_match = await integration_router.match(path, request.method, None)
                if route_match:
                    request.state.route_match = route_match
                return await call_next(request)

        app.add_middleware(AuthMiddleware)
        app.add_middleware(RouteMatchingMiddleware)
        app.add_middleware(TestSetupMiddleware)

        with patch("gateway.auth.middleware.get_settings", return_value=integration_settings):
            async with httpx.AsyncClient(app=app, base_url="http://testserver") as client:
                response = await client.get("/api/public/test")

                assert response.status_code == 401
                assert "WWW-Authenticate" in response.headers
                www_auth = response.headers["WWW-Authenticate"]
                assert "Bearer" in www_auth
                assert "invalid_token" in www_auth


class TestRateLimitIntegration:
    @pytest.mark.asyncio
    async def test_rate_limit_exceeded_returns_429(
        self, integration_router, integration_rate_limiter,
        integration_circuit_breaker, valid_jwt, downstream_service,
        integration_settings, real_redis_client
    ):
        from fastapi import FastAPI, Request
        from starlette.middleware.base import BaseHTTPMiddleware
        from gateway.auth.middleware import AuthMiddleware
        from gateway.rate_limit.middleware import RateLimitMiddleware
        from gateway.circuit_breaker.middleware import CircuitBreakerMiddleware
        from gateway.routing.proxy import convert_to_starlette_response

        await real_redis_client.flushdb()

        app = FastAPI()

        class TestSetupMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                request.state.request_id = str(uuid.uuid4())
                request.state.start_time = time.time()
                request.state.user = {"user_id": "rate-test-user"}
                request.state.is_authenticated = True
                request.state.rate_limited = False
                request.state.circuit_broken = False
                request.state.upstream_latency = 0
                return await call_next(request)

        class RouteMatchingMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                path = request.url.path
                route_match = await integration_router.match(path, request.method, "rate-test-user")
                if route_match:
                    request.state.route_match = route_match
                return await call_next(request)

        class ProxyMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                route_match = getattr(request.state, "route_match", None)
                if not route_match:
                    return await call_next(request)

                proxy_client = ProxyClient()
                try:
                    response, latency = await proxy_client.forward(request, route_match)
                    request.state.upstream_latency = latency
                    return convert_to_starlette_response(response)
                finally:
                    await proxy_client.close()

        app.add_middleware(ProxyMiddleware)
        app.add_middleware(CircuitBreakerMiddleware)
        app.add_middleware(RateLimitMiddleware)
        app.add_middleware(AuthMiddleware)
        app.add_middleware(RouteMatchingMiddleware)
        app.add_middleware(TestSetupMiddleware)

        with patch("gateway.auth.middleware.get_settings", return_value=integration_settings):
            with patch("gateway.rate_limit.middleware.get_rate_limiter", return_value=integration_rate_limiter):
                with patch("gateway.circuit_breaker.middleware.get_circuit_breaker", return_value=integration_circuit_breaker):
                    async with httpx.AsyncClient(app=app, base_url="http://testserver") as client:
                        for i in range(5):
                            response = await client.get(
                                "/api/public/test/rate",
                                headers={"Authorization": f"Bearer {valid_jwt}"}
                            )
                            assert response.status_code == 200

                        assert downstream_service.request_count == 5

                        response = await client.get(
                            "/api/public/test/rate",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )

                        assert response.status_code == 429
                        assert "Retry-After" in response.headers
                        assert "X-RateLimit-Limit" in response.headers
                        data = response.json()
                        assert data["error"]["code"] == 429
                        assert "Too Many Requests" in data["error"]["message"]

                        assert downstream_service.request_count == 5

    @pytest.mark.asyncio
    async def test_rate_limit_recovery_after_wait(
        self, integration_router, integration_rate_limiter,
        integration_circuit_breaker, valid_jwt, downstream_service,
        integration_settings, real_redis_client
    ):
        from fastapi import FastAPI, Request
        from starlette.middleware.base import BaseHTTPMiddleware
        from gateway.auth.middleware import AuthMiddleware
        from gateway.rate_limit.middleware import RateLimitMiddleware
        from gateway.circuit_breaker.middleware import CircuitBreakerMiddleware
        from gateway.routing.proxy import convert_to_starlette_response

        await real_redis_client.flushdb()

        settings = integration_settings.model_copy(deep=True)
        settings.rate_limit.window_seconds = 2

        app = FastAPI()

        class TestSetupMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                request.state.request_id = str(uuid.uuid4())
                request.state.start_time = time.time()
                request.state.user = {"user_id": "recovery-test-user"}
                request.state.is_authenticated = True
                request.state.rate_limited = False
                request.state.circuit_broken = False
                request.state.upstream_latency = 0
                return await call_next(request)

        class RouteMatchingMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                path = request.url.path
                route_match = await integration_router.match(path, request.method, "recovery-test-user")
                if route_match:
                    request.state.route_match = route_match
                return await call_next(request)

        class ProxyMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                route_match = getattr(request.state, "route_match", None)
                if not route_match:
                    return await call_next(request)

                proxy_client = ProxyClient()
                try:
                    response, latency = await proxy_client.forward(request, route_match)
                    request.state.upstream_latency = latency
                    return convert_to_starlette_response(response)
                finally:
                    await proxy_client.close()

        app.add_middleware(ProxyMiddleware)
        app.add_middleware(CircuitBreakerMiddleware)
        app.add_middleware(RateLimitMiddleware)
        app.add_middleware(AuthMiddleware)
        app.add_middleware(RouteMatchingMiddleware)
        app.add_middleware(TestSetupMiddleware)

        limiter = RateLimiter()
        limiter.redis = real_redis_client
        limiter._script_sha = None
        limiter.rl_settings = settings.rate_limit
        await limiter.init_script()

        with patch("gateway.auth.middleware.get_settings", return_value=settings):
            with patch("gateway.rate_limit.middleware.get_rate_limiter", return_value=limiter):
                with patch("gateway.circuit_breaker.middleware.get_circuit_breaker", return_value=integration_circuit_breaker):
                    async with httpx.AsyncClient(app=app, base_url="http://testserver") as client:
                        for i in range(5):
                            response = await client.get(
                                "/api/public/test/recovery",
                                headers={"Authorization": f"Bearer {valid_jwt}"}
                            )
                            assert response.status_code == 200

                        response = await client.get(
                            "/api/public/test/recovery",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )
                        assert response.status_code == 429

                        await asyncio.sleep(2.5)

                        response = await client.get(
                            "/api/public/test/recovery",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )
                        assert response.status_code == 200


class TestCircuitBreakerIntegration:
    @pytest.mark.asyncio
    async def test_circuit_breaker_opens_after_failures(
        self, integration_router, integration_rate_limiter,
        integration_circuit_breaker, valid_jwt, failing_downstream_service,
        integration_settings, real_redis_client
    ):
        from fastapi import FastAPI, Request
        from starlette.middleware.base import BaseHTTPMiddleware
        from gateway.auth.middleware import AuthMiddleware
        from gateway.rate_limit.middleware import RateLimitMiddleware
        from gateway.circuit_breaker.middleware import CircuitBreakerMiddleware
        from gateway.routing.proxy import convert_to_starlette_response

        await real_redis_client.flushdb()

        targets = [RouteTarget(url=failing_downstream_service.base_url, weight=1)]
        for route in integration_router._routes:
            route.targets = targets

        app = FastAPI()

        class TestSetupMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                request.state.request_id = str(uuid.uuid4())
                request.state.start_time = time.time()
                request.state.user = {"user_id": "cb-test-user"}
                request.state.is_authenticated = True
                request.state.rate_limited = False
                request.state.circuit_broken = False
                request.state.upstream_latency = 0
                return await call_next(request)

        class RouteMatchingMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                path = request.url.path
                route_match = await integration_router.match(path, request.method, "cb-test-user")
                if route_match:
                    request.state.route_match = route_match
                return await call_next(request)

        class ProxyMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                route_match = getattr(request.state, "route_match", None)
                if not route_match:
                    return await call_next(request)

                proxy_client = ProxyClient()
                try:
                    response, latency = await proxy_client.forward(request, route_match)
                    request.state.upstream_latency = latency
                    return convert_to_starlette_response(response)
                except Exception:
                    from fastapi.responses import JSONResponse
                    return JSONResponse(status_code=500, content={"error": "upstream error"})
                finally:
                    await proxy_client.close()

        app.add_middleware(ProxyMiddleware)
        app.add_middleware(CircuitBreakerMiddleware)
        app.add_middleware(RateLimitMiddleware)
        app.add_middleware(AuthMiddleware)
        app.add_middleware(RouteMatchingMiddleware)
        app.add_middleware(TestSetupMiddleware)

        with patch("gateway.auth.middleware.get_settings", return_value=integration_settings):
            with patch("gateway.rate_limit.middleware.get_rate_limiter", return_value=integration_rate_limiter):
                with patch("gateway.circuit_breaker.middleware.get_circuit_breaker", return_value=integration_circuit_breaker):
                    async with httpx.AsyncClient(app=app, base_url="http://testserver") as client:
                        for i in range(5):
                            response = await client.get(
                                "/api/public/test/cb",
                                headers={"Authorization": f"Bearer {valid_jwt}"}
                            )

                        assert failing_downstream_service.request_count >= 5

                        response = await client.get(
                            "/api/public/test/cb",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )

                        assert response.status_code == 503
                        assert "X-Circuit-State" in response.headers
                        assert response.headers["X-Circuit-State"] == "open"
                        assert "Retry-After" in response.headers

                        requests_before = failing_downstream_service.request_count
                        response = await client.get(
                            "/api/public/test/cb",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )
                        assert response.status_code == 503
                        assert failing_downstream_service.request_count == requests_before

    @pytest.mark.asyncio
    async def test_circuit_breaker_fallback_response(
        self, integration_router, integration_rate_limiter,
        integration_circuit_breaker, valid_jwt, failing_downstream_service,
        integration_settings, real_redis_client
    ):
        from fastapi import FastAPI, Request
        from starlette.middleware.base import BaseHTTPMiddleware
        from gateway.auth.middleware import AuthMiddleware
        from gateway.rate_limit.middleware import RateLimitMiddleware
        from gateway.circuit_breaker.middleware import CircuitBreakerMiddleware
        from gateway.routing.proxy import convert_to_starlette_response

        await real_redis_client.flushdb()

        fallback_data = {"data": {"message": "Fallback response", "fallback": True}}
        for route in integration_router._routes:
            route.circuit_breaker_config = CircuitBreakerConfigFactory.create_config(
                failure_threshold=0.4,
                wait_duration=3,
                half_open_calls=2,
                fallback_response=fallback_data,
            )
            route.targets = [RouteTarget(url=failing_downstream_service.base_url, weight=1)]

        app = FastAPI()

        class TestSetupMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                request.state.request_id = str(uuid.uuid4())
                request.state.start_time = time.time()
                request.state.user = {"user_id": "fallback-test-user"}
                request.state.is_authenticated = True
                request.state.rate_limited = False
                request.state.circuit_broken = False
                request.state.upstream_latency = 0
                return await call_next(request)

        class RouteMatchingMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                path = request.url.path
                route_match = await integration_router.match(path, request.method, "fallback-test-user")
                if route_match:
                    request.state.route_match = route_match
                return await call_next(request)

        class ProxyMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                route_match = getattr(request.state, "route_match", None)
                if not route_match:
                    return await call_next(request)

                proxy_client = ProxyClient()
                try:
                    response, latency = await proxy_client.forward(request, route_match)
                    request.state.upstream_latency = latency
                    return convert_to_starlette_response(response)
                except Exception:
                    from fastapi.responses import JSONResponse
                    return JSONResponse(status_code=500, content={"error": "upstream error"})
                finally:
                    await proxy_client.close()

        app.add_middleware(ProxyMiddleware)
        app.add_middleware(CircuitBreakerMiddleware)
        app.add_middleware(RateLimitMiddleware)
        app.add_middleware(AuthMiddleware)
        app.add_middleware(RouteMatchingMiddleware)
        app.add_middleware(TestSetupMiddleware)

        with patch("gateway.auth.middleware.get_settings", return_value=integration_settings):
            with patch("gateway.rate_limit.middleware.get_rate_limiter", return_value=integration_rate_limiter):
                with patch("gateway.circuit_breaker.middleware.get_circuit_breaker", return_value=integration_circuit_breaker):
                    async with httpx.AsyncClient(app=app, base_url="http://testserver") as client:
                        for i in range(5):
                            await client.get(
                                "/api/public/test/fallback",
                                headers={"Authorization": f"Bearer {valid_jwt}"}
                            )

                        response = await client.get(
                            "/api/public/test/fallback",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )

                        assert response.status_code == 200
                        assert "X-Circuit-State" in response.headers
                        assert response.headers["X-Circuit-State"] == "open"
                        assert "X-Circuit-Fallback" in response.headers
                        assert response.headers["X-Circuit-Fallback"] == "static"

                        data = response.json()
                        assert data["data"]["fallback"] is True
                        assert data["data"]["message"] == "Fallback response"

    @pytest.mark.asyncio
    async def test_circuit_breaker_recovery(
        self, integration_router, integration_rate_limiter,
        integration_circuit_breaker, valid_jwt, downstream_service,
        slow_downstream_service, integration_settings, real_redis_client
    ):
        from fastapi import FastAPI, Request
        from starlette.middleware.base import BaseHTTPMiddleware
        from gateway.auth.middleware import AuthMiddleware
        from gateway.rate_limit.middleware import RateLimitMiddleware
        from gateway.circuit_breaker.middleware import CircuitBreakerMiddleware
        from gateway.routing.proxy import convert_to_starlette_response

        await real_redis_client.flushdb()

        settings = integration_settings.model_copy(deep=True)
        settings.circuit_breaker.wait_duration_in_open_state = 2

        breaker = CircuitBreaker()
        breaker.redis = real_redis_client
        breaker.cb_settings = settings.circuit_breaker

        for route in integration_router._routes:
            route.circuit_breaker_config = CircuitBreakerConfigFactory.create_config(
                failure_threshold=0.4,
                slow_request_duration=0.3,
                wait_duration=2,
                half_open_calls=2,
            )
            route.targets = [RouteTarget(url=slow_downstream_service.base_url, weight=1)]

        app = FastAPI()

        class TestSetupMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                request.state.request_id = str(uuid.uuid4())
                request.state.start_time = time.time()
                request.state.user = {"user_id": "recovery-cb-user"}
                request.state.is_authenticated = True
                request.state.rate_limited = False
                request.state.circuit_broken = False
                request.state.upstream_latency = 0
                return await call_next(request)

        class RouteMatchingMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                path = request.url.path
                route_match = await integration_router.match(path, request.method, "recovery-cb-user")
                if route_match:
                    request.state.route_match = route_match
                return await call_next(request)

        class ProxyMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                route_match = getattr(request.state, "route_match", None)
                if not route_match:
                    return await call_next(request)

                proxy_client = ProxyClient()
                try:
                    response, latency = await proxy_client.forward(request, route_match)
                    request.state.upstream_latency = latency
                    return convert_to_starlette_response(response)
                except Exception as e:
                    from fastapi.responses import JSONResponse
                    return JSONResponse(status_code=500, content={"error": str(e)})
                finally:
                    await proxy_client.close()

        app.add_middleware(ProxyMiddleware)
        app.add_middleware(CircuitBreakerMiddleware)
        app.add_middleware(RateLimitMiddleware)
        app.add_middleware(AuthMiddleware)
        app.add_middleware(RouteMatchingMiddleware)
        app.add_middleware(TestSetupMiddleware)

        with patch("gateway.auth.middleware.get_settings", return_value=settings):
            with patch("gateway.rate_limit.middleware.get_rate_limiter", return_value=integration_rate_limiter):
                with patch("gateway.circuit_breaker.middleware.get_circuit_breaker", return_value=breaker):
                    async with httpx.AsyncClient(app=app, base_url="http://testserver") as client:
                        for i in range(5):
                            await client.get(
                                "/api/public/test/recovery",
                                headers={"Authorization": f"Bearer {valid_jwt}"}
                            )

                        response = await client.get(
                            "/api/public/test/recovery",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )
                        assert response.status_code == 503
                        assert response.headers["X-Circuit-State"] == "open"

                        await asyncio.sleep(2.5)

                        for route in integration_router._routes:
                            route.targets = [RouteTarget(url=downstream_service.base_url, weight=1)]

                        response = await client.get(
                            "/api/public/test/recovery",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )
                        assert response.headers["X-Circuit-State"] in ["half_open", "closed"]

                        if response.headers["X-Circuit-State"] == "half_open":
                            await asyncio.sleep(0.5)
                            response = await client.get(
                                "/api/public/test/recovery",
                                headers={"Authorization": f"Bearer {valid_jwt}"}
                            )
                            assert response.status_code == 200


class TestRateLimitAndCircuitBreakerPriority:
    @pytest.mark.asyncio
    async def test_rate_limit_takes_priority_over_circuit_breaker(
        self, integration_router, integration_rate_limiter,
        integration_circuit_breaker, valid_jwt, failing_downstream_service,
        integration_settings, real_redis_client
    ):
        from fastapi import FastAPI, Request
        from starlette.middleware.base import BaseHTTPMiddleware
        from gateway.auth.middleware import AuthMiddleware
        from gateway.rate_limit.middleware import RateLimitMiddleware
        from gateway.circuit_breaker.middleware import CircuitBreakerMiddleware
        from gateway.routing.proxy import convert_to_starlette_response

        await real_redis_client.flushdb()

        for route in integration_router._routes:
            route.rate_limit_per_user = 3
            route.rate_limit_enabled = True
            route.circuit_breaker_enabled = True
            route.circuit_breaker_config = CircuitBreakerConfigFactory.create_config(
                failure_threshold=0.5,
                wait_duration=10,
            )
            route.targets = [RouteTarget(url=failing_downstream_service.base_url, weight=1)]

        app = FastAPI()

        class TestSetupMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                request.state.request_id = str(uuid.uuid4())
                request.state.start_time = time.time()
                request.state.user = {"user_id": "priority-test-user"}
                request.state.is_authenticated = True
                request.state.rate_limited = False
                request.state.circuit_broken = False
                request.state.upstream_latency = 0
                return await call_next(request)

        class RouteMatchingMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                path = request.url.path
                route_match = await integration_router.match(path, request.method, "priority-test-user")
                if route_match:
                    request.state.route_match = route_match
                return await call_next(request)

        class ProxyMiddleware(BaseHTTPMiddleware):
            async def dispatch(self, request: Request, call_next):
                route_match = getattr(request.state, "route_match", None)
                if not route_match:
                    return await call_next(request)

                proxy_client = ProxyClient()
                try:
                    response, latency = await proxy_client.forward(request, route_match)
                    request.state.upstream_latency = latency
                    return convert_to_starlette_response(response)
                except Exception:
                    from fastapi.responses import JSONResponse
                    return JSONResponse(status_code=500, content={"error": "upstream error"})
                finally:
                    await proxy_client.close()

        app.add_middleware(ProxyMiddleware)
        app.add_middleware(CircuitBreakerMiddleware)
        app.add_middleware(RateLimitMiddleware)
        app.add_middleware(AuthMiddleware)
        app.add_middleware(RouteMatchingMiddleware)
        app.add_middleware(TestSetupMiddleware)

        with patch("gateway.auth.middleware.get_settings", return_value=integration_settings):
            with patch("gateway.rate_limit.middleware.get_rate_limiter", return_value=integration_rate_limiter):
                with patch("gateway.circuit_breaker.middleware.get_circuit_breaker", return_value=integration_circuit_breaker):
                    async with httpx.AsyncClient(app=app, base_url="http://testserver") as client:
                        for i in range(3):
                            response = await client.get(
                                "/api/public/test/priority",
                                headers={"Authorization": f"Bearer {valid_jwt}"}
                            )

                        assert failing_downstream_service.request_count == 3

                        response = await client.get(
                            "/api/public/test/priority",
                            headers={"Authorization": f"Bearer {valid_jwt}"}
                        )

                        assert response.status_code == 429
                        assert failing_downstream_service.request_count == 3


class TestDatabaseIntegration:
    @pytest.mark.asyncio
    async def test_route_persistence_and_retrieval(self, db_session):
        repo = RouteRepository(db_session)

        route_data = RouteFactory.create_prefix_route(
            name="db-test-route",
            path="/api/db/test",
            target_url="http://test-service:8080",
        )

        created = await repo.create(route_data)
        assert created.id is not None
        assert created.name == "db-test-route"
        assert created.path == "/api/db/test"
        assert created.is_active is True
        assert created.version == 1

        retrieved = await repo.get_by_id(created.id)
        assert retrieved is not None
        assert retrieved.name == "db-test-route"
        assert retrieved.path == "/api/db/test"

        all_routes = await repo.get_all_active()
        assert len(all_routes) >= 1
        route_names = [r.name for r in all_routes]
        assert "db-test-route" in route_names

        updated = await repo.update(created.id, {"description": "Updated description"})
        assert updated is not None
        assert updated.version == 2
        assert updated.description == "Updated description"

        max_version = await repo.get_max_version()
        assert max_version >= 2

        result = await repo.delete(created.id)
        assert result is True

        deleted_route = await repo.get_by_id(created.id)
        assert deleted_route is not None
        assert deleted_route.is_active is False


class TestRedisRateLimitAtomicity:
    @pytest.mark.asyncio
    async def test_concurrent_rate_limit_with_real_redis(
        self, real_redis_client, integration_settings
    ):
        limiter = RateLimiter()
        limiter.redis = real_redis_client
        limiter.rl_settings = integration_settings.rate_limit
        limiter.rl_settings.default_user_limit = 10
        await limiter.init_script()

        user_id = "concurrent-test-user"
        api_path = "/api/concurrent/test"

        await real_redis_client.flushdb()

        results = []

        async def make_request():
            result = await limiter.check_rate_limit(
                user_id=user_id,
                api_path=api_path,
                custom_user_limit=10,
                custom_api_limit=100,
            )
            return result

        tasks = [make_request() for _ in range(15)]
        results = await asyncio.gather(*tasks)

        allowed_count = sum(1 for r in results if r.allowed)
        denied_count = sum(1 for r in results if not r.allowed)

        assert allowed_count <= 10
        assert denied_count >= 5

        total_used = max(r.total_requests for r in results if r.allowed) if any(r.allowed for r in results) else 0
        assert total_used == allowed_count

        final_result = await limiter.check_rate_limit(
            user_id=user_id,
            api_path=api_path,
            custom_user_limit=10,
            custom_api_limit=100,
        )
        assert not final_result.allowed
        assert final_result.total_requests == allowed_count
