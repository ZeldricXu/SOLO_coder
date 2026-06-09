from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select, func
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import PermissionChecker, get_current_user
from app.core.audit import AuditLogger
from app.schemas.common import (
    PaginatedParams,
    SuccessResponse,
    IdResponse,
    APIResponse,
    PaginatedResponse,
)
from app.schemas.role import (
    RoleCreate,
    RoleUpdate,
    Role,
    RoleWithUsers,
    RoleListResponse,
    RoleDetailResponse,
    PermissionCreate,
    PermissionUpdate,
    Permission,
    PermissionListResponse,
    PermissionDetailResponse,
)
from app.models.role import Role as RoleModel, role_permission
from app.models.permission import Permission as PermissionModel
from app.models.user import User as UserModel, user_role
from app.core.cache import cache

router = APIRouter()


@router.get(
    "/permissions",
    response_model=PermissionListResponse,
    summary="获取权限列表",
    dependencies=[Depends(PermissionChecker(["permission:list"]))],
)
async def get_permissions(
    db: Session = Depends(get_db),
    params: PaginatedParams = Depends(),
    *,
    name: Optional[str] = None,
    resource_type: Optional[str] = None,
    action: Optional[str] = None,
):
    stmt = select(PermissionModel)
    count_stmt = select(func.count()).select_from(PermissionModel)

    where_conditions = []
    if name:
        where_conditions.append(PermissionModel.name.like(f"%{name}%"))
    if resource_type:
        where_conditions.append(PermissionModel.resource_type == resource_type)
    if action:
        where_conditions.append(PermissionModel.action == action)

    if where_conditions:
        from sqlalchemy import and_
        condition = and_(*where_conditions)
        stmt = stmt.where(condition)
        count_stmt = count_stmt.where(condition)

    total = db.execute(count_stmt).scalar_one() or 0

    from sqlalchemy import desc, asc
    if params.sort_by and hasattr(PermissionModel, params.sort_by):
        sort_column = getattr(PermissionModel, params.sort_by)
        stmt = stmt.order_by(desc(sort_column) if params.sort_order == "desc" else asc(sort_column))
    else:
        stmt = stmt.order_by(desc(PermissionModel.id))

    offset = (params.page - 1) * params.page_size
    stmt = stmt.offset(offset).limit(params.page_size)

    items = list(db.execute(stmt).scalars().all())
    total_pages = (total + params.page_size - 1) // params.page_size

    result = PaginatedResponse(
        items=items,
        page=params.page,
        page_size=params.page_size,
        total=total,
        total_pages=total_pages,
        has_next=params.page < total_pages,
        has_prev=params.page > 1,
    )

    return PermissionListResponse(data=result)


@router.get(
    "/permissions/{permission_id}",
    response_model=PermissionDetailResponse,
    summary="获取权限详情",
    dependencies=[Depends(PermissionChecker(["permission:read"]))],
)
async def get_permission(
    db: Session = Depends(get_db),
    *,
    permission_id: int,
):
    permission = db.execute(
        select(PermissionModel).where(PermissionModel.id == permission_id)
    ).scalar_one_or_none()

    if not permission:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Permission with id {permission_id} not found",
        )

    return PermissionDetailResponse(data=Permission.model_validate(permission))


@router.post(
    "/permissions",
    response_model=IdResponse,
    status_code=status.HTTP_201_CREATED,
    summary="创建权限",
    dependencies=[Depends(PermissionChecker(["permission:create"]))],
)
async def create_permission(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    permission_data: PermissionCreate,
):
    existing = db.execute(
        select(PermissionModel).where(
            (PermissionModel.code == permission_data.code) |
            ((PermissionModel.resource_type == permission_data.resource_type) &
             (PermissionModel.action == permission_data.action))
        )
    ).scalar_one_or_none()

    if existing:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Permission code or resource_type+action combination already exists",
        )

    db_permission = PermissionModel(**permission_data.model_dump())
    db.add(db_permission)
    db.flush()
    db.refresh(db_permission)

    audit_logger = AuditLogger(db)
    audit_logger.log_create(
        current_user,
        resource_type="permission",
        resource_id=db_permission.id,
        new_value=permission_data.model_dump(),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return IdResponse(id=db_permission.id)


@router.put(
    "/permissions/{permission_id}",
    response_model=SuccessResponse,
    summary="更新权限",
    dependencies=[Depends(PermissionChecker(["permission:update"]))],
)
async def update_permission(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    permission_id: int,
    permission_data: PermissionUpdate,
):
    db_permission = db.execute(
        select(PermissionModel).where(PermissionModel.id == permission_id)
    ).scalar_one_or_none()

    if not db_permission:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Permission with id {permission_id} not found",
        )

    old_data = {c.name: getattr(db_permission, c.name) for c in db_permission.__table__.columns}
    update_data = permission_data.model_dump(exclude_unset=True)

    for field, value in update_data.items():
        if hasattr(db_permission, field):
            setattr(db_permission, field, value)

    db.flush()
    db.refresh(db_permission)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="permission",
        resource_id=permission_id,
        old_value=old_data,
        new_value=update_data,
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    cache.delete_pattern("role:*")
    db.commit()

    return SuccessResponse(message="Permission updated successfully")


@router.get(
    "/",
    response_model=RoleListResponse,
    summary="获取角色列表",
    dependencies=[Depends(PermissionChecker(["role:list"]))],
)
async def get_roles(
    db: Session = Depends(get_db),
    params: PaginatedParams = Depends(),
    *,
    name: Optional[str] = None,
    code: Optional[str] = None,
    is_active: Optional[bool] = None,
):
    stmt = select(RoleModel, func.count(user_role.c.user_id).label("user_count")).outerjoin(
        user_role, RoleModel.id == user_role.c.role_id
    ).group_by(RoleModel.id)
    count_stmt = select(func.count()).select_from(RoleModel)

    where_conditions = []
    if name:
        where_conditions.append(RoleModel.name.like(f"%{name}%"))
    if code:
        where_conditions.append(RoleModel.code.like(f"%{code}%"))
    if is_active is not None:
        where_conditions.append(RoleModel.is_active == is_active)

    if where_conditions:
        from sqlalchemy import and_
        condition = and_(*where_conditions)
        stmt = stmt.where(condition)
        count_stmt = count_stmt.where(condition)

    total = db.execute(count_stmt).scalar_one() or 0

    from sqlalchemy import desc, asc
    if params.sort_by and hasattr(RoleModel, params.sort_by):
        sort_column = getattr(RoleModel, params.sort_by)
        stmt = stmt.order_by(desc(sort_column) if params.sort_order == "desc" else asc(sort_column))
    else:
        stmt = stmt.order_by(desc(RoleModel.id))

    offset = (params.page - 1) * params.page_size
    stmt = stmt.offset(offset).limit(params.page_size)

    results = db.execute(stmt).all()
    items = []
    for role, user_count in results:
        role_dict = {c.name: getattr(role, c.name) for c in role.__table__.columns}
        role_dict["user_count"] = user_count
        items.append(RoleWithUsers.model_validate(role_dict))

    total_pages = (total + params.page_size - 1) // params.page_size

    result = PaginatedResponse(
        items=items,
        page=params.page,
        page_size=params.page_size,
        total=total,
        total_pages=total_pages,
        has_next=params.page < total_pages,
        has_prev=params.page > 1,
    )

    return RoleListResponse(data=result)


@router.get(
    "/{role_id}",
    response_model=RoleDetailResponse,
    summary="获取角色详情",
    dependencies=[Depends(PermissionChecker(["role:read"]))],
)
async def get_role(
    db: Session = Depends(get_db),
    *,
    role_id: int,
):
    role = db.execute(
        select(RoleModel).where(RoleModel.id == role_id)
    ).scalar_one_or_none()

    if not role:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Role with id {role_id} not found",
        )

    return RoleDetailResponse(data=Role.model_validate(role))


@router.post(
    "/",
    response_model=IdResponse,
    status_code=status.HTTP_201_CREATED,
    summary="创建角色",
    dependencies=[Depends(PermissionChecker(["role:create"]))],
)
async def create_role(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    role_data: RoleCreate,
):
    existing = db.execute(
        select(RoleModel).where(RoleModel.code == role_data.code)
    ).scalar_one_or_none()

    if existing:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Role code {role_data.code} already exists",
        )

    role_dict = role_data.model_dump(exclude={"permission_ids"})
    db_role = RoleModel(**role_dict)

    if role_data.permission_ids:
        permissions = db.execute(
            select(PermissionModel).where(PermissionModel.id.in_(role_data.permission_ids))
        ).scalars().all()
        db_role.permissions = permissions

    db.add(db_role)
    db.flush()
    db.refresh(db_role)

    audit_logger = AuditLogger(db)
    audit_logger.log_create(
        current_user,
        resource_type="role",
        resource_id=db_role.id,
        new_value={
            **role_dict,
            "permission_ids": role_data.permission_ids,
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return IdResponse(id=db_role.id)


@router.put(
    "/{role_id}",
    response_model=SuccessResponse,
    summary="更新角色",
    dependencies=[Depends(PermissionChecker(["role:update"]))],
)
async def update_role(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    role_id: int,
    role_data: RoleUpdate,
):
    db_role = db.execute(
        select(RoleModel).where(RoleModel.id == role_id)
    ).scalar_one_or_none()

    if not db_role:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Role with id {role_id} not found",
        )

    old_data = {c.name: getattr(db_role, c.name) for c in db_role.__table__.columns}
    update_data = role_data.model_dump(exclude_unset=True)

    if "permission_ids" in update_data:
        permission_ids = update_data.pop("permission_ids")
        if permission_ids is not None:
            permissions = db.execute(
                select(PermissionModel).where(PermissionModel.id.in_(permission_ids))
            ).scalars().all()
            db_role.permissions = permissions

    for field, value in update_data.items():
        if hasattr(db_role, field):
            setattr(db_role, field, value)

    db.flush()
    db.refresh(db_role)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="role",
        resource_id=role_id,
        old_value=old_data,
        new_value=role_data.model_dump(exclude_unset=True),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    cache.delete_pattern("role:*")
    cache.delete_pattern("user:*")
    db.commit()

    return SuccessResponse(message="Role updated successfully")


@router.delete(
    "/{role_id}",
    response_model=SuccessResponse,
    summary="删除角色",
    dependencies=[Depends(PermissionChecker(["role:delete"]))],
)
async def delete_role(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    role_id: int,
):
    db_role = db.execute(
        select(RoleModel).where(RoleModel.id == role_id)
    ).scalar_one_or_none()

    if not db_role:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Role with id {role_id} not found",
        )

    user_count = db.execute(
        select(func.count()).select_from(user_role).where(user_role.c.role_id == role_id)
    ).scalar_one() or 0

    if user_count > 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Cannot delete role with {user_count} users assigned",
        )

    old_data = {c.name: getattr(db_role, c.name) for c in db_role.__table__.columns}

    db.execute(role_permission.delete().where(role_permission.c.role_id == role_id))
    db.delete(db_role)
    db.flush()

    audit_logger = AuditLogger(db)
    audit_logger.log_delete(
        current_user,
        resource_type="role",
        resource_id=role_id,
        old_value=old_data,
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    cache.delete_pattern("role:*")
    cache.delete_pattern("user:*")
    db.commit()

    return SuccessResponse(message="Role deleted successfully")


@router.post(
    "/{role_id}/permissions",
    response_model=SuccessResponse,
    summary="分配权限给角色",
    dependencies=[Depends(PermissionChecker(["role:assign_permissions"]))],
)
async def assign_permissions(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    role_id: int,
    permission_ids: list[int],
):
    db_role = db.execute(
        select(RoleModel).where(RoleModel.id == role_id)
    ).scalar_one_or_none()

    if not db_role:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Role with id {role_id} not found",
        )

    permissions = db.execute(
        select(PermissionModel).where(PermissionModel.id.in_(permission_ids))
    ).scalars().all()

    if len(permissions) != len(permission_ids):
        found_ids = {p.id for p in permissions}
        missing_ids = set(permission_ids) - found_ids
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Permissions not found: {missing_ids}",
        )

    db_role.permissions = permissions
    db.flush()
    db.refresh(db_role)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="role",
        resource_id=role_id,
        old_value={"action": "assign_permissions"},
        new_value={"permission_ids": permission_ids},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    cache.delete_pattern("role:*")
    cache.delete_pattern("user:*")
    db.commit()

    return SuccessResponse(message="Permissions assigned successfully")


@router.get(
    "/{role_id}/permissions",
    response_model=APIResponse[list[Permission]],
    summary="获取角色的权限列表",
    dependencies=[Depends(PermissionChecker(["role:read"]))],
)
async def get_role_permissions(
    db: Session = Depends(get_db),
    *,
    role_id: int,
):
    db_role = db.execute(
        select(RoleModel).where(RoleModel.id == role_id)
    ).scalar_one_or_none()

    if not db_role:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Role with id {role_id} not found",
        )

    return APIResponse(data=db_role.permissions)
