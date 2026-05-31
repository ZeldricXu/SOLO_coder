from __future__ import annotations

import os
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Environment(str, Enum):
    DEVELOPMENT = "development"
    STAGING = "staging"
    PRODUCTION = "production"


class CacheType(str, Enum):
    MEMORY = "memory"
    REDIS = "redis"
    MEMCACHED = "memcached"


class StorageProvider(str, Enum):
    LOCAL = "local"
    S3 = "s3"
    AZURE = "azure"
    GCS = "gcs"


class DatabaseSettings(BaseSettings):
    url: str = "sqlite+aiosqlite:///./dev.db"
    pool_size: int = 10
    max_overflow: int = 20
    pool_recycle: int = 3600
    statement_timeout: int = 30000

    model_config = SettingsConfigDict(env_prefix="DATABASE_", extra="ignore")


class CacheSettings(BaseSettings):
    type: CacheType = CacheType.MEMORY
    url: str = "redis://localhost:6379/0"
    ttl: int = 3600
    max_size: int = 10000

    model_config = SettingsConfigDict(env_prefix="CACHE_", extra="ignore")


class RedisSettings(BaseSettings):
    url: str = "redis://localhost:6379/1"
    pool_size: int = 50
    socket_timeout: int = 5000
    socket_connect_timeout: int = 5000

    model_config = SettingsConfigDict(env_prefix="REDIS_", extra="ignore")


class JWTSettings(BaseSettings):
    secret_key: str = "dev-secret-key"
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 60
    refresh_token_expire_days: int = 7

    model_config = SettingsConfigDict(env_prefix="JWT_", extra="ignore")


class RateLimitSettings(BaseSettings):
    default: int = 100
    window: int = 60
    burst: int = 150

    model_config = SettingsConfigDict(env_prefix="RATE_LIMIT_", extra="ignore")


class StorageSettings(BaseSettings):
    provider: StorageProvider = StorageProvider.LOCAL
    local_path: str = "./storage"
    s3_endpoint: str = ""
    s3_bucket: str = ""
    s3_access_key: str = ""
    s3_secret_key: str = ""
    s3_sse: str = ""

    model_config = SettingsConfigDict(env_prefix="STORAGE_", extra="ignore")


class MonitoringSettings(BaseSettings):
    enabled: bool = True
    metrics_port: int = 9090
    alert_webhook_url: str = ""
    alert_slack_webhook: str = ""
    alert_email_from: str = ""

    model_config = SettingsConfigDict(env_prefix="MONITORING_", extra="ignore")


class DocumentSettings(BaseSettings):
    index_path: str = "./index"
    sync_interval: int = 300

    model_config = SettingsConfigDict(env_prefix="DOCUMENT_", extra="ignore")


class ConfigSettings(BaseSettings):
    watch_enabled: bool = True
    refresh_interval: int = 60

    model_config = SettingsConfigDict(env_prefix="CONFIG_", extra="ignore")


class CORSSettings(BaseSettings):
    origins: List[str] = ["*"]
    allow_credentials: bool = True

    @classmethod
    def _parse_origins(cls, v: str) -> List[str]:
        if isinstance(v, str):
            return [o.strip() for o in v.split(",") if o.strip()]
        return v

    model_config = SettingsConfigDict(env_prefix="CORS_", extra="ignore")


class SecuritySettings(BaseSettings):
    secure_headers_enabled: bool = False
    hsts_max_age: int = 31536000
    content_security_policy: str = "default-src 'self'"

    model_config = SettingsConfigDict(env_prefix="SECURE_", extra="ignore")


class ServerSettings(BaseSettings):
    workers: int = 1
    loop: str = "auto"
    http: str = "auto"

    model_config = SettingsConfigDict(env_prefix="UVICORN_", extra="ignore")


class Settings(BaseSettings):
    app_env: Environment = Environment.DEVELOPMENT
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    app_name: str = "infra-platform"
    debug: bool = False
    log_level: str = "info"

    database: DatabaseSettings = Field(default_factory=DatabaseSettings)
    cache: CacheSettings = Field(default_factory=CacheSettings)
    redis: RedisSettings = Field(default_factory=RedisSettings)
    jwt: JWTSettings = Field(default_factory=JWTSettings)
    rate_limit: RateLimitSettings = Field(default_factory=RateLimitSettings)
    storage: StorageSettings = Field(default_factory=StorageSettings)
    monitoring: MonitoringSettings = Field(default_factory=MonitoringSettings)
    document: DocumentSettings = Field(default_factory=DocumentSettings)
    config: ConfigSettings = Field(default_factory=ConfigSettings)
    cors: CORSSettings = Field(default_factory=CORSSettings)
    security: SecuritySettings = Field(default_factory=SecuritySettings)
    server: ServerSettings = Field(default_factory=ServerSettings)

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    def is_development(self) -> bool:
        return self.app_env == Environment.DEVELOPMENT

    def is_staging(self) -> bool:
        return self.app_env == Environment.STAGING

    def is_production(self) -> bool:
        return self.app_env == Environment.PRODUCTION


def get_settings() -> Settings:
    env = os.getenv("APP_ENV", "development").lower()
    env_file = f"config/.env.{env}"
    if os.path.exists(env_file):
        Settings.model_config["env_file"] = env_file
    return Settings()


settings = get_settings()
