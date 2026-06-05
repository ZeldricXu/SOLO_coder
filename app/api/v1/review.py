from typing import Optional, List
from datetime import datetime
from fastapi import APIRouter, HTTPException, Query, Depends, Body
from fastapi.responses import StreamingResponse

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.common import APIResponse, PaginatedResponse
from app.schemas.review import (
    ReviewStatusEnum,
    ReviewPriorityEnum,
    ReviewTaskCompleteRequest,
    FieldCorrection,
    TrainingDataExportRequest,
)
from app.services.review_service import ReviewService

logger = get_logger(__name__)
settings = get_settings()

router = APIRouter(prefix="/review", tags=["review"])


def get_review_service() -> ReviewService:
    return ReviewService()


@router.get("/queue", response_model=APIResponse[PaginatedResponse])
async def get_review_queue(
    status: Optional[ReviewStatusEnum] = None,
    priority: Optional[ReviewPriorityEnum] = None,
    assigned_to: Optional[str] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    review_service: ReviewService = Depends(get_review_service),
):
    try:
        tasks, total = review_service.get_review_queue(
            status=status,
            priority=priority,
            assigned_to=assigned_to,
            page=page,
            page_size=page_size,
        )

        return APIResponse(
            success=True,
            data=PaginatedResponse(
                items=tasks,
                total=total,
                page=page,
                page_size=page_size,
                total_pages=(total + page_size - 1) // page_size,
            ),
        )

    except Exception as e:
        logger.error(f"Failed to get review queue: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/tasks/{task_id}", response_model=APIResponse)
async def get_review_task(
    task_id: int,
    review_service: ReviewService = Depends(get_review_service),
):
    try:
        task = review_service.get_review_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="Review task not found")

        return APIResponse(success=True, data=task)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get review task {task_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/tasks/{task_id}/assign", response_model=APIResponse)
async def assign_review_task(
    task_id: int,
    assigned_to: str = Body(..., embed=True),
    review_service: ReviewService = Depends(get_review_service),
):
    try:
        task = review_service.assign_review_task(task_id, assigned_to)
        if not task:
            raise HTTPException(status_code=404, detail="Review task not found")

        return APIResponse(
            success=True,
            message=f"Task assigned to {assigned_to}",
            data={"task_id": task.id, "assigned_to": task.assigned_to},
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to assign review task {task_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/tasks/{task_id}/start", response_model=APIResponse)
async def start_review_task(
    task_id: int,
    reviewer: str = Body(..., embed=True),
    review_service: ReviewService = Depends(get_review_service),
):
    try:
        task = review_service.start_review_task(task_id, reviewer)
        if not task:
            raise HTTPException(status_code=404, detail="Review task not found")

        return APIResponse(
            success=True,
            message=f"Task started by {reviewer}",
            data={"task_id": task.id, "status": task.status.value},
        )

    except HTTPException:
        raise
    except PermissionError as e:
        raise HTTPException(status_code=403, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to start review task {task_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/tasks/{task_id}/complete", response_model=APIResponse)
async def complete_review_task(
    task_id: int,
    request: ReviewTaskCompleteRequest,
    review_service: ReviewService = Depends(get_review_service),
):
    try:
        request.task_id = task_id
        result = review_service.complete_review_task(request)

        return APIResponse(
            success=True,
            message="Review completed",
            data=result,
        )

    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to complete review task {task_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/tasks/{task_id}/comment", response_model=APIResponse)
async def add_review_comment(
    task_id: int,
    comment_text: str = Body(..., embed=True),
    commenter: str = Body(..., embed=True),
    field_name: Optional[str] = Body(None),
    comment_type: str = Body("general"),
    review_service: ReviewService = Depends(get_review_service),
):
    try:
        comment = review_service.add_review_comment(
            task_id=task_id,
            comment_text=comment_text,
            commenter=commenter,
            field_name=field_name,
            comment_type=comment_type,
        )

        return APIResponse(
            success=True,
            message="Comment added",
            data={"comment_id": comment.id},
        )

    except Exception as e:
        logger.error(f"Failed to add review comment: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/tasks/{task_id}/escalate", response_model=APIResponse)
async def escalate_review_task(
    task_id: int,
    escalated_to: str = Body(..., embed=True),
    escalated_reason: str = Body(..., embed=True),
    escalated_by: str = Body(..., embed=True),
    review_service: ReviewService = Depends(get_review_service),
):
    try:
        task = review_service.escalate_review_task(
            task_id=task_id,
            escalated_to=escalated_to,
            escalated_reason=escalated_reason,
            escalated_by=escalated_by,
        )

        return APIResponse(
            success=True,
            message="Task escalated",
            data={
                "task_id": task.id,
                "escalated_to": task.escalated_to,
                "escalated_reason": task.escalated_reason,
            },
        )

    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to escalate review task {task_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/statistics", response_model=APIResponse)
async def get_review_statistics(
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    review_service: ReviewService = Depends(get_review_service),
):
    try:
        stats = review_service.get_review_statistics(
            start_date=start_date,
            end_date=end_date,
        )

        return APIResponse(success=True, data=stats)

    except Exception as e:
        logger.error(f"Failed to get review statistics: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/export-training-data")
async def export_training_data(
    request: TrainingDataExportRequest,
    review_service: ReviewService = Depends(get_review_service),
):
    try:
        filename, content = review_service.export_training_data(request)

        media_type = (
            "application/json"
            if request.export_format == "json"
            else "text/csv"
        )

        return StreamingResponse(
            iter([content]),
            media_type=media_type,
            headers={"Content-Disposition": f"attachment; filename={filename}"},
        )

    except Exception as e:
        logger.error(f"Failed to export training data: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
