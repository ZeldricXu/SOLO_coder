import os
import time
import shutil
from pathlib import Path
from typing import Optional, Dict, Any, List
from datetime import datetime

from videoprocess.config import settings
from videoprocess.models import EditRecordORM, VideoORM, generate_id
from videoprocess.queue_manager import EditQueue, Task


class EditModule:
    def __init__(self, db_session):
        self.db = db_session

        if settings.enable_edit_queue:
            try:
                self._queue = EditQueue(redis_url=settings.redis_url)
                self._queue_enabled = self._queue.queue_manager.is_connected() or settings.redis_enable_fallback
            except Exception:
                self._queue_enabled = False
        else:
            self._queue_enabled = False

    def validate_edit_params(self, edit_type: str, edit_params: Dict[str, Any], video_duration: float) -> tuple[bool, Optional[str]]:
        if edit_type == "cut":
            start = edit_params.get("start", 0)
            end = edit_params.get("end")

            if start < 0:
                return False, "起始时间不能为负数"

            if end is not None:
                if end <= start:
                    return False, "结束时间必须大于起始时间"
                if end > video_duration:
                    return False, f"结束时间超出视频时长 {video_duration}s"

            return True, None

        elif edit_type == "merge":
            video_list = edit_params.get("video_ids", [])
            if not video_list or len(video_list) < 2:
                return False, "合并操作需要至少2个视频ID"
            return True, None

        else:
            return False, f"不支持的编辑类型: {edit_type}. 支持: cut, merge"

    def create_edit_record(
        self,
        video: VideoORM,
        edit_type: str,
        edit_params: Dict[str, Any],
    ) -> EditRecordORM:
        valid, error = self.validate_edit_params(edit_type, edit_params, video.video_duration)
        if not valid:
            raise ValueError(error)

        edit_id = generate_id("edit")
        output_filename = f"{video.video_id}_{edit_type}.{video.video_format}"
        output_path = settings.transcoded_dir / output_filename

        record = EditRecordORM(
            edit_id=edit_id,
            video_id=video.video_id,
            edit_type=edit_type,
            edit_params=edit_params,
            edit_status="queued" if self._queue_enabled else "pending",
            output_path=str(output_path),
        )

        self.db.add(record)
        self.db.commit()
        self.db.refresh(record)
        return record

    def submit_to_queue(
        self,
        video: VideoORM,
        record: EditRecordORM,
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
                edit_type=record.edit_type,
                edit_params=record.edit_params,
                priority=priority,
            )
            return {
                "success": True,
                "task_id": task.task_id,
                "edit_id": record.edit_id,
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
            "task_type": "edit",
            "enabled": False,
        }

    def execute_cut(
        self,
        video: VideoORM,
        record: EditRecordORM,
    ) -> Dict[str, Any]:
        start_time = time.time()
        record.edit_status = "processing"
        self.db.commit()

        try:
            params = record.edit_params
            start = params.get("start", 0)
            end = params.get("end")

            source_path = Path(video.storage_path)
            if not source_path.exists():
                raise FileNotFoundError(f"源视频文件不存在: {video.storage_path}")

            output_path = Path(record.output_path)
            self._perform_cut(source_path, output_path, start, end, video.video_format)

            process_time = time.time() - start_time
            record.duration = round(process_time, 2)
            record.edit_status = "completed"
            self.db.commit()
            self.db.refresh(record)

            return {
                "success": True,
                "edit_id": record.edit_id,
                "output_path": record.output_path,
                "duration": record.duration,
            }

        except Exception as e:
            record.edit_status = "failed"
            record.error_message = str(e)
            self.db.commit()
            return {
                "success": False,
                "edit_id": record.edit_id,
                "error": str(e),
            }

    def execute_merge(
        self,
        main_video: VideoORM,
        record: EditRecordORM,
        videos_to_merge: List[VideoORM],
    ) -> Dict[str, Any]:
        start_time = time.time()
        record.edit_status = "processing"
        self.db.commit()

        try:
            all_videos = [main_video] + videos_to_merge
            source_paths = []
            for v in all_videos:
                p = Path(v.storage_path)
                if not p.exists():
                    raise FileNotFoundError(f"视频文件不存在: {v.storage_path}")
                source_paths.append(p)

            output_path = Path(record.output_path)
            self._perform_merge(source_paths, output_path, main_video.video_format)

            process_time = time.time() - start_time
            record.duration = round(process_time, 2)
            record.edit_status = "completed"
            self.db.commit()
            self.db.refresh(record)

            return {
                "success": True,
                "edit_id": record.edit_id,
                "output_path": record.output_path,
                "duration": record.duration,
            }

        except Exception as e:
            record.edit_status = "failed"
            record.error_message = str(e)
            self.db.commit()
            return {
                "success": False,
                "edit_id": record.edit_id,
                "error": str(e),
            }

    def _perform_cut(
        self,
        source_path: Path,
        output_path: Path,
        start: float,
        end: Optional[float],
        video_format: str,
    ):
        try:
            from moviepy.editor import VideoFileClip

            clip = VideoFileClip(str(source_path))
            if end is not None:
                subclip = clip.subclip(start, end)
            else:
                subclip = clip.subclip(start)

            from videoprocess.codec_config import codec_config_manager

            codec_name = codec_config_manager.get_default_codec_for_format(video_format)
            codec_cfg = codec_config_manager.get_codec_config(codec_name)
            codec = codec_cfg.get("codec_name", "libx264") if codec_cfg else "libx264"
            audio_codec = codec_cfg.get("default_audio_codec", "aac") if codec_cfg else "aac"

            subclip.write_videofile(str(output_path), codec=codec, audio_codec=audio_codec)
            clip.close()
            subclip.close()

        except ImportError:
            shutil.copy2(source_path, output_path)

    def _perform_merge(
        self,
        source_paths: List[Path],
        output_path: Path,
        video_format: str,
    ):
        try:
            from moviepy.editor import VideoFileClip, concatenate_videoclips

            clips = [VideoFileClip(str(p)) for p in source_paths]
            final_clip = concatenate_videoclips(clips, method="compose")

            from videoprocess.codec_config import codec_config_manager

            codec_name = codec_config_manager.get_default_codec_for_format(video_format)
            codec_cfg = codec_config_manager.get_codec_config(codec_name)
            codec = codec_cfg.get("codec_name", "libx264") if codec_cfg else "libx264"
            audio_codec = codec_cfg.get("default_audio_codec", "aac") if codec_cfg else "aac"

            final_clip.write_videofile(str(output_path), codec=codec, audio_codec=audio_codec)

            for clip in clips:
                clip.close()
            final_clip.close()

        except ImportError:
            if source_paths:
                shutil.copy2(source_paths[0], output_path)

    def get_edit_record(self, edit_id: str) -> Optional[EditRecordORM]:
        return self.db.query(EditRecordORM).filter(EditRecordORM.edit_id == edit_id).first()

    def list_edit_records(
        self,
        video_id: Optional[str] = None,
        edit_type: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[EditRecordORM]:
        query = self.db.query(EditRecordORM)
        if video_id:
            query = query.filter(EditRecordORM.video_id == video_id)
        if edit_type:
            query = query.filter(EditRecordORM.edit_type == edit_type)
        return query.order_by(EditRecordORM.edited_at.desc()).offset(offset).limit(limit).all()

    def is_queue_enabled(self) -> bool:
        return self._queue_enabled

    def update_status(self, edit_id: str, status: str) -> bool:
        record = self.get_edit_record(edit_id)
        if record:
            record.edit_status = status
            self.db.commit()
            return True
        return False

    def retry_failed_edit(self, edit_id: str) -> Optional[Dict[str, Any]]:
        record = self.get_edit_record(edit_id)
        if not record:
            return None

        if record.edit_status != "failed":
            return {
                "success": False,
                "message": "Only failed edits can be retried",
                "edit_id": edit_id,
            }

        video = self.db.query(VideoORM).filter(VideoORM.video_id == record.video_id).first()
        if not video:
            return {
                "success": False,
                "message": "Video not found",
                "edit_id": edit_id,
            }

        if record.edit_type == "cut":
            if self._queue_enabled:
                record.edit_status = "queued"
                self.db.commit()
                return self.submit_to_queue(video, record)
            else:
                return self.execute_cut(video, record)
        elif record.edit_type == "merge":
            video_ids = record.edit_params.get("video_ids", [])
            merge_videos = []
            for vid in video_ids[1:]:
                v = self.db.query(VideoORM).filter(VideoORM.video_id == vid).first()
                if v:
                    merge_videos.append(v)

            if self._queue_enabled:
                record.edit_status = "queued"
                self.db.commit()
                return self.submit_to_queue(video, record)
            else:
                return self.execute_merge(video, record, merge_videos)

        return {
            "success": False,
            "message": f"Unknown edit type",
            "edit_id": edit_id,
        }
