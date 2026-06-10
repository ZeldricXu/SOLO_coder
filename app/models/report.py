from datetime import datetime, timedelta
from app import db
import json


class ReportSchedule(db.Model):
    __tablename__ = 'report_schedules'

    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(200), nullable=False)
    dashboard_id = db.Column(db.Integer, db.ForeignKey('dashboards.id'), nullable=False, index=True)
    owner_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)
    cron_expression = db.Column(db.String(100))
    interval_minutes = db.Column(db.Integer)
    recipients = db.Column(db.JSON)
    report_type = db.Column(db.String(20), default='pdf')
    include_snapshot = db.Column(db.Boolean, default=True)
    include_data = db.Column(db.Boolean, default=False)
    timezone = db.Column(db.String(50), default='Asia/Shanghai')
    is_active = db.Column(db.Boolean, default=True)
    start_time = db.Column(db.DateTime)
    end_time = db.Column(db.DateTime)
    last_run_at = db.Column(db.DateTime)
    next_run_at = db.Column(db.DateTime)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    dashboard = db.relationship('Dashboard', back_populates='report_schedules')
    owner = db.relationship('User', back_populates='report_schedules')
    reports = db.relationship('Report', back_populates='schedule', cascade='all, delete-orphan')

    REPORT_TYPES = {
        'pdf': 'PDF文档',
        'png': 'PNG图片',
        'excel': 'Excel文件',
    }

    def get_recipients(self):
        if isinstance(self.recipients, str):
            try:
                return json.loads(self.recipients)
            except json.JSONDecodeError:
                return []
        return self.recipients or []

    def set_recipients(self, recipients):
        self.recipients = recipients

    def get_next_run_time(self):
        now = datetime.utcnow()

        if self.end_time and now >= self.end_time:
            self.is_active = False
            return None

        if self.start_time and now < self.start_time:
            return self.start_time

        if self.cron_expression:
            from croniter import croniter
            iter = croniter(self.cron_expression, now)
            next_run = iter.get_next(datetime)
            if self.end_time and next_run > self.end_time:
                return None
            return next_run
        elif self.interval_minutes:
            next_run = now + timedelta(minutes=self.interval_minutes)
            if self.end_time and next_run > self.end_time:
                if now < self.end_time:
                    return self.end_time
                return None
            if self.start_time and next_run < self.start_time:
                return self.start_time
            return next_run
        return None

    def is_within_window(self):
        now = datetime.utcnow()
        if self.start_time and now < self.start_time:
            return False
        if self.end_time and now > self.end_time:
            return False
        return True

    def to_dict(self):
        return {
            'id': self.id,
            'name': self.name,
            'dashboard_id': self.dashboard_id,
            'dashboard_name': self.dashboard.name if self.dashboard else None,
            'cron_expression': self.cron_expression,
            'interval_minutes': self.interval_minutes,
            'recipients': self.get_recipients(),
            'report_type': self.report_type,
            'report_type_name': self.REPORT_TYPES.get(self.report_type, self.report_type),
            'include_snapshot': self.include_snapshot,
            'include_data': self.include_data,
            'timezone': self.timezone,
            'is_active': self.is_active,
            'start_time': self.start_time.isoformat() if self.start_time else None,
            'end_time': self.end_time.isoformat() if self.end_time else None,
            'is_within_window': self.is_within_window(),
            'last_run_at': self.last_run_at.isoformat() if self.last_run_at else None,
            'next_run_at': self.next_run_at.isoformat() if self.next_run_at else None,
            'created_at': self.created_at.isoformat() if self.created_at else None,
        }


class Report(db.Model):
    __tablename__ = 'reports'

    id = db.Column(db.Integer, primary_key=True)
    schedule_id = db.Column(db.Integer, db.ForeignKey('report_schedules.id'), index=True)
    dashboard_id = db.Column(db.Integer, db.ForeignKey('dashboards.id'), nullable=False, index=True)
    owner_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)
    title = db.Column(db.String(200))
    file_path = db.Column(db.String(500))
    file_type = db.Column(db.String(20))
    file_size = db.Column(db.Integer)
    status = db.Column(db.String(20), default='pending')
    error_message = db.Column(db.Text)
    snapshot_url = db.Column(db.String(500))
    data_summary = db.Column(db.JSON)
    sent_to = db.Column(db.JSON)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, index=True)
    completed_at = db.Column(db.DateTime)

    dashboard = db.relationship('Dashboard', back_populates='reports')
    owner = db.relationship('User', back_populates='reports')
    schedule = db.relationship('ReportSchedule', back_populates='reports')

    STATUS = {
        'pending': '等待中',
        'generating': '生成中',
        'completed': '已完成',
        'failed': '失败',
        'sent': '已发送',
    }

    def get_data_summary(self):
        if isinstance(self.data_summary, str):
            try:
                return json.loads(self.data_summary)
            except json.JSONDecodeError:
                return {}
        return self.data_summary or {}

    def set_data_summary(self, summary):
        self.data_summary = summary

    def get_sent_to(self):
        if isinstance(self.sent_to, str):
            try:
                return json.loads(self.sent_to)
            except json.JSONDecodeError:
                return []
        return self.sent_to or []

    def set_sent_to(self, sent_to):
        self.sent_to = sent_to

    def to_dict(self):
        return {
            'id': self.id,
            'schedule_id': self.schedule_id,
            'dashboard_id': self.dashboard_id,
            'dashboard_name': self.dashboard.name if self.dashboard else None,
            'title': self.title,
            'file_path': self.file_path,
            'file_type': self.file_type,
            'file_size': self.file_size,
            'status': self.status,
            'status_name': self.STATUS.get(self.status, self.status),
            'error_message': self.error_message,
            'snapshot_url': self.snapshot_url,
            'data_summary': self.get_data_summary(),
            'sent_to': self.get_sent_to(),
            'created_at': self.created_at.isoformat() if self.created_at else None,
            'completed_at': self.completed_at.isoformat() if self.completed_at else None,
        }
