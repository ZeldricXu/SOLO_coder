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
from app.schemas.batch import (
    Batch,
    BatchDetail,
    BatchCreate,
    BatchUpdate,
    BatchGenerateRequest,
    BatchGenerateResponse,
    BatchReceiveRequest,
    BatchReceiveResponse,
    BatchSplitRequest,
    BatchSplitResponse,
    BatchMergeRequest,
    BatchMergeResponse,
    BatchFreezeRequest,
    BatchFilterParams,
    BatchInventoryItem,
    BatchTraceResponse,
    InspectionStatusEnum,
    BatchStatusEnum,
)
from app.models.user import User
from app.services.batch_service import create_batch_service
from app.utils.exceptions import InventoryException

router = APIRouter(prefix="/batches", tags=["批次管理"])


@router.get("", response_model=APIResponse[PaginatedResponse[Batch]])
def list_batches(
    sku_id: int | None = Query(None, description="SKU ID"),
    warehouse_id: int | None = Query(None, description="仓库ID"),
    supplier_id: int | None = Query(None, description="供应商ID"),
    inspection_status: InspectionStatusEnum | None = Query(None, description="质检状态"),
    batch_status: BatchStatusEnum | None = Query(None, description="批次状态"),
    is_frozen: bool | None = Query(None, description="是否冻结"),
    is_expiring: bool | None = Query(None, description="是否临期"),
    date_from: datetime | None = Query(None, description="入库开始日期"),
    date_to: datetime | None = Query(None, description="入库结束日期"),
    expiration_from: datetime | None = Query(None, description="到期开始日期"),
    expiration_to: datetime | None = Query(None, description="到期结束日期"),
    min_remaining: int | None = Query(None, description="最小剩余数量"),
    max_remaining: int | None = Query(None, description="最大剩余数量"),
    keyword: str | None = Query(None, description="关键词搜索"),
    sort_by: str | None = Query("created_at", description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)

        filters = BatchFilterParams(
            sku_id=sku_id,
            warehouse_id=warehouse_id,
            supplier_id=supplier_id,
            inspection_status=inspection_status,
            batch_status=batch_status,
            is_frozen=is_frozen,
            is_expiring=is_expiring,
            date_from=date_from,
            date_to=date_to,
            expiration_from=expiration_from,
            expiration_to=expiration_to,
            keyword=keyword,
            min_remaining=min_remaining,
            max_remaining=max_remaining,
        )

        batches, total, total_pages = service.list_batches(
            filters=filters,
            page=paginated.page,
            page_size=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )

        return APIResponse(
            data=PaginatedResponse(
                items=[Batch(**b) for b in batches],
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


@router.get("/{batch_id}", response_model=APIResponse[BatchDetail])
def get_batch_detail(
    batch_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        batch = service.get_batch_detail(batch_id)
        return APIResponse(data=BatchDetail(**batch))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("", response_model=APIResponse[IdResponse])
def create_batch(
    batch_in: BatchCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        batch = service.create_batch(batch_in)
        return APIResponse(data=IdResponse(id=batch.id))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.put("/{batch_id}", response_model=APIResponse[SuccessResponse])
def update_batch(
    batch_id: int,
    batch_in: BatchUpdate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        service.update_batch(batch_id, batch_in)
        return APIResponse(data=SuccessResponse(success=True))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/generate", response_model=APIResponse[BatchGenerateResponse])
def generate_batch_numbers(
    request: BatchGenerateRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        result = service.generate_batch_numbers(request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/receive", response_model=APIResponse[BatchReceiveResponse])
def receive_batches(
    request: BatchReceiveRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        result = service.receive_batches(request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{batch_id}/freeze", response_model=APIResponse[SuccessResponse])
def freeze_batch(
    batch_id: int,
    request: BatchFreezeRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        service.freeze_batch(batch_id, request)
        return APIResponse(data=SuccessResponse(success=True, message="批次冻结成功"))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{batch_id}/unfreeze", response_model=APIResponse[SuccessResponse])
def unfreeze_batch(
    batch_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        service.unfreeze_batch(batch_id)
        return APIResponse(data=SuccessResponse(success=True, message="批次解冻成功"))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/{batch_id}/split", response_model=APIResponse[BatchSplitResponse])
def split_batch(
    batch_id: int,
    request: BatchSplitRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        result = service.split_batch(batch_id, request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/merge", response_model=APIResponse[BatchMergeResponse])
def merge_batches(
    request: BatchMergeRequest,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        result = service.merge_batches(request)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{batch_id}/inventory", response_model=APIResponse[list[BatchInventoryItem]])
def get_batch_inventory(
    batch_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        items = service.get_batch_inventory(batch_id=batch_id)
        return APIResponse(data=items)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/inventory/all", response_model=APIResponse[list[BatchInventoryItem]])
def get_all_batch_inventory(
    sku_id: int | None = Query(None, description="SKU ID"),
    warehouse_id: int | None = Query(None, description="仓库ID"),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        items = service.get_batch_inventory(
            sku_id=sku_id,
            warehouse_id=warehouse_id,
        )
        return APIResponse(data=items)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{batch_id}/trace", response_model=APIResponse[BatchTraceResponse])
def get_batch_trace(
    batch_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        trace = service.get_batch_trace(batch_id)
        return APIResponse(data=BatchTraceResponse(**trace))
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{batch_id}/shelf-life", response_model=APIResponse[dict])
def get_batch_shelf_life(
    batch_id: int,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        result = service.calculate_shelf_life(batch_id)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/expiring/check", response_model=APIResponse[list[dict]])
def check_expiring_batches(
    days: int = Query(30, description="预警天数", ge=1, le=365),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        service = create_batch_service(db, current_user)
        result = service.check_expiring_batches(days=days)
        return APIResponse(data=result)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e
