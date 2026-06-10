from datetime import datetime
from werkzeug.security import generate_password_hash, check_password_hash
from flask_login import UserMixin
from app import db


class Role(db.Model):
    __tablename__ = 'roles'

    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(50), unique=True, nullable=False)
    description = db.Column(db.String(200))
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

    users = db.relationship('User', back_populates='role')

    PERMISSIONS = {
        'admin': ['all'],
        'editor': ['dashboard:create', 'dashboard:edit', 'dashboard:delete',
                   'datasource:create', 'datasource:edit', 'datasource:delete',
                   'chart:create', 'chart:edit', 'chart:delete',
                   'template:use'],
        'viewer': ['dashboard:view', 'template:use'],
    }

    def has_permission(self, permission):
        if self.name == 'admin':
            return True
        perms = self.PERMISSIONS.get(self.name, [])
        return permission in perms or 'all' in perms


class Team(db.Model):
    __tablename__ = 'teams'

    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(100), nullable=False)
    description = db.Column(db.Text)
    created_by = db.Column(db.Integer, db.ForeignKey('users.id'))
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    members = db.relationship('TeamMember', back_populates='team', cascade='all, delete-orphan')
    dashboards = db.relationship('Dashboard', back_populates='team')
    datasources = db.relationship('DataSource', back_populates='team')


class TeamMember(db.Model):
    __tablename__ = 'team_members'

    id = db.Column(db.Integer, primary_key=True)
    team_id = db.Column(db.Integer, db.ForeignKey('teams.id'), nullable=False)
    user_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)
    role = db.Column(db.String(20), default='member')
    joined_at = db.Column(db.DateTime, default=datetime.utcnow)

    team = db.relationship('Team', back_populates='members')
    user = db.relationship('User', back_populates='team_members')

    __table_args__ = (db.UniqueConstraint('team_id', 'user_id', name='_team_user_uc'),)


class User(UserMixin, db.Model):
    __tablename__ = 'users'

    id = db.Column(db.Integer, primary_key=True)
    email = db.Column(db.String(120), unique=True, nullable=False, index=True)
    name = db.Column(db.String(80), nullable=False)
    password_hash = db.Column(db.String(255))
    avatar = db.Column(db.String(255))
    role_id = db.Column(db.Integer, db.ForeignKey('roles.id'), default=3)
    is_active = db.Column(db.Boolean, default=True)
    is_email_verified = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    last_login_at = db.Column(db.DateTime)

    role = db.relationship('Role', back_populates='users')
    team_members = db.relationship('TeamMember', back_populates='user', cascade='all, delete-orphan')
    dashboards = db.relationship('Dashboard', back_populates='owner')
    datasources = db.relationship('DataSource', back_populates='owner')
    charts = db.relationship('Chart', back_populates='owner')
    share_links = db.relationship('ShareLink', back_populates='user')
    reports = db.relationship('Report', back_populates='owner')
    report_schedules = db.relationship('ReportSchedule', back_populates='owner')

    def set_password(self, password):
        self.password_hash = generate_password_hash(password)

    def check_password(self, password):
        return check_password_hash(self.password_hash, password)

    def has_permission(self, permission):
        return self.role and self.role.has_permission(permission)

    def get_teams(self):
        return [tm.team for tm in self.team_members]

    def is_team_admin(self, team_id):
        tm = TeamMember.query.filter_by(team_id=team_id, user_id=self.id).first()
        return tm and tm.role == 'admin'

    def has_dashboard_access(self, dashboard_id, permission='view'):
        from app.models.dashboard import Dashboard, DashboardShare
        dashboard = Dashboard.query.get(dashboard_id)
        if not dashboard:
            return False
        if dashboard.owner_id == self.id:
            return True
        if self.has_permission('all'):
            return True
        if dashboard.team_id:
            tm = TeamMember.query.filter_by(team_id=dashboard.team_id, user_id=self.id).first()
            if tm:
                if permission == 'view':
                    return True
                if permission == 'edit' and tm.role in ['admin', 'editor']:
                    return True
        share = DashboardShare.query.filter_by(
            dashboard_id=dashboard_id,
            user_id=self.id
        ).first()
        if share:
            if permission == 'view':
                return True
            if permission == 'edit' and share.can_edit:
                return True
        return False
