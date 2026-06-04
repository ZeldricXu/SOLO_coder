from fastapi import APIRouter, Depends, HTTPException, status, Query
from typing import List, Dict, Any, Optional

from recommendation_engine.models.schemas import FeedbackEvent
from recommendation_engine.api.dependencies import (
    get_feedback_collector_svc,
    verify_api_key,
)
from recommendation_engine.feedback_collector import FeedbackCollector

router = APIRouter(prefix="/api/v1/feedback", tags=["feedback"], dependencies=[Depends(verify_api_key)])


@router.post("", status_code=status.HTTP_202_ACCEPTED)
async def collect_feedback(
    event: FeedbackEvent,
    collector: FeedbackCollector = Depends(get_feedback_collector_svc),
):
    try:
        success = await collector.collect(event)
        if not success:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Feedback collector queue full or not running",
            )
        return {"status": "accepted", "event_id": event.event_id}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to collect feedback: {str(e)}",
        )


@router.post("/batch", status_code=status.HTTP_202_ACCEPTED)
async def collect_feedback_batch(
    events: List[FeedbackEvent],
    collector: FeedbackCollector = Depends(get_feedback_collector_svc),
):
    try:
        success_count = await collector.collect_batch(events)
        return {
            "status": "accepted",
            "success_count": success_count,
            "total": len(events),
            "failed_count": len(events) - success_count,
        }
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to collect feedback batch: {str(e)}",
        )


@router.post("/raw", status_code=status.HTTP_202_ACCEPTED)
async def collect_raw_feedback(
    event_data: Dict[str, Any],
    collector: FeedbackCollector = Depends(get_feedback_collector_svc),
):
    try:
        success = await collector.collect_raw(event_data)
        if not success:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Invalid event data or collector unavailable",
            )
        return {"status": "accepted"}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to collect raw feedback: {str(e)}",
        )


@router.post("/load-fallback", status_code=status.HTTP_200_OK)
async def load_fallback_data(
    date_str: Optional[str] = Query(None, description="Date string in YYYY-MM-DD format"),
    collector: FeedbackCollector = Depends(get_feedback_collector_svc),
):
    try:
        count = await collector.load_fallback_data(date_str)
        return {"status": "success", "loaded_count": count}
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to load fallback data: {str(e)}",
        )


@router.get("/stats")
async def get_feedback_stats(
    collector: FeedbackCollector = Depends(get_feedback_collector_svc),
):
    try:
        stats = collector.get_stats()
        return stats
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get stats: {str(e)}",
        )
