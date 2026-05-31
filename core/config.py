import os
from typing import Any, Dict
try:
    from pydantic_settings import BaseSettings
except ImportError:
    from pydantic import BaseSettings


class Settings(BaseSettings):
    app_name: str = "APIShield"
    version: str = "1.0.0"
    debug: bool = False

    database_url: str = "sqlite:///./apishield.db"
    redis_url: str = "redis://localhost:6379/0"

    hash_algorithm: str = "sha256"
    encryption_algorithm: str = "AES-256-GCM"

    default_page_size: int = 20
    max_page_size: int = 100

    privacy_budget_default: float = 10.0
    privacy_budget_max: float = 100.0

    shamir_default_threshold: int = 3
    shamir_default_total: int = 5

    tee_enclave_path: str = "/tmp/enclaves"
    tee_max_enclaves: int = 10

    fl_max_rounds: int = 100
    fl_learning_rate: float = 0.01

    class Config:
        env_prefix = "APISHIELD_"
        env_file = ".env"


settings = Settings()


def get_config() -> Settings:
    return settings
