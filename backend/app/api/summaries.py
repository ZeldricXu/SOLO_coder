from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import Optional, List, Dict, Any
from datetime import datetime, date
from app.core.database import get_db
from app.core.security import get_current_active_user, require_admin
from app.core.utils import (
    get_week_range, get_week_key, get_week_display,
    get_prev_week_key, get_prev_week_range
)
from app.models import models
from app.schemas import schemas

router = APIRouter(prefix="/api/summaries", tags=["汇总生成"])


def _find_field_by_flag(fields_snapshot: list, flag: str) -> Optional[str]:
    for f in fields_snapshot:
        if f.get(flag):
            return f.get("field_key")
    return None


def _find_field_by_name(fields_snapshot: list, keywords: List[str]) -> Optional[str]:
    for f in fields_snapshot:
        name = (f.get("field_name") or "").lower()
        key = (f.get("field_key") or "").lower()
        for kw in keywords:
            if kw in name or kw in key:
                return f.get("field_key")
    return None


def _split_lines(text: str) -> List[str]:
    if not text:
        return []
    return [line.strip() for line in text.replace("\r", "").split("\n") if line.strip()]


def _build_summary_for_week(db: Session, week_key: str, generator_id: Optional[int] = None) -> models.WeeklySummary:
    monday, friday = get_week_range()
    if week_key != get_week_key():
        try:
            year_part, week_part = week_key.split("-W")
            from datetime import timedelta
            jan4 = date(int(year_part), 1, 4)
            start = jan4 - timedelta(days=jan4.weekday()) + timedelta(weeks=int(week_part) - 1)
            monday = start
            friday = start + timedelta(days=4)
        except Exception:
            pass

    submitted_reports = db.query(models.WeeklyReport).filter(
        models.WeeklyReport.week_key == week_key,
        models.WeeklyReport.status == "submitted"
    ).all()

    total_users = db.query(models.User).filter(models.User.is_active == True).count()
    teams = db.query(models.Team).all()

    by_team: Dict[int, Dict[str, Any]] = {}
    all_risks: List[Dict[str, Any]] = []
    deviations: List[models.PlanDeviationItem] = []

    prev_week = get_prev_week_key(monday)
    prev_reports = {r.submitter_id: r for r in db.query(models.WeeklyReport).filter(
        models.WeeklyReport.week_key == prev_week,
        models.WeeklyReport.status == "submitted"
    ).all()}

    for team in teams:
        by_team[team.id] = {
            "team_id": team.id,
            "team_name": team.name,
            "reports": [],
            "total_members": 0,
            "submitted_count": 0,
            "risks": []
        }

    for user in db.query(models.User).filter(models.User.is_active == True).all():
        if user.team_id and user.team_id in by_team:
            by_team[user.team_id]["total_members"] += 1

    for report in submitted_reports:
        user = report.submitter
        content = report.content or {}
        team_id = user.team_id or 0

        fields_snapshot = []
        if report.template_version_id:
            tv = db.query(models.TemplateVersion).filter(
                models.TemplateVersion.id == report.template_version_id
            ).first()
            if tv:
                fields_snapshot = tv.fields_snapshot or []

        risk_key = _find_field_by_flag(fields_snapshot, "is_risk_field")
        if not risk_key:
            risk_key = _find_field_by_name(fields_snapshot, ["风险", "阻塞", "risk", "block", "问题"])

        plan_key_prev = _find_field_by_flag(fields_snapshot, "is_plan_field")
        if not plan_key_prev:
            plan_key_prev = _find_field_by_name(fields_snapshot, ["下周计划", "下周", "计划", "plan", "next"])
        achieve_key = _find_field_by_flag(fields_snapshot, "is_achievement_field")
        if not achieve_key:
            achieve_key = _find_field_by_name(fields_snapshot, ["本周完成", "完成", "成果", "成就", "achievement", "done"])

        risk_text = ""
        if risk_key and content.get(risk_key):
            risk_text = content[risk_key]
            if risk_text.strip() and not risk_text.strip().lower() in ["无", "none", "n/a", "没有", "暂无"]:
                risk_item = {
                    "user_id": user.id,
                    "user_name": user.full_name,
                    "team_name": user.team.name if user.team else "未分配",
                    "content": risk_text
                }
                all_risks.append(risk_item)
                if team_id in by_team:
                    by_team[team_id]["risks"].append(risk_item)

        report_info = {
            "report_id": report.id,
            "user_id": user.id,
            "user_name": user.full_name,
            "team_name": user.team.name if user.team else "未分配",
            "word_count": report.word_count,
            "submitted_at": report.submitted_at.isoformat() if report.submitted_at else None,
            "content_summary": {k: v for k, v in content.items() if isinstance(v, str) and len(v) < 500}
        }
        if team_id in by_team:
            by_team[team_id]["reports"].append(report_info)
            by_team[team_id]["submitted_count"] += 1

        prev_report = prev_reports.get(user.id)
        if prev_report and prev_report.content and plan_key_prev and achieve_key:
            prev_content = prev_report.content or {}
            prev_plan = prev_content.get(plan_key_prev, "")
            current_achieve = content.get(achieve_key, "")

            prev_plan_lines = _split_lines(prev_plan)
            achieve_lines = _split_lines(current_achieve)

            for p_item in prev_plan_lines:
                p_clean = p_item.lstrip("-*•· \t1234567890.")
                if not p_clean:
                    continue
                found = False
                for a in achieve_lines:
                    a_clean = a.lstrip("-*•· \t1234567890.")
                    if p_clean[:5] in a or a_clean[:5] in p_clean:
                        found = True
                        break
                if not found and len(p_clean) > 3:
                    deviation = models.PlanDeviationItem(
                        user_id=user.id,
                        user_name=user.full_name,
                        planned_item=p_item,
                        actual_status="未完成",
                        note=f"上周计划: {p_item}",
                        deviation_level="minor" if "延期" in current_achieve or "推迟" in current_achieve else "normal"
                    )
                    deviations.append(deviation)

    summary_content = {
        "week_key": week_key,
        "week_display": f"{monday.strftime('%Y年%m月%d日')} - {friday.strftime('%m月%d日')}",
        "overall_stats": {
            "total_users": total_users,
            "submitted_count": len(submitted_reports),
            "submission_rate": round(len(submitted_reports) / total_users * 100, 1) if total_users else 0,
            "pending_count": total_users - len(submitted_reports)
        },
        "by_team": list(by_team.values()),
        "risks": {
            "total_count": len(all_risks),
            "items": all_risks
        },
        "deviation_count": len(deviations),
        "generated_at": datetime.utcnow().isoformat()
    }

    existing = db.query(models.WeeklySummary).filter(models.WeeklySummary.week_key == week_key).first()
    if existing:
        existing.content = summary_content
        existing.generated_at = datetime.utcnow()
        existing.generated_by = generator_id
        existing.status = "regenerated"
        summary = existing
        db.query(models.PlanDeviationItem).filter(
            models.PlanDeviationItem.summary_id == existing.id
        ).delete()
    else:
        summary = models.WeeklySummary(
            week_key=week_key,
            week_start=monday,
            week_end=friday,
            content=summary_content,
            generated_by=generator_id,
            status="generated"
        )
        db.add(summary)
        db.flush()

    for d in deviations:
        d.summary_id = summary.id
        db.add(d)

    db.commit()
    db.refresh(summary)
    return summary


def _build_summary_response(summary: models.WeeklySummary, db: Session) -> schemas.WeeklySummaryResponse:
    deviations = [schemas.PlanDeviationItemResponse(
        id=d.id,
        user_id=d.user_id,
        user_name=d.user_name,
        planned_item=d.planned_item,
        actual_status=d.actual_status,
        note=d.note,
        deviation_level=d.deviation_level
    ) for d in summary.deviation_items]

    return schemas.WeeklySummaryResponse(
        id=summary.id,
        week_key=summary.week_key,
        week_start=summary.week_start,
        week_end=summary.week_end,
        content=summary.content or {},
        generated_at=summary.generated_at,
        pdf_path=summary.pdf_path,
        status=summary.status,
        deviation_items=deviations
    )


@router.get("", response_model=List[schemas.WeeklySummaryResponse])
def list_summaries(
    week_key: Optional[str] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    query = db.query(models.WeeklySummary)
    if week_key:
        query = query.filter(models.WeeklySummary.week_key == week_key)
    summaries = query.order_by(models.WeeklySummary.week_key.desc()).limit(12).all()
    return [_build_summary_response(s, db) for s in summaries]


@router.get("/current", response_model=schemas.WeeklySummaryResponse)
def get_current_summary(
    week_key: Optional[str] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    if not week_key:
        week_key = get_week_key()

    summary = db.query(models.WeeklySummary).filter(models.WeeklySummary.week_key == week_key).first()
    if not summary:
        summary = _build_summary_for_week(db, week_key, current_user.id)
    return _build_summary_response(summary, db)


@router.get("/{summary_id}", response_model=schemas.WeeklySummaryResponse)
def get_summary(
    summary_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    summary = db.query(models.WeeklySummary).filter(models.WeeklySummary.id == summary_id).first()
    if not summary:
        raise HTTPException(status_code=404, detail="汇总不存在")
    return _build_summary_response(summary, db)


@router.post("/generate", response_model=schemas.WeeklySummaryResponse)
def generate_summary(
    data: schemas.GenerateSummaryRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    week_key = data.week_key or get_week_key()
    existing = db.query(models.WeeklySummary).filter(models.WeeklySummary.week_key == week_key).first()
    if existing and not data.force:
        return _build_summary_response(existing, db)
    summary = _build_summary_for_week(db, week_key, current_user.id)
    return _build_summary_response(summary, db)
