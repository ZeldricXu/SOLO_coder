import os
from typing import Optional
from pydantic import Field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    APP_NAME: str = "SearchEngine"
    APP_VERSION: str = "1.0.0"
    
    ELASTICSEARCH_HOST: str = Field(default="localhost")
    ELASTICSEARCH_PORT: int = Field(default=9200)
    ELASTICSEARCH_INDEX: str = Field(default="search_content")
    
    REDIS_HOST: str = Field(default="localhost")
    REDIS_PORT: int = Field(default=6379)
    REDIS_DB: int = Field(default=0)
    REDIS_PASSWORD: Optional[str] = Field(default=None)
    
    CACHE_TTL: int = Field(default=300)
    CACHE_ENABLED: bool = Field(default=True)
    
    SORT_STRATEGY_FILE: str = Field(default="sort_strategies.json")
    
    LOG_FILE: str = Field(default="search_logs.json")
    STAT_FILE: str = Field(default="search_stats.json")
    
    PAGE_SIZE_DEFAULT: int = Field(default=10)
    PAGE_SIZE_MAX: int = Field(default=100)
    
    SORT_TIMEOUT: float = Field(default=5.0)
    
    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


settings = Settings()
