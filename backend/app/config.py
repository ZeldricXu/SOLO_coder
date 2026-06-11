from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    APP_NAME: str = "城市交通流量三维可视化平台"
    APP_VERSION: str = "1.0.0"
    DEBUG: bool = True

    POSTGRES_HOST: str = "localhost"
    POSTGRES_PORT: int = 5432
    POSTGRES_USER: str = "postgres"
    POSTGRES_PASSWORD: str = "postgres"
    POSTGRES_DB: str = "traffic_viz"

    REDIS_HOST: str = "localhost"
    REDIS_PORT: int = 6379
    REDIS_DB: int = 0
    REDIS_PASSWORD: Optional[str] = None

    INFLUXDB_URL: str = "http://localhost:8086"
    INFLUXDB_TOKEN: str = "my-token"
    INFLUXDB_ORG: str = "traffic-org"
    INFLUXDB_BUCKET: str = "traffic-data"

    KAFKA_BOOTSTRAP_SERVERS: str = "localhost:9092"
    KAFKA_TRAFFIC_TOPIC: str = "traffic-stream"
    KAFKA_GROUP_ID: str = "traffic-viz-group"

    HDFS_HOST: str = "localhost"
    HDFS_PORT: int = 9870
    HDFS_USER: str = "hdfs"
    HDFS_BASE_PATH: str = "/traffic/data"

    CELERY_BROKER_URL: str = "redis://localhost:6379/1"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/2"

    SECRET_KEY: str = "your-secret-key-change-in-production"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 1440

    TILE_CACHE_DIR: str = "./data/tiles"
    HEATMAP_CACHE_DIR: str = "./data/cache"
    SAMPLE_DATA_DIR: str = "./data/sample"

    PREDICTION_MODEL_DIR: str = "./models"
    DEFAULT_PREDICTION_HORIZONS: list = [15, 30, 60]

    CESIUM_TOKEN: str = ""
    MAP_CENTER_LAT: float = 39.9042
    MAP_CENTER_LON: float = 116.4074
    MAP_DEFAULT_ZOOM: int = 14

    class Config:
        env_file = ".env"
        case_sensitive = True


settings = Settings()
