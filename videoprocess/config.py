import os
from pathlib import Path
from typing import Optional, List
from pydantic_settings import BaseSettings


BASE_DIR = Path(__file__).resolve().parent.parent


ALLOWED_VIDEO_FORMATS = ["mp4", "webm", "avi", "mkv", "mov", "flv", "wmv", "m4v"]

ALLOWED_CODECS = {
    "mp4": ["h264", "h265", "libx264", "libx265", "mpeg4"],
    "webm": ["vp9", "vp8", "libvpx", "libvpx-vp9"],
    "avi": ["mpeg4", "libxvid"],
    "mkv": ["h264", "h265", "vp9"],
    "mov": ["h264", "mpeg4"],
    "flv": ["flv", "h264"],
    "wmv": ["wmv2"],
    "m4v": ["h264"],
}

DEFAULT_TRANSCODE_PROFILES = {
    "low": {
        "resolution": "640x360",
        "bitrate": "500k",
        "fps": 24,
        "crf": 28,
    },
    "medium": {
        "resolution": "1280x720",
        "bitrate": "2000k",
        "fps": 30,
        "crf": 23,
    },
    "high": {
        "resolution": "1920x1080",
        "bitrate": "5000k",
        "fps": 30,
        "crf": 18,
    },
    "ultra": {
        "resolution": "3840x2160",
        "bitrate": "10000k",
        "fps": 60,
        "crf": 16,
    },
}

DEFAULT_CLEANUP_STRATEGY = {
    "source_expire_days": 30,
    "transcoded_expire_days": 15,
    "thumbnail_expire_days": 7,
    "max_storage_gb": 500,
    "cleanup_percentage": 85,
    "check_interval_hours": 12,
}

DEFAULT_WATERMARK_CONFIG = {
    "position": "bottom-right",
    "opacity": 0.7,
    "padding": 20,
    "font_size": 36,
    "font_color": "white",
}

THUMBNAIL_CONFIG = {
    "sizes": {"small": "320x180", "medium": "640x360", "large": "1280x720"},
    "default_size": "medium",
    "format": "jpg",
    "quality": 85,
    "capture_time": 1.0,
}

QUALITY_THRESHOLDS = {
    "min_resolution": "320x180",
    "min_bitrate": 500,
    "min_fps": 10,
    "warning_bitrate": 1000,
    "excellent_score": 90,
    "good_score": 70,
    "fair_score": 50,
}


class Settings(BaseSettings):
    app_name: str = "VideoProcess 视频转码与处理服务"
    app_version: str = "1.0.0"
    debug: bool = True

    base_dir: Path = BASE_DIR
    storage_dir: Path = BASE_DIR / "storage"
    uploads_dir: Path = BASE_DIR / "storage" / "uploads"
    transcoded_dir: Path = BASE_DIR / "storage" / "transcoded"
    thumbnails_dir: Path = BASE_DIR / "storage" / "thumbnails"
    temp_dir: Path = BASE_DIR / "storage" / "temp"
    logs_dir: Path = BASE_DIR / "storage" / "logs"

    max_file_size: int = 5 * 1024 * 1024 * 1024
    allowed_video_formats: List[str] = ALLOWED_VIDEO_FORMATS

    database_url: str = "sqlite:///./videoprocess.db"

    ffmpeg_path: Optional[str] = None
    ffprobe_path: Optional[str] = None

    api_host: str = "0.0.0.0"
    api_port: int = 8000

    default_transcode_profile: str = "medium"
    enable_async_processing: bool = True

    redis_url: str = "redis://localhost:6379/0"
    redis_queue_prefix: str = "videoprocess"
    redis_enable_fallback: bool = True

    transcode_max_retries: int = 3
    transcode_retry_delay: int = 5
    transcode_worker_count: int = 1

    edit_max_retries: int = 3
    edit_retry_delay: int = 5
    edit_worker_count: int = 1

    enable_transcode_queue: bool = True
    enable_edit_queue: bool = True

    def ensure_dirs(self):
        for dir_path in [
            self.storage_dir,
            self.uploads_dir,
            self.transcoded_dir,
            self.thumbnails_dir,
            self.temp_dir,
            self.logs_dir,
        ]:
            dir_path.mkdir(parents=True, exist_ok=True)

    class Config:
        env_file = ".env"
        case_sensitive = False
        env_prefix = "VIDEOPROCESS_"


settings = Settings()
settings.ensure_dirs()
