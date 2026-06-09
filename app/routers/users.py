from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import PermissionChecker, get_current_user
from app.core.audit import AuditLogger
from app.schemas.common import (
    PaginatedParams,
    SuccessResponse,
    BulkOperationRequest,
    BulkOperationResponse,
    IdResponse,
)
from app.schemas.user import (
    UserCreate,
    UserUpdate,
    User,
    UserDetailResponse,
    UserListResponse,
    AssignRolesRequest,
    ResetPasswordRequest,
    ToggleUserStatusRequest,
)
from app.services.user_service import user_service
from app.models.user import User as UserModel

router = APIRouter()


@router.get(
    "/",
    response_model=UserListResponse,
    summary="获取用户列表",
    dependencies=[Depends(PermissionChecker(["user:list"]))],
)
async def get_users(
    db: Session = Depends(get_db),
    params: PaginatedParams = Depends(),
    *,
    username: Optional[str] = None,
    email: Optional[str] = None,
    is_active: Optional[bool] = None,
    role_id: Optional[int] = None,
):
    filters = {}
    if username:
        filters["username"] = f"%{username}%"
    if email:
        filters["email"] = f"%{email}%"
    if is_active is not None:
        filters["is_active"] = is_active

    search_filters = []
    if username:
        search_filters.append(UserModel.username.like(f"%{username}%"))
    if email:
        search_filters.append(UserModel.email.like(f"%{email}%"))

    result = user_service.get_with_role_count(
        db,
        page=params.page,
        page_size=params.page_size,
        sort_by=params.sort_by,
        sort_order=params.sort_order,
        filters=filters,
        search_filters=search_filters if search_filters else None,
    )

    return UserListResponse(data=result)


@router.get(
    "/{user_id}",
    response_model=UserDetailResponse,
    summary="获取用户详情",
    dependencies=[Depends(PermissionChecker(["user:read"]))],
)
async def get_user(
    db: Session = Depends(get_db),
    *,
    user_id: int,
):
    user = user_service.get_or_404(db, id=user_id)
    return UserDetailResponse(data=User.model_validate(user))


@router.post(
    "/",
    response_model=IdResponse,
    status_code=status.HTTP_201_CREATED,
    summary="创建用户",
    dependencies=[Depends(PermissionChecker(["user:create"]))],
)
async def create_user(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    user_data: UserCreate,
):
    user = user_service.create(db, obj_in=user_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_create(
        current_user,
        resource_type="user",
        resource_id=user.id,
        new_value={
            "id": user.id,
            "username": user.username,
            "email": user.email,
            "role_ids": user_data.role_ids,
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return IdResponse(id=user.id)


@router.put(
    "/{user_id}",
    response_model=SuccessResponse,
    summary="更新用户",
    dependencies=[Depends(PermissionChecker(["user:update"]))],
)
async def update_user(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    user_id: int,
    user_data: UserUpdate,
):
    db_user = user_service.get_or_404(db, id=user_id, use_cache=False)
    old_data = {c.name: getattr(db_user, c.name) for c in db_user.__table__.columns}

    user_service.update(db, db_obj=db_user, obj_in=user_data)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="user",
        resource_id=user_id,
        old_value=old_data,
        new_value=user_data.model_dump(exclude_unset=True),
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="User updated successfully")


@router.delete(
    "/{user_id}",
    response_model=SuccessResponse,
    summary="删除用户",
    dependencies=[Depends(PermissionChecker(["user:delete"]))],
)
async def delete_user(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    user_id: int,
):
    if user_id == current_user.id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot delete yourself",
        )

    db_user = user_service.get_or_404(db, id=user_id, use_cache=False)
    old_data = {c.name: getattr(db_user, c.name) for c in db_user.__table__.columns}

    user_service.delete(db, id=user_id, soft_delete=True)

    audit_logger = AuditLogger(db)
    audit_logger.log_delete(
        current_user,
        resource_type="user",
        resource_id=user_id,
        old_value=old_data,
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="User deleted successfully")


@router.post(
    "/batch",
    response_model=BulkOperationResponse,
    summary="批量操作用户",
    dependencies=[Depends(PermissionChecker(["user:delete", "user:update"]))],
)
async def batch_operate_users(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    operation: BulkOperationRequest,
):
    if current_user.id in operation.ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot perform operation on yourself",
        )

    result = {"success_count": 0, "failed_count": 0, "failed_ids": [], "errors": []}

    if operation.action == "delete":
        result = user_service.bulk_delete(db, ids=operation.ids, soft_delete=True)
    elif operation.action == "activate":
        for user_id in operation.ids:
            try:
                user_service.toggle_status(db, user_id=user_id, is_active=True)
                result["success_count"] += 1
            except Exception as e:
                result["failed_count"] += 1
                result["failed_ids"].append(user_id)
                result["errors"].append({"id": user_id, "error": str(e)})
    elif operation.action == "deactivate":
        for user_id in operation.ids:
            try:
                user_service.toggle_status(db, user_id=user_id, is_active=False)
                result["success_count"] += 1
            except Exception as e:
                result["failed_count"] += 1
                result["failed_ids"].append(user_id)
                result["errors"].append({"id": user_id, "error": str(e)})
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unsupported action: {operation.action}",
        )

    audit_logger = AuditLogger(db)
    audit_logger.log(
        user_id=current_user.id,
        action=f"batch_{operation.action}",
        resource_type="user",
        new_value={"ids": operation.ids},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return BulkOperationResponse(**result)


@router.post(
    "/{user_id}/roles",
    response_model=SuccessResponse,
    summary="分配用户角色",
    dependencies=[Depends(PermissionChecker(["user:assign_roles"]))],
)
async def assign_roles(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    user_id: int,
    role_data: AssignRolesRequest,
):
    user_service.assign_roles(db, user_id=user_id, role_ids=role_data.role_ids)

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="user",
        resource_id=user_id,
        old_value={"action": "assign_roles"},
        new_value={"role_ids": role_data.role_ids},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Roles assigned successfully")


@router.post(
    "/{user_id}/reset-password",
    response_model=SuccessResponse,
    summary="重置用户密码",
    dependencies=[Depends(PermissionChecker(["user:reset_password"]))],
)
async def reset_password(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    user_id: int,
    password_data: ResetPasswordRequest,
):
    user_service.reset_password(
        db,
        user_id=user_id,
        new_password=password_data.new_password,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="user",
        resource_id=user_id,
        old_value={"action": "reset_password"},
        new_value={"password_reset": True},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Password reset successfully")


@router.post(
    "/{user_id}/toggle-status",
    response_model=SuccessResponse,
    summary="切换用户状态",
    dependencies=[Depends(PermissionChecker(["user:update"]))],
)
async def toggle_user_status(
    request: Request,
    db: Session = Depends(get_db),
    current_user: UserModel = Depends(get_current_user),
    *,
    user_id: int,
    status_data: ToggleUserStatusRequest,
):
    if user_id == current_user.id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot change your own status",
        )

    user_service.toggle_status(
        db,
        user_id=user_id,
        is_active=status_data.is_active,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="user",
        resource_id=user_id,
        old_value={"action": "toggle_status"},
        new_value={"is_active": status_data.is_active},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="User status updated successfully")
