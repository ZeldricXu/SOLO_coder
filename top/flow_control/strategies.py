import asyncio
import hashlib
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional
from uuid import uuid4


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid4().hex[:12]}"


class CircuitBreakerState(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


class DeploymentStrategy(str, Enum):
    CANARY = "canary"
    BLUE_GREEN = "blue_green"
    MIRROR = "mirror"
    NONE = "none"


@dataclass
class TrafficRule:
    rule_id: str
    name: str
    selector: Dict[str, str] = field(default_factory=dict)
    percentage: float = 100.0
    target_version: str = "latest"
    priority: int = 0
    enabled: bool = True
    created_at: datetime = field(default_factory=utc_now)


@dataclass
class ReleasePolicy:
    policy_id: str
    name: str
    strategy: DeploymentStrategy = DeploymentStrategy.NONE
    canary_percentage: float = 0.0
    stable_version: str = "v1"
    canary_version: str = "v2"
    blue_version: str = "v1"
    green_version: str = "v2"
    active_color: str = "blue"
    mirror_enabled: bool = False
    mirror_percentage: float = 0.0
    mirror_target: str = "staging"
    created_at: datetime = field(default_factory=utc_now)


@dataclass
class MirrorConfig:
    config_id: str
    name: str
    source_path: str
    target_path: str
    percentage: float = 100.0
    headers_to_copy: List[str] = field(default_factory=list)
    timeout_ms: int = 5000
    enabled: bool = True


@dataclass
class FlowPolicy:
    policy_id: str
    name: str
    circuit_breaker_enabled: bool = True
    failure_threshold: int = 5
    success_threshold: int = 3
    timeout_seconds: int = 30
    wait_duration_seconds: int = 60
    rate_limit_enabled: bool = False
    requests_per_minute: int = 1000
    created_at: datetime = field(default_factory=utc_now)


class TrafficSelector:
    def __init__(self):
        self._rules: List[TrafficRule] = []

    def add_rule(self, rule: TrafficRule) -> None:
        self._rules.append(rule)
        self._rules.sort(key=lambda r: r.priority, reverse=True)

    def remove_rule(self, rule_id: str) -> bool:
        for i, rule in enumerate(self._rules):
            if rule.rule_id == rule_id:
                self._rules.pop(i)
                return True
        return False

    def select_target(
        self,
        headers: Dict[str, str],
        request_id: Optional[str] = None,
    ) -> Optional[str]:
        for rule in self._rules:
            if not rule.enabled:
                continue

            matches = True
            for key, value in rule.selector.items():
                if headers.get(key) != value:
                    matches = False
                    break

            if matches:
                if rule.percentage >= 100.0:
                    return rule.target_version

                if request_id:
                    hash_val = int(
                        hashlib.md5(request_id.encode()).hexdigest(), 16
                    ) % 100
                    if hash_val < rule.percentage:
                        return rule.target_version
                else:
                    if hash(time.time()) % 100 < rule.percentage:
                        return rule.target_version

        return None

    def list_rules(self) -> List[TrafficRule]:
        return list(self._rules)


class CanaryRelease:
    def __init__(self):
        self._stable_version = "v1"
        self._canary_version = "v2"
        self._canary_percentage = 0.0
        self._sticky_users: Dict[str, str] = {}
        self._lock = asyncio.Lock()

    @property
    def stable_version(self) -> str:
        return self._stable_version

    @property
    def canary_version(self) -> str:
        return self._canary_version

    @property
    def canary_percentage(self) -> float:
        return self._canary_percentage

    def configure(
        self,
        stable_version: str,
        canary_version: str,
        initial_percentage: float = 0.0,
    ) -> None:
        self._stable_version = stable_version
        self._canary_version = canary_version
        self._canary_percentage = max(0.0, min(100.0, initial_percentage))

    def set_percentage(self, percentage: float) -> None:
        self._canary_percentage = max(0.0, min(100.0, percentage))

    def promote_canary(self) -> None:
        self._stable_version = self._canary_version
        self._canary_percentage = 100.0

    def rollback(self) -> None:
        self._canary_percentage = 0.0

    def get_target(
        self,
        user_id: Optional[str] = None,
        request_id: Optional[str] = None,
    ) -> str:
        if self._canary_percentage <= 0:
            return self._stable_version

        if self._canary_percentage >= 100:
            return self._canary_version

        if user_id and user_id in self._sticky_users:
            return self._sticky_users[user_id]

        hash_value = 0
        if request_id:
            hash_value = int(hashlib.md5(request_id.encode()).hexdigest(), 16) % 100
        elif user_id:
            hash_value = int(hashlib.md5(user_id.encode()).hexdigest(), 16) % 100
        else:
            hash_value = int(time.time() * 1000) % 100

        if hash_value < self._canary_percentage:
            if user_id:
                self._sticky_users[user_id] = self._canary_version
            return self._canary_version

        if user_id:
            self._sticky_users[user_id] = self._stable_version
        return self._stable_version

    def get_status(self) -> Dict[str, Any]:
        return {
            "stable_version": self._stable_version,
            "canary_version": self._canary_version,
            "canary_percentage": self._canary_percentage,
            "sticky_users_count": len(self._sticky_users),
        }


class BlueGreenDeployment:
    def __init__(self):
        self._blue_version = "v1"
        self._green_version = "v2"
        self._active_color = "blue"
        self._lock = asyncio.Lock()

    @property
    def active_version(self) -> str:
        if self._active_color == "blue":
            return self._blue_version
        return self._green_version

    @property
    def inactive_version(self) -> str:
        if self._active_color == "blue":
            return self._green_version
        return self._blue_version

    def configure(
        self,
        blue_version: str,
        green_version: str,
        active_color: str = "blue",
    ) -> None:
        self._blue_version = blue_version
        self._green_version = green_version
        self._active_color = active_color

    async def switch_to_blue(self) -> None:
        async with self._lock:
            self._active_color = "blue"

    async def switch_to_green(self) -> None:
        async with self._lock:
            self._active_color = "green"

    def get_target(self) -> str:
        return self.active_version

    def get_status(self) -> Dict[str, Any]:
        return {
            "blue_version": self._blue_version,
            "green_version": self._green_version,
            "active_color": self._active_color,
            "active_version": self.active_version,
            "inactive_version": self.inactive_version,
        }


class TrafficMirror:
    def __init__(self):
        self._configs: List[MirrorConfig] = []
        self._handlers: Dict[str, Callable[[Any], None]] = {}

    def add_config(self, config: MirrorConfig) -> None:
        self._configs.append(config)

    def remove_config(self, config_id: str) -> bool:
        for i, config in enumerate(self._configs):
            if config.config_id == config_id:
                self._configs.pop(i)
                return True
        return False

    def register_handler(
        self,
        config_id: str,
        handler: Callable[[Any], None],
    ) -> None:
        self._handlers[config_id] = handler

    async def mirror_request(
        self,
        path: str,
        request_data: Any,
        headers: Dict[str, str],
        request_id: Optional[str] = None,
    ) -> bool:
        for config in self._configs:
            if not config.enabled:
                continue

            if path != config.source_path:
                continue

            if config.percentage < 100.0:
                if request_id:
                    hash_val = int(hashlib.md5(request_id.encode()).hexdigest(), 16) % 100
                    if hash_val >= config.percentage:
                        continue
                else:
                    if hash(time.time()) % 100 >= config.percentage:
                        continue

            handler = self._handlers.get(config.config_id)
            if handler:
                try:
                    mirrored_headers = {}
                    for h in config.headers_to_copy:
                        if h in headers:
                            mirrored_headers[h] = headers[h]

                    mirror_payload = {
                        "target_path": config.target_path,
                        "request_data": request_data,
                        "headers": mirrored_headers,
                        "original_path": path,
                    }

                    if asyncio.iscoroutinefunction(handler):
                        await handler(mirror_payload)
                    else:
                        handler(mirror_payload)

                    return True
                except Exception:
                    pass

        return False

    def list_configs(self) -> List[MirrorConfig]:
        return list(self._configs)


class CircuitBreaker:
    def __init__(
        self,
        name: str,
        failure_threshold: int = 5,
        success_threshold: int = 3,
        timeout_seconds: int = 30,
        wait_duration_seconds: int = 60,
    ):
        self._name = name
        self._failure_threshold = failure_threshold
        self._success_threshold = success_threshold
        self._timeout_seconds = timeout_seconds
        self._wait_duration_seconds = wait_duration_seconds

        self._state = CircuitBreakerState.CLOSED
        self._failure_count = 0
        self._success_count = 0
        self._last_failure_time: Optional[datetime] = None
        self._last_state_change = utc_now()
        self._lock = asyncio.Lock()

    @property
    def state(self) -> CircuitBreakerState:
        return self._state

    @property
    def is_open(self) -> bool:
        return self._state == CircuitBreakerState.OPEN

    @property
    def is_closed(self) -> bool:
        return self._state == CircuitBreakerState.CLOSED

    @property
    def is_half_open(self) -> bool:
        return self._state == CircuitBreakerState.HALF_OPEN

    async def call(
        self,
        func: Callable,
        fallback: Optional[Callable] = None,
        *args,
        **kwargs,
    ) -> Any:
        await self._maybe_transition_to_half_open()

        if self._state == CircuitBreakerState.OPEN:
            if fallback:
                if asyncio.iscoroutinefunction(fallback):
                    return await fallback(*args, **kwargs)
                else:
                    return fallback(*args, **kwargs)
            raise RuntimeError(
                f"Circuit breaker '{self._name}' is OPEN. Service unavailable."
            )

        try:
            if asyncio.iscoroutinefunction(func):
                result = await func(*args, **kwargs)
            else:
                result = func(*args, **kwargs)

            await self._on_success()
            return result

        except asyncio.TimeoutError:
            await self._on_failure()
            if fallback:
                if asyncio.iscoroutinefunction(fallback):
                    return await fallback(*args, **kwargs)
                else:
                    return fallback(*args, **kwargs)
            raise

        except Exception:
            await self._on_failure()
            if fallback:
                if asyncio.iscoroutinefunction(fallback):
                    return await fallback(*args, **kwargs)
                else:
                    return fallback(*args, **kwargs)
            raise

    async def _maybe_transition_to_half_open(self) -> None:
        if self._state != CircuitBreakerState.OPEN:
            return

        if self._last_state_change:
            elapsed = (utc_now() - self._last_state_change).total_seconds()
            if elapsed >= self._wait_duration_seconds:
                async with self._lock:
                    self._state = CircuitBreakerState.HALF_OPEN
                    self._success_count = 0
                    self._last_state_change = utc_now()

    async def _on_success(self) -> None:
        async with self._lock:
            if self._state == CircuitBreakerState.HALF_OPEN:
                self._success_count += 1
                if self._success_count >= self._success_threshold:
                    self._state = CircuitBreakerState.CLOSED
                    self._failure_count = 0
                    self._success_count = 0
                    self._last_state_change = utc_now()
            else:
                self._failure_count = 0

    async def _on_failure(self) -> None:
        async with self._lock:
            self._failure_count += 1
            self._last_failure_time = utc_now()

            if self._state == CircuitBreakerState.CLOSED:
                if self._failure_count >= self._failure_threshold:
                    self._state = CircuitBreakerState.OPEN
                    self._last_state_change = utc_now()
            elif self._state == CircuitBreakerState.HALF_OPEN:
                self._state = CircuitBreakerState.OPEN
                self._success_count = 0
                self._last_state_change = utc_now()

    async def force_open(self) -> None:
        async with self._lock:
            self._state = CircuitBreakerState.OPEN
            self._last_state_change = utc_now()

    async def force_closed(self) -> None:
        async with self._lock:
            self._state = CircuitBreakerState.CLOSED
            self._failure_count = 0
            self._success_count = 0
            self._last_state_change = utc_now()

    async def reset(self) -> None:
        async with self._lock:
            self._state = CircuitBreakerState.CLOSED
            self._failure_count = 0
            self._success_count = 0
            self._last_failure_time = None
            self._last_state_change = utc_now()

    def get_status(self) -> Dict[str, Any]:
        return {
            "name": self._name,
            "state": self._state.value,
            "failure_count": self._failure_count,
            "success_count": self._success_count,
            "failure_threshold": self._failure_threshold,
            "success_threshold": self._success_threshold,
            "wait_duration_seconds": self._wait_duration_seconds,
            "last_state_change": self._last_state_change.isoformat() if self._last_state_change else None,
            "last_failure_time": self._last_failure_time.isoformat() if self._last_failure_time else None,
        }


class TrafficRouter:
    def __init__(self):
        self._selector = TrafficSelector()
        self._canary = CanaryRelease()
        self._blue_green = BlueGreenDeployment()
        self._mirror = TrafficMirror()
        self._circuit_breakers: Dict[str, CircuitBreaker] = {}
        self._default_version = "latest"

    @property
    def selector(self) -> TrafficSelector:
        return self._selector

    @property
    def canary(self) -> CanaryRelease:
        return self._canary

    @property
    def blue_green(self) -> BlueGreenDeployment:
        return self._blue_green

    @property
    def mirror(self) -> TrafficMirror:
        return self._mirror

    def get_or_create_circuit_breaker(
        self,
        name: str,
        failure_threshold: int = 5,
        success_threshold: int = 3,
        wait_duration_seconds: int = 60,
    ) -> CircuitBreaker:
        if name not in self._circuit_breakers:
            self._circuit_breakers[name] = CircuitBreaker(
                name=name,
                failure_threshold=failure_threshold,
                success_threshold=success_threshold,
                wait_duration_seconds=wait_duration_seconds,
            )
        return self._circuit_breakers[name]

    def get_circuit_breaker(self, name: str) -> Optional[CircuitBreaker]:
        return self._circuit_breakers.get(name)

    def route(
        self,
        headers: Dict[str, str],
        user_id: Optional[str] = None,
        request_id: Optional[str] = None,
    ) -> str:
        target = self._selector.select_target(headers, request_id)
        if target:
            return target

        canary_target = self._canary.get_target(user_id, request_id)
        if canary_target != self._canary.stable_version:
            return canary_target

        return self._blue_green.get_target()

    async def with_circuit_breaker(
        self,
        service_name: str,
        func: Callable,
        fallback: Optional[Callable] = None,
        *args,
        **kwargs,
    ) -> Any:
        cb = self.get_or_create_circuit_breaker(service_name)
        return await cb.call(func, fallback, *args, **kwargs)

    def add_traffic_rule(self, rule: TrafficRule) -> None:
        self._selector.add_rule(rule)

    def remove_traffic_rule(self, rule_id: str) -> bool:
        return self._selector.remove_rule(rule_id)

    def configure_canary(
        self,
        stable_version: str,
        canary_version: str,
        percentage: float = 0.0,
    ) -> None:
        self._canary.configure(stable_version, canary_version, percentage)

    def configure_blue_green(
        self,
        blue_version: str,
        green_version: str,
        active_color: str = "blue",
    ) -> None:
        self._blue_green.configure(blue_version, green_version, active_color)

    def add_mirror_config(self, config: MirrorConfig) -> None:
        self._mirror.add_config(config)

    async def mirror(
        self,
        path: str,
        request_data: Any,
        headers: Dict[str, str],
        request_id: Optional[str] = None,
    ) -> bool:
        return await self._mirror.mirror_request(path, request_data, headers, request_id)

    def get_status(self) -> Dict[str, Any]:
        return {
            "canary": self._canary.get_status(),
            "blue_green": self._blue_green.get_status(),
            "traffic_rules": len(self._selector.list_rules()),
            "circuit_breakers": {
                name: cb.get_status()
                for name, cb in self._circuit_breakers.items()
            },
            "mirror_configs": len(self._mirror.list_configs()),
        }


_router_instance: Optional[TrafficRouter] = None


def get_traffic_router() -> TrafficRouter:
    global _router_instance
    if _router_instance is None:
        _router_instance = TrafficRouter()
    return _router_instance
