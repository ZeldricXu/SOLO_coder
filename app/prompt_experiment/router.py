from fastapi import APIRouter, Depends, Query, Body
from uuid import UUID
from sqlalchemy.ext.asyncio import AsyncSession
from typing import Optional, Dict, Any

from app.database import get_db
from app.schemas import (
    PromptCreate,
    PromptResponse,
    ABTestCreate,
    ABTestResponse,
    ABTestResult,
    PromptExperimentCreate,
    PromptExperimentResponse,
    ExperimentEvaluation,
    BaseResponse,
    PaginatedResponse,
)
from app.prompt_experiment.service import PromptService, ABTestService, ExperimentService
from app.api_gateway.auth import get_current_user
from app.logging import LogContext
from app.models import User

router = APIRouter(prefix="/api/v1/prompts", tags=["Prompt Experiments"])


@router.post("", response_model=BaseResponse[PromptResponse])
async def create_prompt(
    prompt_in: PromptCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = PromptService(db)
    user_id = current_user.id if current_user else UUID("00000000-0000-0000-0000-000000000001")
    prompt = await service.create_prompt(prompt_in, user_id)
    return BaseResponse(
        code=201,
        data=prompt,
        request_id=LogContext.get_request_id(),
        message="Prompt created successfully",
    )


@router.get("/{prompt_id}", response_model=BaseResponse[PromptResponse])
async def get_prompt(
    prompt_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = PromptService(db)
    prompt = await service.get_prompt(prompt_id)
    return BaseResponse(data=prompt, request_id=LogContext.get_request_id())


@router.get("/by-name/{name}", response_model=BaseResponse[PromptResponse])
async def get_prompt_by_name(
    name: str,
    version: Optional[int] = Query(None, description="Prompt version"),
    db: AsyncSession = Depends(get_db),
):
    service = PromptService(db)
    prompt = await service.get_prompt_by_name(name, version)
    return BaseResponse(data=prompt, request_id=LogContext.get_request_id())


@router.get("", response_model=BaseResponse[PaginatedResponse[PromptResponse]])
async def list_prompts(
    name_pattern: Optional[str] = Query(None, description="Filter by name pattern"),
    tag: Optional[str] = Query(None, description="Filter by tag"),
    include_versions: bool = Query(False, description="Include all versions"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=100, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    service = PromptService(db)
    skip = (page - 1) * page_size
    prompts, total = await service.list_prompts(
        name_pattern=name_pattern,
        tag=tag,
        include_versions=include_versions,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=prompts,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )


@router.get("/{name}/versions", response_model=BaseResponse[list])
async def list_prompt_versions(
    name: str,
    db: AsyncSession = Depends(get_db),
):
    service = PromptService(db)
    versions = await service.list_versions(name)
    return BaseResponse(data=versions, request_id=LogContext.get_request_id())


@router.post("/{prompt_id}/render", response_model=BaseResponse[str])
async def render_prompt(
    prompt_id: UUID,
    variables: Dict[str, Any] = Body(default_factory=dict, description="Template variables"),
    db: AsyncSession = Depends(get_db),
):
    service = PromptService(db)
    rendered = await service.render_prompt(prompt_id, variables)
    return BaseResponse(data=rendered, request_id=LogContext.get_request_id())


@router.delete("/{prompt_id}", response_model=BaseResponse)
async def delete_prompt(
    prompt_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = PromptService(db)
    await service.delete_prompt(prompt_id)
    return BaseResponse(
        message="Prompt deleted successfully",
        request_id=LogContext.get_request_id(),
    )


@router.post("/ab-tests", response_model=BaseResponse[ABTestResponse])
async def create_ab_test(
    test_in: ABTestCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = ABTestService(db)
    user_id = current_user.id if current_user else UUID("00000000-0000-0000-0000-000000000001")
    test = await service.create_ab_test(test_in, user_id)
    return BaseResponse(
        code=201,
        data=test,
        request_id=LogContext.get_request_id(),
        message="AB test created successfully",
    )


@router.get("/ab-tests/{test_id}", response_model=BaseResponse[ABTestResponse])
async def get_ab_test(
    test_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = ABTestService(db)
    test = await service.get_ab_test(test_id)
    return BaseResponse(data=test, request_id=LogContext.get_request_id())


@router.get("/ab-tests", response_model=BaseResponse[PaginatedResponse[ABTestResponse]])
async def list_ab_tests(
    status: Optional[str] = Query(None, description="Filter by status"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=100, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    service = ABTestService(db)
    skip = (page - 1) * page_size
    tests, total = await service.list_ab_tests(
        status=status,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=tests,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )


@router.post("/ab-tests/{test_id}/start", response_model=BaseResponse[ABTestResponse])
async def start_ab_test(
    test_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = ABTestService(db)
    test = await service.start_ab_test(test_id)
    return BaseResponse(
        data=test,
        request_id=LogContext.get_request_id(),
        message="AB test started successfully",
    )


@router.post("/ab-tests/{test_id}/stop", response_model=BaseResponse[ABTestResponse])
async def stop_ab_test(
    test_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = ABTestService(db)
    test = await service.stop_ab_test(test_id)
    return BaseResponse(
        data=test,
        request_id=LogContext.get_request_id(),
        message="AB test stopped successfully",
    )


@router.get("/ab-tests/{test_id}/variant", response_model=BaseResponse[dict])
async def get_ab_test_variant(
    test_id: UUID,
    user_identifier: str = Query(..., description="User identifier for deterministic assignment"),
    db: AsyncSession = Depends(get_db),
):
    service = ABTestService(db)
    variant, prompt_id = await service.get_variant(test_id, user_identifier)
    return BaseResponse(
        data={"variant": variant, "prompt_id": str(prompt_id)},
        request_id=LogContext.get_request_id(),
    )


@router.post("/ab-tests/{test_id}/result", response_model=BaseResponse[ABTestResponse])
async def record_ab_test_result(
    test_id: UUID,
    metrics: Dict[str, float] = Body(..., description="Metrics data"),
    variant: str = Query(..., description="Variant: control or treatment"),
    db: AsyncSession = Depends(get_db),
):
    service = ABTestService(db)
    test = await service.record_result(test_id, variant, metrics)
    return BaseResponse(
        data=test,
        request_id=LogContext.get_request_id(),
        message="Result recorded successfully",
    )


@router.get("/ab-tests/{test_id}/analyze", response_model=BaseResponse[ABTestResult])
async def analyze_ab_test(
    test_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = ABTestService(db)
    result = await service.analyze_results(test_id)
    return BaseResponse(data=result, request_id=LogContext.get_request_id())


@router.post("/experiments", response_model=BaseResponse[PromptExperimentResponse])
async def create_experiment(
    exp_in: PromptExperimentCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = ExperimentService(db)
    user_id = current_user.id if current_user else UUID("00000000-0000-0000-0000-000000000001")
    experiment = await service.create_experiment(exp_in, user_id)
    return BaseResponse(
        code=201,
        data=experiment,
        request_id=LogContext.get_request_id(),
        message="Experiment created successfully",
    )


@router.get("/experiments/{experiment_id}", response_model=BaseResponse[PromptExperimentResponse])
async def get_experiment(
    experiment_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = ExperimentService(db)
    experiment = await service.get_experiment(experiment_id)
    return BaseResponse(data=experiment, request_id=LogContext.get_request_id())


@router.get("/experiments", response_model=BaseResponse[PaginatedResponse[PromptExperimentResponse]])
async def list_experiments(
    prompt_id: Optional[UUID] = Query(None, description="Filter by prompt ID"),
    status: Optional[str] = Query(None, description="Filter by status"),
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(20, ge=1, le=100, description="Page size"),
    db: AsyncSession = Depends(get_db),
):
    service = ExperimentService(db)
    skip = (page - 1) * page_size
    experiments, total = await service.list_experiments(
        prompt_id=prompt_id,
        status=status,
        skip=skip,
        limit=page_size,
    )
    return BaseResponse(
        data=PaginatedResponse(
            items=experiments,
            total=total,
            page=page,
            page_size=page_size,
            total_pages=(total + page_size - 1) // page_size,
        ),
        request_id=LogContext.get_request_id(),
    )


@router.post("/experiments/{experiment_id}/run", response_model=BaseResponse[PromptExperimentResponse])
async def run_experiment(
    experiment_id: UUID,
    db: AsyncSession = Depends(get_db),
):
    service = ExperimentService(db)
    experiment = await service.run_experiment(experiment_id)
    return BaseResponse(
        data=experiment,
        request_id=LogContext.get_request_id(),
        message="Experiment completed successfully",
    )


@router.post("/experiments/{experiment_id}/evaluation", response_model=BaseResponse[PromptExperimentResponse])
async def add_evaluation(
    experiment_id: UUID,
    evaluation: ExperimentEvaluation,
    db: AsyncSession = Depends(get_db),
):
    service = ExperimentService(db)
    experiment = await service.add_evaluation(experiment_id, evaluation)
    return BaseResponse(
        data=experiment,
        request_id=LogContext.get_request_id(),
        message="Evaluation added successfully",
    )
