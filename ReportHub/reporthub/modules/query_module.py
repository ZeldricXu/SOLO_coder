from typing import Optional, List, Dict, Any
from datetime import datetime, timedelta
from sqlalchemy.orm import Session

from reporthub.models import Report, ReportTemplate


class QueryModule:
    def __init__(self, db: Session):
        self.db = db

    def get_report_by_id(self, report_id: str) -> Optional[Report]:
        return self.db.query(Report).filter(Report.report_id == report_id).first()

    def get_reports_by_template(self, template_id: str, limit: int = 100,
                                offset: int = 0) -> List[Report]:
        return self.db.query(Report).filter(
            Report.template_id == template_id
        ).order_by(Report.generated_at.desc()).limit(limit).offset(offset).all()

    def get_reports_by_date_range(self, template_id: Optional[str] = None,
                                  start_date: Optional[datetime] = None,
                                  end_date: Optional[datetime] = None,
                                  limit: int = 100, offset: int = 0) -> List[Report]:
        query = self.db.query(Report)
        if template_id:
            query = query.filter(Report.template_id == template_id)
        if start_date:
            query = query.filter(Report.generated_at >= start_date)
        if end_date:
            query = query.filter(Report.generated_at <= end_date)
        return query.order_by(Report.generated_at.desc()).limit(limit).offset(offset).all()

    def get_reports_by_generator(self, generator: str, limit: int = 100,
                                 offset: int = 0) -> List[Report]:
        return self.db.query(Report).filter(
            Report.generator == generator
        ).order_by(Report.generated_at.desc()).limit(limit).offset(offset).all()

    def get_reports_by_status(self, status: str, limit: int = 100,
                              offset: int = 0) -> List[Report]:
        return self.db.query(Report).filter(
            Report.status == status
        ).order_by(Report.generated_at.desc()).limit(limit).offset(offset).all()

    def search_reports(self, keyword: Optional[str] = None,
                       template_id: Optional[str] = None,
                       status: Optional[str] = None,
                       start_date: Optional[datetime] = None,
                       end_date: Optional[datetime] = None,
                       limit: int = 100,
                       offset: int = 0) -> List[Report]:
        query = self.db.query(Report)
        if keyword:
            query = query.filter(Report.report_name.ilike(f"%{keyword}%"))
        if template_id:
            query = query.filter(Report.template_id == template_id)
        if status:
            query = query.filter(Report.status == status)
        if start_date:
            query = query.filter(Report.generated_at >= start_date)
        if end_date:
            query = query.filter(Report.generated_at <= end_date)
        return query.order_by(Report.generated_at.desc()).limit(limit).offset(offset).all()

    def get_recent_reports(self, days: int = 7, limit: int = 100) -> List[Report]:
        cutoff_date = datetime.utcnow() - timedelta(days=days)
        return self.db.query(Report).filter(
            Report.generated_at >= cutoff_date
        ).order_by(Report.generated_at.desc()).limit(limit).all()

    def get_report_count(self, template_id: Optional[str] = None,
                         start_date: Optional[datetime] = None,
                         end_date: Optional[datetime] = None) -> int:
        query = self.db.query(Report)
        if template_id:
            query = query.filter(Report.template_id == template_id)
        if start_date:
            query = query.filter(Report.generated_at >= start_date)
        if end_date:
            query = query.filter(Report.generated_at <= end_date)
        return query.count()

    def get_report_data(self, report_id: str) -> Optional[Dict[str, Any]]:
        report = self.get_report_by_id(report_id)
        if not report:
            return None
        return report.report_data

    def get_report_file_path(self, report_id: str) -> Optional[str]:
        report = self.get_report_by_id(report_id)
        if not report:
            return None
        return report.report_file
