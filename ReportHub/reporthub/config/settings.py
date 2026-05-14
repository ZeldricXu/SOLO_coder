import os
import json
from typing import Optional, Dict, Any, List
from dataclasses import dataclass, field


@dataclass
class RedisConfig:
    host: str = "localhost"
    port: int = 6379
    db: int = 0
    password: Optional[str] = None
    max_connections: int = 10
    socket_timeout: float = 5.0
    socket_connect_timeout: float = 5.0
    retry_on_timeout: bool = True

    @property
    def url(self) -> str:
        if self.password:
            return f"redis://:{self.password}@{self.host}:{self.port}/{self.db}"
        return f"redis://{self.host}:{self.port}/{self.db}"


@dataclass
class RetryComplexityConfig:
    level: int
    base_delay: float
    max_retries: int
    backoff_multiplier: float
    description: str

    def to_dict(self) -> Dict[str, Any]:
        return {
            "level": self.level,
            "base_delay": self.base_delay,
            "max_retries": self.max_retries,
            "backoff_multiplier": self.backoff_multiplier,
            "description": self.description
        }


@dataclass
class ExportFormatConfig:
    format_name: str
    enabled: bool = True
    options: Dict[str, Any] = field(default_factory=dict)
    content_type: str = "application/octet-stream"
    file_extension: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "format_name": self.format_name,
            "enabled": self.enabled,
            "options": self.options,
            "content_type": self.content_type,
            "file_extension": self.file_extension
        }


class Settings:
    DATABASE_URL: str = os.getenv(
        "DATABASE_URL",
        "sqlite:///./reporthub_data.db"
    )
    STORAGE_PATH: str = os.getenv(
        "STORAGE_PATH",
        "./storage"
    )
    API_HOST: str = os.getenv("API_HOST", "0.0.0.0")
    API_PORT: int = int(os.getenv("API_PORT", "8000"))
    MAX_RETRY_COUNT: int = int(os.getenv("MAX_RETRY_COUNT", "3"))
    RETRY_DELAY: int = int(os.getenv("RETRY_DELAY", "5"))
    REPORT_EXPIRE_DAYS: int = int(os.getenv("REPORT_EXPIRE_DAYS", "30"))

    REDIS_CONFIG = RedisConfig(
        host=os.getenv("REDIS_HOST", "localhost"),
        port=int(os.getenv("REDIS_PORT", "6379")),
        db=int(os.getenv("REDIS_DB", "0")),
        password=os.getenv("REDIS_PASSWORD"),
        max_connections=int(os.getenv("REDIS_MAX_CONNECTIONS", "10")),
        socket_timeout=float(os.getenv("REDIS_SOCKET_TIMEOUT", "5.0")),
        socket_connect_timeout=float(os.getenv("REDIS_SOCKET_CONNECT_TIMEOUT", "5.0"))
    )

    RETRY_COMPLEXITY_CONFIGS: Dict[int, RetryComplexityConfig] = {
        1: RetryComplexityConfig(
            level=1,
            base_delay=1.0,
            max_retries=2,
            backoff_multiplier=1.5,
            description="简单报表 (0-100行)"
        ),
        2: RetryComplexityConfig(
            level=2,
            base_delay=2.0,
            max_retries=3,
            backoff_multiplier=2.0,
            description="中等报表 (100-1000行)"
        ),
        3: RetryComplexityConfig(
            level=3,
            base_delay=5.0,
            max_retries=4,
            backoff_multiplier=2.5,
            description="复杂报表 (1000-10000行)"
        ),
        4: RetryComplexityConfig(
            level=4,
            base_delay=10.0,
            max_retries=5,
            backoff_multiplier=3.0,
            description="大量数据报表 (10000-100000行)"
        ),
        5: RetryComplexityConfig(
            level=5,
            base_delay=30.0,
            max_retries=5,
            backoff_multiplier=3.0,
            description="超大型报表 (100000+行)"
        )
    }

    EXPORT_FORMAT_CONFIGS: Dict[str, ExportFormatConfig] = {
        "xlsx": ExportFormatConfig(
            format_name="xlsx",
            enabled=True,
            options={
                "sheet_name": "报表数据",
                "header_style": {
                    "bold": True,
                    "size": 12,
                    "color": "FFFFFF",
                    "bg_color": "4472C4"
                },
                "column_width": 18,
                "freeze_panes": "A2"
            },
            content_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            file_extension="xlsx"
        ),
        "csv": ExportFormatConfig(
            format_name="csv",
            enabled=True,
            options={
                "delimiter": ",",
                "encoding": "utf-8",
                "include_header": True,
                "quote_char": '"'
            },
            content_type="text/csv",
            file_extension="csv"
        ),
        "pdf": ExportFormatConfig(
            format_name="pdf",
            enabled=True,
            options={
                "page_size": "A4",
                "orientation": "portrait",
                "title_style": {
                    "font": "Helvetica-Bold",
                    "size": 18
                },
                "header_color": "#4472C4",
                "alternate_row_color": "#F2F2F2"
            },
            content_type="application/pdf",
            file_extension="pdf"
        )
    }

    TASK_QUEUE_KEYS = {
        "GENERATION_QUEUE": "reporthub:generation:queue",
        "EXPORT_QUEUE": "reporthub:export:queue",
        "SCHEDULE_QUEUE": "reporthub:schedule:queue",
        "TASK_STATUS_PREFIX": "reporthub:task:status:",
        "TASK_LOCK_PREFIX": "reporthub:task:lock:",
        "DEAD_LETTER_QUEUE": "reporthub:dlq:queue"
    }

    @property
    def reports_storage_path(self) -> str:
        return os.path.join(self.STORAGE_PATH, "reports")

    @property
    def exports_storage_path(self) -> str:
        return os.path.join(self.STORAGE_PATH, "exports")

    @property
    def config_storage_path(self) -> str:
        return os.path.join(self.STORAGE_PATH, "configs")

    def get_retry_config(self, complexity_level: int) -> RetryComplexityConfig:
        normalized_level = max(1, min(complexity_level, 5))
        return self.RETRY_COMPLEXITY_CONFIGS[normalized_level]

    def get_export_format_config(self, format_name: str) -> Optional[ExportFormatConfig]:
        config = self.EXPORT_FORMAT_CONFIGS.get(format_name.lower())
        if config and config.enabled:
            return config
        return None

    def get_supported_export_formats(self) -> List[str]:
        return [fmt for fmt, config in self.EXPORT_FORMAT_CONFIGS.items() if config.enabled]

    def update_retry_config(self, level: int, config: Dict[str, Any]) -> RetryComplexityConfig:
        if level not in self.RETRY_COMPLEXITY_CONFIGS:
            raise ValueError(f"Invalid complexity level: {level}")
        existing = self.RETRY_COMPLEXITY_CONFIGS[level]
        if "base_delay" in config:
            existing.base_delay = config["base_delay"]
        if "max_retries" in config:
            existing.max_retries = config["max_retries"]
        if "backoff_multiplier" in config:
            existing.backoff_multiplier = config["backoff_multiplier"]
        if "description" in config:
            existing.description = config["description"]
        return existing

    def update_export_format_config(self, format_name: str, config: Dict[str, Any]) -> ExportFormatConfig:
        if format_name not in self.EXPORT_FORMAT_CONFIGS:
            self.EXPORT_FORMAT_CONFIGS[format_name] = ExportFormatConfig(
                format_name=format_name,
                enabled=True,
                options={},
                content_type=config.get("content_type", "application/octet-stream"),
                file_extension=config.get("file_extension", format_name)
            )
        existing = self.EXPORT_FORMAT_CONFIGS[format_name]
        if "enabled" in config:
            existing.enabled = config["enabled"]
        if "options" in config:
            existing.options = config["options"]
        if "content_type" in config:
            existing.content_type = config["content_type"]
        if "file_extension" in config:
            existing.file_extension = config["file_extension"]
        return existing

    def add_export_format(self, format_name: str, config: Dict[str, Any]) -> ExportFormatConfig:
        if format_name in self.EXPORT_FORMAT_CONFIGS:
            raise ValueError(f"Export format already exists: {format_name}")
        new_config = ExportFormatConfig(
            format_name=format_name,
            enabled=config.get("enabled", True),
            options=config.get("options", {}),
            content_type=config.get("content_type", "application/octet-stream"),
            file_extension=config.get("file_extension", format_name)
        )
        self.EXPORT_FORMAT_CONFIGS[format_name] = new_config
        return new_config

    def remove_export_format(self, format_name: str) -> bool:
        if format_name in self.EXPORT_FORMAT_CONFIGS:
            del self.EXPORT_FORMAT_CONFIGS[format_name]
            return True
        return False

    def load_export_formats_from_file(self, config_file: str) -> int:
        if not os.path.exists(config_file):
            return 0
        with open(config_file, 'r', encoding='utf-8') as f:
            configs = json.load(f)
        loaded_count = 0
        for format_name, config in configs.items():
            self.update_export_format_config(format_name, config)
            loaded_count += 1
        return loaded_count

    def load_retry_configs_from_file(self, config_file: str) -> int:
        if not os.path.exists(config_file):
            return 0
        with open(config_file, 'r', encoding='utf-8') as f:
            configs = json.load(f)
        loaded_count = 0
        for level_str, config in configs.items():
            level = int(level_str)
            self.update_retry_config(level, config)
            loaded_count += 1
        return loaded_count


settings = Settings()
