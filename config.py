"""
全局配置模块
"""
from dataclasses import dataclass, field
from typing import Dict, Any, Optional
import os
from dotenv import load_dotenv

load_dotenv()


@dataclass
class StorageConfig:
    hot_storage_path: str = "data/hot"
    cold_storage_path: str = "data/cold"
    archive_storage_path: str = "data/archive"
    hot_data_retention_days: int = 7
    cold_data_retention_days: int = 30
    auto_cleanup: bool = True


@dataclass
class StreamingConfig:
    window_size_ms: int = 60000
    slide_interval_ms: int = 10000
    late_data_tolerance_ms: int = 300000
    checkpoint_interval_ms: int = 5000
    state_backend: str = "memory"


@dataclass
class IndexConfig:
    default_index_type: str = "hnsw"
    dimension: int = 1536
    nlist: int = 4096
    m: int = 16
    ef_search: int = 50
    ef_construction: int = 200


@dataclass
class QualityConfig:
    check_interval_minutes: int = 60
    alert_threshold: float = 0.95
    auto_fix: bool = False
    anomaly_data_dir: str = "data/anomaly"


@dataclass
class CDCConfig:
    source_type: str = "mysql"
    hostname: str = "localhost"
    port: int = 3306
    username: str = "root"
    password: str = ""
    server_id: int = 1
    include_tables: list = field(default_factory=list)
    exclude_tables: list = field(default_factory=list)
    output_format: str = "json"
    output_dir: str = "cdc_output"


@dataclass
class GlobalConfig:
    storage: StorageConfig = field(default_factory=StorageConfig)
    streaming: StreamingConfig = field(default_factory=StreamingConfig)
    index: IndexConfig = field(default_factory=IndexConfig)
    quality: QualityConfig = field(default_factory=QualityConfig)
    cdc: CDCConfig = field(default_factory=CDCConfig)
    log_level: str = "INFO"
    data_dir: str = "data"

    @classmethod
    def from_env(cls) -> "GlobalConfig":
        config = cls()
        config.storage.hot_storage_path = os.getenv("HOT_STORAGE_PATH", config.storage.hot_storage_path)
        config.storage.cold_storage_path = os.getenv("COLD_STORAGE_PATH", config.storage.cold_storage_path)
        config.streaming.window_size_ms = int(os.getenv("WINDOW_SIZE_MS", config.streaming.window_size_ms))
        config.index.dimension = int(os.getenv("EMBEDDING_DIMENSION", config.index.dimension))
        config.log_level = os.getenv("LOG_LEVEL", config.log_level)
        return config
