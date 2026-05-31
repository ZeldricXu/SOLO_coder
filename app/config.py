from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    APP_NAME: str = "Notification Priority Platform"
    DEBUG: bool = True
    
    DATABASE_URL: str = "sqlite+aiosqlite:///./app.db"
    
    REDIS_URL: str = "redis://localhost:6379/0"
    CELERY_BROKER_URL: str = "redis://localhost:6379/0"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/0"
    
    LOG_LEVEL: str = "INFO"
    LOG_FORMAT: str = "json"
    
    PROMETHEUS_PORT: int = 8001
    
    MAX_NOTIFICATION_PRIORITY: int = 10
    NOTIFICATION_SUPPRESSION_WINDOW: int = 60
    NOTIFICATION_RATE_LIMIT: int = 100
    
    GPU_COUNT: int = 8
    GPU_MEMORY_PER_DEVICE: int = 24576
    
    API_GATEWAY_PORT: int = 8000
    
    class Config:
        env_file = ".env"


settings = Settings()
