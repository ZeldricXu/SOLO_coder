from __future__ import annotations

import asyncio
import secrets
import uuid
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException, Request, Response, status
from fastapi.responses import JSONResponse
from pydantic import Field

from top.core.models import BaseModel, utc_now
from top.config.manager import ConfigManager, InMemoryConfigStore
from top.monitoring.collector import Monitor, get_monitor
from top.gateway.auth import JWTAuth, Permission, PermissionLevel, Role, get_auth_provider
from top.gateway.rate_limit import RateLimitAlgorithm, RateLimitPolicy, get_rate_limiter
from top.gateway.middleware import create_api_gateway


class APIResponse(BaseModel):
    code: int
    data: Optional[Any] = None
    message: Optional[str] = None


class ResourceCreateRequest(BaseModel):
    type: str
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, str] = Field(default_factory=dict)


class ResourceStatusResponse(BaseModel):
    id: str
    status: str
    progress: float = 0.0
    phase: Optional[str] = None
    error_detail: Optional[str] = None
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None


class BatchOperation(BaseModel):
    action: str
    id: str
    parameters: Dict[str, Any] = Field(default_factory=dict)


class BatchResult(BaseModel):
    batch_id: str
    results: List[Dict[str, Any]]


class AppState:
    def __init__(self):
        self.config_manager: Optional[ConfigManager] = None
        self.monitor: Optional[Monitor] = None
        self.auth_provider: Optional[JWTAuth] = None
        self.rate_limiter = None
        self.gateway = None
        self.resources: Dict[str, Dict[str, Any]] = {}
        self.workflows: Dict[str, Any] = {}


_app_instance: Optional[FastAPI] = None
_app_state = AppState()


def _setup_routes(app: FastAPI, state: AppState) -> None:
    @app.post("/api/v1/resources", response_model=APIResponse, status_code=201)
    async def create_resource(request: ResourceCreateRequest) -> APIResponse:
        state.monitor.increment("resources.created", 1, {"type": request.type})
        
        resource_id = f"rsc_{secrets.token_hex(6)}"
        resource = {
            "id": resource_id,
            "type": request.type,
            "config": request.config,
            "labels": request.labels,
            "status": "provisioning",
            "progress": 0.0,
            "phase": "initializing",
            "created_at": utc_now(),
            "updated_at": utc_now(),
        }
        state.resources[resource_id] = resource
        
        state.monitor.increment("resources.active", 1, {"type": request.type})
        
        return APIResponse(
            code=201,
            data={"id": resource_id, "status": "provisioning"},
        )

    @app.get("/api/v1/resources/{resource_id}/status", response_model=APIResponse)
    async def get_resource_status(resource_id: str) -> APIResponse:
        state.monitor.increment("resources.status_queries", 1)
        
        resource = state.resources.get(resource_id)
        if not resource:
            raise HTTPException(status_code=404, detail="Resource not found")
        
        return APIResponse(
            code=200,
            data=ResourceStatusResponse(
                id=resource["id"],
                status=resource["status"],
                progress=resource.get("progress", 0.0),
                phase=resource.get("phase"),
                error_detail=resource.get("error_detail"),
                created_at=resource.get("created_at"),
                updated_at=resource.get("updated_at"),
            ),
        )

    @app.post("/api/v1/resources/batch", response_model=APIResponse)
    async def batch_operations(operations: List[BatchOperation]) -> APIResponse:
        batch_id = f"batch_{secrets.token_hex(6)}"
        results = []
        
        for op in operations:
            result = await _execute_batch_operation(state, op)
            results.append(result)
        
        return APIResponse(
            code=200,
            data=BatchResult(batch_id=batch_id, results=results),
        )

    @app.get("/api/v1/config/{namespace}")
    async def get_config(namespace: str) -> APIResponse:
        config = state.config_manager.get_latest(namespace)
        if not config:
            raise HTTPException(status_code=404, detail="Config not found")
        
        return APIResponse(code=200, data=config.model_dump())

    @app.post("/api/v1/config/{namespace}")
    async def update_config(namespace: str, parameters: Dict[str, Any]) -> APIResponse:
        current = state.config_manager.get_latest(namespace)
        new_version = (current.version + 1) if current else 1
        
        from top.core.models import ConfigModel
        config = ConfigModel(
            config_id=f"cfg_{secrets.token_hex(6)}",
            namespace=namespace,
            version=new_version,
            parameters=parameters,
            enabled=True,
        )
        state.config_manager.save(config)
        
        return APIResponse(
            code=200,
            data={"config_id": config.config_id, "version": config.version},
        )

    @app.get("/api/v1/metrics")
    async def get_metrics() -> Response:
        metrics_text = state.monitor.export(format="prometheus")
        return Response(
            content=metrics_text,
            media_type="text/plain; version=0.0.4",
        )

    @app.get("/health")
    async def health_check() -> APIResponse:
        return APIResponse(code=200, data={"status": "healthy"})


async def _execute_batch_operation(
    state: AppState,
    operation: BatchOperation,
) -> Dict[str, Any]:
    result = {
        "id": operation.id,
        "action": operation.action,
        "success": False,
        "error": None,
    }

    try:
        if operation.action == "restart":
            resource = state.resources.get(operation.id)
            if resource:
                resource["status"] = "restarting"
                resource["progress"] = 0.0
                resource["updated_at"] = utc_now()
                result["success"] = True
            else:
                result["error"] = "Resource not found"

        elif operation.action == "stop":
            resource = state.resources.get(operation.id)
            if resource:
                resource["status"] = "stopped"
                resource["updated_at"] = utc_now()
                result["success"] = True
            else:
                result["error"] = "Resource not found"

        elif operation.action == "delete":
            if operation.id in state.resources:
                del state.resources[operation.id]
                result["success"] = True
            else:
                result["error"] = "Resource not found"

        else:
            result["error"] = f"Unknown action: {operation.action}"

    except Exception as e:
        result["error"] = str(e)

    return result


def _setup_auth(state: AppState) -> None:
    secret_key = secrets.token_hex(32)
    state.auth_provider = JWTAuth(secret_key=secret_key, token_ttl=86400)

    admin_role = Role(
        role_id="admin",
        name="Administrator",
        permissions=[
            Permission(
                name="full_access",
                level=PermissionLevel.ADMIN,
                resource_pattern="*",
            )
        ],
    )
    viewer_role = Role(
        role_id="viewer",
        name="Viewer",
        permissions=[
            Permission(
                name="read_all",
                level=PermissionLevel.READ,
                resource_pattern="*",
            )
        ],
    )

    state.auth_provider.register_role(admin_role)
    state.auth_provider.register_role(viewer_role)


def _setup_rate_limit(state: AppState) -> None:
    from top.gateway.rate_limit import get_rate_limiter

    state.rate_limiter = get_rate_limiter()
    
    state.rate_limiter.configure_global_limit(
        RateLimitPolicy(
            limit=1000,
            window_seconds=60,
            algorithm=RateLimitAlgorithm.SLIDING_WINDOW,
        )
    )
    
    state.rate_limiter.configure_resource_limit(
        resource="/api/v1/resources*",
        policy=RateLimitPolicy(
            limit=100,
            window_seconds=60,
            algorithm=RateLimitAlgorithm.TOKEN_BUCKET,
            burst_limit=200,
        ),
        priority=10,
    )


def _setup_gateway(state: AppState) -> None:
    state.gateway = create_api_gateway(
        auth_provider=state.auth_provider,
        rate_limiter=state.rate_limiter,
        exempt_auth_paths=["/health", "/api/v1/metrics"],
        use_caching=True,
        cache_ttl=30,
    )


def _setup_config(state: AppState) -> None:
    store = InMemoryConfigStore()
    state.config_manager = ConfigManager(store=store)

    from top.core.models import ConfigModel
    
    default_config = ConfigModel(
        config_id="cfg_default",
        namespace="default",
        version=1,
        parameters={
            "timeout": 30,
            "retries": 3,
            "max_concurrent": 100,
        },
    )
    state.config_manager.save(default_config)

    prod_config = ConfigModel(
        config_id="cfg_prod",
        namespace="production",
        version=1,
        parameters={
            "timeout": 60,
            "retries": 5,
            "max_concurrent": 500,
        },
    )
    state.config_manager.save(prod_config)


def _setup_monitoring(state: AppState) -> None:
    state.monitor = get_monitor()
    
    state.monitor.create_counter(
        "resources.created",
        "Total number of resources created",
    )
    state.monitor.create_counter(
        "resources.status_queries",
        "Total number of status queries",
    )
    state.monitor.create_gauge(
        "resources.active",
        "Number of active resources",
    )
    state.monitor.create_histogram(
        "request_latency_ms",
        "Request latency in milliseconds",
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _app_state
    
    _setup_auth(_app_state)
    _setup_rate_limit(_app_state)
    _setup_gateway(_app_state)
    _setup_config(_app_state)
    _setup_monitoring(_app_state)
    _setup_routes(app, _app_state)
    
    app.state.top = _app_state
    
    yield

    _app_state.resources.clear()
    _app_state.workflows.clear()


def create_app() -> FastAPI:
    global _app_instance
    
    if _app_instance is not None:
        return _app_instance
    
    app = FastAPI(
        title="TOP - Task Orchestration Platform",
        description="A vertical technology domain dependency task orchestration system",
        version="0.1.0",
        lifespan=lifespan,
    )

    _app_instance = app
    return app


def get_app() -> FastAPI:
    global _app_instance
    if _app_instance is None:
        _app_instance = create_app()
    return _app_instance
