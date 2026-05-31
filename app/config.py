from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    APP_NAME: str = "EdgeIoTPlatform"
    APP_VERSION: str = "1.1.0"
    DEBUG: bool = False
    
    DATABASE_URL: str = "sqlite+aiosqlite:///./edge_iot.db"
    DATABASE_SYNC_URL: str = "sqlite:///./edge_iot.db"
    
    REDIS_URL: str = "redis://localhost:6379/0"
    CELERY_BROKER_URL: str = "redis://localhost:6379/1"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/2"
    
    JWT_SECRET: str = "change-this-to-a-strong-secret-key-in-production"
    JWT_ALGORITHM: str = "HS256"
    JWT_EXPIRE_MINUTES: int = 60
    
    RATE_LIMIT_DEFAULT: str = "100/minute"
    RATE_LIMIT_STRICT: str = "10/minute"
    
    STORAGE_BASE_PATH: str = "./storage"
    BACKUP_PATH: str = "./storage/backups"
    FIRMWARE_PATH: str = "./storage/firmware"
    MODELS_PATH: str = "./storage/models"
    
    LOG_LEVEL: str = "INFO"
    
    OTA_MAX_RETRIES: int = 3
    OTA_GRAYSCALE_BATCH_SIZE: int = 10
    
    DEVICE_SHADOW_MAX_CONCURRENT: int = 100
    DEVICE_SHADOW_QUEUE_TIMEOUT: int = 30
    DEVICE_SHADOW_RETRY_ATTEMPTS: int = 3
    DEVICE_SHADOW_RETRY_DELAY: float = 1.0
    
    CACHE_L1_TTL: int = 60
    CACHE_L2_TTL: int = 300
    CACHE_L3_TTL: int = 3600
    CACHE_L1_MAX_SIZE: int = 1000
    CACHE_L2_MAX_SIZE: int = 5000
    CACHE_ENABLED: bool = True
    
    AUTOSCALE_ENABLED: bool = True
    AUTOSCALE_MIN_INSTANCES: int = 1
    AUTOSCALE_MAX_INSTANCES: int = 10
    AUTOSCALE_TARGET_CPU: float = 70.0
    AUTOSCALE_TARGET_LATENCY: int = 500
    AUTOSCALE_COOLDOWN_PERIOD: int = 60
    AUTOSCALE_CHECK_INTERVAL: int = 30
    
    CIRCUIT_BREAKER_ENABLED: bool = True
    CIRCUIT_BREAKER_FAILURE_THRESHOLD: int = 5
    CIRCUIT_BREAKER_RECOVERY_TIMEOUT: int = 30
    
    class Config:
        env_file = ".env"
        case_sensitive = True


settings = Settings()
