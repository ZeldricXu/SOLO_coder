from functools import lru_cache
from typing import Optional

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class AppSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False
    )

    app_name: str = "db-pool-platform"
    app_env: str = "development"
    app_debug: bool = True
    app_host: str = "0.0.0.0"
    app_port: int = 8000

    database_url: str = "postgresql+asyncpg://user:password@localhost:5432/appdb"
    database_pool_size: int = 10
    database_max_overflow: int = 20
    database_pool_recycle: int = 3600
    database_echo: bool = False

    redis_url: str = "redis://localhost:6379/0"
    celery_broker_url: str = "redis://localhost:6379/1"
    celery_result_backend: str = "redis://localhost:6379/2"

    config_file: str = "./config/config.yaml"
    config_watch_enabled: bool = True

    storage_local_path: str = "./storage"
    storage_provider: str = "local"

    metrics_enabled: bool = True
    metrics_port: int = 9090

    log_level: str = "INFO"
    log_format: str = "json"

    trace_enabled: bool = True
    trace_exporter: str = "console"

    sbom_cve_db_path: str = "./data/cve_db.json"
    quality_rules_path: str = "./config/quality_rules.yaml"


@lru_cache
def get_settings() -> AppSettings:
    return AppSettings()
