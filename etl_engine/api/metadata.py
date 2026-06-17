import uuid
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from etl_engine.db.session import get_session
from etl_engine.models.execution import PipelineExecution
from etl_engine.models.pipeline import Pipeline
from etl_engine.models.source import DataSource
from etl_engine.orchestrator.dag import DAG, DAGDefinition

router = APIRouter(prefix="/api/metadata", tags=["metadata"])


class SourceWithSchema(BaseModel):
    id: uuid.UUID
    name: str
    type: str
    is_active: bool
    connection_config: dict[str, Any] | None
    created_at: datetime

    model_config = {"from_attributes": True}


class TableInfo(BaseModel):
    tables: list[dict[str, Any]]


class PipelineWithDeps(BaseModel):
    id: uuid.UUID
    name: str
    description: str | None
    schedule: str | None
    is_active: bool
    dependencies: dict[str, Any]

    model_config = {"from_attributes": True}


class DependencyGraphDetail(BaseModel):
    pipeline_id: uuid.UUID
    pipeline_name: str
    nodes: list[dict[str, Any]]
    edges: list[dict[str, Any]]
    execution_order: list[list[str]]


class PipelineStatus(BaseModel):
    pipeline_id: uuid.UUID
    pipeline_name: str
    latest_status: str | None
    latest_execution_id: uuid.UUID | None
    last_run_at: datetime | None


class HistoryEntry(BaseModel):
    id: uuid.UUID
    pipeline_id: uuid.UUID
    status: str
    trigger_type: str
    started_at: datetime | None
    finished_at: datetime | None
    error_message: str | None
    created_at: datetime

    model_config = {"from_attributes": True}


class AggregateStats(BaseModel):
    total_pipelines: int
    total_executions: int
    success_rate: float
    avg_duration_seconds: float | None
    quality_pass_rate: float | None


@router.get("/sources", response_model=list[SourceWithSchema])
async def list_sources_with_schema(
    session: AsyncSession = Depends(get_session),
):
    result = await session.execute(
        select(DataSource).order_by(DataSource.created_at)
    )
    return result.scalars().all()


@router.get("/sources/{source_id}/tables", response_model=TableInfo)
async def get_source_tables(
    source_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    source = await session.get(DataSource, source_id)
    if source is None:
        raise HTTPException(status_code=404, detail="Source not found")

    tables: list[dict[str, Any]] = []
    if source.connection_config and "tables" in source.connection_config:
        for table_name, table_info in source.connection_config["tables"].items():
            tables.append({"name": table_name, **table_info})

    return TableInfo(tables=tables)


@router.get("/pipelines", response_model=list[PipelineWithDeps])
async def list_pipelines_with_deps(
    session: AsyncSession = Depends(get_session),
):
    result = await session.execute(select(Pipeline).order_by(Pipeline.created_at))
    pipelines = result.scalars().all()

    response: list[PipelineWithDeps] = []
    for p in pipelines:
        try:
            dag = DAG(DAGDefinition(**p.dag_definition))
            deps = {
                "nodes": [n.id for n in dag.definition.nodes],
                "edges": [{"source": e.source, "target": e.target} for e in dag.definition.edges],
            }
        except Exception:
            deps = {"nodes": [], "edges": []}

        response.append(PipelineWithDeps(
            id=p.id,
            name=p.name,
            description=p.description,
            schedule=p.schedule,
            is_active=p.is_active,
            dependencies=deps,
        ))
    return response


@router.get("/pipelines/{pipeline_id}/graph", response_model=DependencyGraphDetail)
async def get_pipeline_graph(
    pipeline_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    pipeline = await session.get(Pipeline, pipeline_id)
    if pipeline is None:
        raise HTTPException(status_code=404, detail="Pipeline not found")

    dag = DAG(DAGDefinition(**pipeline.dag_definition))

    nodes = [
        {
            "id": n.id,
            "type": n.type,
            "dependencies": n.dependencies,
            "config": n.config,
        }
        for n in dag.definition.nodes
    ]
    edges = [
        {"source": e.source, "target": e.target, "data_mapping": e.data_mapping}
        for e in dag.definition.edges
    ]
    execution_order = dag.get_execution_order()

    return DependencyGraphDetail(
        pipeline_id=pipeline.id,
        pipeline_name=pipeline.name,
        nodes=nodes,
        edges=edges,
        execution_order=execution_order,
    )


@router.get("/status", response_model=list[PipelineStatus])
async def get_all_pipeline_status(
    session: AsyncSession = Depends(get_session),
):
    pipelines_result = await session.execute(select(Pipeline))
    pipelines = pipelines_result.scalars().all()

    statuses: list[PipelineStatus] = []
    for p in pipelines:
        latest_result = await session.execute(
            select(PipelineExecution)
            .where(PipelineExecution.pipeline_id == p.id)
            .order_by(PipelineExecution.created_at.desc())
            .limit(1)
        )
        latest = latest_result.scalar_one_or_none()

        statuses.append(PipelineStatus(
            pipeline_id=p.id,
            pipeline_name=p.name,
            latest_status=latest.status if latest else None,
            latest_execution_id=latest.id if latest else None,
            last_run_at=latest.started_at if latest else None,
        ))
    return statuses


@router.get("/history", response_model=list[HistoryEntry])
async def get_execution_history(
    pipeline_id: uuid.UUID | None = Query(None),
    status: str | None = Query(None),
    start_date: datetime | None = Query(None),
    end_date: datetime | None = Query(None),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(PipelineExecution).order_by(PipelineExecution.created_at.desc())

    if pipeline_id is not None:
        stmt = stmt.where(PipelineExecution.pipeline_id == pipeline_id)
    if status is not None:
        stmt = stmt.where(PipelineExecution.status == status)
    if start_date is not None:
        stmt = stmt.where(PipelineExecution.created_at >= start_date)
    if end_date is not None:
        stmt = stmt.where(PipelineExecution.created_at <= end_date)

    result = await session.execute(stmt)
    return result.scalars().all()


@router.get("/stats", response_model=AggregateStats)
async def get_aggregate_stats(
    session: AsyncSession = Depends(get_session),
):
    total_pipelines = (await session.execute(
        select(func.count(Pipeline.id))
    )).scalar_one()

    total_executions = (await session.execute(
        select(func.count(PipelineExecution.id))
    )).scalar_one()

    success_count = (await session.execute(
        select(func.count(PipelineExecution.id)).where(
            PipelineExecution.status == "success"
        )
    )).scalar_one()

    success_rate = success_count / total_executions if total_executions > 0 else 0.0

    avg_duration_result = (await session.execute(
        select(
            func.avg(
                func.extract("epoch", PipelineExecution.finished_at - PipelineExecution.started_at)
            )
        ).where(
            PipelineExecution.status == "success",
            PipelineExecution.started_at.isnot(None),
            PipelineExecution.finished_at.isnot(None),
        )
    )).scalar_one_or_none()

    quality_total = (await session.execute(
        select(func.count(PipelineExecution.id)).where(
            PipelineExecution.quality_passed.isnot(None)
        )
    )).scalar_one()

    quality_passed = (await session.execute(
        select(func.count(PipelineExecution.id)).where(
            PipelineExecution.quality_passed == True
        )
    )).scalar_one()

    quality_pass_rate = quality_passed / quality_total if quality_total > 0 else None

    return AggregateStats(
        total_pipelines=total_pipelines,
        total_executions=total_executions,
        success_rate=round(success_rate, 4),
        avg_duration_seconds=round(avg_duration_result, 2) if avg_duration_result else None,
        quality_pass_rate=round(quality_pass_rate, 4) if quality_pass_rate is not None else None,
    )
