from __future__ import annotations
from fastapi import APIRouter, Depends, HTTPException, Query, Body
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.schemas.common import (
    APIResponse,
    PaginatedResponse,
    PaginatedParams,
    SuccessResponse,
    BulkOperationRequest,
    BulkOperationResponse,
)
from app.schemas.warehouse import (
    Supplier,
    SupplierCreate,
    SupplierUpdate,
)
from app.services.supplier_service import create_supplier_service
from app.utils.exceptions import InventoryException

router = APIRouter(prefix="/api/v1/suppliers", tags=["供应商管理"])


@router.get("", response_model=APIResponse[PaginatedResponse[Supplier]])
def list_suppliers(
    is_active: bool | None = Query(None, description="是否启用"),
    city: str | None = Query(None, description="城市"),
    credit_rating: str | None = Query(None, description="信用等级"),
    search: str | None = Query(None, description="搜索关键词"),
    paginated: PaginatedParams = Depends(),
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)

        skip = (paginated.page - 1) * paginated.page_size
        suppliers = supplier_service.list_suppliers(
            skip=skip,
            limit=paginated.page_size,
            is_active=is_active,
            city=city,
            credit_rating=credit_rating,
            search=search,
        )
        total = supplier_service.count_suppliers(
            is_active=is_active,
            city=city,
            credit_rating=credit_rating,
            search=search,
        )
        total_pages = (total + paginated.page_size - 1) // paginated.page_size

        return APIResponse(
            data=PaginatedResponse(
                items=suppliers,
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


@router.post("", response_model=APIResponse[Supplier])
def create_supplier(
    supplier_in: SupplierCreate,
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        supplier = supplier_service.create_supplier(supplier_in)
        db.commit()
        return APIResponse(data=supplier, message="供应商创建成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/stats", response_model=APIResponse[dict])
def get_supplier_stats(
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        stats = supplier_service.get_supplier_stats()
        return APIResponse(data=stats)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/{supplier_id}", response_model=APIResponse[Supplier])
def get_supplier(
    supplier_id: int,
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        supplier = supplier_service.get_supplier(supplier_id)
        return APIResponse(data=supplier)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.put("/{supplier_id}", response_model=APIResponse[Supplier])
def update_supplier(
    supplier_id: int,
    supplier_in: SupplierUpdate,
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        supplier = supplier_service.update_supplier(supplier_id, supplier_in)
        db.commit()
        return APIResponse(data=supplier, message="供应商更新成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.delete("/{supplier_id}", response_model=APIResponse[SuccessResponse])
def delete_supplier(
    supplier_id: int,
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        supplier_service.delete_supplier(supplier_id)
        db.commit()
        return APIResponse(data=SuccessResponse(message="供应商删除成功"))
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.patch("/{supplier_id}/credit-rating", response_model=APIResponse[Supplier])
def set_credit_rating(
    supplier_id: int,
    rating: str = Body(..., description="信用等级"),
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        supplier = supplier_service.set_credit_rating(supplier_id, rating)
        db.commit()
        return APIResponse(data=supplier, message="信用等级设置成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.patch("/{supplier_id}/payment-terms", response_model=APIResponse[Supplier])
def set_payment_terms(
    supplier_id: int,
    terms: str = Body(..., description="付款条款"),
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        supplier = supplier_service.set_payment_terms(supplier_id, terms)
        db.commit()
        return APIResponse(data=supplier, message="付款条款设置成功")
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/filter/rating", response_model=APIResponse[list[Supplier]])
def get_suppliers_by_rating(
    min_rating: str = Query(..., description="最低信用等级"),
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        suppliers = supplier_service.get_suppliers_by_rating(min_rating)
        return APIResponse(data=suppliers)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.get("/filter/lead-time", response_model=APIResponse[list[Supplier]])
def get_suppliers_by_lead_time(
    max_days: int = Query(..., description="最大交货周期(天)"),
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        suppliers = supplier_service.get_suppliers_by_lead_time(max_days)
        return APIResponse(data=suppliers)
    except InventoryException as e:
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/bulk/activate", response_model=APIResponse[BulkOperationResponse])
def bulk_activate_suppliers(
    request: BulkOperationRequest,
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        count = supplier_service.bulk_activate_suppliers(request.ids)
        db.commit()
        return APIResponse(
            data=BulkOperationResponse(
                success_count=count,
                failed_count=len(request.ids) - count,
                failed_ids=[],
                errors=[],
            ),
            message=f"成功激活 {count} 个供应商",
        )
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e


@router.post("/bulk/deactivate", response_model=APIResponse[BulkOperationResponse])
def bulk_deactivate_suppliers(
    request: BulkOperationRequest,
    db: Session = Depends(get_db),
):
    try:
        supplier_service = create_supplier_service(db)
        count = supplier_service.bulk_deactivate_suppliers(request.ids)
        db.commit()
        return APIResponse(
            data=BulkOperationResponse(
                success_count=count,
                failed_count=len(request.ids) - count,
                failed_ids=[],
                errors=[],
            ),
            message=f"成功停用 {count} 个供应商",
        )
    except InventoryException as e:
        db.rollback()
        raise HTTPException(status_code=e.code, detail=e.message) from e
