from typing import Optional, Dict, Any, List
from datetime import datetime, timedelta, date
from sqlalchemy import func

from videoprocess.config import settings
from videoprocess.models import (
    VideoStatORM,
    VideoORM,
    TranscodeRecordORM,
    EditRecordORM,
    ThumbnailORM,
    QualityReportORM,
    generate_id,
)


class AnalyticsModule:
    def __init__(self, db_session):
        self.db = db_session

    def get_today_stat(self) -> VideoStatORM:
        today_str = date.today().isoformat()
        stat = self.db.query(VideoStatORM).filter(VideoStatORM.stat_date == today_str).first()

        if not stat:
            stat = VideoStatORM(
                stat_id=generate_id("stat"),
                stat_date=today_str,
                upload_count=0,
                transcode_count=0,
                edit_count=0,
                total_size=0,
                avg_duration=0.0,
            )
            self.db.add(stat)
            self.db.commit()
            self.db.refresh(stat)

        return stat

    def increment_upload_count(self, file_size: int, duration: float):
        stat = self.get_today_stat()
        stat.upload_count += 1
        stat.total_size += file_size

        total_duration = stat.avg_duration * (stat.upload_count - 1) + duration
        stat.avg_duration = round(total_duration / stat.upload_count, 2) if stat.upload_count > 0 else 0.0

        self.db.commit()

    def increment_transcode_count(self):
        stat = self.get_today_stat()
        stat.transcode_count += 1
        self.db.commit()

    def increment_edit_count(self):
        stat = self.get_today_stat()
        stat.edit_count += 1
        self.db.commit()

    def get_stat_by_date(self, stat_date: str) -> Optional[Dict[str, Any]]:
        stat = self.db.query(VideoStatORM).filter(VideoStatORM.stat_date == stat_date).first()
        return stat.to_dict() if stat else None

    def get_stats_range(
        self,
        start_date: str,
        end_date: str,
    ) -> List[Dict[str, Any]]:
        stats = (
            self.db.query(VideoStatORM)
            .filter(VideoStatORM.stat_date >= start_date)
            .filter(VideoStatORM.stat_date <= end_date)
            .order_by(VideoStatORM.stat_date.asc())
            .all()
        )
        return [s.to_dict() for s in stats]

    def get_last_n_days_stats(self, n: int = 7) -> List[Dict[str, Any]]:
        end_date = date.today()
        start_date = end_date - timedelta(days=n - 1)
        return self.get_stats_range(start_date.isoformat(), end_date.isoformat())

    def get_aggregate_stats(self, days: int = 30) -> Dict[str, Any]:
        end_date = date.today()
        start_date = end_date - timedelta(days=days - 1)

        stats = self.get_stats_range(start_date.isoformat(), end_date.isoformat())

        if not stats:
            return {
                "period_days": days,
                "total_uploads": 0,
                "total_transcodes": 0,
                "total_edits": 0,
                "total_size_bytes": 0,
                "total_size_gb": 0,
                "avg_daily_uploads": 0,
                "avg_daily_transcodes": 0,
            }

        total_uploads = sum(s["upload_count"] for s in stats)
        total_transcodes = sum(s["transcode_count"] for s in stats)
        total_edits = sum(s["edit_count"] for s in stats)
        total_size = sum(s["total_size"] for s in stats)

        return {
            "period_days": days,
            "total_uploads": total_uploads,
            "total_transcodes": total_transcodes,
            "total_edits": total_edits,
            "total_size_bytes": total_size,
            "total_size_gb": round(total_size / (1024 * 1024 * 1024), 2),
            "avg_daily_uploads": round(total_uploads / days, 2),
            "avg_daily_transcodes": round(total_transcodes / days, 2),
        }

    def get_video_statistics(self) -> Dict[str, Any]:
        total_videos = self.db.query(VideoORM).count()
        total_size = self.db.query(VideoORM).with_entities(
            func.sum(VideoORM.video_size)
        ).scalar() or 0
        avg_duration = self.db.query(VideoORM).with_entities(
            func.avg(VideoORM.video_duration)
        ).scalar() or 0.0

        status_counts = {}
        statuses = self.db.query(VideoORM.video_status, func.count(VideoORM.video_id)).group_by(
            VideoORM.video_status
        ).all()
        for status, count in statuses:
            status_counts[status] = count

        format_counts = {}
        formats = self.db.query(VideoORM.video_format, func.count(VideoORM.video_id)).group_by(
            VideoORM.video_format
        ).all()
        for fmt, count in formats:
            format_counts[fmt] = count

        return {
            "total_videos": total_videos,
            "total_size_bytes": total_size,
            "total_size_gb": round(total_size / (1024 * 1024 * 1024), 2),
            "avg_duration_seconds": round(avg_duration, 2),
            "status_distribution": status_counts,
            "format_distribution": format_counts,
        }

    def get_transcode_statistics(self) -> Dict[str, Any]:
        total_transcodes = self.db.query(TranscodeRecordORM).count()
        completed = self.db.query(TranscodeRecordORM).filter(
            TranscodeRecordORM.transcode_status == "completed"
        ).count()
        failed = self.db.query(TranscodeRecordORM).filter(
            TranscodeRecordORM.transcode_status == "failed"
        ).count()
        processing = self.db.query(TranscodeRecordORM).filter(
            TranscodeRecordORM.transcode_status == "processing"
        ).count()

        avg_time = self.db.query(TranscodeRecordORM).with_entities(
            func.avg(TranscodeRecordORM.transcode_time)
        ).filter(TranscodeRecordORM.transcode_status == "completed").scalar() or 0.0

        format_counts = {}
        formats = self.db.query(TranscodeRecordORM.target_format, func.count()).group_by(
            TranscodeRecordORM.target_format
        ).all()
        for fmt, count in formats:
            format_counts[fmt] = count

        return {
            "total_transcodes": total_transcodes,
            "completed": completed,
            "failed": failed,
            "processing": processing,
            "success_rate": round((completed / total_transcodes) * 100, 2) if total_transcodes > 0 else 0,
            "avg_transcode_time_seconds": round(avg_time, 2),
            "target_format_distribution": format_counts,
        }

    def get_edit_statistics(self) -> Dict[str, Any]:
        total_edits = self.db.query(EditRecordORM).count()
        completed = self.db.query(EditRecordORM).filter(
            EditRecordORM.edit_status == "completed"
        ).count()
        failed = self.db.query(EditRecordORM).filter(
            EditRecordORM.edit_status == "failed"
        ).count()

        type_counts = {}
        types = self.db.query(EditRecordORM.edit_type, func.count()).group_by(
            EditRecordORM.edit_type
        ).all()
        for et, count in types:
            type_counts[et] = count

        avg_time = self.db.query(EditRecordORM).with_entities(
            func.avg(EditRecordORM.duration)
        ).filter(EditRecordORM.edit_status == "completed").scalar() or 0.0

        return {
            "total_edits": total_edits,
            "completed": completed,
            "failed": failed,
            "success_rate": round((completed / total_edits) * 100, 2) if total_edits > 0 else 0,
            "edit_type_distribution": type_counts,
            "avg_edit_time_seconds": round(avg_time, 2),
        }

    def get_quality_statistics(self) -> Dict[str, Any]:
        total_reports = self.db.query(QualityReportORM).count()
        if total_reports == 0:
            return {"total_reports": 0, "avg_quality_score": 0, "quality_distribution": {}}

        avg_score = self.db.query(QualityReportORM).with_entities(
            func.avg(QualityReportORM.quality_score)
        ).scalar() or 0

        scores = self.db.query(QualityReportORM.quality_score).all()
        distribution = {"excellent": 0, "good": 0, "fair": 0, "poor": 0}

        for (score,) in scores:
            if score >= 90:
                distribution["excellent"] += 1
            elif score >= 70:
                distribution["good"] += 1
            elif score >= 50:
                distribution["fair"] += 1
            else:
                distribution["poor"] += 1

        return {
            "total_reports": total_reports,
            "avg_quality_score": round(avg_score, 2),
            "quality_distribution": distribution,
        }

    def get_comprehensive_report(self, days: int = 30) -> Dict[str, Any]:
        return {
            "generated_at": datetime.now().isoformat(),
            "period_days": days,
            "aggregate": self.get_aggregate_stats(days),
            "videos": self.get_video_statistics(),
            "transcodes": self.get_transcode_statistics(),
            "edits": self.get_edit_statistics(),
            "quality": self.get_quality_statistics(),
        }
