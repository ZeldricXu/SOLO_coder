from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import List, Optional, Literal
from functools import lru_cache


class KafkaSASLConfig(BaseSettings):
    mechanism: str = "PLAIN"
    username: Optional[str] = None
    password: Optional[str] = None
    ssl_cafile: Optional[str] = None


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        env_nested_delimiter="__",
    )

    service_name: str = "recommendation-engine"
    service_host: str = "0.0.0.0"
    service_port: int = 8000
    log_level: str = "info"
    environment: Literal["development", "staging", "production"] = "development"

    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_db: int = 0
    redis_password: Optional[str] = None
    redis_max_connections: int = 100
    redis_connection_string: Optional[str] = None

    pg_host: str = "localhost"
    pg_port: int = 5432
    pg_user: str = "postgres"
    pg_password: str = "postgres"
    pg_database: str = "recommendation"
    pg_pool_min_size: int = 5
    pg_pool_max_size: int = 20
    pg_connection_string: Optional[str] = None

    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_consumer_group_id: str = "recommendation-engine-group"
    kafka_feedback_topic: str = "user-feedback-events"
    kafka_feedback_partitions: int = 6
    kafka_producer_acks: str = "1"
    kafka_batch_size: int = 16384
    kafka_linger_ms: int = 5
    kafka_sasl: Optional[KafkaSASLConfig] = None

    faiss_index_path: str = "./data/faiss_index"
    faiss_index_type: str = "IVF1024,Flat"
    faiss_embedding_dim: int = 768
    faiss_nprobe: int = 64
    faiss_rebuild_batch_size: int = 10000

    als_model_path: str = "./data/als_model.npz"
    als_factors: int = 64
    als_regularization: float = 0.01
    als_iterations: int = 20
    als_top_k: int = 100

    lgbm_model_path: str = "./data/lgbm_model.txt"
    lgbm_feature_names: List[str] = [
        "ctr_score",
        "stay_time_score",
        "purchase_score",
        "share_score",
        "tag_match_score",
        "vector_cosine_score",
        "als_score",
        "content_popularity",
        "user_interest_diversity",
        "content_freshness",
    ]

    user_profile_ttl_seconds: int = 86400
    user_profile_version_key_prefix: str = "user:profile:version"
    user_profile_version_ttl_seconds: int = 604800

    abtest_hash_bucket: int = 1000
    abtest_config_ttl_seconds: int = 60
    abtest_layers: List[str] = ["recall_layer", "rank_layer", "rerank_layer"]

    triton_server_url: str = "localhost:8000"
    triton_model_timeout: int = 10
    onnx_providers: List[str] = ["CPUExecutionProvider"]

    iceberg_catalog_name: str = "default"
    iceberg_database: str = "recommendation"
    iceberg_table: str = "user_feedback_events"
    iceberg_warehouse: str = "./data/iceberg_warehouse"

    feature_cache_ttl_seconds: int = 300
    realtime_counter_window_seconds: int = 86400

    pipeline_recall_top_k: int = 200
    pipeline_rank_top_k: int = 50
    pipeline_rerank_top_k: int = 20
    pipeline_mmr_lambda: float = 0.7

    embedding_service_url: str = "http://localhost:8080/embeddings"
    embedding_service_timeout: int = 5
    embedding_service_batch_size: int = 32

    feedback_collector_max_queue_size: int = 10000
    feedback_collector_worker_count: int = 4
    feedback_collector_batch_size: int = 500

    hot_reload_enabled: bool = True
    hot_reload_interval_seconds: int = 30

    business_rules_redis_key: str = "business_rules:{scene}"
    business_rules_hot_reload_seconds: int = 15
    business_rules_max_pin_positions: int = 5

    cf_online_update_enabled: bool = True
    cf_online_update_kafka_topic: str = "cf-online-updates"
    cf_cold_start_max_age_hours: int = 24
    cf_cold_start_min_interactions: int = 5
    cf_online_learning_rate: float = 0.01
    cf_online_regularization: float = 0.01
    cf_pca_components: int = 64


class DevelopmentSettings(Settings):
    environment: Literal["development"] = "development"
    log_level: str = "debug"
    pg_database: str = "recommendation_dev"
    hot_reload_interval_seconds: int = 5
    business_rules_hot_reload_seconds: int = 5
    faiss_index_path: str = "./data/faiss_index_dev"
    als_model_path: str = "./data/als_model_dev.npz"
    iceberg_warehouse: str = "./data/iceberg_warehouse_dev"


class StagingSettings(Settings):
    environment: Literal["staging"] = "staging"
    log_level: str = "info"
    hot_reload_interval_seconds: int = 15
    faiss_index_path: str = "/data/faiss_index_staging"
    als_model_path: str = "/data/als_model_staging.npz"
    iceberg_warehouse: str = "/data/iceberg_warehouse_staging"


class ProductionSettings(Settings):
    environment: Literal["production"] = "production"
    log_level: str = "warning"
    faiss_index_path: str = "/data/faiss_index"
    als_model_path: str = "/data/als_model.npz"
    iceberg_warehouse: str = "/data/iceberg_warehouse"


_settings_classes = {
    "development": DevelopmentSettings,
    "staging": StagingSettings,
    "production": ProductionSettings,
}


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    import os
    env = os.getenv("ENVIRONMENT", "development").lower()
    settings_class = _settings_classes.get(env, DevelopmentSettings)
    return settings_class()


settings = get_settings()
