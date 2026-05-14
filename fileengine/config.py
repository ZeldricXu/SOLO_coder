import os
import json
from pathlib import Path
from typing import Optional, Dict, Any, List
from datetime import timedelta
from pydantic_settings import BaseSettings
from pydantic import Field


BASE_DIR = Path(__file__).resolve().parent.parent


DEFAULT_CONVERSION_PROFILES = {
    "default": {
        "description": "默认转换配置，平衡质量和速度",
        "image": {
            "quality": 85,
            "dpi": 300,
            "max_width": None,
            "max_height": None,
            "resize": None,
            "format": "jpg",
        },
        "pdf": {
            "quality": 85,
            "dpi": 300,
            "pages": None,
            "format": "jpg",
        },
        "video": {
            "fps": 30,
            "crf": 23,
            "preset": "medium",
            "bitrate": "1000k",
            "format": "mp4",
        },
    },
    "high_quality": {
        "description": "高质量转换，文件较大",
        "image": {
            "quality": 95,
            "dpi": 600,
            "max_width": None,
            "max_height": None,
            "resize": None,
            "format": "png",
        },
        "pdf": {
            "quality": 95,
            "dpi": 600,
            "pages": None,
            "format": "png",
        },
        "video": {
            "fps": 60,
            "crf": 18,
            "preset": "veryslow",
            "bitrate": "5000k",
            "format": "mp4",
        },
    },
    "low_quality": {
        "description": "低质量转换，适合预览",
        "image": {
            "quality": 50,
            "dpi": 72,
            "max_width": 800,
            "max_height": 600,
            "resize": None,
            "format": "jpg",
        },
        "pdf": {
            "quality": 50,
            "dpi": 72,
            "pages": [0],
            "format": "jpg",
        },
        "video": {
            "fps": 15,
            "crf": 28,
            "preset": "ultrafast",
            "bitrate": "500k",
            "format": "mp4",
        },
    },
    "thumbnail": {
        "description": "缩略图生成配置",
        "image": {
            "quality": 70,
            "dpi": 72,
            "max_width": 200,
            "max_height": 200,
            "resize": None,
            "format": "jpg",
        },
        "pdf": {
            "quality": 70,
            "dpi": 72,
            "pages": [0],
            "format": "jpg",
        },
        "video": {
            "fps": 10,
            "crf": 30,
            "preset": "ultrafast",
            "bitrate": "200k",
            "format": "mp4",
        },
    },
    "social_media": {
        "description": "社交媒体优化配置",
        "image": {
            "quality": 85,
            "dpi": 72,
            "max_width": 1200,
            "max_height": 630,
            "resize": None,
            "format": "jpg",
        },
        "pdf": {
            "quality": 85,
            "dpi": 150,
            "pages": [0],
            "format": "jpg",
        },
        "video": {
            "fps": 30,
            "crf": 23,
            "preset": "medium",
            "bitrate": "2000k",
            "format": "mp4",
        },
    },
}


DEFAULT_CLEANUP_STRATEGY = {
    "default": {
        "expire_days": 30,
        "trigger_condition": "scheduled",
        "schedule_interval_hours": 24,
        "max_storage_gb": 100,
        "cleanup_percentage": 80,
    },
    "image": {
        "expire_days": 15,
        "trigger_condition": "scheduled",
        "schedule_interval_hours": 24,
        "max_storage_gb": 50,
        "cleanup_percentage": 70,
    },
    "video": {
        "expire_days": 7,
        "trigger_condition": "scheduled",
        "schedule_interval_hours": 6,
        "max_storage_gb": 200,
        "cleanup_percentage": 90,
    },
    "pdf": {
        "expire_days": 60,
        "trigger_condition": "scheduled",
        "schedule_interval_hours": 24,
        "max_storage_gb": 100,
        "cleanup_percentage": 80,
    },
    "archive": {
        "expire_days": 3,
        "trigger_condition": "capacity",
        "schedule_interval_hours": 1,
        "max_storage_gb": 50,
        "cleanup_percentage": 75,
    },
    "result": {
        "expire_days": 7,
        "trigger_condition": "scheduled",
        "schedule_interval_hours": 12,
        "max_storage_gb": 100,
        "cleanup_percentage": 80,
    },
}


class ConversionProfileConfig:
    def __init__(self, profiles: Dict[str, Any]):
        self.profiles = profiles

    def get_profile(self, profile_name: str = "default") -> Dict[str, Any]:
        return self.profiles.get(profile_name, self.profiles.get("default", {}))

    def get_profile_params(
        self,
        profile_name: str,
        source_format: str,
    ) -> Dict[str, Any]:
        profile = self.get_profile(profile_name)
        format_key = self._normalize_format(source_format)
        return profile.get(format_key, {})

    def _normalize_format(self, source_format: str) -> str:
        fmt = source_format.lower()
        if fmt == "pdf":
            return "pdf"
        elif fmt in ["jpg", "jpeg", "png", "webp", "gif", "tiff", "bmp", "image"]:
            return "image"
        elif fmt in ["mp4", "webm", "avi", "mkv", "mov", "video"]:
            return "video"
        return "image"

    def list_profiles(self) -> List[str]:
        return list(self.profiles.keys())

    def add_profile(self, name: str, config: Dict[str, Any]):
        self.profiles[name] = config

    def update_profile(self, name: str, config: Dict[str, Any]):
        if name in self.profiles:
            self.profiles[name].update(config)

    def delete_profile(self, name: str) -> bool:
        if name in self.profiles and name != "default":
            del self.profiles[name]
            return True
        return False

    def merge_with_user_params(
        self,
        profile_name: str,
        source_format: str,
        user_params: Dict[str, Any] = None,
    ) -> Dict[str, Any]:
        profile_params = self.get_profile_params(profile_name, source_format)
        result = dict(profile_params)
        if user_params:
            result.update(user_params)
        return result


class CleanupStrategyConfig:
    def __init__(self, strategies: Dict[str, Any]):
        self.strategies = strategies

    def get_strategy(self, file_type: str = "default") -> Dict[str, Any]:
        return self.strategies.get(file_type, self.strategies.get("default", {}))

    def get_expire_days(self, file_type: str) -> int:
        strategy = self.get_strategy(file_type)
        return strategy.get("expire_days", 30)

    def get_trigger_condition(self, file_type: str) -> str:
        strategy = self.get_strategy(file_type)
        return strategy.get("trigger_condition", "scheduled")

    def get_schedule_interval(self, file_type: str) -> int:
        strategy = self.get_strategy(file_type)
        return strategy.get("schedule_interval_hours", 24)

    def get_max_storage_gb(self, file_type: str) -> int:
        strategy = self.get_strategy(file_type)
        return strategy.get("max_storage_gb", 100)

    def get_cleanup_percentage(self, file_type: str) -> int:
        strategy = self.get_strategy(file_type)
        return strategy.get("cleanup_percentage", 80)

    def list_strategies(self) -> List[str]:
        return list(self.strategies.keys())

    def add_strategy(self, file_type: str, config: Dict[str, Any]):
        self.strategies[file_type] = config

    def update_strategy(self, file_type: str, config: Dict[str, Any]):
        if file_type in self.strategies:
            self.strategies[file_type].update(config)

    def delete_strategy(self, file_type: str) -> bool:
        if file_type in self.strategies and file_type != "default":
            del self.strategies[file_type]
            return True
        return False


class Settings(BaseSettings):
    app_name: str = "FileEngine 文件处理服务"
    app_version: str = "2.0.0"
    debug: bool = True

    base_dir: Path = BASE_DIR
    storage_dir: Path = BASE_DIR / "storage"
    chunks_dir: Path = BASE_DIR / "storage" / "chunks"
    upload_dir: Path = BASE_DIR / "storage" / "uploads"
    result_dir: Path = BASE_DIR / "storage" / "results"
    temp_dir: Path = BASE_DIR / "storage" / "temp"
    logs_dir: Path = BASE_DIR / "storage" / "logs"

    max_file_size: int = 1024 * 1024 * 1024
    chunk_size: int = 1024 * 1024 * 5
    allowed_upload_types: list = ["*/*"]

    celery_broker_url: str = "redis://localhost:6379/0"
    celery_result_backend: str = "redis://localhost:6379/0"

    database_url: str = "sqlite:///./fileengine.db"

    file_expire_days: int = 30
    task_max_retry: int = 3
    task_timeout: int = 3600

    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_db: int = 0
    redis_password: Optional[str] = None
    redis_use_ssl: bool = False

    ffmpeg_path: Optional[str] = None
    libreoffice_path: Optional[str] = None

    api_host: str = "0.0.0.0"
    api_port: int = 8000

    enable_redis_queue: bool = True
    redis_convert_queue_key: str = "fileengine:convert:queue"
    redis_upload_queue_key: str = "fileengine:upload:queue"
    redis_task_prefix: str = "fileengine:task:"

    enable_async_upload: bool = True
    async_upload_worker_count: int = 2

    enable_scheduled_cleanup: bool = True
    cleanup_check_interval_seconds: int = 300

    conversion_profiles_file: Optional[str] = None
    cleanup_strategies_file: Optional[str] = None

    def ensure_dirs(self):
        for dir_path in [
            self.storage_dir,
            self.chunks_dir,
            self.upload_dir,
            self.result_dir,
            self.temp_dir,
            self.logs_dir,
        ]:
            dir_path.mkdir(parents=True, exist_ok=True)

    def get_redis_url(self) -> str:
        if self.redis_password:
            auth = f":{self.redis_password}@"
        else:
            auth = ""
        protocol = "rediss" if self.redis_use_ssl else "redis"
        return f"{protocol}://{auth}{self.redis_host}:{self.redis_port}/{self.redis_db}"

    def load_conversion_profiles(self) -> Dict[str, Any]:
        if self.conversion_profiles_file:
            file_path = Path(self.conversion_profiles_file)
            if file_path.exists():
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        profiles = json.load(f)
                        merged = dict(DEFAULT_CONVERSION_PROFILES)
                        merged.update(profiles)
                        return merged
                except Exception as e:
                    print(f"Warning: Failed to load conversion profiles: {e}")
        return DEFAULT_CONVERSION_PROFILES

    def load_cleanup_strategies(self) -> Dict[str, Any]:
        if self.cleanup_strategies_file:
            file_path = Path(self.cleanup_strategies_file)
            if file_path.exists():
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        strategies = json.load(f)
                        merged = dict(DEFAULT_CLEANUP_STRATEGY)
                        merged.update(strategies)
                        return merged
                except Exception as e:
                    print(f"Warning: Failed to load cleanup strategies: {e}")
        return DEFAULT_CLEANUP_STRATEGY

    class Config:
        env_file = ".env"
        case_sensitive = False
        env_prefix = "FILEENGINE_"


settings = Settings()
settings.ensure_dirs()

conversion_profiles = ConversionProfileConfig(settings.load_conversion_profiles())
cleanup_strategies = CleanupStrategyConfig(settings.load_cleanup_strategies())
