from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import Optional, List, Dict, Any
from datetime import datetime
from app.core.database import get_db
from app.core.security import get_current_active_user, require_admin
from app.core.utils import (
    get_week_range, get_week_key, get_week_display, get_prev_week_key
)
from app.models import models
from app.schemas import schemas
import re

router = APIRouter(prefix="/api/reports", tags=["周报管理"])


def _calc_word_count(content: Dict[str, Any]) -> int:
    total = 0
    for v in content.values():
        if isinstance(v, str):
            clean = re.sub(r'[#*`>\-\s]+', '', v)
            total += len(clean)
    return total


def _validate_required_fields(content: Dict[str, Any], fields: list) -> List[str]:
    missing = []
    for f in fields:
        key = f.get("field_key") if isinstance(f, dict) else f.field_key
        required = f.get("is_required", True) if isinstance(f, dict) else f.is_required
        if required:
            val = content.get(key)
            if not val or (isinstance(val, str) and not val.strip()):
                name = f.get("field_name", key) if isinstance(f, dict) else (f.field_name or key)
                missing.append(name)
    return missing


def _build_report_response(report: models.WeeklyReport, db: Session) -> schemas.WeeklyReportResponse:
    tpl_name = report.template.name if report.template else None
    proxy_name = report.proxy_submitter.full_name if report.proxy_submitter else None
    return schemas.WeeklyReportResponse(
        id=report.id,
        submitter_id=report.submitter_id,
        submitter_name=report.submitter.full_name,
        proxy_submitter_id=report.proxy_submitter_id,
        proxy_submitter_name=proxy_name,
        template_id=report.template_id,
        template_name=tpl_name,
        template_version_id=report.template_version_id,
        week_key=report.week_key,
        week_start=report.week_start,
        week_end=report.week_end,
        content=report.content or {},
        word_count=report.word_count,
        status=report.status,
        submitted_at=report.submitted_at,
        created_at=report.created_at,
        updated_at=report.updated_at
    )


@router.get("/my-current", response_model=Optional[schemas.WeeklyReportResponse])
def get_my_current_report(
    week_key: Optional[str] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    if not week_key:
        week_key = get_week_key()

    report = db.query(models.WeeklyReport).filter(
        models.WeeklyReport.submitter_id == current_user.id,
        models.WeeklyReport.week_key == week_key
    ).first()
    if report:
        return _build_report_response(report, db)

    template_id = None
    template_version = None
    if current_user.team and current_user.team.template_id:
        template_id = current_user.team.template_id
    else:
        default_tpl = db.query(models.Template).filter(models.Template.is_default == True).first()
        if default_tpl:
            template_id = default_tpl.id
    if template_id:
        latest_ver = db.query(models.TemplateVersion).filter(
            models.TemplateVersion.template_id == template_id
        ).order_by(models.TemplateVersion.version.desc()).first()
        template_version = latest_ver

    monday, friday = get_week_range()
    report = models.WeeklyReport(
        submitter_id=current_user.id,
        template_id=template_id,
        template_version_id=template_version.id if template_version else None,
        week_key=week_key,
        week_start=monday,
        week_end=friday,
        content={},
        word_count=0,
        status="draft"
    )
    db.add(report)
    db.commit()
    db.refresh(report)
    return _build_report_response(report, db)


@router.get("/my-history", response_model=List[schemas.WeeklyReportResponse])
def get_my_history(
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    reports = db.query(models.WeeklyReport).filter(
        models.WeeklyReport.submitter_id == current_user.id
    ).order_by(models.WeeklyReport.week_key.desc()).limit(12).all()
    return [_build_report_response(r, db) for r in reports]


@router.get("", response_model=List[schemas.WeeklyReportResponse])
def list_reports(
    week_key: Optional[str] = None,
    team_id: Optional[int] = None,
    submitter_id: Optional[int] = None,
    status: Optional[str] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    if not week_key:
        week_key = get_week_key()

    query = db.query(models.WeeklyReport).filter(models.WeeklyReport.week_key == week_key)

    is_admin = current_user.role in ["admin", "super_admin"]
    if not is_admin:
        if current_user.team_id:
            team_member_ids = [u.id for u in db.query(models.User).filter(
                models.User.team_id == current_user.team_id
            ).all()]
            team_member_ids.append(current_user.id)
            query = query.filter(models.WeeklyReport.submitter_id.in_(team_member_ids))
        else:
            query = query.filter(models.WeeklyReport.submitter_id == current_user.id)

    if team_id:
        member_ids = [u.id for u in db.query(models.User).filter(
            models.User.team_id == team_id
        ).all()]
        query = query.filter(models.WeeklyReport.submitter_id.in_(member_ids))
    if submitter_id:
        query = query.filter(models.WeeklyReport.submitter_id == submitter_id)
    if status:
        query = query.filter(models.WeeklyReport.status == status)

    reports = query.order_by(models.WeeklyReport.submitter_id.asc()).all()
    return [_build_report_response(r, db) for r in reports]


@router.get("/{report_id}", response_model=schemas.WeeklyReportResponse)
def get_report(
    report_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    report = db.query(models.WeeklyReport).filter(models.WeeklyReport.id == report_id).first()
    if not report:
        raise HTTPException(status_code=404, detail="周报不存在")

    is_admin = current_user.role in ["admin", "super_admin"]
    if not is_admin and report.submitter_id != current_user.id:
        if report.submitter.team_id != current_user.team_id:
            raise HTTPException(status_code=403, detail="无权查看此周报")
    return _build_report_response(report, db)


@router.put("/{report_id}", response_model=schemas.WeeklyReportResponse)
def update_report(
    report_id: int,
    data: schemas.WeeklyReportUpdate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    report = db.query(models.WeeklyReport).filter(models.WeeklyReport.id == report_id).first()
    if not report:
        raise HTTPException(status_code=404, detail="周报不存在")

    is_admin = current_user.role in ["admin", "super_admin"]
    is_proxy = False
    if report.submitter.team_id and current_user.team_id == report.submitter.team_id:
        team = db.query(models.Team).filter(models.Team.id == current_user.team_id).first()
        if team and team.leader_id == current_user.id:
            is_proxy = True

    if not is_admin and report.submitter_id != current_user.id and not is_proxy:
        raise HTTPException(status_code=403, detail="无权修改此周报")

    if report.status == "submitted" and not is_admin:
        raise HTTPException(status_code=400, detail="已提交的周报需管理员或TL撤回")

    if data.content is not None:
        report.content = data.content
        report.word_count = _calc_word_count(data.content)

    if data.status is not None:
        if data.status == "submitted":
            if report.template_version_id:
                tv = db.query(models.TemplateVersion).filter(
                    models.TemplateVersion.id == report.template_version_id
                ).first()
                if tv:
                    missing = _validate_required_fields(report.content or {}, tv.fields_snapshot)
                    if missing:
                        raise HTTPException(status_code=400, detail=f"必填字段未填写: {', '.join(missing)}")
            report.submitted_at = datetime.utcnow()
        report.status = data.status

    report.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(report)
    return _build_report_response(report, db)


@router.post("/proxy-submit", response_model=schemas.WeeklyReportResponse)
def proxy_submit_report(
    data: schemas.WeeklyReportSubmit,
    proxy_user_id: Optional[int] = None,
    week_key: Optional[str] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    if not data.proxy_user_id and not proxy_user_id:
        raise HTTPException(status_code=400, detail="请指定被代理人")
    target_user_id = data.proxy_user_id or proxy_user_id

    target_user = db.query(models.User).filter(models.User.id == target_user_id).first()
    if not target_user:
        raise HTTPException(status_code=404, detail="目标用户不存在")

    is_admin = current_user.role in ["admin", "super_admin"]
    is_leader = False
    if target_user.team_id and current_user.team_id == target_user.team_id:
        team = db.query(models.Team).filter(models.Team.id == current_user.team_id).first()
        if team and team.leader_id == current_user.id:
            is_leader = True
    if not is_admin and not is_leader:
        raise HTTPException(status_code=403, detail="无权代理提交，需管理员或团队负责人")

    if not week_key:
        week_key = get_week_key()

    report = db.query(models.WeeklyReport).filter(
        models.WeeklyReport.submitter_id == target_user_id,
        models.WeeklyReport.week_key == week_key
    ).first()

    template_id = None
    template_version = None
    if target_user.team and target_user.team.template_id:
        template_id = target_user.team.template_id
    else:
        default_tpl = db.query(models.Template).filter(models.Template.is_default == True).first()
        if default_tpl:
            template_id = default_tpl.id
    if template_id:
        latest_ver = db.query(models.TemplateVersion).filter(
            models.TemplateVersion.template_id == template_id
        ).order_by(models.TemplateVersion.version.desc()).first()
        template_version = latest_ver

    if not report:
        monday, friday = get_week_range()
        report = models.WeeklyReport(
            submitter_id=target_user_id,
            proxy_submitter_id=current_user.id,
            template_id=template_id,
            template_version_id=template_version.id if template_version else None,
            week_key=week_key,
            week_start=monday,
            week_end=friday,
            content=data.content or {},
            word_count=_calc_word_count(data.content or {}),
            status=data.status,
            submitted_at=datetime.utcnow() if data.status == "submitted" else None
        )
        db.add(report)
    else:
        if report.template_version_id:
            tv = db.query(models.TemplateVersion).filter(
                models.TemplateVersion.id == report.template_version_id
            ).first()
            if tv and data.status == "submitted":
                missing = _validate_required_fields(data.content or report.content or {}, tv.fields_snapshot)
                if missing:
                    raise HTTPException(status_code=400, detail=f"必填字段未填写: {', '.join(missing)}")
        report.proxy_submitter_id = current_user.id
        report.content = data.content or report.content
        report.word_count = _calc_word_count(report.content or {})
        report.status = data.status
        if data.status == "submitted":
            report.submitted_at = datetime.utcnow()
        report.updated_at = datetime.utcnow()

    db.commit()
    db.refresh(report)
    return _build_report_response(report, db)


@router.post("/{report_id}/revoke")
def revoke_report(
    report_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    report = db.query(models.WeeklyReport).filter(models.WeeklyReport.id == report_id).first()
    if not report:
        raise HTTPException(status_code=404, detail="周报不存在")

    is_admin = current_user.role in ["admin", "super_admin"]
    is_leader = False
    if report.submitter.team_id and current_user.team_id == report.submitter.team_id:
        team = db.query(models.Team).filter(models.Team.id == current_user.team_id).first()
        if team and team.leader_id == current_user.id:
            is_leader = True

    if not is_admin and not is_leader and report.submitter_id != current_user.id:
        raise HTTPException(status_code=403, detail="无权撤回此周报")

    if report.status != "submitted":
        raise HTTPException(status_code=400, detail="仅已提交状态的周报可撤回")

    report.status = "draft"
    report.submitted_at = None
    report.updated_at = datetime.utcnow()
    db.commit()
    return {"message": "撤回成功"}


@router.get("/pending/list")
def get_pending_users(
    week_key: Optional[str] = None,
    team_id: Optional[int] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    if not week_key:
        week_key = get_week_key()

    query = db.query(models.User).filter(models.User.is_active == True)
    if team_id:
        query = query.filter(models.User.team_id == team_id)
    users = query.all()

    submitted_ids = [r.submitter_id for r in db.query(models.WeeklyReport).filter(
        models.WeeklyReport.week_key == week_key,
        models.WeeklyReport.status == "submitted"
    ).all()]

    pending = []
    for u in users:
        if u.id not in submitted_ids:
            pending.append({
                "user_id": u.id,
                "user_name": u.full_name,
                "team_name": u.team.name if u.team else "未分配",
                "email": u.email
            })
    return {
        "week_key": week_key,
        "week_display": get_week_display(),
        "total_users": len(users),
        "submitted_count": len(submitted_ids),
        "pending_count": len(pending),
        "pending_users": pending
    }
