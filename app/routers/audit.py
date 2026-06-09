from datetime import datetime
from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import Response
from sqlalchemy.orm import Session

from app.models.audit import AuditAction

from app.core.database import get_db
from app.schemas.common import (
    APIResponse,
    PaginatedResponse,
    PaginatedParams,
)
from app.schemas.audit import (
    AuditLog,
    AuditLogDetail,
    AuditLogQuery,
    AuditStatisticsResponse,
    AuditAnomalyDetectionRequest,
    AuditAnomalyResponse,
    AuditExportRequest,
    AuditExportResponse,
    UserActivityStats,
    ResourceActivityStats,
)
from app.services.audit_service import create_audit_service
from app.utils.exceptions import InventoryException

router = APIRouter(prefix="/api/v1/audit", tags=["审计日志管理"])


@router.get("", response_model=APIResponse[PaginatedResponse[AuditLog]])
def list_audit_logs(
    user_id: Optional[int] = Query(None, description="用户ID"),
    action: Optional[str] = Query(None, description="操作类型"),
    resource_type: Optional[str] = Query(None, description="资源类型"),
    resource_id: Optional[int] = Query(None, description="资源ID"),
    ip_address: Optional[str] = Query(None, description="IP地址"),
    start_date: Optional[datetime] = Query(None, description="开始日期"),
    end_date: Optional[datetime] = Query(None, description="结束日期"),
    keyword: Optional[str] = Query(None, description="关键词搜索"),
    sort_by: str = Query("timestamp", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
) -> APIResponse[PaginatedResponse[AuditLog]]:
    try:
        audit_service = create_audit_service(db)

        action_enum: Optional[AuditAction] = None
        if action:
            try:
                action_enum = AuditAction(action)
            except ValueError:
                pass

        filters = AuditLogQuery(
            user_id=user_id,
            action=action_enum,
            resource_type=resource_type,
            resource_id=resource_id,
            ip_address=ip_address,
            start_date=start_date,
            end_date=end_date,
            keyword=keyword,
        )

        skip = (paginated.page - 1) * paginated.page_size
        logs = audit_service.list_logs(
            filters=filters,
            skip=skip,
            limit=paginated.page_size,
        )
        total = audit_service.count_logs(filters)
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=logs,
                page=paginated.page,
                page_size=paginated.page_size,
                total=total,
                total_pages=total_pages,
                has_next=paginated.page < total_pages,
                has_prev=paginated.page > 1,
            )
        )
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{log_id}", response_model=APIResponse[AuditLogDetail])
def get_audit_log_detail(
    log_id: int,
    db: Session = Depends(get_db),
) -> APIResponse[AuditLogDetail]:
    try:
        audit_service = create_audit_service(db)
        log_detail = audit_service.get_log_detail(log_id)
        return APIResponse(data=log_detail)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/statistics", response_model=APIResponse[AuditStatisticsResponse])
def get_audit_statistics(
    user_id: Optional[int] = Query(None, description="用户ID"),
    resource_type: Optional[str] = Query(None, description="资源类型"),
    start_date: Optional[datetime] = Query(None, description="开始日期"),
    end_date: Optional[datetime] = Query(None, description="结束日期"),
    db: Session = Depends(get_db),
):
    try:
        audit_service = create_audit_service(db)

        filters = AuditLogQuery(
            user_id=user_id,
            resource_type=resource_type,
            start_date=start_date,
            end_date=end_date,
        )

        stats = audit_service.get_statistics(filters=filters)
        return APIResponse(data=stats)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/statistics/user-activity", response_model=APIResponse[list[UserActivityStats]])
def get_user_activity_stats(
    user_id: Optional[int] = Query(None, description="用户ID"),
    start_date: Optional[datetime] = Query(None, description="开始日期"),
    end_date: Optional[datetime] = Query(None, description="结束日期"),
    limit: int = Query(100, description="返回数量限制"),
    db: Session = Depends(get_db),
):
    try:
        audit_service = create_audit_service(db)
        stats = audit_service.get_user_activity_stats(
            user_id=user_id,
            start_date=start_date,
            end_date=end_date,
            limit=limit,
        )
        return APIResponse(data=stats)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/statistics/resource-activity", response_model=APIResponse[list[ResourceActivityStats]])
def get_resource_activity_stats(
    resource_type: Optional[str] = Query(None, description="资源类型"),
    start_date: Optional[datetime] = Query(None, description="开始日期"),
    end_date: Optional[datetime] = Query(None, description="结束日期"),
    limit: int = Query(50, description="返回数量限制"),
    db: Session = Depends(get_db),
):
    try:
        audit_service = create_audit_service(db)
        stats = audit_service.get_resource_activity_stats(
            resource_type=resource_type,
            start_date=start_date,
            end_date=end_date,
            limit=limit,
        )
        return APIResponse(data=stats)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/detect-anomalies", response_model=APIResponse[list[AuditAnomalyResponse]])
def detect_anomalies(
    request: AuditAnomalyDetectionRequest,
    db: Session = Depends(get_db),
):
    try:
        audit_service = create_audit_service(db)
        anomalies = audit_service.detect_anomalies(request)
        return APIResponse(data=anomalies)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/export", response_model=APIResponse[AuditExportResponse])
def export_audit_logs(
    request: AuditExportRequest,
    db: Session = Depends(get_db),
):
    try:
        audit_service = create_audit_service(db)
        export_result = audit_service.export_logs(request)
        return APIResponse(data=export_result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/export/download/{export_code}")
def download_export(
    export_code: str,
    db: Session = Depends(get_db),
):
    try:
        audit_service = create_audit_service(db)
        export_data = audit_service.get_export_content(export_code)
        if not export_data:
            raise InventoryException(
                "Export link expired or invalid", code=404
            )

        return Response(
            content=export_data["content"],
            media_type=export_data["content_type"],
            headers={
                "Content-Disposition": f"attachment; filename={export_data['filename']}"
            },
        )
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e
