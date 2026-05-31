from typing import Protocol, List, Optional, Dict, Any, runtime_checkable
from abc import abstractmethod

from .models import (
    RouteDefinition,
    RouteTarget,
    GatewayRequest,
    GatewayResponse,
    LoadBalanceStrategy,
    CircuitBreakerConfig,
    GatewayMetrics,
    ProtocolType,
)


@runtime_checkable
class RequestRouterPort(Protocol):
    @abstractmethod
    async def register_route(self, route: RouteDefinition) -> RouteDefinition:
        ...

    @abstractmethod
    async def unregister_route(self, route_id: str) -> bool:
        ...

    @abstractmethod
    async def list_routes(self) -> List[RouteDefinition]:
        ...

    @abstractmethod
    async def match_route(self, request: GatewayRequest) -> RouteDefinition:
        ...


@runtime_checkable
class ProtocolConverterPort(Protocol):
    @abstractmethod
    async def convert_request(
        self, request: GatewayRequest, target_protocol: ProtocolType
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def convert_response(
        self,
        response_body: Any,
        source_protocol: ProtocolType,
        target_protocol: ProtocolType,
        request_id: str,
    ) -> GatewayResponse:
        ...

    @abstractmethod
    async def transform_request_body(
        self, body: Dict[str, Any], transform: Dict[str, Any]
    ) -> Dict[str, Any]:
        ...

    @abstractmethod
    async def transform_response_body(
        self, body: Dict[str, Any], transform: Dict[str, Any]
    ) -> Dict[str, Any]:
        ...


@runtime_checkable
class LoadBalancerPort(Protocol):
    @abstractmethod
    async def select_target(
        self,
        targets: List[RouteTarget],
        strategy: LoadBalanceStrategy,
        service_name: str,
    ) -> RouteTarget:
        ...

    @abstractmethod
    async def increment_connection(self, target: RouteTarget) -> None:
        ...

    @abstractmethod
    async def decrement_connection(self, target: RouteTarget) -> None:
        ...


@runtime_checkable
class CircuitBreakerManagerPort(Protocol):
    @abstractmethod
    def get_breaker(
        self, key: str, config: CircuitBreakerConfig
    ) -> Any:
        ...

    @abstractmethod
    def get_all_statuses(self) -> Dict[str, Dict[str, Any]]:
        ...


@runtime_checkable
class APIGatewayServicePort(Protocol):
    @abstractmethod
    async def register_route(
        self, route: RouteDefinition, trace_id: Optional[str] = None
    ) -> RouteDefinition:
        ...

    @abstractmethod
    async def unregister_route(
        self, route_id: str, trace_id: Optional[str] = None
    ) -> bool:
        ...

    @abstractmethod
    async def list_routes(self, trace_id: Optional[str] = None) -> List[RouteDefinition]:
        ...

    @abstractmethod
    async def handle_request(
        self, request: GatewayRequest, trace_id: Optional[str] = None
    ) -> GatewayResponse:
        ...

    @abstractmethod
    async def get_metrics(self, trace_id: Optional[str] = None) -> GatewayMetrics:
        ...

    @abstractmethod
    async def get_circuit_breaker_statuses(self) -> Dict[str, Dict[str, Any]]:
        ...

    @abstractmethod
    async def close(self) -> None:
        ...
