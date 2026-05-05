from pydantic_settings import BaseSettings
from typing import Optional, List
from functools import lru_cache


class Settings(BaseSettings):
    APP_NAME: str = "DataFlow 实时数据流分析平台"
    APP_VERSION: str = "1.1.0"
    DEBUG: bool = True

    MYSQL_HOST: str = "localhost"
    MYSQL_PORT: int = 3306
    MYSQL_USER: str = "root"
    MYSQL_PASSWORD: str = ""
    MYSQL_DATABASE: str = "dataflow"

    KAFKA_BOOTSTRAP_SERVERS: str = "localhost:9092"
    KAFKA_GROUP_ID: str = "dataflow-consumer"
    KAFKA_AUTO_OFFSET_RESET: str = "latest"
    KAFKA_ENABLE_AUTO_COMMIT: bool = False
    KAFKA_MANUAL_COMMIT_INTERVAL: int = 5000
    KAFKA_ENABLE_OFFSET_PERSISTENCE: bool = True

    INFLUXDB_URL: str = "http://localhost:8086"
    INFLUXDB_TOKEN: str = "root"
    INFLUXDB_ORG: str = "dataflow"
    INFLUXDB_BUCKET: str = "metrics"

    SLACK_WEBHOOK_URL: Optional[str] = None
    SLACK_CHANNEL: str = "#alerts"
    SLACK_ENABLED: bool = True

    SMTP_HOST: str = "smtp.example.com"
    SMTP_PORT: int = 587
    SMTP_USER: str = ""
    SMTP_PASSWORD: str = ""
    SMTP_FROM: str = "alerts@example.com"
    SMTP_TO: List[str] = ["admin@example.com"]
    SMTP_USE_TLS: bool = True
    SMTP_ENABLED: bool = False

    PIPELINE_CONFIG_PATH: str = "config/pipelines.yaml"
    PIPELINE_AUTO_RELOAD: bool = False
    PIPELINE_RELOAD_INTERVAL: int = 60

    WEBSOCKET_HOST: str = "0.0.0.0"
    WEBSOCKET_PORT: int = 8000

    BATCH_WRITE_INTERVAL: int = 5
    MAX_BATCH_SIZE: int = 1000

    class Config:
        env_file = ".env"
        case_sensitive = True


@lru_cache()
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
