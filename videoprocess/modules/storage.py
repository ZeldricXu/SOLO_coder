import os
import shutil
from pathlib import Path
from typing import Optional, Dict, Any, List
from datetime import datetime, timedelta

from videoprocess.config import settings, DEFAULT_CLEANUP_STRATEGY
from videoprocess.models import VideoORM, ThumbnailORM, TranscodeRecordORM, EditRecordORM
from videoprocess.queue_manager import ReferenceChecker


class StorageModule:
    def __init__(self, db_session):
        self.db = db_session
        self.cleanup_config = DEFAULT_CLEANUP_STRATEGY
        self._reference_checker = ReferenceChecker(db_session)

    def get_storage_usage(self) -> Dict[str, Any]:
        total_size = 0
        file_counts = {
            "uploads": 0,
            "transcoded": 0,
            "thumbnails": 0,
            "temp": 0,
        }

        directories = [
            (settings.uploads_dir, "uploads"),
            (settings.transcoded_dir, "transcoded"),
            (settings.thumbnails_dir, "thumbnails"),
            (settings.temp_dir, "temp"),
        ]

        for dir_path, key in directories:
            if dir_path.exists():
                for f in dir_path.rglob("*"):
                    if f.is_file():
                        total_size += f.stat().st_size
                        file_counts[key] += 1

        max_bytes = self.cleanup_config["max_storage_gb"] * 1024 * 1024 * 1024
        usage_percentage = (total_size / max_bytes) * 100 if max_bytes > 0 else 0

        return {
            "total_size_bytes": total_size,
            "total_size_mb": round(total_size / (1024 * 1024), 2),
            "total_size_gb": round(total_size / (1024 * 1024 * 1024), 2),
            "max_storage_gb": self.cleanup_config["max_storage_gb"],
            "usage_percentage": round(usage_percentage, 2),
            "file_counts": file_counts,
            "needs_cleanup": usage_percentage >= self.cleanup_config["cleanup_percentage"],
        }

    def get_file_size(self, file_path: str) -> int:
        path = Path(file_path)
        if path.exists() and path.is_file():
            return path.stat().st_size
        return 0

    def file_exists(self, file_path: str) -> bool:
        return Path(file_path).exists()

    def delete_file(self, file_path: str) -> bool:
        path = Path(file_path)
        if path.exists():
            try:
                if path.is_file():
                    path.unlink()
                elif path.is_dir():
                    shutil.rmtree(path)
                return True
            except Exception:
                return False
        return False

    def move_file(self, source_path: str, target_path: str) -> bool:
        try:
            source = Path(source_path)
            target = Path(target_path)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(source), str(target))
            return True
        except Exception:
            return False

    def copy_file(self, source_path: str, target_path: str) -> bool:
        try:
            source = Path(source_path)
            target = Path(target_path)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(str(source), str(target))
            return True
        except Exception:
            return False

    def check_video_references(self, video_id: str) -> Dict[str, Any]:
        return self._reference_checker.check_video_references(video_id)

    def can_delete_video(self, video_id: str) -> tuple[bool, Optional[str]]:
        return self._reference_checker.can_delete_video(video_id)

    def cleanup_expired_files(self) -> Dict[str, Any]:
        deleted_count = 0
        deleted_size = 0
        skipped_count = 0
        skipped_size = 0
        skipped_reasons = []
        now = datetime.now()

        upload_expire = timedelta(days=self.cleanup_config["source_expire_days"])
        transcode_expire = timedelta(days=self.cleanup_config["transcoded_expire_days"])
        thumbnail_expire = timedelta(days=self.cleanup_config["thumbnail_expire_days"])

        videos = self.db.query(VideoORM).all()
        for video in videos:
            if video.upload_time:
                age = now - video.upload_time
                if age > upload_expire:
                    can_delete, reason = self.can_delete_video(video.video_id)
                    if can_delete:
                        if self.delete_file(video.storage_path):
                            deleted_count += 1
                            deleted_size += video.video_size
                    else:
                        skipped_count += 1
                        skipped_size += video.video_size
                        if reason:
                            skipped_reasons.append({"video_id": video.video_id, "reason": reason})

        transcodes = self.db.query(TranscodeRecordORM).filter(
            TranscodeRecordORM.transcode_status.in_(["completed", "failed"])
        ).all()
        for t in transcodes:
            if t.output_path:
                file_path = Path(t.output_path)
                if file_path.exists():
                    file_mtime = datetime.fromtimestamp(file_path.stat().st_mtime)
                    if now - file_mtime > transcode_expire:
                        if t.transcode_status == "completed":
                            size = self.get_file_size(t.output_path)
                            if self.delete_file(t.output_path):
                                deleted_count += 1
                                deleted_size += size
                        else:
                            size = self.get_file_size(t.output_path)
                            if self.delete_file(t.output_path):
                                deleted_count += 1
                                deleted_size += size

        thumbnails = self.db.query(ThumbnailORM).all()
        for th in thumbnails:
            file_path = Path(th.thumbnail_path)
            if file_path.exists():
                file_mtime = datetime.fromtimestamp(file_path.stat().st_mtime)
                if now - file_mtime > thumbnail_expire:
                    size = self.get_file_size(th.thumbnail_path)
                    if self.delete_file(th.thumbnail_path):
                        deleted_count += 1
                        deleted_size += size

        if settings.temp_dir.exists():
            for f in settings.temp_dir.rglob("*"):
                if f.is_file():
                    file_mtime = datetime.fromtimestamp(f.stat().st_mtime)
                    if now - file_mtime > timedelta(hours=24):
                        size = f.stat().st_size
                        if self.delete_file(str(f)):
                            deleted_count += 1
                            deleted_size += size

        return {
            "deleted_count": deleted_count,
            "deleted_size_bytes": deleted_size,
            "deleted_size_mb": round(deleted_size / (1024 * 1024), 2),
            "skipped_count": skipped_count,
            "skipped_size_bytes": skipped_size,
            "skipped_size_mb": round(skipped_size / (1024 * 1024), 2),
            "skipped_reasons": skipped_reasons,
            "timestamp": now.isoformat(),
        }

    def cleanup_by_capacity(self) -> Dict[str, Any]:
        usage = self.get_storage_usage()
        if not usage["needs_cleanup"]:
            return {
                "deleted_count": 0,
                "deleted_size_bytes": 0,
                "message": "Storage usage below threshold, no cleanup needed",
            }

        target_deletion = usage["total_size_bytes"] * 0.3
        deleted_count = 0
        deleted_size = 0
        skipped_count = 0
        skipped_reasons = []

        videos = self.db.query(VideoORM).order_by(VideoORM.upload_time.asc()).all()
        for video in videos:
            if deleted_size >= target_deletion:
                break

            can_delete, reason = self.can_delete_video(video.video_id)
            if can_delete:
                if self.delete_file(video.storage_path):
                    deleted_count += 1
                    deleted_size += video.video_size
            else:
                skipped_count += 1
                if reason:
                    skipped_reasons.append({"video_id": video.video_id, "reason": reason})

        return {
            "deleted_count": deleted_count,
            "deleted_size_bytes": deleted_size,
            "deleted_size_mb": round(deleted_size / (1024 * 1024), 2),
            "skipped_count": skipped_count,
            "skipped_reasons": skipped_reasons,
            "message": "Capacity-based cleanup completed",
        }

    def get_referenced_videos(self) -> List[Dict[str, Any]]:
        referenced = []

        transcodes = self.db.query(TranscodeRecordORM).filter(
            TranscodeRecordORM.transcode_status.in_(["pending", "processing", "queued"])
        ).all()
        for t in transcodes:
            referenced.append({
                "video_id": t.video_id,
                "reference_type": "transcode",
                "reference_id": t.transcode_id,
                "status": t.transcode_status,
            })

        edits = self.db.query(EditRecordORM).filter(
            EditRecordORM.edit_status.in_(["pending", "processing", "queued"])
        ).all()
        for e in edits:
            referenced.append({
                "video_id": e.video_id,
                "reference_type": "edit",
                "reference_id": e.edit_id,
                "status": e.edit_status,
            })

            if e.edit_type == "merge":
                params = e.edit_params or {}
                video_ids = params.get("video_ids", [])
                for vid in video_ids:
                    if vid != e.video_id:
                        referenced.append({
                            "video_id": vid,
                            "reference_type": "merge_edit",
                            "reference_id": e.edit_id,
                            "status": e.edit_status,
                        })

        return referenced

    def list_storage_contents(self, directory: str) -> List[Dict[str, Any]]:
        dir_map = {
            "uploads": settings.uploads_dir,
            "transcoded": settings.transcoded_dir,
            "thumbnails": settings.thumbnails_dir,
            "temp": settings.temp_dir,
        }

        dir_path = dir_map.get(directory)
        if not dir_path or not dir_path.exists():
            return []

        contents = []
        for f in dir_path.iterdir():
            if f.is_file():
                stat = f.stat()
                contents.append({
                    "name": f.name,
                    "size_bytes": stat.st_size,
                    "size_mb": round(stat.st_size / (1024 * 1024), 2),
                    "modified_at": datetime.fromtimestamp(stat.st_mtime).isoformat(),
                })

        return sorted(contents, key=lambda x: x["modified_at"], reverse=True)

    def get_directory_size(self, directory: str) -> Dict[str, Any]:
        dir_map = {
            "uploads": settings.uploads_dir,
            "transcoded": settings.transcoded_dir,
            "thumbnails": settings.thumbnails_dir,
            "temp": settings.temp_dir,
        }

        dir_path = dir_map.get(directory)
        if not dir_path or not dir_path.exists():
            return {"size_bytes": 0, "file_count": 0}

        total_size = 0
        file_count = 0
        for f in dir_path.rglob("*"):
            if f.is_file():
                total_size += f.stat().st_size
                file_count += 1

        return {
            "size_bytes": total_size,
            "size_mb": round(total_size / (1024 * 1024), 2),
            "size_gb": round(total_size / (1024 * 1024 * 1024), 4),
            "file_count": file_count,
        }

    def get_cleanup_summary(self) -> Dict[str, Any]:
        usage = self.get_storage_usage()
        referenced = self.get_referenced_videos()

        return {
            "storage_usage": usage,
            "referenced_videos_count": len(referenced),
            "referenced_videos": referenced,
            "cleanup_config": self.cleanup_config,
        }
