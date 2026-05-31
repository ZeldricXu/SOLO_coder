from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Any, Dict, List, Optional
from datetime import datetime

from .config import settings
from .modules import (
    get_logger,
    get_code_quality_service,
    get_core_processor,
    get_environment_manager,
    get_scheduler,
    get_document_index,
    get_scaffolder,
    get_cache,
    get_monitoring,
    set_log_level,
    get_current_log_level,
    TaskStatus,
    TaskType,
    ProjectType,
)

logger = get_logger(__name__)

app = FastAPI(title=settings.app_name, version=settings.app_version)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class APIResponse(BaseModel):
    code: int
    message: str = "success"
    data: Optional[Any] = None


@app.get("/")
async def root():
    return {"name": settings.app_name, "version": settings.app_version, "status": "running"}


@app.get("/health")
async def health_check():
    return {"status": "healthy", "timestamp": datetime.utcnow().isoformat()}


@app.post("/api/v1/resources", status_code=201)
async def create_resource(request: Dict[str, Any]) -> APIResponse:
    processor = get_core_processor()
    result = await processor.execute_handler({
        "traceId": f"trace_{datetime.utcnow().timestamp()}",
        "requestId": f"req_{datetime.utcnow().timestamp()}",
        "namespace": request.get("namespace", "default"),
        "params": {"payload": request},
    })
    if result.success:
        return APIResponse(code=201, data={
            "id": result.run_id or f"rsc_{datetime.utcnow().timestamp()}",
            "status": "provisioning",
        })
    raise HTTPException(status_code=result.error_code or 500, detail=result.error_message)


@app.get("/api/v1/resources/{resource_id}/status")
async def get_resource_status(resource_id: str) -> APIResponse:
    run = get_core_processor().get_run_manager().get_run(resource_id)
    if not run:
        raise HTTPException(status_code=404, detail="Resource not found")
    return APIResponse(code=200, data={
        "id": run.run_id,
        "status": run.phase,
        "progress": run.progress,
    })


@app.post("/api/v1/resources/batch")
async def batch_operations(request: Dict[str, Any]) -> APIResponse:
    results = []
    for op in request.get("operations", []):
        action = op.get("action")
        resource_id = op.get("id")
        if action == "stop":
            success = get_scheduler().cancel_task(resource_id)
            results.append({"id": resource_id, "action": action, "success": success})
        else:
            results.append({"id": resource_id, "action": action, "success": False})
    return APIResponse(code=200, data={
        "batch_id": f"batch_{datetime.utcnow().timestamp()}",
        "results": results,
    })


@app.post("/api/v1/code-quality/analyze")
async def analyze_code(request: Dict[str, Any]) -> APIResponse:
    service = get_code_quality_service()
    report = service.analyze_code(request["code"], request["language"], request.get("filename", "untitled"))
    return APIResponse(code=200, data={
        "report_id": report.report_id,
        "language": report.language,
        "total_issues": report.total_issues,
        "quality_score": report.quality_score,
        "threshold_pass": report.threshold_pass,
        "issues": [i.__dict__ for i in report.issues],
    })


@app.get("/api/v1/logs/level")
async def get_log_levels() -> APIResponse:
    return APIResponse(code=200, data={"current_level": get_current_log_level()})


@app.put("/api/v1/logs/level")
async def update_log_level(request: Dict[str, Any]) -> APIResponse:
    set_log_level(request["level"], request.get("logger_name"))
    return APIResponse(code=200, data={"level": request["level"]})


@app.get("/api/v1/cache/stats")
async def get_cache_stats() -> APIResponse:
    return APIResponse(code=200, data=get_cache().get_stats())


@app.delete("/api/v1/cache")
async def clear_cache() -> APIResponse:
    count = get_cache().clear()
    return APIResponse(code=200, data={"cleared_entries": count})


@app.get("/api/v1/monitoring/metrics")
async def get_metrics() -> APIResponse:
    monitoring = get_monitoring()
    snapshot = monitoring.collector.create_snapshot()
    return APIResponse(code=200, data={
        "snapshot_id": snapshot.snapshot_id,
        "timestamp": snapshot.timestamp.isoformat(),
        "metrics": snapshot.metrics,
    })


@app.get("/api/v1/environments")
async def list_environments(owner: Optional[str] = None, status: Optional[str] = None) -> APIResponse:
    envs = get_environment_manager().list(owner=owner, status=status)
    return APIResponse(code=200, data=[
        {"env_id": e.env_id, "name": e.name, "status": e.status, "endpoints": e.endpoints}
        for e in envs
    ])


@app.post("/api/v1/environments", status_code=201)
async def create_environment(request: Dict[str, Any]) -> APIResponse:
    from .modules.environment_module import EnvironmentType
    try:
        env = get_environment_manager().create(
            name=request["name"],
            owner=request["owner"],
            env_type=EnvironmentType(request.get("env_type", "preview")),
            config=request.get("config"),
            ttl_hours=request.get("ttl_hours"),
        )
        return APIResponse(code=201, data={
            "env_id": env.env_id,
            "name": env.name,
            "status": env.status,
            "endpoints": env.endpoints,
        })
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.get("/api/v1/environments/{env_id}")
async def get_environment(env_id: str) -> APIResponse:
    env = get_environment_manager().get(env_id)
    if not env:
        raise HTTPException(status_code=404, detail="Environment not found")
    return APIResponse(code=200, data={
        "env_id": env.env_id,
        "name": env.name,
        "type": env.type,
        "owner": env.owner,
        "status": env.status,
        "endpoints": env.endpoints,
    })


@app.post("/api/v1/environments/{env_id}/stop")
async def stop_environment(env_id: str) -> APIResponse:
    success = get_environment_manager().stop(env_id)
    if not success:
        raise HTTPException(status_code=404, detail="Environment not found")
    return APIResponse(code=200, data={"stopped": True})


@app.delete("/api/v1/environments/{env_id}")
async def delete_environment(env_id: str) -> APIResponse:
    success = get_environment_manager().delete(env_id)
    if not success:
        raise HTTPException(status_code=404, detail="Environment not found")
    return APIResponse(code=200, data={"deleted": True})


@app.post("/api/v1/tasks")
async def create_task(request: Dict[str, Any]) -> APIResponse:
    task = get_scheduler().add_task(
        name=request["name"],
        payload=request.get("payload"),
        dependencies=request.get("dependencies"),
    )
    return APIResponse(code=200, data={
        "task_id": task.task_id,
        "name": task.name,
        "status": task.status,
    })


@app.get("/api/v1/tasks")
async def list_tasks(status: Optional[str] = None) -> APIResponse:
    tasks = get_scheduler().list_tasks(status=TaskStatus(status) if status else None)
    return APIResponse(code=200, data=[
        {"task_id": t.task_id, "name": t.name, "status": t.status}
        for t in tasks
    ])


@app.get("/api/v1/tasks/{task_id}")
async def get_task(task_id: str) -> APIResponse:
    task = get_scheduler().get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return APIResponse(code=200, data={
        "task_id": task.task_id,
        "name": task.name,
        "status": task.status,
        "result": task.result,
        "error": task.error,
    })


@app.post("/api/v1/tasks/{task_id}/cancel")
async def cancel_task(task_id: str) -> APIResponse:
    success = get_scheduler().cancel_task(task_id)
    if not success:
        raise HTTPException(status_code=404, detail="Task not found")
    return APIResponse(code=200, data={"cancelled": True})


@app.get("/api/v1/tasks/stats")
async def get_task_stats() -> APIResponse:
    return APIResponse(code=200, data=get_scheduler().get_stats())


@app.get("/api/v1/docs/search")
async def search_documents(q: str, limit: int = 10) -> APIResponse:
    results = get_document_index().search(q, limit=limit)
    return APIResponse(code=200, data=[
        {"doc_id": r.doc_id, "title": r.title, "score": r.score, "snippet": r.snippet}
        for r in results
    ])


@app.get("/api/v1/docs")
async def list_documents() -> APIResponse:
    docs = get_document_index().list_documents()
    return APIResponse(code=200, data=[
        {"doc_id": d.doc_id, "title": d.title, "source": d.source}
        for d in docs
    ])


@app.get("/api/v1/docs/{doc_id}")
async def get_document(doc_id: str) -> APIResponse:
    doc = get_document_index().get_document(doc_id)
    if not doc:
        raise HTTPException(status_code=404, detail="Document not found")
    return APIResponse(code=200, data={
        "doc_id": doc.doc_id,
        "title": doc.title,
        "content": doc.content,
        "source": doc.source,
    })


@app.get("/api/v1/scaffold/templates")
async def list_scaffold_templates() -> APIResponse:
    return APIResponse(code=200, data=get_scaffolder().list_templates())


@app.post("/api/v1/scaffold/generate")
async def generate_scaffold(request: Dict[str, Any]) -> APIResponse:
    from .modules.scaffolding_module import TemplateConfig
    config = TemplateConfig(
        name=request["name"],
        template_type=ProjectType(request.get("template_type", "fastapi")),
        variables=request.get("variables", {}),
        author=request.get("author", "Anonymous"),
        version=request.get("version", "0.1.0"),
        description=request.get("description", ""),
    )
    project = get_scaffolder().generate(config)
    return APIResponse(code=200, data={
        "project_name": project.project_path,
        "template_type": project.template_type,
        "files": [f.path for f in project.files],
    })


@app.get("/api/v1/config/{namespace}")
async def get_config(namespace: str) -> APIResponse:
    config = get_core_processor().get_config_manager().load_config(namespace)
    return APIResponse(code=200, data={
        "config_id": config.config_id,
        "namespace": config.namespace,
        "version": config.version,
        "parameters": config.parameters,
    })


@app.put("/api/v1/config/{namespace}")
async def update_config(namespace: str, parameters: Dict[str, Any]) -> APIResponse:
    config = get_core_processor().get_config_manager().update_config(namespace, parameters)
    return APIResponse(code=200, data={
        "config_id": config.config_id,
        "version": config.version,
        "parameters": config.parameters,
    })


@app.on_event("startup")
async def startup_event():
    logger.info(f"{settings.app_name} v{settings.app_version} starting up...")
    await get_scheduler().start(max_workers=10)
    logger.info("Scheduler started")


@app.on_event("shutdown")
async def shutdown_event():
    logger.info("Shutting down...")
    await get_scheduler().stop()
    logger.info("Scheduler stopped")
