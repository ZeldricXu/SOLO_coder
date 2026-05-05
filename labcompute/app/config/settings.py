from pydantic_settings import BaseSettings
from typing import Optional

class Settings(BaseSettings):
    PROJECT_NAME: str = "LabCompute"
    VERSION: str = "2.0.0"
    
    REDIS_URL: str = "redis://localhost:6379/0"
    CELERY_BROKER_URL: str = "redis://localhost:6379/0"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/0"
    
    DATABASE_URL: str = "postgresql://postgres:postgres@localhost:5432/labcompute"
    
    DATA_DIR: str = "data"
    REPORTS_DIR: str = "reports"
    TEMP_DIR: Optional[str] = None
    
    MAX_TASK_PRIORITY: int = 10
    DEFAULT_TASK_PRIORITY: int = 5
    TASK_TIMEOUT: int = 3600
    
    CONVERGENCE_THRESHOLD: float = 1e10
    MAX_MATRIX_SIZE: int = 100000
    
    ODE_DEFAULT_RTOL: float = 1e-6
    ODE_DEFAULT_ATOL: float = 1e-9
    ODE_MIN_STEP: float = 1e-10
    ODE_MAX_STEP: float = 1e2
    ODE_SAFETY_FACTOR: float = 0.9
    ODE_MAX_INCREASE: float = 10.0
    ODE_MAX_DECREASE: float = 0.1
    ODE_MAX_ADAPTIVE_STEPS: int = 500000
    
    MATRIX_BLOCK_STRATEGY: str = "auto"
    MATRIX_BLOCK_SIZE: int = 128
    MATRIX_MAX_MEMORY_BYTES: int = 1024 * 1024 * 1024
    MATRIX_USE_MEMMAP: bool = True
    MATRIX_SMALL_THRESHOLD: int = 2000
    
    class Config:
        env_file = ".env"
        case_sensitive = True

settings = Settings()
