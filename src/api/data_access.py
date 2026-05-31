from fastapi import APIRouter, Depends, Header, HTTPException
from typing import Optional, List
from src.core import ApiResponse, get_trace_id
from src.modules.data_access import (
    SchemaVersion,
    SchemaStatus,
    MigrationDefinition,
    DataSourceConfig,
    DataTransferRequest,
)
from src.di import DIContainer, get_container

router = APIRouter(prefix="/api/v1/data", tags=["Data Access"])


@router.post("/schemas", response_model=ApiResponse)
async def register_schema(
    schema: SchemaVersion,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.register_schema(schema, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/schemas", response_model=ApiResponse)
async def list_schemas(
    status: Optional[SchemaStatus] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.list_schemas(status, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/schemas/{name}", response_model=ApiResponse)
async def get_schema(
    name: str,
    version: Optional[int] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.get_schema(name, version, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.get("/schemas/{name}/version", response_model=ApiResponse)
async def get_current_version(
    name: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.get_current_version(name, trace_id or get_trace_id())
    return ApiResponse.success({"schema": name, "current_version": result})


@router.post("/migrations", response_model=ApiResponse)
async def create_migration(
    migration: MigrationDefinition,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.create_migration(migration, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.get("/migrations", response_model=ApiResponse)
async def list_migrations(
    schema_name: Optional[str] = None,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.list_migrations(schema_name, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/migrations/{migration_id}/execute", response_model=ApiResponse)
async def execute_migration(
    migration_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.execute_migration(migration_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/migrations/rollback/{execution_id}", response_model=ApiResponse)
async def rollback_migration(
    execution_id: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.rollback_migration(execution_id, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/sources", response_model=ApiResponse)
async def add_data_source(
    config: DataSourceConfig,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.add_data_source(config, trace_id or get_trace_id())
    return ApiResponse.created(result)


@router.post("/transfer", response_model=ApiResponse)
async def transfer_data(
    request: DataTransferRequest,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.transfer_data(request, trace_id or get_trace_id())
    return ApiResponse.success(result)


@router.post("/validate", response_model=ApiResponse)
async def validate_data(
    source_id: str,
    table_name: str,
    schema_name: str,
    trace_id: Optional[str] = Header(None),
    container: DIContainer = Depends(get_container),
):
    result = await container.data_access.validate_data(
        source_id, table_name, schema_name, trace_id or get_trace_id()
    )
    return ApiResponse.success(result)
