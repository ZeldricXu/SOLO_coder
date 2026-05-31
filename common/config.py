from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    APP_ENV: str = "development"
    ENVIRONMENT: str = "development"
    SERVICE_NAME: str = "LLMGateway"
    APP_NAME: str = "LLMGateway"
    DEBUG: bool = False

    HOST: str = "0.0.0.0"
    PORT: int = 8080
    WORKERS: int = 1

    CORS_ORIGINS: list = ["*"]

    DATABASE_URL: str = "sqlite+aiosqlite:///./llm_gateway.db"
    REDIS_URL: str = "redis://localhost:6379/0"

    CELERY_BROKER_URL: str = "redis://localhost:6379/1"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/2"

    JWT_SECRET_KEY: str = "dev-secret-key-change-in-production"
    JWT_ALGORITHM: str = "HS256"
    JWT_ACCESS_TOKEN_EXPIRE_MINUTES: int = 30

    OPENAI_API_KEY: Optional[str] = None
    ANTHROPIC_API_KEY: Optional[str] = None
    ZHIPU_API_KEY: Optional[str] = None

    GPU_CLUSTER_CONFIG: str = "./config/gpu_cluster.yaml"
    FEATURE_STORE_REDIS_URL: str = "redis://localhost:6379/3"

    LOG_LEVEL: str = "INFO"
    METRICS_PORT: int = 9090

    MAX_BATCH_SIZE: int = 100
    DEFAULT_TIMEOUT: int = 30
    MAX_RETRIES: int = 3

    @property
    def is_development(self) -> bool:
        return self.APP_ENV == "development"

    @property
    def is_production(self) -> bool:
        return self.APP_ENV == "production"


settings = Settings()
