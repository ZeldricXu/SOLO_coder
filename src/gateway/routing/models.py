from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Pattern
import re
import random
from uuid import UUID

from gateway.logger import get_logger

logger = get_logger("routing")


@dataclass
class RouteTarget:
    url: str
    weight: int = 1
    is_healthy: bool = True
    timeout: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "url": self.url,
            "weight": self.weight,
            "is_healthy": self.is_healthy,
            "timeout": self.timeout,
        }


@dataclass
class RouteConfig:
    id: UUID
    name: str
    path: str
    match_type: str
    path_pattern: Optional[str] = None
    targets: List[RouteTarget] = field(default_factory=list)
    weight_rules: Optional[Dict[str, Any]] = None
    methods: List[str] = field(default_factory=list)
    auth_required: bool = True
    auth_strategy: Optional[str] = None
    rate_limit_enabled: bool = True
    rate_limit_per_user: Optional[int] = None
    rate_limit_per_api: Optional[int] = None
    circuit_breaker_enabled: bool = True
    circuit_breaker_config: Optional[Dict[str, Any]] = None
    transform_request: Optional[Dict[str, Any]] = None
    transform_response: Optional[Dict[str, Any]] = None
    timeout: int = 30
    retry_count: int = 0
    version: int = 1
    compiled_pattern: Optional[Pattern] = None
    strip_prefix: str = ""

    def compile(self) -> None:
        if self.match_type == "regex" and self.path_pattern:
            try:
                self.compiled_pattern = re.compile(self.path_pattern)
            except re.error as e:
                logger.error("Failed to compile regex pattern", pattern=self.path_pattern, error=str(e))
                self.compiled_pattern = None

    def matches_method(self, method: str) -> bool:
        if not self.methods:
            return True
        return method.upper() in [m.upper() for m in self.methods]

    def select_target(self, user_id: Optional[str] = None) -> Optional[RouteTarget]:
        healthy_targets = [t for t in self.targets if t.is_healthy]
        if not healthy_targets:
            return None

        if self.match_type == "weighted" and self.weight_rules:
            return self._select_weighted_target(healthy_targets, user_id)

        if len(healthy_targets) == 1:
            return healthy_targets[0]

        total_weight = sum(t.weight for t in healthy_targets)
        if total_weight <= 0:
            return random.choice(healthy_targets)

        r = random.uniform(0, total_weight)
        cumulative = 0
        for target in healthy_targets:
            cumulative += target.weight
            if r <= cumulative:
                return target

        return healthy_targets[-1]

    def _select_weighted_target(self, targets: List[RouteTarget], user_id: Optional[str]) -> Optional[RouteTarget]:
        if not self.weight_rules:
            return targets[0] if targets else None

        rules = self.weight_rules.get("rules", [])
        default_target = self.weight_rules.get("default_target")

        if user_id:
            for rule in rules:
                condition = rule.get("condition", {})
                user_match = condition.get("user_id")
                if user_match and user_id == user_match:
                    target_url = rule.get("target")
                    for t in targets:
                        if t.url == target_url:
                            return t

                header_match = condition.get("header")
                if header_match:
                    pass

        if default_target:
            for t in targets:
                if t.url == default_target:
                    return t

        return targets[0] if targets else None

    def rewrite_path(self, original_path: str) -> str:
        if self.match_type == "prefix" and self.strip_prefix:
            if original_path.startswith(self.strip_prefix):
                return original_path[len(self.strip_prefix):] or "/"
        return original_path

    @classmethod
    def from_db_model(cls, db_route) -> "RouteConfig":
        targets = []
        for t in db_route.targets or []:
            targets.append(RouteTarget(
                url=t.get("url", ""),
                weight=t.get("weight", 1),
                timeout=t.get("timeout"),
            ))

        route = cls(
            id=db_route.id,
            name=db_route.name,
            path=db_route.path,
            match_type=db_route.match_type,
            path_pattern=db_route.path_pattern,
            targets=targets,
            weight_rules=db_route.weight_rules,
            methods=db_route.methods or [],
            auth_required=db_route.auth_required,
            auth_strategy=db_route.auth_strategy,
            rate_limit_enabled=db_route.rate_limit_enabled,
            rate_limit_per_user=db_route.rate_limit_per_user,
            rate_limit_per_api=db_route.rate_limit_per_api,
            circuit_breaker_enabled=db_route.circuit_breaker_enabled,
            circuit_breaker_config=db_route.circuit_breaker_config,
            transform_request=db_route.transform_request,
            transform_response=db_route.transform_response,
            timeout=db_route.timeout,
            retry_count=db_route.retry_count,
            version=db_route.version,
            strip_prefix=db_route.path if db_route.match_type == "prefix" else "",
        )
        route.compile()
        return route


@dataclass
class RouteMatch:
    route: RouteConfig
    matched_path: str
    target: RouteTarget
    path_params: Dict[str, Any] = field(default_factory=dict)
