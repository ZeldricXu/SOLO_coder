from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    DATABASE_URL: str = "postgresql+asyncpg://etl:etl@localhost:5432/etl_engine"
    REDIS_URL: str = "redis://localhost:6379/0"
    CELERY_BROKER_URL: str = "redis://localhost:6379/1"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/2"
    DEBUG: bool = False
    LOG_LEVEL: str = "INFO"
    SMTP_HOST: str = ""
    SMTP_PORT: int = 587
    SMTP_USER: str = ""
    SMTP_PASSWORD: str = ""
    SMTP_FROM: str = ""
    PAGERDUTY_ROUTING_KEY: str = ""
    QUALITY_DEFAULT_STRATEGY: str = "alert"
    MAX_RETRY_ATTEMPTS: int = 3
    RETRY_DELAY_SECONDS: int = 60
    PROMETHEUS_PORT: int = 9091
    API_HOST: str = "0.0.0.0"
    API_PORT: int = 8000

    model_config = {"env_prefix": "ETL_", "env_file": ".env"}


settings = Settings()
