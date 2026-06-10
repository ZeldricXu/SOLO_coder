import os
from datetime import timedelta
from dotenv import load_dotenv

load_dotenv()


class Config:
    SECRET_KEY = os.getenv('SECRET_KEY', 'dev-secret-key-change-in-production')
    FLASK_ENV = os.getenv('FLASK_ENV', 'development')
    DEBUG = FLASK_ENV == 'development'

    SQLALCHEMY_DATABASE_URI = os.getenv('DATABASE_URL', 'sqlite:///dashboard.db')
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    SQLALCHEMY_ENGINE_OPTIONS = {
        'pool_pre_ping': True,
        'pool_recycle': 300,
    }

    REDIS_URL = os.getenv('REDIS_URL', 'redis://localhost:6379/0')
    CELERY_BROKER_URL = os.getenv('CELERY_BROKER_URL', 'redis://localhost:6379/1')
    CELERY_RESULT_BACKEND = os.getenv('CELERY_RESULT_BACKEND', 'redis://localhost:6379/2')

    CACHE_TTL = {
        'default': int(os.getenv('CACHE_TTL_DEFAULT', 300)),
        'mysql': int(os.getenv('CACHE_TTL_MYSQL', 60)),
        'clickhouse': int(os.getenv('CACHE_TTL_CLICKHOUSE', 30)),
        'prometheus': int(os.getenv('CACHE_TTL_PROMETHEUS', 15)),
        'http': int(os.getenv('CACHE_TTL_HTTP', 60)),
    }

    MAIL_SERVER = os.getenv('MAIL_SERVER', 'smtp.gmail.com')
    MAIL_PORT = int(os.getenv('MAIL_PORT', 587))
    MAIL_USE_TLS = os.getenv('MAIL_USE_TLS', 'true').lower() == 'true'
    MAIL_USERNAME = os.getenv('MAIL_USERNAME', '')
    MAIL_PASSWORD = os.getenv('MAIL_PASSWORD', '')
    MAIL_DEFAULT_SENDER = os.getenv('MAIL_DEFAULT_SENDER', MAIL_USERNAME)

    SSE_HEARTBEAT_INTERVAL = int(os.getenv('SSE_HEARTBEAT_INTERVAL', 15))
    MAX_SSE_CONNECTIONS = int(os.getenv('MAX_SSE_CONNECTIONS', 1000))

    PLAYWRIGHT_EXECUTABLE_PATH = os.getenv('PLAYWRIGHT_EXECUTABLE_PATH')

    SHARE_LINK_EXPIRY_HOURS = 24
    MAX_SHARE_LINK_EXPIRY_DAYS = 30

    UPLOAD_FOLDER = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'uploads')
    EXPORT_FOLDER = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'exports')
    SNAPSHOT_FOLDER = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'data', 'snapshots')

    MAX_CONTENT_LENGTH = 50 * 1024 * 1024

    WTF_CSRF_ENABLED = True
    WTF_CSRF_TIME_LIMIT = None

    PERMANENT_SESSION_LIFETIME = timedelta(days=7)


class DevelopmentConfig(Config):
    DEBUG = True
    TESTING = False


class ProductionConfig(Config):
    DEBUG = False
    TESTING = False


class TestingConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = 'sqlite:///:memory:'


config = {
    'development': DevelopmentConfig,
    'production': ProductionConfig,
    'testing': TestingConfig,
    'default': DevelopmentConfig,
}
