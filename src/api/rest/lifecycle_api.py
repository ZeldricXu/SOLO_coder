from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Dict, List, Optional
from datetime import datetime

router = APIRouter()


class TieringEvaluateRequest(BaseModel):
    database_name: str
    table_name: str
    table_stats: Dict[str, Any]


class ArchiveRequest(BaseModel):
    database_name: str
    table_name: str
    records: List[Dict[str, Any]]
    cutoff_date: Optional[str] = None
    target_tier: str = "cold"


class MigrateRequest(BaseModel):
    database_name: str
    table_name: str
    date_str: Optional[str] = None


class CleanupRequest(BaseModel):
    retention_days: Optional[int] = None


class AddPolicyRequest(BaseModel):
    source_tier: str
    target_tier: str
    age_days: int
    priority: int = 0


_lifecycle_service = None


def _get_service():
    global _lifecycle_service
    if _lifecycle_service is None:
        from src.infrastructure.config.settings import get_settings
        from src.infrastructure.storage.cold_storage import ColdStorage
        from src.infrastructure.storage.archive_storage import ArchiveStorage
        from src.service.lifecycle_service import LifecycleService

        settings = get_settings()
        cold_storage = ColdStorage(settings.storage.cold)
        archive_storage = ArchiveStorage(settings.storage.archive)
        _lifecycle_service = LifecycleService(settings.lifecycle, cold_storage, archive_storage)
    return _lifecycle_service


@router.post("/tiering/evaluate")
async def evaluate_tiering(request: TieringEvaluateRequest):
    service = _get_service()
    return {"actions": service.evaluate_tiering(request.database_name, request.table_name, request.table_stats)}


@router.post("/tiering/execute")
async def execute_tiering(request: TieringEvaluateRequest):
    service = _get_service()
    return {"results": service.execute_tiering(request.database_name, request.table_name, request.table_stats)}


@router.post("/archive")
async def archive_data(request: ArchiveRequest):
    service = _get_service()
    cutoff = datetime.fromisoformat(request.cutoff_date) if request.cutoff_date else None
    return service.archive_data(request.database_name, request.table_name, request.records, cutoff, request.target_tier)


@router.post("/migrate/cold-to-archive")
async def migrate_cold_to_archive(request: MigrateRequest):
    service = _get_service()
    return service.migrate_cold_to_archive(request.database_name, request.table_name, request.date_str)


@router.post("/cleanup")
async def cleanup_expired(request: CleanupRequest):
    service = _get_service()
    return service.cleanup_expired(request.retention_days)


@router.get("/policies/tiering")
async def get_tiering_policies():
    service = _get_service()
    return {"policies": service.get_tiering_policies()}


@router.post("/policies/tiering")
async def add_tiering_policy(request: AddPolicyRequest):
    service = _get_service()
    service.add_tiering_policy(request.source_tier, request.target_tier, request.age_days, request.priority)
    return {"status": "added"}


@router.get("/policies/cleanup")
async def get_cleanup_policies():
    service = _get_service()
    return {"policies": service.get_cleanup_policies()}
