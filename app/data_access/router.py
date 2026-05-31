from fastapi import APIRouter, Depends, Query, Body
from uuid import UUID
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional, Dict, Any

from app.database import get_db
from app.schemas import (
    SchemaVersionCreate,
    SchemaVersionResponse,
    DataMigrationCreate,
    DataMigrationResponse,
    MigrationExecuteRequest,
    BaseResponse,
    PaginatedResponse,
)
from app.data_access.service import DataAccessService, SchemaVersionManager
from app.logging import LogContext

router = APIRouter(prefix="/api/v1/data-access", tags=["Data Access"])


@router.post("/schemas", response_model=BaseResponse[SchemaVersionResponse])
async def create_schema_version(
    schema_in: SchemaVersionCreate,
    db: AsyncSession = Depends(get_db),
):
    manager = SchemaVersionManager(db)
    schema = await manager.create_schema_version(schema_in)
    return BaseResponse(
        code=201,
        data=schema,
        request_id=LogContext.get_request_id(),
        message="Schema version created successfully",
    )


@router.get("/schemas/{version_id}", response_model=BaseResponse[SchemaVersionResponse])
async def get_schema_version(
    version_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    manager = SchemaVersionManager(db)
    schema = await manager.get_schema_version(version_id)
    return BaseResponse(data=schema, request_id=LogContext.get_request_id())


@router.get("/schemas/current/{schema_name}", response_model=BaseResponse[SchemaVersionResponse])
async def get_current_schema(
    schema_name: str,
    db: AsyncSession = Depends(get_db),
):
    manager = SchemaVersionManager(db)
    schema = await manager.get_current_schema(schema_name)
    return BaseResponse(data=schema, request_id=LogContext.get_request_id())


@router.get("/schemas", response_model=BaseResponse[PaginatedResponse[SchemaVersionResponse]])
async def list_schema_versions(
    schema_name: Optional[str] = Query(None, description="Filter by schema name"),
    only_current: bool = Query(False, description="Only show current versions"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=100, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    manager = SchemaVersionManager(db)
    skip = (page - 1) * page_size
    schemas, total = await manager.list_schema_versions(
        schema_name=schema_name,
        only_current=only_current,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=schemas,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )


@router.post("/schemas/{schema_name}/validate", response_model=BaseResponse[dict])
async def validate_data(
    schema_name: str,
    data: Dict[str, Any] = Body(..., description="Data to validate"),
    version: Optional[int] = Query(None, description="Schema version"),
    db: AsyncSession = Depends(get_db),
):
    manager = SchemaVersionManager(db)
    valid, errors = await manager.validate_data_against_schema(
        data=data,
        schema_name=schema_name,
        version=version,
    )
    return BaseResponse(
        data={"valid": valid, "errors": errors},
        request_id=LogContext.get_request_id(),
    )


@router.get("/schemas/{schema_name}/diff", response_model=BaseResponse[dict])
async def diff_schemas(
    schema_name: str,
    version_a: int = Query(..., description="First version"),
    version_b: int = Query(..., description="Second version"),
    db: AsyncSession = Depends(get_db),
):
    manager = SchemaVersionManager(db)
    diff = await manager.diff_schemas(schema_name, version_a, version_b)
    return BaseResponse(data=diff, request_id=LogContext.get_request_id())


@router.post("/migrations", response_model=BaseResponse[DataMigrationResponse])
async def create_migration(
    migration_in: DataMigrationCreate,
    db: AsyncSession = Depends(get_db),
):
    service = DataAccessService(db)
    migration = await service.create_migration(migration_in)
    return BaseResponse(
        code=201,
        data=migration,
        request_id=LogContext.get_request_id(),
        message="Data migration created successfully",
    )


@router.get("/migrations/{migration_id}", response_model=BaseResponse[DataMigrationResponse])
async def get_migration(
    migration_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = DataAccessService(db)
    migration = await service.get_migration(migration_id)
    return BaseResponse(data=migration, request_id=LogContext.get_request_id())


@router.get("/migrations", response_model=BaseResponse[PaginatedResponse[DataMigrationResponse]])
async def list_migrations(
    status: Optional[str] = Query(None, description="Filter by status"),
    schema_name: Optional[str] = Query(None, description="Filter by schema name"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=100, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    service = DataAccessService(db)
    skip = (page - 1) * page_size
    migrations, total = await service.list_migrations(
        status=status,
        schema_name=schema_name,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=migrations,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )


@router.post("/migrations/execute", response_model=BaseResponse[DataMigrationResponse])
async def execute_migration(
    request: MigrationExecuteRequest,
    db: AsyncSession = Depends(get_db),
):
    service = DataAccessService(db)
    migration = await service.execute_migration(request)
    return BaseResponse(
        data=migration,
        request_id=LogContext.get_request_id(),
        message="Migration executed successfully",
    )


@router.get("/migrations/{migration_id}/status", response_model=BaseResponse[dict])
async def get_migration_status(
    migration_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = DataAccessService(db)
    status = await service.get_migration_status(migration_id)
    return BaseResponse(data=status, request_id=LogContext.get_request_id())
