import uuid
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class RouteFactory:
    @staticmethod
    def create_route_dict(
        name: str = "test-route",
        path: str = "/api/test",
        match_type: str = "prefix",
        path_pattern: Optional[str] = None,
        targets: Optional[List[Dict[str, Any]]] = None,
        methods: Optional[List[str]] = None,
        auth_required: bool = True,
        auth_strategy: Optional[str] = None,
        rate_limit_enabled: bool = True,
        rate_limit_per_user: Optional[int] = None,
        rate_limit_per_api: Optional[int] = None,
        circuit_breaker_enabled: bool = True,
        circuit_breaker_config: Optional[Dict[str, Any]] = None,
        transform_request: Optional[Dict[str, Any]] = None,
        transform_response: Optional[Dict[str, Any]] = None,
        timeout: int = 30,
        is_active: bool = True,
        version: int = 1,
        weight_rules: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        if targets is None:
            targets = [{"url": "http://localhost:9001", "weight": 1}]

        return {
            "id": uuid.uuid4(),
            "name": name,
            "description": f"Test route: {name}",
            "path": path,
            "match_type": match_type,
            "path_pattern": path_pattern,
            "targets": targets,
            "weight_rules": weight_rules,
            "methods": methods or [],
            "auth_required": auth_required,
            "auth_strategy": auth_strategy,
            "rate_limit_enabled": rate_limit_enabled,
            "rate_limit_per_user": rate_limit_per_user,
            "rate_limit_per_api": rate_limit_per_api,
            "circuit_breaker_enabled": circuit_breaker_enabled,
            "circuit_breaker_config": circuit_breaker_config,
            "transform_request": transform_request,
            "transform_response": transform_response,
            "timeout": timeout,
            "retry_count": 0,
            "is_active": is_active,
            "version": version,
            "created_at": utc_now(),
            "updated_at": utc_now(),
        }

    @staticmethod
    def create_prefix_route(name: str, path: str, target_url: str) -> Dict[str, Any]:
        return RouteFactory.create_route_dict(
            name=name,
            path=path,
            match_type="prefix",
            targets=[{"url": target_url, "weight": 1}],
        )

    @staticmethod
    def create_regex_route(name: str, path: str, pattern: str, target_url: str) -> Dict[str, Any]:
        return RouteFactory.create_route_dict(
            name=name,
            path=path,
            match_type="regex",
            path_pattern=pattern,
            targets=[{"url": target_url, "weight": 1}],
        )

    @staticmethod
    def create_weighted_route(name: str, path: str, targets: List[Dict[str, Any]],
                               weight_rules: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        return RouteFactory.create_route_dict(
            name=name,
            path=path,
            match_type="weighted",
            targets=targets,
            weight_rules=weight_rules,
        )


class APIKeyFactory:
    @staticmethod
    def create_api_key_dict(
        key: Optional[str] = None,
        name: str = "test-api-key",
        user_id: str = "test-user-123",
        tenant_id: Optional[str] = "test-tenant",
        scopes: Optional[List[str]] = None,
        status: str = "approved",
        expires_in_days: Optional[int] = 365,
        rate_limit_quota: Optional[int] = None,
        created_by: str = "test-user-123",
        approved_by: Optional[str] = "admin",
    ) -> Dict[str, Any]:
        import secrets
        key_value = key or secrets.token_urlsafe(32)
        now = utc_now()
        expires_at = now + timedelta(days=expires_in_days) if expires_in_days else None

        return {
            "id": uuid.uuid4(),
            "key": key_value,
            "name": name,
            "description": f"Test API key: {name}",
            "user_id": user_id,
            "tenant_id": tenant_id,
            "scopes": scopes or ["read", "write"],
            "allowed_paths": None,
            "rate_limit_quota": rate_limit_quota,
            "status": status,
            "expires_at": expires_at,
            "last_used_at": None,
            "created_by": created_by,
            "approved_by": approved_by,
            "approved_at": now if status == "approved" else None,
            "created_at": now,
            "updated_at": now,
        }


class JWTFactory:
    @staticmethod
    def create_token(
        user_id: str = "test-user-123",
        username: str = "testuser",
        email: str = "test@example.com",
        roles: Optional[List[str]] = None,
        scopes: Optional[List[str]] = None,
        tenant_id: str = "test-tenant",
        expires_in_minutes: int = 30,
        issuer: str = "api-gateway",
        audience: str = "api-services",
        secret_key: Optional[str] = None,
        algorithm: str = "HS256",
        expired: bool = False,
    ) -> str:
        from jose import jwt

        now = utc_now()
        if expired:
            exp = now - timedelta(minutes=1)
        else:
            exp = now + timedelta(minutes=expires_in_minutes)

        from gateway.config import get_settings
        settings = get_settings()
        secret = secret_key or settings.jwt.secret_key

        payload = {
            "sub": user_id,
            "preferred_username": username,
            "username": username,
            "email": email,
            "roles": roles or ["user"],
            "scope": " ".join(scopes) if scopes else "read write",
            "tenant_id": tenant_id,
            "iat": int(now.timestamp()),
            "exp": int(exp.timestamp()),
            "iss": issuer,
            "aud": audience,
        }

        return jwt.encode(payload, secret, algorithm=algorithm)


class CircuitBreakerConfigFactory:
    @staticmethod
    def create_config(
        failure_threshold: float = 0.5,
        slow_request_threshold: float = 0.5,
        slow_request_duration: float = 5.0,
        wait_duration: int = 30,
        half_open_calls: int = 5,
        fallback_response: Optional[Dict[str, Any]] = None,
        fallback_target: Optional[str] = None,
    ) -> Dict[str, Any]:
        return {
            "failure_threshold": failure_threshold,
            "slow_request_threshold": slow_request_threshold,
            "slow_request_duration": slow_request_duration,
            "wait_duration": wait_duration,
            "half_open_calls": half_open_calls,
            "fallback_response": fallback_response,
            "fallback_target": fallback_target,
        }

    @staticmethod
    def create_fallback_response(
        message: str = "Service temporarily unavailable",
        code: str = "SERVICE_UNAVAILABLE",
    ) -> Dict[str, Any]:
        return {
            "data": {
                "message": message,
                "code": code,
                "fallback": True,
            }
        }


class MockDownstreamService:
    def __init__(self, host: str = "127.0.0.1", port: int = 0, delay: float = 0.0,
                 status_code: int = 200, response_data: Optional[Dict[str, Any]] = None):
        self.host = host
        self.port = port
        self.delay = delay
        self.status_code = status_code
        self.response_data = response_data or {"status": "ok"}
        self.request_count = 0
        self._server = None
        self._handler = None

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    async def start(self) -> None:
        from aiohttp import web

        async def handler(request):
            self.request_count += 1
            if self.delay > 0:
                import asyncio
                await asyncio.sleep(self.delay)
            return web.json_response(self.response_data, status=self.status_code)

        app = web.Application()
        app.router.add_route("*", "/{tail:.*}", handler)

        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, self.host, self.port)
        await site.start()

        self._server = runner
        actual_port = runner.addresses[0][1] if runner.addresses else self.port
        if self.port == 0:
            self.port = actual_port

    async def stop(self) -> None:
        if self._server:
            await self._server.cleanup()
            self._server = None

    def reset(self) -> None:
        self.request_count = 0
