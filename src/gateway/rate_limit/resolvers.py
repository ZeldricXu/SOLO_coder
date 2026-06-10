from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional
from starlette.requests import Request
import fnmatch
import re

from gateway.config import get_settings, RateLimitDimension
from gateway.logger import get_logger

logger = get_logger("rate-limit-resolver")


class RateLimitKeyResolver(ABC):
    @abstractmethod
    async def resolve(self, request: Request, context: Dict[str, Any]) -> Optional[str]:
        pass

    def get_name(self) -> str:
        return self.__class__.__name__


class UserIdResolver(RateLimitKeyResolver):
    async def resolve(self, request: Request, context: Dict[str, Any]) -> Optional[str]:
        user = getattr(request.state, "user", None)
        if user and isinstance(user, dict) and user.get("user_id"):
            return f"user_{user['user_id']}"
        return None


class IPResolver(RateLimitKeyResolver):
    async def resolve(self, request: Request, context: Dict[str, Any]) -> Optional[str]:
        client_ip = request.client.host if request.client else None
        if client_ip:
            return f"ip_{client_ip}"
        return None


class ApiKeyResolver(RateLimitKeyResolver):
    async def resolve(self, request: Request, context: Dict[str, Any]) -> Optional[str]:
        api_key = request.headers.get("X-API-Key") or request.headers.get("x-api-key")
        if not api_key:
            api_key = context.get("api_key_id")
        if api_key:
            return f"apikey_{api_key[:8]}"
        return None


class HeaderResolver(RateLimitKeyResolver):
    def __init__(self, header_name: str = "X-Service-Name"):
        self.header_name = header_name

    async def resolve(self, request: Request, context: Dict[str, Any]) -> Optional[str]:
        value = request.headers.get(self.header_name)
        if value:
            safe_value = re.sub(r'[^a-zA-Z0-9_\-]', '_', value)
            return f"header_{self.header_name.lower()}_{safe_value}"
        return None


class PathResolver(RateLimitKeyResolver):
    async def resolve(self, request: Request, context: Dict[str, Any]) -> Optional[str]:
        api_path = context.get("api_path") or request.url.path
        if api_path:
            return f"api_{api_path}"
        return None


class CompositeKeyResolver:
    def __init__(self):
        self.settings = get_settings()
        self.rl_settings = self.settings.rate_limit
        self._resolvers: Dict[str, RateLimitKeyResolver] = {}
        self._init_resolvers()

    def _init_resolvers(self) -> None:
        self._resolvers = {
            "user_id": UserIdResolver(),
            "ip": IPResolver(),
            "api_key": ApiKeyResolver(),
            "api_path": PathResolver(),
        }

    def register_resolver(self, name: str, resolver: RateLimitKeyResolver) -> None:
        self._resolvers[name] = resolver
        logger.info("Rate limit key resolver registered", name=name)

    async def resolve_keys(self, request: Request, context: Dict[str, Any]) -> List[str]:
        if not self.rl_settings.multi_dimension_enabled:
            user_id = context.get("user_id")
            api_path = context.get("api_path") or request.url.path
            keys = []
            if user_id:
                keys.append(f"user:{user_id}:{api_path}")
            keys.append(f"api:{api_path}")
            return keys

        dimension_parts = []

        for dim in self.rl_settings.dimensions:
            if not dim.enabled:
                continue

            resolver = self._get_resolver(dim)
            if not resolver:
                continue

            try:
                value = await resolver.resolve(request, context)
                if value:
                    dimension_parts.append(value)
            except Exception as e:
                logger.error("Failed to resolve dimension", dimension=dim.name, error=str(e))

        if not dimension_parts:
            api_path = context.get("api_path") or request.url.path
            dimension_parts.append(f"api_{api_path}")

        combined_key = self.rl_settings.dimension_separator.join(dimension_parts)

        keys = [combined_key]

        pattern_keys = self._match_pattern_rules(dimension_parts)
        keys.extend(pattern_keys)

        return keys

    def _get_resolver(self, dimension: RateLimitDimension) -> Optional[RateLimitKeyResolver]:
        resolver_name = dimension.resolver

        if resolver_name == "header":
            header_name = dimension.pattern or "X-Service-Name"
            return HeaderResolver(header_name)

        return self._resolvers.get(resolver_name)

    def _match_pattern_rules(self, dimension_parts: List[str]) -> List[str]:
        matched_keys = []
        dim_str = self.rl_settings.dimension_separator.join(dimension_parts)

        for rule in self.rl_settings.pattern_rules:
            pattern = rule.get("pattern")
            if not pattern:
                continue

            if fnmatch.fnmatch(dim_str, pattern):
                key_prefix = rule.get("key_prefix", "pattern")
                matched_keys.append(f"{key_prefix}:{pattern}")

        return matched_keys

    async def resolve_for_limiter(self, request: Request, api_path: str,
                                   user_id: Optional[str] = None) -> List[str]:
        context = {
            "api_path": api_path,
            "user_id": user_id,
        }
        return await self.resolve_keys(request, context)


_resolver_instance: Optional[CompositeKeyResolver] = None


def get_rate_limit_key_resolver() -> CompositeKeyResolver:
    global _resolver_instance
    if _resolver_instance is None:
        _resolver_instance = CompositeKeyResolver()
    return _resolver_instance
