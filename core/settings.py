import os
from functools import lru_cache
from typing import Optional

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class BaseAppSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    app_name: str = "ticket-routing-system"
    app_env: str = Field(default="development", env="APP_ENV")
    app_version: str = "1.0.0"
    app_host: str = Field(default="0.0.0.0", env="APP_HOST")
    app_port: int = Field(default=8000, env="APP_PORT")
    app_debug: bool = Field(default=True, env="APP_DEBUG")
    app_workers: int = Field(default=1, env="APP_WORKERS")

    secret_key: str = Field(default="dev-secret-key-change-in-production", env="SECRET_KEY")
    api_prefix: str = Field(default="/api/v1", env="API_PREFIX")

    cors_origins: list[str] = Field(default=["*"], env="CORS_ORIGINS")

    database_url: str = Field(
        default="sqlite+aiosqlite:///./app.db",
        env="DATABASE_URL",
    )
    sync_database_url: str = Field(
        default="sqlite:///./app.db",
        env="SYNC_DATABASE_URL",
    )
    db_pool_size: int = Field(default=10, env="DB_POOL_SIZE")
    db_max_overflow: int = Field(default=20, env="DB_MAX_OVERFLOW")
    db_pool_recycle: int = Field(default=3600, env="DB_POOL_RECYCLE")
    db_echo: bool = Field(default=False, env="DB_ECHO")

    redis_url: str = Field(default="redis://localhost:6379/0", env="REDIS_URL")
    redis_pool_size: int = Field(default=10, env="REDIS_POOL_SIZE")
    redis_max_connections: int = Field(default=20, env="REDIS_MAX_CONNECTIONS")

    log_level: str = Field(default="INFO", env="LOG_LEVEL")
    log_format: str = Field(
        default="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
        env="LOG_FORMAT",
    )

    jwt_algorithm: str = Field(default="HS256", env="JWT_ALGORITHM")
    jwt_access_token_expire_minutes: int = Field(
        default=30, env="JWT_ACCESS_TOKEN_EXPIRE_MINUTES"
    )
    jwt_refresh_token_expire_days: int = Field(
        default=7, env="JWT_REFRESH_TOKEN_EXPIRE_DAYS"
    )

    rate_limit_per_minute: int = Field(default=60, env="RATE_LIMIT_PER_MINUTE")
    rate_limit_burst: int = Field(default=100, env="RATE_LIMIT_BURST")

    sentry_dsn: Optional[str] = Field(default=None, env="SENTRY_DSN")
    sentry_traces_sample_rate: float = Field(
        default=1.0, env="SENTRY_TRACES_SAMPLE_RATE"
    )

    metrics_enabled: bool = Field(default=True, env="METRICS_ENABLED")
    metrics_port: int = Field(default=9090, env="METRICS_PORT")

    feature_flag_skill_matching: bool = Field(
        default=True, env="FEATURE_FLAG_SKILL_MATCHING"
    )
    feature_flag_load_balancing: bool = Field(
        default=True, env="FEATURE_FLAG_LOAD_BALANCING"
    )
    feature_flag_auto_assignment: bool = Field(
        default=True, env="FEATURE_FLAG_AUTO_ASSIGNMENT"
    )


class DevelopmentSettings(BaseAppSettings):
    model_config = SettingsConfigDict(env_file=".env.development")

    app_debug: bool = True
    log_level: str = "DEBUG"
    db_echo: bool = True


class StagingSettings(BaseAppSettings):
    model_config = SettingsConfigDict(env_file=".env.staging")

    app_debug: bool = False
    log_level: str = "INFO"
    db_echo: bool = False
    rate_limit_per_minute: int = 120


class ProductionSettings(BaseAppSettings):
    model_config = SettingsConfigDict(env_file=".env.production")

    app_debug: bool = False
    log_level: str = "WARNING"
    db_echo: bool = False
    db_pool_size: int = 50
    db_max_overflow: int = 100
    rate_limit_per_minute: int = 300
    rate_limit_burst: int = 500


class SettingsFactory:
    @staticmethod
    def get_settings() -> BaseAppSettings:
        env = os.getenv("APP_ENV", "development").lower()

        if env == "development":
            return DevelopmentSettings()
        elif env == "staging":
            return StagingSettings()
        elif env == "production":
            return ProductionSettings()
        else:
            raise ValueError(f"Unknown environment: {env}")


@lru_cache
def get_settings() -> BaseAppSettings:
    return SettingsFactory.get_settings()
