import uuid
from typing import Optional, List, Dict, Any
from datetime import datetime
from sqlalchemy.orm import Session

from reporthub.models import ReportStat


class StatisticsModule:
    def __init__(self, db: Session):
        self.db = db

    def _get_current_month(self) -> str:
        return datetime.utcnow().strftime("%Y-%m")

    def _get_or_create_stat(self, template_id: str, stat_month: str) -> ReportStat:
        stat = self.db.query(ReportStat).filter(
            ReportStat.template_id == template_id,
            ReportStat.stat_month == stat_month
        ).first()
        if not stat:
            stat_id = f"stat_{uuid.uuid4().hex[:12]}"
            stat = ReportStat(
                stat_id=stat_id,
                template_id=template_id,
                stat_month=stat_month,
                generate_count=0,
                export_count=0,
                total_rows=0,
                avg_generate_time=0,
                total_generate_time=0
            )
            self.db.add(stat)
            self.db.commit()
            self.db.refresh(stat)
        return stat

    def update_generate_stats(self, template_id: str, generate_time_ms: int, rows_count: int) -> None:
        stat_month = self._get_current_month()
        stat = self._get_or_create_stat(template_id, stat_month)
        stat.generate_count += 1
        stat.total_rows += rows_count
        stat.total_generate_time += generate_time_ms
        if stat.generate_count > 0:
            stat.avg_generate_time = int(stat.total_generate_time / stat.generate_count)
        self.db.commit()
        self.db.refresh(stat)

    def update_export_stats(self, template_id: str) -> None:
        stat_month = self._get_current_month()
        stat = self._get_or_create_stat(template_id, stat_month)
        stat.export_count += 1
        self.db.commit()
        self.db.refresh(stat)

    def get_template_statistics(self, template_id: str, stat_month: Optional[str] = None) -> Optional[ReportStat]:
        stat_month = stat_month or self._get_current_month()
        return self.db.query(ReportStat).filter(
            ReportStat.template_id == template_id,
            ReportStat.stat_month == stat_month
        ).first()

    def get_all_statistics(self, stat_month: Optional[str] = None) -> List[ReportStat]:
        stat_month = stat_month or self._get_current_month()
        return self.db.query(ReportStat).filter(ReportStat.stat_month == stat_month).all()

    def get_trend_analysis(self, template_id: str, months: int = 6) -> List[Dict[str, Any]]:
        from dateutil.relativedelta import relativedelta
        end_date = datetime.utcnow()
        trends = []
        for i in range(months):
            month_date = end_date - relativedelta(months=i)
            stat_month = month_date.strftime("%Y-%m")
            stat = self.db.query(ReportStat).filter(
                ReportStat.template_id == template_id,
                ReportStat.stat_month == stat_month
            ).first()
            if stat:
                trends.append({
                    "month": stat_month,
                    "generate_count": stat.generate_count,
                    "export_count": stat.export_count,
                    "total_rows": stat.total_rows,
                    "avg_generate_time": stat.avg_generate_time
                })
            else:
                trends.append({
                    "month": stat_month,
                    "generate_count": 0,
                    "export_count": 0,
                    "total_rows": 0,
                    "avg_generate_time": 0
                })
        return list(reversed(trends))

    def get_summary_report(self, template_id: str, stat_month: Optional[str] = None) -> Dict[str, Any]:
        stat = self.get_template_statistics(template_id, stat_month)
        if not stat:
            return {}
        summary = {
            "template_id": template_id,
            "stat_month": stat.stat_month,
            "generate_count": stat.generate_count,
            "export_count": stat.export_count,
            "total_rows": stat.total_rows,
            "avg_generate_time": stat.avg_generate_time,
            "export_ratio": round(stat.export_count / max(stat.generate_count, 1) * 100, 2),
            "avg_rows_per_report": int(stat.total_rows / max(stat.generate_count, 1))
        }
        return summary
