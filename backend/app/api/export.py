from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session
from typing import Optional, List, Dict, Any
import os
import json
from datetime import datetime
from app.core.database import get_db
from app.core.security import require_admin
from app.core.config import get_settings
from app.models import models
from app.schemas import schemas

router = APIRouter(prefix="/api/export", tags=["导出与分发"])
settings = get_settings()

EXPORT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "exports")
os.makedirs(EXPORT_DIR, exist_ok=True)


def _generate_pdf(summary: models.WeeklySummary) -> str:
    try:
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
        from reportlab.lib.units import cm
        from reportlab.lib import colors
        from reportlab.platypus import (
            SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak
        )
        from reportlab.lib.enums import TA_CENTER, TA_LEFT
    except ImportError:
        raise HTTPException(status_code=500, detail="reportlab未安装，无法生成PDF")

    filename = f"weekly_summary_{summary.week_key}_{summary.id}.pdf"
    filepath = os.path.join(EXPORT_DIR, filename)

    content = summary.content or {}
    styles = getSampleStyleSheet()

    title_style = ParagraphStyle(
        'CustomTitle', parent=styles['Title'], fontSize=20, spaceAfter=20, alignment=TA_CENTER
    )
    h2_style = ParagraphStyle(
        'H2', parent=styles['Heading2'], fontSize=14, spaceBefore=12, spaceAfter=8, textColor=colors.HexColor('#1f2937')
    )
    h3_style = ParagraphStyle(
        'H3', parent=styles['Heading3'], fontSize=12, spaceBefore=8, spaceAfter=4
    )
    normal_style = ParagraphStyle(
        'Normal', parent=styles['Normal'], fontSize=10, leading=16
    )
    risk_style = ParagraphStyle(
        'Risk', parent=styles['Normal'], fontSize=10, leading=16, textColor=colors.red
    )

    doc = SimpleDocTemplate(filepath, pagesize=A4, topMargin=2 * cm, bottomMargin=2 * cm, leftMargin=2 * cm, rightMargin=2 * cm)
    story = []

    story.append(Paragraph(f"周报汇总 - {content.get('week_display', summary.week_key)}", title_style))
    story.append(Spacer(1, 0.5 * cm))

    stats = content.get('overall_stats', {})
    story.append(Paragraph("📊 整体统计", h2_style))
    stats_data = [
        ["总人数", "已提交", "待提交", "提交率"],
        [
            str(stats.get('total_users', 0)),
            str(stats.get('submitted_count', 0)),
            str(stats.get('pending_count', 0)),
            f"{stats.get('submission_rate', 0)}%"
        ]
    ]
    t = Table(stats_data, colWidths=[4 * cm] * 4)
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#3b82f6')),
        ('TEXTCOLOR', (0, 0), (-1, 0), colors.whitesmoke),
        ('ALIGN', (0, 0), (-1, -1), 'CENTER'),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('GRID', (0, 0), (-1, -1), 0.5, colors.grey)
    ]))
    story.append(t)
    story.append(Spacer(1, 0.3 * cm))

    by_team = content.get('by_team', [])
    if by_team:
        story.append(Paragraph("👥 按团队汇总", h2_style))
        team_data = [["团队", "总人数", "已提交", "提交率"]]
        for t in by_team:
            rate = f"{round(t.get('submitted_count', 0) / t.get('total_members', 1) * 100, 1)}%" if t.get('total_members') else "-"
            team_data.append([
                t.get('team_name', ''),
                str(t.get('total_members', 0)),
                str(t.get('submitted_count', 0)),
                rate
            ])
            for r in t.get('reports', []):
                team_data.append([
                    f"  · {r.get('user_name', '')}",
                    f"字数: {r.get('word_count', 0)}",
                    "✓", ""
                ])
        tt = Table(team_data, colWidths=[6 * cm, 3 * cm, 3 * cm, 3 * cm])
        tt.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#10b981')),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.whitesmoke),
            ('GRID', (0, 0), (-1, -1), 0.3, colors.lightgrey),
            ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ]))
        story.append(tt)
        story.append(Spacer(1, 0.3 * cm))

    risks = content.get('risks', {})
    risk_items = risks.get('items', [])
    if risk_items:
        story.append(Paragraph("⚠️ 本周风险与阻塞（标红）", h2_style))
        for idx, item in enumerate(risk_items, 1):
            story.append(Paragraph(
                f"<b>{idx}. [{item.get('team_name', '')}] {item.get('user_name', '')}</b>",
                h3_style
            ))
            safe_content = str(item.get('content', '')).replace('\n', '<br/>')
            story.append(Paragraph(safe_content, risk_style))
            story.append(Spacer(1, 0.15 * cm))
        story.append(Spacer(1, 0.3 * cm))

    deviations = summary.deviation_items or []
    if deviations:
        story.append(Paragraph("❌ 计划偏离项（上周计划 vs 本周完成）", h2_style))
        dev_data = [["成员", "计划项", "实际状态", "偏离级别"]]
        for d in deviations:
            dev_data.append([
                d.user_name,
                (d.planned_item[:50] + '...') if len(d.planned_item) > 50 else d.planned_item,
                d.actual_status,
                d.deviation_level
            ])
        dt = Table(dev_data, colWidths=[3 * cm, 6 * cm, 2.5 * cm, 2.5 * cm])
        dt.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#ef4444')),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.whitesmoke),
            ('GRID', (0, 0), (-1, -1), 0.3, colors.lightgrey),
            ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ]))
        story.append(dt)

    story.append(Spacer(1, 0.5 * cm))
    story.append(Paragraph(f"生成时间: {summary.generated_at.strftime('%Y-%m-%d %H:%M:%S')}", normal_style))

    doc.build(story)
    return filename


def _send_email(recipients: List[str], subject: str, body_html: str, attachment_path: Optional[str] = None):
    try:
        import smtplib
        from email.mime.multipart import MIMEMultipart
        from email.mime.text import MIMEText
        from email.mime.base import MIMEBase
        from email import encoders
    except ImportError:
        return False, "邮件库不可用"

    try:
        msg = MIMEMultipart('alternative')
        msg['Subject'] = subject
        msg['From'] = settings.SMTP_USER
        msg['To'] = ', '.join(recipients)
        msg.attach(MIMEText(body_html, 'html', 'utf-8'))

        if attachment_path and os.path.exists(attachment_path):
            with open(attachment_path, 'rb') as f:
                part = MIMEBase('application', 'octet-stream')
                part.set_payload(f.read())
            encoders.encode_base64(part)
            fname = os.path.basename(attachment_path)
            part.add_header('Content-Disposition', f'attachment; filename="{fname}"')
            msg.attach(part)

        if settings.SMTP_USE_SSL:
            server = smtplib.SMTP_SSL(settings.SMTP_HOST, settings.SMTP_PORT, timeout=30)
        else:
            server = smtplib.SMTP(settings.SMTP_HOST, settings.SMTP_PORT, timeout=30)
            server.starttls()

        server.login(settings.SMTP_USER, settings.SMTP_PASSWORD)
        server.sendmail(settings.SMTP_USER, recipients, msg.as_string())
        server.quit()
        return True, None
    except Exception as e:
        return False, str(e)


def _push_wecom_webhook(webhook_url: str, content: str, is_markdown: bool = True):
    import requests
    try:
        msg_type = "markdown" if is_markdown else "text"
        key = "content" if is_markdown else "content"
        payload = {"msgtype": msg_type, msg_type: {key: content}}
        r = requests.post(webhook_url, json=payload, timeout=10)
        data = r.json()
        return data.get("errcode", -1) == 0, data.get("errmsg", str(r.status_code))
    except Exception as e:
        return False, str(e)


def _push_feishu_webhook(webhook_url: str, content: str, is_markdown: bool = True):
    import requests
    try:
        payload = {
            "msg_type": "interactive",
            "card": {
                "header": {"title": {"tag": "plain_text", "content": "周报汇总"}},
                "elements": [{"tag": "markdown", "content": content[:5000]}]
            }
        }
        r = requests.post(webhook_url, json=payload, timeout=10)
        data = r.json()
        return data.get("code", -1) == 0, data.get("msg", str(r.status_code))
    except Exception as e:
        return False, str(e)


def _push_confluence(title: str, html_content: str):
    import requests
    from base64 import b64encode
    try:
        if not settings.CONFLUENCE_BASE_URL or not settings.CONFLUENCE_USERNAME:
            return False, "Confluence未配置"
        auth = b64encode(f"{settings.CONFLUENCE_USERNAME}:{settings.CONFLUENCE_API_TOKEN}".encode()).decode()
        headers = {
            "Authorization": f"Basic {auth}",
            "Content-Type": "application/json"
        }
        url = f"{settings.CONFLUENCE_BASE_URL.rstrip('/')}/rest/api/content"
        payload = {
            "type": "page",
            "title": title,
            "space": {"key": settings.CONFLUENCE_SPACE_KEY or "WEEKLY"},
            "body": {"storage": {"value": html_content, "representation": "storage"}}
        }
        r = requests.post(url, headers=headers, json=payload, timeout=15)
        return 200 <= r.status_code < 300, r.text[:500]
    except Exception as e:
        return False, str(e)


def _push_yuque(title: str, body: str):
    import requests
    try:
        if not settings.YUQUE_TOKEN or not settings.YUQUE_REPO:
            return False, "语雀未配置"
        headers = {
            "X-Auth-Token": settings.YUQUE_TOKEN,
            "Content-Type": "application/json"
        }
        url = f"{settings.YUQUE_BASE_URL.rstrip('/')}/repos/{settings.YUQUE_LOGIN or 'me'}/{settings.YUQUE_REPO}/docs"
        payload = {"title": title, "body": body, "format": "markdown"}
        r = requests.post(url, headers=headers, json=payload, timeout=15)
        return 200 <= r.status_code < 300, r.text[:500]
    except Exception as e:
        return False, str(e)


def _push_notion(title: str, content: str):
    import requests
    try:
        if not settings.NOTION_TOKEN or not settings.NOTION_DATABASE_ID:
            return False, "Notion未配置"
        headers = {
            "Authorization": f"Bearer {settings.NOTION_TOKEN}",
            "Content-Type": "application/json",
            "Notion-Version": "2022-06-28"
        }
        url = "https://api.notion.com/v1/pages"
        payload = {
            "parent": {"database_id": settings.NOTION_DATABASE_ID},
            "properties": {
                "Name": {"title": [{"text": {"content": title}}]}
            },
            "children": [
                {"object": "block", "type": "paragraph",
                 "paragraph": {"rich_text": [{"type": "text", "text": {"content": content[:2000]}}]}}
            ]
        }
        r = requests.post(url, headers=headers, json=payload, timeout=15)
        return 200 <= r.status_code < 300, r.text[:500]
    except Exception as e:
        return False, str(e)


def _summary_to_markdown(summary: models.WeeklySummary) -> str:
    content = summary.content or {}
    lines = []
    lines.append(f"# 周报汇总 - {content.get('week_display', summary.week_key)}\n")
    stats = content.get('overall_stats', {})
    lines.append("## 📊 整体统计\n")
    lines.append(f"- 总人数: **{stats.get('total_users', 0)}**")
    lines.append(f"- 已提交: **{stats.get('submitted_count', 0)}**")
    lines.append(f"- 待提交: **{stats.get('pending_count', 0)}**")
    lines.append(f"- 提交率: **{stats.get('submission_rate', 0)}%**\n")

    by_team = content.get('by_team', [])
    if by_team:
        lines.append("## 👥 按团队汇总\n")
        for t in by_team:
            rate = round(t.get('submitted_count', 0) / t.get('total_members', 1) * 100, 1) if t.get('total_members') else 0
            lines.append(f"### {t.get('team_name', '')} ({t.get('submitted_count', 0)}/{t.get('total_members', 0)}, {rate}%)\n")
            for r in t.get('reports', []):
                lines.append(f"- **{r.get('user_name', '')}** ({r.get('word_count', 0)}字)")
            lines.append("")

    risks = content.get('risks', {})
    risk_items = risks.get('items', [])
    if risk_items:
        lines.append("## ⚠️ 本周风险与阻塞\n")
        for item in risk_items:
            lines.append(f"### [{item.get('team_name', '')}] {item.get('user_name', '')}")
            lines.append(f"<span style='color:red'>{item.get('content', '')}</span>\n")

    if summary.deviation_items:
        lines.append("## ❌ 计划偏离项\n")
        for d in summary.deviation_items:
            lines.append(f"- **{d.user_name}** - {d.planned_item[:80]} -> **{d.actual_status}** ({d.deviation_level})")

    lines.append(f"\n---\n*生成时间: {summary.generated_at.strftime('%Y-%m-%d %H:%M:%S')}*")
    return '\n'.join(lines)


def _summary_to_html(summary: models.WeeklySummary) -> str:
    md = _summary_to_markdown(summary)
    import re
    html = md
    html = re.sub(r'^### (.+)$', r'<h4>\1</h4>', html, flags=re.M)
    html = re.sub(r'^## (.+)$', r'<h2>\1</h2>', html, flags=re.M)
    html = re.sub(r'^# (.+)$', r'<h1>\1</h1>', html, flags=re.M)
    html = re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', html)
    html = re.sub(r'^- (.+)$', r'<li>\1</li>', html, flags=re.M)
    html = re.sub(r'(<li>.*</li>\n?)+', lambda m: f'<ul>{m.group()}</ul>', html)
    html = re.sub(r'\n+', '<br/>', html)
    return f'<html><body style="font-family:sans-serif;max-width:800px;margin:auto;padding:20px;">{html}</body></html>'


@router.get("/download/{summary_id}")
def download_summary(
    summary_id: int,
    format: str = "pdf",
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    summary = db.query(models.WeeklySummary).filter(models.WeeklySummary.id == summary_id).first()
    if not summary:
        raise HTTPException(status_code=404, detail="汇总不存在")

    if format == "json":
        filename = f"weekly_summary_{summary.week_key}.json"
        filepath = os.path.join(EXPORT_DIR, filename)
        with open(filepath, 'w', encoding='utf-8') as f:
            json.dump(summary.content or {}, f, ensure_ascii=False, indent=2)
        return FileResponse(filepath, media_type="application/json", filename=filename)
    elif format == "markdown":
        filename = f"weekly_summary_{summary.week_key}.md"
        filepath = os.path.join(EXPORT_DIR, filename)
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(_summary_to_markdown(summary))
        return FileResponse(filepath, media_type="text/markdown", filename=filename)
    else:
        fname = _generate_pdf(summary)
        fpath = os.path.join(EXPORT_DIR, fname)
        summary.pdf_path = fpath
        db.commit()
        return FileResponse(fpath, media_type="application/pdf", filename=fname)


@router.post("/distribute")
def distribute_summary(
    data: schemas.ExportRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    summary = db.query(models.WeeklySummary).filter(models.WeeklySummary.week_key == data.week_key).first()
    if not summary:
        raise HTTPException(status_code=404, detail="该周汇总不存在，请先生成")

    results = {"week_key": data.week_key, "actions": []}
    md_content = _summary_to_markdown(summary)
    html_content = _summary_to_html(summary)

    pdf_path = None
    if data.email_to or data.format == "pdf":
        try:
            fname = _generate_pdf(summary)
            pdf_path = os.path.join(EXPORT_DIR, fname)
            summary.pdf_path = pdf_path
            db.commit()
        except Exception as e:
            results["actions"].append({"type": "pdf", "status": "failed", "error": str(e)})

    emails = data.email_to or settings.report_email_list
    if data.push_confluence or emails:
        if emails:
            ok, err = _send_email(
                emails,
                f"[周报汇总] {summary.content.get('week_display', summary.week_key)}",
                html_content,
                pdf_path
            )
            results["actions"].append({
                "type": "email", "recipients": emails, "status": "success" if ok else "failed", "error": err
            })

    if data.push_wecom:
        teams = db.query(models.Team).all()
        for team in teams:
            ns = team.settings
            if ns and ns.notify_wecom_enabled and ns.wecom_webhook:
                ok, err = _push_wecom_webhook(ns.wecom_webhook, md_content)
                results["actions"].append({
                    "type": "wecom", "team": team.name, "status": "success" if ok else "failed", "error": err
                })
        if settings.WECOM_BOT_WEBHOOK:
            ok, err = _push_wecom_webhook(settings.WECOM_BOT_WEBHOOK, md_content)
            results["actions"].append({
                "type": "wecom_global", "status": "success" if ok else "failed", "error": err
            })

    if data.push_feishu:
        teams = db.query(models.Team).all()
        for team in teams:
            ns = team.settings
            if ns and ns.notify_feishu_enabled and ns.feishu_webhook:
                ok, err = _push_feishu_webhook(ns.feishu_webhook, md_content)
                results["actions"].append({
                    "type": "feishu", "team": team.name, "status": "success" if ok else "failed", "error": err
                })
        if settings.FEISHU_BOT_WEBHOOK:
            ok, err = _push_feishu_webhook(settings.FEISHU_BOT_WEBHOOK, md_content)
            results["actions"].append({
                "type": "feishu_global", "status": "success" if ok else "failed", "error": err
            })

    if data.push_confluence:
        title = f"周报汇总 - {summary.content.get('week_display', summary.week_key)}"
        ok, err = _push_confluence(title, html_content)
        results["actions"].append({"type": "confluence", "status": "success" if ok else "failed", "error": err})

    if data.push_yuque:
        title = f"周报汇总 - {summary.content.get('week_display', summary.week_key)}"
        ok, err = _push_yuque(title, md_content)
        results["actions"].append({"type": "yuque", "status": "success" if ok else "failed", "error": err})

    if data.push_notion:
        title = f"周报汇总 - {summary.content.get('week_display', summary.week_key)}"
        ok, err = _push_notion(title, md_content)
        results["actions"].append({"type": "notion", "status": "success" if ok else "failed", "error": err})

    summary.distributed_to = results
    summary.status = "distributed"
    db.commit()
    return results


@router.post("/send-reminder")
def send_reminder_manual(
    data: schemas.SendReminderRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    from app.services.notification import send_reminder_to_users
    week_key = get_week_key()

    user_ids = data.user_ids or []
    if data.team_id:
        members = db.query(models.User).filter(
            models.User.team_id == data.team_id,
            models.User.is_active == True
        ).all()
        for m in members:
            if m.id not in user_ids:
                user_ids.append(m.id)

    if not user_ids:
        submitted = [r.submitter_id for r in db.query(models.WeeklyReport).filter(
            models.WeeklyReport.week_key == week_key,
            models.WeeklyReport.status == "submitted"
        ).all()]
        all_users = db.query(models.User).filter(models.User.is_active == True).all()
        user_ids = [u.id for u in all_users if u.id not in submitted]

    results = send_reminder_to_users(db, user_ids, week_key, data.reminder_type)
    return {"sent_count": len(results), "results": results}
