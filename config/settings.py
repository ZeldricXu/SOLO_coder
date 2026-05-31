from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    app_name: str = "Edge IoT Platform"
    app_version: str = "1.0.0"
    environment: str = "development"

    database_url: str = "sqlite:///./edge_iot.db"
    redis_url: str = "redis://localhost:6379/0"

    mqtt_broker: str = "localhost"
    mqtt_port: int = 1883
    mqtt_username: Optional[str] = None
    mqtt_password: Optional[str] = None

    api_host: str = "0.0.0.0"
    api_port: int = 8000

    edge_node_id: str = "edge-node-001"
    cloud_endpoint: str = "http://localhost:9000"

    offline_cache_path: str = "./offline_cache"
    offline_cache_max_size_mb: int = 1024
    offline_sync_batch_size: int = 100

    inference_model_path: str = "./models"
    inference_gpu_enabled: bool = False

    log_level: str = "INFO"
    log_file: str = "./logs/edge_iot.log"

    class Config:
        env_file = ".env"


settings = Settings()
