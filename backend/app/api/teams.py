from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import Optional, List
from app.core.database import get_db
from app.core.security import get_current_active_user, require_admin
from app.models import models
from app.schemas import schemas

router = APIRouter(prefix="/api/teams", tags=["团队管理"])


@router.get("", response_model=List[schemas.TeamResponse])
def list_teams(
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    teams = db.query(models.Team).order_by(models.Team.id.asc()).all()
    result = []
    for team in teams:
        leader = db.query(models.User).filter(models.User.id == team.leader_id).first() if team.leader_id else None
        template = db.query(models.Template).filter(models.Template.id == team.template_id).first() if team.template_id else None
        member_count = db.query(models.User).filter(models.User.team_id == team.id).count()
        result.append(schemas.TeamResponse(
            id=team.id,
            name=team.name,
            description=team.description,
            leader_id=team.leader_id,
            deadline_day=team.deadline_day,
            deadline_hour=team.deadline_hour,
            deadline_minute=team.deadline_minute,
            template_id=team.template_id,
            member_count=member_count,
            leader_name=leader.full_name if leader else None,
            template_name=template.name if template else None
        ))
    return result


@router.get("/{team_id}", response_model=schemas.TeamResponse)
def get_team(
    team_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    team = db.query(models.Team).filter(models.Team.id == team_id).first()
    if not team:
        raise HTTPException(status_code=404, detail="团队不存在")
    leader = db.query(models.User).filter(models.User.id == team.leader_id).first() if team.leader_id else None
    template = db.query(models.Template).filter(models.Template.id == team.template_id).first() if team.template_id else None
    member_count = db.query(models.User).filter(models.User.team_id == team.id).count()
    return schemas.TeamResponse(
        id=team.id,
        name=team.name,
        description=team.description,
        leader_id=team.leader_id,
        deadline_day=team.deadline_day,
        deadline_hour=team.deadline_hour,
        deadline_minute=team.deadline_minute,
        template_id=team.template_id,
        member_count=member_count,
        leader_name=leader.full_name if leader else None,
        template_name=template.name if template else None
    )


@router.post("", response_model=schemas.TeamResponse)
def create_team(
    team_data: schemas.TeamCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    existing = db.query(models.Team).filter(models.Team.name == team_data.name).first()
    if existing:
        raise HTTPException(status_code=400, detail="团队名称已存在")

    team = models.Team(**team_data.model_dump())
    db.add(team)
    db.commit()
    db.refresh(team)

    settings = models.TeamNotificationSetting(team_id=team.id)
    db.add(settings)
    db.commit()

    leader = db.query(models.User).filter(models.User.id == team.leader_id).first() if team.leader_id else None
    template = db.query(models.Template).filter(models.Template.id == team.template_id).first() if team.template_id else None
    return schemas.TeamResponse(
        id=team.id,
        name=team.name,
        description=team.description,
        leader_id=team.leader_id,
        deadline_day=team.deadline_day,
        deadline_hour=team.deadline_hour,
        deadline_minute=team.deadline_minute,
        template_id=team.template_id,
        member_count=0,
        leader_name=leader.full_name if leader else None,
        template_name=template.name if template else None
    )


@router.put("/{team_id}", response_model=schemas.TeamResponse)
def update_team(
    team_id: int,
    team_data: schemas.TeamUpdate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    team = db.query(models.Team).filter(models.Team.id == team_id).first()
    if not team:
        raise HTTPException(status_code=404, detail="团队不存在")

    update_data = team_data.model_dump(exclude_unset=True)
    for field, value in update_data.items():
        if value is not None:
            setattr(team, field, value)

    db.commit()
    db.refresh(team)

    leader = db.query(models.User).filter(models.User.id == team.leader_id).first() if team.leader_id else None
    template = db.query(models.Template).filter(models.Template.id == team.template_id).first() if team.template_id else None
    member_count = db.query(models.User).filter(models.User.team_id == team.id).count()
    return schemas.TeamResponse(
        id=team.id,
        name=team.name,
        description=team.description,
        leader_id=team.leader_id,
        deadline_day=team.deadline_day,
        deadline_hour=team.deadline_hour,
        deadline_minute=team.deadline_minute,
        template_id=team.template_id,
        member_count=member_count,
        leader_name=leader.full_name if leader else None,
        template_name=template.name if template else None
    )


@router.delete("/{team_id}")
def delete_team(
    team_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    team = db.query(models.Team).filter(models.Team.id == team_id).first()
    if not team:
        raise HTTPException(status_code=404, detail="团队不存在")
    members = db.query(models.User).filter(models.User.team_id == team_id).count()
    if members > 0:
        raise HTTPException(status_code=400, detail="该团队还有成员，无法删除")
    db.delete(team)
    db.commit()
    return {"message": "删除成功"}


@router.get("/{team_id}/members", response_model=List[schemas.UserResponse])
def get_team_members(
    team_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    team = db.query(models.Team).filter(models.Team.id == team_id).first()
    if not team:
        raise HTTPException(status_code=404, detail="团队不存在")
    members = db.query(models.User).filter(models.User.team_id == team_id).all()
    result = []
    for m in members:
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
            team_name=team.name
        ))
    return result


@router.get("/{team_id}/notification-setting", response_model=schemas.NotificationSettingResponse)
def get_notification_setting(
    team_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    setting = db.query(models.TeamNotificationSetting).filter(
        models.TeamNotificationSetting.team_id == team_id
    ).first()
    if not setting:
        setting = models.TeamNotificationSetting(team_id=team_id)
        db.add(setting)
        db.commit()
        db.refresh(setting)
    return setting


@router.put("/{team_id}/notification-setting", response_model=schemas.NotificationSettingResponse)
def update_notification_setting(
    team_id: int,
    data: schemas.NotificationSettingUpdate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    setting = db.query(models.TeamNotificationSetting).filter(
        models.TeamNotificationSetting.team_id == team_id
    ).first()
    if not setting:
        setting = models.TeamNotificationSetting(team_id=team_id)
        db.add(setting)
        db.commit()
        db.refresh(setting)

    update_data = data.model_dump(exclude_unset=True)
    for field, value in update_data.items():
        if value is not None:
            setattr(setting, field, value)
    db.commit()
    db.refresh(setting)
    return setting
