from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    app_name: str = "IoT Platform"
    version: str = "1.0.0"
    environment: str = "development"

    database_url: str = "sqlite+aiosqlite:///./iot_platform.db"
    database_echo: bool = False

    redis_url: str = "redis://localhost:6379/0"
    cache_ttl: int = 300

    api_host: str = "0.0.0.0"
    api_port: int = 8000

    jwt_secret: str = "your-secret-key-change-in-production"
    jwt_algorithm: str = "HS256"
    access_token_expire_minutes: int = 60

    log_level: str = "info"
    log_format: str = "json"

    ota_storage_path: str = "./data/ota"
    file_storage_path: str = "./data/files"

    celery_broker_url: str = "redis://localhost:6379/1"
    celery_result_backend: str = "redis://localhost:6379/2"

    max_concurrent_tasks: int = 100
    task_timeout_seconds: int = 300

    enable_tracing: bool = True
    enable_metrics: bool = True

    class Config:
        env_file = ".env"


settings = Settings()
