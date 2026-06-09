from __future__ import annotations
from datetime import datetime
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import get_current_user
from app.schemas.common import (
    APIResponse,
    PaginatedResponse,
    PaginatedParams,
    SuccessResponse,
    IdResponse,
)
from app.schemas.approval import (
    ApprovalWorkflow,
    ApprovalWorkflowDetail,
    ApprovalWorkflowCreate,
    ApprovalWorkflowUpdate,
    ApprovalNodeCreate,
    ApprovalRecord,
    ApprovalRecordDetail,
    ApprovalActionRequest,
    ApprovalStatisticsResponse,
    ResourceTypeEnum,
    ApprovalStatusEnum,
)
from app.models.user import User
from app.services.approval_service import create_approval_service
from app.utils.exceptions import ApprovalException

router = APIRouter(tags=["审批流"])


@router.get("/workflows", response_model=APIResponse[PaginatedResponse[ApprovalWorkflow]])
def list_workflows(
    resource_type: ResourceTypeEnum | None = Query(None, description="资源类型"),
    is_active: bool | None = Query(None, description="是否启用"),
    keyword: str | None = Query(None, description="关键词搜索（名称/编码）"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)

        workflows, total, total_pages = service.list_workflows(
            page=paginated.page,
            page_size=paginated.page_size,
            resource_type=resource_type,
            is_active=is_active,
            keyword=keyword,
        )

        return APIResponse(
            data=PaginatedResponse(
                items=workflows,
                page=paginated.page,
                page_size=paginated.page_size,
                total=total,
                total_pages=total_pages,
                has_next=paginated.page < total_pages,
                has_prev=paginated.page > 1,
            )
        )
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/workflows", response_model=APIResponse[IdResponse])
def create_workflow(
    workflow_in: ApprovalWorkflowCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        workflow = service.create_workflow(workflow_in)
        return APIResponse(data=IdResponse(id=workflow.id))
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.put("/workflows/{workflow_id}", response_model=APIResponse[SuccessResponse])
def update_workflow(
    workflow_id: int,
    workflow_in: ApprovalWorkflowUpdate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        service.update_workflow(workflow_id, workflow_in)
        return APIResponse(data=SuccessResponse(success=True))
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/workflows/{workflow_id}", response_model=APIResponse[ApprovalWorkflowDetail])
def get_workflow_detail(
    workflow_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        workflow = service.get_workflow(workflow_id)
        if not workflow:
            raise HTTPException(status_code=404, detail="工作流不存在")
        return APIResponse(data=workflow)
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/workflows/{workflow_id}/nodes", response_model=APIResponse[IdResponse])
def add_approval_node(
    workflow_id: int,
    node_in: ApprovalNodeCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        node = service.add_node(workflow_id, node_in)
        return APIResponse(data=IdResponse(id=node.id))
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/pending", response_model=APIResponse[PaginatedResponse[ApprovalRecord]])
def get_pending_approvals(
    resource_type: ResourceTypeEnum | None = Query(None, description="资源类型"),
    status: ApprovalStatusEnum | None = Query(None, description="审批状态"),
    sort_by: str | None = Query("submitted_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        skip = (paginated.page - 1) * paginated.page_size
        records, total = service.get_pending_approvals(
            user_id=current_user.id,
            resource_type=resource_type,
            status=status,
            skip=skip,
            limit=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=records,
                page=paginated.page,
                page_size=paginated.page_size,
                total=total,
                total_pages=total_pages,
                has_next=paginated.page < total_pages,
                has_prev=paginated.page > 1,
            )
        )
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/statistics", response_model=APIResponse[ApprovalStatisticsResponse])
def get_approval_statistics(
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        stats = service.get_approval_statistics(current_user.id)
        return APIResponse(data=stats)
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{record_id}/approve", response_model=APIResponse[SuccessResponse])
def approve_approval(
    record_id: int,
    action_data: ApprovalActionRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        service.process_approval_action(record_id, action_data, current_user)
        return APIResponse(data=SuccessResponse(success=True))
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{record_id}/reject", response_model=APIResponse[SuccessResponse])
def reject_approval(
    record_id: int,
    action_data: ApprovalActionRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        service.process_approval_action(record_id, action_data, current_user)
        return APIResponse(data=SuccessResponse(success=True))
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{record_id}/withdraw", response_model=APIResponse[SuccessResponse])
def withdraw_approval(
    record_id: int,
    action_data: ApprovalActionRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        service.process_approval_action(record_id, action_data, current_user)
        return APIResponse(data=SuccessResponse(success=True))
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/records", response_model=APIResponse[PaginatedResponse[ApprovalRecord]])
def list_approval_records(
    resource_type: ResourceTypeEnum | None = Query(None, description="资源类型"),
    resource_id: int | None = Query(None, description="资源ID"),
    status: ApprovalStatusEnum | None = Query(None, description="审批状态"),
    submitter_id: int | None = Query(None, description="提交人ID"),
    approver_id: int | None = Query(None, description="审批人ID"),
    date_from: datetime | None = Query(None, description="提交开始日期"),
    date_to: datetime | None = Query(None, description="提交结束日期"),
    sort_by: str | None = Query("submitted_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        from app.schemas.approval import ApprovalRecordListFilter

        service = create_approval_service(db, current_user)

        filters = ApprovalRecordListFilter(
            resource_type=resource_type,
            resource_id=resource_id,
            status=status,
            submitter_id=submitter_id,
            approver_id=approver_id,
            date_from=date_from,
            date_to=date_to,
        )

        skip = (paginated.page - 1) * paginated.page_size
        records, total = service._list_approval_records(
            filters=filters,
            skip=skip,
            limit=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=records,
                page=paginated.page,
                page_size=paginated.page_size,
                total=total,
                total_pages=total_pages,
                has_next=paginated.page < total_pages,
                has_prev=paginated.page > 1,
            )
        )
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/records/{record_id}", response_model=APIResponse[ApprovalRecordDetail])
def get_approval_record_detail(
    record_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_approval_service(db, current_user)
        record = service._get_approval_record_detail(record_id)
        if not record:
            raise HTTPException(status_code=404, detail="审批记录不存在")
        return APIResponse(data=record)
    except ApprovalException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e
