from __future__ import annotations
from datetime import datetime
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.schemas.common import (
    APIResponse,
    PaginatedResponse,
    PaginatedParams,
)
from app.schemas.warehouse import (
    Inventory,
    InventoryDetail,
    InventoryTransaction,
    InventoryAdjustRequest,
    InventoryTransferRequest,
    InventoryReserveRequest,
    InventoryReleaseRequest,
    InventoryOverview,
    InventoryFilterParams,
    TransactionFilterParams,
    InventorySync,
    InventorySyncCreate,
    SyncConflict,
    SyncConflictResolve,
)
from app.services.inventory_service import create_inventory_service
from app.services.inventory_sync_service import create_inventory_sync_service
from app.utils.exceptions import InventoryException

router = APIRouter(prefix="/api/v1/inventories", tags=["库存管理"])


@router.get("", response_model=APIResponse[PaginatedResponse[Inventory]])
def list_inventories(
    sku_id: int | None = Query(None, description="SKU ID"),
    warehouse_id: int | None = Query(None, description="仓库ID"),
    zone_id: int | None = Query(None, description="库区ID"),
    min_quantity: int | None = Query(None, description="最小数量"),
    max_quantity: int | None = Query(None, description="最大数量"),
    min_available: int | None = Query(None, description="最小可用数量"),
    max_available: int | None = Query(None, description="最大可用数量"),
    has_low_stock: bool | None = Query(None, description="是否低库存"),
    has_overstock: bool | None = Query(None, description="是否超储"),
    sort_by: str | None = Query(None, description="排序字段"),
    sort_order: str = Query("desc", description="排序方向"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        inventory_service = create_inventory_service(db)

        filters = InventoryFilterParams(
            sku_id=sku_id,
            warehouse_id=warehouse_id,
            zone_id=zone_id,
            min_quantity=min_quantity,
            max_quantity=max_quantity,
            min_available=min_available,
            max_available=max_available,
            has_low_stock=has_low_stock,
            has_overstock=has_overstock,
        )

        skip = (paginated.page - 1) * paginated.page_size
        inventories = inventory_service.list_inventories(
            filters=filters,
            skip=skip,
            limit=paginated.page_size,
            sort_by=sort_by,
            sort_order=sort_order,
        )
        total = inventory_service.count_inventories(filters)
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=inventories,
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


@router.get("/overview", response_model=APIResponse[InventoryOverview])
def get_inventory_overview(
    db: Session = Depends(get_db),
):
    try:
        inventory_service = create_inventory_service(db)
        overview = inventory_service.get_inventory_overview()
        return APIResponse(data=overview)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{inventory_id}", response_model=APIResponse[Inventory])
def get_inventory(
    inventory_id: int,
    db: Session = Depends(get_db),
):
    try:
        inventory_service = create_inventory_service(db)
        inventory = inventory_service.get_inventory(inventory_id)
        return APIResponse(data=inventory)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{inventory_id}/detail", response_model=APIResponse[InventoryDetail])
def get_inventory_detail(
    inventory_id: int,
    db: Session = Depends(get_db),
):
    try:
        inventory_service = create_inventory_service(db)
        inventory_detail = inventory_service.get_inventory_detail(inventory_id)
        return APIResponse(data=inventory_detail)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/adjust", response_model=APIResponse[Inventory])
def adjust_inventory(
    adjust_request: InventoryAdjustRequest,
    db: Session = Depends(get_db),
):
    try:
        inventory_service = create_inventory_service(db)
        inventory = inventory_service.adjust_inventory(adjust_request)
        db.commit()
        return APIResponse(data=inventory, message="库存调整成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/transfer", response_model=APIResponse[dict])
def transfer_inventory(
    transfer_request: InventoryTransferRequest,
    db: Session = Depends(get_db),
):
    try:
        inventory_service = create_inventory_service(db)
        result = inventory_service.transfer_inventory(transfer_request)
        db.commit()
        return APIResponse(data=result, message="库存调拨成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/reserve", response_model=APIResponse[Inventory])
def reserve_inventory(
    reserve_request: InventoryReserveRequest,
    db: Session = Depends(get_db),
):
    try:
        inventory_service = create_inventory_service(db)
        inventory = inventory_service.reserve_inventory(reserve_request)
        db.commit()
        return APIResponse(data=inventory, message="库存预占成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/release", response_model=APIResponse[Inventory])
def release_inventory(
    release_request: InventoryReleaseRequest,
    db: Session = Depends(get_db),
):
    try:
        inventory_service = create_inventory_service(db)
        inventory = inventory_service.release_inventory(release_request)
        db.commit()
        return APIResponse(data=inventory, message="库存释放成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/transactions", response_model=APIResponse[PaginatedResponse[InventoryTransaction]])
def list_transactions(
    sku_id: int | None = Query(None, description="SKU ID"),
    warehouse_id: int | None = Query(None, description="仓库ID"),
    zone_id: int | None = Query(None, description="库区ID"),
    transaction_type: str | None = Query(None, description="事务类型"),
    start_date: datetime | None = Query(None, description="开始日期"),
    end_date: datetime | None = Query(None, description="结束日期"),
    reference_type: str | None = Query(None, description="参考类型"),
    reference_id: int | None = Query(None, description="参考ID"),
    batch_id: str | None = Query(None, description="批次号"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        inventory_service = create_inventory_service(db)

        filters = TransactionFilterParams(
            sku_id=sku_id,
            warehouse_id=warehouse_id,
            zone_id=zone_id,
            transaction_type=transaction_type,
            start_date=start_date,
            end_date=end_date,
            reference_type=reference_type,
            reference_id=reference_id,
            batch_id=batch_id,
        )

        skip = (paginated.page - 1) * paginated.page_size
        transactions = inventory_service.list_transactions(
            filters=filters,
            skip=skip,
            limit=paginated.page_size,
        )
        total = inventory_service.count_transactions(filters)
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=transactions,
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


@router.get("/sync", response_model=APIResponse[PaginatedResponse[InventorySync]])
def list_syncs(
    source_warehouse_id: int | None = Query(None, description="源仓库ID"),
    target_warehouse_id: int | None = Query(None, description="目标仓库ID"),
    sync_status: str | None = Query(None, description="同步状态"),
    sync_type: str | None = Query(None, description="同步类型"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)

        skip = (paginated.page - 1) * paginated.page_size
        syncs = sync_service.list_syncs(
            source_warehouse_id=source_warehouse_id,
            target_warehouse_id=target_warehouse_id,
            sync_status=sync_status,
            sync_type=sync_type,
            skip=skip,
            limit=paginated.page_size,
        )
        total = sync_service.count_syncs(
            source_warehouse_id=source_warehouse_id,
            target_warehouse_id=target_warehouse_id,
            sync_status=sync_status,
            sync_type=sync_type,
        )
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=syncs,
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


@router.post("/sync", response_model=APIResponse[InventorySync])
def create_sync(
    sync_in: InventorySyncCreate,
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)
        sync = sync_service.create_sync(sync_in)
        db.commit()
        return APIResponse(data=sync, message="同步任务创建成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/sync/{sync_id}", response_model=APIResponse[InventorySync])
def get_sync(
    sync_id: int,
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)
        sync = sync_service.get_sync(sync_id)
        return APIResponse(data=sync)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/sync/{sync_id}/execute", response_model=APIResponse[InventorySync])
def execute_sync(
    sync_id: int,
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)
        sync = sync_service.process_sync(sync_id)
        db.commit()
        return APIResponse(data=sync, message="同步任务执行完成")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/sync/{sync_id}/retry", response_model=APIResponse[InventorySync])
def retry_sync(
    sync_id: int,
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)
        sync = sync_service.retry_failed_sync(sync_id)
        db.commit()
        return APIResponse(data=sync, message="同步任务重试成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/sync/conflicts", response_model=APIResponse[PaginatedResponse[SyncConflict]])
def list_sync_conflicts(
    sync_id: int | None = Query(None, description="同步任务ID"),
    sku_id: int | None = Query(None, description="SKU ID"),
    conflict_type: str | None = Query(None, description="冲突类型"),
    status: str | None = Query(None, description="冲突状态"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)

        skip = (paginated.page - 1) * paginated.page_size
        conflicts = sync_service.get_sync_conflicts(
            sync_id=sync_id,
            sku_id=sku_id,
            conflict_type=conflict_type,
            status=status,
            skip=skip,
            limit=paginated.page_size,
        )
        total = sync_service.count_conflicts(
            sync_id=sync_id,
            sku_id=sku_id,
            conflict_type=conflict_type,
            status=status,
        )
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=conflicts,
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


@router.post("/sync/conflicts/{conflict_id}/resolve", response_model=APIResponse[SyncConflict])
def resolve_conflict(
    conflict_id: int,
    resolve_in: SyncConflictResolve,
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)
        conflict = sync_service.resolve_conflict(conflict_id, resolve_in)
        db.commit()
        return APIResponse(data=conflict, message="冲突解决成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/sync/delay/check", response_model=APIResponse[dict])
def check_sync_delay(
    source_warehouse_id: int = Query(..., description="源仓库ID"),
    target_warehouse_id: int = Query(..., description="目标仓库ID"),
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)
        delay_status = sync_service.check_sync_delay(
            source_warehouse_id, target_warehouse_id
        )
        return APIResponse(data=delay_status)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/sync/delay/report", response_model=APIResponse[list[dict]])
def get_sync_delay_report(
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)
        report = sync_service.get_sync_delay_report()
        return APIResponse(data=report)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/sync/consistency-check", response_model=APIResponse[dict])
def perform_consistency_check(
    source_warehouse_id: int = Query(..., description="源仓库ID"),
    target_warehouse_id: int = Query(..., description="目标仓库ID"),
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)
        result = sync_service.perform_consistency_check(
            source_warehouse_id, target_warehouse_id
        )
        return APIResponse(data=result, message="一致性检查完成")
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/sync/cdc/process", response_model=APIResponse[dict])
def process_cdc_events(
    db: Session = Depends(get_db),
):
    try:
        sync_service = create_inventory_sync_service(db)
        result = sync_service.process_cdc_events()
        db.commit()
        return APIResponse(data=result, message="CDC事件处理完成")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e
