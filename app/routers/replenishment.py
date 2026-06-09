from datetime import datetime, date
from fastapi import APIRouter, Depends, HTTPException, Query, Body
from sqlalchemy.orm import Session
from typing import Optional, List

from app.core.database import get_db
from app.core.security import get_current_user
from app.schemas.common import (
    APIResponse,
    PaginatedResponse,
    PaginatedParams,
    SuccessResponse,
    IdResponse,
)
from app.schemas.replenishment import (
    ReplenishmentSuggestion,
    ReplenishmentSuggestionDetail,
    ReplenishmentReviewRequest,
    ReplenishmentConvertRequest,
    ReplenishmentGenerateRequest,
    ReplenishmentGenerateResponse,
    ForecastRequest,
    ForecastResponse,
    SalesForecast,
    ReplenishmentStatisticsResponse,
    ReplenishmentStatusEnum,
    ForecastPeriodEnum,
    ForecastMethodEnum,
)
from app.models.user import User
from app.services.replenishment_service import create_replenishment_service

router = APIRouter(prefix="/api/v1/replenishment", tags=["智能补货"])


@router.get("/suggestions", response_model=APIResponse[PaginatedResponse[ReplenishmentSuggestion]])
def list_suggestions(
    status: Optional[ReplenishmentStatusEnum] = Query(None, description="状态"),
    sku_id: Optional[int] = Query(None, description="SKU ID"),
    supplier_id: Optional[int] = Query(None, description="供应商ID"),
    warehouse_id: Optional[int] = Query(None, description="仓库ID"),
    created_by: Optional[int] = Query(None, description="创建人ID"),
    date_from: Optional[datetime] = Query(None, description="开始日期"),
    date_to: Optional[datetime] = Query(None, description="结束日期"),
    min_quantity: Optional[int] = Query(None, description="最小数量"),
    max_quantity: Optional[int] = Query(None, description="最大数量"),
    sort_by: Optional[str] = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_replenishment_service(db, current_user)
    items, total, total_pages = service.list_suggestions(
        page=paginated.page,
        page_size=paginated.page_size,
        status=status.value if status else None,
        sku_id=sku_id,
        supplier_id=supplier_id,
        warehouse_id=warehouse_id,
        created_by=created_by,
        date_from=date_from,
        date_to=date_to,
        min_quantity=min_quantity,
        max_quantity=max_quantity,
        sort_by=sort_by,
        sort_order=sort_order,
    )
    return APIResponse(
        data=PaginatedResponse(
            items=items,
            page=paginated.page,
            page_size=paginated.page_size,
            total=total,
            total_pages=total_pages,
            has_next=paginated.page < total_pages,
            has_prev=paginated.page > 1,
        )
    )


@router.get("/suggestions/{suggestion_id}", response_model=APIResponse[ReplenishmentSuggestionDetail])
def get_suggestion(
    suggestion_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_replenishment_service(db, current_user)
    suggestion = service.get_suggestion(suggestion_id)
    if not suggestion:
        raise HTTPException(status_code=404, detail="补货建议不存在")
    return APIResponse(data=ReplenishmentSuggestionDetail.model_validate(suggestion.__dict__))


@router.post("/suggestions/generate", response_model=APIResponse[ReplenishmentGenerateResponse])
async def generate_suggestions(
    request: ReplenishmentGenerateRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_replenishment_service(db, current_user)
    result = await service.generate_suggestions(request)
    return APIResponse(data=result)


@router.post("/suggestions/{suggestion_id}/approve", response_model=APIResponse[SuccessResponse])
def approve_suggestion(
    suggestion_id: int,
    request: Optional[ReplenishmentReviewRequest] = Body(
        default=None, description="审批参数"
    ),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_replenishment_service(db, current_user)
    if request is None:
        request = ReplenishmentReviewRequest(approved=True)
    else:
        request.approved = True
    try:
        service.review_suggestion(suggestion_id, request)
        return APIResponse(data=SuccessResponse(success=True))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/suggestions/{suggestion_id}/reject", response_model=APIResponse[SuccessResponse])
def reject_suggestion(
    suggestion_id: int,
    request: Optional[ReplenishmentReviewRequest] = Body(
        default=None, description="驳回参数"
    ),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_replenishment_service(db, current_user)
    if request is None:
        request = ReplenishmentReviewRequest(approved=False)
    else:
        request.approved = False
    try:
        service.review_suggestion(suggestion_id, request)
        return APIResponse(data=SuccessResponse(success=True))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/suggestions/{suggestion_id}/convert", response_model=APIResponse[IdResponse])
def convert_to_purchase_order(
    suggestion_id: int,
    request: Optional[ReplenishmentConvertRequest] = Body(
        default=None, description="转换参数"
    ),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_replenishment_service(db, current_user)
    if request is None:
        request = ReplenishmentConvertRequest()
    try:
        purchase_order_id = service.convert_to_purchase_order(suggestion_id, request)
        return APIResponse(data=IdResponse(id=purchase_order_id))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/forecast", response_model=APIResponse[PaginatedResponse[SalesForecast]])
def get_forecast_list(
    sku_id: Optional[int] = Query(None, description="SKU ID"),
    forecast_period: Optional[ForecastPeriodEnum] = Query(None, description="预测周期"),
    forecast_method: Optional[ForecastMethodEnum] = Query(None, description="预测方法"),
    date_from: Optional[date] = Query(None, description="开始日期"),
    date_to: Optional[date] = Query(None, description="结束日期"),
    min_confidence: Optional[float] = Query(None, ge=0, le=1, description="最小置信度"),
    sort_by: Optional[str] = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_replenishment_service(db, current_user)
    items, total, total_pages = service.get_forecast_list(
        page=paginated.page,
        page_size=paginated.page_size,
        sku_id=sku_id,
        forecast_period=forecast_period.value if forecast_period else None,
        forecast_method=forecast_method.value if forecast_method else None,
        date_from=date_from,
        date_to=date_to,
        min_confidence=min_confidence,
        sort_by=sort_by,
        sort_order=sort_order,
    )
    return APIResponse(
        data=PaginatedResponse(
            items=items,
            page=paginated.page,
            page_size=paginated.page_size,
            total=total,
            total_pages=total_pages,
            has_next=paginated.page < total_pages,
            has_prev=paginated.page > 1,
        )
    )


@router.post("/forecast", response_model=APIResponse[List[ForecastResponse]])
def generate_forecast(
    request: ForecastRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_replenishment_service(db, current_user)
    results = service.generate_forecast(request)
    return APIResponse(data=results)


@router.get("/statistics", response_model=APIResponse[ReplenishmentStatisticsResponse])
def get_statistics(
    date_from: Optional[datetime] = Query(None, description="开始日期"),
    date_to: Optional[datetime] = Query(None, description="结束日期"),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_replenishment_service(db, current_user)
    stats = service.get_statistics(date_from=date_from, date_to=date_to)
    return APIResponse(data=stats)
