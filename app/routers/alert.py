from datetime import datetime
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
from app.schemas.alert import (
    AlertRule,
    AlertRuleDetail,
    AlertRuleCreate,
    AlertRuleUpdate,
    InventoryAlert,
    InventoryAlertDetail,
    AlertAcknowledgeRequest,
    AlertResolveRequest,
    AlertStatisticsResponse,
    AlertCheckResponse,
    AlertRuleTypeEnum,
    ThresholdTypeEnum,
    AlertLevelEnum,
    AlertStatusEnum,
)
from app.models.user import User
from app.services.alert_service import create_alert_service

router = APIRouter(prefix="/api/v1/alerts", tags=["库存预警"])


@router.get("/rules", response_model=APIResponse[PaginatedResponse[AlertRule]])
def list_rules(
    rule_type: Optional[AlertRuleTypeEnum] = Query(None, description="预警类型"),
    is_active: Optional[bool] = Query(None, description="是否启用"),
    threshold_type: Optional[ThresholdTypeEnum] = Query(None, description="阈值类型"),
    keyword: Optional[str] = Query(None, description="关键词搜索"),
    sort_by: Optional[str] = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    items, total, total_pages = service.list_rules(
        page=paginated.page,
        page_size=paginated.page_size,
        rule_type=rule_type.value if rule_type else None,
        is_active=is_active,
        threshold_type=threshold_type.value if threshold_type else None,
        keyword=keyword,
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


@router.post("/rules", response_model=APIResponse[IdResponse])
def create_rule(
    obj_in: AlertRuleCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    rule = service.create_rule(obj_in)
    return APIResponse(data=IdResponse(id=rule.id))


@router.get("/rules/{rule_id}", response_model=APIResponse[AlertRuleDetail])
def get_rule(
    rule_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    rule = service.get_rule(rule_id)
    if not rule:
        raise HTTPException(status_code=404, detail="预警规则不存在")
    return APIResponse(data=AlertRuleDetail.model_validate(rule.__dict__))


@router.put("/rules/{rule_id}", response_model=APIResponse[SuccessResponse])
def update_rule(
    rule_id: int,
    obj_in: AlertRuleUpdate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    service.update_rule(rule_id, obj_in)
    return APIResponse(data=SuccessResponse(success=True))


@router.delete("/rules/{rule_id}", response_model=APIResponse[SuccessResponse])
def delete_rule(
    rule_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    service.delete_rule(rule_id)
    return APIResponse(data=SuccessResponse(success=True))


@router.post("/rules/{rule_id}/enable", response_model=APIResponse[SuccessResponse])
def enable_rule(
    rule_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    service.enable_rule(rule_id)
    return APIResponse(data=SuccessResponse(success=True))


@router.post("/rules/{rule_id}/disable", response_model=APIResponse[SuccessResponse])
def disable_rule(
    rule_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    service.disable_rule(rule_id)
    return APIResponse(data=SuccessResponse(success=True))


@router.get("", response_model=APIResponse[PaginatedResponse[InventoryAlert]])
def list_alerts(
    status: Optional[AlertStatusEnum] = Query(None, description="预警状态"),
    alert_level: Optional[AlertLevelEnum] = Query(None, description="预警级别"),
    alert_type: Optional[AlertRuleTypeEnum] = Query(None, description="预警类型"),
    sku_id: Optional[int] = Query(None, description="SKU ID"),
    warehouse_id: Optional[int] = Query(None, description="仓库ID"),
    rule_id: Optional[int] = Query(None, description="规则ID"),
    date_from: Optional[datetime] = Query(None, description="开始日期"),
    date_to: Optional[datetime] = Query(None, description="结束日期"),
    acknowledged: Optional[bool] = Query(None, description="是否已确认"),
    sort_by: Optional[str] = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    items, total, total_pages = service.list_alerts(
        page=paginated.page,
        page_size=paginated.page_size,
        status=status.value if status else None,
        alert_level=alert_level.value if alert_level else None,
        alert_type=alert_type.value if alert_type else None,
        sku_id=sku_id,
        warehouse_id=warehouse_id,
        rule_id=rule_id,
        date_from=date_from,
        date_to=date_to,
        acknowledged=acknowledged,
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


@router.get("/{alert_id}", response_model=APIResponse[InventoryAlertDetail])
def get_alert(
    alert_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    alert = service.get_alert(alert_id)
    if not alert:
        raise HTTPException(status_code=404, detail="预警记录不存在")
    return APIResponse(data=InventoryAlertDetail.model_validate(alert.__dict__))


@router.post("/{alert_id}/acknowledge", response_model=APIResponse[SuccessResponse])
def acknowledge_alert(
    alert_id: int,
    request: AlertAcknowledgeRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    try:
        service.acknowledge_alert(alert_id, request)
        return APIResponse(data=SuccessResponse(success=True))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/{alert_id}/resolve", response_model=APIResponse[SuccessResponse])
def resolve_alert(
    alert_id: int,
    request: AlertResolveRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    try:
        service.resolve_alert(alert_id, request)
        return APIResponse(data=SuccessResponse(success=True))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/{alert_id}/close", response_model=APIResponse[SuccessResponse])
def close_alert(
    alert_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    try:
        service.close_alert(alert_id)
        return APIResponse(data=SuccessResponse(success=True))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/check", response_model=APIResponse[AlertCheckResponse])
async def check_alerts(
    rule_id: Optional[int] = Query(None, description="指定规则ID"),
    sku_ids: Optional[List[int]] = Query(None, description="指定SKU ID列表"),
    warehouse_ids: Optional[List[int]] = Query(None, description="指定仓库ID列表"),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    result = await service.check_alerts(
        rule_id=rule_id,
        sku_ids=sku_ids,
        warehouse_ids=warehouse_ids,
    )
    return APIResponse(data=result)


@router.get("/statistics", response_model=APIResponse[AlertStatisticsResponse])
def get_statistics(
    date_from: Optional[datetime] = Query(None, description="开始日期"),
    date_to: Optional[datetime] = Query(None, description="结束日期"),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    service = create_alert_service(db, current_user)
    stats = service.get_statistics(date_from=date_from, date_to=date_to)
    return APIResponse(data=stats)
