import uuid
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from etl_engine.db.session import get_session
from etl_engine.models.pipeline import Pipeline
from etl_engine.orchestrator.dag import DAG, DAGDefinition

router = APIRouter(prefix="/api/pipelines", tags=["pipelines"])


class PipelineCreate(BaseModel):
    name: str
    description: str | None = None
    dag_definition: dict[str, Any]
    schedule: str | None = None
    is_active: bool = True
    max_retries: int = 3
    retry_delay_seconds: int = 60
    timeout_seconds: int = 3600
    sla_seconds: int | None = None


class PipelineUpdate(BaseModel):
    name: str | None = None
    description: str | None = None
    dag_definition: dict[str, Any] | None = None
    schedule: str | None = None
    is_active: bool | None = None
    max_retries: int | None = None
    retry_delay_seconds: int | None = None
    timeout_seconds: int | None = None
    sla_seconds: int | None = None


class PipelineResponse(BaseModel):
    id: uuid.UUID
    name: str
    description: str | None
    dag_definition: dict[str, Any]
    schedule: str | None
    is_active: bool
    max_retries: int
    retry_delay_seconds: int
    timeout_seconds: int
    sla_seconds: int | None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class DependencyGraph(BaseModel):
    nodes: list[dict[str, Any]]
    edges: list[dict[str, Any]]
    execution_order: list[list[str]]


class TriggerResponse(BaseModel):
    pipeline_id: uuid.UUID
    message: str


def _validate_dag(dag_definition: dict[str, Any]) -> DAG:
    try:
        definition = DAGDefinition(**dag_definition)
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"Invalid DAG definition: {exc}")

    dag = DAG(definition)
    if not dag.validate():
        raise HTTPException(status_code=422, detail="DAG validation failed: cycle detected or orphan nodes found")
    return dag


@router.post("", response_model=PipelineResponse, status_code=201)
async def create_pipeline(
    body: PipelineCreate,
    session: AsyncSession = Depends(get_session),
):
    existing = await session.execute(
        select(Pipeline).where(Pipeline.name == body.name)
    )
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(status_code=409, detail=f"Pipeline '{body.name}' already exists")

    _validate_dag(body.dag_definition)

    pipeline = Pipeline(
        name=body.name,
        description=body.description,
        dag_definition=body.dag_definition,
        schedule=body.schedule,
        is_active=body.is_active,
        max_retries=body.max_retries,
        retry_delay_seconds=body.retry_delay_seconds,
        timeout_seconds=body.timeout_seconds,
        sla_seconds=body.sla_seconds,
    )
    session.add(pipeline)
    await session.commit()
    await session.refresh(pipeline)
    return pipeline


@router.get("", response_model=list[PipelineResponse])
async def list_pipelines(
    session: AsyncSession = Depends(get_session),
):
    result = await session.execute(select(Pipeline).order_by(Pipeline.created_at))
    return result.scalars().all()


@router.get("/{pipeline_id}", response_model=PipelineResponse)
async def get_pipeline(
    pipeline_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    pipeline = await session.get(Pipeline, pipeline_id)
    if pipeline is None:
        raise HTTPException(status_code=404, detail="Pipeline not found")
    return pipeline


@router.put("/{pipeline_id}", response_model=PipelineResponse)
async def update_pipeline(
    pipeline_id: uuid.UUID,
    body: PipelineUpdate,
    session: AsyncSession = Depends(get_session),
):
    pipeline = await session.get(Pipeline, pipeline_id)
    if pipeline is None:
        raise HTTPException(status_code=404, detail="Pipeline not found")

    update_data = body.model_dump(exclude_unset=True)

    if "name" in update_data and update_data["name"] != pipeline.name:
        existing = await session.execute(
            select(Pipeline).where(Pipeline.name == update_data["name"])
        )
        if existing.scalar_one_or_none() is not None:
            raise HTTPException(status_code=409, detail=f"Pipeline '{update_data['name']}' already exists")

    if "dag_definition" in update_data:
        _validate_dag(update_data["dag_definition"])

    for field, value in update_data.items():
        setattr(pipeline, field, value)

    await session.commit()
    await session.refresh(pipeline)
    return pipeline


@router.delete("/{pipeline_id}", status_code=204)
async def delete_pipeline(
    pipeline_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    pipeline = await session.get(Pipeline, pipeline_id)
    if pipeline is None:
        raise HTTPException(status_code=404, detail="Pipeline not found")
    await session.delete(pipeline)
    await session.commit()


@router.post("/{pipeline_id}/trigger", response_model=TriggerResponse)
async def trigger_pipeline(
    pipeline_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    pipeline = await session.get(Pipeline, pipeline_id)
    if pipeline is None:
        raise HTTPException(status_code=404, detail="Pipeline not found")
    if not pipeline.is_active:
        raise HTTPException(status_code=400, detail="Pipeline is not active")

    return TriggerResponse(
        pipeline_id=pipeline.id,
        message="Pipeline triggered successfully",
    )


@router.get("/{pipeline_id}/dependencies", response_model=DependencyGraph)
async def get_dependencies(
    pipeline_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    pipeline = await session.get(Pipeline, pipeline_id)
    if pipeline is None:
        raise HTTPException(status_code=404, detail="Pipeline not found")

    dag = DAG(DAGDefinition(**pipeline.dag_definition))

    nodes = [
        {
            "id": node.id,
            "type": node.type,
            "dependencies": node.dependencies,
        }
        for node in dag.definition.nodes
    ]
    edges = [
        {"source": edge.source, "target": edge.target}
        for edge in dag.definition.edges
    ]
    execution_order = dag.get_execution_order()

    return DependencyGraph(nodes=nodes, edges=edges, execution_order=execution_order)
