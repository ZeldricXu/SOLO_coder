from datetime import datetime
from app import db
import json
from cryptography.fernet import Fernet
from flask import current_app


class DataSource(db.Model):
    __tablename__ = 'datasources'

    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(200), nullable=False, index=True)
    description = db.Column(db.Text)
    type = db.Column(db.String(50), nullable=False)
    owner_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)
    team_id = db.Column(db.Integer, db.ForeignKey('teams.id'))
    connection_config = db.Column(db.LargeBinary)
    query_templates = db.Column(db.JSON)
    is_public = db.Column(db.Boolean, default=False)
    status = db.Column(db.String(20), default='active')
    cache_ttl = db.Column(db.Integer)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    last_used_at = db.Column(db.DateTime)
    query_count = db.Column(db.Integer, default=0)

    owner = db.relationship('User', back_populates='datasources')
    team = db.relationship('Team', back_populates='datasources')
    charts = db.relationship('Chart', back_populates='datasource')

    TYPES = {
        'mysql': 'MySQL',
        'clickhouse': 'ClickHouse',
        'prometheus': 'Prometheus',
        'http': 'HTTP API',
    }

    def _get_cipher(self):
        key = current_app.config['SECRET_KEY'].ljust(32)[:32].encode()
        return Fernet(Fernet.generate_key() if len(key) != 32 else key)

    def get_connection_config(self):
        if not self.connection_config:
            return {}
        try:
            cipher = self._get_cipher()
            decrypted = cipher.decrypt(self.connection_config)
            return json.loads(decrypted.decode())
        except Exception:
            if isinstance(self.connection_config, bytes):
                try:
                    return json.loads(self.connection_config.decode())
                except json.JSONDecodeError:
                    return {}
            return {}

    def set_connection_config(self, config):
        cipher = self._get_cipher()
        encrypted = cipher.encrypt(json.dumps(config).encode())
        self.connection_config = encrypted

    def get_query_templates(self):
        if isinstance(self.query_templates, str):
            try:
                return json.loads(self.query_templates)
            except json.JSONDecodeError:
                return []
        return self.query_templates or []

    def set_query_templates(self, templates):
        self.query_templates = templates

    def increment_query_count(self):
        self.query_count += 1
        self.last_used_at = datetime.utcnow()
        db.session.add(self)

    def test_connection(self):
        from app.services.datasource_service import test_datasource_connection
        return test_datasource_connection(self)

    def execute_query(self, query_template, params=None):
        from app.services.datasource_service import execute_query
        return execute_query(self, query_template, params)

    def to_dict(self, include_config=False):
        data = {
            'id': self.id,
            'name': self.name,
            'description': self.description,
            'type': self.type,
            'type_name': self.TYPES.get(self.type, self.type),
            'owner_id': self.owner_id,
            'owner_name': self.owner.name if self.owner else None,
            'team_id': self.team_id,
            'team_name': self.team.name if self.team else None,
            'is_public': self.is_public,
            'status': self.status,
            'cache_ttl': self.cache_ttl,
            'query_templates': self.get_query_templates(),
            'created_at': self.created_at.isoformat() if self.created_at else None,
            'updated_at': self.updated_at.isoformat() if self.updated_at else None,
            'query_count': self.query_count,
        }
        if include_config:
            config = self.get_connection_config()
            if 'password' in config:
                config['password'] = '********'
            data['connection_config'] = config
        return data
