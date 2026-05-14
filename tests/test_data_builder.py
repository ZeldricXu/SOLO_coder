import sys
from pathlib import Path
from datetime import datetime, timedelta
from typing import Optional, Dict, Any, List

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

from videoprocess.models import (
    VideoORM,
    TranscodeRecordORM,
    EditRecordORM,
    QualityReportORM,
    ThumbnailORM,
    VideoStatORM,
    HistoryRecordORM,
    generate_id,
    utc_now,
)


class TestDataBuilder:
    def __init__(self):
        self._video_counter = 0
        self._transcode_counter = 0
        self._edit_counter = 0
        self._quality_counter = 0
        self._thumbnail_counter = 0

    def create_video_info(
        self,
        video_id: Optional[str] = None,
        video_name: str = "测试视频",
        video_format: str = "mp4",
        video_duration: float = 120.0,
        video_size: int = 104857600,
        upload_user: str = "user_001",
        video_status: str = "uploaded",
        storage_path: str = "/storage/videos/video_001.mp4",
        days_ago: int = 0,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> VideoORM:
        if video_id is None:
            self._video_counter += 1
            video_id = f"video_{self._video_counter:03d}"

        upload_time = utc_now() - timedelta(days=days_ago)

        return VideoORM(
            video_id=video_id,
            video_name=video_name,
            video_format=video_format,
            video_duration=video_duration,
            video_size=video_size,
            upload_user=upload_user,
            upload_time=upload_time,
            video_status=video_status,
            storage_path=storage_path,
            metadata=metadata or {"original_name": f"{video_name}.{video_format}"},
        )

    def create_transcode_config(
        self,
        source_format: str = "mp4",
        target_format: str = "webm",
        target_codec: str = "vp9",
        profile: str = "medium",
        quality: str = "high",
        audio_codec: Optional[str] = "aac",
        fps: int = 30,
        resolution: Optional[str] = None,
        bitrate: Optional[str] = None,
    ) -> Dict[str, Any]:
        return {
            "source_format": source_format,
            "target_format": target_format,
            "target_codec": target_codec,
            "profile": profile,
            "quality": quality,
            "audio_codec": audio_codec,
            "fps": fps,
            "resolution": resolution,
            "bitrate": bitrate,
        }

    def create_transcode_record(
        self,
        transcode_id: Optional[str] = None,
        video_id: str = "video_001",
        source_format: str = "mp4",
        target_format: str = "webm",
        target_codec: str = "vp9",
        transcode_status: str = "completed",
        transcode_time: float = 60.5,
        output_path: str = "/storage/videos/video_001.webm",
        profile: str = "medium",
        error_message: Optional[str] = None,
        hours_ago: int = 0,
    ) -> TranscodeRecordORM:
        if transcode_id is None:
            self._transcode_counter += 1
            transcode_id = f"transcode_{self._transcode_counter:03d}"

        transcoded_at = utc_now() - timedelta(hours=hours_ago)

        return TranscodeRecordORM(
            transcode_id=transcode_id,
            video_id=video_id,
            source_format=source_format,
            target_format=target_format,
            target_codec=target_codec,
            transcode_status=transcode_status,
            transcode_time=transcode_time,
            output_path=output_path,
            transcoded_at=transcoded_at,
            profile=profile,
            error_message=error_message,
        )

    def create_edit_params(
        self,
        edit_type: str = "cut",
        start: float = 10.0,
        end: float = 50.0,
        video_ids: Optional[List[str]] = None,
    ) -> Dict[str, Any]:
        if edit_type == "cut":
            return {
                "edit_type": "cut",
                "params": {"start": start, "end": end},
            }
        elif edit_type == "merge":
            return {
                "edit_type": "merge",
                "params": {"video_ids": video_ids or ["video_001", "video_002"]},
            }
        return {}

    def create_edit_record(
        self,
        edit_id: Optional[str] = None,
        video_id: str = "video_001",
        edit_type: str = "cut",
        edit_params: Optional[Dict[str, Any]] = None,
        edit_status: str = "completed",
        output_path: str = "/storage/videos/video_001_cut.mp4",
        duration: float = 35.0,
        error_message: Optional[str] = None,
        hours_ago: int = 0,
    ) -> EditRecordORM:
        if edit_id is None:
            self._edit_counter += 1
            edit_id = f"edit_{self._edit_counter:03d}"

        if edit_params is None:
            edit_params = {"start": 10, "end": 50} if edit_type == "cut" else {"video_ids": ["video_001", "video_002"]}

        edited_at = utc_now() - timedelta(hours=hours_ago)

        return EditRecordORM(
            edit_id=edit_id,
            video_id=video_id,
            edit_type=edit_type,
            edit_params=edit_params,
            edit_status=edit_status,
            output_path=output_path,
            edited_at=edited_at,
            duration=duration,
            error_message=error_message,
        )

    def create_quality_report(
        self,
        quality_id: Optional[str] = None,
        video_id: str = "video_001",
        resolution: str = "1920x1080",
        bitrate: int = 5000,
        frame_rate: float = 30.0,
        quality_score: int = 85,
        quality_issues: Optional[List[str]] = None,
        duration: float = 120.0,
        codec: str = "h264",
        hours_ago: int = 0,
    ) -> QualityReportORM:
        if quality_id is None:
            self._quality_counter += 1
            quality_id = f"quality_{self._quality_counter:03d}"

        detected_at = utc_now() - timedelta(hours=hours_ago)

        return QualityReportORM(
            quality_id=quality_id,
            video_id=video_id,
            resolution=resolution,
            bitrate=bitrate,
            frame_rate=frame_rate,
            quality_score=quality_score,
            quality_issues=quality_issues or [],
            detected_at=detected_at,
            duration=duration,
            codec=codec,
        )

    def create_thumbnail_info(
        self,
        thumbnail_id: Optional[str] = None,
        video_id: str = "video_001",
        thumbnail_path: str = "/storage/thumbnails/video_001_thumb.jpg",
        thumbnail_size: int = 51200,
        size_name: str = "medium",
        width: int = 640,
        height: int = 360,
        days_ago: int = 0,
    ) -> ThumbnailORM:
        if thumbnail_id is None:
            self._thumbnail_counter += 1
            thumbnail_id = f"thumb_{self._thumbnail_counter:03d}"

        generated_at = utc_now() - timedelta(days=days_ago)

        return ThumbnailORM(
            thumbnail_id=thumbnail_id,
            video_id=video_id,
            thumbnail_path=thumbnail_path,
            thumbnail_size=thumbnail_size,
            generated_at=generated_at,
            size_name=size_name,
            width=width,
            height=height,
        )

    def create_statistics_data(
        self,
        stat_date: Optional[str] = None,
        upload_count: int = 50,
        transcode_count: int = 40,
        edit_count: int = 20,
        total_size: int = 1048576000,
        avg_duration: float = 180.0,
        days_ago: int = 0,
    ) -> VideoStatORM:
        if stat_date is None:
            stat_date = (datetime.now() - timedelta(days=days_ago)).date().isoformat()

        return VideoStatORM(
            stat_id=generate_id("stat"),
            stat_date=stat_date,
            upload_count=upload_count,
            transcode_count=transcode_count,
            edit_count=edit_count,
            total_size=total_size,
            avg_duration=avg_duration,
        )

    def create_history_record(
        self,
        video_id: str = "video_001",
        action_type: str = "transcode",
        action_details: Optional[Dict[str, Any]] = None,
        status: str = "completed",
        duration: float = 45.0,
        result_path: Optional[str] = "/storage/videos/video_001_processed.mp4",
        hours_ago: int = 0,
    ) -> HistoryRecordORM:
        if action_details is None:
            if action_type == "transcode":
                action_details = {"target_format": "webm", "profile": "medium"}
            elif action_type == "edit":
                action_details = {"edit_type": "cut", "start": 10, "end": 50}
            elif action_type == "upload":
                action_details = {"original_name": "test_video.mp4", "size": 104857600}
            else:
                action_details = {}

        created_at = utc_now() - timedelta(hours=hours_ago)

        return HistoryRecordORM(
            history_id=generate_id("history"),
            video_id=video_id,
            action_type=action_type,
            action_details=action_details,
            status=status,
            created_at=created_at,
            duration=duration,
            result_path=result_path,
        )

    def create_sample_videos(self, count: int = 5) -> List[VideoORM]:
        formats = ["mp4", "webm", "avi", "mkv", "mov"]
        durations = [60, 120, 180, 240, 300]
        sizes = [52428800, 104857600, 209715200, 314572800, 419430400]
        names = ["产品介绍", "教程演示", "发布会", "培训视频", "项目展示"]
        users = ["user_001", "user_002", "user_003"]

        videos = []
        for i in range(count):
            videos.append(
                self.create_video_info(
                    video_name=f"{names[i % len(names)]}_{i + 1}",
                    video_format=formats[i % len(formats)],
                    video_duration=durations[i % len(durations)],
                    video_size=sizes[i % len(sizes)],
                    upload_user=users[i % len(users)],
                    days_ago=i,
                    storage_path=f"/storage/videos/video_{i + 1:03d}.{formats[i % len(formats)]}",
                )
            )
        return videos

    def create_transcode_scenarios(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": "mp4_to_webm_vp9",
                "config": self.create_transcode_config(
                    source_format="mp4",
                    target_format="webm",
                    target_codec="vp9",
                    profile="medium",
                ),
                "expected": {
                    "target_format": "webm",
                    "target_codec": "vp9",
                    "valid": True,
                },
            },
            {
                "name": "avi_to_mp4_h264",
                "config": self.create_transcode_config(
                    source_format="avi",
                    target_format="mp4",
                    target_codec="h264",
                    profile="high",
                    fps=60,
                ),
                "expected": {
                    "target_format": "mp4",
                    "target_codec": "h264",
                    "valid": True,
                },
            },
            {
                "name": "mkv_to_mov",
                "config": self.create_transcode_config(
                    source_format="mkv",
                    target_format="mov",
                    target_codec="h264",
                    profile="low",
                ),
                "expected": {
                    "target_format": "mov",
                    "target_codec": "h264",
                    "valid": True,
                },
            },
            {
                "name": "invalid_format",
                "config": self.create_transcode_config(
                    source_format="mp4",
                    target_format="invalid_format",
                    target_codec="h264",
                ),
                "expected": {
                    "valid": False,
                    "error_contains": "不支持",
                },
            },
        ]

    def create_edit_scenarios(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": "simple_cut",
                "params": self.create_edit_params(
                    edit_type="cut",
                    start=10.0,
                    end=50.0,
                ),
                "video_duration": 120.0,
                "expected": {"valid": True, "output_duration": 40.0},
            },
            {
                "name": "cut_from_start",
                "params": self.create_edit_params(
                    edit_type="cut",
                    start=0.0,
                    end=60.0,
                ),
                "video_duration": 120.0,
                "expected": {"valid": True, "output_duration": 60.0},
            },
            {
                "name": "cut_to_end",
                "params": {
                    "edit_type": "cut",
                    "params": {"start": 30.0},
                },
                "video_duration": 120.0,
                "expected": {"valid": True, "output_duration": 90.0},
            },
            {
                "name": "invalid_start_time",
                "params": self.create_edit_params(
                    edit_type="cut",
                    start=-10.0,
                    end=50.0,
                ),
                "video_duration": 120.0,
                "expected": {"valid": False, "error_contains": "负数"},
            },
            {
                "name": "end_after_duration",
                "params": self.create_edit_params(
                    edit_type="cut",
                    start=10.0,
                    end=200.0,
                ),
                "video_duration": 120.0,
                "expected": {"valid": False, "error_contains": "超出"},
            },
            {
                "name": "end_before_start",
                "params": self.create_edit_params(
                    edit_type="cut",
                    start=50.0,
                    end=10.0,
                ),
                "video_duration": 120.0,
                "expected": {"valid": False, "error_contains": "大于"},
            },
        ]

    def create_quality_scenarios(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": "excellent_quality_4k",
                "video_info": {
                    "resolution": "3840x2160",
                    "bitrate": 15000,
                    "frame_rate": 60.0,
                },
                "expected": {
                    "min_score": 90,
                    "level": "excellent",
                    "issues": 0,
                },
            },
            {
                "name": "good_quality_1080p",
                "video_info": {
                    "resolution": "1920x1080",
                    "bitrate": 5000,
                    "frame_rate": 30.0,
                },
                "expected": {
                    "min_score": 70,
                    "max_score": 89,
                    "level": "good",
                },
            },
            {
                "name": "fair_quality_720p",
                "video_info": {
                    "resolution": "1280x720",
                    "bitrate": 2000,
                    "frame_rate": 24.0,
                },
                "expected": {
                    "min_score": 50,
                    "max_score": 69,
                    "level": "fair",
                },
            },
            {
                "name": "poor_quality_low_res",
                "video_info": {
                    "resolution": "320x180",
                    "bitrate": 300,
                    "frame_rate": 10.0,
                },
                "expected": {
                    "max_score": 49,
                    "level": "poor",
                    "issues_greater_than": 0,
                },
            },
            {
                "name": "high_res_low_bitrate",
                "video_info": {
                    "resolution": "1920x1080",
                    "bitrate": 400,
                    "frame_rate": 30.0,
                },
                "expected": {
                    "issues_contains": "比特率",
                },
            },
        ]

    def create_watermark_scenarios(self) -> List[Dict[str, Any]]:
        positions = ["top-left", "top-right", "bottom-left", "bottom-right", "center"]
        return [
            {
                "name": f"text_watermark_{pos}",
                "config": {
                    "type": "text",
                    "text": "VideoProcess",
                    "position": pos,
                    "opacity": 0.7,
                    "font_size": 36,
                },
                "expected": {
                    "success": True,
                    "position": pos,
                },
            }
            for pos in positions
        ]

    def create_cleanup_scenarios(self) -> List[Dict[str, Any]]:
        return [
            {
                "name": "expired_source_videos",
                "videos": [
                    {"days_ago": 35, "should_delete": True},
                    {"days_ago": 25, "should_delete": False},
                    {"days_ago": 40, "should_delete": True},
                    {"days_ago": 1, "should_delete": False},
                ],
                "expected_deleted_count": 2,
            },
            {
                "name": "expired_thumbnails",
                "thumbnails": [
                    {"days_ago": 10, "should_delete": True},
                    {"days_ago": 5, "should_delete": False},
                    {"days_ago": 15, "should_delete": True},
                ],
                "expected_deleted_count": 2,
            },
        ]

    def reset_counters(self):
        self._video_counter = 0
        self._transcode_counter = 0
        self._edit_counter = 0
        self._quality_counter = 0
        self._thumbnail_counter = 0


test_data_builder = TestDataBuilder()
