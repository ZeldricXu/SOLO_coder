from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    APP_NAME: str = "Lumina Mind API"
    APP_VERSION: str = "0.1.0"
    DEBUG: bool = True
    
    PORT: int = 8001
    
    OPENAI_API_KEY: Optional[str] = None
    OPENAI_MODEL: str = "gpt-3.5-turbo"
    EMBEDDING_MODEL: str = "text-embedding-ada-002"
    
    class Config:
        env_file = ".env"
        case_sensitive = True


settings = Settings()
