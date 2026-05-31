from __future__ import annotations

import asyncio
import time
from datetime import timedelta
from unittest.mock import MagicMock, patch

import pytest

from src.api_gateway.auth import AuthService, PermissionChecker, User
from src.api_gateway.rate_limit import (
    RateLimitConfig,
    RateLimiter,
    TokenBucketRateLimiter,
    WindowState,
)
from src.common.exceptions import ForbiddenError, RateLimitError, UnauthorizedError
from jose import JWTError


# =============================================================================
# User Model Tests
# =============================================================================
class TestUserModel:
    def test_user_creation_defaults(self):
        user = User(
            user_id="u1",
            username="testuser",
            email="test@example.com",
        )
        assert user.user_id == "u1"
        assert user.username == "testuser"
        assert user.email == "test@example.com"
        assert user.roles == []
        assert user.permissions == []
        assert user.tenant_id is None
        assert user.is_active is True

    def test_user_with_roles_and_permissions(self):
        user = User(
            user_id="u1",
            username="admin",
            email="admin@example.com",
            roles=["admin", "user"],
            permissions=["read", "write"],
            tenant_id="t1",
            is_active=True,
        )
        assert "admin" in user.roles
        assert "read" in user.permissions
        assert user.tenant_id == "t1"


# =============================================================================
# AuthService Tests - Normal Flow
# =============================================================================
class TestAuthServiceNormalFlow:
    def setup_method(self):
        self.auth_service = AuthService(
            secret_key="test_secret_key",
            algorithm="HS256",
            access_token_expire_minutes=30,
        )

    def test_hash_password_produces_different_hashes(self):
        hashed1 = self.auth_service.hash_password("password123")
        hashed2 = self.auth_service.hash_password("password123")
        assert hashed1 != hashed2
        assert len(hashed1) > 0

    def test_verify_password_correct(self):
        hashed = self.auth_service.hash_password("password123")
        assert self.auth_service.verify_password("password123", hashed) is True

    def test_verify_password_incorrect(self):
        hashed = self.auth_service.hash_password("password123")
        assert self.auth_service.verify_password("wrongpassword", hashed) is False

    def test_create_and_decode_token(self):
        user = User(
            user_id="u1",
            username="testuser",
            email="test@example.com",
            roles=["user"],
            permissions=["read"],
            tenant_id="t1",
        )
        token = self.auth_service.create_access_token(user)
        assert isinstance(token, str)
        decoded = self.auth_service.decode_token(token)
        assert decoded.user_id == "u1"
        assert decoded.username == "testuser"
        assert decoded.roles == ["user"]
        assert decoded.permissions == ["read"]
        assert decoded.tenant_id == "t1"

    def test_create_token_with_custom_expiry(self):
        user = User(user_id="u1", username="testuser", email="test@example.com")
        token = self.auth_service.create_access_token(user, expires_delta=timedelta(minutes=60))
        decoded = self.auth_service.decode_token(token)
        assert decoded.exp is not None

    def test_register_and_authenticate_user(self):
        user = User(
            user_id="u1",
            username="testuser",
            email="test@example.com",
        )
        self.auth_service.register_user(user, "password123")
        authenticated = self.auth_service.authenticate_user("testuser", "password123")
        assert authenticated.username == "testuser"
        assert authenticated.user_id == "u1"

    def test_register_and_authenticate_api_key(self):
        user = User(
            user_id="u1",
            username="apiuser",
            email="api@example.com",
        )
        self.auth_service.register_api_key("api_key_123", user, ["read", "write"])
        authenticated = self.auth_service.authenticate_api_key("api_key_123")
        assert authenticated.username == "apiuser"


# =============================================================================
# AuthService Tests - Boundary Values & Edge Cases
# =============================================================================
class TestAuthServiceBoundary:
    def setup_method(self):
        self.auth_service = AuthService(
            secret_key="test_secret_key",
            algorithm="HS256",
            access_token_expire_minutes=1,
        )

    def test_authenticate_nonexistent_user_raises_unauthorized(self):
        with pytest.raises(UnauthorizedError, match="Invalid credentials"):
            self.auth_service.authenticate_user("nonexistent", "password")

    def test_authenticate_invalid_password_raises_unauthorized(self):
        user = User(user_id="u1", username="testuser", email="test@example.com")
        self.auth_service.register_user(user, "correct_password")
        with pytest.raises(UnauthorizedError, match="Invalid credentials"):
            self.auth_service.authenticate_user("testuser", "wrong_password")

    def test_authenticate_inactive_user_raises_unauthorized(self):
        user = User(
            user_id="u1",
            username="inactive",
            email="inactive@example.com",
            is_active=False,
        )
        self.auth_service.register_user(user, "password123")
        with pytest.raises(UnauthorizedError, match="inactive"):
            self.auth_service.authenticate_user("inactive", "password123")

    def test_decode_invalid_token_raises_unauthorized(self):
        with pytest.raises(UnauthorizedError, match="Invalid token"):
            self.auth_service.decode_token("invalid.token.here")

    def test_decode_expired_token_raises_unauthorized(self):
        user = User(user_id="u1", username="testuser", email="test@example.com")
        token = self.auth_service.create_access_token(user, expires_delta=timedelta(seconds=1))
        time.sleep(1.5)
        with pytest.raises((UnauthorizedError, JWTError)):
            self.auth_service.decode_token(token)

    def test_decode_tampered_token_raises_unauthorized(self):
        other_auth = AuthService(secret_key="different_key", algorithm="HS256")
        user = User(user_id="u1", username="testuser", email="test@example.com")
        token = other_auth.create_access_token(user)
        with pytest.raises(UnauthorizedError, match="Invalid token"):
            self.auth_service.decode_token(token)

    def test_authenticate_invalid_api_key_raises_unauthorized(self):
        with pytest.raises(UnauthorizedError, match="Invalid API key"):
            self.auth_service.authenticate_api_key("invalid_key")

    def test_empty_password_hash_and_verify(self):
        hashed = self.auth_service.hash_password("")
        assert self.auth_service.verify_password("", hashed) is True
        assert self.auth_service.verify_password("not_empty", hashed) is False

    def test_long_password(self):
        long_password = "a" * 1000
        hashed = self.auth_service.hash_password(long_password)
        assert self.auth_service.verify_password(long_password, hashed) is True


# =============================================================================
# PermissionChecker Tests - Normal Flow
# =============================================================================
class TestPermissionCheckerNormalFlow:
    def setup_method(self):
        self.checker = PermissionChecker()

    def test_has_permission_direct(self):
        token_data = MagicMock()
        token_data.roles = []
        token_data.permissions = ["read", "write"]
        token_data.user_id = "u1"
        assert self.checker.has_permission(token_data, "read") is True
        assert self.checker.has_permission(token_data, "delete") is False

    def test_has_permission_from_role(self):
        self.checker.add_role_permissions("editor", ["edit", "publish"])
        token_data = MagicMock()
        token_data.roles = ["editor"]
        token_data.permissions = []
        token_data.user_id = "u1"
        assert self.checker.has_permission(token_data, "edit") is True
        assert self.checker.has_permission(token_data, "delete") is False

    def test_has_permission_from_user_specific(self):
        self.checker.add_user_permissions("u1", ["special:action"])
        token_data = MagicMock()
        token_data.roles = []
        token_data.permissions = []
        token_data.user_id = "u1"
        assert self.checker.has_permission(token_data, "special:action") is True

    def test_admin_bypass_all_permissions(self):
        token_data = MagicMock()
        token_data.roles = ["admin"]
        token_data.permissions = []
        token_data.user_id = "u1"
        assert self.checker.has_permission(token_data, "any:permission:here") is True

    def test_wildcard_permission_bypass(self):
        token_data = MagicMock()
        token_data.roles = []
        token_data.permissions = ["*"]
        token_data.user_id = "u1"
        assert self.checker.has_permission(token_data, "anything") is True

    def test_has_role(self):
        token_data = MagicMock()
        token_data.roles = ["user", "editor"]
        assert self.checker.has_role(token_data, "user") is True
        assert self.checker.has_role(token_data, "admin") is False

    def test_admin_role_bypass_role_check(self):
        token_data = MagicMock()
        token_data.roles = ["admin"]
        assert self.checker.has_role(token_data, "superadmin") is True

    def test_require_permission_passes(self):
        token_data = MagicMock()
        token_data.roles = []
        token_data.permissions = ["read"]
        token_data.user_id = "u1"
        self.checker.require_permission(token_data, "read")

    def test_require_permission_raises_forbidden(self):
        token_data = MagicMock()
        token_data.roles = []
        token_data.permissions = []
        token_data.user_id = "u1"
        with pytest.raises(ForbiddenError, match="Missing required permission"):
            self.checker.require_permission(token_data, "delete")

    def test_require_role_passes(self):
        token_data = MagicMock()
        token_data.roles = ["editor"]
        self.checker.require_role(token_data, "editor")

    def test_require_role_raises_forbidden(self):
        token_data = MagicMock()
        token_data.roles = ["user"]
        with pytest.raises(ForbiddenError, match="Missing required role"):
            self.checker.require_role(token_data, "admin")


# =============================================================================
# PermissionChecker Tests - Wildcard & Hierarchical Permissions
# =============================================================================
class TestPermissionCheckerWildcards:
    def setup_method(self):
        self.checker = PermissionChecker()

    def test_hierarchical_permission_wildcard(self):
        token_data = MagicMock()
        token_data.roles = []
        token_data.permissions = ["users:*"]
        token_data.user_id = "u1"
        assert self.checker.has_permission(token_data, "users:read") is True
        assert self.checker.has_permission(token_data, "users:write") is True
        assert self.checker.has_permission(token_data, "users:delete") is True
        assert self.checker.has_permission(token_data, "products:read") is False

    def test_multi_level_wildcard(self):
        token_data = MagicMock()
        token_data.roles = []
        token_data.permissions = ["api:v1:*"]
        token_data.user_id = "u1"
        assert self.checker.has_permission(token_data, "api:v1:users:read") is True
        assert self.checker.has_permission(token_data, "api:v2:users:read") is False

    def test_partial_wildcard_match(self):
        token_data = MagicMock()
        token_data.roles = []
        token_data.permissions = ["admin:*"]
        token_data.user_id = "u1"
        assert self.checker.has_permission(token_data, "admin:users:delete") is True

    def test_multiple_permission_sources(self):
        self.checker.add_role_permissions("user", ["read", "list"])
        self.checker.add_user_permissions("u1", ["special"])
        token_data = MagicMock()
        token_data.roles = ["user"]
        token_data.permissions = ["base"]
        token_data.user_id = "u1"
        permissions = self.checker.get_user_permissions(token_data)
        assert "read" in permissions
        assert "list" in permissions
        assert "special" in permissions
        assert "base" in permissions

    def test_add_duplicate_permissions(self):
        self.checker.add_role_permissions("editor", ["edit"])
        self.checker.add_role_permissions("editor", ["edit", "publish"])
        token_data = MagicMock()
        token_data.roles = ["editor"]
        token_data.permissions = []
        token_data.user_id = "u1"
        permissions = self.checker.get_user_permissions(token_data)
        assert "edit" in permissions
        assert "publish" in permissions
        assert len(permissions) == 2


# =============================================================================
# RateLimiter Tests - Normal Flow
# =============================================================================
class TestRateLimiterNormalFlow:
    def setup_method(self):
        self.rate_limiter = RateLimiter(
            default_config=RateLimitConfig(
                requests=100,
                window_seconds=60,
                enabled=True,
            )
        )

    def test_single_request_allowed(self):
        result = self.rate_limiter.check_rate_limit(
            endpoint="/api/test",
            headers={},
            ip_address="192.168.1.1",
        )
        assert result["allowed"] is True
        assert result["remaining"] == 99
        assert "reset" in result
        assert result["limit"] == 100

    def test_multiple_requests_within_limit(self):
        results = []
        for i in range(50):
            result = self.rate_limiter.check_rate_limit(
                endpoint="/api/test",
                headers={},
                ip_address="192.168.1.1",
            )
            results.append(result)
        assert results[0]["remaining"] == 99
        assert results[-1]["remaining"] == 50
        assert all(r["allowed"] for r in results)

    def test_custom_endpoint_config(self):
        self.rate_limiter.set_config(
            endpoint="/api/limited",
            config=RateLimitConfig(requests=10, window_seconds=60),
        )
        result = self.rate_limiter.check_rate_limit(
            endpoint="/api/limited",
            headers={},
            ip_address="192.168.1.1",
        )
        assert result["limit"] == 10
        assert result["remaining"] == 9

    def test_disabled_rate_limiting(self):
        self.rate_limiter.set_config(
            endpoint="/api/open",
            config=RateLimitConfig(requests=100, window_seconds=60, enabled=False),
        )
        for i in range(200):
            result = self.rate_limiter.check_rate_limit(
                endpoint="/api/open",
                headers={},
                ip_address="192.168.1.1",
            )
            assert result["allowed"] is True

    def test_different_clients_have_separate_counters(self):
        for i in range(100):
            self.rate_limiter.check_rate_limit(
                endpoint="/api/test",
                headers={},
                ip_address="192.168.1.1",
            )
        result = self.rate_limiter.check_rate_limit(
            endpoint="/api/test",
            headers={},
            ip_address="192.168.1.2",
        )
        assert result["allowed"] is True
        assert result["remaining"] == 99

    def test_get_stats(self):
        self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.1")
        self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.2")
        stats = self.rate_limiter.get_stats()
        assert "/api/test" in stats
        assert stats["/api/test"]["total_clients"] == 2
        assert stats["/api/test"]["active_requests"] == 2

    def test_reset_client(self):
        self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.1")
        self.rate_limiter.reset_client("/api/test", "ip:192.168.1.1")
        stats = self.rate_limiter.get_stats("/api/test")
        assert stats["/api/test"]["total_clients"] == 0

    def test_client_identification_by_api_key(self):
        result1 = self.rate_limiter.check_rate_limit(
            "/api/test",
            {"x-api-key": "api_key_123"},
            "192.168.1.1",
        )
        result2 = self.rate_limiter.check_rate_limit(
            "/api/test",
            {"x-api-key": "api_key_456"},
            "192.168.1.1",
        )
        assert result1["remaining"] == 99
        assert result2["remaining"] == 99

    def test_client_identification_by_user_id(self):
        result1 = self.rate_limiter.check_rate_limit(
            "/api/test",
            {"x-user-id": "user123"},
            "192.168.1.1",
        )
        result2 = self.rate_limiter.check_rate_limit(
            "/api/test",
            {"x-user-id": "user456"},
            "192.168.1.1",
        )
        assert result1["remaining"] == 99
        assert result2["remaining"] == 99


# =============================================================================
# RateLimiter Tests - Boundary Values
# =============================================================================
class TestRateLimiterBoundary:
    def setup_method(self):
        self.rate_limiter = RateLimiter(
            default_config=RateLimitConfig(
                requests=5,
                window_seconds=1,
                enabled=True,
            )
        )

    def test_rate_limit_exactly_at_limit(self):
        for i in range(5):
            result = self.rate_limiter.check_rate_limit(
                "/api/test", {}, "192.168.1.1"
            )
        assert result["remaining"] == 0
        assert result["allowed"] is True

    def test_rate_limit_exceeded_raises_error(self):
        for i in range(5):
            self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.1")
        with pytest.raises(RateLimitError) as exc_info:
            self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.1")
        assert exc_info.value.details["retry_after"] == 1
        assert exc_info.value.details["limit"] == 5

    def test_blocked_client_raises_immediately(self):
        for i in range(5):
            self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.1")
        with pytest.raises(RateLimitError):
            self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.1")
        with pytest.raises(RateLimitError):
            self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.1")

    def test_window_expiry_resets_counter(self):
        for i in range(5):
            self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.1")
        time.sleep(1.1)
        result = self.rate_limiter.check_rate_limit("/api/test", {}, "192.168.1.1")
        assert result["allowed"] is True
        assert result["remaining"] == 4

    def test_zero_requests_limit(self):
        limiter = RateLimiter(
            default_config=RateLimitConfig(requests=0, window_seconds=60)
        )
        with pytest.raises(RateLimitError):
            limiter.check_rate_limit("/api/test", {}, "192.168.1.1")

    def test_empty_headers_uses_ip(self):
        result = self.rate_limiter.check_rate_limit(
            "/api/test",
            {},
            "10.0.0.1",
        )
        assert result["allowed"] is True

    def test_get_stats_for_specific_endpoint(self):
        self.rate_limiter.check_rate_limit("/api/test1", {}, "192.168.1.1")
        self.rate_limiter.check_rate_limit("/api/test2", {}, "192.168.1.1")
        stats = self.rate_limiter.get_stats("/api/test1")
        assert "/api/test1" in stats
        assert "/api/test2" not in stats

    def test_reset_nonexistent_client_no_error(self):
        self.rate_limiter.reset_client("/api/test", "nonexistent")


# =============================================================================
# TokenBucketRateLimiter Tests
# =============================================================================
class TestTokenBucketRateLimiter:
    def test_acquire_single_token(self):
        limiter = TokenBucketRateLimiter(rate=10.0, capacity=5.0)
        assert limiter.acquire("client1", 1.0) is True

    def test_acquire_multiple_tokens(self):
        limiter = TokenBucketRateLimiter(rate=10.0, capacity=10.0)
        assert limiter.acquire("client1", 5.0) is True
        assert limiter.acquire("client1", 5.0) is True
        assert limiter.acquire("client1", 1.0) is False

    def test_token_refill_over_time(self):
        limiter = TokenBucketRateLimiter(rate=10.0, capacity=5.0)
        for _ in range(5):
            limiter.acquire("client1", 1.0)
        assert limiter.acquire("client1", 1.0) is False
        time.sleep(0.2)
        assert limiter.acquire("client1", 1.0) is True

    def test_try_acquire_success(self):
        limiter = TokenBucketRateLimiter(rate=10.0, capacity=5.0)
        result = limiter.try_acquire("client1", 3.0)
        assert result["allowed"] is True
        assert result["remaining_tokens"] == 2.0

    def test_try_acquire_failure(self):
        limiter = TokenBucketRateLimiter(rate=10.0, capacity=5.0)
        limiter.acquire("client1", 5.0)
        result = limiter.try_acquire("client1", 1.0)
        assert result["allowed"] is False
        assert "retry_after" in result

    def test_different_keys_are_independent(self):
        limiter = TokenBucketRateLimiter(rate=10.0, capacity=5.0)
        for _ in range(5):
            limiter.acquire("client1", 1.0)
        assert limiter.acquire("client1", 1.0) is False
        assert limiter.acquire("client2", 1.0) is True

    def test_capacity_not_exceeded_on_refill(self):
        limiter = TokenBucketRateLimiter(rate=100.0, capacity=5.0)
        time.sleep(0.1)
        result = limiter.try_acquire("client1", 1.0)
        assert result["remaining_tokens"] <= 5.0


# =============================================================================
# RateLimiter Concurrency Tests
# =============================================================================
class TestRateLimiterConcurrency:
    @pytest.mark.asyncio
    async def test_concurrent_rate_limit_checks(self):
        limiter = RateLimiter(
            default_config=RateLimitConfig(requests=100, window_seconds=60)
        )

        async def make_request(client_id: str):
            try:
                return limiter.check_rate_limit(
                    "/api/test",
                    {"x-user-id": client_id},
                    "127.0.0.1",
                )
            except RateLimitError:
                return None

        tasks = []
        for i in range(50):
            tasks.append(make_request(f"user{i}"))
        results = await asyncio.gather(*tasks)
        assert len([r for r in results if r is not None]) == 50

    @pytest.mark.asyncio
    async def test_concurrent_same_client_rate_limit(self):
        limiter = RateLimiter(
            default_config=RateLimitConfig(requests=50, window_seconds=60)
        )

        async def make_request():
            try:
                return limiter.check_rate_limit(
                    "/api/test",
                    {},
                    "192.168.1.1",
                )
            except RateLimitError:
                return None

        tasks = [make_request() for _ in range(100)]
        results = await asyncio.gather(*tasks)
        success_count = len([r for r in results if r is not None])
        assert success_count <= 50

    @pytest.mark.asyncio
    async def test_concurrent_token_bucket(self):
        limiter = TokenBucketRateLimiter(rate=100.0, capacity=100.0)

        async def try_acquire():
            return limiter.try_acquire("shared_client", 1.0)

        tasks = [try_acquire() for _ in range(150)]
        results = await asyncio.gather(*tasks)
        allowed = sum(1 for r in results if r["allowed"])
        assert allowed <= 100


# =============================================================================
# Integration Tests - Auth + Permission + Rate Limit
# =============================================================================
class TestApiGatewayIntegration:
    def setup_method(self):
        self.auth_service = AuthService(secret_key="integration_test", algorithm="HS256")
        self.permission_checker = PermissionChecker()
        self.rate_limiter = RateLimiter()
        self.permission_checker.add_role_permissions("admin", ["users:*", "products:*"])
        self.permission_checker.add_role_permissions("user", ["users:read"])

    def test_full_auth_flow(self):
        user = User(
            user_id="admin1",
            username="admin",
            email="admin@example.com",
            roles=["admin"],
        )
        self.auth_service.register_user(user, "adminpass")
        authenticated = self.auth_service.authenticate_user("admin", "adminpass")
        token = self.auth_service.create_access_token(authenticated)
        token_data = self.auth_service.decode_token(token)
        assert self.permission_checker.has_permission(token_data, "users:delete") is True

    def test_user_limited_permissions(self):
        user = User(
            user_id="user1",
            username="regular_user",
            email="user@example.com",
            roles=["user"],
        )
        self.auth_service.register_user(user, "userpass")
        authenticated = self.auth_service.authenticate_user("regular_user", "userpass")
        token = self.auth_service.create_access_token(authenticated)
        token_data = self.auth_service.decode_token(token)
        assert self.permission_checker.has_permission(token_data, "users:read") is True
        assert self.permission_checker.has_permission(token_data, "users:delete") is False

    def test_rate_limit_after_authentication(self):
        user = User(
            user_id="user1",
            username="test",
            email="test@example.com",
        )
        self.auth_service.register_user(user, "pass")
        authenticated = self.auth_service.authenticate_user("test", "pass")
        token = self.auth_service.create_access_token(authenticated)
        headers = {"authorization": f"Bearer {token}"}
        for i in range(100):
            self.rate_limiter.check_rate_limit(
                "/api/resource", headers, "192.168.1.1"
            )
        with pytest.raises(RateLimitError):
            self.rate_limiter.check_rate_limit(
                "/api/resource", headers, "192.168.1.1"
            )
