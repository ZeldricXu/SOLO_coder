from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    APP_ENV: str = "development"
    APP_HOST: str = "0.0.0.0"
    APP_PORT: int = 8000

    DATABASE_URL: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/profilerhub"
    DATABASE_POOL_SIZE: int = 10
    DATABASE_MAX_OVERFLOW: int = 20

    REDIS_URL: str = "redis://localhost:6379/0"
    REDIS_POOL_SIZE: int = 20

    CELERY_BROKER_URL: str = "redis://localhost:6379/1"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/2"

    TIMESERIES_ENGINE: str = "influxdb"
    INFLUXDB_URL: str = "http://localhost:8086"
    INFLUXDB_TOKEN: Optional[str] = None
    INFLUXDB_ORG: str = "profilerhub"
    INFLUXDB_BUCKET: str = "metrics"

    LOG_LEVEL: str = "INFO"
    ENABLE_METRICS: bool = True
    ENABLE_TRACING: bool = True

    WAL_ENABLED: bool = True
    WAL_PATH: str = "./data/wal"
    WAL_FLUSH_INTERVAL: float = 1.0

    ANOMALY_DETECTION_INTERVAL: int = 300
    ALERT_EVALUATION_INTERVAL: int = 60
    SLO_EVALUATION_INTERVAL: int = 300

    TAIL_SAMPLING_WINDOW: int = 30
    TAIL_SAMPLING_RATE: float = 0.1

    @property
    def is_development(self) -> bool:
        return self.APP_ENV == "development"

    @property
    def is_production(self) -> bool:
        return self.APP_ENV == "production"


settings = Settings()
