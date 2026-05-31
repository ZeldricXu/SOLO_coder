from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Any, Dict, List, Optional

router = APIRouter()
_metadata_service = None


def _get_service():
    global _metadata_service
    if _metadata_service is None:
        from src.infrastructure.config.settings import get_settings
        from src.infrastructure.db.connection_pool import ConnectionPool
        from src.service.metadata_service import MetadataService

        settings = get_settings()
        pool = ConnectionPool.get_pool("metastore", settings.metastore)
        _metadata_service = MetadataService(pool)
    return _metadata_service


class ScanRequest(BaseModel):
    database_name: str
    schema_name: str = "public"


class SchemaRequest(BaseModel):
    database_name: str
    table_name: str
    schema_name: str = "public"


class StatsRequest(BaseModel):
    database_name: str
    table_name: str
    schema_name: str = "public"


class SampleRequest(BaseModel):
    database_name: str
    table_name: str
    schema_name: str = "public"
    limit: Optional[int] = None
    method: Optional[str] = None


class FullScanRequest(BaseModel):
    database_name: str
    schema_name: str = "public"


@router.post("/scan")
async def scan_database(request: ScanRequest):
    service = _get_service()
    return service.scan_database(request.database_name, request.schema_name)


@router.post("/schema")
async def get_table_schema(request: SchemaRequest):
    service = _get_service()
    result = service.get_table_schema(request.database_name, request.table_name, request.schema_name)
    if result is None:
        raise HTTPException(status_code=404, detail="Table not found")
    return result


@router.post("/stats")
async def collect_stats(request: StatsRequest):
    service = _get_service()
    return service.collect_table_stats(request.database_name, request.table_name, request.schema_name)


@router.post("/sample")
async def get_sample_data(request: SampleRequest):
    service = _get_service()
    return service.get_sample_data(request.database_name, request.table_name, request.schema_name, request.limit, request.method)


@router.post("/full-scan")
async def full_scan(request: FullScanRequest):
    service = _get_service()
    return service.full_scan(request.database_name, request.schema_name)


@router.get("/tables")
async def list_tables(database: Optional[str] = None):
    service = _get_service()
    return {"tables": service.list_tables(database)}
