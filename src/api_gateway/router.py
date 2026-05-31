from __future__ import annotations

from datetime import timedelta
from typing import Any, Dict, Optional

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pydantic import BaseModel, SecretStr

from src.common.models import APIResponse
from src.api_gateway.auth import AuthService, User, TokenData, PermissionChecker
from src.api_gateway.rate_limit import RateLimiter, RateLimitConfig

router = APIRouter(prefix="/gateway", tags=["API Gateway"])

security = HTTPBearer(auto_error=False)

_auth_service: Optional[AuthService] = None
_permission_checker: Optional[PermissionChecker] = None
_rate_limiter: Optional[RateLimiter] = None


def get_auth_service() -> AuthService:
    global _auth_service
    if _auth_service is None:
        _auth_service = AuthService(
            secret_key="dev-secret-key-change-in-production",
            algorithm="HS256",
            access_token_expire_minutes=30,
        )
        _auth_service.register_user(
            User(user_id="admin_001", username="admin", email="admin@example.com", roles=["admin"], permissions=["*"]),
            "admin123",
        )
        _auth_service.register_user(
            User(user_id="user_001", username="user", email="user@example.com", roles=["user"], permissions=["read"]),
            "user123",
        )
    return _auth_service


def get_permission_checker() -> PermissionChecker:
    global _permission_checker
    if _permission_checker is None:
        _permission_checker = PermissionChecker()
        _permission_checker.add_role_permissions("admin", ["*"])
        _permission_checker.add_role_permissions("user", ["read", "write"])
    return _permission_checker


def get_rate_limiter() -> RateLimiter:
    global _rate_limiter
    if _rate_limiter is None:
        _rate_limiter = RateLimiter(default_config=RateLimitConfig(requests=100, window_seconds=60))
    return _rate_limiter


async def get_current_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(security),
    auth_service: AuthService = Depends(get_auth_service),
) -> TokenData:
    if credentials is None:
        raise HTTPException(status_code=401, detail="Not authenticated")
    try:
        return auth_service.decode_token(credentials.credentials)
    except Exception as e:
        raise HTTPException(status_code=401, detail=str(e))


class LoginRequest(BaseModel):
    username: str
    password: str


class LoginResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int = 1800


@router.post("/auth/login")
async def login(
    request: LoginRequest,
    auth_service: AuthService = Depends(get_auth_service),
) -> APIResponse:
    user = auth_service.authenticate_user(request.username, request.password)
    access_token = auth_service.create_access_token(user)
    return APIResponse(
        data=LoginResponse(
            access_token=access_token,
            expires_in=auth_service.access_token_expire_minutes * 60,
        )
    )


@router.get("/auth/me")
async def get_me(
    current_user: TokenData = Depends(get_current_user),
    permission_checker: PermissionChecker = Depends(get_permission_checker),
) -> APIResponse:
    return APIResponse(
        data={
            "user_id": current_user.user_id,
            "username": current_user.username,
            "roles": current_user.roles,
            "permissions": list(permission_checker.get_user_permissions(current_user)),
            "tenant_id": current_user.tenant_id,
        }
    )


@router.post("/auth/check-permission")
async def check_permission(
    permission: str,
    current_user: TokenData = Depends(get_current_user),
    permission_checker: PermissionChecker = Depends(get_permission_checker),
) -> APIResponse:
    has_perm = permission_checker.has_permission(current_user, permission)
    return APIResponse(data={"permission": permission, "allowed": has_perm})


@router.get("/rate-limit/stats")
async def get_rate_limit_stats(
    rate_limiter: RateLimiter = Depends(get_rate_limiter),
    current_user: TokenData = Depends(get_current_user),
) -> APIResponse:
    return APIResponse(data=rate_limiter.get_stats())


@router.post("/rate-limit/config")
async def set_rate_limit_config(
    endpoint: str,
    requests: int,
    window_seconds: int,
    rate_limiter: RateLimiter = Depends(get_rate_limiter),
    current_user: TokenData = Depends(get_current_user),
) -> APIResponse:
    rate_limiter.set_config(endpoint, RateLimitConfig(requests=requests, window_seconds=window_seconds))
    return APIResponse(data={"endpoint": endpoint, "requests": requests, "window_seconds": window_seconds})


@router.middleware("http")
async def rate_limit_middleware(request: Request, call_next: Any) -> Response:
    rate_limiter = get_rate_limiter()
    try:
        headers = dict(request.headers)
        ip = request.client.host if request.client else "unknown"
        result = rate_limiter.check_rate_limit(request.url.path, headers, ip)
        response = await call_next(request)
        response.headers["X-RateLimit-Limit"] = str(result.get("limit", 100))
        response.headers["X-RateLimit-Remaining"] = str(result.get("remaining", 0))
        response.headers["X-RateLimit-Reset"] = str(result.get("reset", 0))
        return response
    except HTTPException as e:
        if e.status_code == 429:
            return Response(content=e.detail, status_code=429)
        raise
