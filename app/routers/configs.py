from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional, List
from app.database import get_async_db
from app.modules.config_manager import ConfigManager
from app.modules.api_gateway import get_current_user, Permission, require_permission
from app.schemas import ConfigCreate, ConfigUpdate, ConfigRollback, APIResponse, CacheInvalidateRequest
from app.logger import logger

router = APIRouter(prefix="/api/v1/configs", tags=["Configuration"])


@router.post("", response_model=APIResponse)
async def create_config(
    data: ConfigCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = ConfigManager(db)
    config = await manager.create_config(
        config_id=data.config_id,
        namespace=data.namespace,
        parameters=data.parameters,
        enabled=data.enabled
    )
    await db.commit()
    
    return APIResponse(
        code=201,
        data={
            "config_id": config.config_id,
            "namespace": config.namespace,
            "version": config.version,
            "parameters": config.parameters,
            "enabled": config.enabled
        }
    )


@router.get("/{config_id}", response_model=APIResponse)
async def get_config(
    config_id: str,
    namespace: str = "default",
    version: int = None,
    use_cache: bool = Query(True, description="Use cache if available"),
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = ConfigManager(db)
    config = await manager.get_config(config_id, namespace, version, use_cache=use_cache)
    
    if not config:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Config not found"
        )
    
    return APIResponse(
        code=200,
        data={
            "config_id": config.config_id,
            "namespace": config.namespace,
            "version": config.version,
            "parameters": config.parameters,
            "enabled": config.enabled,
            "applied_at": config.applied_at.isoformat() if config.applied_at else None
        }
    )


@router.post("/bulk", response_model=APIResponse)
async def get_config_bulk(
    config_ids: List[str],
    namespace: str = "default",
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = ConfigManager(db)
    results = await manager.get_config_bulk(config_ids, namespace)
    
    return APIResponse(code=200, data=results)


@router.put("/{config_id}", response_model=APIResponse)
async def update_config(
    config_id: str,
    data: ConfigUpdate,
    namespace: str = "default",
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = ConfigManager(db)
    config = await manager.update_config(config_id, namespace, data.parameters)
    
    if not config:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Config not found"
        )
    
    await db.commit()
    
    return APIResponse(
        code=200,
        data={
            "config_id": config.config_id,
            "namespace": config.namespace,
            "version": config.version,
            "parameters": config.parameters,
            "enabled": config.enabled
        }
    )


@router.post("/{config_id}/rollback", response_model=APIResponse)
async def rollback_config(
    config_id: str,
    data: ConfigRollback,
    namespace: str = "default",
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = ConfigManager(db)
    config = await manager.rollback_config(config_id, namespace, data.target_version)
    
    if not config:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Config not found or target version invalid"
        )
    
    await db.commit()
    
    return APIResponse(
        code=200,
        data={
            "config_id": config.config_id,
            "namespace": config.namespace,
            "version": config.version,
            "target_version": data.target_version,
            "parameters": config.parameters
        }
    )


@router.get("", response_model=APIResponse)
async def list_configs(
    namespace: str = None,
    limit: int = 100,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = ConfigManager(db)
    configs = await manager.list_configs(namespace, limit)
    
    return APIResponse(code=200, data=configs)


@router.get("/{config_id}/history", response_model=APIResponse)
async def get_config_history(
    config_id: str,
    namespace: str = "default",
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = ConfigManager(db)
    history = await manager.get_config_history(config_id, namespace)
    
    return APIResponse(code=200, data=history)


@router.post("/{config_id}/disable", response_model=APIResponse)
async def disable_config(
    config_id: str,
    namespace: str = "default",
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = ConfigManager(db)
    config = await manager.disable_config(config_id, namespace)
    
    if not config:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Config not found"
        )
    
    await db.commit()
    
    return APIResponse(
        code=200,
        data={"config_id": config_id, "enabled": False}
    )


@router.post("/{config_id}/enable", response_model=APIResponse)
async def enable_config(
    config_id: str,
    namespace: str = "default",
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = ConfigManager(db)
    config = await manager.enable_config(config_id, namespace)
    
    if not config:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Config not found"
        )
    
    await db.commit()
    
    return APIResponse(
        code=200,
        data={"config_id": config_id, "enabled": True}
    )


@router.get("/cache/metrics", response_model=APIResponse)
async def get_cache_metrics(
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    manager = ConfigManager(db)
    metrics = manager.get_cache_metrics()
    
    return APIResponse(code=200, data=metrics)


@router.post("/cache/invalidate", response_model=APIResponse)
async def invalidate_cache(
    data: CacheInvalidateRequest,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = ConfigManager(db)
    metrics = await manager.invalidate_cache(
        config_id=data.config_id,
        namespace=data.namespace,
        version=data.version
    )
    
    return APIResponse(code=200, data=metrics)


@router.post("/cache/reset-metrics", response_model=APIResponse)
async def reset_cache_metrics(
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.ADMIN))
):
    manager = ConfigManager(db)
    manager.reset_cache_metrics()
    metrics = manager.get_cache_metrics()
    
    return APIResponse(code=200, data=metrics)
