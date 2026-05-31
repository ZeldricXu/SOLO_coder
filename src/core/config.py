from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    app_name: str = "middleware-platform"
    app_env: str = "development"
    debug: bool = True

    host: str = "0.0.0.0"
    port: int = 8000

    database_url: str = "sqlite+aiosqlite:///./platform.db"
    redis_url: str = "redis://localhost:6379/0"

    celery_broker_url: str = "redis://localhost:6379/1"
    celery_result_backend: str = "redis://localhost:6379/2"

    api_gateway_timeout: int = 30
    api_gateway_retries: int = 3
    api_gateway_max_concurrent: int = 100

    storage_backup_path: str = "./backups"
    storage_data_path: str = "./data"

    notification_default_channel: str = "email"

    gpu_total_memory_gb: int = 80
    gpu_max_tasks_per_gpu: int = 4

    drift_detection_threshold: float = 0.05
    metrics_retention_days: int = 30


settings = Settings()
