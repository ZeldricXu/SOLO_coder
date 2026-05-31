import uuid
import asyncio
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from fastapi import FastAPI, HTTPException, Depends, BackgroundTasks, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager

from ..modules.logging_module import LogManager, get_logger
from ..modules.config_module import get_config_manager, get_app_config
from ..modules.core_module import CoreEngine, get_core_engine, TaskStatus
from ..modules.data_access import get_db_manager, get_entity_repository, EntityStatus
from ..modules.notification_module import (
    get_notification_manager, NotificationPriority, NotificationChannel
)
from ..modules.fault_injection import (
    get_fault_injection_manager, FaultType, InjectionScope, FaultStatus
)
from ..modules.audit_module import (
    get_command_audit_manager, CommandType, AuditAction, Severity
)
from ..modules.storage_module import get_storage_manager, BackupInfo
from ..modules.event_store import get_event_store, EventType

from .models import (
    ApiResponse, ResourceCreateRequest, ResourceCreateResponse, ResourceStatusResponse,
    BatchRequest, BatchResponse, BatchResult, TaskResponse, NotificationRequest,
    FaultCreateRequest, ConfigCreateRequest, AuditQueryRequest, BackupRequest, RestoreRequest
)

logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    LogManager()
    config = get_app_config()
    logger.info("Starting application", app_name=config.app_name, env=config.app_env)

    core_engine = get_core_engine()
    await core_engine.initialize()

    fault_manager = get_fault_injection_manager()
    if config.feature_flags.get("fault_injection"):
        fault_manager.enable()
        logger.warning("Fault injection feature is enabled")

    yield

    logger.info("Shutting down application")
    await core_engine.shutdown()


app = FastAPI(
    title="Cloud Native Engine API",
    description="可扩展的云原生引擎 - 数据迁移与Schema版本控制",
    version="0.1.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

config = get_app_config()
api_prefix = config.api_prefix


@app.middleware("http")
async def add_request_id(request: Request, call_next):
    request_id = str(uuid.uuid4())
    start_time = datetime.utcnow()

    audit_manager = get_command_audit_manager()
    await audit_manager.log_action(
        action=AuditAction.API_CALL,
        description=f"API call: {request.method} {request.url.path}",
        source_ip=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
        request_id=request_id,
        metadata={"method": request.method, "path": request.url.path},
    )

    response = await call_next(request)
    response.headers["X-Request-ID"] = request_id

    duration = (datetime.utcnow() - start_time).total_seconds()
    logger.info(
        "Request completed",
        method=request.method,
        path=request.url.path,
        status_code=response.status_code,
        duration_seconds=duration,
        request_id=request_id,
    )

    return response


@app.get("/health")
async def health_check():
    return {"status": "healthy", "timestamp": datetime.utcnow().isoformat()}


@app.get(f"{api_prefix}/resources")
async def list_resources(
    type: Optional[str] = None,
    status: Optional[str] = None,
    skip: int = 0,
    limit: int = 100,
):
    db = get_db_manager()
    repo = get_entity_repository()

    async with db.get_session() as session:
        filters = {}
        if type:
            filters["type"] = type
        if status:
            filters["status"] = status

        entities = await repo.list(session, skip=skip, limit=limit, filters=filters)
        return ApiResponse(
            data=[
                {
                    "id": e.id,
                    "type": e.type,
                    "status": e.status,
                    "created_at": e.created_at.isoformat(),
                    "labels": e.labels,
                }
                for e in entities
            ]
        )


@app.post(f"{api_prefix}/resources", response_model=ApiResponse[ResourceCreateResponse])
async def create_resource(request: ResourceCreateRequest):
    core = get_core_engine()
    result = await core.execution_handler.create_entity(
        entity_type=request.type,
        config=request.config,
        labels=request.labels,
        user_id=request.user_id,
        metadata=request.metadata,
    )

    if "code" in result and result["code"] != 200:
        raise HTTPException(status_code=result["code"], detail=result.get("error"))

    return ApiResponse(
        code=201,
        message="Resource created successfully",
        data=ResourceCreateResponse(
            id=result["id"],
            status=result["status"],
            trace_id=result["trace_id"],
        ),
    )


@app.get(f"{api_prefix}/resources/{{resource_id}}/status", response_model=ApiResponse[ResourceStatusResponse])
async def get_resource_status(resource_id: str):
    core = get_core_engine()
    result = await core.execution_handler.get_entity_status(resource_id)

    if "code" in result and result["code"] == 404:
        raise HTTPException(status_code=404, detail=result.get("error"))

    return ApiResponse(data=ResourceStatusResponse(**result))


@app.get(f"{api_prefix}/resources/{{resource_id}}")
async def get_resource(resource_id: str):
    db = get_db_manager()
    repo = get_entity_repository()

    async with db.get_session() as session:
        entity = await repo.get_by_id(session, resource_id)
        if not entity:
            raise HTTPException(status_code=404, detail="Resource not found")

        return ApiResponse(data={
            "id": entity.id,
            "type": entity.type,
            "status": entity.status,
            "attributes": entity.attributes,
            "labels": entity.labels,
            "created_at": entity.created_at.isoformat(),
            "updated_at": entity.updated_at.isoformat(),
        })


@app.post(f"{api_prefix}/resources/batch", response_model=ApiResponse[BatchResponse])
async def batch_operations(request: BatchRequest):
    core = get_core_engine()
    db = get_db_manager()
    repo = get_entity_repository()

    batch_id = f"batch_{uuid.uuid4().hex[:8]}"
    results: List[BatchResult] = []

    for op in request.operations:
        try:
            if op.action == "start":
                result = await core.execution_handler.create_entity(
                    entity_type="task",
                    config=op.params.get("config", {}),
                )
                success = "code" not in result or result["code"] == 201
                results.append(BatchResult(
                    id=op.id,
                    action=op.action,
                    success=success,
                    result=result if success else None,
                    error=result.get("error") if not success else None,
                ))
            elif op.action == "stop":
                async with db.get_session() as session:
                    updated = await repo.update_status(session, op.id, EntityStatus.CANCELLED)
                    results.append(BatchResult(
                        id=op.id,
                        action=op.action,
                        success=updated is not None,
                        result={"status": EntityStatus.CANCELLED} if updated else None,
                        error="Resource not found" if not updated else None,
                    ))
            else:
                results.append(BatchResult(
                    id=op.id,
                    action=op.action,
                    success=False,
                    error=f"Unknown action: {op.action}",
                ))
        except Exception as e:
            results.append(BatchResult(
                id=op.id,
                action=op.action,
                success=False,
                error=str(e),
            ))

    return ApiResponse(data=BatchResponse(batch_id=batch_id, results=results))


@app.delete(f"{api_prefix}/resources/{{resource_id}}")
async def delete_resource(resource_id: str):
    db = get_db_manager()
    repo = get_entity_repository()

    async with db.get_session() as session:
        deleted = await repo.delete(session, resource_id)
        if not deleted:
            raise HTTPException(status_code=404, detail="Resource not found")

    return ApiResponse(message="Resource deleted successfully")


@app.get(f"{api_prefix}/tasks")
async def list_tasks(
    status: Optional[str] = None,
    name: Optional[str] = None,
    limit: int = 100,
):
    core = get_core_engine()
    status_enum = TaskStatus(status) if status else None
    tasks = core.scheduler.list_tasks(status=status_enum, name=name, limit=limit)

    return ApiResponse(data=[
        TaskResponse(
            task_id=t.task_id,
            name=t.name,
            status=t.status.value,
            priority=t.priority.value,
            created_at=t.created_at.isoformat(),
            started_at=t.started_at.isoformat() if t.started_at else None,
            completed_at=t.completed_at.isoformat() if t.completed_at else None,
            result=t.result,
            error=t.error,
        )
        for t in tasks
    ])


@app.get(f"{api_prefix}/tasks/{{task_id}}", response_model=ApiResponse[TaskResponse])
async def get_task(task_id: str):
    core = get_core_engine()
    task = core.scheduler.get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    return ApiResponse(data=TaskResponse(
        task_id=task.task_id,
        name=task.name,
        status=task.status.value,
        priority=task.priority.value,
        created_at=task.created_at.isoformat(),
        started_at=task.started_at.isoformat() if task.started_at else None,
        completed_at=task.completed_at.isoformat() if task.completed_at else None,
        result=task.result,
        error=task.error,
    ))


@app.post(f"{api_prefix}/tasks/{{task_id}}/cancel")
async def cancel_task(task_id: str):
    core = get_core_engine()
    cancelled = core.scheduler.cancel_task(task_id)
    if not cancelled:
        raise HTTPException(status_code=404, detail="Task not found or cannot be cancelled")

    return ApiResponse(message="Task cancelled successfully")


@app.get(f"{api_prefix}/tasks/{{task_id}}/result")
async def get_task_result(task_id: str, wait: bool = False, timeout: float = 30.0):
    core = get_core_engine()

    if wait:
        try:
            result = await core.wait_for_task(task_id, timeout=timeout)
        except asyncio.TimeoutError:
            raise HTTPException(status_code=408, detail="Timeout waiting for task result")
    else:
        result = core.get_task_result(task_id)

    if not result:
        raise HTTPException(status_code=404, detail="Task result not found")

    return ApiResponse(data=result)


@app.get(f"{api_prefix}/configs")
async def list_configs():
    config_manager = get_config_manager()
    return ApiResponse(data=config_manager.get_all_configs())


@app.post(f"{api_prefix}/configs")
async def create_config(request: ConfigCreateRequest):
    db = get_db_manager()
    from ..modules.data_access import get_config_repository
    repo = get_config_repository()

    async with db.get_session() as session:
        config = await repo.create_new_version(
            session,
            config_id=request.config_id,
            namespace=request.namespace,
            parameters=request.parameters,
            description=request.description,
        )

    return ApiResponse(
        code=201,
        message="Config created successfully",
        data={"config_id": config.config_id, "version": config.version, "namespace": config.namespace},
    )


@app.get(f"{api_prefix}/configs/{{config_id}}")
async def get_config(config_id: str, namespace: str = "default"):
    db = get_db_manager()
    from ..modules.data_access import get_config_repository
    repo = get_config_repository()

    async with db.get_session() as session:
        config = await repo.get_latest_version(session, config_id, namespace)
        if not config:
            raise HTTPException(status_code=404, detail="Config not found")

        return ApiResponse(data={
            "config_id": config.config_id,
            "namespace": config.namespace,
            "version": config.version,
            "parameters": config.parameters,
            "enabled": config.enabled,
            "applied_at": config.applied_at.isoformat() if config.applied_at else None,
        })


@app.get(f"{api_prefix}/notifications")
async def list_notifications(limit: int = 100):
    nm = get_notification_manager()
    notifications = nm.get_recent_notifications(limit=limit)
    return ApiResponse(data=[n.to_dict() for n in notifications])


@app.post(f"{api_prefix}/notifications")
async def send_notification(request: NotificationRequest):
    nm = get_notification_manager()

    priority_map = {
        "low": NotificationPriority.LOW,
        "medium": NotificationPriority.MEDIUM,
        "high": NotificationPriority.HIGH,
        "critical": NotificationPriority.CRITICAL,
    }

    channel_map = {
        "email": NotificationChannel.EMAIL,
        "slack": NotificationChannel.SLACK,
        "webhook": NotificationChannel.WEBHOOK,
        "console": NotificationChannel.CONSOLE,
        "sms": NotificationChannel.SMS,
        "push": NotificationChannel.PUSH,
        "in_app": NotificationChannel.IN_APP,
    }

    priority = priority_map.get(request.priority.lower(), NotificationPriority.MEDIUM)
    channels = [channel_map.get(c.lower(), NotificationChannel.CONSOLE) for c in request.channels]

    notification = nm.create_notification(
        title=request.title,
        message=request.message,
        priority=priority,
        channels=channels if channels else None,
        recipients=request.recipients,
        tags=request.tags,
    )

    result = await nm.send(notification)
    return ApiResponse(data={"notification_id": result.notification_id, "status": result.status.value})


@app.get(f"{api_prefix}/notifications/stats")
async def get_notification_stats():
    nm = get_notification_manager()
    return ApiResponse(data=nm.get_stats())


@app.post(f"{api_prefix}/notifications/silence")
async def silence_notifications(duration: int = 3600):
    nm = get_notification_manager()
    rule = nm.silence(duration=duration)
    return ApiResponse(data={"rule_id": rule.rule_id, "end_time": rule.end_time.isoformat()})


@app.get(f"{api_prefix}/faults")
async def list_faults(status: Optional[str] = None):
    fm = get_fault_injection_manager()
    status_enum = FaultStatus(status) if status else None
    faults = fm.list_faults(status=status_enum)
    return ApiResponse(data=[f.to_dict() for f in faults])


@app.post(f"{api_prefix}/faults")
async def create_fault(request: FaultCreateRequest):
    fm = get_fault_injection_manager()

    try:
        fault_type = FaultType(request.fault_type)
        scope = InjectionScope(request.scope)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    fault = fm.create_fault(
        fault_type=fault_type,
        scope=scope,
        target=request.target,
        parameters=request.parameters,
        description=request.description,
    )

    return ApiResponse(
        code=201,
        message="Fault created successfully",
        data={"fault_id": fault.fault_id, "status": fault.status.value},
    )


@app.post(f"{api_prefix}/faults/{{fault_id}}/activate")
async def activate_fault(fault_id: str):
    fm = get_fault_injection_manager()
    if not fm.is_enabled:
        raise HTTPException(status_code=400, detail="Fault injection is not enabled")

    fault = fm.activate_fault(fault_id)
    if not fault:
        raise HTTPException(status_code=404, detail="Fault not found")

    return ApiResponse(message="Fault activated successfully", data=fault.to_dict())


@app.post(f"{api_prefix}/faults/{{fault_id}}/deactivate")
async def deactivate_fault(fault_id: str):
    fm = get_fault_injection_manager()
    fault = fm.deactivate_fault(fault_id)
    if not fault:
        raise HTTPException(status_code=404, detail="Fault not found")

    return ApiResponse(message="Fault deactivated successfully", data=fault.to_dict())


@app.delete(f"{api_prefix}/faults/{{fault_id}}")
async def delete_fault(fault_id: str):
    fm = get_fault_injection_manager()
    deleted = fm.delete_fault(fault_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Fault not found")

    return ApiResponse(message="Fault deleted successfully")


@app.get(f"{api_prefix}/faults/stats")
async def get_fault_stats():
    fm = get_fault_injection_manager()
    return ApiResponse(data=fm.get_stats())


@app.get(f"{api_prefix}/audit/logs")
async def get_audit_logs(
    start_time: Optional[datetime] = None,
    end_time: Optional[datetime] = None,
    user_id: Optional[str] = None,
    action: Optional[str] = None,
    limit: int = 100,
):
    am = get_command_audit_manager()
    action_enum = AuditAction(action) if action else None

    logs = await am.query_audit_logs(
        start_time=start_time,
        end_time=end_time,
        user_id=user_id,
        action=action_enum,
        limit=limit,
    )

    return ApiResponse(data=[log.to_dict() for log in logs])


@app.get(f"{api_prefix}/audit/reports")
async def generate_audit_report(
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
):
    am = get_command_audit_manager()
    report = await am.generate_compliance_report(
        start_date=start_date,
        end_date=end_date,
    )
    return ApiResponse(data=report.to_dict())


@app.get(f"{api_prefix}/audit/commands")
async def list_commands(
    status: Optional[str] = None,
    user_id: Optional[str] = None,
    limit: int = 100,
):
    am = get_command_audit_manager()
    from ..modules.audit_module import CommandStatus
    status_enum = CommandStatus(status) if status else None

    commands = await am.list_commands(
        status=status_enum,
        user_id=user_id,
        limit=limit,
    )

    return ApiResponse(data=[cmd.to_dict() for cmd in commands])


@app.get(f"{api_prefix}/commands")
async def list_all_commands(
    status: Optional[str] = None,
    user_id: Optional[str] = None,
    limit: int = 100,
):
    am = get_command_audit_manager()
    from ..modules.audit_module import CommandStatus
    status_enum = CommandStatus(status) if status else None

    commands = await am.list_commands(
        status=status_enum,
        user_id=user_id,
        limit=limit,
    )

    return ApiResponse(data=[cmd.to_dict() for cmd in commands])


@app.get(f"{api_prefix}/commands/{{command_id}}")
async def get_command(command_id: str):
    am = get_command_audit_manager()
    command = await am.get_command(command_id)
    if not command:
        raise HTTPException(status_code=404, detail="Command not found")

    return ApiResponse(data=command.to_dict())


@app.get(f"{api_prefix}/storage/backups")
async def list_backups(limit: int = 100):
    sm = get_storage_manager()
    backups = await sm.list_backups(limit=limit)
    return ApiResponse(data=[b.__dict__ for b in backups])


@app.post(f"{api_prefix}/storage/backups")
async def create_backup(request: BackupRequest, background_tasks: BackgroundTasks):
    sm = get_storage_manager()
    background_tasks.add_task(sm.create_backup, request.source_path, request.backup_name)

    return ApiResponse(
        code=202,
        message="Backup creation started",
        data={"source_path": request.source_path},
    )


@app.post(f"{api_prefix}/storage/backups/{{backup_id}}/restore")
async def restore_backup(backup_id: str, request: RestoreRequest):
    sm = get_storage_manager()
    try:
        success = await sm.restore_backup(backup_id, request.destination_path, request.overwrite)
        return ApiResponse(message="Backup restored successfully", data={"success": success})
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="Backup not found")
    except FileExistsError:
        raise HTTPException(status_code=409, detail="Destination exists and overwrite is disabled")


@app.delete(f"{api_prefix}/storage/backups/{{backup_id}}")
async def delete_backup(backup_id: str):
    sm = get_storage_manager()
    deleted = await sm.delete_backup(backup_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Backup not found")

    return ApiResponse(message="Backup deleted successfully")


@app.get(f"{api_prefix}/storage/objects")
async def list_storage_objects(prefix: str = ""):
    sm = get_storage_manager()
    objects = await sm.list_data(prefix=prefix)
    return ApiResponse(data=[
        {
            "key": o.key,
            "size": o.size,
            "last_modified": o.last_modified.isoformat(),
            "etag": o.etag,
        }
        for o in objects
    ])


@app.get(f"{api_prefix}/events")
async def list_events(
    aggregate_id: Optional[str] = None,
    event_type: Optional[str] = None,
    limit: int = 100,
):
    es = get_event_store()
    et = EventType(event_type) if event_type else None

    if aggregate_id:
        events = await es.get_events(aggregate_id)
    else:
        events = []
        async for event in es.get_all_events(event_type=et, limit=limit):
            events.append(event)

    return ApiResponse(data=[e.to_dict() for e in events])


@app.get(f"{api_prefix}/events/snapshots")
async def list_snapshots(aggregate_id: Optional[str] = None):
    es = get_event_store()
    return ApiResponse(data={"message": "Snapshots API endpoint"})


@app.get(f"{api_prefix}/system/stats")
async def get_system_stats():
    core = get_core_engine()
    es = get_event_store()
    fm = get_fault_injection_manager()
    nm = get_notification_manager()
    config = get_config_manager()

    return ApiResponse(data={
        "task_scheduler": core.scheduler.get_stats(),
        "fault_injection": fm.get_stats(),
        "notifications": nm.get_stats(),
        "config_hash": config.config_hash,
        "timestamp": datetime.utcnow().isoformat(),
    })


@app.get(f"{api_prefix}/system/config")
async def get_system_config():
    config = get_config_manager()
    return ApiResponse(data=config.get_all_configs())


@app.post(f"{api_prefix}/system/shutdown")
async def system_shutdown():
    core = get_core_engine()
    asyncio.create_task(core.shutdown())
    return ApiResponse(message="Shutdown initiated")


def get_core_engine() -> CoreEngine:
    return get_core_engine()


if __name__ == "__main__":
    import uvicorn
    config = get_app_config()
    uvicorn.run(
        "src.api.main:app",
        host=config.app_host,
        port=config.app_port,
        reload=config.app_debug,
    )
