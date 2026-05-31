from functools import lru_cache
from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict


class AppSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    APP_NAME: str = "task-orchestrator"
    APP_ENV: str = "development"
    APP_HOST: str = "0.0.0.0"
    APP_PORT: int = 8000
    DEBUG: bool = False
    CORS_ORIGINS: list = ["*"]

    LOG_LEVEL: str = "INFO"
    LOG_DIR: str = "./logs"
    LOG_MAX_BYTES: int = 10 * 1024 * 1024
    LOG_BACKUP_COUNT: int = 10
    LOG_ARCHIVE_DIR: str = "./logs/archive"

    DATABASE_URL: str = "postgresql+asyncpg://user:password@localhost:5432/taskdb"
    DATABASE_POOL_SIZE: int = 20
    DATABASE_MAX_OVERFLOW: int = 10
    DATABASE_POOL_RECYCLE: int = 3600

    REDIS_URL: str = "redis://localhost:6379/0"

    CELERY_BROKER_URL: str = "redis://localhost:6379/1"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/2"

    S3_ENDPOINT: str = "http://localhost:9000"
    S3_ACCESS_KEY: str = "minioadmin"
    S3_SECRET_KEY: str = "minioadmin"
    S3_BUCKET: str = "task-orchestrator"
    S3_REGION: str = "us-east-1"

    NOTIFICATION_RETRY_MAX_ATTEMPTS: int = 3
    NOTIFICATION_RETRY_DELAY: int = 5
    NOTIFICATION_TIMEOUT: int = 30

    SCHEDULER_MAX_WORKERS: int = 10
    SCHEDULER_TASK_TIMEOUT: int = 3600

    QUALITY_GATE_THRESHOLD_COMPLEXITY: int = 10
    QUALITY_GATE_THRESHOLD_COVERAGE: float = 80.0
    QUALITY_GATE_THRESHOLD_DUPLICATION: float = 5.0


@lru_cache
def get_settings() -> AppSettings:
    return AppSettings()
