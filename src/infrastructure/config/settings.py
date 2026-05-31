"""Application configuration settings."""
from __future__ import annotations

import os
from functools import lru_cache
from typing import Any, Dict, Optional

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict
import yaml


class AppConfig(BaseSettings):
    name: str = "file-storage-lifecycle"
    version: str = "1.0.0"
    environment: str = "development"


class ServerConfig(BaseSettings):
    host: str = "0.0.0.0"
    port: int = 8000


class StorageTierConfig(BaseSettings):
    path: str
    max_size_gb: int = 100


class StorageConfig(BaseSettings):
    hot: StorageTierConfig
    cold: StorageTierConfig
    archive: StorageTierConfig


class LifecycleConfig(BaseSettings):
    hot_to_cold_days: int = 30
    cold_to_archive_days: int = 90
    archive_retention_days: int = 365
    cleanup_interval_hours: int = 24


class DatabaseConfig(BaseSettings):
    url: str = "sqlite:///./data/metastore.db"
    pool_size: int = 10
    max_overflow: int = 20


class CacheConfig(BaseSettings):
    host: str = "localhost"
    port: int = 6379
    db: int = 0
    ttl_seconds: int = 3600


class KafkaTopicsConfig(BaseSettings):
    storage_events: str = "storage.events"
    lifecycle_events: str = "lifecycle.events"


class KafkaConfig(BaseSettings):
    bootstrap_servers: str = "localhost:9092"
    topics: KafkaTopicsConfig


class MessagingConfig(BaseSettings):
    kafka: KafkaConfig


class LoggingConfig(BaseSettings):
    level: str = "INFO"
    format: str = "json"
    file_path: str = "./logs/app.log"
    max_file_size_mb: int = 100
    backup_count: int = 5


class TracingConfig(BaseSettings):
    enabled: bool = True
    service_name: str = "file-storage-service"
    endpoint: str = "http://localhost:4317"


class SchedulerConfig(BaseSettings):
    enabled: bool = True
    timezone: str = "Asia/Shanghai"


class QualityConfig(BaseSettings):
    check_interval_minutes: int = 60
    alert_enabled: bool = True


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_nested_delimiter="__",
        case_sensitive=False,
        extra="ignore",
    )

    app: AppConfig = Field(default_factory=AppConfig)
    server: ServerConfig = Field(default_factory=ServerConfig)
    storage: StorageConfig
    lifecycle: LifecycleConfig = Field(default_factory=LifecycleConfig)
    database: DatabaseConfig = Field(default_factory=DatabaseConfig)
    cache: CacheConfig = Field(default_factory=CacheConfig)
    messaging: MessagingConfig
    logging: LoggingConfig = Field(default_factory=LoggingConfig)
    tracing: TracingConfig = Field(default_factory=TracingConfig)
    scheduler: SchedulerConfig = Field(default_factory=SchedulerConfig)
    quality: QualityConfig = Field(default_factory=QualityConfig)

    @classmethod
    def from_yaml(cls, config_path: str) -> "Settings":
        if not os.path.exists(config_path):
            raise FileNotFoundError(f"Configuration file not found: {config_path}")

        with open(config_path, "r", encoding="utf-8") as f:
            config_data = yaml.safe_load(f)

        return cls.model_validate(config_data)


@lru_cache()
def get_settings(config_path: Optional[str] = None) -> Settings:
    if config_path is None:
        config_path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(__file__)))),
            "config",
            "settings.yaml",
        )

    try:
        return Settings.from_yaml(config_path)
    except Exception:
        default_config = {
            "storage": {
                "hot": {"path": "./data/hot", "max_size_gb": 100},
                "cold": {"path": "./data/cold", "max_size_gb": 500},
                "archive": {"path": "./data/archive", "max_size_gb": 2000},
            },
            "messaging": {
                "kafka": {
                    "bootstrap_servers": "localhost:9092",
                    "topics": {
                        "storage_events": "storage.events",
                        "lifecycle_events": "lifecycle.events",
                    },
                }
            },
        }
        return Settings.model_validate(default_config)
