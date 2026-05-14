import os
from pathlib import Path
from typing import Optional, Dict, Any, List
from datetime import datetime

from videoprocess.config import settings, THUMBNAIL_CONFIG
from videoprocess.models import ThumbnailORM, VideoORM, generate_id


class ThumbnailModule:
    def __init__(self, db_session):
        self.db = db_session
        self.config = THUMBNAIL_CONFIG

    def _parse_size(self, size_str: str) -> tuple[int, int]:
        parts = size_str.split("x")
        if len(parts) == 2:
            return int(parts[0]), int(parts[1])
        return 640, 360

    def generate_thumbnail(
        self,
        video: VideoORM,
        size_name: str = "medium",
        capture_time: Optional[float] = None,
    ) -> ThumbnailORM:
        video_path = Path(video.storage_path)
        if not video_path.exists():
            raise FileNotFoundError(f"视频文件不存在: {video.storage_path}")

        size_str = self.config["sizes"].get(size_name, self.config["sizes"]["medium"])
        width, height = self._parse_size(size_str)
        capture = capture_time or self.config["capture_time"]

        thumbnail_id = generate_id("thumb")
        output_filename = f"{video.video_id}_{size_name}.{self.config['format']}"
        output_path = settings.thumbnails_dir / output_filename

        try:
            from moviepy.editor import VideoFileClip

            clip = VideoFileClip(str(video_path))
            actual_time = min(capture, clip.duration)

            frame = clip.get_frame(actual_time)

            try:
                from PIL import Image
                import numpy as np

                img = Image.fromarray(frame)
                img = img.resize((width, height), Image.LANCZOS)

                if self.config["format"].lower() in ["jpg", "jpeg"]:
                    if img.mode in ("RGBA", "P"):
                        img = img.convert("RGB")
                    img.save(str(output_path), "JPEG", quality=self.config["quality"])
                else:
                    img.save(str(output_path))

            except ImportError:
                import numpy as np
                from PIL import Image

                img = Image.fromarray(frame)
                img = img.resize((width, height), Image.LANCZOS)
                img.save(str(output_path))

            clip.close()

        except ImportError:
            try:
                from PIL import Image
                import numpy as np

                img = Image.new("RGB", (width, height), color=(50, 50, 80))
                img.save(str(output_path), "JPEG", quality=self.config["quality"])
            except ImportError:
                output_path.touch()

        file_size = output_path.stat().st_size if output_path.exists() else 0

        thumbnail = ThumbnailORM(
            thumbnail_id=thumbnail_id,
            video_id=video.video_id,
            thumbnail_path=str(output_path),
            thumbnail_size=file_size,
            size_name=size_name,
            width=width,
            height=height,
        )

        self.db.add(thumbnail)
        self.db.commit()
        self.db.refresh(thumbnail)

        return thumbnail

    def generate_thumbnails_batch(
        self,
        video: VideoORM,
        sizes: Optional[List[str]] = None,
        capture_time: Optional[float] = None,
    ) -> List[ThumbnailORM]:
        if not sizes:
            sizes = list(self.config["sizes"].keys())

        thumbnails = []
        for size in sizes:
            if size in self.config["sizes"]:
                thumbnail = self.generate_thumbnail(video, size, capture_time)
                thumbnails.append(thumbnail)

        return thumbnails

    def get_thumbnail(self, thumbnail_id: str) -> Optional[ThumbnailORM]:
        return self.db.query(ThumbnailORM).filter(ThumbnailORM.thumbnail_id == thumbnail_id).first()

    def get_video_thumbnails(self, video_id: str) -> List[ThumbnailORM]:
        return (
            self.db.query(ThumbnailORM)
            .filter(ThumbnailORM.video_id == video_id)
            .order_by(ThumbnailORM.generated_at.desc())
            .all()
        )

    def get_default_thumbnail(self, video_id: str) -> Optional[ThumbnailORM]:
        default_size = self.config["default_size"]
        thumbnails = (
            self.db.query(ThumbnailORM)
            .filter(ThumbnailORM.video_id == video_id)
            .filter(ThumbnailORM.size_name == default_size)
            .order_by(ThumbnailORM.generated_at.desc())
            .first()
        )

        if thumbnails:
            return thumbnails

        all_thumbs = (
            self.db.query(ThumbnailORM)
            .filter(ThumbnailORM.video_id == video_id)
            .order_by(ThumbnailORM.generated_at.desc())
            .first()
        )
        return all_thumbs

    def list_all_thumbnails(
        self,
        video_id: Optional[str] = None,
        size_name: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[ThumbnailORM]:
        query = self.db.query(ThumbnailORM)

        if video_id:
            query = query.filter(ThumbnailORM.video_id == video_id)
        if size_name:
            query = query.filter(ThumbnailORM.size_name == size_name)

        return query.order_by(ThumbnailORM.generated_at.desc()).offset(offset).limit(limit).all()

    def get_available_sizes(self) -> Dict[str, str]:
        return dict(self.config["sizes"])

    def get_default_size(self) -> str:
        return self.config["default_size"]

    def delete_thumbnail(self, thumbnail_id: str) -> bool:
        thumbnail = self.get_thumbnail(thumbnail_id)
        if not thumbnail:
            return False

        try:
            path = Path(thumbnail.thumbnail_path)
            if path.exists():
                path.unlink()
        except Exception:
            pass

        self.db.delete(thumbnail)
        self.db.commit()
        return True

    def delete_video_thumbnails(self, video_id: str) -> int:
        thumbnails = self.get_video_thumbnails(video_id)
        count = 0

        for thumb in thumbnails:
            if self.delete_thumbnail(thumb.thumbnail_id):
                count += 1

        return count
