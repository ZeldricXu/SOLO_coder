from functools import lru_cache
from typing import Dict, List, Optional
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=True,
    )

    APP_NAME: str = "DocumentUnderstandingPipeline"
    APP_ENV: str = "development"
    APP_DEBUG: bool = True
    APP_HOST: str = "0.0.0.0"
    APP_PORT: int = 8000
    API_PREFIX: str = "/api/v1"
    SECRET_KEY: str = "change-me-in-production"
    WORKERS: int = 1
    CORS_ORIGINS: List[str] = ["*"]

    @property
    def DEBUG(self) -> bool:
        return self.APP_DEBUG

    @property
    def HOST(self) -> str:
        return self.APP_HOST

    @property
    def PORT(self) -> int:
        return self.APP_PORT

    DATABASE_URL: str = "postgresql+asyncpg://postgres:password@localhost:5432/doc_understanding"
    DATABASE_SYNC_URL: str = "postgresql+psycopg2://postgres:password@localhost:5432/doc_understanding"

    REDIS_URL: str = "redis://localhost:6379/0"
    REDIS_CACHE_DB: int = 1
    REDIS_QUEUE_DB: int = 2

    CELERY_BROKER_URL: str = "redis://localhost:6379/2"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/3"
    CELERY_TASK_TIME_LIMIT: int = 3600
    CELERY_TASK_SOFT_TIME_LIMIT: int = 3000
    CELERY_WORKER_PREFETCH_MULTIPLIER: int = 1
    CELERY_MAX_RETRIES: int = 3
    CELERY_WORKER_CONCURRENCY: int = 2
    CELERY_WORKER_MAX_TASKS_PER_CHILD: int = 1000

    MINIO_ENDPOINT: str = "localhost:9000"
    MINIO_ACCESS_KEY: str = "minioadmin"
    MINIO_SECRET_KEY: str = "minioadmin"
    MINIO_SECURE: bool = False
    MINIO_RAW_BUCKET: str = "raw-documents"
    MINIO_PROCESSED_BUCKET: str = "processed-documents"
    MINIO_MODEL_BUCKET: str = "model-artifacts"
    MINIO_BUCKET_BATCHES: str = "batches"

    OCR_LANGS: str = "ch,en"
    OCR_USE_GPU: bool = False
    OCR_DET_MODEL_DIR: Optional[str] = None
    OCR_REC_MODEL_DIR: Optional[str] = None
    OCR_CLS_MODEL_DIR: Optional[str] = None

    ML_MODEL_CACHE_DIR: str = "./.model_cache"
    ML_DEVICE: str = "cpu"
    ML_BATCH_SIZE: int = 4
    ML_MAX_SEQ_LENGTH: int = 512
    GPU_MEMORY_FRACTION: float = 0.8
    GPU_MEMORY_LIMIT_MB: Optional[int] = None

    EXTRACTION_MODEL_NAME: str = "qwen-vl-chat"
    EXTRACTION_MODEL_VERSION: str = "1.0.0"
    EXTRACTION_MODEL_PATH: Optional[str] = None
    EXTRACTION_CONFIDENCE_THRESHOLD: float = 0.7

    LAYOUT_MODEL_NAME: str = "layoutlmv3-base"
    LAYOUT_MODEL_VERSION: str = "1.0.0"
    LAYOUT_MODEL_PATH: Optional[str] = None

    TABLE_MODEL_NAME: str = "table-transformer-detection"
    TABLE_MODEL_VERSION: str = "1.0.0"
    TABLE_MODEL_PATH: Optional[str] = None

    API_KEYS: str = ""
    REQUIRE_API_KEY: bool = False

    RATE_LIMIT_PER_MINUTE: int = 100
    MAX_CONCURRENT_TASKS: int = 4
    TASK_TIMEOUT: int = 3600
    TASK_PRIORITY_HIGH: int = 0
    TASK_PRIORITY_MEDIUM: int = 5
    TASK_PRIORITY_LOW: int = 10

    LOG_LEVEL: str = "INFO"
    LOG_DIR: str = "./logs"

    AB_TEST_ENABLED: bool = True
    AB_TEST_TRAFFIC_SPLIT: str = '{"model_v1": 0.7, "model_v2": 0.3}'
    AB_TEST_METRICS: str = "extraction_accuracy,review_rate,processing_time"

    ICD10_CODES_FILE: str = "./data/icd10_codes.json"

    @property
    def ocr_lang_list(self) -> List[str]:
        return [lang.strip() for lang in self.OCR_LANGS.split(",")]

    @property
    def ab_test_traffic_split_dict(self) -> Dict[str, float]:
        import json
        try:
            return json.loads(self.AB_TEST_TRAFFIC_SPLIT)
        except json.JSONDecodeError:
            return {"model_v1": 1.0}

    @property
    def ab_test_metrics_list(self) -> List[str]:
        return [m.strip() for m in self.AB_TEST_METRICS.split(",")]

    @property
    def api_keys_list(self) -> List[str]:
        if not self.API_KEYS:
            return []
        return [k.strip() for k in self.API_KEYS.split(",") if k.strip()]


@lru_cache()
def get_settings() -> Settings:
    import os
    env = os.getenv("APP_ENV", "development")
    env_file = f".env.{env}"
    if os.path.exists(env_file):
        return Settings(_env_file=env_file)
    return Settings()
