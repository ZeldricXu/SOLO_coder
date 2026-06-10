import asyncio
import uuid
from typing import Any, Dict
from unittest.mock import MagicMock, AsyncMock, patch, PropertyMock

import pytest
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.datastructures import Headers, URL

from gateway.auth.jwt import JWTValidator
from gateway.auth.middleware import AuthMiddleware
from gateway.auth.oauth2 import KeycloakPlugin, Auth0Plugin, CustomOAuthPlugin


pytestmark = [pytest.mark.unit, pytest.mark.asyncio]


def create_mock_request(
    method: str = "GET",
    path: str = "/api/test",
    headers: Dict[str, str] = None,
    query_string: str = "",
):
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
    request.state.auth_required = False
    request.state.auth_strategy = None

    return request


class TestJWTValidator:
    async def test_validate_valid_token(self, jwt_validator: JWTValidator, valid_jwt_token: str):
        is_valid, user_info, error = await jwt_validator.validate(valid_jwt_token)

        assert is_valid is True
        assert error is None
        assert user_info is not None
        assert user_info["user_id"] == "test-user-123"
        assert user_info["username"] == "testuser"
        assert user_info["email"] == "test@example.com"
        assert "roles" in user_info
        assert "scopes" in user_info

    async def test_validate_expired_token(self, jwt_validator: JWTValidator, expired_jwt_token: str):
        is_valid, user_info, error = await jwt_validator.validate(expired_jwt_token)

        assert is_valid is False
        assert user_info is None
        assert error is not None
        assert "expired" in error.lower()

    async def test_validate_invalid_token_format(self, jwt_validator: JWTValidator):
        is_valid, user_info, error = await jwt_validator.validate("invalid-token")

        assert is_valid is False
        assert user_info is None
        assert error is not None

    async def test_validate_invalid_signature(self, jwt_validator: JWTValidator):
        from jose import jwt
        import time

        payload = {
            "sub": "test-user",
            "iat": int(time.time()),
            "exp": int(time.time()) + 3600,
            "iss": "test-gateway",
            "aud": "test-services",
        }
        token = jwt.encode(payload, "wrong-secret", algorithm="HS256")

        is_valid, user_info, error = await jwt_validator.validate(token)

        assert is_valid is False
        assert user_info is None
        assert error is not None

    async def test_validate_wrong_issuer(self, jwt_validator: JWTValidator, test_settings):
        from tests.factories import JWTFactory

        token = JWTFactory.create_token(
            user_id="test-user-123",
            issuer="wrong-issuer",
            secret_key="test-secret-key-for-testing-purposes",
        )

        is_valid, user_info, error = await jwt_validator.validate(token)

        assert is_valid is False
        assert user_info is None
        assert error is not None

    async def test_validate_wrong_audience(self, jwt_validator: JWTValidator):
        from tests.factories import JWTFactory

        token = JWTFactory.create_token(
            user_id="test-user-123",
            audience="wrong-audience",
            secret_key="test-secret-key-for-testing-purposes",
        )

        is_valid, user_info, error = await jwt_validator.validate(token)

        assert is_valid is False
        assert user_info is None
        assert error is not None

    async def test_create_token(self, jwt_validator: JWTValidator):
        token = jwt_validator.create_token(
            user_id="new-user-456",
            username="newuser",
            email="new@example.com",
        )

        assert isinstance(token, str)
        assert len(token) > 0

        is_valid, user_info, _ = await jwt_validator.validate(token)
        assert is_valid is True
        assert user_info["user_id"] == "new-user-456"
        assert user_info["username"] == "newuser"
        assert user_info["email"] == "new@example.com"

    async def test_token_contains_roles_and_scopes(self, jwt_validator: JWTValidator, valid_jwt_token: str):
        is_valid, user_info, _ = await jwt_validator.validate(valid_jwt_token)

        assert is_valid is True
        assert isinstance(user_info["roles"], list)
        assert "user" in user_info["roles"]
        assert isinstance(user_info["scopes"], list)
        assert "read" in user_info["scopes"]
        assert "write" in user_info["scopes"]


class TestAuthMiddleware:
    async def test_valid_jwt_returns_200(self, test_settings):
        app = MagicMock()
        middleware = AuthMiddleware(app)

        async def mock_call_next(request):
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="GET",
            path="/api/public/test",
            headers={"Authorization": "Bearer valid-token"},
        )

        with patch.object(middleware, 'jwt_validator') as mock_validator:
            mock_validator.validate = AsyncMock(return_value=(True, {"user_id": "test-user-123"}, None))
            response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 200

    async def test_expired_jwt_returns_401(self, test_settings, expired_jwt_token: str):
        middleware = AuthMiddleware(None)

        async def mock_call_next(request):
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="GET",
            path="/api/public/test",
            headers={"Authorization": f"Bearer {expired_jwt_token}"},
        )

        response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 401

    async def test_missing_authorization_header_returns_401_with_www_authenticate(self, test_settings):
        middleware = AuthMiddleware(None)

        async def mock_call_next(request):
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="GET",
            path="/api/public/test",
        )

        response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 401
        assert "www-authenticate" in [h.lower() for h in response.headers.keys()]

        www_auth = None
        for k, v in response.headers.items():
            if k.lower() == "www-authenticate":
                www_auth = v
                break

        assert www_auth is not None
        assert "Bearer" in www_auth
        assert "invalid_token" in www_auth

    async def test_invalid_bearer_format_returns_401(self, test_settings):
        middleware = AuthMiddleware(None)

        async def mock_call_next(request):
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="GET",
            path="/api/public/test",
            headers={"Authorization": "InvalidFormat token123"},
        )

        response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 401

    async def test_public_path_skips_auth(self, test_settings):
        middleware = AuthMiddleware(None)

        call_count = 0

        async def mock_call_next(request):
            nonlocal call_count
            call_count += 1
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="GET",
            path="/health",
        )

        response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 200
        assert call_count == 1

    async def test_options_method_skips_auth(self, test_settings):
        middleware = AuthMiddleware(None)

        call_count = 0

        async def mock_call_next(request):
            nonlocal call_count
            call_count += 1
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="OPTIONS",
            path="/api/public/test",
        )

        response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 200
        assert call_count == 1

    async def test_internal_path_uses_mtls_strategy(self, test_settings):
        middleware = AuthMiddleware(None)

        async def mock_call_next(request):
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="GET",
            path="/api/internal/test",
        )

        with patch.object(middleware, 'mtls_validator') as mock_mtls:
            mock_mtls.validate = AsyncMock(return_value=(False, None, "No client certificate"))
            mock_mtls.add_ca_cert = MagicMock()
            response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 401

    async def test_auth_strategy_selection_longest_prefix(self, test_settings):
        from gateway.config import AuthStrategy

        middleware = AuthMiddleware(None)
        middleware.auth_strategies = [
            AuthStrategy(path_prefix="/api/", strategy="jwt"),
            AuthStrategy(path_prefix="/api/internal/", strategy="mtls"),
            AuthStrategy(path_prefix="/api/admin/", strategy="api_key"),
        ]

        strategy = middleware._get_auth_strategy("/api/internal/users")
        assert strategy is not None
        assert strategy.strategy == "mtls"
        assert strategy.path_prefix == "/api/internal/"

        strategy2 = middleware._get_auth_strategy("/api/users")
        assert strategy2 is not None
        assert strategy2.strategy == "jwt"

    async def test_auth_strategy_no_match(self, test_settings):
        from gateway.config import AuthStrategy

        middleware = AuthMiddleware(None)
        middleware.auth_strategies = [
            AuthStrategy(path_prefix="/api/internal/", strategy="mtls"),
        ]

        strategy = middleware._get_auth_strategy("/other/path")
        assert strategy is None


class TestOAuth2Plugins:
    async def test_keycloak_plugin_creation(self):
        config = {
            "jwks_url": "http://keycloak/auth/realms/test/protocol/openid-connect/certs",
            "issuer": "http://keycloak/auth/realms/test",
            "client_id": "test-client",
            "client_secret": "test-secret",
            "introspect_url": "http://keycloak/auth/realms/test/protocol/openid-connect/token/introspect",
        }

        plugin = KeycloakPlugin(config)
        assert plugin.config == config

    async def test_auth0_plugin_creation(self):
        config = {
            "jwks_url": "https://tenant.auth0.com/.well-known/jwks.json",
            "issuer": "https://tenant.auth0.com/",
            "audience": "https://api.example.com",
        }

        plugin = Auth0Plugin(config)
        assert plugin.config == config

    async def test_custom_oauth_plugin_creation(self):
        config = {
            "jwks_url": "http://custom-oauth/.well-known/jwks.json",
            "issuer": "http://custom-oauth",
            "validation_type": "jwks",
        }

        plugin = CustomOAuthPlugin(config)
        assert plugin.config == config

    async def test_oauth2_plugin_extract_claims(self):
        from tests.factories import JWTFactory

        config = {"jwks_url": "http://test/.well-known/jwks.json"}
        plugin = KeycloakPlugin(config)

        token = JWTFactory.create_token(
            user_id="oauth-user-789",
            username="oauthuser",
            email="oauth@example.com",
            roles=["admin", "user"],
            scopes=["read", "write", "admin"],
            secret_key="test-secret-key-for-testing-purposes",
        )

        from jose import jwt
        payload = jwt.decode(
            token,
            "test-secret-key-for-testing-purposes",
            algorithms=["HS256"],
            options={
                "verify_signature": False,
                "verify_aud": False,
                "verify_iss": False,
                "verify_exp": False,
            },
        )

        claims = plugin._extract_claims(payload)
        assert claims["user_id"] == "oauth-user-789"
        assert claims["username"] == "oauthuser"
        assert claims["email"] == "oauth@example.com"
        assert "admin" in claims["roles"]
        assert "read" in claims["scopes"]

    async def test_plugin_close(self):
        config = {"jwks_url": "http://test/.well-known/jwks.json"}
        plugin = KeycloakPlugin(config)

        mock_client = MagicMock()
        mock_client.aclose = AsyncMock()
        plugin._http_client = mock_client

        await plugin.close()
        mock_client.aclose.assert_called_once()


class TestOAuth2ClientCredentialsFlow:
    """Test OAuth2 client_credentials flow."""

    async def test_introspect_token_success(self):
        config = {
            "introspect_url": "http://keycloak/introspect",
            "client_id": "test-client",
            "client_secret": "test-secret",
            "issuer": "test-issuer",
        }

        plugin = KeycloakPlugin(config)

        mock_response = MagicMock()
        mock_response.json = MagicMock(return_value={
            "active": True,
            "sub": "client-id-123",
            "scope": "read write",
            "client_id": "test-client",
            "exp": 9999999999,
        })
        mock_response.raise_for_status = MagicMock()

        with patch.object(plugin._http_client, 'post', new_callable=AsyncMock) as mock_post:
            mock_post.return_value = mock_response

            is_valid, user_info, error = await plugin.introspect_token("test-access-token")

            assert is_valid is True
            assert user_info is not None
            assert user_info["user_id"] == "client-id-123"
            assert error is None

            mock_post.assert_called_once()

    async def test_introspect_token_inactive(self):
        config = {
            "introspect_url": "http://keycloak/introspect",
            "client_id": "test-client",
            "client_secret": "test-secret",
        }

        plugin = KeycloakPlugin(config)

        mock_response = MagicMock()
        mock_response.json = MagicMock(return_value={"active": False})
        mock_response.raise_for_status = MagicMock()

        with patch.object(plugin._http_client, 'post', new_callable=AsyncMock) as mock_post:
            mock_post.return_value = mock_response

            is_valid, user_info, error = await plugin.introspect_token("invalid-token")

            assert is_valid is False
            assert user_info is None
            assert error is not None

    async def test_introspect_not_configured(self):
        config = {"jwks_url": "http://test/jwks"}
        plugin = KeycloakPlugin(config)

        is_valid, user_info, error = await plugin.introspect_token("test-token")

        assert is_valid is False
        assert user_info is None
        assert "not configured" in error.lower()


class TestAPIKeyAuth:
    """Test API Key authentication."""

    async def test_api_key_missing_header_returns_401(self, test_settings):
        from gateway.config import AuthStrategy

        middleware = AuthMiddleware(None)
        middleware.auth_strategies = [
            AuthStrategy(path_prefix="/api/admin/", strategy="api_key"),
        ]

        async def mock_call_next(request):
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="GET",
            path="/api/admin/test",
        )

        response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 401

        www_auth = None
        for k, v in response.headers.items():
            if k.lower() == "www-authenticate":
                www_auth = v
                break

        assert www_auth is not None
        assert "ApiKey" in www_auth


class TestMTLSAuth:
    """Test mTLS authentication."""

    async def test_mtls_no_cert_returns_401(self, test_settings):
        middleware = AuthMiddleware(None)

        async def mock_call_next(request):
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="GET",
            path="/api/internal/test",
        )

        with patch.object(middleware, 'mtls_validator') as mock_mtls:
            mock_mtls.validate = AsyncMock(return_value=(False, None, "Client certificate required"))
            mock_mtls.add_ca_cert = MagicMock()
            response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 401

        www_auth = None
        for k, v in response.headers.items():
            if k.lower() == "www-authenticate":
                www_auth = v
                break

        assert www_auth is not None
        assert "Certificate" in www_auth

    async def test_mtls_valid_cert_passes(self, test_settings):
        middleware = AuthMiddleware(None)

        async def mock_call_next(request):
            return JSONResponse(status_code=200, content={"status": "ok"})

        request = create_mock_request(
            method="GET",
            path="/api/internal/test",
        )

        with patch.object(middleware, 'mtls_validator') as mock_mtls:
            mock_mtls.validate = AsyncMock(return_value=(True, {"user_id": "service-account-1", "auth_type": "mtls"}, None))
            mock_mtls.add_ca_cert = MagicMock()
            response = await middleware.dispatch(request, mock_call_next)

        assert response.status_code == 200
