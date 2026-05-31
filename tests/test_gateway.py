import pytest
import base64

from top.gateway.auth import (
    JWTAuth,
    BasicAuth,
    APIKeyAuth,
    Role,
    Permission,
    PermissionLevel,
)
from top.gateway.rate_limit import (
    SlidingWindowLimiter,
    TokenBucketLimiter,
    FixedWindowLimiter,
    RateLimitRouter,
    RateLimitPolicy,
    RateLimitAlgorithm,
)


class TestJWTAuth:
    def test_token_creation_and_validation(self):
        auth = JWTAuth(secret_key="test_secret", token_ttl=3600)
        
        token = auth.create_token(
            user_id="user_001",
            username="testuser",
            roles=["admin"],
        )
        
        assert isinstance(token, str)
        assert len(token) > 0
        
        result = auth.authenticate({"token": token})
        
        assert result.authenticated
        assert result.principal is not None
        assert result.principal.user_id == "user_001"
        assert result.principal.username == "testuser"
        assert "admin" in result.principal.roles

    def test_invalid_token(self):
        auth = JWTAuth(secret_key="test_secret", token_ttl=3600)
        
        result = auth.authenticate({"token": "invalid_token"})
        
        assert not result.authenticated
        assert result.error is not None

    def test_expired_token(self):
        auth = JWTAuth(secret_key="test_secret", token_ttl=1)
        
        token = auth.create_token(
            user_id="user_001",
            username="testuser",
        )
        
        import time
        time.sleep(1.1)
        
        result = auth.authenticate({"token": token})
        
        assert not result.authenticated
        assert "expired" in result.error.lower()

    def test_token_revocation(self):
        auth = JWTAuth(secret_key="test_secret", token_ttl=3600)
        
        token = auth.create_token(
            user_id="user_001",
            username="testuser",
            additional_claims={"jti": "test_jti"},
        )
        
        auth.revoke_token("test_jti")
        
        result = auth.authenticate({"token": token})
        
        assert not result.authenticated
        assert "revoked" in result.error.lower()


class TestBasicAuth:
    def test_valid_credentials(self):
        auth = BasicAuth()
        auth.register_user(
            username="testuser",
            password="testpass123",
            user_id="user_001",
            roles=["admin"],
        )
        
        credentials = base64.b64encode(b"testuser:testpass123").decode()
        result = auth.authenticate({"basic_token": credentials})
        
        assert result.authenticated
        assert result.principal is not None
        assert result.principal.username == "testuser"

    def test_invalid_password(self):
        auth = BasicAuth()
        auth.register_user(
            username="testuser",
            password="testpass123",
        )
        
        credentials = base64.b64encode(b"testuser:wrongpass").decode()
        result = auth.authenticate({"basic_token": credentials})
        
        assert not result.authenticated

    def test_missing_credentials(self):
        auth = BasicAuth()
        result = auth.authenticate({"basic_token": ""})
        
        assert not result.authenticated


class TestAPIKeyAuth:
    def test_api_key_generation_and_validation(self):
        auth = APIKeyAuth()
        
        api_key = auth.register_api_key(user_id="user_001", expires_in_days=30)
        
        assert api_key.startswith("sk_")
        
        result = auth.authenticate({"api_key": api_key})
        
        assert result.authenticated
        assert result.principal is not None
        assert result.principal.user_id == "user_001"

    def test_expired_api_key(self):
        auth = APIKeyAuth()
        api_key = auth.register_api_key(user_id="user_001", expires_in_days=-1)
        
        result = auth.authenticate({"api_key": api_key})
        
        assert not result.authenticated
        assert "expired" in result.error.lower()

    def test_revoked_api_key(self):
        auth = APIKeyAuth()
        api_key = auth.register_api_key(user_id="user_001", expires_in_days=30)
        
        key_prefix = api_key[:8]
        auth.revoke_api_key(key_prefix)
        
        result = auth.authenticate({"api_key": api_key})
        
        assert not result.authenticated
        assert "revoked" in result.error.lower()


class TestRateLimiters:
    def test_sliding_window_limiter(self):
        limiter = SlidingWindowLimiter(limit=5, window_seconds=60)
        
        for i in range(5):
            result = limiter.check("user1")
            assert result.allowed
            assert result.remaining == 4 - i
        
        result = limiter.check("user1")
        assert not result.allowed
        assert result.retry_after > 0

    def test_token_bucket_limiter(self):
        limiter = TokenBucketLimiter(
            limit=5,
            window_seconds=60,
            burst_limit=10,
            refill_rate=1.0,
        )
        
        for i in range(10):
            result = limiter.check("user1")
            assert result.allowed
        
        result = limiter.check("user1")
        assert not result.allowed

    def test_fixed_window_limiter(self):
        limiter = FixedWindowLimiter(limit=5, window_seconds=1)
        
        for i in range(5):
            result = limiter.check("user1")
            assert result.allowed
        
        result = limiter.check("user1")
        assert not result.allowed

    def test_rate_limit_router(self):
        router = RateLimitRouter()
        
        router.configure_global_limit(
            RateLimitPolicy(
                limit=100,
                window_seconds=60,
                algorithm=RateLimitAlgorithm.SLIDING_WINDOW,
            )
        )
        
        router.configure_resource_limit(
            resource="/api/v1/admin/*",
            policy=RateLimitPolicy(
                limit=10,
                window_seconds=60,
                algorithm=RateLimitAlgorithm.SLIDING_WINDOW,
            ),
            priority=10,
        )
        
        for i in range(10):
            result = router.check("user1", "/api/v1/admin/test")
            assert result.allowed
        
        result = router.check("user1", "/api/v1/admin/test")
        assert not result.allowed


class TestAuthorization:
    def test_role_based_authorization(self):
        auth = JWTAuth(secret_key="test_secret")
        
        admin_role = Role(
            role_id="admin",
            name="Admin",
            permissions=[
                Permission(
                    name="admin_access",
                    level=PermissionLevel.ADMIN,
                    resource_pattern="*",
                )
            ],
        )
        
        viewer_role = Role(
            role_id="viewer",
            name="Viewer",
            permissions=[
                Permission(
                    name="read_access",
                    level=PermissionLevel.READ,
                    resource_pattern="/api/v1/*",
                )
            ],
        )
        
        auth.register_role(admin_role)
        auth.register_role(viewer_role)
        
        token = auth.create_token(
            user_id="user_001",
            username="admin_user",
            roles=["admin"],
        )
        
        result = auth.authenticate({"token": token})
        
        assert result.authenticated
        assert auth.authorize(result.principal, "/api/v1/resources", "write")
        assert auth.authorize(result.principal, "/api/v1/resources", "read")
        
        viewer_token = auth.create_token(
            user_id="user_002",
            username="viewer_user",
            roles=["viewer"],
        )
        
        viewer_result = auth.authenticate({"token": viewer_token})
        
        assert viewer_result.authenticated
        assert auth.authorize(viewer_result.principal, "/api/v1/resources", "read")
        assert not auth.authorize(viewer_result.principal, "/api/v1/resources", "write")
