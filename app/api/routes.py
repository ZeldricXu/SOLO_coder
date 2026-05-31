from fastapi import APIRouter, HTTPException
from typing import List, Dict, Any, Optional
from pydantic import BaseModel

from app.core.models import (
    ResourceRequest, BatchOperation, APIResponse,
    DataCategory, SensitivityLevel
)
from app.modules.core_processor import core_processor
from app.modules.data_access import data_access_module
from app.modules.config_manager import config_module
from app.modules.storage import storage_module
from app.modules.classification import classification_module
from app.modules.differential_privacy import dp_module
from app.modules.audit import audit_module
from app.modules.notification import notification_module, NotificationChannel
from app.modules.mpc import mpc_module, MPCProtocol

router = APIRouter(prefix="/api/v1", tags=["resources"])


@router.post("/resources", response_model=APIResponse)
async def create_resource(request: ResourceRequest):
    result = core_processor.create_resource(request)
    audit_module.log("api_user", "create_resource", "resource", result.data.id if result.data else None)
    return result


@router.get("/resources/{resource_id}/status", response_model=APIResponse)
async def get_resource_status(resource_id: str):
    result = core_processor.get_resource_status(resource_id)
    audit_module.log("api_user", "get_resource_status", "resource", resource_id)
    return result


@router.post("/resources/batch", response_model=APIResponse)
async def batch_operations(operations: List[BatchOperation]):
    result = await core_processor.execute_batch(operations)
    audit_module.log("api_user", "batch_operations", "resource", None, {"count": len(operations)})
    return result


@router.get("/resources", response_model=APIResponse)
async def list_resources(entity_type: Optional[str] = None):
    resources = data_access_module.list_resources(entity_type)
    audit_module.log("api_user", "list_resources", "resource", None, {"type_filter": entity_type})
    return APIResponse(
        code=200,
        data={"resources": [r.model_dump() for r in resources], "count": len(resources)}
    )


@router.delete("/resources/{resource_id}", response_model=APIResponse)
async def delete_resource(resource_id: str):
    success = data_access_module.delete_resource(resource_id)
    audit_module.log("api_user", "delete_resource", "resource", resource_id)
    if success:
        return APIResponse(code=200, data={"deleted": True}, message="资源已删除")
    return APIResponse(code=404, message="资源不存在")


config_router = APIRouter(prefix="/api/v1/configs", tags=["configs"])


@config_router.get("/{namespace}", response_model=APIResponse)
async def get_config(namespace: str = "default"):
    config = config_module.get_config_entity(namespace)
    if config:
        audit_module.log("api_user", "get_config", "config", namespace)
        return APIResponse(code=200, data=config.model_dump())
    return APIResponse(code=404, message="配置不存在")


@config_router.post("/{namespace}", response_model=APIResponse)
async def create_config(namespace: str, parameters: Dict[str, Any]):
    config = config_module.create_and_apply(namespace, parameters, "API创建")
    audit_module.log("api_user", "create_config", "config", namespace, {"version": config.version})
    return APIResponse(code=201, data=config.model_dump(), message="配置已创建")


@config_router.put("/{namespace}", response_model=APIResponse)
async def update_config(namespace: str, parameters: Dict[str, Any]):
    config = config_module.set(namespace, parameters, "API更新")
    audit_module.log("api_user", "update_config", "config", namespace, {"version": config.version})
    return APIResponse(code=200, data=config.model_dump(), message="配置已更新")


@config_router.post("/{namespace}/rollback", response_model=APIResponse)
async def rollback_config(namespace: str, target_version: int, reason: str = "rollback"):
    config = config_module.rollback_manager.rollback_to_version(namespace, target_version, reason)
    if config:
        audit_module.log("api_user", "rollback_config", "config", namespace, {"to_version": target_version})
        return APIResponse(code=200, data=config.version_id, message="配置已回滚")
    return APIResponse(code=400, message="回滚失败")


@config_router.get("/{namespace}/versions", response_model=APIResponse)
async def list_config_versions(namespace: str):
    versions = config_module.version_manager.list_versions(namespace)
    return APIResponse(
        code=200,
        data={
            "namespace": namespace,
            "active_version": config_module.version_manager.get_active_version(namespace),
            "versions": [
                {
                    "version": v.version,
                    "status": v.status,
                    "created_at": v.created_at.isoformat(),
                    "description": v.description
                }
                for v in versions
            ]
        }
    )


storage_router = APIRouter(prefix="/api/v1/storage", tags=["storage"])


@storage_router.post("/backups", response_model=APIResponse)
async def create_backup(source_path: str, name: Optional[str] = None, compress: bool = True):
    try:
        record = await storage_module.create_and_execute_backup(source_path, name, compress=compress)
        audit_module.log("api_user", "create_backup", "backup", record.backup_id, {"source": source_path})
        return APIResponse(code=201, data=record.backup_id, message="备份已创建")
    except Exception as e:
        return APIResponse(code=400, message=f"创建备份失败: {str(e)}")


@storage_router.get("/backups", response_model=APIResponse)
async def list_backups():
    backups = storage_module.backup_manager.list_backups()
    return APIResponse(
        code=200,
        data={
            "count": len(backups),
            "backups": [
                {
                    "backup_id": b.backup_id,
                    "name": b.name,
                    "status": b.status,
                    "size_bytes": b.size_bytes,
                    "file_count": b.file_count,
                    "created_at": b.created_at.isoformat()
                }
                for b in backups
            ]
        }
    )


@storage_router.post("/backups/{backup_id}/restore", response_model=APIResponse)
async def restore_backup(backup_id: str, target_path: str, overwrite: bool = False):
    try:
        task = await storage_module.create_and_execute_recovery(backup_id, target_path, overwrite)
        audit_module.log("api_user", "restore_backup", "backup", backup_id, {"target": target_path})
        return APIResponse(
            code=200,
            data={
                "recovery_id": task.recovery_id,
                "status": task.status,
                "restored_files": task.restored_files
            }
        )
    except Exception as e:
        return APIResponse(code=400, message=f"恢复备份失败: {str(e)}")


@storage_router.get("/backups/{backup_id}/verify", response_model=APIResponse)
async def verify_backup(backup_id: str):
    is_valid = await storage_module.backup_manager.verify_backup_integrity(backup_id)
    return APIResponse(code=200, data={"valid": is_valid})


classification_router = APIRouter(prefix="/api/v1/classification", tags=["classification"])


class ClassificationRequest(BaseModel):
    field_name: str
    value: Any


class DatasetClassificationRequest(BaseModel):
    dataset: List[Dict[str, Any]]
    sample_rate: float = 1.0


@classification_router.post("/classify", response_model=APIResponse)
async def classify_value(request: ClassificationRequest):
    result = classification_module.classify_and_evaluate(request.field_name, request.value)
    audit_module.log("api_user", "classify_value", "data", None, {"field": request.field_name})
    return APIResponse(code=200, data=result)


@classification_router.post("/classify/dataset", response_model=APIResponse)
async def classify_dataset(request: DatasetClassificationRequest):
    result = classification_module.classify_dataset(request.dataset, request.sample_rate)
    summary = classification_module.get_data_summary(request.dataset)
    audit_module.log(
        "api_user",
        "classify_dataset",
        "data",
        None,
        {"records": len(request.dataset), "fields": len(result)}
    )
    return APIResponse(code=200, data={"classifications": result, "summary": summary})


@classification_router.get("/rules", response_model=APIResponse)
async def list_rules():
    rules = classification_module.scanner.list_rules()
    return APIResponse(
        code=200,
        data={
            "rules": [
                {
                    "rule_id": r.rule_id,
                    "name": r.name,
                    "category": r.category,
                    "sensitivity": r.sensitivity,
                    "priority": r.priority
                }
                for r in rules
            ]
        }
    )


@classification_router.get("/policies", response_model=APIResponse)
async def list_policies():
    policies = classification_module.policy_engine.list_policies()
    return APIResponse(
        code=200,
        data={
            "policies": [
                {
                    "policy_id": p.policy_id,
                    "name": p.name,
                    "sensitivity_level": p.sensitivity_level,
                    "actions": p.actions,
                    "enabled": p.enabled
                }
                for p in policies
            ]
        }
    )


dp_router = APIRouter(prefix="/api/v1/privacy", tags=["differential_privacy"])


class QueryRequest(BaseModel):
    data: List[float]
    budget_id: str
    epsilon: float = 1.0
    lower: float = 0.0
    upper: float = 100.0


class CustomQueryRequest(BaseModel):
    result: Any
    budget_id: str
    sensitivity: float = 1.0
    epsilon: float = 1.0
    delta: float = 1e-5


@dp_router.post("/budgets", response_model=APIResponse)
async def create_budget(epsilon: float = 1.0, delta: float = 1e-5, budget_id: Optional[str] = None):
    budget = dp_module.budget_manager.create_budget(budget_id, epsilon, delta)
    audit_module.log("api_user", "create_budget", "privacy_budget", budget.budget_id)
    return APIResponse(code=201, data={"budget_id": budget.budget_id, "epsilon": budget.epsilon})


@dp_router.get("/budgets", response_model=APIResponse)
async def list_budgets():
    budgets = dp_module.budget_manager.list_budgets()
    global_status = dp_module.budget_manager.get_global_status()
    return APIResponse(code=200, data={"global": global_status, "budgets": budgets})


@dp_router.post("/query/count", response_model=APIResponse)
async def private_count(request: QueryRequest):
    result = dp_module.private_count(request.data, request.budget_id, request.epsilon)
    if result is None:
        return APIResponse(code=400, message="隐私预算不足")
    audit_module.log("api_user", "private_count", "privacy", None, {"budget_id": request.budget_id})
    return APIResponse(code=200, data={"true_count": len(request.data), "noisy_count": result})


@dp_router.post("/query/sum", response_model=APIResponse)
async def private_sum(request: QueryRequest):
    result = dp_module.private_sum(request.data, request.budget_id, request.lower, request.upper, request.epsilon)
    if result is None:
        return APIResponse(code=400, message="隐私预算不足")
    audit_module.log("api_user", "private_sum", "privacy", None, {"budget_id": request.budget_id})
    return APIResponse(code=200, data={"true_sum": sum(request.data), "noisy_sum": result})


@dp_router.post("/query/mean", response_model=APIResponse)
async def private_mean(request: QueryRequest):
    result = dp_module.private_mean(request.data, request.budget_id, request.lower, request.upper, request.epsilon)
    if result is None:
        return APIResponse(code=400, message="隐私预算不足")
    audit_module.log("api_user", "private_mean", "privacy", None, {"budget_id": request.budget_id})
    return APIResponse(code=200, data={
        "true_mean": sum(request.data) / len(request.data) if request.data else 0,
        "noisy_mean": result
    })


@dp_router.post("/query/custom", response_model=APIResponse)
async def private_custom_query(request: CustomQueryRequest):
    result = dp_module.private_query(
        request.result, request.budget_id, request.sensitivity,
        request.epsilon, request.delta
    )
    if result is None:
        return APIResponse(code=400, message="隐私预算不足")
    audit_module.log("api_user", "private_custom", "privacy", None, {"budget_id": request.budget_id})
    return APIResponse(code=200, data={"original": request.result, "noisy": result})


@dp_router.post("/budgets/{budget_id}/reset", response_model=APIResponse)
async def reset_budget(budget_id: str):
    success = dp_module.budget_manager.reset_budget(budget_id)
    if success:
        audit_module.log("api_user", "reset_budget", "privacy_budget", budget_id)
        return APIResponse(code=200, message="预算已重置")
    return APIResponse(code=404, message="预算不存在")


@dp_router.get("/report", response_model=APIResponse)
async def get_privacy_report(budget_id: Optional[str] = None):
    report = dp_module.get_privacy_report(budget_id)
    return APIResponse(code=200, data=report)


audit_router = APIRouter(prefix="/api/v1/audit", tags=["audit"])


@audit_router.get("/logs", response_model=APIResponse)
async def get_audit_logs(action: Optional[str] = None,
                          resource_type: Optional[str] = None,
                          resource_id: Optional[str] = None,
                          limit: int = 100):
    logs = audit_module.query_logs(action, resource_type, resource_id, limit=limit)
    return APIResponse(
        code=200,
        data={
            "count": len(logs),
            "logs": [
                {
                    "log_id": l.log_id,
                    "timestamp": l.timestamp.isoformat(),
                    "actor": l.actor,
                    "action": l.action,
                    "resource_type": l.resource_type,
                    "resource_id": l.resource_id,
                    "details": l.details,
                    "status": l.status,
                    "hash": l.current_hash
                }
                for l in logs
            ]
        }
    )


@audit_router.get("/logs/{log_id}/proof", response_model=APIResponse)
async def get_log_proof(log_id: str):
    proof = audit_module.get_log_proof(log_id)
    if proof:
        return APIResponse(code=200, data=proof)
    return APIResponse(code=404, message="日志不存在")


@audit_router.get("/integrity", response_model=APIResponse)
async def verify_integrity():
    result = audit_module.verify_integrity()
    audit_module.log("api_user", "verify_integrity", "audit_chain")
    return APIResponse(code=200, data=result)


@audit_router.get("/chain-info", response_model=APIResponse)
async def get_chain_info():
    info = audit_module.get_chain_info()
    return APIResponse(code=200, data=info)


notification_router = APIRouter(prefix="/api/v1/notifications", tags=["notifications"])


class NotificationRequest(BaseModel):
    channel: NotificationChannel
    recipients: List[str]
    template_id: Optional[str] = None
    subject: Optional[str] = None
    body: Optional[str] = None
    context: Dict[str, Any] = {}


@notification_router.post("/send", response_model=APIResponse)
async def send_notification(request: NotificationRequest):
    try:
        if request.template_id:
            results = await notification_module.notify(
                channel=request.channel,
                recipients=request.recipients,
                template_id=request.template_id,
                context=request.context
            )
        else:
            if not request.subject or not request.body:
                return APIResponse(code=400, message="需要提供模板ID或主题和正文")
            results = await notification_module.notify_with_custom_content(
                channel=request.channel,
                recipients=request.recipients,
                subject=request.subject,
                body=request.body,
                context=request.context
            )

        audit_module.log(
            "api_user",
            "send_notification",
            "notification",
            None,
            {"channel": request.channel, "recipients_count": len(request.recipients)}
        )

        return APIResponse(
            code=200,
            data={
                "sent_count": sum(1 for r in results if r.status == "sent"),
                "failed_count": sum(1 for r in results if r.status == "failed"),
                "results": [
                    {
                        "recipient": r.recipient,
                        "status": r.status,
                        "error": r.error_message
                    }
                    for r in results
                ]
            }
        )
    except Exception as e:
        return APIResponse(code=400, message=str(e))


@notification_router.get("/templates", response_model=APIResponse)
async def list_templates():
    templates = notification_module.renderer.list_templates()
    return APIResponse(
        code=200,
        data={
            "templates": [
                {
                    "template_id": t.template_id,
                    "name": t.name,
                    "channel": t.channel,
                    "variables": t.variables
                }
                for t in templates
            ]
        }
    )


@notification_router.get("/stats", response_model=APIResponse)
async def get_notification_stats():
    stats = notification_module.notifier.get_statistics()
    return APIResponse(code=200, data=stats)


mpc_router = APIRouter(prefix="/api/v1/mpc", tags=["mpc"])


class MPCRequest(BaseModel):
    operation: str
    participant_inputs: Dict[str, Any]
    protocol: MPCProtocol = MPCProtocol.SECRET_SHARING


@mpc_router.post("/compute", response_model=APIResponse)
async def run_mpc_computation(request: MPCRequest):
    try:
        if request.operation == "sum":
            result = await mpc_module.run_secure_sum(request.participant_inputs, request.protocol)
        elif request.operation == "average":
            result = await mpc_module.run_secure_average(request.participant_inputs, request.protocol)
        else:
            return APIResponse(code=400, message=f"不支持的操作: {request.operation}")

        audit_module.log(
            "api_user",
            "mpc_compute",
            "mpc",
            result.get("session_id"),
            {"operation": request.operation, "participants": len(request.participant_inputs)}
        )

        return APIResponse(code=200, data=result)
    except Exception as e:
        return APIResponse(code=400, message=str(e))


@mpc_router.post("/sessions", response_model=APIResponse)
async def create_mpc_session(protocol: MPCProtocol, operation: str, participant_ids: str):
    participants = participant_ids.split(",")
    session = mpc_module.coordinator.create_session(protocol, operation, participants)
    audit_module.log(
        "api_user",
        "create_mpc_session",
        "mpc",
        session.session_id,
        {"participants": len(participants)}
    )
    return APIResponse(code=201, data={"session_id": session.session_id})


@mpc_router.post("/sessions/{session_id}/input", response_model=APIResponse)
async def submit_mpc_input(session_id: str, participant_id: str, value: float):
    success = await mpc_module.coordinator.submit_input(session_id, participant_id, value)
    if success:
        return APIResponse(code=200, message="输入已提交")
    return APIResponse(code=400, message="提交失败")


@mpc_router.post("/sessions/{session_id}/execute", response_model=APIResponse)
async def execute_mpc_session(session_id: str):
    result = await mpc_module.coordinator.execute_computation(session_id)
    audit_module.log("api_user", "execute_mpc_session", "mpc", session_id)
    return APIResponse(code=200, data={"result": result})


@mpc_router.get("/sessions/{session_id}", response_model=APIResponse)
async def get_mpc_session_status(session_id: str):
    status = mpc_module.coordinator.get_session_status(session_id)
    if status:
        return APIResponse(code=200, data=status)
    return APIResponse(code=404, message="会话不存在")


@mpc_router.get("/sessions", response_model=APIResponse)
async def list_mpc_sessions():
    sessions = mpc_module.coordinator.list_sessions()
    return APIResponse(
        code=200,
        data={
            "count": len(sessions),
            "sessions": [
                {
                    "session_id": s.session_id,
                    "protocol": s.protocol,
                    "phase": s.phase,
                    "operation": s.operation,
                    "participants_count": len(s.participants),
                    "created_at": s.created_at.isoformat()
                }
                for s in sessions
            ]
        }
    )


migration_router = APIRouter(prefix="/api/v1/migrations", tags=["migrations"])


@migration_router.post("/schema/versions", response_model=APIResponse)
async def create_schema_version(definition: Dict[str, Any], description: str = ""):
    version = data_access_module.schema_controller.create_version(definition, description)
    audit_module.log(
        "api_user",
        "create_schema_version",
        "schema",
        None,
        {"version": version.version}
    )
    return APIResponse(code=201, data={"version": version.version, "hash": version.hash})


@migration_router.get("/schema/versions", response_model=APIResponse)
async def list_schema_versions():
    versions = data_access_module.schema_controller.list_versions()
    return APIResponse(
        code=200,
        data={
            "current_version": data_access_module.schema_controller.get_current_version().version,
            "versions": [
                {
                    "version": v.version,
                    "hash": v.hash,
                    "created_at": v.created_at.isoformat(),
                    "description": v.description
                }
                for v in versions
            ]
        }
    )


@migration_router.get("/schema/versions/{version}", response_model=APIResponse)
async def get_schema_version(version: int):
    schema = data_access_module.schema_controller.get_version(version)
    if schema:
        return APIResponse(
            code=200,
            data={
                "version": schema.version,
                "hash": schema.hash,
                "definition": schema.definition,
                "created_at": schema.created_at.isoformat(),
                "description": schema.description
            }
        )
    return APIResponse(code=404, message="版本不存在")


@migration_router.post("/tasks", response_model=APIResponse)
async def create_migration_task(source: str, target: str, table_name: str,
                                 total_records: int = 0, batch_size: int = 1000):
    task = data_access_module.migration_service.create_migration_task(
        source, target, table_name, total_records, batch_size
    )
    audit_module.log(
        "api_user",
        "create_migration_task",
        "migration",
        task.task_id,
        {"table": table_name}
    )
    return APIResponse(code=201, data={"task_id": task.task_id, "table_name": table_name})


@migration_router.get("/tasks", response_model=APIResponse)
async def list_migration_tasks():
    tasks = data_access_module.migration_service.list_tasks()
    return APIResponse(
        code=200,
        data={
            "count": len(tasks),
            "tasks": [
                {
                    "task_id": t.task_id,
                    "table_name": t.table_name,
                    "status": t.status,
                    "records_processed": t.records_processed,
                    "total_records": t.total_records
                }
                for t in tasks
            ]
        }
    )


@migration_router.get("/tasks/{task_id}", response_model=APIResponse)
async def get_migration_task_status(task_id: str):
    task = data_access_module.migration_service.get_task_status(task_id)
    if task:
        return APIResponse(
            code=200,
            data={
                "task_id": task.task_id,
                "source": task.source,
                "target": task.target,
                "table_name": task.table_name,
                "status": task.status,
                "records_processed": task.records_processed,
                "total_records": task.total_records,
                "progress": task.records_processed / max(task.total_records, 1)
            }
        )
    return APIResponse(code=404, message="任务不存在")


health_router = APIRouter(prefix="/api/v1", tags=["health"])


@health_router.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "service": "privacy_compute_service",
        "version": "1.0.0",
        "timestamp": "2026-05-13T08:00:00Z"
    }


@health_router.get("/info")
async def service_info():
    return {
        "name": "隐私计算与数据安全服务",
        "description": "实现数据迁移与Schema版本控制、存储管理、数据分类分级、差分隐私、审计日志等功能",
        "modules": [
            "data_access",
            "storage",
            "classification",
            "core_processor",
            "differential_privacy",
            "config_manager",
            "audit",
            "notification",
            "mpc"
        ],
        "api_version": "v1"
    }
