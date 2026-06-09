from __future__ import annotations
from datetime import datetime
from fastapi import APIRouter, Depends, HTTPException, Query, Body
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
from app.schemas.purchase_order import (
    PurchaseOrder,
    PurchaseOrderDetail,
    PurchaseOrderCreate,
    PurchaseOrderUpdate,
    PurchaseOrderGenerateRequest,
    PurchaseOrderGenerateResponse,
    PurchaseOrderReceiveRequest,
    PurchaseOrderStatusEnum,
)
from app.models.user import User
from app.services.purchase_order_service import create_purchase_order_service
from app.utils.exceptions import PurchaseOrderException

router = APIRouter(tags=["采购订单"])


@router.get("", response_model=APIResponse[PaginatedResponse[PurchaseOrder]])
def list_orders(
    order_no: str | None = Query(None, description="订单编号"),
    supplier_id: int | None = Query(None, description="供应商ID"),
    warehouse_id: int | None = Query(None, description="仓库ID"),
    status: PurchaseOrderStatusEnum | None = Query(None, description="订单状态"),
    created_by: int | None = Query(None, description="创建人ID"),
    date_from: datetime | None = Query(None, description="创建开始日期"),
    date_to: datetime | None = Query(None, description="创建结束日期"),
    keyword: str | None = Query(None, description="关键词搜索"),
    sort_by: str | None = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)

        status_list = [status] if status else None

        orders, total, total_pages = service.list_orders(
            page=paginated.page,
            page_size=paginated.page_size,
            status=status_list,
            supplier_id=supplier_id,
            warehouse_id=warehouse_id,
            created_by=created_by,
            start_date=date_from,
            end_date=date_to,
            order_no=order_no,
            keyword=keyword,
            sort_by=sort_by,
            sort_order=sort_order,
        )

        return APIResponse(
            data=PaginatedResponse(
                items=orders,
                page=paginated.page,
                page_size=paginated.page_size,
                total=total,
                total_pages=total_pages,
                has_next=paginated.page < total_pages,
                has_prev=paginated.page > 1,
            )
        )
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{order_id}", response_model=APIResponse[PurchaseOrderDetail])
def get_order_detail(
    order_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)
        order = service.get_order_detail(order_id)
        if not order:
            raise HTTPException(status_code=404, detail="采购订单不存在")
        return APIResponse(data=order)
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("", response_model=APIResponse[IdResponse])
def create_order(
    order_in: PurchaseOrderCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)
        order = service.create_order(order_in, current_user)
        return APIResponse(data=IdResponse(id=order.id))
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.put("/{order_id}", response_model=APIResponse[SuccessResponse])
def update_order(
    order_id: int,
    order_in: PurchaseOrderUpdate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)
        service.update_order(order_id, order_in, current_user)
        return APIResponse(data=SuccessResponse(success=True))
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.delete("/{order_id}", response_model=APIResponse[SuccessResponse])
def delete_order(
    order_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)
        service.delete_order(order_id, current_user)
        return APIResponse(data=SuccessResponse(success=True))
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/generate", response_model=APIResponse[PurchaseOrderGenerateResponse])
def generate_purchase_suggestions(
    request: PurchaseOrderGenerateRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)
        result = service.generate_purchase_suggestions(request, current_user)
        return APIResponse(data=result)
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{order_id}/submit", response_model=APIResponse[SuccessResponse])
def submit_for_approval(
    order_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)
        service.submit_for_approval(order_id, current_user)
        return APIResponse(data=SuccessResponse(success=True))
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{order_id}/receive", response_model=APIResponse[SuccessResponse])
def receive_order(
    order_id: int,
    request: PurchaseOrderReceiveRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)
        service.receive_order(order_id, request, current_user)
        return APIResponse(data=SuccessResponse(success=True))
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{order_id}/close", response_model=APIResponse[SuccessResponse])
def close_order(
    order_id: int,
    close_reason: str | None = Body(None, description="关闭原因"),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)
        service.close_order(order_id, close_reason, current_user)
        return APIResponse(data=SuccessResponse(success=True))
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{order_id}/cancel", response_model=APIResponse[SuccessResponse])
def cancel_order(
    order_id: int,
    cancel_reason: str | None = Body(None, description="取消原因"),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_purchase_order_service(db)
        service.cancel_order(order_id, cancel_reason, current_user)
        return APIResponse(data=SuccessResponse(success=True))
    except PurchaseOrderException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e
