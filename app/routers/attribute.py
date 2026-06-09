from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import PermissionChecker, get_current_user
from app.core.audit import AuditLogger
from app.schemas.common import (
    PaginatedParams,
    SuccessResponse,
    IdResponse,
)
from app.schemas.product import (
    AttributeCreate,
    AttributeUpdate,
    Attribute,
    AttributeListResponse,
    AttributeDetailResponse,
    AttributeTemplateCreate,
    AttributeTemplateUpdate,
    AttributeTemplate,
    AttributeTemplateListResponse,
    AttributeTemplateDetailResponse,
    ApplyTemplateRequest,
)
from app.services.attribute_service import (
    attribute_service,
    attribute_template_service,
)
from app.models.user import User as UserModel
from app.models.attribute import AttributeDataType

router = APIRouter()


@router.get(
    "/attributes",
    response_model=AttributeListResponse,
    summary="获取属性列表",
    dependencies=[Depends(PermissionChecker(["attribute:list"]))],
)
async def get_attributes(
    db: Session = Depends(get_db),
    params: PaginatedParams = Depends(),
    *,
    name: Optional[str] = None,
    code: Optional[str] = None,
    data_type: Optional[AttributeDataType] = None,
    is_searchable: Optional[bool] = None,
    is_filterable: Optional[bool] = None,
):
    filters = {}
    if name:
        filters["name"] = f"%{name}%"
    if code:
        filters["code"] = f"%{code}%"
    if data_type:
        filters["data_type"] = data_type
    if is_searchable is not None:
        filters["is_searchable"] = is_searchable
    if is_filterable is not None:
        filters["is_filterable"] = is_filterable

    search_filters = []
    from app.models.attribute import Attribute as AttributeModel
    if name:
        search_filters.append(AttributeModel.name.like(f"%{name}%"))
    if code:
        search_filters.append(AttributeModel.code.like(f"%{code}%"))

    result = attribute_service.get_multi(
        db,
        page=params.page,
        page_size=params.page_size,
        sort_by=params.sort_by,
        sort_order=params.sort_order,
        filters=filters,
        search_filters=search_filters if search_filters else None,
    )

    return AttributeListResponse(data=result)


@router.get(
    "/attributes/{attribute_id}",
    response_model=AttributeDetailResponse,
    summary="获取属性详情",
    dependencies=[Depends(PermissionChecker(["attribute:read"]))],
)
async def get_attribute(
    db: Session = Depends(get_db),
    *,
    attribute_id: int,
):
    attribute = attribute_service.get_or_404(db, id=attribute_id)
    return AttributeDetailResponse(data=Attribute.model_validate(attribute))


@router.post(
    "/attributes",
    response_model=IdResponse,
    status_code=status.HTTP_201_CREATED,
    summary="创建属性",
    dependencies=[Depends(PermissionChecker(["attribute:create"]))],
)
async def create_attribute(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    attribute_data: AttributeCreate,
):
    attribute = attribute_service.create(db, obj_in=attribute_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_create(
        current_user,
        resource_type="attribute",
        resource_id=attribute.id,
        new_value=attribute_data.model_dump(),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return IdResponse(id=attribute.id)


@router.put(
    "/attributes/{attribute_id}",
    response_model=SuccessResponse,
    summary="更新属性",
    dependencies=[Depends(PermissionChecker(["attribute:update"]))],
)
async def update_attribute(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    attribute_id: int,
    attribute_data: AttributeUpdate,
):
    db_attr = attribute_service.get_or_404(db, id=attribute_id, use_cache=False)
    old_data = {c.name: getattr(db_attr, c.name) for c in db_attr.__table__.columns}

    attribute_service.update(db, db_obj=db_attr, obj_in=attribute_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="attribute",
        resource_id=attribute_id,
        old_value=old_data,
        new_value=attribute_data.model_dump(exclude_unset=True),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Attribute updated successfully")


@router.delete(
    "/attributes/{attribute_id}",
    response_model=SuccessResponse,
    summary="删除属性",
    dependencies=[Depends(PermissionChecker(["attribute:delete"]))],
)
async def delete_attribute(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    attribute_id: int,
):
    db_attr = attribute_service.get_or_404(db, id=attribute_id, use_cache=False)
    old_data = {c.name: getattr(db_attr, c.name) for c in db_attr.__table__.columns}

    attribute_service.delete(db, id=attribute_id)

    audit_logger = AuditLogger(db)
    audit_logger.log_delete(
        current_user,
        resource_type="attribute",
        resource_id=attribute_id,
        old_value=old_data,
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Attribute deleted successfully")


@router.get(
    "/templates",
    response_model=AttributeTemplateListResponse,
    summary="获取属性模板列表",
    dependencies=[Depends(PermissionChecker(["attribute_template:list"]))],
)
async def get_attribute_templates(
    db: Session = Depends(get_db),
    params: PaginatedParams = Depends(),
    *,
    name: Optional[str] = None,
):
    filters = {}
    if name:
        filters["name"] = f"%{name}%"

    search_filters = []
    from app.models.attribute import AttributeTemplate as TemplateModel
    if name:
        search_filters.append(TemplateModel.name.like(f"%{name}%"))

    result = attribute_template_service.get_multi(
        db,
        page=params.page,
        page_size=params.page_size,
        sort_by=params.sort_by,
        sort_order=params.sort_order,
        filters=filters,
        search_filters=search_filters if search_filters else None,
    )

    return AttributeTemplateListResponse(data=result)


@router.get(
    "/templates/{template_id}",
    response_model=AttributeTemplateDetailResponse,
    summary="获取属性模板详情",
    dependencies=[Depends(PermissionChecker(["attribute_template:read"]))],
)
async def get_attribute_template(
    db: Session = Depends(get_db),
    *,
    template_id: int,
):
    template = attribute_template_service.get_or_404(db, id=template_id)
    return AttributeTemplateDetailResponse(data=AttributeTemplate.model_validate(template))


@router.post(
    "/templates",
    response_model=IdResponse,
    status_code=status.HTTP_201_CREATED,
    summary="创建属性模板",
    dependencies=[Depends(PermissionChecker(["attribute_template:create"]))],
)
async def create_attribute_template(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    template_data: AttributeTemplateCreate,
):
    template = attribute_template_service.create(db, obj_in=template_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_create(
        current_user,
        resource_type="attribute_template",
        resource_id=template.id,
        new_value=template_data.model_dump(),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return IdResponse(id=template.id)


@router.put(
    "/templates/{template_id}",
    response_model=SuccessResponse,
    summary="更新属性模板",
    dependencies=[Depends(PermissionChecker(["attribute_template:update"]))],
)
async def update_attribute_template(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    template_id: int,
    template_data: AttributeTemplateUpdate,
):
    db_template = attribute_template_service.get_or_404(db, id=template_id, use_cache=False)
    old_data = {c.name: getattr(db_template, c.name) for c in db_template.__table__.columns}

    attribute_template_service.update(db, db_obj=db_template, obj_in=template_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="attribute_template",
        resource_id=template_id,
        old_value=old_data,
        new_value=template_data.model_dump(exclude_unset=True),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Attribute template updated successfully")


@router.delete(
    "/templates/{template_id}",
    response_model=SuccessResponse,
    summary="删除属性模板",
    dependencies=[Depends(PermissionChecker(["attribute_template:delete"]))],
)
async def delete_attribute_template(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    template_id: int,
):
    db_template = attribute_template_service.get_or_404(db, id=template_id, use_cache=False)
    old_data = {c.name: getattr(db_template, c.name) for c in db_template.__table__.columns}

    attribute_template_service.delete(db, id=template_id)

    audit_logger = AuditLogger(db)
    audit_logger.log_delete(
        current_user,
        resource_type="attribute_template",
        resource_id=template_id,
        old_value=old_data,
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Attribute template deleted successfully")


@router.post(
    "/templates/apply",
    response_model=SuccessResponse,
    summary="应用属性模板到商品",
    dependencies=[Depends(PermissionChecker(["attribute_template:apply", "product:update"]))],
)
async def apply_template_to_product(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    apply_data: ApplyTemplateRequest,
):
    attribute_template_service.apply_to_product(
        db,
        template_id=apply_data.template_id,
        product_id=apply_data.product_id,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="product",
        resource_id=apply_data.product_id,
        old_value={"action": "apply_template"},
        new_value={"template_id": apply_data.template_id},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Attribute template applied to product successfully")


@router.post(
    "/templates/{template_id}/inherit/{source_template_id}",
    response_model=SuccessResponse,
    summary="继承另一个属性模板的属性",
    dependencies=[Depends(PermissionChecker(["attribute_template:update"]))],
)
async def inherit_template(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    template_id: int,
    source_template_id: int,
    override_existing: bool = False,
):
    if template_id == source_template_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot inherit from self",
        )

    attribute_template_service.inherit_template(
        db,
        target_template_id=template_id,
        source_template_id=source_template_id,
        override_existing=override_existing,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="attribute_template",
        resource_id=template_id,
        old_value={"action": "inherit_template"},
        new_value={
            "source_template_id": source_template_id,
            "override_existing": override_existing,
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Template inherited successfully")
