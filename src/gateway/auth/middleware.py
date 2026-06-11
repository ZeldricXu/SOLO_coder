"""
认证中间件模块

在请求生命周期中的位置：
    第 3 个中间件（顺序：RequestID → Metrics → SecurityFilter → **Auth** → RouteMatching → RateLimit → ...）
    前一个中间件：SecurityFilterMiddleware（安全过滤器）
    后一个中间件：RouteMatchingMiddleware（路由匹配）

功能职责：
    - 根据路由前缀匹配认证策略（JWT / OAuth2 / mTLS / API Key / None）
    - 执行身份认证（Authentication）
    - 执行基础授权（Authorization）— 路径级别的访问控制
    - 认证成功后将用户信息注入 request.state.user

输入数据结构：
    request: Starlette Request 对象
        - request.headers: 请求头，包含 Authorization / X-API-Key 等
        - request.url.path: 请求路径，用于匹配认证策略前缀

输出数据结构（写入 request.state）：
    - request.state.is_authenticated: bool，是否认证成功
    - request.state.user: Dict[str, Any]，用户信息
        {
            "user_id": "用户唯一标识",
            "username": "用户名",
            "roles": ["角色列表"],
            "scopes": ["权限范围"],
            "auth_type": "认证方式: jwt/oauth2/mtls/api_key/none",
            "tenant_id": "租户ID(可选)",
            "allowed_paths": ["允许访问的路径前缀(可选)"],
        }
    - request.state.auth_strategy: str，使用的认证策略名称
    - request.state.auth_required: bool，该路径是否需要认证

插件扩展点 — 如何注册一个新的 IdP（OAuth2 身份提供商）：
    1. 在数据库的 idp_configs 表中插入一条记录（或通过管理 API 配置）
    2. IdP 配置示例：
        {
            "id": "my-keycloak",
            "name": "My Keycloak",
            "type": "keycloak",
            "config": {
                "issuer": "https://keycloak.example.com/realms/myrealm",
                "client_id": "api-gateway",
                "client_secret": "xxx",
                "jwks_uri": "https://keycloak.example.com/realms/myrealm/protocol/openid-connect/certs",
                "userinfo_endpoint": "https://keycloak.example.com/realms/myrealm/protocol/openid-connect/userinfo",
            }
        }

    3. 在路由配置中绑定认证策略：
        {
            "path_prefix": "/api/internal",
            "strategy": "oauth2",
            "idp": "my-keycloak"
        }

    4. 最小自定义 IdP 插件示例（写在 gateway/auth/plugins/ 目录下）：
        # gateway/auth/plugins/my_idp.py
        from gateway.auth.oauth2 import BaseOAuth2Plugin

        class MyIdPPlugin(BaseOAuth2Plugin):
            name = "my_idp"

            async def validate_token(self, token: str) -> Tuple[bool, Optional[dict], Optional[str]]:
                # 自定义 token 验证逻辑
                payload = self._decode_jwt(token)
                if not payload:
                    return False, None, "Invalid token"

                user_info = {
                    "user_id": payload.get("sub"),
                    "username": payload.get("preferred_username"),
                    "auth_type": "oauth2",
                    "idp": self.idp_id,
                }
                return True, user_info, None

        然后在 gateway/auth/oauth2.py 的 _PLUGIN_REGISTRY 中注册。
"""

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
from gateway.observability import record_auth_success, record_auth_failure
from gateway.logger import get_logger

logger = get_logger("auth-middleware")


class AuthMiddleware(BaseHTTPMiddleware):
    """
    认证授权中间件

    功能职责：
        1. 根据请求路径前缀匹配合适的认证策略（最长前缀匹配）
        2. 执行认证：支持 JWT、OAuth2、mTLS、API Key、公开访问 五种方式
        3. 执行授权：基于路径前缀的访问控制
        4. 认证成功后将用户上下文注入 request.state

    在请求生命周期中的位置：
        第 4 个中间件（按 Starlette 逆序执行，实际是第 6 层）
        前驱：SecurityFilterMiddleware（安全过滤）
        后继：RouteMatchingMiddleware（路由匹配）

    输入：
        - request: Starlette Request 对象
            - headers: 从 Authorization / X-API-Key 等头中提取凭证
            - url.path: 用于匹配认证策略前缀

    输出（写入 request.state）：
        - is_authenticated: bool
        - user: dict 用户信息
        - auth_strategy: str 认证策略名
        - auth_required: bool 是否需要认证

    失败响应：
        - 401 Unauthorized: 认证失败，带 WWW-Authenticate 头
        - 403 Forbidden: 认证成功但无权限访问该路径
    """

    def __init__(self, app):
        super().__init__(app)
        self.settings = get_settings()
        self.auth_strategies = self.settings.gateway.auth_strategies
        self.jwt_validator = get_jwt_validator()
        self.mtls_validator = get_mtls_validator()
        self.api_key_validator = get_api_key_validator()

    async def dispatch(self, request: Request, call_next):
        """
        中间件主入口

        Args:
            request: Starlette 请求对象
            call_next: 调用下一个中间件的回调函数

        Returns:
            Starlette Response 对象
        """
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

                route_match = getattr(request.state, "route_match", None)
                route_name = route_match.route.name if route_match and route_match.route else None
                record_auth_failure(route=route_name, reason=error or "authentication_failed", auth_type=strategy.strategy.lower())

                headers = {}
                auth_type = strategy.strategy.lower()
                if auth_type in ["jwt", "oauth2"]:
                    headers["WWW-Authenticate"] = f'Bearer realm="{path}", error="invalid_token", error_description="{error or "Authentication required"}"'
                elif auth_type == "api_key":
                    headers["WWW-Authenticate"] = 'ApiKey realm="API Gateway"'
                elif auth_type == "mtls":
                    headers["WWW-Authenticate"] = 'Certificate realm="API Gateway"'
                else:
                    headers["WWW-Authenticate"] = f'{strategy.strategy} realm="API Gateway"'

                return JSONResponse(
                    status_code=401,
                    content={
                        "error": {
                            "code": 401,
                            "message": "Unauthorized",
                            "detail": error or "Authentication required",
                        }
                    },
                    headers=headers,
                )

            request.state.user = user_info
            request.state.is_authenticated = True

            route_match = getattr(request.state, "route_match", None)
            route_name = route_match.route.name if route_match and route_match.route else None
            record_auth_success(route=route_name, auth_type=strategy.strategy.lower())

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
        """
        判断是否为公开路径（跳过认证）

        Args:
            path: 请求路径

        Returns:
            True 表示是公开路径，不需要认证
        """
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
        """
        最长前缀匹配认证策略

        遍历所有配置的认证策略，匹配路径前缀最长的那个策略。
        例如：路径 /api/internal/users 同时匹配 /api 和 /api/internal，
             会选择 /api/internal 对应的策略。

        Args:
            path: 请求路径

        Returns:
            AuthStrategy 对象，或 None（表示未找到匹配策略，跳过认证）
        """
        matched_strategy = None
        max_prefix_len = 0

        for strategy in self.auth_strategies:
            if path.startswith(strategy.path_prefix):
                if len(strategy.path_prefix) > max_prefix_len:
                    max_prefix_len = len(strategy.path_prefix)
                    matched_strategy = strategy

        return matched_strategy

    async def _authenticate(self, request: Request, strategy: AuthStrategy) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        """
        根据策略类型调度到对应的认证方法

        Args:
            request: 请求对象
            strategy: 认证策略配置

        Returns:
            Tuple[是否认证成功, 用户信息字典或None, 错误信息或None]
        """
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
        """
        JWT 认证：验证 Bearer Token 的签名和有效期

        如果 strategy.idp 指定了非 default 的 IdP，则尝试用对应的 OAuth2 插件验证；
        否则使用默认的 JWT 验证器（本地密钥 + 本地配置）。

        Args:
            request: 请求对象
            strategy: 认证策略

        Returns:
            同 _authenticate 方法
        """
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
        """
        OAuth2 认证：通过指定的 IdP 插件验证 token

        支持多 IdP（Keycloak、Auth0、自建 OAuth Server 等），
        每个 IdP 对应一个插件实例，从数据库加载配置。

        Args:
            request: 请求对象
            strategy: 认证策略（必须指定 idp 字段）

        Returns:
            同 _authenticate 方法
        """
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
        """
        mTLS 双向 TLS 认证

        验证客户端证书的有效性（是否由受信任的 CA 签发、是否过期、是否被吊销）。
        通常用于内部服务之间的调用（如 /api/internal/* 路径）。

        Args:
            request: 请求对象
            strategy: 认证策略（可配置 mtls_ca_cert 指定 CA 证书）

        Returns:
            同 _authenticate 方法
        """
        if strategy.mtls_ca_cert:
            self.mtls_validator.add_ca_cert(strategy.path_prefix, strategy.mtls_ca_cert)
        return await self.mtls_validator.validate(request)

    async def _authenticate_api_key(self, request: Request, strategy: AuthStrategy) -> Tuple[bool, Optional[Dict[str, Any]], Optional[str]]:
        """
        API Key 认证：从 X-API-Key 头提取并验证

        API Key 存储在 PostgreSQL 的 api_keys 表中，支持启用/禁用、配额管理等。
        主要用于开发者门户的 API 调用场景。

        Args:
            request: 请求对象
            strategy: 认证策略

        Returns:
            同 _authenticate 方法，用户信息包含 api_key_id、plan_id 等
        """
        api_key = request.headers.get("X-API-Key") or request.headers.get("api-key")
        if not api_key:
            return False, None, "API Key not found in headers"

        return await self.api_key_validator.validate(api_key)

    def _extract_bearer_token(self, request: Request) -> Optional[str]:
        """
        从 Authorization 头中提取 Bearer Token

        支持格式：
            Authorization: Bearer <token>
            authorization: bearer <token> （大小写不敏感）

        Args:
            request: 请求对象

        Returns:
            Token 字符串，或 None（未找到有效 Bearer token）
        """
        auth_header = request.headers.get("Authorization")
        if not auth_header:
            return None

        parts = auth_header.split()
        if len(parts) == 2 and parts[0].lower() == "bearer":
            return parts[1]

        return None

    def _authorize(self, request: Request, user_info: Dict[str, Any]) -> bool:
        """
        基础授权检查：验证用户是否有权限访问当前路径

        检查逻辑：
            1. 如果路由配置了 auth_required=false，直接放行
            2. 如果用户信息包含 allowed_paths，验证当前路径是否在允许列表中

        Args:
            request: 请求对象
            user_info: 用户信息字典

        Returns:
            True 表示有权限，False 表示无权限
        """
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
    """
    获取认证中间件单例

    Returns:
        AuthMiddleware 单例实例
    """
    global _auth_middleware_instance
    if _auth_middleware_instance is None:
        _auth_middleware_instance = AuthMiddleware(None)
    return _auth_middleware_instance
