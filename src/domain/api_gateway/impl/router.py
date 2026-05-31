from typing import List, Dict, Any, Optional
import re
from ..models import RouteDefinition, GatewayRequest
from ..interfaces import RequestRouterPort
from src.core import generate_id, PlatformError
import logging

logger = logging.getLogger(__name__)


class RequestRouter(RequestRouterPort):
    def __init__(self):
        self._routes: Dict[str, RouteDefinition] = {}
        self._compiled_patterns: Dict[str, Any] = {}

    async def register_route(self, route: RouteDefinition) -> RouteDefinition:
        route_id = route.route_id or generate_id("route")
        route.route_id = route_id

        try:
            pattern = re.compile(route.path.replace("{", "(?P<").replace("}", ">[^/]+)"))
            self._compiled_patterns[route_id] = pattern
        except re.error as e:
            raise PlatformError(f"Invalid route pattern: {e}")

        self._routes[route_id] = route
        logger.info(f"Registered route: {route.methods} {route.path} -> {route.service_name}")
        return route

    async def unregister_route(self, route_id: str) -> bool:
        if route_id in self._routes:
            del self._routes[route_id]
            if route_id in self._compiled_patterns:
                del self._compiled_patterns[route_id]
            logger.info(f"Unregistered route: {route_id}")
            return True
        return False

    async def list_routes(self) -> List[RouteDefinition]:
        return sorted(
            list(self._routes.values()),
            key=lambda r: (-r.priority, r.created_at),
        )

    async def match_route(self, request: GatewayRequest) -> RouteDefinition:
        best_match = None
        best_priority = -1

        for route in sorted(self._routes.values(), key=lambda r: -r.priority):
            if request.method not in route.methods:
                continue

            pattern = self._compiled_patterns.get(route.route_id)
            if not pattern:
                continue

            if pattern.match(request.path):
                return route

            if route.path == request.path:
                return route

        raise PlatformError(f"No route found for {request.method} {request.path}", 404)
