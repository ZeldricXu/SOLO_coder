__version__ = "1.0.0"

from app.core.config import settings
from app.core.database import get_db, init_db
from app.modules.model_manager import model_manager
from app.modules.training_service import training_service
from app.modules.result_storage import result_storage
from app.modules.exporter import exporter
from app.modules.preprocessing import text_preprocessor
from app.modules.classifier import text_classifier
from app.modules.sentiment_analyzer import sentiment_analyzer
from app.modules.keyword_extractor import keyword_extractor

__all__ = [
    "settings",
    "get_db",
    "init_db",
    "model_manager",
    "training_service",
    "result_storage",
    "exporter",
    "text_preprocessor",
    "text_classifier",
    "sentiment_analyzer",
    "keyword_extractor"
]
