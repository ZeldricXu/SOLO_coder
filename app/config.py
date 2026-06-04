from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional
from functools import lru_cache


class BaseConfig(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore"
    )

    app_name: str = "运维监控大盘"
    app_version: str = "1.0.0"

    database_url: str = "sqlite:///./monitor.db"
    echo_sql: bool = False

    prometheus_url: str = "http://localhost:9090"
    prometheus_timeout: int = 10
    prometheus_username: Optional[str] = None
    prometheus_password: Optional[str] = None

    elasticsearch_url: str = "http://localhost:9200"
    elasticsearch_timeout: int = 10
    elasticsearch_username: Optional[str] = None
    elasticsearch_password: Optional[str] = None

    dingtalk_webhook: Optional[str] = None
    wechat_webhook: Optional[str] = None
    phone_notify_url: Optional[str] = None

    smtp_host: Optional[str] = None
    smtp_port: int = 587
    smtp_username: Optional[str] = None
    smtp_password: Optional[str] = None
    smtp_from_email: Optional[str] = None
    smtp_to_emails: Optional[str] = None
    smtp_cc_emails: Optional[str] = None
    smtp_use_tls: bool = True

    health_check_interval: int = 30
    alert_evaluate_interval: int = 60

    default_user_id: int = 1

    enable_metrics: bool = True


class DevelopmentConfig(BaseConfig):
    model_config = SettingsConfigDict(env_prefix="")

    debug: bool = True
    log_level: str = "DEBUG"
    hot_reload: bool = True


class StagingConfig(BaseConfig):
    model_config = SettingsConfigDict(env_prefix="")

    debug: bool = False
    log_level: str = "INFO"
    hot_reload: bool = False


class ProductionConfig(BaseConfig):
    model_config = SettingsConfigDict(env_prefix="")

    debug: bool = False
    log_level: str = "WARNING"
    hot_reload: bool = False
    echo_sql: bool = False


@lru_cache
def get_settings() -> BaseConfig:
    import os
    env = os.getenv("ENVIRONMENT", "development").lower()

    if env == "production":
        return ProductionConfig()
    elif env == "staging":
        return StagingConfig()
    else:
        return DevelopmentConfig()


settings = get_settings()
