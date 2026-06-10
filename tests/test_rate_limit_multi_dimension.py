import pytest
from unittest.mock import MagicMock, patch, AsyncMock
from typing import Any, Dict

from starlette.requests import Request
from starlette.testclient import TestClient

from gateway.rate_limit.resolvers import (
    RateLimitKeyResolver,
    UserIdResolver,
    IPResolver,
    ApiKeyResolver,
    HeaderResolver,
    PathResolver,
    CompositeKeyResolver,
    get_rate_limit_key_resolver,
)
from gateway.config import get_settings, RateLimitSettings, RateLimitDimension
from gateway.rate_limit.limiter import RateLimiter, RateLimitResult
from tests.conftest import *


pytestmark = pytest.mark.asyncio


class TestRateLimitKeyResolvers:
    async def test_user_id_resolver_with_user(self):
        resolver = UserIdResolver()
        request = MagicMock(spec=Request)
        request.state = MagicMock()
        request.state.user = {"user_id": "test_user_123"}
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert result == "user_test_user_123"

    async def test_user_id_resolver_no_user(self):
        resolver = UserIdResolver()
        request = MagicMock(spec=Request)
        request.state = MagicMock()
        request.state.user = None
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert result is None

    async def test_ip_resolver_with_client_ip(self):
        resolver = IPResolver()
        request = MagicMock(spec=Request)
        request.client = MagicMock()
        request.client.host = "192.168.1.100"
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert result == "ip_192.168.1.100"

    async def test_ip_resolver_no_client(self):
        resolver = IPResolver()
        request = MagicMock(spec=Request)
        request.client = None
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert result is None

    async def test_api_key_resolver_from_header(self):
        resolver = ApiKeyResolver()
        request = MagicMock(spec=Request)
        request.headers = {"X-API-Key": "abc123def456"}
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert result == "apikey_abc123de"

    async def test_api_key_resolver_from_context(self):
        resolver = ApiKeyResolver()
        request = MagicMock(spec=Request)
        request.headers = {}
        context = {"api_key_id": "key_7890abcd"}

        result = await resolver.resolve(request, context)
        assert result == "apikey_key_7890"

    async def test_api_key_resolver_no_key(self):
        resolver = ApiKeyResolver()
        request = MagicMock(spec=Request)
        request.headers = {}
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert result is None

    async def test_header_resolver_with_value(self):
        resolver = HeaderResolver("X-Service-Name")
        request = MagicMock(spec=Request)
        request.headers = {"X-Service-Name": "order-service"}
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert result == "header_x-service-name_order-service"

    async def test_header_resolver_no_value(self):
        resolver = HeaderResolver("X-Service-Name")
        request = MagicMock(spec=Request)
        request.headers = {}
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert result is None

    async def test_header_resolver_sanitizes_value(self):
        resolver = HeaderResolver("X-Custom")
        request = MagicMock(spec=Request)
        request.headers = {"X-Custom": "test/../value;bad"}
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert ";" not in result
        assert "/" not in result
        assert ".." not in result

    async def test_path_resolver_with_context(self):
        resolver = PathResolver()
        request = MagicMock(spec=Request)
        request.url = MagicMock()
        request.url.path = "/api/other"
        context = {"api_path": "/api/users"}

        result = await resolver.resolve(request, context)
        assert result == "api_/api/users"

    async def test_path_resolver_from_request(self):
        resolver = PathResolver()
        request = MagicMock(spec=Request)
        request.url = MagicMock()
        request.url.path = "/api/products"
        context: Dict[str, Any] = {}

        result = await resolver.resolve(request, context)
        assert result == "api_/api/products"


class TestCompositeKeyResolver:
    async def test_legacy_mode_user_and_api(self):
        settings = get_settings()
        settings.rate_limit.multi_dimension_enabled = False

        resolver = CompositeKeyResolver()
        resolver.settings = settings
        resolver.rl_settings = settings.rate_limit

        request = MagicMock(spec=Request)
        request.state = MagicMock()
        request.state.user = {"user_id": "user123"}
        request.url = MagicMock()
        request.url.path = "/api/test"

        context = {"api_path": "/api/test", "user_id": "user123"}
        keys = await resolver.resolve_keys(request, context)

        assert len(keys) == 2
        assert "user:user123:/api/test" in keys
        assert "api:/api/test" in keys

    async def test_legacy_mode_no_user(self):
        settings = get_settings()
        settings.rate_limit.multi_dimension_enabled = False

        resolver = CompositeKeyResolver()
        resolver.settings = settings
        resolver.rl_settings = settings.rate_limit

        request = MagicMock(spec=Request)
        request.state = MagicMock()
        request.state.user = None
        request.url = MagicMock()
        request.url.path = "/api/test"

        context = {"api_path": "/api/test"}
        keys = await resolver.resolve_keys(request, context)

        assert len(keys) == 1
        assert "api:/api/test" in keys

    async def test_multi_dimension_mode_user_and_api(self):
        settings = get_settings()
        settings.rate_limit.multi_dimension_enabled = True
        settings.rate_limit.dimensions = [
            RateLimitDimension(name="user_id", resolver="user_id", enabled=True),
            RateLimitDimension(name="api_path", resolver="api_path", enabled=True),
        ]

        resolver = CompositeKeyResolver()
        resolver.settings = settings
        resolver.rl_settings = settings.rate_limit

        request = MagicMock(spec=Request)
        request.state = MagicMock()
        request.state.user = {"user_id": "user123"}
        request.url = MagicMock()
        request.url.path = "/api/test"
        request.headers = {}

        context = {"api_path": "/api/test", "user_id": "user123"}
        keys = await resolver.resolve_keys(request, context)

        assert len(keys) >= 1
        combined_key = keys[0]
        assert "user_user123" in combined_key
        assert "api_/api/test" in combined_key

    async def test_multi_dimension_with_ip(self):
        settings = get_settings()
        settings.rate_limit.multi_dimension_enabled = True
        settings.rate_limit.dimensions = [
            RateLimitDimension(name="ip", resolver="ip", enabled=True),
            RateLimitDimension(name="api_path", resolver="api_path", enabled=True),
        ]

        resolver = CompositeKeyResolver()
        resolver.settings = settings
        resolver.rl_settings = settings.rate_limit

        request = MagicMock(spec=Request)
        request.client = MagicMock()
        request.client.host = "10.0.0.1"
        request.state = MagicMock()
        request.state.user = None
        request.url = MagicMock()
        request.url.path = "/api/test"
        request.headers = {}

        context = {"api_path": "/api/test"}
        keys = await resolver.resolve_keys(request, context)

        assert len(keys) >= 1
        combined_key = keys[0]
        assert "ip_10.0.0.1" in combined_key

    async def test_multi_dimension_disabled_dimension(self):
        settings = get_settings()
        settings.rate_limit.multi_dimension_enabled = True
        settings.rate_limit.dimensions = [
            RateLimitDimension(name="user_id", resolver="user_id", enabled=False),
            RateLimitDimension(name="api_path", resolver="api_path", enabled=True),
        ]

        resolver = CompositeKeyResolver()
        resolver.settings = settings
        resolver.rl_settings = settings.rate_limit

        request = MagicMock(spec=Request)
        request.state = MagicMock()
        request.state.user = {"user_id": "user123"}
        request.url = MagicMock()
        request.url.path = "/api/test"
        request.headers = {}

        context = {"api_path": "/api/test", "user_id": "user123"}
        keys = await resolver.resolve_keys(request, context)

        combined_key = keys[0]
        assert "user_user123" not in combined_key
        assert "api_/api/test" in combined_key

    async def test_pattern_rule_matching(self):
        from gateway.config import Settings, RateLimitSettings, RateLimitDimension, DatabaseSettings, RedisSettings, JWTSettings, CircuitBreakerSettings, GatewaySettings, AnalyticsSettings, SecurityFilterSettings, WebhookSettings, DeveloperPortalSettings

        settings = Settings(
            db=DatabaseSettings(),
            redis=RedisSettings(),
            jwt=JWTSettings(),
            rate_limit=RateLimitSettings(
                multi_dimension_enabled=True,
                dimension_separator=":",
                dimensions=[
                    RateLimitDimension(name="user_id", resolver="user_id", enabled=True),
                    RateLimitDimension(name="api_path", resolver="api_path", enabled=True),
                ],
                pattern_rules=[
                    {
                        "pattern": "user_*:api_/api/export*",
                        "key_prefix": "export_group",
                        "limit": 100,
                    }
                ],
            ),
            circuit_breaker=CircuitBreakerSettings(),
            gateway=GatewaySettings(),
            analytics=AnalyticsSettings(),
            security_filter=SecurityFilterSettings(),
            webhook=WebhookSettings(),
            portal=DeveloperPortalSettings(),
        )

        resolver = CompositeKeyResolver()
        resolver.settings = settings
        resolver.rl_settings = settings.rate_limit

        request = MagicMock(spec=Request)
        request.state = MagicMock()
        request.state.user = {"user_id": "free_user"}
        request.url = MagicMock()
        request.url.path = "/api/export/data"
        request.headers = {}

        context = {"api_path": "/api/export/data", "user_id": "free_user"}
        keys = await resolver.resolve_keys(request, context)

        pattern_keys = [k for k in keys if k.startswith("export_group:")]
        assert len(pattern_keys) == 1

    async def test_register_custom_resolver(self):
        class CustomResolver(RateLimitKeyResolver):
            async def resolve(self, request, context):
                return "custom_value"

        resolver = CompositeKeyResolver()
        custom = CustomResolver()
        resolver.register_resolver("custom", custom)

        assert "custom" in resolver._resolvers


class TestRateLimiterMultiDimension:
    async def test_check_rate_limit_multi_dimension(self, rate_limiter: RateLimiter):
        request = MagicMock(spec=Request)
        request.state = MagicMock()
        request.state.user = {"user_id": "multi_user"}
        request.url = MagicMock()
        request.url.path = "/api/multi"
        request.headers = {}
        request.client = MagicMock()
        request.client.host = "127.0.0.1"

        result = await rate_limiter.check_rate_limit_multi_dimension(
            request, "/api/multi", custom_user_limit=5, custom_api_limit=100
        )

        assert isinstance(result, RateLimitResult)
        assert result.allowed is True
        assert result.remaining >= 0

    async def test_multi_dimension_backwards_compatible(self, rate_limiter: RateLimiter):
        result = await rate_limiter.check_rate_limit(
            user_id="compat_user",
            api_path="/api/compat",
            custom_user_limit=10,
        )

        assert isinstance(result, RateLimitResult)
        assert result.allowed is True
        assert result.limit == 10
