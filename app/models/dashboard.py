from datetime import datetime
from app import db
import json


class Dashboard(db.Model):
    __tablename__ = 'dashboards'

    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(200), nullable=False, index=True)
    description = db.Column(db.Text)
    owner_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)
    team_id = db.Column(db.Integer, db.ForeignKey('teams.id'))
    layout_config = db.Column(db.JSON)
    settings = db.Column(db.JSON)
    refresh_interval = db.Column(db.Integer, default=30)
    is_public = db.Column(db.Boolean, default=False)
    status = db.Column(db.String(20), default='active')
    created_at = db.Column(db.DateTime, default=datetime.utcnow, index=True)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    last_viewed_at = db.Column(db.DateTime)
    view_count = db.Column(db.Integer, default=0)

    owner = db.relationship('User', back_populates='dashboards')
    team = db.relationship('Team', back_populates='dashboards')
    charts = db.relationship('Chart', back_populates='dashboard', cascade='all, delete-orphan')
    shares = db.relationship('DashboardShare', back_populates='dashboard', cascade='all, delete-orphan')
    share_links = db.relationship('ShareLink', back_populates='dashboard', cascade='all, delete-orphan')
    reports = db.relationship('Report', back_populates='dashboard')
    report_schedules = db.relationship('ReportSchedule', back_populates='dashboard')

    def get_layout_config(self):
        if isinstance(self.layout_config, str):
            try:
                return json.loads(self.layout_config)
            except json.JSONDecodeError:
                return {'grid': {'cols': 12, 'rowHeight': 50}, 'widgets': []}
        return self.layout_config or {'grid': {'cols': 12, 'rowHeight': 50}, 'widgets': []}

    def set_layout_config(self, config):
        self.layout_config = config

    def get_settings(self):
        if isinstance(self.settings, str):
            try:
                return json.loads(self.settings)
            except json.JSONDecodeError:
                return {}
        return self.settings or {}

    def set_settings(self, settings):
        self.settings = settings

    def increment_view_count(self):
        self.view_count += 1
        self.last_viewed_at = datetime.utcnow()
        db.session.add(self)

    def to_dict(self, include_layout=True, include_charts=False):
        data = {
            'id': self.id,
            'name': self.name,
            'description': self.description,
            'owner_id': self.owner_id,
            'owner_name': self.owner.name if self.owner else None,
            'team_id': self.team_id,
            'team_name': self.team.name if self.team else None,
            'refresh_interval': self.refresh_interval,
            'is_public': self.is_public,
            'status': self.status,
            'created_at': self.created_at.isoformat() if self.created_at else None,
            'updated_at': self.updated_at.isoformat() if self.updated_at else None,
            'view_count': self.view_count,
        }
        if include_layout:
            data['layout_config'] = self.get_layout_config()
            data['settings'] = self.get_settings()
        if include_charts:
            data['charts'] = [chart.to_dict() for chart in self.charts]
        return data


class DashboardShare(db.Model):
    __tablename__ = 'dashboard_shares'

    id = db.Column(db.Integer, primary_key=True)
    dashboard_id = db.Column(db.Integer, db.ForeignKey('dashboards.id'), nullable=False, index=True)
    user_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False, index=True)
    can_edit = db.Column(db.Boolean, default=False)
    can_share = db.Column(db.Boolean, default=False)
    shared_by = db.Column(db.Integer, db.ForeignKey('users.id'))
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

    dashboard = db.relationship('Dashboard', back_populates='shares', foreign_keys=[dashboard_id])
    user = db.relationship('User', foreign_keys=[user_id])
    shared_by_user = db.relationship('User', foreign_keys=[shared_by])

    __table_args__ = (db.UniqueConstraint('dashboard_id', 'user_id', name='_dashboard_user_share_uc'),)
