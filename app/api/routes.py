import uuid
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Request, UploadFile, File, Query
from fastapi.responses import JSONResponse, StreamingResponse

from app.config.manager import get_config_manager
from app.core.processor import get_request_processor, EventType
from app.data.database import get_db_manager
from app.data.query_optimizer import get_query_optimizer
from app.gateway.middleware import get_gateway
from app.monitoring.metrics import get_metrics_collector, MetricType
from app.monitoring.tracing import get_tracer
from app.models.schemas import (
    ResourceCreate, ResourceResponse, ResourceStatus,
    BatchRequest, BatchResultItem, BatchResult,
    EntityModel, ConfigModel, RunModel, MetricsSnapshotModel
)

router = APIRouter(prefix="/api/v1")

_resources: Dict[str, Dict[str, Any]] = {}


def get_trace_id(request: Request) -> str:
    headers = dict(request.headers)
    trace_headers = ["x-trace-id", "x-request-id", "traceparent"]
    for header in trace_headers:
        if header in headers:
            value = headers[header]
            if header == "traceparent" and "-" in value:
                return value.split("-")[1]
            return value
    return uuid.uuid4().hex


@router.get("/health", tags=["health"])
async def health_check():
    return {
        "status": "healthy",
        "timestamp": datetime.utcnow().isoformat(),
        "version": "1.0.0"
    }


@router.post("/resources", response_model=ResourceResponse, tags=["resources"])
async def create_resource(
    request: Request,
    resource: ResourceCreate,
    trace_id: str = Depends(get_trace_id)
):
    tracer = get_tracer()
    with tracer.span("create_resource", trace_id=trace_id):
        resource_id = f"rsc_{uuid.uuid4().hex[:6]}"

        _resources[resource_id] = {
            "id": resource_id,
            "type": resource.type,
            "status": ResourceStatus.PROVISIONING,
            "config": resource.config,
            "labels": resource.labels,
            "progress": 0.0,
            "created_at": datetime.utcnow().isoformat()
        }

        processor = get_request_processor()
        processor.emitter.emit(
            processor.init_context().__class__.__bases__[0].__class__.__name__
            if False else
            type(processor.init_context()).__mro__[0].__name__
        )

        return ResourceResponse(
            code=201,
            data={
                "id": resource_id,
                "status": ResourceStatus.PROVISIONING
            }
        )


@router.get("/resources/{resource_id}/status", tags=["resources"])
async def get_resource_status(
    resource_id: str,
    trace_id: str = Depends(get_trace_id)
):
    tracer = get_tracer()
    with tracer.span("get_resource_status", trace_id=trace_id):
        if resource_id not in _resources:
            raise HTTPException(status_code=404, detail="Resource not found")

        resource = _resources[resource_id]
        if resource["status"] == ResourceStatus.PROVISIONING:
            resource["progress"] = min(resource["progress"] + 0.2, 1.0)
            if resource["progress"] >= 1.0:
                resource["status"] = ResourceStatus.RUNNING

        return {
            "code": 200,
            "data": {
                "id": resource_id,
                "status": resource["status"],
                "progress": resource["progress"]
            }
        }


@router.post("/resources/batch", response_model=BatchResult, tags=["resources"])
async def batch_operations(
    request: Request,
    batch: BatchRequest,
    trace_id: str = Depends(get_trace_id)
):
    tracer = get_tracer()
    with tracer.span("batch_operations", trace_id=trace_id):
        results: List[Dict[str, Any]] = []

        for op in batch.operations:
            success = False
            message = None

            if op.action == "stop":
                if op.id in _resources:
                    _resources[op.id]["status"] = ResourceStatus.STOPPED
                    success = True
                else:
                    message = "Resource not found"
            elif op.action == "start":
                if op.id in _resources:
                    _resources[op.id]["status"] = ResourceStatus.RUNNING
                    success = True
                else:
                    message = "Resource not found"
            elif op.action == "delete":
                if op.id in _resources:
                    del _resources[op.id]
                    success = True
                else:
                    message = "Resource not found"
            else:
                message = f"Unknown action: {op.action}"

            results.append({
                "id": op.id,
                "success": success,
                "message": message
            })

        batch_id = f"batch_{uuid.uuid4().hex[:6]}"
        return BatchResult(
            code=200,
            data={
                "batch_id": batch_id,
                "results": results
            }
        )


@router.post("/execute", tags=["core"])
async def execute_handler_endpoint(
    request: Request,
    trace_id: str = Depends(get_trace_id)
):
    tracer = get_tracer()
    with tracer.span("execute_handler_endpoint", trace_id=trace_id):
        body = await request.json() if await request.body() else {}
        processor = get_request_processor()
        result = await processor.execute_handler(body)
        return result


@router.get("/config/{namespace}", tags=["config"])
async def get_config(
    namespace: str = "default",
    key: Optional[str] = None
):
    config_manager = get_config_manager()
    if key:
        value = config_manager.get(namespace, key)
        return {"namespace": namespace, "key": key, "value": value}
    config = config_manager.get(namespace)
    return {"namespace": namespace, "config": config}


@router.put("/config/{namespace}/{key}", tags=["config"])
async def update_config(
    namespace: str,
    key: str,
    value: Any
):
    config_manager = get_config_manager()
    config_manager.set(namespace, key, value)
    return {
        "namespace": namespace,
        "key": key,
        "value": value,
        "version": config_manager.get_version(namespace)
    }


@router.get("/metrics", tags=["monitoring"])
async def get_metrics():
    metrics = get_metrics_collector()
    return metrics.snapshot()


@router.get("/metrics/prometheus", tags=["monitoring"])
async def get_prometheus_metrics():
    metrics = get_metrics_collector()
    return metrics.export_prometheus()


@router.get("/traces/{trace_id}", tags=["tracing"])
async def get_trace(trace_id: str):
    tracer = get_tracer()
    trace = tracer.get_trace(trace_id)
    if not trace:
        raise HTTPException(status_code=404, detail="Trace not found")
    return tracer.export_trace(trace_id)


@router.get("/logs", tags=["gateway"])
async def get_logs(limit: int = Query(100, ge=1, le=1000)):
    gateway = get_gateway()
    return {"logs": gateway.get_recent_logs(limit)}


@router.post("/quality/check", tags=["quality"])
async def run_quality_check(path: str):
    from app.quality.gate import run_quality_check
    import os
    if not os.path.exists(path):
        raise HTTPException(status_code=404, detail="Path not found")
    report = run_quality_check(path)
    return report.to_dict()


@router.post("/vulnerability/analyze", tags=["vulnerability"])
async def analyze_vulnerabilities(file_path: str):
    from app.vulnerability.analyzer import analyze_sbom
    import os
    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="SBOM file not found")
    return analyze_sbom(file_path)


@router.post("/storage/upload", tags=["storage"])
async def upload_storage_file(
    bucket: str = "default",
    file: UploadFile = File(...)
):
    from app.storage.manager import upload_file
    content = await file.read()
    metadata = await upload_file(
        file_name=file.filename or "uploaded_file",
        content=content,
        content_type=file.content_type,
        bucket=bucket
    )
    return {
        "file_id": metadata.file_id,
        "name": metadata.original_name,
        "size": metadata.size,
        "content_type": metadata.content_type,
        "md5_hash": metadata.md5_hash
    }


@router.get("/storage/{file_id}", tags=["storage"])
async def download_storage_file(file_id: str, bucket: str = "default"):
    from app.storage.manager import download_file
    stored = await download_file(file_id, bucket)
    if not stored:
        raise HTTPException(status_code=404, detail="File not found")

    return StreamingResponse(
        iter([stored.content]),
        media_type=stored.metadata.content_type,
        headers={
            "Content-Disposition": f"attachment; filename={stored.metadata.original_name}",
            "X-File-Id": file_id
        }
    )


@router.delete("/storage/{file_id}", tags=["storage"])
async def delete_storage_file(file_id: str, bucket: str = "default"):
    from app.storage.manager import delete_file
    success = await delete_file(file_id, bucket)
    if not success:
        raise HTTPException(status_code=404, detail="File not found")
    return {"success": True, "file_id": file_id}


@router.get("/db/pools", tags=["database"])
async def get_db_pools():
    db_manager = get_db_manager()
    return db_manager.get_all_stats()


@router.get("/db/health", tags=["database"])
async def check_db_health():
    db_manager = get_db_manager()
    return await db_manager.check_all_health()


@router.get("/db/query-stats", tags=["database"])
async def get_query_stats():
    optimizer = get_query_optimizer()
    return {
        "cache_stats": optimizer.get_cache_stats(),
        "queries": optimizer.get_query_stats()
    }


@router.post("/contract/validate/openapi", tags=["contract"])
async def validate_openapi_schema(schema: Dict[str, Any]):
    from app.contract.testing import validate_openapi
    result = validate_openapi(schema)
    return {
        "valid": result.valid,
        "errors": [{"message": e.message, "path": e.path} for e in result.errors],
        "warnings": [{"message": e.message, "path": e.path} for e in result.warnings]
    }


@router.post("/contract/validate/graphql", tags=["contract"])
async def validate_graphql_schema(schema_str: str):
    from app.contract.testing import validate_graphql
    result = validate_graphql(schema_str)
    return {
        "valid": result.valid,
        "errors": [{"message": e.message, "path": e.path} for e in result.errors],
        "warnings": [{"message": e.message, "path": e.path} for e in result.warnings]
    }


@router.post("/contract/mock/register", tags=["contract"])
async def register_mock_endpoint(
    method: str,
    path: str,
    status_code: int = 200,
    response_body: Optional[Dict[str, Any]] = None
):
    from app.contract.testing import create_mock_server
    server = create_mock_server()
    endpoint = server.register_endpoint(
        method=method,
        path=path,
        status_code=status_code,
        response_body=response_body or {}
    )
    return {
        "method": endpoint.method,
        "path": endpoint.path,
        "status_code": endpoint.status_code
    }


@router.get("/entities", tags=["models"], response_model=List[EntityModel])
async def list_entities():
    return [
        EntityModel(
            id="ent_001",
            type="record",
            status="completed",
            attributes={"key": "value"}
        )
    ]


@router.get("/configs", tags=["models"], response_model=List[ConfigModel])
async def list_configs():
    config_manager = get_config_manager()
    namespaces = config_manager.get_namespaces()
    return [
        ConfigModel(
            config_id=f"cfg_{i:03d}",
            namespace=ns,
            version=config_manager.get_version(ns),
            parameters=config_manager.get(ns),
            enabled=True
        )
        for i, ns in enumerate(namespaces)
    ]


@router.get("/metrics-snapshot", tags=["models"], response_model=MetricsSnapshotModel)
async def get_metrics_snapshot():
    metrics = get_metrics_collector()
    snapshot = metrics.snapshot()
    return MetricsSnapshotModel(
        metrics={
            "throughput": metrics.get_counter("requests_succeeded") or 0,
            "latency_p99": (
                metrics.get_histogram_stats("request_total_duration_ms") or
                type("obj", (), {"p99": 0})()
            ).p99,
            "error_rate": metrics.get_counter("requests_failed") or 0
        },
        dimensions={"host": "node-1", "region": "cn-east"}
    )
