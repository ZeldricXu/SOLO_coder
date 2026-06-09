from typing import Optional, Any
from fastapi import APIRouter, Depends, HTTPException, Request, status, Path
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import PermissionChecker, get_current_user
from app.core.audit import AuditLogger
from app.schemas.common import (
    PaginatedParams,
    SuccessResponse,
    IdResponse,
    APIResponse,
)
from app.schemas.product import (
    SkuCreate,
    SkuUpdate,
    Sku,
    SkuDetail,
    SkuListResponse,
    SkuDetailResponse,
    SkuGenerateRequest,
    SkuGenerateResponse,
    SkuGenerateResult,
    SkuBatchUpdateRequest,
    SkuBatchUpdateResponse,
    SkuLifecycleTransitionResponse,
)
from app.services.sku_service import sku_service, SkuLifecycleStatus
from app.models.user import User as UserModel
from app.models.sku import SkuStatus

router = APIRouter()


@router.get(
    "/",
    response_model=SkuListResponse,
    summary="获取SKU列表",
    dependencies=[Depends(PermissionChecker(["sku:list"]))],
)
async def get_skus(
    db: Session = Depends(get_db),
    params: PaginatedParams = Depends(),
    *,
    sku_code: Optional[str] = None,
    product_id: Optional[int] = None,
    product_name: Optional[str] = None,
    status: Optional[SkuStatus] = None,
    lifecycle_status: Optional[SkuLifecycleStatus] = None,
    min_price: Optional[float] = None,
    max_price: Optional[float] = None,
):
    filters: dict[str, Any] = {}
    if sku_code:
        filters["sku_code"] = f"%{sku_code}%"
    if product_id:
        filters["product_id"] = product_id
    if product_name:
        filters["name"] = f"%{product_name}%"
    if status:
        filters["status"] = status
    if lifecycle_status:
        filters["lifecycle_status"] = lifecycle_status

    search_filters = []
    if sku_code:
        from app.models.sku import SKU
        search_filters.append(SKU.sku_code.like(f"%{sku_code}%"))
    if product_name:
        from app.models.product import Product
        search_filters.append(Product.name.like(f"%{product_name}%"))

    result = sku_service.get_list_with_details(
        db,
        page=params.page,
        page_size=params.page_size,
        sort_by=params.sort_by,
        sort_order=params.sort_order,
        filters=filters,
        search_filters=search_filters if search_filters else None,
    )

    return SkuListResponse(data=result)


@router.get(
    "/{sku_id}",
    response_model=SkuDetailResponse,
    summary="获取SKU详情",
    dependencies=[Depends(PermissionChecker(["sku:read"]))],
)
async def get_sku(
    db: Session = Depends(get_db),
    *,
    sku_id: int,
):
    sku_data = sku_service.get_with_details(db, id=sku_id)
    return SkuDetailResponse(data=SkuDetail.model_validate(sku_data))


@router.post(
    "/",
    response_model=IdResponse,
    status_code=status.HTTP_201_CREATED,
    summary="创建SKU",
    dependencies=[Depends(PermissionChecker(["sku:create"]))],
)
async def create_sku(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    sku_data: SkuCreate,
):
    from app.models.sku import SKU
    existing = db.execute(
        select(SKU).where(SKU.sku_code == sku_data.sku_code)
    ).scalar_one_or_none()

    if existing:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"SKU code {sku_data.sku_code} already exists",
        )

    sku = sku_service.create(db, obj_in=sku_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_create(
        current_user,
        resource_type="sku",
        resource_id=sku.id,
        new_value=sku_data.model_dump(),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return IdResponse(id=sku.id)


@router.put(
    "/{sku_id}",
    response_model=SuccessResponse,
    summary="更新SKU",
    dependencies=[Depends(PermissionChecker(["sku:update"]))],
)
async def update_sku(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    sku_id: int,
    sku_data: SkuUpdate,
):
    db_sku = sku_service.get_or_404(db, id=sku_id, use_cache=False)
    old_data = {c.name: getattr(db_sku, c.name) for c in db_sku.__table__.columns}

    sku_service.update(db, db_obj=db_sku, obj_in=sku_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="sku",
        resource_id=sku_id,
        old_value=old_data,
        new_value=sku_data.model_dump(exclude_unset=True),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="SKU updated successfully")


@router.delete(
    "/{sku_id}",
    response_model=SuccessResponse,
    summary="删除SKU",
    dependencies=[Depends(PermissionChecker(["sku:delete"]))],
)
async def delete_sku(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    sku_id: int,
):
    db_sku = sku_service.get_or_404(db, id=sku_id, use_cache=False)
    old_data = {c.name: getattr(db_sku, c.name) for c in db_sku.__table__.columns}

    sku_service.delete(db, id=sku_id)

    audit_logger = AuditLogger(db)
    audit_logger.log_delete(
        current_user,
        resource_type="sku",
        resource_id=sku_id,
        old_value=old_data,
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="SKU deleted successfully")


@router.post(
    "/generate",
    response_model=SkuGenerateResponse,
    summary="根据属性组合批量生成SKU",
    dependencies=[Depends(PermissionChecker(["sku:generate"]))],
)
async def generate_skus(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    generate_data: SkuGenerateRequest,
):
    result = sku_service.generate_skus(db, request=generate_data)

    audit_logger = AuditLogger(db)
    audit_logger.log(
        user_id=current_user.id,
        action="generate_skus",
        resource_type="sku",
        resource_id=generate_data.product_id,
        new_value={
            "product_id": generate_data.product_id,
            "attributes": [a.model_dump() for a in generate_data.attributes],
            "generated_count": result["success_count"],
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SkuGenerateResponse(
        data=SkuGenerateResult(
            success_count=result["success_count"],
            failed_count=result["failed_count"],
            generated_skus=result["generated_skus"],
            errors=result["errors"],
        )
    )


@router.post(
    "/{sku_id}/lifecycle/{status}",
    response_model=SkuLifecycleTransitionResponse,
    summary="SKU生命周期状态流转",
    dependencies=[Depends(PermissionChecker(["sku:update"]))],
)
async def transition_lifecycle(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    sku_id: int,
    status: SkuLifecycleStatus = Path(..., description="目标生命周期状态"),
):
    old_sku = sku_service.get_or_404(db, id=sku_id, use_cache=False)
    old_status = old_sku.lifecycle_status

    sku = sku_service.transition_lifecycle(
        db,
        sku_id=sku_id,
        target_status=status,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="sku",
        resource_id=sku_id,
        old_value={"lifecycle_status": old_status.value},
        new_value={"lifecycle_status": status.value},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SkuLifecycleTransitionResponse(data=Sku.model_validate(sku))


@router.get(
    "/lifecycle/transitions",
    response_model=APIResponse[dict],
    summary="获取生命周期状态转换规则",
    dependencies=[Depends(PermissionChecker(["sku:read"]))],
)
async def get_lifecycle_transitions():
    transitions = sku_service.get_lifecycle_transitions()
    return APIResponse(data=transitions)


@router.post(
    "/batch-update",
    response_model=SkuBatchUpdateResponse,
    summary="批量更新SKU",
    dependencies=[Depends(PermissionChecker(["sku:update"]))],
)
async def batch_update_skus(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    batch_data: SkuBatchUpdateRequest,
):
    update_items = [item.model_dump(exclude_unset=True) for item in batch_data.items]
    result = sku_service.batch_update(db, items=update_items)

    audit_logger = AuditLogger(db)
    audit_logger.log(
        user_id=current_user.id,
        action="batch_update_skus",
        resource_type="sku",
        new_value={
            "items_count": len(batch_data.items),
            "success_count": result["success_count"],
            "failed_count": result["failed_count"],
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SkuBatchUpdateResponse(
        message=f"Batch update completed: {result['success_count']} success, {result['failed_count']} failed",
        data=result,
    )


@router.post(
    "/{sku_id}/apply-template/{template_id}",
    response_model=SuccessResponse,
    summary="应用属性模板到SKU",
    dependencies=[Depends(PermissionChecker(["sku:update", "attribute_template:apply"]))],
)
async def apply_template_to_sku(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    sku_id: int,
    template_id: int,
):
    sku_service.apply_attribute_template(
        db,
        sku_id=sku_id,
        template_id=template_id,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="sku",
        resource_id=sku_id,
        old_value={"action": "apply_template"},
        new_value={"template_id": template_id},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Attribute template applied successfully")
