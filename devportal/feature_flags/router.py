from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from ..core.database import get_db
from ..core.schemas import APIResponse, PaginatedResponse
from ..core.dependencies import get_current_user, PermissionChecker
from ..core.models import User
from ..core.utils import utc_now
from .models import FeatureFlag
from .schemas import (
    FeatureFlagCreate,
    FeatureFlagUpdate,
    FeatureFlagResponse,
    UserSegmentCreate,
    UserSegmentUpdate,
    UserSegmentResponse,
    RolloutPhaseCreate,
    RolloutPhaseUpdate,
    RolloutPhaseResponse,
    EvaluationRequest,
    EvaluationResponse,
    BatchEvaluationRequest,
    BatchEvaluationResponse,
    FlagStatsResponse,
    RolloutScheduleRequest,
)
from .services import (
    FeatureFlagService,
    UserSegmentCRUD,
    RolloutPhaseCRUD,
    RolloutService,
)

router = APIRouter(prefix="/feature-flags", tags=["Feature Flags"])


@router.post("", response_model=APIResponse[FeatureFlagResponse], status_code=201)
async def create_flag(
    flag_in: FeatureFlagCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:create"])),
):
    service = FeatureFlagService(db)
    flag = await service.create_flag(flag_in)
    return APIResponse(code=201, data=flag)


@router.get("", response_model=PaginatedResponse[FeatureFlagResponse])
async def list_flags(
    namespace: Optional[str] = None,
    enabled: Optional[bool] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:read"])),
):
    service = FeatureFlagService(db)
    skip = (page - 1) * page_size
    flags, total = await service.list_flags(namespace, enabled, skip, page_size)
    return PaginatedResponse(
        code=200, data=flags, total=total, page=page, page_size=page_size
    )


@router.get("/{flag_id}", response_model=APIResponse[FeatureFlagResponse])
async def get_flag(
    flag_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:read"])),
):
    service = FeatureFlagService(db)
    flag = await service.get_flag(flag_id)
    return APIResponse(code=200, data=flag)


@router.patch("/{flag_id}", response_model=APIResponse[FeatureFlagResponse])
async def update_flag(
    flag_id: str,
    flag_in: FeatureFlagUpdate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:update"])),
):
    service = FeatureFlagService(db)
    flag = await service.update_flag(flag_id, flag_in)
    return APIResponse(code=200, data=flag)


@router.delete("/{flag_id}", response_model=APIResponse[Dict[str, Any]])
async def delete_flag(
    flag_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:delete"])),
):
    service = FeatureFlagService(db)
    await service.delete_flag(flag_id)
    return APIResponse(code=200, data={"id": flag_id, "deleted": True})


@router.post("/evaluate", response_model=APIResponse[EvaluationResponse])
async def evaluate_flag(
    request: EvaluationRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    service = FeatureFlagService(db)
    result = await service.evaluate_flag(request)
    return APIResponse(code=200, data=result)


@router.post("/batch-evaluate", response_model=APIResponse[BatchEvaluationResponse])
async def batch_evaluate(
    request: BatchEvaluationRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    service = FeatureFlagService(db)
    results = await service.batch_evaluate(request.flag_keys, request.user_id, request.context)
    return APIResponse(
        code=200,
        data=BatchEvaluationResponse(results=results, timestamp=utc_now()),
    )


@router.get("/{flag_id}/stats", response_model=APIResponse[FlagStatsResponse])
async def get_flag_stats(
    flag_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:read"])),
):
    service = FeatureFlagService(db)
    stats = await service.get_flag_stats(flag_id)
    return APIResponse(code=200, data=FlagStatsResponse(**stats))


@router.post("/segments", response_model=APIResponse[UserSegmentResponse], status_code=201)
async def create_segment(
    segment_in: UserSegmentCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:create"])),
):
    crud = UserSegmentCRUD(db)
    segment = await crud.create(segment_in)
    return APIResponse(code=201, data=segment)


@router.get("/segments", response_model=PaginatedResponse[UserSegmentResponse])
async def list_segments(
    namespace: Optional[str] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:read"])),
):
    crud = UserSegmentCRUD(db)
    skip = (page - 1) * page_size
    segments, total = await crud.list(namespace, skip, page_size)
    return PaginatedResponse(
        code=200, data=segments, total=total, page=page, page_size=page_size
    )


@router.get("/segments/{segment_id}", response_model=APIResponse[UserSegmentResponse])
async def get_segment(
    segment_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:read"])),
):
    crud = UserSegmentCRUD(db)
    segment = await crud.get(segment_id)
    return APIResponse(code=200, data=segment)


@router.patch("/segments/{segment_id}", response_model=APIResponse[UserSegmentResponse])
async def update_segment(
    segment_id: str,
    segment_in: UserSegmentUpdate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:update"])),
):
    crud = UserSegmentCRUD(db)
    segment = await crud.update(segment_id, segment_in)
    return APIResponse(code=200, data=segment)


@router.delete("/segments/{segment_id}", response_model=APIResponse[Dict[str, Any]])
async def delete_segment(
    segment_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:delete"])),
):
    crud = UserSegmentCRUD(db)
    await crud.delete(segment_id)
    return APIResponse(code=200, data={"id": segment_id, "deleted": True})


@router.post("/segments/{segment_id}/evaluate", response_model=APIResponse[Dict[str, Any]])
async def evaluate_segment(
    segment_id: str,
    request: EvaluationRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    crud = UserSegmentCRUD(db)
    matched = await crud.evaluate(segment_id, request.user_id, request.context)
    return APIResponse(
        code=200,
        data={"segment_id": segment_id, "matched": matched},
    )


@router.post("/rollout-phases", response_model=APIResponse[RolloutPhaseResponse], status_code=201)
async def create_rollout_phase(
    phase_in: RolloutPhaseCreate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:create"])),
):
    crud = RolloutPhaseCRUD(db)
    phase = await crud.create(phase_in)
    return APIResponse(code=201, data=phase)


@router.get("/flags/{flag_id}/rollout-phases", response_model=APIResponse[List[RolloutPhaseResponse]])
async def list_rollout_phases(
    flag_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:read"])),
):
    crud = RolloutPhaseCRUD(db)
    phases = await crud.list_for_flag(flag_id)
    return APIResponse(code=200, data=phases)


@router.patch("/rollout-phases/{phase_id}", response_model=APIResponse[RolloutPhaseResponse])
async def update_rollout_phase(
    phase_id: str,
    phase_in: RolloutPhaseUpdate,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:update"])),
):
    crud = RolloutPhaseCRUD(db)
    phase = await crud.update(phase_id, phase_in)
    return APIResponse(code=200, data=phase)


@router.delete("/rollout-phases/{phase_id}", response_model=APIResponse[Dict[str, Any]])
async def delete_rollout_phase(
    phase_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:delete"])),
):
    crud = RolloutPhaseCRUD(db)
    await crud.delete(phase_id)
    return APIResponse(code=200, data={"id": phase_id, "deleted": True})


@router.post("/rollout-schedule", response_model=APIResponse[List[RolloutPhaseResponse]])
async def create_rollout_schedule(
    request: RolloutScheduleRequest,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:create"])),
):
    crud = RolloutPhaseCRUD(db)
    phases = await crud.create_schedule(request.flag_id, request.phases)
    return APIResponse(code=201, data=phases)


@router.post("/trigger-rollout-updates", response_model=APIResponse[Dict[str, Any]])
async def trigger_rollout_updates(
    db: AsyncSession = Depends(get_db),
    user: User = Depends(PermissionChecker(required_permissions=["feature_flags:update"])),
):
    updated = await RolloutService.update_scheduled_phases(db)
    return APIResponse(code=200, data={"updated_phases": updated})
