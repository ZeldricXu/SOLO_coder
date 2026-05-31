import yaml
from pathlib import Path
from dataclasses import dataclass, field
from typing import Any, Dict, Optional


@dataclass
class DatabaseConfig:
    driver: str = "postgresql"
    host: str = "localhost"
    port: int = 5432
    database: str = ""
    username: str = ""
    password: str = ""
    pool_size: int = 10
    max_overflow: int = 20

    @property
    def dsn(self) -> str:
        return f"{self.driver}://{self.username}:{self.password}@{self.host}:{self.port}/{self.database}"


@dataclass
class RedisConfig:
    host: str = "localhost"
    port: int = 6379
    db: int = 0
    password: str = ""
    max_connections: int = 50
    default_ttl: int = 3600


@dataclass
class KafkaConfig:
    bootstrap_servers: str = "localhost:9092"
    group_id: str = "streamsql-consumer"
    auto_offset_reset: str = "earliest"
    enable_auto_commit: bool = False
    topics: Dict[str, str] = field(default_factory=dict)


@dataclass
class StorageTierConfig:
    type: str = ""
    retention_days: int = 30
    base_path: str = ""
    compression: str = "snappy"


@dataclass
class StorageConfig:
    hot: StorageTierConfig = field(default_factory=lambda: StorageTierConfig(type="postgresql"))
    cold: StorageTierConfig = field(default_factory=lambda: StorageTierConfig(type="parquet"))
    archive: StorageTierConfig = field(default_factory=lambda: StorageTierConfig(type="parquet"))


@dataclass
class CDCMySQLConfig:
    host: str = "localhost"
    port: int = 3306
    username: str = "root"
    password: str = ""
    server_id: int = 1001
    binlog_position: int = 4


@dataclass
class CDCPostgreSQLConfig:
    host: str = "localhost"
    port: int = 5432
    database: str = ""
    username: str = ""
    password: str = ""
    replication_slot: str = "streamsql_slot"
    publication: str = "streamsql_pub"


@dataclass
class CDCConfig:
    mysql: CDCMySQLConfig = field(default_factory=CDCMySQLConfig)
    postgresql: CDCPostgreSQLConfig = field(default_factory=CDCPostgreSQLConfig)


@dataclass
class LifecycleConfig:
    hot_to_cold_days: int = 30
    cold_to_archive_days: int = 180
    check_interval_minutes: int = 60
    archive_retention_days: int = 365
    cleanup_interval_hours: int = 24


@dataclass
class VectorConfig:
    dimension: int = 768
    index_type: str = "IVF_FLAT"
    nlist: int = 100
    nprobe: int = 10
    metric: str = "L2"


@dataclass
class TimeseriesCompressionConfig:
    algorithm: str = "gorilla"
    block_size: int = 4096


@dataclass
class DownsamplingConfig:
    intervals: list = field(default_factory=lambda: ["1m", "5m", "1h", "1d"])
    default_aggregation: str = "avg"


@dataclass
class MultiresConfig:
    resolutions: list = field(default_factory=list)


@dataclass
class TimeseriesConfig:
    compression: TimeseriesCompressionConfig = field(default_factory=TimeseriesCompressionConfig)
    downsampling: DownsamplingConfig = field(default_factory=DownsamplingConfig)
    multires: MultiresConfig = field(default_factory=MultiresConfig)


@dataclass
class QualityConfig:
    default_strictness: str = "warning"
    check_interval_minutes: int = 30
    max_concurrent_checks: int = 5
    anomaly_threshold: float = 3.0


@dataclass
class MetadataConfig:
    scan_interval_hours: int = 6
    max_sample_rows: int = 1000
    sample_method: str = "random"


@dataclass
class ServerConfig:
    host: str = "0.0.0.0"
    port: int = 8080
    workers: int = 4
    log_level: str = "info"


class Settings:
    _instance: Optional["Settings"] = None
    _config_path: Optional[str] = None

    def __init__(self, config_path: Optional[str] = None):
        self._raw: Dict[str, Any] = {}
        self.server = ServerConfig()
        self.metastore = DatabaseConfig(database="streamsql_meta")
        self.timeseries_db = DatabaseConfig(driver="clickhouse", port=8123, database="streamsql_ts")
        self.redis = RedisConfig()
        self.kafka = KafkaConfig()
        self.storage = StorageConfig()
        self.cdc = CDCConfig()
        self.lifecycle = LifecycleConfig()
        self.vector = VectorConfig()
        self.timeseries = TimeseriesConfig()
        self.quality = QualityConfig()
        self.metadata = MetadataConfig()

        if config_path:
            self._load(config_path)

    def _load(self, config_path: str) -> None:
        path = Path(config_path)
        if not path.exists():
            return
        with open(path, "r", encoding="utf-8") as f:
            self._raw = yaml.safe_load(f) or {}
        self._apply(self._raw)

    def _apply(self, raw: Dict[str, Any]) -> None:
        if "server" in raw:
            srv = raw["server"]
            self.server = ServerConfig(
                host=srv.get("host", "0.0.0.0"),
                port=srv.get("port", 8080),
                workers=srv.get("workers", 4),
                log_level=srv.get("log_level", "info"),
            )
        if "database" in raw:
            db = raw["database"]
            if "metastore" in db:
                ms = db["metastore"]
                self.metastore = DatabaseConfig(
                    driver=ms.get("driver", "postgresql"),
                    host=ms.get("host", "localhost"),
                    port=ms.get("port", 5432),
                    database=ms.get("database", ""),
                    username=ms.get("username", ""),
                    password=ms.get("password", ""),
                    pool_size=ms.get("pool_size", 10),
                    max_overflow=ms.get("max_overflow", 20),
                )
            if "timeseries" in db:
                ts = db["timeseries"]
                self.timeseries_db = DatabaseConfig(
                    driver=ts.get("driver", "clickhouse"),
                    host=ts.get("host", "localhost"),
                    port=ts.get("port", 8123),
                    database=ts.get("database", ""),
                )
        if "cache" in raw and "redis" in raw["cache"]:
            r = raw["cache"]["redis"]
            self.redis = RedisConfig(
                host=r.get("host", "localhost"),
                port=r.get("port", 6379),
                db=r.get("db", 0),
                password=r.get("password", ""),
                max_connections=r.get("max_connections", 50),
                default_ttl=r.get("default_ttl", 3600),
            )
        if "messaging" in raw and "kafka" in raw["messaging"]:
            k = raw["messaging"]["kafka"]
            self.kafka = KafkaConfig(
                bootstrap_servers=k.get("bootstrap_servers", "localhost:9092"),
                group_id=k.get("group_id", "streamsql-consumer"),
                auto_offset_reset=k.get("auto_offset_reset", "earliest"),
                enable_auto_commit=k.get("enable_auto_commit", False),
                topics=k.get("topics", {}),
            )
        if "storage" in raw:
            st = raw["storage"]
            if "hot" in st:
                self.storage.hot = StorageTierConfig(**{k: v for k, v in st["hot"].items() if k in StorageTierConfig.__dataclass_fields__})
            if "cold" in st:
                self.storage.cold = StorageTierConfig(**{k: v for k, v in st["cold"].items() if k in StorageTierConfig.__dataclass_fields__})
            if "archive" in st:
                self.storage.archive = StorageTierConfig(**{k: v for k, v in st["archive"].items() if k in StorageTierConfig.__dataclass_fields__})
        if "cdc" in raw:
            c = raw["cdc"]
            if "mysql" in c:
                self.cdc.mysql = CDCMySQLConfig(**c["mysql"])
            if "postgresql" in c:
                self.cdc.postgresql = CDCPostgreSQLConfig(**c["postgresql"])
        if "lifecycle" in raw:
            lc = raw["lifecycle"]
            tiering = lc.get("tiering", {})
            cleanup = lc.get("cleanup", {})
            self.lifecycle = LifecycleConfig(
                hot_to_cold_days=tiering.get("hot_to_cold_days", 30),
                cold_to_archive_days=tiering.get("cold_to_archive_days", 180),
                check_interval_minutes=tiering.get("check_interval_minutes", 60),
                archive_retention_days=cleanup.get("archive_retention_days", 365),
                cleanup_interval_hours=cleanup.get("cleanup_interval_hours", 24),
            )
        if "vector" in raw:
            v = raw["vector"]
            self.vector = VectorConfig(**v)
        if "timeseries" in raw:
            ts = raw["timeseries"]
            if "compression" in ts:
                self.timeseries.compression = TimeseriesCompressionConfig(**ts["compression"])
            if "downsampling" in ts:
                self.timeseries.downsampling = DownsamplingConfig(**ts["downsampling"])
            if "multires" in ts:
                self.timeseries.multires = MultiresConfig(resolutions=ts["multires"].get("resolutions", []))
        if "quality" in raw:
            q = raw["quality"]
            self.quality = QualityConfig(**q)
        if "metadata" in raw:
            m = raw["metadata"]
            self.metadata = MetadataConfig(**m)

    @classmethod
    def get_instance(cls, config_path: Optional[str] = None) -> "Settings":
        if cls._instance is None or (config_path and config_path != cls._config_path):
            cls._instance = cls(config_path)
            cls._config_path = config_path
        return cls._instance


def get_settings(config_path: Optional[str] = None) -> Settings:
    return Settings.get_instance(config_path)
