from fastapi import APIRouter, Depends, HTTPException, status, Query
from typing import Optional, List, Dict, Any

from recommendation_engine.models.schemas import UserBehaviorEvent, UserProfile
from recommendation_engine.api.dependencies import (
    get_user_profile_svc,
    verify_api_key,
)
from recommendation_engine.user_profile_service import UserProfileService

router = APIRouter(prefix="/api/v1/user-profile", tags=["user-profile"], dependencies=[Depends(verify_api_key)])


@router.post("/behavior", status_code=status.HTTP_202_ACCEPTED)
async def ingest_behavior_event(
    event: UserBehaviorEvent,
    service: UserProfileService = Depends(get_user_profile_svc),
):
    try:
        await service.ingest_behavior_event(event)
        return {"status": "accepted", "event_id": event.event_id}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to ingest behavior event: {str(e)}",
        )


@router.post("/behavior/batch", status_code=status.HTTP_202_ACCEPTED)
async def ingest_behavior_events_batch(
    events: List[UserBehaviorEvent],
    service: UserProfileService = Depends(get_user_profile_svc),
):
    try:
        success_count = 0
        for event in events:
            await service.ingest_behavior_event(event)
            success_count += 1
        return {"status": "accepted", "success_count": success_count, "total": len(events)}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to ingest behavior events: {str(e)}",
        )


@router.get("/{user_id}", response_model=UserProfile)
async def get_user_profile(
    user_id: str,
    version: Optional[int] = Query(None, description="Profile version number"),
    service: UserProfileService = Depends(get_user_profile_svc),
):
    try:
        profile = await service.get_profile(user_id, version)
        if not profile:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"User profile not found for user {user_id}",
            )
        return profile
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get user profile: {str(e)}",
        )


@router.post("/{user_id}/refresh", response_model=UserProfile)
async def refresh_user_profile(
    user_id: str,
    service: UserProfileService = Depends(get_user_profile_svc),
):
    try:
        profile = await service.refresh_profile(user_id)
        return profile
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to refresh user profile: {str(e)}",
        )


@router.get("/{user_id}/versions")
async def get_profile_versions(
    user_id: str,
    limit: int = Query(10, ge=1, le=100),
    service: UserProfileService = Depends(get_user_profile_svc),
):
    try:
        versions = await service.get_profile_versions(user_id, limit)
        return {"user_id": user_id, "versions": versions}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get profile versions: {str(e)}",
        )


@router.get("/{user_id}/stats")
async def get_user_stats(
    user_id: str,
    service: UserProfileService = Depends(get_user_profile_svc),
):
    try:
        stats = await service.get_realtime_stats(user_id)
        return {"user_id": user_id, "stats": stats}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get user stats: {str(e)}",
        )
