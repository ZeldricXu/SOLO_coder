from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import Optional, List
from app.core.database import get_db
from app.core.security import get_password_hash, get_current_active_user, require_admin
from app.models import models
from app.schemas import schemas

router = APIRouter(prefix="/api/users", tags=["用户管理"])


@router.get("", response_model=List[schemas.UserResponse])
def list_users(
    team_id: Optional[int] = None,
    role: Optional[str] = None,
    keyword: Optional[str] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    query = db.query(models.User)
    if team_id:
        query = query.filter(models.User.team_id == team_id)
    if role:
        query = query.filter(models.User.role == role)
    if keyword:
        like = f"%{keyword}%"
        query = query.filter(
            (models.User.username.like(like)) |
            (models.User.full_name.like(like)) |
            (models.User.email.like(like))
        )
    users = query.order_by(models.User.id.asc()).all()

    result = []
    for user in users:
        team_name = user.team.name if user.team else None
        result.append(schemas.UserResponse(
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
        ))
    return result


@router.get("/{user_id}", response_model=schemas.UserResponse)
def get_user(
    user_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
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


@router.post("", response_model=schemas.UserResponse)
def create_user(
    user_data: schemas.UserCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    existing = db.query(models.User).filter(
        (models.User.username == user_data.username) |
        (models.User.email == user_data.email)
    ).first()
    if existing:
        raise HTTPException(status_code=400, detail="用户名或邮箱已存在")

    user = models.User(
        username=user_data.username,
        email=user_data.email,
        full_name=user_data.full_name,
        hashed_password=get_password_hash(user_data.password),
        role=user_data.role,
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


@router.put("/{user_id}", response_model=schemas.UserResponse)
def update_user(
    user_id: int,
    user_data: schemas.UserUpdate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")

    update_data = user_data.model_dump(exclude_unset=True)
    if "password" in update_data and update_data["password"]:
        update_data["hashed_password"] = get_password_hash(update_data.pop("password"))

    for field, value in update_data.items():
        if value is not None:
            setattr(user, field, value)

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


@router.delete("/{user_id}")
def delete_user(
    user_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    if user_id == current_user.id:
        raise HTTPException(status_code=400, detail="不能删除自己")
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    db.delete(user)
    db.commit()
    return {"message": "删除成功"}


@router.get("/{user_id}/team_members", response_model=List[schemas.UserResponse])
def get_team_members(
    user_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    if not user.team_id:
        return []

    members = db.query(models.User).filter(models.User.team_id == user.team_id).all()
    result = []
    for m in members:
        team_name = m.team.name if m.team else None
        result.append(schemas.UserResponse(
            id=m.id,
            username=m.username,
            email=m.email,
            full_name=m.full_name,
            team_id=m.team_id,
            role=m.role,
            wecom_userid=m.wecom_userid,
            feishu_open_id=m.feishu_open_id,
            is_active=m.is_active,
            created_at=m.created_at,
            team_name=team_name
        ))
    return result
