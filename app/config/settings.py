from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "StructuredLoggingPlatform"
    app_version: str = "1.0.0"
    environment: str = "development"

    host: str = "0.0.0.0"
    port: int = 8000

    log_level: str = "INFO"
    log_format: str = "json"

    database_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/platform"
    database_echo: bool = False
    database_pool_size: int = 10
    database_max_overflow: int = 20

    redis_url: str = "redis://localhost:6379/0"

    secret_key: str = "your-secret-key-change-in-production"
    access_token_expire_minutes: int = 30
    algorithm: str = "HS256"

    rate_limit_requests: int = 100
    rate_limit_window_seconds: int = 60

    s3_endpoint_url: Optional[str] = None
    s3_access_key_id: Optional[str] = None
    s3_secret_access_key: Optional[str] = None
    s3_bucket_name: str = "platform-data"
    s3_region_name: str = "cn-east-1"

    gpu_total_memory_gb: int = 64
    gpu_scheduler_interval: float = 0.1

    celery_broker_url: str = "redis://localhost:6379/1"
    celery_result_backend: str = "redis://localhost:6379/2"

    prometheus_port: int = 9090
    metrics_enabled: bool = True

    feature_store_ttl_seconds: int = 86400
    feature_store_consistency_check: bool = True

    cors_origins: list = ["*"]
    auto_migrate: bool = True

    @property
    def is_production(self) -> bool:
        return self.environment == "production"


settings = Settings()
