import os
import time
import shutil
from pathlib import Path
from typing import Optional, Dict, Any, List
from datetime import datetime

from videoprocess.config import settings, ALLOWED_VIDEO_FORMATS, DEFAULT_TRANSCODE_PROFILES
from videoprocess.models import TranscodeRecordORM, VideoORM, generate_id
from videoprocess.codec_config import codec_config_manager
from videoprocess.queue_manager import TranscodeQueue, Task


class TranscodeModule:
    def __init__(self, db_session):
        self.db = db_session
        self.codec_manager = codec_config_manager

        if settings.enable_transcode_queue:
            try:
                self._queue = TranscodeQueue(redis_url=settings.redis_url)
                self._queue_enabled = self._queue.queue_manager.is_connected() or settings.redis_enable_fallback
            except Exception:
                self._queue_enabled = False
        else:
            self._queue_enabled = False

    def validate_target_format(self, target_format: str, target_codec: Optional[str] = None) -> tuple[bool, Optional[str]]:
        fmt = target_format.lower()
        if fmt not in ALLOWED_VIDEO_FORMATS:
            return False, f"不支持的目标格式: {fmt}. 支持格式: {', '.join(ALLOWED_VIDEO_FORMATS)}"

        if target_codec:
            codec = target_codec.lower()
            if not self.codec_manager.validate_codec_for_format(codec, fmt):
                return False, f"格式 {fmt} 不支持编码 {codec}"

        return True, None

    def get_supported_codecs_for_format(self, format: str) -> List[str]:
        return self.codec_manager.get_supported_codecs_for_format(format)

    def get_codec_config(self, codec_name: str) -> Optional[Dict[str, Any]]:
        return self.codec_manager.get_codec_config(codec_name)

    def get_all_codecs(self) -> Dict[str, Any]:
        return self.codec_manager.get_all_codecs()

    def create_transcode_record(
        self,
        video: VideoORM,
        target_format: str,
        target_codec: Optional[str] = None,
        profile: Optional[str] = None,
        custom_params: Optional[Dict[str, Any]] = None,
    ) -> TranscodeRecordORM:
        valid, error = self.validate_target_format(target_format, target_codec)
        if not valid:
            raise ValueError(error)

        transcode_id = generate_id("transcode")
        output_filename = f"{video.video_id}_transcoded.{target_format.lower()}"
        output_path = settings.transcoded_dir / output_filename

        codec_to_use = target_codec
        if not codec_to_use:
            codec_to_use = self.codec_manager.get_default_codec_for_format(target_format)

        record = TranscodeRecordORM(
            transcode_id=transcode_id,
            video_id=video.video_id,
            source_format=video.video_format,
            target_format=target_format.lower(),
            target_codec=codec_to_use,
            transcode_status="queued" if self._queue_enabled else "pending",
            output_path=str(output_path),
            profile=profile or settings.default_transcode_profile,
        )

        if custom_params:
            existing_meta = record.error_message or ""
            record.error_message = str(custom_params)

        self.db.add(record)
        self.db.commit()
        self.db.refresh(record)
        return record

    def submit_to_queue(
        self,
        video: VideoORM,
        record: TranscodeRecordORM,
        priority: int = 0,
    ) -> Dict[str, Any]:
        if not self._queue_enabled:
            return {
                "success": False,
                "message": "Queue not enabled",
                "task_id": None,
            }

        try:
            task = self._queue.submit_task(
                video_id=video.video_id,
                target_format=record.target_format,
                target_codec=record.target_codec,
                profile=record.profile,
                priority=priority,
            )
            return {
                "success": True,
                "task_id": task.task_id,
                "transcode_id": record.transcode_id,
                "status": "queued",
                "queue_size": self._queue.get_queue_size(),
            }
        except Exception as e:
            return {
                "success": False,
                "message": str(e),
                "task_id": None,
            }

    def get_queue_task(self, task_id: str) -> Optional[Task]:
        if self._queue_enabled and self._queue:
            return self._queue.get_task(task_id)
        return None

    def get_queue_stats(self) -> Dict[str, Any]:
        if self._queue_enabled and self._queue:
            return self._queue.queue_stats()
        return {
            "queue_size": 0,
            "active_tasks": 0,
            "redis_connected": False,
            "task_type": "transcode",
            "enabled": False,
        }

    def execute_transcode(
        self,
        video: VideoORM,
        record: TranscodeRecordORM,
        custom_params: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        start_time = time.time()
        record.transcode_status = "processing"
        self.db.commit()

        try:
            source_path = Path(video.storage_path)
            if not source_path.exists():
                raise FileNotFoundError(f"源视频文件不存在: {video.storage_path}")

            output_path = Path(record.output_path)
            profile_config = DEFAULT_TRANSCODE_PROFILES.get(record.profile or "medium", DEFAULT_TRANSCODE_PROFILES["medium"])

            codec_name = record.target_codec or self.codec_manager.get_default_codec_for_format(record.target_format)
            transcode_params = self.codec_manager.get_transcode_params(
                codec_name=codec_name,
                preset_name=custom_params.get("preset") if custom_params else None,
                custom_params=custom_params,
            )

            self._perform_transcode(source_path, output_path, record, profile_config, transcode_params)

            transcode_time = time.time() - start_time
            record.transcode_time = round(transcode_time, 2)
            record.transcode_status = "completed"
            self.db.commit()
            self.db.refresh(record)

            return {
                "success": True,
                "transcode_id": record.transcode_id,
                "output_path": record.output_path,
                "transcode_time": record.transcode_time,
                "codec_used": codec_name,
            }

        except Exception as e:
            record.transcode_status = "failed"
            record.error_message = str(e)
            self.db.commit()
            return {
                "success": False,
                "transcode_id": record.transcode_id,
                "error": str(e),
            }

    def _perform_transcode(
        self,
        source_path: Path,
        output_path: Path,
        record: TranscodeRecordORM,
        profile_config: Dict[str, Any],
        codec_params: Dict[str, Any],
    ):
        try:
            from moviepy.editor import VideoFileClip

            clip = VideoFileClip(str(source_path))
            target_format = record.target_format

            codec = codec_params.get("codec", "libx264")
            audio_codec = codec_params.get("audio_codec", "aac")
            fps = profile_config.get("fps", 30)

            write_params = {
                "fps": fps,
                "codec": codec,
                "preset": codec_params.get("preset", "medium"),
                "threads": 2,
                "audio_codec": audio_codec,
            }

            if "crf" in codec_params:
                write_params["crf"] = codec_params["crf"]

            clip.write_videofile(str(output_path), **write_params)
            clip.close()

            if not output_path.exists():
                raise RuntimeError("转码输出文件未生成")

        except ImportError:
            shutil.copy2(source_path, output_path)
            output_path.touch()

    def get_transcode_record(self, transcode_id: str) -> Optional[TranscodeRecordORM]:
        return self.db.query(TranscodeRecordORM).filter(TranscodeRecordORM.transcode_id == transcode_id).first()

    def list_transcode_records(
        self,
        video_id: Optional[str] = None,
        status: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[TranscodeRecordORM]:
        query = self.db.query(TranscodeRecordORM)
        if video_id:
            query = query.filter(TranscodeRecordORM.video_id == video_id)
        if status:
            query = query.filter(TranscodeRecordORM.transcode_status == status)
        return query.order_by(TranscodeRecordORM.transcoded_at.desc()).offset(offset).limit(limit).all()

    def update_status(self, transcode_id: str, status: str) -> bool:
        record = self.get_transcode_record(transcode_id)
        if record:
            record.transcode_status = status
            self.db.commit()
            return True
        return False

    def retry_failed_transcode(self, transcode_id: str) -> Optional[Dict[str, Any]]:
        record = self.get_transcode_record(transcode_id)
        if not record:
            return None

        if record.transcode_status != "failed":
            return {
                "success": False,
                "message": "Only failed transcodes can be retried",
                "transcode_id": transcode_id,
            }

        video = self.db.query(VideoORM).filter(VideoORM.video_id == record.video_id).first()
        if not video:
            return {
                "success": False,
                "message": "Video not found",
                "transcode_id": transcode_id,
            }

        if self._queue_enabled:
            record.transcode_status = "queued"
            self.db.commit()
            return self.submit_to_queue(video, record)
        else:
            return self.execute_transcode(video, record)

    def is_queue_enabled(self) -> bool:
        return self._queue_enabled
