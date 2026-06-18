import os
from enum import Enum
from typing import Optional

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Environment(str, Enum):
    DEV = "dev"
    STAGING = "staging"
    PROD = "prod"


class Settings(BaseSettings):
    APP_NAME: str = "城市交通流量三维可视化平台"
    APP_VERSION: str = "1.0.0"
    ENVIRONMENT: Environment = Environment.DEV
    DEBUG: bool = True

    POSTGRES_HOST: str = "localhost"
    POSTGRES_PORT: int = 5432
    POSTGRES_USER: str = "postgres"
    POSTGRES_PASSWORD: str = "postgres"
    POSTGRES_DB: str = "traffic_viz"
    POSTGRES_POOL_SIZE: int = Field(default=10, ge=1, le=100)
    POSTGRES_MAX_OVERFLOW: int = Field(default=20, ge=0, le=100)
    POSTGRES_POOL_RECYCLE: int = 3600
    POSTGRES_SSL_MODE: str = "prefer"

    REDIS_HOST: str = "localhost"
    REDIS_PORT: int = 6379
    REDIS_DB: int = 0
    REDIS_PASSWORD: Optional[str] = None
    REDIS_SSL: bool = False

    INFLUXDB_URL: str = "http://localhost:8086"
    INFLUXDB_TOKEN: str = "my-token"
    INFLUXDB_ORG: str = "traffic-org"
    INFLUXDB_BUCKET: str = "traffic-data"
    INFLUXDB_TIMEOUT: int = 30000

    KAFKA_BOOTSTRAP_SERVERS: str = "localhost:9092"
    KAFKA_TRAFFIC_TOPIC: str = "traffic-stream"
    KAFKA_GROUP_ID: str = "traffic-viz-group"
    KAFKA_SECURITY_PROTOCOL: str = "PLAINTEXT"
    KAFKA_SASL_MECHANISM: Optional[str] = None
    KAFKA_SASL_USERNAME: Optional[str] = None
    KAFKA_SASL_PASSWORD: Optional[str] = None
    KAFKA_AUTO_OFFSET_RESET: str = "earliest"
    KAFKA_ENABLE_AUTO_COMMIT: bool = False
    KAFKA_SESSION_TIMEOUT_MS: int = 30000

    HDFS_HOST: str = "localhost"
    HDFS_PORT: int = 9870
    HDFS_USER: str = "hdfs"
    HDFS_BASE_PATH: str = "/traffic/data"

    CELERY_BROKER_URL: str = "redis://localhost:6379/1"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/2"
    CELERY_TASK_TIME_LIMIT: int = 3600
    CELERY_TASK_SOFT_TIME_LIMIT: int = 3300
    CELERY_WORKER_CONCURRENCY: int = 4
    CELERY_WORKER_MAX_TASKS_PER_CHILD: int = 1000
    CELERY_RESULT_EXPIRES: int = 86400

    SECRET_KEY: str = "your-secret-key-change-in-production"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 1440
    CORS_ORIGINS: str = "*"

    TILE_CACHE_DIR: str = "./data/tiles"
    HEATMAP_CACHE_DIR: str = "./data/cache"
    SAMPLE_DATA_DIR: str = "./data/sample"
    PREDICTION_MODEL_DIR: str = "./models"
    DEFAULT_PREDICTION_HORIZONS: list = [15, 30, 60]

    MAP_CENTER_LAT: float = Field(default=39.9042, description="地图中心纬度，部署差异化参数")
    MAP_CENTER_LON: float = Field(default=116.4074, description="地图中心经度，部署差异化参数")
    MAP_DEFAULT_ZOOM: int = Field(default=14, ge=1, le=20)
    TILE_ORIGIN_LON: float = Field(default=116.0, description="瓦片原点经度，部署差异化参数")
    TILE_ORIGIN_LAT: float = Field(default=39.5, description="瓦片原点纬度，部署差异化参数")
    MAP_MAX_ZOOM: int = 18
    MAP_MIN_ZOOM: int = 8
    CESIUM_TOKEN: str = ""

    TILES_S3_ENDPOINT: Optional[str] = None
    TILES_S3_BUCKET: str = "traffic-tiles"
    TILES_S3_ACCESS_KEY: Optional[str] = None
    TILES_S3_SECRET_KEY: Optional[str] = None
    TILES_S3_REGION: str = "cn-north-1"
    TILES_S3_USE_PATH_STYLE: bool = True

    VAULT_ADDR: Optional[str] = None
    VAULT_TOKEN: Optional[str] = None
    VAULT_SECRET_PATH: str = "secret/data/traffic-viz"
    VAULT_ENABLED: bool = False

    LOG_LEVEL: str = "INFO"
    SENTRY_DSN: Optional[str] = None
    JAEGER_AGENT_HOST: Optional[str] = None
    JAEGER_AGENT_PORT: int = 6831
    METRICS_ENABLED: bool = True
    METRICS_PORT: int = 9090

    PREDICTION_BATCH_SIZE: int = 32
    PREDICTION_DEVICE: str = "cpu"

    @field_validator("ENVIRONMENT", mode="before")
    @classmethod
    def validate_environment(cls, v):
        if isinstance(v, str):
            return Environment(v.lower())
        return v

    @field_validator("DEBUG", mode="before")
    @classmethod
    def sync_debug_with_env(cls, v, info):
        if info.data.get("ENVIRONMENT") == Environment.PROD:
            return False
        return v

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore",
    )

    @property
    def is_production(self) -> bool:
        return self.ENVIRONMENT == Environment.PROD

    @property
    def is_staging(self) -> bool:
        return self.ENVIRONMENT == Environment.STAGING

    @property
    def is_development(self) -> bool:
        return self.ENVIRONMENT == Environment.DEV

    @property
    def database_url(self) -> str:
        return (
            f"postgresql+psycopg2://{self.POSTGRES_USER}:{self.POSTGRES_PASSWORD}"
            f"@{self.POSTGRES_HOST}:{self.POSTGRES_PORT}/{self.POSTGRES_DB}"
            f"?sslmode={self.POSTGRES_SSL_MODE}"
        )

    @property
    def redis_url(self) -> str:
        scheme = "rediss" if self.REDIS_SSL else "redis"
        auth = f":{self.REDIS_PASSWORD}@" if self.REDIS_PASSWORD else ""
        return f"{scheme}://{auth}{self.REDIS_HOST}:{self.REDIS_PORT}/{self.REDIS_DB}"

    @property
    def kafka_config(self) -> dict:
        config = {
            "bootstrap.servers": self.KAFKA_BOOTSTRAP_SERVERS,
            "group.id": self.KAFKA_GROUP_ID,
            "auto.offset.reset": self.KAFKA_AUTO_OFFSET_RESET,
            "enable.auto.commit": self.KAFKA_ENABLE_AUTO_COMMIT,
            "session.timeout.ms": self.KAFKA_SESSION_TIMEOUT_MS,
        }
        if self.KAFKA_SECURITY_PROTOCOL != "PLAINTEXT":
            config["security.protocol"] = self.KAFKA_SECURITY_PROTOCOL
        if self.KAFKA_SASL_MECHANISM:
            config["sasl.mechanism"] = self.KAFKA_SASL_MECHANISM
        if self.KAFKA_SASL_USERNAME:
            config["sasl.username"] = self.KAFKA_SASL_USERNAME
        if self.KAFKA_SASL_PASSWORD:
            config["sasl.password"] = self.KAFKA_SASL_PASSWORD
        return config


def load_vault_secrets(addr: str, token: str, path: str) -> dict:
    try:
        import hvac

        client = hvac.Client(url=addr, token=token)
        if not client.is_authenticated():
            raise RuntimeError("Vault authentication failed")

        response = client.secrets.kv.v2.read_secret_version(path=path)
        return response["data"]["data"]
    except ImportError:
        return {}
    except Exception as e:
        if "localhost" not in addr:
            raise RuntimeError(f"Failed to load Vault secrets: {e}")
        return {}


def get_settings() -> Settings:
    settings = Settings()

    if settings.VAULT_ENABLED and settings.VAULT_ADDR:
        vault_secrets = load_vault_secrets(
            settings.VAULT_ADDR, settings.VAULT_TOKEN, settings.VAULT_SECRET_PATH
        )
        if vault_secrets:
            os.environ.update(vault_secrets)
            settings = Settings()

    return settings


settings = get_settings()
