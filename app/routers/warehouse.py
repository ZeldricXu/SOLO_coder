from __future__ import annotations
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.schemas.common import (
    APIResponse,
    PaginatedResponse,
    PaginatedParams,
    SuccessResponse,
)
from app.schemas.warehouse import (
    Warehouse,
    WarehouseCreate,
    WarehouseUpdate,
    WarehouseDetail,
    Zone,
    ZoneCreate,
    ZoneUpdate,
    WarehouseInventoryOverview,
)
from app.services.warehouse_service import create_warehouse_service
from app.utils.exceptions import InventoryException

router = APIRouter(prefix="/api/v1/warehouses", tags=["仓库管理"])


@router.get("", response_model=APIResponse[PaginatedResponse[Warehouse]])
def list_warehouses(
    warehouse_type: str | None = Query(None, description="仓库类型"),
    is_active: bool | None = Query(None, description="是否启用"),
    city: str | None = Query(None, description="城市"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        warehouse_service, _ = create_warehouse_service(db)

        skip = (paginated.page - 1) * paginated.page_size
        warehouses = warehouse_service.list_warehouses(
            skip=skip,
            limit=paginated.page_size,
            warehouse_type=warehouse_type,
            is_active=is_active,
            city=city,
        )
        total = warehouse_service.count_warehouses()
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=warehouses,
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


@router.post("", response_model=APIResponse[Warehouse])
def create_warehouse(
    warehouse_in: WarehouseCreate,
    db: Session = Depends(get_db),
):
    try:
        warehouse_service, _ = create_warehouse_service(db)
        warehouse = warehouse_service.create_warehouse(warehouse_in)
        db.commit()
        return APIResponse(data=warehouse, message="仓库创建成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{warehouse_id}", response_model=APIResponse[Warehouse])
def get_warehouse(
    warehouse_id: int,
    db: Session = Depends(get_db),
):
    try:
        warehouse_service, _ = create_warehouse_service(db)
        warehouse = warehouse_service.get_warehouse(warehouse_id)
        return APIResponse(data=warehouse)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{warehouse_id}/detail", response_model=APIResponse[WarehouseDetail])
def get_warehouse_detail(
    warehouse_id: int,
    db: Session = Depends(get_db),
):
    try:
        warehouse_service, _ = create_warehouse_service(db)
        warehouse_detail = warehouse_service.get_warehouse_detail(warehouse_id)
        return APIResponse(data=warehouse_detail)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.put("/{warehouse_id}", response_model=APIResponse[Warehouse])
def update_warehouse(
    warehouse_id: int,
    warehouse_in: WarehouseUpdate,
    db: Session = Depends(get_db),
):
    try:
        warehouse_service, _ = create_warehouse_service(db)
        warehouse = warehouse_service.update_warehouse(warehouse_id, warehouse_in)
        db.commit()
        return APIResponse(data=warehouse, message="仓库更新成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.delete("/{warehouse_id}", response_model=APIResponse[SuccessResponse])
def delete_warehouse(
    warehouse_id: int,
    db: Session = Depends(get_db),
):
    try:
        warehouse_service, _ = create_warehouse_service(db)
        warehouse_service.delete_warehouse(warehouse_id)
        db.commit()
        return APIResponse(data=SuccessResponse(message="仓库删除成功"))
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{warehouse_id}/utilization", response_model=APIResponse[dict])
def get_warehouse_utilization(
    warehouse_id: int,
    db: Session = Depends(get_db),
):
    try:
        warehouse_service, _ = create_warehouse_service(db)
        utilization = warehouse_service.get_warehouse_utilization_status(warehouse_id)
        return APIResponse(data=utilization)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{warehouse_id}/inventory-overview", response_model=APIResponse[WarehouseInventoryOverview])
def get_warehouse_inventory_overview(
    warehouse_id: int,
    db: Session = Depends(get_db),
):
    try:
        warehouse_service, _ = create_warehouse_service(db)
        overview = warehouse_service.get_warehouse_inventory_overview(warehouse_id)
        return APIResponse(data=overview)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{warehouse_id}/zones", response_model=APIResponse[PaginatedResponse[Zone]])
def list_warehouse_zones(
    warehouse_id: int,
    storage_type: str | None = Query(None, description="存储类型"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        _, zone_service = create_warehouse_service(db)

        skip = (paginated.page - 1) * paginated.page_size
        zones = zone_service.list_zones(
            warehouse_id=warehouse_id,
            skip=skip,
            limit=paginated.page_size,
            storage_type=storage_type,
        )
        total = zone_service.count_zones(warehouse_id=warehouse_id)
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=zones,
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


@router.post("/{warehouse_id}/zones", response_model=APIResponse[Zone])
def create_zone(
    warehouse_id: int,
    zone_in: ZoneCreate,
    db: Session = Depends(get_db),
):
    try:
        _, zone_service = create_warehouse_service(db)
        zone_in.warehouse_id = warehouse_id
        zone = zone_service.create_zone(zone_in)
        db.commit()
        return APIResponse(data=zone, message="库区创建成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/zones/{zone_id}", response_model=APIResponse[Zone])
def get_zone(
    zone_id: int,
    db: Session = Depends(get_db),
):
    try:
        _, zone_service = create_warehouse_service(db)
        zone = zone_service.get_zone(zone_id)
        return APIResponse(data=zone)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.put("/zones/{zone_id}", response_model=APIResponse[Zone])
def update_zone(
    zone_id: int,
    zone_in: ZoneUpdate,
    db: Session = Depends(get_db),
):
    try:
        _, zone_service = create_warehouse_service(db)
        zone = zone_service.update_zone(zone_id, zone_in)
        db.commit()
        return APIResponse(data=zone, message="库区更新成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.delete("/zones/{zone_id}", response_model=APIResponse[SuccessResponse])
def delete_zone(
    zone_id: int,
    db: Session = Depends(get_db),
):
    try:
        _, zone_service = create_warehouse_service(db)
        zone_service.delete_zone(zone_id)
        db.commit()
        return APIResponse(data=SuccessResponse(message="库区删除成功"))
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/zones", response_model=APIResponse[PaginatedResponse[Zone]])
def list_all_zones(
    warehouse_id: int | None = Query(None, description="仓库ID"),
    storage_type: str | None = Query(None, description="存储类型"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        _, zone_service = create_warehouse_service(db)

        skip = (paginated.page - 1) * paginated.page_size
        zones = zone_service.list_zones(
            warehouse_id=warehouse_id,
            skip=skip,
            limit=paginated.page_size,
            storage_type=storage_type,
        )
        total = zone_service.count_zones(warehouse_id=warehouse_id)
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=zones,
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
