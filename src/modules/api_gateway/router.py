from typing import Dict, List, Optional
from .types import RouteDefinition, GatewayRequest, HTTPMethod
from src.core import NotFoundError, generate_id
import logging
import re

logger = logging.getLogger(__name__)


class RequestRouter:
    def __init__(self):
        self._routes: Dict[str, RouteDefinition] = {}
        self._route_patterns: List[tuple] = []

    async def register_route(self, route: RouteDefinition) -> RouteDefinition:
        route.route_id = route.route_id or generate_id("route")
        self._routes[route.route_id] = route

        pattern = re.compile(f"^{route.path.replace('*', '.*')}$")
        self._route_patterns.append((pattern, route))
        self._route_patterns.sort(key=lambda x: len(x[1].path), reverse=True)

        logger.info(f"Registered route: {route.path} -> {route.service_name}, id={route.route_id}")
        return route

    async def unregister_route(self, route_id: str) -> bool:
        if route_id not in self._routes:
            return False

        route = self._routes[route_id]
        del self._routes[route_id]
        self._route_patterns = [(p, r) for p, r in self._route_patterns if r.route_id != route_id]

        logger.info(f"Unregistered route: {route.path}")
        return True

    async def match_route(self, request: GatewayRequest) -> RouteDefinition:
        for pattern, route in self._route_patterns:
            if pattern.match(request.path):
                if request.method not in route.methods:
                    continue
                logger.debug(f"Route matched: {request.path} -> {route.service_name}")
                return route

        raise NotFoundError(f"No route found for path: {request.path}, method: {request.method}")

    async def get_route(self, route_id: str) -> RouteDefinition:
        route = self._routes.get(route_id)
        if not route:
            raise NotFoundError(f"Route not found: {route_id}")
        return route

    async def list_routes(self) -> List[RouteDefinition]:
        return list(self._routes.values())

    async def update_route(self, route_id: str, updates: Dict[str, any]) -> RouteDefinition:
        route = await self.get_route(route_id)
        for key, value in updates.items():
            if hasattr(route, key):
                setattr(route, key, value)
        self._routes[route_id] = route
        return route
