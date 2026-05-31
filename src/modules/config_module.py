import os
import json
import hashlib
from pathlib import Path
from datetime import datetime
from typing import Any, Dict, List, Optional, TypeVar, Generic
from enum import Enum
from pydantic import BaseModel, Field, field_validator, ConfigDict
from pydantic_settings import BaseSettings
from functools import lru_cache

T = TypeVar('T')


class ConfigSource(str, Enum):
    ENV = "env"
    FILE = "file"
    DEFAULT = "default"
    DATABASE = "database"


class ConfigEntry(BaseModel, Generic[T]):
    key: str
    value: T
    source: ConfigSource
    description: Optional[str] = None
    updated_at: datetime = Field(default_factory=datetime.utcnow)
    version: int = 1


class DatabaseConfig(BaseModel):
    url: str = "sqlite+aiosqlite:///./app.db"
    sync_url: str = "sqlite:///./app.db"
    pool_size: int = 10
    max_overflow: int = 20
    pool_recycle: int = 3600
    echo: bool = False

    @field_validator('url')
    def validate_url(cls, v):
        if not v:
            raise ValueError("Database URL cannot be empty")
        return v


class RedisConfig(BaseModel):
    url: str = "redis://localhost:6379/0"
    max_connections: int = 50
    socket_timeout: int = 5
    socket_connect_timeout: int = 5


class CacheConfig(BaseModel):
    enabled: bool = True
    l1_max_size: int = 10000
    l1_ttl: int = 60
    l2_ttl: int = 300
    redis_url: Optional[str] = None
    warmup_on_start: bool = False


class LoggingConfig(BaseModel):
    level: str = "INFO"
    dir: str = "./logs"
    max_bytes: int = 100 * 1024 * 1024
    backup_count: int = 30
    enable_json: bool = True
    enable_console: bool = True


class StorageConfig(BaseModel):
    backend: str = "local"
    local_path: str = "./data"
    s3_bucket: Optional[str] = None
    s3_region: Optional[str] = None
    s3_access_key: Optional[str] = None
    s3_secret_key: Optional[str] = None


class NotificationConfig(BaseModel):
    email_host: Optional[str] = None
    email_port: int = 587
    email_user: Optional[str] = None
    email_password: Optional[str] = None
    slack_webhook: Optional[str] = None
    webhook_url: Optional[str] = None
    default_channels: List[str] = ["webhook"]


class EventStoreConfig(BaseModel):
    backend: str = "sqlalchemy"
    snapshot_interval: int = 100
    snapshot_retention: int = 7


class TaskConfig(BaseModel):
    broker_url: str = "redis://localhost:6379/1"
    result_backend: str = "redis://localhost:6379/2"
    task_serializer: str = "json"
    result_serializer: str = "json"
    accept_content: List[str] = ["json"]
    timezone: str = "UTC"
    enable_utc: bool = True


class AppConfig(BaseSettings):
    model_config = ConfigDict(env_nested_delimiter='__', extra='ignore')

    app_name: str = "cloud-native-engine"
    app_env: str = "development"
    app_debug: bool = False
    app_port: int = 8000
    app_host: str = "0.0.0.0"
    api_prefix: str = "/api/v1"

    database: DatabaseConfig = Field(default_factory=DatabaseConfig)
    redis: RedisConfig = Field(default_factory=RedisConfig)
    cache: CacheConfig = Field(default_factory=CacheConfig)
    logging: LoggingConfig = Field(default_factory=LoggingConfig)
    storage: StorageConfig = Field(default_factory=StorageConfig)
    notification: NotificationConfig = Field(default_factory=NotificationConfig)
    event_store: EventStoreConfig = Field(default_factory=EventStoreConfig)
    task: TaskConfig = Field(default_factory=TaskConfig)

    feature_flags: Dict[str, bool] = Field(default_factory=lambda: {
        "fault_injection": False,
        "event_sourcing": True,
        "audit_logging": True,
    })

    rate_limits: Dict[str, int] = Field(default_factory=lambda: {
        "requests_per_minute": 1000,
        "concurrent_tasks": 100,
    })


class ConfigManager:
    _instance: Optional['ConfigManager'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, config_file: Optional[str] = None, env_file: Optional[str] = None):
        if self._initialized:
            return

        self.config_file = Path(config_file) if config_file else None
        self.env_file = Path(env_file) if env_file else Path(".env")
        self._config_store: Dict[str, ConfigEntry] = {}
        self._config_hash: Optional[str] = None
        self._load_config()
        self._initialized = True

    def _load_config(self):
        self._load_env_file()
        self._load_config_file()
        self.app_config = AppConfig()
        self._update_config_hash()

    def _load_env_file(self):
        if self.env_file and self.env_file.exists():
            from dotenv import load_dotenv
            load_dotenv(self.env_file)

    def _load_config_file(self):
        if self.config_file and self.config_file.exists():
            with open(self.config_file, 'r') as f:
                file_config = json.load(f)
            for key, value in file_config.items():
                os.environ[key.upper()] = str(value)

    def _update_config_hash(self):
        config_data = self.app_config.model_dump_json()
        self._config_hash = hashlib.sha256(config_data.encode()).hexdigest()

    @property
    def config_hash(self) -> str:
        return self._config_hash or ""

    def get(self, key: str, default: Any = None) -> Any:
        keys = key.split('.')
        value = self.app_config
        try:
            for k in keys:
                value = getattr(value, k)
            return value
        except AttributeError:
            return default

    def set(self, key: str, value: Any, source: ConfigSource = ConfigSource.DEFAULT,
            description: Optional[str] = None) -> ConfigEntry:
        entry = ConfigEntry(
            key=key,
            value=value,
            source=source,
            description=description,
        )
        self._config_store[key] = entry
        return entry

    def validate(self) -> List[str]:
        errors: List[str] = []

        if not self.app_config.database.url:
            errors.append("Database URL is required")

        if self.app_config.storage.backend == "s3":
            if not self.app_config.storage.s3_bucket:
                errors.append("S3 bucket is required for S3 storage backend")

        for channel in self.app_config.notification.default_channels:
            if channel == "email" and not self.app_config.notification.email_host:
                errors.append("Email host is required for email notifications")
            if channel == "slack" and not self.app_config.notification.slack_webhook:
                errors.append("Slack webhook is required for Slack notifications")

        return errors

    def get_all_configs(self) -> Dict[str, Any]:
        return self.app_config.model_dump()

    def diff(self, other_config: 'ConfigManager') -> Dict[str, Dict[str, Any]]:
        current = self.get_all_configs()
        other = other_config.get_all_configs()
        differences = {}

        def compare_dicts(d1: Dict, d2: Dict, prefix: str = ""):
            all_keys = set(d1.keys()) | set(d2.keys())
            for key in all_keys:
                full_key = f"{prefix}.{key}" if prefix else key
                v1 = d1.get(key)
                v2 = d2.get(key)
                if isinstance(v1, dict) and isinstance(v2, dict):
                    compare_dicts(v1, v2, full_key)
                elif v1 != v2:
                    differences[full_key] = {"current": v1, "other": v2}

        compare_dicts(current, other)
        return differences

    def export(self, filepath: str, include_sensitive: bool = False) -> None:
        config_data = self.get_all_configs()
        if not include_sensitive:
            config_data = self._mask_sensitive_data(config_data)

        with open(filepath, 'w') as f:
            json.dump(config_data, f, indent=2, default=str)

    def _mask_sensitive_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        sensitive_keys = {'password', 'secret', 'token', 'key'}
        result = {}
        for key, value in data.items():
            if isinstance(value, dict):
                result[key] = self._mask_sensitive_data(value)
            elif any(s in key.lower() for s in sensitive_keys):
                result[key] = "***MASKED***"
            else:
                result[key] = value
        return result

    def reload(self) -> None:
        self._config_store.clear()
        self._load_config()

    def is_feature_enabled(self, feature_name: str) -> bool:
        return self.app_config.feature_flags.get(feature_name, False)


@lru_cache()
def get_config_manager() -> ConfigManager:
    return ConfigManager()


def get_app_config() -> AppConfig:
    return get_config_manager().app_config
