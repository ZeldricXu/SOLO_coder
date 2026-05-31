from typing import Any, Dict, Optional

from fastapi import APIRouter, Depends, Header, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db

from .models import (
    AbExperimentCreate,
    ExperimentResultCreate,
    PromptVersionCreate,
)
from .service import PromptExperimentService


router = APIRouter(prefix="/prompt-experiments", tags=["Prompt实验管理"])


@router.post("/prompts", response_model=Dict[str, Any], status_code=201)
async def create_prompt_version(
    prompt_data: PromptVersionCreate,
    db: AsyncSession = Depends(get_db),
):
    service = PromptExperimentService(db)
    prompt = await service.create_prompt_version(prompt_data)
    return {
        "code": 201,
        "data": prompt.model_dump(),
        "message": "Prompt版本创建成功",
    }


@router.get("/prompts/{version_id}", response_model=Dict[str, Any])
async def get_prompt_version(
    version_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = PromptExperimentService(db)
    prompt = await service.get_prompt_version(version_id, tenant_id)
    return {
        "code": 200,
        "data": prompt.model_dump(),
        "message": "查询成功",
    }


@router.post("/experiments", response_model=Dict[str, Any], status_code=201)
async def create_experiment(
    experiment_data: AbExperimentCreate,
    db: AsyncSession = Depends(get_db),
):
    service = PromptExperimentService(db)
    experiment = await service.create_experiment(experiment_data)
    return {
        "code": 201,
        "data": experiment.model_dump(),
        "message": "实验创建成功",
    }


@router.get("/experiments/{experiment_id}", response_model=Dict[str, Any])
async def get_experiment(
    experiment_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = PromptExperimentService(db)
    experiment = await service.get_experiment(experiment_id, tenant_id)
    return {
        "code": 200,
        "data": experiment.model_dump(),
        "message": "查询成功",
    }


@router.post("/experiments/{experiment_id}/start", response_model=Dict[str, Any])
async def start_experiment(
    experiment_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = PromptExperimentService(db)
    experiment = await service.start_experiment(experiment_id, tenant_id)
    return {
        "code": 200,
        "data": experiment.model_dump(),
        "message": "实验启动成功",
    }


@router.post("/results", response_model=Dict[str, Any], status_code=201)
async def record_result(
    result_data: ExperimentResultCreate,
    db: AsyncSession = Depends(get_db),
):
    service = PromptExperimentService(db)
    result = await service.record_result(result_data)
    return {
        "code": 201,
        "data": result.model_dump(),
        "message": "实验结果记录成功",
    }


@router.get("/experiments/{experiment_id}/stats", response_model=Dict[str, Any])
async def get_experiment_stats(
    experiment_id: str,
    user_agent: str = Header("desktop", alias="User-Agent"),
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = PromptExperimentService(db)
    stats = await service.get_experiment_stats(experiment_id, user_agent, tenant_id)
    return {
        "code": 200,
        "data": stats.model_dump(),
        "message": "查询成功",
    }
