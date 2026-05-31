from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    app_name: str = "DevPortal"
    app_env: str = "development"
    debug: bool = True

    database_url: str = "sqlite+aiosqlite:///./data/devportal.db"
    sync_database_url: str = "sqlite:///./data/devportal.db"

    redis_url: str = "redis://localhost:6379/0"
    celery_broker_url: str = "redis://localhost:6379/0"
    celery_result_backend: str = "redis://localhost:6379/0"

    api_prefix: str = "/api/v1"
    secret_key: str = "devportal-secret-key-change-in-production"
    access_token_expire_minutes: int = 60 * 24 * 7

    whoosh_index_dir: str = "./data/whoosh_index"
    template_dir: str = "./templates"
    data_dir: str = "./data"

    nvd_api_url: str = "https://services.nvd.nist.gov/rest/json/cves/2.0"
    nvd_api_key: Optional[str] = None

    default_environment_ttl_hours: int = 24
    max_environments_per_user: int = 5

    quality_gate_default_thresholds: dict = {
        "coverage": 80.0,
        "duplication": 3.0,
        "complexity": 10,
        "vulnerabilities": 0,
        "bugs": 0,
    }


settings = Settings()
