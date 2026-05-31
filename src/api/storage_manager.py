from fastapi import APIRouter, Depends, Header, HTTPException
from typing import Optional, List
from src.core import ApiResponse, get_trace_id
from src.modules.storage_manager import (
    BackupPolicy,
    BackupRecord,
    RestoreRequest,
    StorageConfig,
    StorageType,
    BackupStatus,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/storage", tags=["Storage Manager"])


@router.post("/configs", response_model=ApiResponse)
async def add_storage_config(
    config: StorageConfig,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.storage_manager.add_storage_config(config, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.post("/policies", response_model=ApiResponse)
async def create_backup_policy(
    policy: BackupPolicy,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.storage_manager.create_backup_policy(policy, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/policies", response_model=ApiResponse)
async def list_policies(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.storage_manager.list_policies(trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/backups", response_model=ApiResponse)
async def execute_backup(
    policy_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.storage_manager.execute_backup(policy_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/backups", response_model=ApiResponse)
async def list_backups(
    policy_id: Optional[str] = None,
    status: Optional[BackupStatus] = None,
    limit: int = 100,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.storage_manager.list_backups(policy_id, status, limit, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/backups/{backup_id}/verify", response_model=ApiResponse)
async def verify_backup(
    backup_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.storage_manager.verify_backup(backup_id, trace_id or get_trace_id())
    return ApiResponse.success({"valid": result})


@router.post("/restores", response_model=ApiResponse)
async def execute_restore(
    request: RestoreRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.storage_manager.execute_restore(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/restores/{restore_id}", response_model=ApiResponse)
async def get_restore(
    restore_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.storage_manager.get_restore(restore_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/usage", response_model=ApiResponse)
async def get_storage_usage(
    config_id: Optional[str] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.storage_manager.get_storage_usage(config_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/cleanup", response_model=ApiResponse)
async def cleanup_expired_backups(
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    count = await container.storage_manager.cleanup_expired_backups(trace_id or get_trace_id())
    return ApiResponse.success({"deleted_count": count})


@router.get("/types", response_model=ApiResponse)
async def list_storage_types():
    return ApiResponse.success([t.value for t in StorageType])
