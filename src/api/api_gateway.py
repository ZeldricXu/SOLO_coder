from fastapi import APIRouter, Depends, Header, HTTPException, Request
from typing import Optional, List
from src.core import ApiResponse, get_trace_id, BatchRequest
from src.domain import (
    RouteDefinition,
    GatewayRequest,
    GatewayResponse,
    RouteTarget,
    ProtocolType,
    HTTPMethod,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/gateway", tags=["API Gateway"])


@router.post("/routes", response_model=ApiResponse)
async def register_route(
    route: RouteDefinition,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.api_gateway.register_route(route, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/routes", response_model=ApiResponse)
async def list_routes(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.api_gateway.list_routes(trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.delete("/routes/{route_id}", response_model=ApiResponse)
async def unregister_route(
    route_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    success = await container.api_gateway.unregister_route(route_id, trace_id or get_trace_id())
    if not success:
        raise HTTPException(status_code=404, detail="Route not found")
    return ApiResponse.success({"deleted": True})


@router.post("/request", response_model=ApiResponse)
async def handle_request(
    request: GatewayRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.api_gateway.handle_request(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.api_route("/proxy/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH"])
async def proxy_request(
    path: str,
    request: Request,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    body = None
    if request.method in ["POST", "PUT", "PATCH"]:
        try:
            body = await request.json()
        except Exception:
            body = await request.body()

    gateway_request = GatewayRequest(
        path=f"/{path}",
        method=HTTPMethod(request.method),
        headers=dict(request.headers),
        body=body,
        query_params=dict(request.query_params),
    )

    result = await container.api_gateway.handle_request(gateway_request, trace_id or get_trace_id())
    return result.body if isinstance(result.body, dict) else ApiResponse.success(result.body)


@router.get("/metrics", response_model=ApiResponse)
async def get_gateway_metrics(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.api_gateway.get_metrics(trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/circuit-breakers", response_model=ApiResponse)
async def get_circuit_breaker_statuses(
    container: DIContainer = Depends(get_container),
):
    result = await container.api_gateway.get_circuit_breaker_statuses()
    return ApiResponse.success(result)


@router.get("/protocols", response_model=ApiResponse)
async def list_supported_protocols():
    return ApiResponse.success([p.value for p in ProtocolType])


@router.get("/status", response_model=ApiResponse)
async def get_gateway_status(
    container: DIContainer = Depends(get_container),
):
    result = await container.api_gateway.get_gateway_status()
    return ApiResponse.success(result)


@router.get("/request-metrics", response_model=ApiResponse)
async def get_request_metrics(
    route_id: Optional[str] = None,
    container: DIContainer = Depends(get_container),
):
    result = await container.api_gateway.get_request_metrics(route_id)
    return ApiResponse.success(result)


@router.get("/prometheus")
async def get_prometheus_metrics(
    container: DIContainer = Depends(get_container),
):
    from fastapi.responses import PlainTextResponse
    metrics = await container.api_gateway.get_prometheus_metrics()
    return PlainTextResponse(content=metrics, media_type="text/plain")
