from pydantic_settings import BaseSettings
from pydantic import Field
from typing import Optional
from pathlib import Path


class Settings(BaseSettings):
    app_name: str = Field(default="privacy_compute_service", env="APP_NAME")
    app_env: str = Field(default="development", env="APP_ENV")
    app_debug: bool = Field(default=True, env="APP_DEBUG")

    database_url: str = Field(default="sqlite+aiosqlite:///./data/app.db", env="DATABASE_URL")
    redis_url: str = Field(default="redis://localhost:6379/0", env="REDIS_URL")

    secret_key: str = Field(default="default-secret-key", env="SECRET_KEY")
    algorithm: str = Field(default="HS256", env="ALGORITHM")

    log_level: str = Field(default="INFO", env="LOG_LEVEL")
    log_file: str = Field(default="./logs/app.log", env="LOG_FILE")

    storage_path: str = Field(default="./data/storage", env="STORAGE_PATH")
    backup_path: str = Field(default="./data/backups", env="BACKUP_PATH")
    audit_log_path: str = Field(default="./data/audit", env="AUDIT_LOG_PATH")

    default_epsilon: float = Field(default=1.0)
    default_delta: float = Field(default=1e-5)
    max_privacy_budget: float = Field(default=10.0)

    class Config:
        env_file = ".env"
        extra = "ignore"

    def ensure_dirs(self):
        for path in [self.storage_path, self.backup_path, self.audit_log_path, "./logs", "./data"]:
            Path(path).mkdir(parents=True, exist_ok=True)


settings = Settings()
