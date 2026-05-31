from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    app_name: str = "TaskFlow"
    app_version: str = "1.0.0"
    environment: str = "development"

    database_url: str = "sqlite:///./taskflow.db"
    redis_url: str = "redis://localhost:6379/0"

    cache_ttl: int = 300
    cache_max_size: int = 1000

    log_level: str = "INFO"
    log_file: Optional[str] = None

    broker_url: str = "redis://localhost:6379/1"
    result_backend: str = "redis://localhost:6379/2"

    default_timeout: int = 30
    default_retries: int = 3

    max_concurrent_tasks: int = 100
    worker_prefetch_multiplier: int = 1

    alert_webhook_url: Optional[str] = None
    alert_email_smtp: Optional[str] = None

    environment_ttl_hours: int = 24
    max_environments_per_user: int = 5

    doc_index_path: str = "./.doc_index"

    class Config:
        env_file = ".env"


settings = Settings()
