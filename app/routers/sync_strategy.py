from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.schemas.common import (
    APIResponse,
    SuccessResponse,
)
from app.schemas.sync_strategy import (
    WarehouseSyncStrategyUpdate,
    WarehouseSyncStrategyResponse,
    ManualSyncRequest,
    ManualSyncResult,
    ScheduledSyncResult,
    SyncQueueResponse,
    InventorySnapshotCreate,
    InventorySnapshotResponse,
    SnapshotListFilter,
    SnapshotListResponse,
)
from app.services.sync_strategy_service import SyncStrategyService
from app.utils.exceptions import (
    NotFoundException,
    ValidationException,
    BusinessException,
)

router = APIRouter(prefix="/api/v1/warehouses", tags=["库存同步策略"])


def create_sync_strategy_service(db: Session) -> SyncStrategyService:
    return SyncStrategyService(db)


@router.put("/{warehouse_id}/sync-strategy", response_model=APIResponse[WarehouseSyncStrategyResponse])
def update_warehouse_sync_strategy(
    warehouse_id: int,
    strategy_update: WarehouseSyncStrategyUpdate,
    db: Session = Depends(get_db),
):
    try:
        service = create_sync_strategy_service(db)
        warehouse = service.update_warehouse_sync_strategy(warehouse_id, strategy_update)
        db.commit()

        result = WarehouseSyncStrategyResponse(
            warehouse_id=warehouse.id,
            sync_strategy=warehouse.sync_strategy,
            is_virtual=warehouse.is_virtual,
            scheduled_sync_time=warehouse.scheduled_sync_time,
            last_snapshot_at=warehouse.last_snapshot_at,
        )
        return APIResponse(data=result, message="同步策略更新成功")
    except NotFoundException as e:
        db.rollback()
        raise HTTPException(status_code=404, detail=str(e)) from e
    except ValidationException as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e)) from e
    except BusinessException as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail=f"更新同步策略失败: {str(e)}") from e


@router.get("/{warehouse_id}/sync-strategy", response_model=APIResponse[WarehouseSyncStrategyResponse])
def get_warehouse_sync_strategy(
    warehouse_id: int,
    db: Session = Depends(get_db),
):
    try:
        service = create_sync_strategy_service(db)
        strategy = service.get_warehouse_sync_strategy(warehouse_id)
        result = WarehouseSyncStrategyResponse(**strategy)
        return APIResponse(data=result)
    except NotFoundException as e:
        raise HTTPException(status_code=404, detail=str(e)) from e
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取同步策略失败: {str(e)}") from e


@router.post("/{warehouse_id}/sync", response_model=APIResponse[ManualSyncResult])
def trigger_manual_sync(
    warehouse_id: int,
    request: ManualSyncRequest,
    db: Session = Depends(get_db),
):
    try:
        service = create_sync_strategy_service(db)
        result = service.trigger_manual_sync(warehouse_id, request)
        db.commit()
        return APIResponse(data=result, message="手动同步已触发")
    except NotFoundException as e:
        db.rollback()
        raise HTTPException(status_code=404, detail=str(e)) from e
    except BusinessException as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail=f"触发手动同步失败: {str(e)}") from e


@router.get("/{warehouse_id}/sync-queue", response_model=APIResponse[SyncQueueResponse])
def get_sync_queue(
    warehouse_id: int,
    limit: int = Query(100, ge=1, le=1000, description="返回数量限制"),
    db: Session = Depends(get_db),
):
    try:
        service = create_sync_strategy_service(db)
        result = service.get_sync_queue(warehouse_id, limit=limit)
        return APIResponse(data=result)
    except NotFoundException as e:
        raise HTTPException(status_code=404, detail=str(e)) from e
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取同步队列失败: {str(e)}") from e


@router.post("/{warehouse_id}/snapshot", response_model=APIResponse[InventorySnapshotResponse])
def create_inventory_snapshot(
    warehouse_id: int,
    snapshot_in: InventorySnapshotCreate,
    db: Session = Depends(get_db),
):
    try:
        if snapshot_in.warehouse_id != warehouse_id:
            raise ValidationException(
                f"warehouse_id in path ({warehouse_id}) does not match body ({snapshot_in.warehouse_id})"
            )

        service = create_sync_strategy_service(db)
        snapshot = service.create_inventory_snapshot(snapshot_in)
        db.commit()

        result = InventorySnapshotResponse.model_validate(snapshot)
        return APIResponse(data=result, message="库存快照创建成功")
    except NotFoundException as e:
        db.rollback()
        raise HTTPException(status_code=404, detail=str(e)) from e
    except ValidationException as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail=f"创建库存快照失败: {str(e)}") from e


@router.post("/{warehouse_id}/virtual-snapshot", response_model=APIResponse[SuccessResponse])
def take_virtual_warehouse_snapshot(
    warehouse_id: int,
    db: Session = Depends(get_db),
):
    try:
        service = create_sync_strategy_service(db)
        result = service.take_virtual_warehouse_snapshot(warehouse_id)
        db.commit()

        return APIResponse(
            data=SuccessResponse(
                success=result.get("success", False),
                message=f"虚拟仓快照创建完成, 共{result.get('snapshot_count', 0)}条记录"
            ),
            message="虚拟仓快照创建成功"
        )
    except NotFoundException as e:
        db.rollback()
        raise HTTPException(status_code=404, detail=str(e)) from e
    except BusinessException as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail=f"创建虚拟仓快照失败: {str(e)}") from e


@router.get("/{warehouse_id}/snapshots", response_model=APIResponse[SnapshotListResponse])
def list_snapshots(
    warehouse_id: int,
    sku_id: int | None = Query(None, description="SKU ID筛选"),
    start_date: str | None = Query(None, description="开始日期, 格式YYYY-MM-DD"),
    end_date: str | None = Query(None, description="结束日期, 格式YYYY-MM-DD"),
    page: int = Query(1, ge=1, description="页码"),
    page_size: int = Query(20, ge=1, le=100, description="每页数量"),
    db: Session = Depends(get_db),
):
    try:
        from datetime import datetime

        filter_params = SnapshotListFilter(
            warehouse_id=warehouse_id,
            sku_id=sku_id,
            start_date=datetime.strptime(start_date, "%Y-%m-%d") if start_date else None,
            end_date=datetime.strptime(end_date, "%Y-%m-%d") if end_date else None,
            page=page,
            page_size=page_size,
        )

        service = create_sync_strategy_service(db)
        result = service.list_snapshots(filter_params)
        return APIResponse(data=result)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=f"日期格式错误: {str(e)}") from e
    except NotFoundException as e:
        raise HTTPException(status_code=404, detail=str(e)) from e
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"查询快照历史失败: {str(e)}") from e


@router.post("/scheduled-sync/run-all", response_model=APIResponse[list[ScheduledSyncResult]])
def run_all_scheduled_syncs(
    db: Session = Depends(get_db),
):
    try:
        service = create_sync_strategy_service(db)
        results = service.run_all_scheduled_syncs()
        db.commit()
        return APIResponse(data=results, message="定时同步任务已执行")
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail=f"执行定时同步失败: {str(e)}") from e


@router.post("/{warehouse_id}/scheduled-sync", response_model=APIResponse[ScheduledSyncResult])
def run_scheduled_sync(
    warehouse_id: int,
    db: Session = Depends(get_db),
):
    try:
        service = create_sync_strategy_service(db)
        result = service.run_scheduled_sync(warehouse_id)
        db.commit()
        return APIResponse(data=result, message="定时同步已执行")
    except NotFoundException as e:
        db.rollback()
        raise HTTPException(status_code=404, detail=str(e)) from e
    except BusinessException as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e)) from e
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail=f"执行定时同步失败: {str(e)}") from e


__all__ = ["router"]
