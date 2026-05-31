from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, Field

from streamsql.core.exceptions import ConfigurationError


class ServerConfig(BaseModel):
    host: str = "0.0.0.0"
    port: int = 8000


class DatabaseConfig(BaseModel):
    url: str = "sqlite:///./streamsql.db"
    echo: bool = False


class RedisConfig(BaseModel):
    url: str = "redis://localhost:6379/0"


class CeleryConfig(BaseModel):
    broker_url: str = "redis://localhost:6379/1"
    result_backend: str = "redis://localhost:6379/2"


class MetadataCrawlerConfig(BaseModel):
    scan_interval: int = 3600
    sample_size: int = 100
    timeout: int = 30


class CDCCaptureConfig(BaseModel):
    batch_size: int = 1000
    retry_attempts: int = 3


class StreamingQueryConfig(BaseModel):
    optimize_enabled: bool = True


class VectorIndexConfig(BaseModel):
    default_dimension: int = 1536
    index_type: str = "hnsw"


class LifecycleManagerConfig(BaseModel):
    hot_threshold_days: int = 7
    cold_threshold_days: int = 30
    archive_threshold_days: int = 90


class DataQualityConfig(BaseModel):
    check_interval: int = 300
    alert_threshold: float = 0.05


class ModulesConfig(BaseModel):
    metadata_crawler: MetadataCrawlerConfig = Field(default_factory=MetadataCrawlerConfig)
    cdc_capture: CDCCaptureConfig = Field(default_factory=CDCCaptureConfig)
    streaming_query: StreamingQueryConfig = Field(default_factory=StreamingQueryConfig)
    vector_index: VectorIndexConfig = Field(default_factory=VectorIndexConfig)
    lifecycle_manager: LifecycleManagerConfig = Field(default_factory=LifecycleManagerConfig)
    data_quality: DataQualityConfig = Field(default_factory=DataQualityConfig)


class AppConfig(BaseModel):
    server: ServerConfig = Field(default_factory=ServerConfig)
    database: DatabaseConfig = Field(default_factory=DatabaseConfig)
    redis: RedisConfig = Field(default_factory=RedisConfig)
    celery: CeleryConfig = Field(default_factory=CeleryConfig)
    modules: ModulesConfig = Field(default_factory=ModulesConfig)


class ConfigManager:
    _instance: "ConfigManager | None" = None
    _config: AppConfig | None = None

    def __new__(cls) -> "ConfigManager":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    @classmethod
    def load(cls, config_path: str | None = None) -> AppConfig:
        if cls._config is not None:
            return cls._config

        config_path = config_path or os.environ.get(
            "STREAMSQL_CONFIG",
            str(Path(__file__).resolve().parents[2] / "config" / "default.yml"),
        )

        try:
            with open(config_path, "r") as f:
                config_data = yaml.safe_load(f) or {}
            cls._config = AppConfig(**config_data)
            return cls._config
        except FileNotFoundError:
            cls._config = AppConfig()
            return cls._config
        except Exception as e:
            raise ConfigurationError("config_file", f"Failed to load config: {e}") from e

    @classmethod
    def get(cls) -> AppConfig:
        if cls._config is None:
            return cls.load()
        return cls._config

    @classmethod
    def override(cls, key_path: str, value: Any) -> None:
        if cls._config is None:
            cls.load()
        parts = key_path.split(".")
        obj: Any = cls._config
        for part in parts[:-1]:
            obj = getattr(obj, part)
        setattr(obj, parts[-1], value)
