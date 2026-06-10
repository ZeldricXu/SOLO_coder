from datetime import datetime
from app import db
import json


class Template(db.Model):
    __tablename__ = 'templates'

    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(200), nullable=False, index=True)
    description = db.Column(db.Text)
    category = db.Column(db.String(100), index=True)
    thumbnail = db.Column(db.String(500))
    preview_url = db.Column(db.String(500))
    dashboard_config = db.Column(db.JSON)
    chart_configs = db.Column(db.JSON)
    datasource_config = db.Column(db.JSON)
    is_system = db.Column(db.Boolean, default=False)
    is_public = db.Column(db.Boolean, default=True)
    use_count = db.Column(db.Integer, default=0)
    rating = db.Column(db.Float, default=0)
    rating_count = db.Column(db.Integer, default=0)
    created_by = db.Column(db.Integer, db.ForeignKey('users.id'))
    created_at = db.Column(db.DateTime, default=datetime.utcnow, index=True)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    creator = db.relationship('User', foreign_keys=[created_by])

    CATEGORIES = {
        'sales': '销售分析',
        'marketing': '营销分析',
        'operations': '运营分析',
        'customer': '客户分析',
        'finance': '财务分析',
        'hr': '人力资源',
        'logistics': '物流分析',
        'custom': '自定义',
    }

    def get_dashboard_config(self):
        if isinstance(self.dashboard_config, str):
            try:
                return json.loads(self.dashboard_config)
            except json.JSONDecodeError:
                return {}
        return self.dashboard_config or {}

    def set_dashboard_config(self, config):
        self.dashboard_config = config

    def get_chart_configs(self):
        if isinstance(self.chart_configs, str):
            try:
                return json.loads(self.chart_configs)
            except json.JSONDecodeError:
                return []
        return self.chart_configs or []

    def set_chart_configs(self, configs):
        self.chart_configs = configs

    def get_datasource_config(self):
        if isinstance(self.datasource_config, str):
            try:
                return json.loads(self.datasource_config)
            except json.JSONDecodeError:
                return {}
        return self.datasource_config or {}

    def set_datasource_config(self, config):
        self.datasource_config = config

    def increment_use_count(self):
        self.use_count += 1
        db.session.add(self)

    def add_rating(self, rating):
        total = self.rating * self.rating_count + rating
        self.rating_count += 1
        self.rating = total / self.rating_count
        db.session.add(self)

    def to_dict(self):
        return {
            'id': self.id,
            'name': self.name,
            'description': self.description,
            'category': self.category,
            'category_name': self.CATEGORIES.get(self.category, self.category),
            'thumbnail': self.thumbnail,
            'preview_url': self.preview_url,
            'dashboard_config': self.get_dashboard_config(),
            'chart_configs': self.get_chart_configs(),
            'datasource_config': self.get_datasource_config(),
            'is_system': self.is_system,
            'is_public': self.is_public,
            'use_count': self.use_count,
            'rating': round(self.rating, 1),
            'rating_count': self.rating_count,
            'created_by': self.created_by,
            'creator_name': self.creator.name if self.creator else None,
            'created_at': self.created_at.isoformat() if self.created_at else None,
            'updated_at': self.updated_at.isoformat() if self.updated_at else None,
        }
