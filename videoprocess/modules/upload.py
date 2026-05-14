import os
import shutil
from pathlib import Path
from typing import Optional, Dict, Any
from datetime import datetime

from videoprocess.config import settings, ALLOWED_VIDEO_FORMATS
from videoprocess.models import VideoORM, generate_id


class UploadModule:
    def __init__(self, db_session):
        self.db = db_session

    def validate_format(self, filename: str) -> tuple[bool, Optional[str]]:
        ext = Path(filename).suffix.lower().lstrip(".")
        if ext not in ALLOWED_VIDEO_FORMATS:
            return False, f"不支持的视频格式: {ext}. 支持格式: {', '.join(ALLOWED_VIDEO_FORMATS)}"
        return True, ext

    def validate_size(self, file_size: int) -> tuple[bool, Optional[str]]:
        if file_size > settings.max_file_size:
            max_mb = settings.max_file_size / (1024 * 1024)
            return False, f"文件大小超过限制. 最大允许: {max_mb:.1f} MB"
        return True, None

    def save_video(
        self,
        file_content: bytes,
        original_filename: str,
        upload_user: str = "anonymous",
    ) -> Dict[str, Any]:
        valid, ext = self.validate_format(original_filename)
        if not valid:
            raise ValueError(ext)

        file_size = len(file_content)
        valid, error = self.validate_size(file_size)
        if not valid:
            raise ValueError(error)

        video_id = generate_id("video")
        safe_name = "".join(
            c for c in Path(original_filename).stem if c.isalnum() or c in "_- "
        ).strip() or "video"
        storage_filename = f"{video_id}.{ext}"
        storage_path = settings.uploads_dir / storage_filename

        with open(storage_path, "wb") as f:
            f.write(file_content)

        duration = self._estimate_duration(file_size, ext)

        video = VideoORM(
            video_id=video_id,
            video_name=original_filename,
            video_format=ext,
            video_duration=duration,
            video_size=file_size,
            upload_user=upload_user,
            video_status="uploaded",
            storage_path=str(storage_path),
            video_metadata={"original_name": original_filename, "safe_name": safe_name},
        )

        self.db.add(video)
        self.db.commit()
        self.db.refresh(video)

        return video.to_dict()

    def get_video(self, video_id: str) -> Optional[VideoORM]:
        return self.db.query(VideoORM).filter(VideoORM.video_id == video_id).first()

    def update_video_status(self, video_id: str, status: str) -> bool:
        video = self.get_video(video_id)
        if video:
            video.video_status = status
            self.db.commit()
            return True
        return False

    def list_videos(self, upload_user: Optional[str] = None, limit: int = 100, offset: int = 0):
        query = self.db.query(VideoORM)
        if upload_user:
            query = query.filter(VideoORM.upload_user == upload_user)
        return query.order_by(VideoORM.upload_time.desc()).offset(offset).limit(limit).all()

    def _estimate_duration(self, file_size: int, format: str) -> float:
        bitrate_estimates = {
            "mp4": 2000000,
            "webm": 1500000,
            "avi": 3000000,
            "mkv": 2500000,
            "mov": 2000000,
            "flv": 1000000,
            "wmv": 1500000,
            "m4v": 2000000,
        }
        bitrate = bitrate_estimates.get(format, 2000000)
        return round(file_size * 8 / bitrate, 2)
