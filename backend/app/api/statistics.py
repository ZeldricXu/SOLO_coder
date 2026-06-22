from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import Optional, List, Dict, Any
from datetime import date, timedelta
from collections import Counter
import re
import jieba
from app.core.database import get_db
from app.core.security import get_current_active_user, require_admin
from app.core.utils import get_week_key, get_week_range
from app.models import models
from app.schemas import schemas

router = APIRouter(prefix="/api/statistics", tags=["统计面板"])


STOP_WORDS = set("""的一是在了和有我不人这他她它那要就都而及与对从被把让给也
又只很但并更或如于其之等把还被已将每个些所最因但无应如
上中下前后左右里外东西南北年月日时分秒周星期""".split())


def _get_past_weeks(n: int = 8) -> List[str]:
    weeks = []
    today = date.today()
    for i in range(n - 1, -1, -1):
        d = today - timedelta(weeks=i)
        weeks.append(get_week_key(d))
    return weeks


def _extract_text_from_report(content: Dict[str, Any]) -> str:
    parts = []
    for v in content.values():
        if isinstance(v, str):
            clean = re.sub(r'[#*`>\-\_\!\[\]\(\)]+', ' ', v)
            parts.append(clean)
    return ' '.join(parts)


@router.get("/overview")
def get_overview(
    week_key: Optional[str] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    if not week_key:
        week_key = get_week_key()

    total_users = db.query(models.User).filter(models.User.is_active == True).count()
    total_teams = db.query(models.Team).count()
    submitted = db.query(models.WeeklyReport).filter(
        models.WeeklyReport.week_key == week_key,
        models.WeeklyReport.status == "submitted"
    ).count()
    total_reports = db.query(models.WeeklyReport).filter(
        models.WeeklyReport.week_key == week_key
    ).count()
    total_word_count = db.query(models.WeeklyReport).filter(
        models.WeeklyReport.week_key == week_key,
        models.WeeklyReport.status == "submitted"
    ).all()
    total_words = sum(r.word_count for r in total_word_count)

    return {
        "week_key": week_key,
        "total_users": total_users,
        "total_teams": total_teams,
        "submitted_count": submitted,
        "pending_count": total_users - submitted,
        "submission_rate": round(submitted / total_users * 100, 1) if total_users else 0,
        "total_draft": total_reports - submitted,
        "average_word_count": round(total_words / submitted, 0) if submitted else 0,
        "total_words": total_words
    }


@router.get("/submission-trend")
def get_submission_trend(
    weeks: int = 8,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    week_list = _get_past_weeks(weeks)
    total_users = db.query(models.User).filter(models.User.is_active == True).count()
    result = []
    for wk in week_list:
        submitted = db.query(models.WeeklyReport).filter(
            models.WeeklyReport.week_key == wk,
            models.WeeklyReport.status == "submitted"
        ).count()
        result.append({
            "week_key": wk,
            "submitted_count": submitted,
            "total_users": total_users,
            "submission_rate": round(submitted / total_users * 100, 1) if total_users else 0
        })
    return {"weeks": week_list, "data": result}


@router.get("/team-ranking")
def get_team_ranking(
    week_key: Optional[str] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    if not week_key:
        week_key = get_week_key()

    teams = db.query(models.Team).all()
    result = []
    for team in teams:
        members = db.query(models.User).filter(
            models.User.team_id == team.id,
            models.User.is_active == True
        ).all()
        member_ids = [m.id for m in members]
        if not member_ids:
            continue
        submitted = db.query(models.WeeklyReport).filter(
            models.WeeklyReport.week_key == week_key,
            models.WeeklyReport.status == "submitted",
            models.WeeklyReport.submitter_id.in_(member_ids)
        ).all()
        avg_words = round(sum(r.word_count for r in submitted) / len(submitted), 0) if submitted else 0

        leader = None
        if team.leader_id:
            u = db.query(models.User).filter(models.User.id == team.leader_id).first()
            leader = u.full_name if u else None

        result.append({
            "team_id": team.id,
            "team_name": team.name,
            "leader_name": leader,
            "total_members": len(member_ids),
            "submitted_count": len(submitted),
            "submission_rate": round(len(submitted) / len(member_ids) * 100, 1),
            "average_word_count": avg_words,
            "submit_speed_avg_minutes": 0
        })

    result.sort(key=lambda x: (-x["submission_rate"], -x["submitted_count"]))
    for i, r in enumerate(result):
        r["rank"] = i + 1
    return result


@router.get("/personal-stats")
def get_personal_stats(
    user_id: Optional[int] = None,
    weeks: int = 8,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    target_id = user_id or current_user.id
    if target_id != current_user.id and current_user.role not in ["admin", "super_admin"]:
        if current_user.team_id:
            target_user = db.query(models.User).filter(models.User.id == target_id).first()
            if not target_user or target_user.team_id != current_user.team_id:
                raise HTTPException(status_code=403, detail="无权查看他人统计")

    week_list = _get_past_weeks(weeks)
    reports = db.query(models.WeeklyReport).filter(
        models.WeeklyReport.submitter_id == target_id,
        models.WeeklyReport.week_key.in_(week_list)
    ).all()
    report_map = {r.week_key: r for r in reports}

    weekly_data = []
    total_submitted = 0
    total_words = 0
    for wk in week_list:
        r = report_map.get(wk)
        submitted = r.status == "submitted" if r else False
        if submitted:
            total_submitted += 1
            total_words += r.word_count or 0
        weekly_data.append({
            "week_key": wk,
            "status": r.status if r else "missing",
            "word_count": r.word_count if r else 0,
            "submitted": submitted,
            "submitted_at": r.submitted_at.isoformat() if r and r.submitted_at else None
        })

    return {
        "user_id": target_id,
        "total_weeks": len(week_list),
        "submitted_weeks": total_submitted,
        "submission_rate": round(total_submitted / len(week_list) * 100, 1) if week_list else 0,
        "average_word_count": round(total_words / total_submitted, 0) if total_submitted else 0,
        "weekly_data": weekly_data
    }


@router.get("/word-cloud")
def get_word_cloud(
    week_key: Optional[str] = None,
    top_n: int = 100,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    if not week_key:
        week_key = get_week_key()

    reports = db.query(models.WeeklyReport).filter(
        models.WeeklyReport.week_key == week_key,
        models.WeeklyReport.status == "submitted"
    ).all()

    all_text_parts = []
    for r in reports:
        all_text_parts.append(_extract_text_from_report(r.content or {}))

    full_text = ' '.join(all_text_parts)
    words = jieba.lcut(full_text)

    counter = Counter()
    for w in words:
        w = w.strip()
        if len(w) >= 2 and w not in STOP_WORDS and not re.match(r'^[\d\.\,\!\?\。\，\！\？\：\;\、\(\)]+$', w):
            counter[w] += 1

    top_words = counter.most_common(top_n)
    result = [{"word": w, "count": c} for w, c in top_words]
    return {
        "week_key": week_key,
        "total_reports": len(reports),
        "word_count": len(result),
        "words": result
    }


@router.get("/reminder-logs")
def get_reminder_logs(
    week_key: Optional[str] = None,
    limit: int = 100,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    if not week_key:
        week_key = get_week_key()

    logs = db.query(models.ReminderLog).filter(
        models.ReminderLog.week_key == week_key
    ).order_by(models.ReminderLog.created_at.desc()).limit(limit).all()

    result = []
    for log in logs:
        u = db.query(models.User).filter(models.User.id == log.user_id).first()
        result.append({
            "id": log.id,
            "user_id": log.user_id,
            "user_name": u.full_name if u else "未知",
            "week_key": log.week_key,
            "reminder_type": log.reminder_type,
            "channel": log.channel,
            "status": log.status,
            "error_message": log.error_message,
            "created_at": log.created_at.isoformat()
        })
    return result
