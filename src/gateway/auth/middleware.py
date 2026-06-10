from typing import Any, Dict, Optional, Tuple
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse

from gateway.config import get_settings, AuthStrategy
from gateway.auth.jwt import get_jwt_validator
from gateway.auth.oauth2 import get_oauth2_plugin
from gateway.auth.mtls import get_mtls_validator
from gateway.auth.api_key import get_api_key_validator
from gateway.db.repository import IdPConfigRepository
from gateway.db import get_db
from gateway.logger import get_logger

logger = get_logger("auth-middleware")


class AuthMiddleware(BaseHTTPMiddleware):
    def __init__(self, app):
        super().__init__(app)
        self.settings = get_settings()
        self.auth_strategies = self.settings.gateway.auth_strategies
        self.jwt_validator = get_jwt_validator()
        self.mtls_validator = get_mtls_validator()
        self.api_key_validator = get_api_key_validator()

    async def dispatch(self, request: Request, call_next):
        if request.method == "OPTIONS":
            return await call_next(request)

        path = request.url.path

        if self._is_public_path(path):
            return await call_next(request)

        strategy = self._get_auth_strategy(path)
        if not strategy:
            request.state.auth_required = False
            return await call_next(request)

        request.state.auth_required = True
        request.state.auth_strategy = strategy.strategy

        try:
            is_authenticated, user_info, error = await self._authenticate(request, strategy)

            if not is_authenticated:
                logger.warning("Authentication failed", path=path, strategy=strategy.strategy, error=error)
                return JSONResponse(
                    status_code=401,
                    content={
                        "error": {
                            "code": 401,
                            "message": "Unauthorized",
                            "detail": error or "Authentication required",
                        }
                    },
                )

            request.state.user = user_info
            request.state.is_authenticated = True

            if not self._authorize(request, user_info):
                return JSONResponse(
                    status_code=403,
                    content={
                        "error": {
                            "code": 403,
                            "message": "Forbidden",
                            "detail": "Insufficient permissions",
                        }
                    },
                )

            return await call_next(request)

        except Exception as e:
            logger.error("Authentication middleware error", error=str(e), exc_info=True)
            return JSONResponse(
                status_code=500,
                content={
                    "error": {
                        "code": 500,
                        "message": "Internal Server Error",
                        "detail": "Authentication service unavailable",
                    }
                },
            )

    def _is_public_path(self, path: str) -> bool:
        public_paths = [
            "/health",
            "/metrics",
            "/docs",
            "/openapi.json",
            "/redoc",
            "/portal/",
            "/static/",
        ]
        return any(path.startswith(p) for p in public_paths)

    def _get_auth_strategy(self, path: str) -> Optional[AuthStrategy]:
        matched_strategy = None
        max_prefix_len = 0

        for strategy in self.auth_strategies:
            if path.startswith(strategy.path_prefix):
                if len(strategy.path_prefix) > max_prefix_len:
                    max_prefix_len = len(strategy.path_prefix)
                    matched_strategy = strategy

        return matched_strategy

    async def _authenticate(self, request: Request, strategy: AuthStrategy) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        auth_type = strategy.strategy.lower()

        if auth_type == "jwt":
            return await self._authenticate_jwt(request, strategy)
        elif auth_type == "oauth2":
            return await self._authenticate_oauth2(request, strategy)
        elif auth_type == "mtls":
            return await self._authenticate_mtls(request, strategy)
        elif auth_type == "api_key":
            return await self._authenticate_api_key(request, strategy)
        elif auth_type == "none" or auth_type == "public":
            return True, {"user_id": "anonymous", "auth_type": "none"}, None
        else:
            return False, None, f"Unknown authentication strategy: {auth_type}"

    async def _authenticate_jwt(self, request: Request, strategy: AuthStrategy) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        token = self._extract_bearer_token(request)
        if not token:
            return False, None, "Bearer token not found"

        if strategy.idp and strategy.idp != "default":
            async for session in get_db():
                repo = IdPConfigRepository(session)
                plugin = await get_oauth2_plugin(strategy.idp, repo)
                if plugin:
                    return await plugin.validate_token(token)
                break

        return await self.jwt_validator.validate(token)

    async def _authenticate_oauth2(self, request: Request, strategy: AuthStrategy) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        token = self._extract_bearer_token(request)
        if not token:
            return False, None, "Bearer token not found"

        if not strategy.idp:
            return False, None, "IdP not configured for OAuth2 strategy"

        async for session in get_db():
            repo = IdPConfigRepository(session)
            plugin = await get_oauth2_plugin(strategy.idp, repo)
            if not plugin:
                return False, None, f"IdP '{strategy.idp}' not found"
            return await plugin.validate_token(token)

        return False, None, "Database unavailable"

    async def _authenticate_mtls(self, request: Request, strategy: AuthStrategy) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        if strategy.mtls_ca_cert:
            self.mtls_validator.add_ca_cert(strategy.path_prefix, strategy.mtls_ca_cert)
        return await self.mtls_validator.validate(request)

    async def _authenticate_api_key(self, request: Request, strategy: AuthStrategy) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        api_key = request.headers.get("X-API-Key") or request.headers.get("api-key")
        if not api_key:
            return False, None, "API Key not found in headers"

        return await self.api_key_validator.validate(api_key)

    def _extract_bearer_token(self, request: Request) -> Optional[str]:
        auth_header = request.headers.get("Authorization")
        if not auth_header:
            return None

        parts = auth_header.split()
        if len(parts) == 2 and parts[0].lower() == "bearer":
            return parts[1]

        return None

    def _authorize(self, request: Request, user_info: Dict[str, Any]) -> bool:
        route_match = getattr(request.state, "route_match", None)
        if not route_match:
            return True

        route = route_match.route
        if not route.auth_required:
            return True

        allowed_paths = user_info.get("allowed_paths", [])
        if allowed_paths:
            request_path = request.url.path
            if not any(request_path.startswith(p) for p in allowed_paths):
                return False

        return True


_auth_middleware_instance: Optional[AuthMiddleware] = None


def get_auth_middleware() -> AuthMiddleware:
    global _auth_middleware_instance
    if _auth_middleware_instance is None:
        _auth_middleware_instance = AuthMiddleware(None)
    return _auth_middleware_instance
