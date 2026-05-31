from typing import Dict, Any, Optional, List
from datetime import datetime
import asyncio
import time
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type

from ..models import (
    RouteDefinition,
    GatewayRequest,
    GatewayResponse,
    LoadBalanceStrategy,
    CircuitBreakerConfig,
    GatewayMetrics,
    ProtocolType,
    RouteTarget,
)
from ..interfaces import (
    RequestRouterPort,
    ProtocolConverterPort,
    LoadBalancerPort,
    CircuitBreakerManagerPort,
    APIGatewayServicePort,
)
from ..impl.router import RequestRouter
from ..impl.protocol_converter import ProtocolConverter
from ..impl.load_balancer import LoadBalancer
from ..impl.circuit_breaker import CircuitBreakerManager
from ..impl.metrics import RequestMetrics, PrometheusExporter

from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    TimeoutError as CoreTimeoutError,
    PlatformError,
    generate_id,
    settings,
)
import logging
import httpx

logger = logging.getLogger(__name__)


class APIGatewayService(APIGatewayServicePort):
    def __init__(
        self,
        router: Optional[RequestRouterPort] = None,
        protocol_converter: Optional[ProtocolConverterPort] = None,
        load_balancer: Optional[LoadBalancerPort] = None,
        circuit_breaker_manager: Optional[CircuitBreakerManagerPort] = None,
        request_metrics: Optional[RequestMetrics] = None,
        prometheus_exporter: Optional[PrometheusExporter] = None,
    ):
        self.router: RequestRouterPort = router or RequestRouter()
        self.protocol_converter: ProtocolConverterPort = protocol_converter or ProtocolConverter()
        self.load_balancer: LoadBalancerPort = load_balancer or LoadBalancer()
        self.circuit_breaker_manager: CircuitBreakerManagerPort = (
            circuit_breaker_manager or CircuitBreakerManager()
        )
        self.request_metrics: RequestMetrics = request_metrics or RequestMetrics()
        self.prometheus: PrometheusExporter = prometheus_exporter or PrometheusExporter()

        self._metrics = get_metrics_collector()
        self._http_client = httpx.AsyncClient(timeout=settings.api_gateway_timeout)
        self._semaphore = asyncio.Semaphore(settings.api_gateway_max_concurrent)
        self._latency_history: List[float] = []
        self._request_count = 0
        self._success_count = 0
        self._failed_count = 0

    async def register_route(
        self,
        route: RouteDefinition,
        trace_id: Optional[str] = None,
    ) -> RouteDefinition:
        with init_context(trace_id, operation="register_route"):
            try:
                result = await self.router.register_route(route)
                self.prometheus.increment_counter("routes_registered", labels={"service": route.service_name})
                emit_event(
                    "gateway.route.registered",
                    {"route_id": result.route_id, "path": result.path, "service": result.service_name},
                    source="api_gateway",
                )
                return result
            except Exception as e:
                logger.error(f"Failed to register route: {e}")
                raise PlatformError(f"路由注册失败: {str(e)}")

    async def unregister_route(self, route_id: str, trace_id: Optional[str] = None) -> bool:
        with init_context(trace_id, operation="unregister_route"):
            result = await self.router.unregister_route(route_id)
            if result:
                self.prometheus.increment_counter("routes_unregistered")
            return result

    async def list_routes(self, trace_id: Optional[str] = None) -> List[RouteDefinition]:
        with init_context(trace_id, operation="list_routes"):
            return await self.router.list_routes()

    async def handle_request(
        self,
        request: GatewayRequest,
        trace_id: Optional[str] = None,
    ) -> GatewayResponse:
        request.request_id = request.request_id or generate_id("req")

        with init_context(trace_id or request.request_id, operation="handle_request", request_id=request.request_id):
            async with self._semaphore:
                self._request_count += 1
                self._metrics.increment("gateway_requests_total")
                self._metrics.gauge("gateway_active_connections", settings.api_gateway_max_concurrent - self._semaphore._value)
                self.prometheus.increment_counter("http_requests_total", labels={"method": request.method.value})
                self.prometheus.set_gauge("active_connections", settings.api_gateway_max_concurrent - self._semaphore._value)

                start_time = time.time()
                timer_id = self._metrics.start_timer("gateway_request")
                route_id = "unknown"

                try:
                    route = await self.router.match_route(request)
                    route_id = route.route_id

                    if route.request_transform and isinstance(request.body, dict):
                        request.body = await self.protocol_converter.transform_request_body(
                            request.body, route.request_transform
                        )

                    target = await self.load_balancer.select_target(
                        route.targets,
                        LoadBalanceStrategy.WEIGHTED_ROUND_ROBIN,
                        route.service_name,
                    )

                    converted_request = await self.protocol_converter.convert_request(
                        request, route.protocol_out
                    )

                    cb_config = CircuitBreakerConfig(
                        failure_threshold=3,
                        timeout_seconds=route.timeout_seconds,
                    )
                    breaker = self.circuit_breaker_manager.get_breaker(
                        f"{route.service_name}:{target.host}", cb_config
                    )

                    await self.load_balancer.increment_connection(target)
                    try:
                        response = await breaker.execute(
                            self._execute_with_retry,
                            request,
                            route,
                            target,
                            converted_request,
                        )
                    finally:
                        await self.load_balancer.decrement_connection(target)

                    if route.response_transform and isinstance(response.body, dict):
                        response.body = await self.protocol_converter.transform_response_body(
                            response.body, route.response_transform
                        )

                    latency = (time.time() - start_time) * 1000
                    response.latency_ms = latency
                    self._latency_history.append(latency)
                    if len(self._latency_history) > 10000:
                        self._latency_history = self._latency_history[-10000:]

                    self._success_count += 1
                    self._metrics.increment("gateway_requests_success")

                    await self.request_metrics.record_request(
                        route_id=route_id,
                        duration=time.time() - start_time,
                        status_code=response.status_code,
                        success=True,
                    )

                    self.prometheus.increment_counter(
                        "http_requests_success",
                        labels={"service": route.service_name, "status": str(response.status_code)}
                    )
                    self.prometheus.observe_histogram(
                        "http_request_duration_seconds",
                        time.time() - start_time,
                        labels={"service": route.service_name}
                    )

                    emit_event(
                        "gateway.request.completed",
                        {
                            "request_id": request.request_id,
                            "path": request.path,
                            "latency_ms": latency,
                            "status": response.status_code,
                        },
                        source="api_gateway",
                    )

                    return response

                except CoreTimeoutError:
                    self._failed_count += 1
                    self._metrics.increment("gateway_requests_timeout")
                    self.prometheus.increment_counter("http_requests_timeout")

                    await self.request_metrics.record_request(
                        route_id=route_id,
                        duration=time.time() - start_time,
                        status_code=504,
                        success=False,
                    )

                    logger.warning(f"Request timeout: {request.path}")
                    return GatewayResponse(
                        request_id=request.request_id,
                        status_code=504,
                        body={"error": "上游服务响应超时"},
                        protocol=ProtocolType.HTTP,
                    )
                except Exception as e:
                    self._failed_count += 1
                    self._metrics.increment("gateway_requests_error")
                    self.prometheus.increment_counter("http_requests_error")

                    await self.request_metrics.record_request(
                        route_id=route_id,
                        duration=time.time() - start_time,
                        status_code=500,
                        success=False,
                    )

                    logger.error(f"Request failed for {request.path}: {e}")
                    emit_event(
                        "gateway.request.failed",
                        {"request_id": request.request_id, "path": request.path, "error": str(e)},
                        source="api_gateway",
                    )
                    raise PlatformError(f"网关请求处理失败: {str(e)}")
                finally:
                    self._metrics.stop_timer(timer_id)

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=5),
        retry=retry_if_exception_type((httpx.TimeoutException, httpx.ConnectError, CoreTimeoutError)),
        reraise=True,
    )
    async def _execute_with_retry(
        self,
        request: GatewayRequest,
        route: RouteDefinition,
        target: RouteTarget,
        converted_request: Dict[str, Any],
    ) -> GatewayResponse:
        target_url = f"http://{target.host}:{target.port}{target.path}"
        logger.debug(f"Proxying request to {target_url}")

        if target.protocol == ProtocolType.HTTP:
            method = converted_request.get("method", request.method.value)
            headers = converted_request.get("headers", {})
            body = converted_request.get("body")
            params = converted_request.get("query_params", {})

            try:
                response = await self._http_client.request(
                    method=method,
                    url=target_url,
                    headers=headers,
                    json=body if isinstance(body, dict) else None,
                    content=body if isinstance(body, (bytes, str)) else None,
                    params=params,
                    timeout=route.timeout_seconds,
                )

                response_body = response.json() if "application/json" in response.headers.get("content-type", "") else response.text

                return await self.protocol_converter.convert_response(
                    response_body,
                    route.protocol_out,
                    request.source_protocol,
                    request.request_id or "",
                )

            except httpx.TimeoutException as e:
                logger.warning(f"Timeout calling {target_url}: {e}")
                raise CoreTimeoutError("上游服务响应超时")
            except httpx.ConnectError as e:
                logger.warning(f"Connection error calling {target_url}: {e}")
                raise PlatformError(f"无法连接到上游服务: {str(e)}")
        else:
            return await self._execute_protocol_target(
                request, route, target, converted_request, target_url
            )

    async def _execute_protocol_target(
        self,
        request: GatewayRequest,
        route: RouteDefinition,
        target: RouteTarget,
        converted_request: Dict[str, Any],
        target_url: str,
    ) -> GatewayResponse:
        logger.info(f"Simulating {target.protocol} call to {target_url}")

        simulated_response = {
            "status": "ok",
            "protocol": target.protocol.value,
            "target_url": target_url,
            "request": converted_request,
            "timestamp": datetime.utcnow().isoformat(),
        }

        return await self.protocol_converter.convert_response(
            simulated_response,
            route.protocol_out,
            request.source_protocol,
            request.request_id or "",
        )

    async def get_metrics(self, trace_id: Optional[str] = None) -> GatewayMetrics:
        with init_context(trace_id, operation="get_metrics"):
            latencies = sorted(self._latency_history[-1000:])
            n = len(latencies)

            return GatewayMetrics(
                total_requests=self._request_count,
                success_requests=self._success_count,
                failed_requests=self._failed_count,
                average_latency_ms=sum(latencies) / n if latencies else 0.0,
                p95_latency_ms=self._percentile(latencies, 95),
                p99_latency_ms=self._percentile(latencies, 99),
                active_connections=settings.api_gateway_max_concurrent - self._semaphore._value,
            )

    def _percentile(self, sorted_data: list, percentile: float) -> float:
        if not sorted_data:
            return 0.0
        k = (len(sorted_data) - 1) * (percentile / 100.0)
        f = int(k)
        c = min(f + 1, len(sorted_data) - 1)
        if f == c:
            return sorted_data[f]
        return sorted_data[f] + (sorted_data[c] - sorted_data[f]) * (k - f)

    async def get_circuit_breaker_statuses(self) -> Dict[str, Dict[str, Any]]:
        return self.circuit_breaker_manager.get_all_statuses()

    async def get_request_metrics(self, route_id: Optional[str] = None) -> Dict[str, Any]:
        if route_id:
            return self.request_metrics.get_route_stats(route_id)
        return self.request_metrics.get_summary()

    async def get_prometheus_metrics(self) -> str:
        return self.prometheus.generate_metrics()

    async def get_gateway_status(self) -> Dict[str, Any]:
        return {
            "status": "healthy",
            "timestamp": datetime.utcnow().isoformat(),
            "active_connections": settings.api_gateway_max_concurrent - self._semaphore._value,
            "registered_routes": len(await self.router.list_routes()),
            "circuit_breakers": len(await self.get_circuit_breaker_statuses()),
            "request_summary": self.request_metrics.get_summary(),
        }

    async def close(self) -> None:
        await self._http_client.aclose()
