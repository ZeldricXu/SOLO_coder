from datetime import datetime, timedelta
from app import db
import secrets


class ShareLink(db.Model):
    __tablename__ = 'share_links'

    id = db.Column(db.Integer, primary_key=True)
    token = db.Column(db.String(64), unique=True, nullable=False, index=True)
    dashboard_id = db.Column(db.Integer, db.ForeignKey('dashboards.id'), nullable=False, index=True)
    user_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)
    permission = db.Column(db.String(20), default='view')
    expires_at = db.Column(db.DateTime)
    password_hash = db.Column(db.String(255))
    max_views = db.Column(db.Integer)
    view_count = db.Column(db.Integer, default=0)
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    last_accessed_at = db.Column(db.DateTime)

    dashboard = db.relationship('Dashboard', back_populates='share_links')
    user = db.relationship('User', back_populates='share_links')

    @classmethod
    def generate_token(cls):
        return secrets.token_urlsafe(32)

    @classmethod
    def create(cls, dashboard_id, user_id, permission='view', expires_hours=24,
               password=None, max_views=None):
        token = cls.generate_token()
        share = cls(
            token=token,
            dashboard_id=dashboard_id,
            user_id=user_id,
            permission=permission,
            max_views=max_views,
        )
        if expires_hours:
            share.expires_at = datetime.utcnow() + timedelta(hours=expires_hours)
        if password:
            from werkzeug.security import generate_password_hash
            share.password_hash = generate_password_hash(password)
        db.session.add(share)
        db.session.commit()
        return share

    def is_valid(self):
        if not self.is_active:
            return False
        if self.expires_at and datetime.utcnow() > self.expires_at:
            return False
        if self.max_views and self.view_count >= self.max_views:
            return False
        return True

    def check_password(self, password):
        if not self.password_hash:
            return True
        from werkzeug.security import check_password_hash
        return check_password_hash(self.password_hash, password)

    def increment_view(self):
        self.view_count += 1
        self.last_accessed_at = datetime.utcnow()
        if self.max_views and self.view_count >= self.max_views:
            self.is_active = False
        db.session.add(self)

    def revoke(self):
        self.is_active = False
        db.session.add(self)

    def to_dict(self):
        return {
            'id': self.id,
            'token': self.token,
            'dashboard_id': self.dashboard_id,
            'dashboard_name': self.dashboard.name if self.dashboard else None,
            'permission': self.permission,
            'expires_at': self.expires_at.isoformat() if self.expires_at else None,
            'has_password': self.password_hash is not None,
            'max_views': self.max_views,
            'view_count': self.view_count,
            'is_active': self.is_active,
            'is_valid': self.is_valid(),
            'created_at': self.created_at.isoformat() if self.created_at else None,
            'last_accessed_at': self.last_accessed_at.isoformat() if self.last_accessed_at else None,
        }
