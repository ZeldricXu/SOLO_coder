from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel

from src.common.models import APIResponse
from src.config.manager import ConfigurationManager
from src.config.models import ConfigValidationRule, ConfigSourceType, ConfigValueType
from src.config.sources import EnvironmentSource, FileSource, HTTPSource, RedisSource

router = APIRouter(prefix="/config", tags=["Configuration"])

_config_manager: Optional[ConfigurationManager] = None


def get_config_manager() -> ConfigurationManager:
    global _config_manager
    if _config_manager is None:
        _config_manager = ConfigurationManager()
        _config_manager.add_source(EnvironmentSource(prefix="APP_"))
    return _config_manager


class SetConfigRequest(BaseModel):
    key: str
    value: Any


class AddSourceRequest(BaseModel):
    type: ConfigSourceType
    config: Dict[str, Any]


class ValidateResponse(BaseModel):
    valid: bool
    errors: List[str]


@router.get("")
async def get_all_config(
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    return APIResponse(data=manager.get_all())


@router.get("/{key:path}")
async def get_config(
    key: str,
    default: Optional[str] = None,
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    if not manager.exists(key) and default is None:
        raise HTTPException(status_code=404, detail=f"Config '{key}' not found")
    return APIResponse(data={"key": key, "value": manager.get(key, default)})


@router.post("", status_code=201)
async def set_config(
    request: SetConfigRequest,
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    manager.set(request.key, request.value)
    return APIResponse(code=201, data={"key": request.key, "value": request.value})


@router.delete("/{key:path}")
async def delete_config(
    key: str,
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    deleted = manager.delete(key)
    if not deleted:
        raise HTTPException(status_code=404, detail=f"Config '{key}' not found")
    return APIResponse(data={"key": key, "deleted": True})


@router.get("/prefix/{prefix}")
async def get_config_by_prefix(
    prefix: str,
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    return APIResponse(data=manager.get_with_prefix(prefix))


@router.post("/refresh")
async def refresh_config(
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    config = await manager.refresh()
    return APIResponse(data={"refreshed": True, "count": len(config)})


@router.get("/snapshots")
async def list_snapshots(
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    return APIResponse(data=[s.model_dump() for s in manager._snapshots])


@router.post("/snapshots", status_code=201)
async def create_snapshot(
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    snapshot = manager.snapshot()
    return APIResponse(code=201, data={"snapshot_id": snapshot.snapshot_id})


@router.post("/snapshots/{snapshot_id}/rollback")
async def rollback_snapshot(
    snapshot_id: str,
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    success = manager.rollback(snapshot_id)
    if not success:
        raise HTTPException(status_code=404, detail="Snapshot not found")
    return APIResponse(data={"rolled_back": True, "snapshot_id": snapshot_id})


@router.post("/sources", status_code=201)
async def add_config_source(
    request: AddSourceRequest,
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    from src.config.sources import ConfigSource
    source: Optional[ConfigSource] = None
    if request.type == ConfigSourceType.ENVIRONMENT:
        source = EnvironmentSource(prefix=request.config.get("prefix", ""), priority=request.config.get("priority", 100))
    elif request.type == ConfigSourceType.FILE:
        source = FileSource(file_path=request.config["file_path"], priority=request.config.get("priority", 50))
    elif request.type == ConfigSourceType.HTTP:
        source = HTTPSource(url=request.config["url"], headers=request.config.get("headers"), priority=request.config.get("priority", 30))
    elif request.type == ConfigSourceType.REDIS:
        source = RedisSource(redis_url=request.config["redis_url"], key_prefix=request.config.get("key_prefix", "config:"), priority=request.config.get("priority", 20))
    if source is None:
        raise HTTPException(status_code=400, detail=f"Unsupported source type: {request.type}")
    manager.add_source(source)
    await manager.load()
    return APIResponse(code=201, data={"type": request.type.value, "added": True})


@router.get("/sources")
async def list_sources(
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    return APIResponse(data=[
        {"type": s.source_type.value, "priority": s.priority}
        for s in manager._sources
    ])


@router.post("/validation/rules", status_code=201)
async def add_validation_rule(
    rule: ConfigValidationRule,
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    manager.add_validation_rule(rule)
    return APIResponse(code=201, data={"rule_id": rule.rule_id})


@router.post("/validate")
async def validate_config(
    manager: ConfigurationManager = Depends(get_config_manager),
) -> APIResponse:
    errors = manager.validate()
    return APIResponse(data={
        "valid": len(errors) == 0,
        "errors": errors,
    })
