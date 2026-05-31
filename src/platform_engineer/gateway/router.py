import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Pattern
from uuid import uuid4

from ..core.exceptions import GatewayError


class RoutingDecision(Enum):
    MATCHED = "matched"
    NOT_FOUND = "not_found"
    METHOD_NOT_ALLOWED = "method_not_allowed"
    RATE_LIMITED = "rate_limited"
    UNAUTHORIZED = "unauthorized"


@dataclass
class RequestContext:
    request_id: str = field(default_factory=lambda: uuid4().hex[:12])
    method: str = "GET"
    path: str = "/"
    headers: Dict[str, str] = field(default_factory=dict)
    body: Optional[bytes] = None
    query_params: Dict[str, str] = field(default_factory=dict)
    client_ip: str = ""
    user_agent: str = ""
    start_time: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    attributes: Dict[str, Any] = field(default_factory=dict)
    protocol: str = "http"
    path_params: Dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "request_id": self.request_id,
            "method": self.method,
            "path": self.path,
            "headers": self.headers,
            "query_params": self.query_params,
            "client_ip": self.client_ip,
            "user_agent": self.user_agent,
            "protocol": self.protocol,
            "path_params": self.path_params,
            "attributes": self.attributes,
        }


@dataclass
class Route:
    name: str
    path_pattern: str
    method: str
    target_url: str
    protocols: List[str] = field(default_factory=lambda: ["http"])
    timeout: float = 30.0
    retry_count: int = 0
    enabled: bool = True
    rate_limit: Optional[Dict[str, Any]] = None
    auth_required: bool = False
    metadata: Dict[str, Any] = field(default_factory=dict)
    _compiled_pattern: Optional[Pattern] = None

    def compile(self) -> None:
        pattern = re.escape(self.path_pattern)
        pattern = re.sub(r'\\\{([a-zA-Z_][a-zA-Z0-9_]*)\\\}', r'(?P<\1>[^/]+)', pattern)
        pattern = re.sub(r'\\\*', r'(?P<wildcard>.*)', pattern)
        self._compiled_pattern = re.compile(f'^{pattern}$')

    def matches(self, method: str, path: str) -> bool:
        if self._compiled_pattern is None:
            self.compile()
        if self.method.upper() != method.upper() and self.method.upper() != "ANY":
            return False
        return bool(self._compiled_pattern and self._compiled_pattern.match(path))

    def extract_path_params(self, path: str) -> Dict[str, str]:
        if self._compiled_pattern is None:
            self.compile()
        if not self._compiled_pattern:
            return {}
        match = self._compiled_pattern.match(path)
        if not match:
            return {}
        return match.groupdict()


class RouteRegistry:
    def __init__(self):
        self._routes: Dict[str, Route] = {}
        self._paths: Dict[str, List[Route]] = {}

    def register(self, route: Route) -> None:
        route.compile()
        self._routes[route.name] = route
        if route.path_pattern not in self._paths:
            self._paths[route.path_pattern] = []
        self._paths[route.path_pattern].append(route)

    def unregister(self, name: str) -> bool:
        if name not in self._routes:
            return False
        route = self._routes[name]
        del self._routes[name]
        if route.path_pattern in self._paths:
            self._paths[route.path_pattern] = [r for r in self._paths[route.path_pattern] if r.name != name]
            if not self._paths[route.path_pattern]:
                del self._paths[route.path_pattern]
        return True

    def get_route(self, name: str) -> Optional[Route]:
        return self._routes.get(name)

    def list_routes(self) -> List[Route]:
        return list(self._routes.values())

    def find_route(self, method: str, path: str) -> Optional[Route]:
        for route in self._routes.values():
            if not route.enabled:
                continue
            if route.matches(method, path):
                return route
        return None

    def find_all_matching(self, method: str, path: str) -> List[Route]:
        matches = []
        for route in self._routes.values():
            if not route.enabled:
                continue
            if route.matches(method, path):
                matches.append(route)
        return matches


class APIGateway:
    def __init__(self, logger=None):
        self._registry = RouteRegistry()
        self._logger = logger
        self._middleware_chain: List[Any] = []
        self._metrics = {
            "requests_total": 0,
            "requests_matched": 0,
            "requests_not_found": 0,
            "requests_unauthorized": 0,
            "requests_rate_limited": 0,
        }

    def register_route(self, route: Route) -> None:
        self._registry.register(route)

    def add_route(
        self,
        name: str,
        path_pattern: str,
        method: str,
        target_url: str,
        **kwargs,
    ) -> Route:
        route = Route(
            name=name,
            path_pattern=path_pattern,
            method=method,
            target_url=target_url,
            **kwargs,
        )
        self._registry.register(route)
        return route

    def remove_route(self, name: str) -> bool:
        return self._registry.unregister(name)

    def add_middleware(self, middleware: Any) -> None:
        self._middleware_chain.append(middleware)

    async def route(self, ctx: RequestContext) -> RoutingDecision:
        self._metrics["requests_total"] += 1
        try:
            for middleware in self._middleware_chain:
                if hasattr(middleware, "process_request"):
                    result = middleware.process_request(ctx)
                    if result is not None and hasattr(result, "name") and result.name != "MATCHED":
                        if result.name == "UNAUTHORIZED":
                            self._metrics["requests_unauthorized"] += 1
                        elif result.name == "RATE_LIMITED":
                            self._metrics["requests_rate_limited"] += 1
                        return result
            route = self._registry.find_route(ctx.method, ctx.path)
            if not route:
                self._metrics["requests_not_found"] += 1
                return RoutingDecision.NOT_FOUND
            ctx.path_params = route.extract_path_params(ctx.path)
            ctx.attributes["target_url"] = route.target_url
            ctx.attributes["route_name"] = route.name
            ctx.attributes["route"] = route
            for middleware in reversed(self._middleware_chain):
                if hasattr(middleware, "process_response"):
                    middleware.process_response(ctx, None)
            self._metrics["requests_matched"] += 1
            return RoutingDecision.MATCHED
        except Exception as e:
            if self._logger:
                self._logger.error(f"Gateway routing error: {e}")
            raise GatewayError(f"Routing failed: {e}")

    def get_route_info(self, name: str) -> Optional[Dict[str, Any]]:
        route = self._registry.get_route(name)
        if not route:
            return None
        return {
            "name": route.name,
            "path_pattern": route.path_pattern,
            "method": route.method,
            "target_url": route.target_url,
            "protocols": route.protocols,
            "timeout": route.timeout,
            "retry_count": route.retry_count,
            "enabled": route.enabled,
            "metadata": route.metadata,
        }

    def list_routes(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": r.name,
                "path_pattern": r.path_pattern,
                "method": r.method,
                "target_url": r.target_url,
                "enabled": r.enabled,
            }
            for r in self._registry.list_routes()
        ]

    def get_metrics(self) -> Dict[str, Any]:
        return dict(self._metrics)

    def reset_metrics(self) -> None:
        for key in self._metrics:
            self._metrics[key] = 0


_global_gateway: Optional[APIGateway] = None


def get_gateway() -> APIGateway:
    global _global_gateway
    if _global_gateway is None:
        _global_gateway = APIGateway()
    return _global_gateway


def set_gateway(gateway: APIGateway) -> None:
    global _global_gateway
    _global_gateway = gateway
