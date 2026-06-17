import uuid
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from etl_engine.db.session import get_session
from etl_engine.models.execution import PipelineExecution
from etl_engine.models.task import TaskExecution

router = APIRouter(prefix="/api/executions", tags=["executions"])


class ExecutionResponse(BaseModel):
    id: uuid.UUID
    pipeline_id: uuid.UUID
    status: str
    trigger_type: str
    started_at: datetime | None
    finished_at: datetime | None
    total_rows_read: int | None
    total_rows_written: int | None
    quality_passed: bool | None
    error_message: str | None
    execution_timeline: dict[str, Any] | None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class TaskExecutionResponse(BaseModel):
    id: uuid.UUID
    pipeline_id: uuid.UUID
    task_name: str
    task_type: str
    status: str
    started_at: datetime | None
    finished_at: datetime | None
    input_rows: int | None
    output_rows: int | None
    memory_peak_mb: float | None
    error_message: str | None
    retry_count: int
    config: dict[str, Any] | None
    quality_report: dict[str, Any] | None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class CancelResponse(BaseModel):
    execution_id: uuid.UUID
    status: str
    message: str


class RetryResponse(BaseModel):
    execution_id: uuid.UUID
    new_execution_id: uuid.UUID | None
    message: str


@router.get("", response_model=list[ExecutionResponse])
async def list_executions(
    pipeline_id: uuid.UUID | None = Query(None),
    status: str | None = Query(None),
    session: AsyncSession = Depends(get_session),
):
    stmt = select(PipelineExecution).order_by(PipelineExecution.created_at.desc())
    if pipeline_id is not None:
        stmt = stmt.where(PipelineExecution.pipeline_id == pipeline_id)
    if status is not None:
        stmt = stmt.where(PipelineExecution.status == status)
    result = await session.execute(stmt)
    return result.scalars().all()


@router.get("/{execution_id}", response_model=ExecutionResponse)
async def get_execution(
    execution_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    execution = await session.get(PipelineExecution, execution_id)
    if execution is None:
        raise HTTPException(status_code=404, detail="Execution not found")
    return execution


@router.get("/{execution_id}/tasks", response_model=list[TaskExecutionResponse])
async def get_execution_tasks(
    execution_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    execution = await session.get(PipelineExecution, execution_id)
    if execution is None:
        raise HTTPException(status_code=404, detail="Execution not found")

    stmt = (
        select(TaskExecution)
        .where(TaskExecution.pipeline_id == execution.pipeline_id)
        .order_by(TaskExecution.created_at)
    )
    result = await session.execute(stmt)
    return result.scalars().all()


@router.post("/{execution_id}/cancel", response_model=CancelResponse)
async def cancel_execution(
    execution_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    execution = await session.get(PipelineExecution, execution_id)
    if execution is None:
        raise HTTPException(status_code=404, detail="Execution not found")

    if execution.status not in ("pending", "running"):
        raise HTTPException(status_code=400, detail=f"Cannot cancel execution in '{execution.status}' state")

    execution.status = "cancelled"
    from datetime import datetime as dt
    execution.finished_at = dt.utcnow()
    await session.commit()

    return CancelResponse(
        execution_id=execution.id,
        status="cancelled",
        message="Execution cancelled successfully",
    )


@router.post("/{execution_id}/retry", response_model=RetryResponse)
async def retry_execution(
    execution_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
):
    execution = await session.get(PipelineExecution, execution_id)
    if execution is None:
        raise HTTPException(status_code=404, detail="Execution not found")

    if execution.status not in ("failed", "cancelled"):
        raise HTTPException(
            status_code=400,
            detail=f"Can only retry failed or cancelled executions, current status: '{execution.status}'",
        )

    new_execution = PipelineExecution(
        pipeline_id=execution.pipeline_id,
        status="pending",
        trigger_type="retry",
    )
    session.add(new_execution)
    await session.commit()
    await session.refresh(new_execution)

    return RetryResponse(
        execution_id=execution.id,
        new_execution_id=new_execution.id,
        message="Retry execution created successfully",
    )
