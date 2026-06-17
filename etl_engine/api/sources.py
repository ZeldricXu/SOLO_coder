import uuid
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from etl_engine.connectors import get_source
from etl_engine.db.session import get_session
from etl_engine.models.source import DataSource

router = APIRouter(prefix="/api/sources", tags=["sources"])


class SourceCreate(BaseModel):
    name: str
    type: str
    connection_config: dict[str, Any] | None = None
    pool_size: int = 5


class SourceUpdate(BaseModel):
    name: str | None = None
    connection_config: dict[str, Any] | None = None
    is_active: bool | None = None
    pool_size: int | None = None


class SourceResponse(BaseModel):
    id: uuid.UUID
    name: str
    type: str
    connection_config: dict[str, Any] | None
    is_active: bool
    pool_size: int
    last_connected_at: datetime | None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class SourceTestResult(BaseModel):
    success: bool
    message: str


class SchemaInfo(BaseModel):
    source_type: str
    schema_info: dict[str, Any]


@router.post("", response_model=SourceResponse, status_code=201)
async def create_source(
    body: SourceCreate,
    session: AsyncSession = Depends(get_session),
):
    existing = await session.execute(
        select(DataSource).where(DataSource.name == body.name)
    )
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(status_code=409, detail=f"Source '{body.name}' already exists")

    source = DataSource(
        name=body.name,
        type=body.type,
        connection_config=body.connection_config,
        pool_size=body.pool_size,
    )
    session.add(source)
    await session.commit()
    await session.refresh(source)
    return source


@router.get("", response_model=list[SourceResponse])
async def list_sources(
    type: str | None = Query(None, alias="type"),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(DataSource)
    if type is not None:
        stmt = stmt.where(DataSource.type == type)
    result = await session.execute(stmt.order_by(DataSource.created_at))
    return result.scalars().all()


@router.get("/{source_id}", response_model=SourceResponse)
async def get_source_detail(
    source_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    source = await session.get(DataSource, source_id)
    if source is None:
        raise HTTPException(status_code=404, detail="Source not found")
    return source


@router.put("/{source_id}", response_model=SourceResponse)
async def update_source(
    source_id: uuid.UUID,
    body: SourceUpdate,
    session: AsyncSession = Depends(get_session),
):
    source = await session.get(DataSource, source_id)
    if source is None:
        raise HTTPException(status_code=404, detail="Source not found")

    update_data = body.model_dump(exclude_unset=True)
    if "name" in update_data and update_data["name"] != source.name:
        existing = await session.execute(
            select(DataSource).where(DataSource.name == update_data["name"])
        )
        if existing.scalar_one_or_none() is not None:
            raise HTTPException(status_code=409, detail=f"Source '{update_data['name']}' already exists")

    for field, value in update_data.items():
        setattr(source, field, value)

    await session.commit()
    await session.refresh(source)
    return source


@router.delete("/{source_id}", status_code=204)
async def delete_source(
    source_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    source = await session.get(DataSource, source_id)
    if source is None:
        raise HTTPException(status_code=404, detail="Source not found")
    await session.delete(source)
    await session.commit()


@router.post("/{source_id}/test", response_model=SourceTestResult)
async def test_source_connection(
    source_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    source = await session.get(DataSource, source_id)
    if source is None:
        raise HTTPException(status_code=404, detail="Source not found")

    try:
        connector = get_source(source.type, source.connection_config or {})
        connected = await connector.test_connection()
        if connected:
            from datetime import datetime as dt
            source.last_connected_at = dt.utcnow()
            await session.commit()
        return SourceTestResult(success=connected, message="Connection successful" if connected else "Connection failed")
    except Exception as exc:
        return SourceTestResult(success=False, message=str(exc))


@router.get("/{source_id}/schema", response_model=SchemaInfo)
async def get_source_schema(
    source_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    source = await session.get(DataSource, source_id)
    if source is None:
        raise HTTPException(status_code=404, detail="Source not found")

    try:
        connector = get_source(source.type, source.connection_config or {})
        await connector.connect()

        schema: dict[str, Any] = {}
        match source.type:
            case "postgresql" | "mysql":
                schema = await _get_db_schema(connector)
            case "mongodb":
                schema = await _get_mongo_schema(connector)
            case "s3":
                schema = await _get_s3_schema(connector)
            case "kafka":
                schema = await _get_kafka_schema(connector)
            case _:
                schema = {"info": f"Schema discovery not supported for type '{source.type}'"}

        await connector.disconnect()
        return SchemaInfo(source_type=source.type, schema=schema)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Schema discovery failed: {exc}")


async def _get_db_schema(connector) -> dict[str, Any]:
    tables: dict[str, Any] = {}
    try:
        df = await connector.read(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
        )
        for _, row in df.iterrows():
            table_name = row["table_name"]
            col_df = await connector.read(
                f"SELECT column_name, data_type FROM information_schema.columns "
                f"WHERE table_name = '{table_name}' AND table_schema = 'public'"
            )
            tables[table_name] = {
                "columns": [
                    {"name": r["column_name"], "type": r["data_type"]}
                    for _, r in col_df.iterrows()
                ]
            }
    except Exception:
        pass
    return tables


async def _get_mongo_schema(connector) -> dict[str, Any]:
    return {"collections": "Schema discovery for MongoDB requires custom implementation"}


async def _get_s3_schema(connector) -> dict[str, Any]:
    return {"buckets": "Schema discovery for S3 requires custom implementation"}


async def _get_kafka_schema(connector) -> dict[str, Any]:
    return {"topics": "Schema discovery for Kafka requires custom implementation"}
