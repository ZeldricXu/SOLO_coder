import asyncio
import math
import uuid
from typing import Dict, List
from unittest.mock import MagicMock, AsyncMock

import pytest

from gateway.routing.router import Router
from gateway.routing.models import RouteConfig, RouteTarget, RouteMatch
from gateway.db.models import Route


pytestmark = [pytest.mark.unit, pytest.mark.asyncio]


class TestPrefixMatching:
    @pytest.mark.parametrize("path,expected_route_name,expected_match_type", [
        ("/api/users", "users-api", "prefix"),
        ("/api/users/", "users-api", "prefix"),
        ("/api/users/123/orders", "users-api", "prefix"),
        ("/api/orders", "orders-api", "prefix"),
        ("/api/orders/123", "orders-api", "prefix"),
        ("/api/public", "public-api", "prefix"),
        ("/api/internal/health", "internal-api", "prefix"),
    ])
    async def test_prefix_match_correct_route(self, router_with_routes: Router, path: str, expected_route_name: str, expected_match_type: str):
        result = await router_with_routes.match(path, "GET")
        assert result is not None
        assert result.route.name == expected_route_name
        assert result.route.match_type == expected_match_type

    async def test_prefix_no_match(self, router_with_routes: Router):
        result = await router_with_routes.match("/api/nonexistent", "GET")
        assert result is None

    async def test_prefix_match_longest_path_first(self, router_with_routes: Router):
        result = await router_with_routes.match("/api/users/123", "GET")
        assert result is not None
        assert result.route.name == "user-detail-api"
        assert result.route.match_type == "regex"

    async def test_prefix_method_filtering(self, router_with_routes: Router):
        route = router_with_routes.get_route_by_name("users-api")
        assert route is not None
        route.methods = ["GET", "POST"]

        result_get = await router_with_routes.match("/api/users", "GET")
        assert result_get is not None
        assert result_get.route.name == "users-api"

        result_post = await router_with_routes.match("/api/users", "POST")
        assert result_post is not None
        assert result_post.route.name == "users-api"

        result_delete = await router_with_routes.match("/api/users", "DELETE")
        assert result_delete is None


class TestRegexMatching:
    async def test_regex_match_user_detail(self, router_with_routes: Router):
        result = await router_with_routes.match("/api/users/abc123", "GET")
        assert result is not None
        assert result.route.name == "user-detail-api"
        assert result.path_params == {"user_id": "abc123"}

    async def test_regex_match_with_uuid(self, router_with_routes: Router):
        test_uuid = str(uuid.uuid4())
        result = await router_with_routes.match(f"/api/users/{test_uuid}", "GET")
        assert result is not None
        assert result.path_params["user_id"] == test_uuid

    async def test_regex_no_match_for_subpath(self, router_with_routes: Router):
        result = await router_with_routes.match("/api/users/abc123/orders", "GET")
        assert result is not None
        assert result.route.name == "users-api"

    async def test_regex_no_match_for_list_endpoint(self, router_with_routes: Router):
        result = await router_with_routes.match("/api/users", "GET")
        assert result is not None
        assert result.route.name == "users-api"

    async def test_regex_invalid_pattern_handled_gracefully(self):
        from gateway.routing.models import RouteConfig

        route = RouteConfig(
            id=uuid.uuid4(),
            name="bad-regex",
            path="/api/bad",
            match_type="regex",
            path_pattern="[invalid",
            targets=[RouteTarget(url="http://localhost:9000")],
        )
        route.compile()
        assert route.compiled_pattern is None


class TestWeightedRouting:
    async def test_weighted_route_matches_prefix(self, router_with_routes: Router):
        result = await router_with_routes.match("/api/weighted/test", "GET")
        assert result is not None
        assert result.route.name == "weighted-api"
        assert result.route.match_type == "weighted"

    async def test_weight_distribution_within_confidence_interval(self, router_with_routes: Router):
        num_requests = 1000
        target_counts = {}
        route = router_with_routes.get_route_by_name("weighted-api")
        assert route is not None

        for _ in range(num_requests):
            target = route.select_target()
            assert target is not None
            target_counts[target.url] = target_counts.get(target.url, 0) + 1

        total_weight = sum(t.weight for t in route.targets)
        weights = {t.url: t.weight / total_weight for t in route.targets}

        assert len(target_counts) == 2

        for url, expected_ratio in weights.items():
            actual_count = target_counts.get(url, 0)
            actual_ratio = actual_count / num_requests

            expected_count = num_requests * expected_ratio
            std_dev = math.sqrt(num_requests * expected_ratio * (1 - expected_ratio))
            margin_of_error = 1.96 * std_dev

            lower_bound = expected_count - margin_of_error
            upper_bound = expected_count + margin_of_error

            assert lower_bound <= actual_count <= upper_bound, \
                f"{url}: actual={actual_count}, expected range=[{lower_bound:.1f}, {upper_bound:.1f}]"

    async def test_weighted_routes_all_healthy(self, router_with_routes: Router):
        route = router_with_routes.get_route_by_name("weighted-api")
        assert route is not None

        target = route.select_target()
        assert target is not None
        assert target.is_healthy

    async def test_weighted_unhealthy_targets_skipped(self, router_with_routes: Router):
        route = router_with_routes.get_route_by_name("weighted-api")
        assert route is not None

        for target in route.targets[:-1]:
            target.is_healthy = False

        target = route.select_target()
        assert target is not None
        assert target.url == route.targets[-1].url

    async def test_all_unhealthy_returns_none(self, router_with_routes: Router):
        route = router_with_routes.get_route_by_name("weighted-api")
        assert route is not None

        for target in route.targets:
            target.is_healthy = False

        target = route.select_target()
        assert target is None


class TestRouteExactMatchPriority:
    """Test that more specific routes (regex) match before less specific (prefix)."""

    async def test_user_id_matches_regex_not_prefix(self, router_with_routes: Router):
        """Key test: /api/users/123 should match user-detail-api (regex), not users-api (prefix)."""
        result = await router_with_routes.match("/api/users/123", "GET")

        assert result is not None
        assert result.route.name == "user-detail-api"
        assert result.route.match_type == "regex"
        assert result.path_params == {"user_id": "123"}

    async def test_user_list_matches_prefix(self, router_with_routes: Router):
        result = await router_with_routes.match("/api/users", "GET")
        assert result is not None
        assert result.route.name == "users-api"
        assert result.route.match_type == "prefix"

    async def test_user_list_slash_matches_prefix(self, router_with_routes: Router):
        result = await router_with_routes.match("/api/users/", "GET")
        assert result is not None
        assert result.route.name == "users-api"
        assert result.route.match_type == "prefix"

    async def test_nested_path_under_user_id_matches_prefix(self, router_with_routes: Router):
        result = await router_with_routes.match("/api/users/123/orders", "GET")
        assert result is not None
        assert result.route.name == "users-api"
        assert result.route.match_type == "prefix"


class TestRouteReload:
    async def test_initial_load_sets_version(self, mock_route_repository):
        router = Router()
        await router.load_routes(mock_route_repository)

        assert router.route_count > 0
        assert router.version == 1

    async def test_reload_with_new_version(self, sample_routes):
        from gateway.db.repository import RouteRepository

        mock_repo = MagicMock()
        mock_repo._call_count = 0
        mock_repo._version_count = 0

        routes_v1 = sample_routes[:3]
        routes_v2 = sample_routes

        async def get_all_active_side_effect():
            from gateway.db.models import Route
            call_count = mock_repo._call_count
            mock_repo._call_count = call_count + 1
            route_data = routes_v2 if call_count > 0 else routes_v1
            return [Route(**r) for r in route_data]

        async def get_max_version_side_effect():
            call_count = mock_repo._version_count
            mock_repo._version_count = call_count + 1
            return 2 if call_count > 0 else 1

        mock_repo.get_all_active = AsyncMock(side_effect=get_all_active_side_effect)
        mock_repo.get_max_version = AsyncMock(side_effect=get_max_version_side_effect)

        router = Router()
        await router.load_routes(mock_repo)
        initial_count = router.route_count
        assert initial_count == 3
        assert router.version == 1

        await router.load_routes(mock_repo)
        assert router.route_count > initial_count
        assert router.version == 2

    async def test_get_route_by_name(self, router_with_routes: Router):
        route = router_with_routes.get_route_by_name("users-api")
        assert route is not None
        assert route.name == "users-api"

    async def test_get_route_by_name_not_found(self, router_with_routes: Router):
        route = router_with_routes.get_route_by_name("nonexistent")
        assert route is None


class TestConcurrentRouteAccess:
    """Tests for concurrent access scenarios during route table updates."""

    async def test_concurrent_match_during_reload(self, sample_routes):
        from gateway.db.repository import RouteRepository

        router = Router()
        mock_repo = MagicMock()

        async def slow_get_all_active():
            await asyncio.sleep(0.01)
            from gateway.db.models import Route
            return [Route(**r) for r in sample_routes]

        async def get_max_version():
            return 1

        mock_repo.get_all_active = AsyncMock(side_effect=slow_get_all_active)
        mock_repo.get_max_version = AsyncMock(side_effect=get_max_version)

        async def match_task(path: str):
            return await router.match(path, "GET")

        reload_task = asyncio.create_task(router.load_routes(mock_repo))

        await asyncio.sleep(0.001)

        match_tasks = [
            asyncio.create_task(match_task("/api/users")),
            asyncio.create_task(match_task("/api/orders")),
            asyncio.create_task(match_task("/api/users/123")),
            asyncio.create_task(match_task("/api/nonexistent")),
        ]

        results = await asyncio.gather(reload_task, *match_tasks)

        assert router.route_count == len(sample_routes)
        assert router.version == 1

    async def test_consecutive_reloads_safe(self, sample_routes):
        from gateway.db.models import Route

        router = Router()
        mock_repo = MagicMock()

        call_count = 0

        async def get_all_active():
            nonlocal call_count
            call_count += 1
            return [Route(**r) for r in sample_routes]

        async def get_max_version():
            return call_count

        mock_repo.get_all_active = AsyncMock(side_effect=get_all_active)
        mock_repo.get_max_version = AsyncMock(side_effect=get_max_version)

        tasks = [router.load_routes(mock_repo) for _ in range(10)]
        await asyncio.gather(*tasks)

        assert router.version > 0
        assert router.route_count == len(sample_routes)


class TestRouteConfigModel:
    async def test_rewrite_path_prefix(self):
        route = RouteConfig(
            id=uuid.uuid4(),
            name="test",
            path="/api/v1",
            match_type="prefix",
            targets=[RouteTarget(url="http://localhost")],
            strip_prefix="/api/v1",
        )

        rewritten = route.rewrite_path("/api/v1/users")
        assert rewritten == "/users"

    async def test_rewrite_path_no_strip(self):
        route = RouteConfig(
            id=uuid.uuid4(),
            name="test",
            path="/api/users",
            match_type="regex",
            path_pattern=r"^/api/users/(?P<id>\d+)$",
            targets=[RouteTarget(url="http://localhost")],
        )

        rewritten = route.rewrite_path("/api/users/123")
        assert rewritten == "/api/users/123"

    async def test_matches_method_empty_list_allows_all(self):
        route = RouteConfig(
            id=uuid.uuid4(),
            name="test",
            path="/test",
            match_type="prefix",
            targets=[RouteTarget(url="http://localhost")],
            methods=[],
        )

        assert route.matches_method("GET")
        assert route.matches_method("POST")
        assert route.matches_method("DELETE")

    async def test_matches_method_specific_list(self):
        route = RouteConfig(
            id=uuid.uuid4(),
            name="test",
            path="/test",
            match_type="prefix",
            targets=[RouteTarget(url="http://localhost")],
            methods=["GET", "POST"],
        )

        assert route.matches_method("GET")
        assert route.matches_method("POST")
        assert not route.matches_method("DELETE")
        assert route.matches_method("get")


class TestRouteTarget:
    async def test_target_to_dict(self):
        target = RouteTarget(
            url="http://localhost:8080",
            weight=5,
            is_healthy=True,
            timeout=30,
        )

        d = target.to_dict()
        assert d["url"] == "http://localhost:8080"
        assert d["weight"] == 5
        assert d["is_healthy"] is True
        assert d["timeout"] == 30
