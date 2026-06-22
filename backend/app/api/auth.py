from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordRequestForm
from sqlalchemy.orm import Session
from datetime import timedelta
from app.core.database import get_db
from app.core.security import (
    verify_password, create_access_token, get_password_hash,
    get_current_active_user, require_admin
)
from app.core.config import get_settings
from app.models import models
from app.schemas import schemas

router = APIRouter(prefix="/api/auth", tags=["认证"])
settings = get_settings()


@router.post("/register", response_model=schemas.UserResponse)
def register(user_data: schemas.UserCreate, db: Session = Depends(get_db)):
    existing = db.query(models.User).filter(
        (models.User.username == user_data.username) |
        (models.User.email == user_data.email)
    ).first()
    if existing:
        raise HTTPException(status_code=400, detail="用户名或邮箱已存在")

    user_count = db.query(models.User).count()
    role = "super_admin" if user_count == 0 else user_data.role

    user = models.User(
        username=user_data.username,
        email=user_data.email,
        full_name=user_data.full_name,
        hashed_password=get_password_hash(user_data.password),
        role=role,
        team_id=user_data.team_id,
        wecom_userid=user_data.wecom_userid,
        feishu_open_id=user_data.feishu_open_id
    )
    db.add(user)
    db.commit()
    db.refresh(user)

    team_name = user.team.name if user.team else None
    return schemas.UserResponse(
        id=user.id,
        username=user.username,
        email=user.email,
        full_name=user.full_name,
        team_id=user.team_id,
        role=user.role,
        wecom_userid=user.wecom_userid,
        feishu_open_id=user.feishu_open_id,
        is_active=user.is_active,
        created_at=user.created_at,
        team_name=team_name
    )


@router.post("/login", response_model=schemas.Token)
def login(form_data: OAuth2PasswordRequestForm = Depends(), db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.username == form_data.username).first()
    if not user or not verify_password(form_data.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="用户名或密码错误",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if not user.is_active:
        raise HTTPException(status_code=400, detail="用户已被禁用")

    access_token_expires = timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    access_token = create_access_token(
        data={"sub": str(user.id)}, expires_delta=access_token_expires
    )

    team_name = user.team.name if user.team else None
    return schemas.Token(
        access_token=access_token,
        user=schemas.UserResponse(
            id=user.id,
            username=user.username,
            email=user.email,
            full_name=user.full_name,
            team_id=user.team_id,
            role=user.role,
            wecom_userid=user.wecom_userid,
            feishu_open_id=user.feishu_open_id,
            is_active=user.is_active,
            created_at=user.created_at,
            team_name=team_name
        )
    )


@router.get("/me", response_model=schemas.UserResponse)
def get_me(current_user: models.User = Depends(get_current_active_user)):
    team_name = current_user.team.name if current_user.team else None
    return schemas.UserResponse(
        id=current_user.id,
        username=current_user.username,
        email=current_user.email,
        full_name=current_user.full_name,
        team_id=current_user.team_id,
        role=current_user.role,
        wecom_userid=current_user.wecom_userid,
        feishu_open_id=current_user.feishu_open_id,
        is_active=current_user.is_active,
        created_at=current_user.created_at,
        team_name=team_name
    )
