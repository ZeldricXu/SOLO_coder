from datetime import datetime, timedelta
from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import (
    create_access_token,
    create_refresh_token,
    decode_token,
    get_current_user,
)
from app.core.config import settings
from app.core.audit import AuditLogger
from app.schemas.auth import (
    LoginRequest,
    RegisterRequest,
    TokenResponse,
    LoginResponse,
    UserInfoResponse,
    ChangePasswordRequest,
    RefreshTokenRequest,
    UserInfo,
)
from app.schemas.user import UserCreate
from app.schemas.common import SuccessResponse
from app.services.user_service import user_service
from app.models.user import User

router = APIRouter()


@router.post("/login", response_model=LoginResponse, summary="用户登录")
async def login(
    request: Request,
    db: Session = Depends(get_db),
    *,
    login_data: LoginRequest,
):
    user = user_service.authenticate(
        db,
        username=login_data.username,
        password=login_data.password,
    )
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
        )
    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="User account is disabled",
        )

    user.last_login_at = datetime.utcnow()
    db.flush()

    audit_logger = AuditLogger(db)
    audit_logger.log_login(
        user,
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    access_token = create_access_token(
        subject=user.id,
        expires_delta=timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES),
    )
    refresh_token = create_refresh_token(subject=user.id)

    return LoginResponse(
        data=TokenResponse(
            access_token=access_token,
            refresh_token=refresh_token,
            token_type="bearer",
            expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        )
    )


@router.post("/register", response_model=LoginResponse, summary="用户注册")
async def register(
    request: Request,
    db: Session = Depends(get_db),
    *,
    register_data: RegisterRequest,
):
    user_create = UserCreate(
        username=register_data.username,
        email=register_data.email,
        password=register_data.password,
        full_name=register_data.full_name,
        phone=register_data.phone,
    )
    user = user_service.create(db, obj_in=user_create)

    audit_logger = AuditLogger(db)
    audit_logger.log_create(
        user,
        resource_type="user",
        resource_id=user.id,
        new_value={
            "id": user.id,
            "username": user.username,
            "email": user.email,
        },
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    access_token = create_access_token(
        subject=user.id,
        expires_delta=timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES),
    )
    refresh_token = create_refresh_token(subject=user.id)

    db.commit()

    return LoginResponse(
        data=TokenResponse(
            access_token=access_token,
            refresh_token=refresh_token,
            token_type="bearer",
            expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        )
    )


@router.post("/refresh", response_model=LoginResponse, summary="刷新令牌")
async def refresh_token(
    request: Request,
    db: Session = Depends(get_db),
    *,
    refresh_data: RefreshTokenRequest,
):
    token_data = decode_token(refresh_data.refresh_token)
    if token_data.token_type != "refresh":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid refresh token",
        )

    user = user_service.get_or_404(db, id=token_data.user_id)
    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="User account is disabled",
        )

    access_token = create_access_token(
        subject=user.id,
        expires_delta=timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES),
    )
    new_refresh_token = create_refresh_token(subject=user.id)

    return LoginResponse(
        data=TokenResponse(
            access_token=access_token,
            refresh_token=new_refresh_token,
            token_type="bearer",
            expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        )
    )


@router.post("/change-password", response_model=SuccessResponse, summary="修改密码")
async def change_password(
    request: Request,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
    *,
    password_data: ChangePasswordRequest,
):
    if password_data.new_password != password_data.confirm_password:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Passwords do not match",
        )

    old_data = {"user_id": current_user.id}
    user_service.change_password(
        db,
        user_id=current_user.id,
        old_password=password_data.old_password,
        new_password=password_data.new_password,
    )

    audit_logger = AuditLogger(db)
    audit_logger.log_update(
        current_user,
        resource_type="user",
        resource_id=current_user.id,
        old_value=old_data,
        new_value={"password_changed": True},
        ip_address=request.client.host if request.client else None,
        user_agent=request.headers.get("user-agent"),
    )

    db.commit()

    return SuccessResponse(message="Password changed successfully")


@router.get("/me", response_model=UserInfoResponse, summary="获取当前用户信息")
async def get_me(
    current_user: User = Depends(get_current_user),
):
    return UserInfoResponse(
        data=UserInfo.model_validate(current_user)
    )
