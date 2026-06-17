import uuid
from datetime import datetime
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from etl_engine.db.session import get_session
from etl_engine.models.execution import PipelineExecution
from etl_engine.models.pipeline import Pipeline
from etl_engine.quality.result import ValidationResult
from etl_engine.quality.rules import QualityRule
from etl_engine.quality.validator import QualityValidator

router = APIRouter(prefix="/api/quality", tags=["quality"])


class QualityRuleCreate(BaseModel):
    pipeline_id: uuid.UUID
    rules: list[dict[str, Any]]


class QualityRuleResponse(BaseModel):
    pipeline_id: uuid.UUID
    rules: list[dict[str, Any]]


class ValidateRequest(BaseModel):
    pipeline_id: uuid.UUID
    execution_id: uuid.UUID | None = None


class QualityReportResponse(BaseModel):
    id: uuid.UUID
    pipeline_id: uuid.UUID
    passed: bool
    total_rules: int
    passed_rules: int
    failed_rules: int
    blocked: bool
    summary: dict[str, Any]
    created_at: datetime

    model_config = {"from_attributes": True}


_pipeline_rules: dict[uuid.UUID, list[dict[str, Any]]] = {}
_quality_reports: list[dict[str, Any]] = []


@router.get("/rules", response_model=QualityRuleResponse)
async def list_quality_rules(
    pipeline_id: uuid.UUID = Query(...),
    session: AsyncSession = Depends(get_session),
):
    pipeline = await session.get(Pipeline, pipeline_id)
    if pipeline is None:
        raise HTTPException(status_code=404, detail="Pipeline not found")

    rules = _pipeline_rules.get(pipeline_id, [])
    return QualityRuleResponse(pipeline_id=pipeline_id, rules=rules)


@router.post("/rules", response_model=QualityRuleResponse)
async def create_quality_rules(
    body: QualityRuleCreate,
    session: AsyncSession = Depends(get_session),
):
    pipeline = await session.get(Pipeline, body.pipeline_id)
    if pipeline is None:
        raise HTTPException(status_code=404, detail="Pipeline not found")

    validated_rules: list[dict[str, Any]] = []
    for rule_data in body.rules:
        try:
            rule = QualityRule(**rule_data)
            validated_rules.append(rule.model_dump())
        except Exception as exc:
            raise HTTPException(
                status_code=422,
                detail=f"Invalid quality rule: {exc}",
            )

    _pipeline_rules[body.pipeline_id] = validated_rules

    return QualityRuleResponse(pipeline_id=body.pipeline_id, rules=validated_rules)


@router.post("/validate", response_model=QualityReportResponse)
async def run_validation(
    body: ValidateRequest,
    session: AsyncSession = Depends(get_session),
):
    pipeline = await session.get(Pipeline, body.pipeline_id)
    if pipeline is None:
        raise HTTPException(status_code=404, detail="Pipeline not found")

    rules_data = _pipeline_rules.get(body.pipeline_id, [])
    if not rules_data:
        raise HTTPException(
            status_code=400,
            detail="No quality rules configured for this pipeline",
        )

    rules = [QualityRule(**r) for r in rules_data]
    validator = QualityValidator(rules)

    try:
        import pandas as pd
        sample_df = pd.DataFrame()
        result: ValidationResult = validator.validate(sample_df)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Validation failed: {exc}")

    report_id = uuid.uuid4()
    report = {
        "id": report_id,
        "pipeline_id": body.pipeline_id,
        "passed": result.passed,
        "total_rules": result.total_rules,
        "passed_rules": result.passed_rules,
        "failed_rules": result.failed_rules,
        "blocked": result.blocked,
        "summary": result.summary,
        "created_at": datetime.utcnow(),
    }
    _quality_reports.append(report)

    return QualityReportResponse(**report)


@router.get("/reports", response_model=list[QualityReportResponse])
async def get_quality_reports(
    pipeline_id: uuid.UUID | None = Query(None),
    session: AsyncSession = Depends(get_session),
):
    reports = _quality_reports
    if pipeline_id is not None:
        reports = [r for r in reports if r["pipeline_id"] == pipeline_id]
    return reports
