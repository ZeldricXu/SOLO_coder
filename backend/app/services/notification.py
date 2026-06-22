import requests
from typing import List, Dict, Any, Optional
from sqlalchemy.orm import Session
from datetime import datetime
from app.models import models
from app.core.config import get_settings
from app.core.utils import get_week_display

settings = get_settings()


def _log_reminder(
    db: Session, user_id: int, week_key: str,
    reminder_type: str, channel: str,
    status: str = "success", error: Optional[str] = None,
    report_id: Optional[int] = None
):
    log = models.ReminderLog(
        report_id=report_id,
        user_id=user_id,
        week_key=week_key,
        reminder_type=reminder_type,
        channel=channel,
        status=status,
        error_message=error
    )
    db.add(log)
    db.commit()


def _build_reminder_message(user_name: str, week_display: str, reminder_type: str) -> str:
    base = f"【周报提醒】{user_name}你好，{week_display} 的周报"
    if reminder_type == "monday_first":
        return f"{base}请及时填写并提交，截止时间为本周五18:00。"
    elif reminder_type == "wednesday_followup":
        return f"{base}还未提交，请尽快完成，避免周五扎堆。"
    elif reminder_type == "friday_urgent":
        return f"【紧急提醒】{user_name}，{week_display} 的周报请立即提交，截止时间就在今天！"
    elif reminder_type == "deadline_2h":
        return f"【最后2小时】{user_name}，距离周报截止还有2小时，请立刻填写提交！"
    else:
        return f"{base}尚未提交，请尽快处理。"


def _send_wecom_message(user: models.User, content: str, at: bool = True) -> tuple:
    if not settings.WECOM_BOT_WEBHOOK:
        return False, "企业微信webhook未配置"
    try:
        mentioned_mobile_list = []
        if at and user.wecom_userid:
            mentioned_mobile_list = [user.wecom_userid]
        payload = {
            "msgtype": "text",
            "text": {
                "content": content,
                "mentioned_mobile_list": mentioned_mobile_list
            }
        }
        r = requests.post(settings.WECOM_BOT_WEBHOOK, json=payload, timeout=10)
        data = r.json()
        return data.get("errcode", -1) == 0, data.get("errmsg", str(r.status_code))
    except Exception as e:
        return False, str(e)


def _send_feishu_message(user: models.User, content: str) -> tuple:
    if not settings.FEISHU_BOT_WEBHOOK:
        return False, "飞书webhook未配置"
    try:
        payload = {
            "msg_type": "text",
            "content": {"text": content}
        }
        r = requests.post(settings.FEISHU_BOT_WEBHOOK, json=payload, timeout=10)
        data = r.json()
        return data.get("code", -1) == 0, data.get("msg", str(r.status_code))
    except Exception as e:
        return False, str(e)


def _send_email_message(user: models.User, subject: str, content: str) -> tuple:
    try:
        import smtplib
        from email.mime.multipart import MIMEMultipart
        from email.mime.text import MIMEText
        if not settings.SMTP_HOST or not settings.SMTP_USER:
            return False, "邮件服务未配置"

        msg = MIMEMultipart('alternative')
        msg['Subject'] = subject
        msg['From'] = settings.SMTP_USER
        msg['To'] = user.email
        html = f"<p>{content.replace(chr(10), '<br/>')}</p>"
        msg.attach(MIMEText(html, 'html', 'utf-8'))

        if settings.SMTP_USE_SSL:
            server = smtplib.SMTP_SSL(settings.SMTP_HOST, settings.SMTP_PORT, timeout=30)
        else:
            server = smtplib.SMTP(settings.SMTP_HOST, settings.SMTP_PORT, timeout=30)
            server.starttls()
        server.login(settings.SMTP_USER, settings.SMTP_PASSWORD)
        server.sendmail(settings.SMTP_USER, [user.email], msg.as_string())
        server.quit()
        return True, None
    except Exception as e:
        return False, str(e)


def _send_team_wecom(team: models.Team, content: str) -> tuple:
    ns = team.settings
    if not ns or not ns.wecom_webhook:
        return False, "团队webhook未配置"
    try:
        payload = {"msgtype": "markdown", "markdown": {"content": content}}
        r = requests.post(ns.wecom_webhook, json=payload, timeout=10)
        data = r.json()
        return data.get("errcode", -1) == 0, data.get("errmsg", str(r.status_code))
    except Exception as e:
        return False, str(e)


def send_reminder_to_users(
    db: Session, user_ids: List[int], week_key: str,
    reminder_type: str = "monday_first"
) -> List[Dict[str, Any]]:
    results = []
    week_display = get_week_display()
    users = db.query(models.User).filter(models.User.id.in_(user_ids)).all()

    for user in users:
        report = db.query(models.WeeklyReport).filter(
            models.WeeklyReport.submitter_id == user.id,
            models.WeeklyReport.week_key == week_key
        ).first()
        report_id = report.id if report else None

        msg = _build_reminder_message(user.full_name, week_display, reminder_type)

        ok, err = _send_wecom_message(user, msg, at=True)
        _log_reminder(db, user.id, week_key, reminder_type, "wecom",
                      "success" if ok else "failed", err, report_id)
        results.append({"user_id": user.id, "user_name": user.full_name,
                        "channel": "wecom", "status": "success" if ok else "failed", "error": err})

        ok, err = _send_feishu_message(user, msg)
        _log_reminder(db, user.id, week_key, reminder_type, "feishu",
                      "success" if ok else "failed", err, report_id)
        results.append({"user_id": user.id, "user_name": user.full_name,
                        "channel": "feishu", "status": "success" if ok else "failed", "error": err})

        ok, err = _send_email_message(user, f"[周报提醒] {week_display}", msg)
        _log_reminder(db, user.id, week_key, reminder_type, "email",
                      "success" if ok else "failed", err, report_id)
        results.append({"user_id": user.id, "user_name": user.full_name,
                        "channel": "email", "status": "success" if ok else "failed", "error": err})

    return results


def get_pending_users_for_reminder(db: Session, week_key: str, team_id: Optional[int] = None) -> List[int]:
    query = db.query(models.User).filter(models.User.is_active == True)
    if team_id:
        query = query.filter(models.User.team_id == team_id)
    all_users = query.all()
    submitted_ids = [r.submitter_id for r in db.query(models.WeeklyReport).filter(
        models.WeeklyReport.week_key == week_key,
        models.WeeklyReport.status == "submitted"
    ).all()]
    return [u.id for u in all_users if u.id not in submitted_ids]


def send_monday_first_reminder(db: Session):
    from app.core.utils import get_week_key
    week_key = get_week_key()
    pending_ids = get_pending_users_for_reminder(db, week_key)
    return send_reminder_to_users(db, pending_ids, week_key, "monday_first")


def send_wednesday_followup_reminder(db: Session):
    from app.core.utils import get_week_key
    week_key = get_week_key()
    pending_ids = get_pending_users_for_reminder(db, week_key)
    return send_reminder_to_users(db, pending_ids, week_key, "wednesday_followup")


def send_friday_urgent_reminder(db: Session):
    from app.core.utils import get_week_key
    week_key = get_week_key()
    pending_ids = get_pending_users_for_reminder(db, week_key)
    return send_reminder_to_users(db, pending_ids, week_key, "friday_urgent")


def send_deadline_2h_reminder(db: Session):
    from app.core.utils import get_week_key
    week_key = get_week_key()
    pending_ids = get_pending_users_for_reminder(db, week_key)
    return send_reminder_to_users(db, pending_ids, week_key, "deadline_2h")


def broadcast_weekly_summary(db: Session, summary: models.WeeklySummary):
    from app.api.export import _summary_to_markdown
    teams = db.query(models.Team).all()
    md = _summary_to_markdown(summary)
    results = []
    for team in teams:
        ok, err = _send_team_wecom(team, md[:4000])
        results.append({"team": team.name, "status": "success" if ok else "failed", "error": err})
    return results
