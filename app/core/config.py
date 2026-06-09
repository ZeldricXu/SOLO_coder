from typing import List
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    APP_ENV: str = "development"
    APP_NAME: str = "Inventory Management Platform"
    APP_VERSION: str = "1.0.0"
    API_PREFIX: str = "/api/v1"
    DEBUG: bool = False

    DATABASE_URL: str = "postgresql+psycopg2://localhost:5432/inventory"
    DATABASE_POOL_SIZE: int = 20
    DATABASE_MAX_OVERFLOW: int = 10
    DATABASE_ECHO: bool = False

    REDIS_BROKER_URL: str = "redis://localhost:6379/0"
    REDIS_BACKEND_URL: str = "redis://localhost:6379/1"
    REDIS_CLUSTER_NODES: str = "localhost:7000,localhost:7001,localhost:7002"

    CELERY_BROKER_URL: str = "redis://localhost:6379/0"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/1"
    CELERY_TASK_ALWAYS_EAGER: bool = False

    SECRET_KEY: str = "change-me-in-production"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    REFRESH_TOKEN_EXPIRE_DAYS: int = 7

    GRPC_SERVER_HOST: str = "0.0.0.0"
    GRPC_SERVER_PORT: int = 50051

    CDC_ENABLED: bool = True
    CDC_POLL_INTERVAL: int = 1000

    LOG_LEVEL: str = "INFO"
    LOG_FORMAT: str = "json"

    CACHE_TTL_DEFAULT: int = 300
    CACHE_TTL_SHORT: int = 60
    CACHE_TTL_LONG: int = 3600

    RATE_LIMIT_PER_MINUTE: int = 100
    RATE_LIMIT_PER_HOUR: int = 1000

    SMTP_HOST: str = "smtp.gmail.com"
    SMTP_PORT: int = 587
    SMTP_USER: str = ""
    SMTP_PASSWORD: str = ""
    SMTP_FROM_EMAIL: str = "noreply@inventory.com"

    TELEGRAM_BOT_TOKEN: str = ""
    TELEGRAM_CHAT_ID: str = ""

    WEBHOOK_URL: str = ""

    @property
    def redis_cluster_node_list(self) -> List[dict]:
        nodes = []
        for node in self.REDIS_CLUSTER_NODES.split(","):
            host, port = node.strip().split(":")
            nodes.append({"host": host, "port": int(port)})
        return nodes


settings = Settings()
