from typing import Dict, List, Optional
import asyncio
import fnmatch

from gateway.db.models import Route
from gateway.db.repository import RouteRepository
from gateway.routing.models import RouteConfig, RouteMatch
from gateway.logger import get_logger

logger = get_logger("router")


class Router:
    def __init__(self):
        self._routes: List[RouteConfig] = []
        self._prefix_routes: Dict[str, RouteConfig] = {}
        self._regex_routes: List[RouteConfig] = []
        self._weighted_routes: List[RouteConfig] = []
        self._version: int = 0
        self._lock = asyncio.Lock()

    @property
    def version(self) -> int:
        return self._version

    @property
    def route_count(self) -> int:
        return len(self._routes)

    async def load_routes(self, repo: RouteRepository) -> None:
        async with self._lock:
            db_routes = await repo.get_all_active()
            new_version = await repo.get_max_version()

            self._routes = []
            self._prefix_routes = {}
            self._regex_routes = []
            self._weighted_routes = []

            for db_route in db_routes:
                route_config = RouteConfig.from_db_model(db_route)
                self._routes.append(route_config)

                if route_config.match_type == "prefix":
                    self._prefix_routes[route_config.path] = route_config
                elif route_config.match_type == "regex":
                    self._regex_routes.append(route_config)
                elif route_config.match_type == "weighted":
                    self._weighted_routes.append(route_config)

            self._routes.sort(key=lambda r: len(r.path), reverse=True)

            self._version = new_version
            logger.info("Routes loaded", count=len(self._routes), version=new_version,
                        prefix_count=len(self._prefix_routes),
                        regex_count=len(self._regex_routes),
                        weighted_count=len(self._weighted_routes))

    async def match(self, path: str, method: str, user_id: Optional[str] = None) -> Optional[RouteMatch]:
        async with self._lock:
            for route in self._routes:
                if not route.matches_method(method):
                    continue

                matched = self._match_route(route, path)
                if matched:
                    target = route.select_target(user_id)
                    if target:
                        return RouteMatch(
                            route=route,
                            matched_path=path,
                            target=target,
                            path_params=matched.get("params", {}),
                        )

            return None

    def _match_route(self, route: RouteConfig, path: str) -> Optional[Dict]:
        if route.match_type == "prefix":
            if path.startswith(route.path):
                return {"params": {}}
        elif route.match_type == "regex" and route.compiled_pattern:
            match = route.compiled_pattern.match(path)
            if match:
                return {"params": match.groupdict()}
        elif route.match_type == "weighted":
            if path.startswith(route.path):
                return {"params": {}}

        return None

    def get_route_by_name(self, name: str) -> Optional[RouteConfig]:
        for route in self._routes:
            if route.name == name:
                return route
        return None

    def get_all_routes(self) -> List[RouteConfig]:
        return list(self._routes)


_router_instance: Optional[Router] = None


def get_router() -> Router:
    global _router_instance
    if _router_instance is None:
        _router_instance = Router()
    return _router_instance
