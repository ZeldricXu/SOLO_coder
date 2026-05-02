from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional


class Settings(BaseSettings):
    OPENAI_API_KEY: str
    OPENAI_API_BASE: str = "https://api.openai.com/v1"
    CHROMA_PERSIST_DIR: str = "./chroma_db"
    DATABASE_URL: str = "sqlite+aiosqlite:///./app.db"

    EMBEDDING_MODEL: str = "text-embedding-3-small"
    LLM_MODEL: str = "gpt-3.5-turbo"

    CHUNK_SIZE: int = 1000
    CHUNK_OVERLAP: int = 200
    TOP_K_RESULTS: int = 5

    model_config = SettingsConfigDict(env_file=".env", case_sensitive=True)


settings = Settings()
