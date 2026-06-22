from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "SnippetHub"
    secret_key: str = "your-secret-key-change-in-production-please"
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 60 * 24 * 7
    database_url: str = "sqlite:///./snippets.db"
    frontend_dir: str = "../frontend"

    class Config:
        env_file = ".env"


settings = Settings()
