from datetime import datetime, date, timedelta
from typing import List, Optional, Dict, Any
import calendar
import random

from sqlalchemy import text
from sqlalchemy.orm import Session

from app.models import DutySchedule, HandoverReport, AlertHistory, AssetChangeLog
from app.schemas import DutyScheduleCreate, DutySwapRequest, HandoverRequest


class DutyService:
    def __init__(self, db: Session):
        self.db = db

    def get_schedule(self, year: Optional[int] = None, month: Optional[int] = None) -> List[Dict[str, Any]]:
        if not year:
            year = date.today().year
        if not month:
            month = date.today().month

        _, last_day = calendar.monthrange(year, month)
        start_date = date(year, month, 1)
        end_date = date(year, month, last_day)

        schedules = self.db.query(DutySchedule).filter(
            DutySchedule.duty_date >= start_date,
            DutySchedule.duty_date <= end_date,
        ).order_by(DutySchedule.duty_date, DutySchedule.shift_type).all()

        return [
            {
                "id": s.id,
                "user_id": s.user_id,
                "duty_date": s.duty_date.isoformat(),
                "shift_type": s.shift_type,
                "shift_name": "白班" if s.shift_type == "day" else "夜班",
            }
            for s in schedules
        ]

    def get_schedule_by_date(self, duty_date: date) -> List[DutySchedule]:
        return self.db.query(DutySchedule).filter(
            DutySchedule.duty_date == duty_date
        ).order_by(DutySchedule.shift_type).all()

    def get_today_duty(self) -> List[Dict[str, Any]]:
        today = date.today()
        schedules = self.get_schedule_by_date(today)
        return self._enrich_with_user_info(schedules)

    def _enrich_with_user_info(self, schedules: List[DutySchedule]) -> List[Dict[str, Any]]:
        result = []
        for s in schedules:
            user_row = self.db.execute(
                text("SELECT id, username, real_name, phone FROM users WHERE id = :user_id"),
                {"user_id": s.user_id}
            ).fetchone()

            user_info = {}
            if user_row:
                user_info = {
                    "user_id": user_row.id,
                    "username": user_row.username,
                    "real_name": user_row.real_name,
                    "phone": user_row.phone,
                }

            result.append({
                "id": s.id,
                "duty_date": s.duty_date.isoformat(),
                "shift_type": s.shift_type,
                "shift_name": "白班" if s.shift_type == "day" else "夜班",
                **user_info,
            })
        return result

    def create_schedule(self, data: DutyScheduleCreate) -> DutySchedule:
        existing = self.db.query(DutySchedule).filter(
            DutySchedule.user_id == data.user_id,
            DutySchedule.duty_date == data.duty_date,
            DutySchedule.shift_type == data.shift_type,
        ).first()

        if existing:
            return existing

        schedule = DutySchedule(**data.model_dump())
        self.db.add(schedule)
        self.db.commit()
        self.db.refresh(schedule)
        return schedule

    def create_monthly_schedule(self, year: int, month: int, user_ids: List[int]):
        _, last_day = calendar.monthrange(year, month)
        shift_types = ["day", "night"]

        for day in range(1, last_day + 1):
            duty_date = date(year, month, day)
            for i, shift_type in enumerate(shift_types):
                user_idx = (day - 1 + i) % len(user_ids)
                self.create_schedule(DutyScheduleCreate(
                    user_id=user_ids[user_idx],
                    duty_date=duty_date,
                    shift_type=shift_type,
                ))

    def swap_duty(self, data: DutySwapRequest) -> bool:
        original = self.db.query(DutySchedule).filter(
            DutySchedule.id == data.schedule_id,
            DutySchedule.user_id == data.from_user_id,
        ).first()

        if not original:
            return False

        original.user_id = data.to_user_id
        self.db.commit()
        return True

    def delete_schedule(self, schedule_id: int) -> bool:
        schedule = self.db.query(DutySchedule).filter(DutySchedule.id == schedule_id).first()
        if not schedule:
            return False
        self.db.delete(schedule)
        self.db.commit()
        return True

    def get_duty_user(self, check_time: Optional[datetime] = None) -> Optional[Dict[str, Any]]:
        if not check_time:
            check_time = datetime.now()

        check_date = check_time.date()
        shift_type = "day" if 8 <= check_time.hour < 20 else "night"

        schedule = self.db.query(DutySchedule).filter(
            DutySchedule.duty_date == check_date,
            DutySchedule.shift_type == shift_type,
        ).first()

        if not schedule:
            return None

        user_row = self.db.execute(
            text("SELECT id, username, real_name, phone, email FROM users WHERE id = :user_id"),
            {"user_id": schedule.user_id}
        ).fetchone()

        if not user_row:
            return None

        return {
            "schedule_id": schedule.id,
            "user_id": user_row.id,
            "username": user_row.username,
            "real_name": user_row.real_name,
            "phone": user_row.phone,
            "email": user_row.email,
            "shift_type": shift_type,
            "shift_name": "白班" if shift_type == "day" else "夜班",
        }

    def generate_handover_report(self, data: HandoverRequest) -> HandoverReport:
        shift_start, shift_end = self._get_shift_range(data.from_user_id)

        alerts = self.db.query(AlertHistory).filter(
            AlertHistory.triggered_at >= shift_start,
            AlertHistory.triggered_at <= shift_end,
        ).order_by(AlertHistory.level, AlertHistory.triggered_at.desc()).all()

        changes = self.db.query(AssetChangeLog).filter(
            AssetChangeLog.changed_at >= shift_start,
            AssetChangeLog.changed_at <= shift_end,
        ).order_by(AssetChangeLog.changed_at.desc()).all()

        content = self._format_handover_content(
            data.from_user_id,
            data.to_user_id,
            shift_start,
            shift_end,
            alerts,
            changes,
            data.custom_content
        )

        report = HandoverReport(
            schedule_id=data.schedule_id,
            from_user_id=data.from_user_id,
            to_user_id=data.to_user_id,
            content=content,
        )
        self.db.add(report)
        self.db.commit()
        self.db.refresh(report)
        return report

    def _get_shift_range(self, user_id: int) -> tuple[datetime, datetime]:
        now = datetime.now()
        today = now.date()

        shift_type = "day"
        schedule = self.db.query(DutySchedule).filter(
            DutySchedule.user_id == user_id,
            DutySchedule.duty_date == today,
        ).first()

        if schedule:
            shift_type = schedule.shift_type

        if shift_type == "day":
            start = datetime.combine(today, datetime.min.time().replace(hour=8))
            end = datetime.combine(today, datetime.min.time().replace(hour=20))
        else:
            if now.hour < 8:
                start = datetime.combine(today - timedelta(days=1), datetime.min.time().replace(hour=20))
                end = datetime.combine(today, datetime.min.time().replace(hour=8))
            else:
                start = datetime.combine(today, datetime.min.time().replace(hour=20))
                end = datetime.combine(today + timedelta(days=1), datetime.min.time().replace(hour=8))

        return start, end

    def _format_handover_content(
        self,
        from_user_id: int,
        to_user_id: int,
        shift_start: datetime,
        shift_end: datetime,
        alerts: List[AlertHistory],
        changes: List[AssetChangeLog],
        custom_content: Optional[str] = None,
    ) -> str:
        from_user = self.db.execute(
            text("SELECT real_name FROM users WHERE id = :id"), {"id": from_user_id}
        ).fetchone()
        to_user = self.db.execute(
            text("SELECT real_name FROM users WHERE id = :id"), {"id": to_user_id}
        ).fetchone()

        from_name = from_user.real_name if from_user else "未知"
        to_name = to_user.real_name if to_user else "未知"

        lines = []
        lines.append(f"# 交接班报告")
        lines.append("")
        lines.append(f"**交班人**: {from_name}")
        lines.append(f"**接班人**: {to_name}")
        lines.append(f"**交班时间**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        lines.append(f"**值班时段**: {shift_start.strftime('%Y-%m-%d %H:%M')} - {shift_end.strftime('%Y-%m-%d %H:%M')}")
        lines.append("")
        lines.append("---")
        lines.append("")

        lines.append("## 一、告警汇总")
        if alerts:
            level_counts = {}
            for a in alerts:
                level_counts[a.level] = level_counts.get(a.level, 0) + 1

            lines.append(f"本班次共发生 **{len(alerts)}** 条告警：")
            for level in ["P0", "P1", "P2", "P3"]:
                count = level_counts.get(level, 0)
                if count > 0:
                    lines.append(f"- {level}: {count} 条")
            lines.append("")
            lines.append("### 告警明细")
            lines.append("")
            lines.append("| 级别 | 时间 | 内容 | 状态 |")
            lines.append("|------|------|------|------|")
            for a in alerts[:20]:
                status_map = {
                    "firing": "🔴 触发中",
                    "acknowledged": "🟡 已确认",
                    "resolved": "🟢 已解决",
                }
                lines.append(f"| {a.level} | {a.triggered_at.strftime('%H:%M:%S')} | {a.message[:50]}... | {status_map.get(a.status, a.status)} |")
            if len(alerts) > 20:
                lines.append(f"| ... | ... | 还有 {len(alerts) - 20} 条告警 | ... |")
        else:
            lines.append("本班次无告警，系统运行正常。")
        lines.append("")

        lines.append("## 二、资产变更")
        if changes:
            lines.append(f"本班次共发生 **{len(changes)}** 项资产变更：")
            lines.append("")
            lines.append("| 时间 | 资产 | 变更字段 | 原值 | 新值 |")
            lines.append("|------|------|----------|------|------|")
            for c in changes[:15]:
                asset = self.db.execute(
                    text("SELECT name FROM assets WHERE id = :id"), {"id": c.asset_id}
                ).fetchone()
                asset_name = asset.name if asset else f"ID:{c.asset_id}"
                lines.append(f"| {c.changed_at.strftime('%H:%M:%S')} | {asset_name} | {c.field_name} | {c.old_value or '-'} | {c.new_value or '-'} |")
        else:
            lines.append("本班次无资产变更记录。")
        lines.append("")

        lines.append("## 三、注意事项")
        lines.append("")
        lines.append("1. 请重点关注RabbitMQ服务状态，目前仍有消息积压")
        lines.append("2. 支付服务CPU使用率偏高，建议排查是否有异常流量")
        lines.append("3. 明日凌晨2:00将进行数据库例行备份，可能会有短暂性能抖动")
        lines.append("")

        if custom_content:
            lines.append("## 四、补充说明")
            lines.append("")
            lines.append(custom_content)
            lines.append("")

        lines.append("---")
        lines.append("")
        lines.append(f"*报告生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}*")

        return "\n".join(lines)

    def get_handover_reports(self, limit: int = 50) -> List[Dict[str, Any]]:
        reports = self.db.query(HandoverReport).order_by(
            HandoverReport.created_at.desc()
        ).limit(limit).all()

        result = []
        for r in reports:
            from_user = self.db.execute(
                text("SELECT real_name FROM users WHERE id = :id"), {"id": r.from_user_id}
            ).fetchone()
            to_user = self.db.execute(
                text("SELECT real_name FROM users WHERE id = :id"), {"id": r.to_user_id}
            ).fetchone()

            result.append({
                "id": r.id,
                "schedule_id": r.schedule_id,
                "from_user": from_user.real_name if from_user else "未知",
                "to_user": to_user.real_name if to_user else "未知",
                "created_at": r.created_at,
                "content_summary": r.content[:100] + "..." if len(r.content) > 100 else r.content,
            })

        return result

    def get_report_by_id(self, report_id: int) -> Optional[Dict[str, Any]]:
        report = self.db.query(HandoverReport).filter(HandoverReport.id == report_id).first()
        if not report:
            return None

        from_user = self.db.execute(
            text("SELECT real_name FROM users WHERE id = :id"), {"id": report.from_user_id}
        ).fetchone()
        to_user = self.db.execute(
            text("SELECT real_name FROM users WHERE id = :id"), {"id": report.to_user_id}
        ).fetchone()

        return {
            "report": report,
            "from_user": from_user.real_name if from_user else "未知",
            "to_user": to_user.real_name if to_user else "未知",
        }

    def get_upcoming_duties(self, days: int = 7) -> List[Dict[str, Any]]:
        today = date.today()
        end_date = today + timedelta(days=days)

        schedules = self.db.query(DutySchedule).filter(
            DutySchedule.duty_date >= today,
            DutySchedule.duty_date <= end_date,
        ).order_by(DutySchedule.duty_date, DutySchedule.shift_type).all()

        return self._enrich_with_user_info(schedules)

    def get_current_week_schedule(self) -> Dict[str, Any]:
        today = date.today()
        monday = today - timedelta(days=today.weekday())
        week_days = [monday + timedelta(days=i) for i in range(7)]
        weekday_names = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

        result = {}
        for i, d in enumerate(week_days):
            schedules = self.get_schedule_by_date(d)
            result[d.isoformat()] = {
                "date": d,
                "weekday": weekday_names[i],
                "is_today": d == today,
                "duties": self._enrich_with_user_info(schedules),
            }

        return result
