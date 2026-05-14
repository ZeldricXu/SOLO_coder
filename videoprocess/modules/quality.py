import os
from pathlib import Path
from typing import Optional, Dict, Any, List
from datetime import datetime

from videoprocess.config import settings, QUALITY_THRESHOLDS
from videoprocess.models import QualityReportORM, VideoORM, generate_id


class QualityModule:
    def __init__(self, db_session):
        self.db = db_session
        self.thresholds = QUALITY_THRESHOLDS

    def _estimate_video_info(self, video: VideoORM) -> Dict[str, Any]:
        estimated_bitrates = {
            "mp4": 2000,
            "webm": 1500,
            "avi": 3000,
            "mkv": 2500,
            "mov": 2000,
            "flv": 1000,
            "wmv": 1500,
            "m4v": 2000,
        }
        bitrate = estimated_bitrates.get(video.video_format.lower(), 2000)

        size_mb = video.video_size / (1024 * 1024)
        if video.video_duration > 0:
            actual_bitrate = (size_mb * 8) / video.video_duration
            bitrate = max(int(actual_bitrate), 500)

        resolution_map = {
            (0, 5): "320x180",
            (5, 20): "640x360",
            (20, 100): "1280x720",
            (100, 500): "1920x1080",
            (500, float("inf")): "3840x2160",
        }

        resolution = "1280x720"
        for (lower, upper), res in resolution_map.items():
            if lower <= size_mb < upper:
                resolution = res
                break

        return {
            "resolution": resolution,
            "bitrate": bitrate,
            "frame_rate": 30,
            "duration": video.video_duration,
            "codec": video.video_metadata.get("codec", "h264") if video.video_metadata else "h264",
        }

    def _calculate_quality_score(self, info: Dict[str, Any]) -> int:
        score = 50

        resolution = info.get("resolution", "1280x720")
        width = int(resolution.split("x")[0]) if "x" in resolution else 1280

        if width >= 3840:
            score += 25
        elif width >= 1920:
            score += 20
        elif width >= 1280:
            score += 15
        elif width >= 640:
            score += 10
        else:
            score -= 10

        bitrate = info.get("bitrate", 2000)
        if bitrate >= 10000:
            score += 20
        elif bitrate >= 5000:
            score += 15
        elif bitrate >= 2000:
            score += 10
        elif bitrate >= 1000:
            score += 5
        else:
            score -= 5

        fps = info.get("frame_rate", 30)
        if fps >= 60:
            score += 10
        elif fps >= 30:
            score += 5
        else:
            score -= 5

        return min(max(score, 0), 100)

    def _identify_issues(self, info: Dict[str, Any]) -> List[str]:
        issues = []

        resolution = info.get("resolution", "1280x720")
        width = int(resolution.split("x")[0]) if "x" in resolution else 1280
        min_width = int(self.thresholds["min_resolution"].split("x")[0])

        if width < min_width:
            issues.append(f"分辨率低于推荐标准 ({resolution} < {self.thresholds['min_resolution']})")

        bitrate = info.get("bitrate", 2000)
        if bitrate < self.thresholds["min_bitrate"]:
            issues.append(f"比特率过低 ({bitrate} kbps < {self.thresholds['min_bitrate']} kbps)")
        elif bitrate < self.thresholds["warning_bitrate"]:
            issues.append(f"比特率偏低，可能影响质量")

        fps = info.get("frame_rate", 30)
        if fps < self.thresholds["min_fps"]:
            issues.append(f"帧率过低 ({fps} fps < {self.thresholds['min_fps']} fps)")

        return issues

    def analyze_video(self, video: VideoORM) -> QualityReportORM:
        video_path = Path(video.storage_path)
        if not video_path.exists():
            raise FileNotFoundError(f"视频文件不存在: {video.storage_path}")

        info = self._estimate_video_info(video)

        issues = self._identify_issues(info)
        score = self._calculate_quality_score(info)

        report = QualityReportORM(
            quality_id=generate_id("quality"),
            video_id=video.video_id,
            resolution=info.get("resolution"),
            bitrate=info.get("bitrate", 0),
            frame_rate=info.get("frame_rate", 0.0),
            quality_score=score,
            quality_issues=issues,
            duration=info.get("duration", 0.0),
            codec=info.get("codec"),
        )

        self.db.add(report)
        self.db.commit()
        self.db.refresh(report)

        return report

    def get_quality_report(self, quality_id: str) -> Optional[QualityReportORM]:
        return self.db.query(QualityReportORM).filter(QualityReportORM.quality_id == quality_id).first()

    def get_video_quality_reports(self, video_id: str, limit: int = 10) -> List[QualityReportORM]:
        return (
            self.db.query(QualityReportORM)
            .filter(QualityReportORM.video_id == video_id)
            .order_by(QualityReportORM.detected_at.desc())
            .limit(limit)
            .all()
        )

    def get_latest_report(self, video_id: str) -> Optional[QualityReportORM]:
        return (
            self.db.query(QualityReportORM)
            .filter(QualityReportORM.video_id == video_id)
            .order_by(QualityReportORM.detected_at.desc())
            .first()
        )

    def get_quality_level(self, score: int) -> str:
        if score >= self.thresholds["excellent_score"]:
            return "excellent"
        elif score >= self.thresholds["good_score"]:
            return "good"
        elif score >= self.thresholds["fair_score"]:
            return "fair"
        else:
            return "poor"

    def check_quality_threshold(self, video: VideoORM, min_score: int = 50) -> Dict[str, Any]:
        report = self.get_latest_report(video.video_id)
        if not report:
            report = self.analyze_video(video)

        level = self.get_quality_level(report.quality_score)
        passes = report.quality_score >= min_score

        return {
            "video_id": video.video_id,
            "quality_score": report.quality_score,
            "quality_level": level,
            "passes_threshold": passes,
            "min_threshold": min_score,
            "issues": report.quality_issues,
            "resolution": report.resolution,
            "bitrate": report.bitrate,
            "frame_rate": report.frame_rate,
        }

    def list_all_reports(
        self,
        min_score: Optional[int] = None,
        max_score: Optional[int] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[QualityReportORM]:
        query = self.db.query(QualityReportORM)

        if min_score is not None:
            query = query.filter(QualityReportORM.quality_score >= min_score)
        if max_score is not None:
            query = query.filter(QualityReportORM.quality_score <= max_score)

        return query.order_by(QualityReportORM.detected_at.desc()).offset(offset).limit(limit).all()
