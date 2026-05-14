import os
from dataclasses import dataclass
from typing import Optional


@dataclass
class Settings:
    APP_NAME: str = "BehaviorTrack"
    APP_VERSION: str = "1.0.0"
    
    DEBUG: bool = os.getenv("DEBUG", "false").lower() == "true"
    HOST: str = os.getenv("HOST", "0.0.0.0")
    PORT: int = int(os.getenv("PORT", "5000"))
    
    MONGODB_URI: str = os.getenv("MONGODB_URI", "mongodb://localhost:27017")
    MONGODB_DB_NAME: str = os.getenv("MONGODB_DB_NAME", "behaviortrack")
    
    EVENTS_COLLECTION: str = "events"
    STATS_COLLECTION: str = "statistics"
    TRAJECTORIES_COLLECTION: str = "trajectories"
    PROFILES_COLLECTION: str = "profiles"
    RELATIONS_COLLECTION: str = "event_relations"
    ABNORMAL_COLLECTION: str = "abnormal_behaviors"
    
    MAX_BATCH_SIZE: int = 100
    SESSION_TIMEOUT_SECONDS: int = 1800
    
    ACTIVE_DAYS_THRESHOLD: int = 7
    MOBILE_RATIO_THRESHOLD: float = 0.7
    EVENT_FREQUENCY_THRESHOLD: int = 100
    
    VISUALIZATION_OUTPUT_DIR: str = os.getenv(
        "VISUALIZATION_OUTPUT_DIR", 
        "/tmp/behaviortrack/visualizations"
    )
    EXPORT_OUTPUT_DIR: str = os.getenv(
        "EXPORT_OUTPUT_DIR", 
        "/tmp/behaviortrack/exports"
    )
    
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")
    
    REDIS_HOST: str = os.getenv("REDIS_HOST", "localhost")
    REDIS_PORT: int = int(os.getenv("REDIS_PORT", "6379"))
    REDIS_PASSWORD: Optional[str] = os.getenv("REDIS_PASSWORD")
    REDIS_DB: int = int(os.getenv("REDIS_DB", "0"))
    REDIS_USE_SSL: bool = os.getenv("REDIS_USE_SSL", "false").lower() == "true"
    
    USE_REDIS_QUEUE: bool = os.getenv("USE_REDIS_QUEUE", "true").lower() == "true"
    BEHAVIOR_QUEUE_KEY: str = os.getenv("BEHAVIOR_QUEUE_KEY", "behaviortrack:behavior_queue")
    ANALYSIS_QUEUE_KEY: str = os.getenv("ANALYSIS_QUEUE_KEY", "behaviortrack:analysis_queue")
    QUEUE_RESULT_PREFIX: str = os.getenv("QUEUE_RESULT_PREFIX", "behaviortrack:queue_result:")
    QUEUE_RESULT_TTL_SECONDS: int = int(os.getenv("QUEUE_RESULT_TTL_SECONDS", "3600"))
    
    TIME_WINDOW_SECONDS: int = int(os.getenv("TIME_WINDOW_SECONDS", "300"))
    STATS_CACHE_TTL_SECONDS: int = int(os.getenv("STATS_CACHE_TTL_SECONDS", "300"))
    STATISTICS_CACHE_PREFIX: str = os.getenv("STATISTICS_CACHE_PREFIX", "behaviortrack:stats:")
    
    DETECTION_RULES_CONFIG_PATH: str = os.getenv(
        "DETECTION_RULES_CONFIG_PATH", 
        "/etc/behaviortrack/detection_rules.json"
    )
    
    def __post_init__(self):
        os.makedirs(self.VISUALIZATION_OUTPUT_DIR, exist_ok=True)
        os.makedirs(self.EXPORT_OUTPUT_DIR, exist_ok=True)


settings = Settings()
