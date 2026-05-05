import os
from pathlib import Path
from pydantic_settings import BaseSettings
from typing import List


class Settings(BaseSettings):
    PROJECT_NAME: str = "TextClassifier 文本分类与情感分析服务"
    VERSION: str = "1.0.0"
    API_V1_STR: str = "/api/v1"

    BASE_DIR: Path = Path(__file__).resolve().parent.parent.parent
    DATA_DIR: Path = BASE_DIR / "data"
    MODELS_DIR: Path = DATA_DIR / "models"
    EXPORTS_DIR: Path = DATA_DIR / "exports"
    STOPWORDS_DIR: Path = DATA_DIR / "stopwords"

    DATABASE_URL: str = f"sqlite:///{DATA_DIR}/text_classifier.db"

    DEFAULT_CONFIDENCE_THRESHOLD: float = 0.6
    DEFAULT_MODEL_VERSION: str = "v1.0.0"

    DEFAULT_LABELS: List[str] = ["产品质量", "价格", "客服服务", "物流配送", "售后"]

    SENTIMENT_MODEL_NAME: str = "distilbert-base-uncased-finetuned-sst-2-english"

    MAX_KEYWORDS_COUNT: int = 10
    MIN_KEYWORD_LENGTH: int = 2

    TRAINING_TEST_SIZE: float = 0.2
    TRAINING_RANDOM_STATE: int = 42

    class Config:
        case_sensitive = True
        env_file = ".env"


settings = Settings()

if not settings.MODELS_DIR.exists():
    settings.MODELS_DIR.mkdir(parents=True, exist_ok=True)
if not settings.EXPORTS_DIR.exists():
    settings.EXPORTS_DIR.mkdir(parents=True, exist_ok=True)
if not settings.STOPWORDS_DIR.exists():
    settings.STOPWORDS_DIR.mkdir(parents=True, exist_ok=True)
