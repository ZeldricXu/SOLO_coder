from typing import Optional, Dict, Any, List
from datetime import datetime, timedelta

from videoprocess.config import settings
from videoprocess.models import HistoryRecordORM, VideoORM, generate_id


class HistoryModule:
    def __init__(self, db_session):
        self.db = db_session

    def record_action(
        self,
        video_id: str,
        action_type: str,
        action_details: Dict[str, Any] = None,
        status: str = "completed",
        duration: float = 0.0,
        result_path: Optional[str] = None,
    ) -> HistoryRecordORM:
        record = HistoryRecordORM(
            history_id=generate_id("history"),
            video_id=video_id,
            action_type=action_type,
            action_details=action_details or {},
            status=status,
            duration=duration,
            result_path=result_path,
        )

        self.db.add(record)
        self.db.commit()
        self.db.refresh(record)
        return record

    def get_history_record(self, history_id: str) -> Optional[HistoryRecordORM]:
        return self.db.query(HistoryRecordORM).filter(HistoryRecordORM.history_id == history_id).first()

    def get_video_history(
        self,
        video_id: str,
        action_type: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[HistoryRecordORM]:
        query = self.db.query(HistoryRecordORM).filter(HistoryRecordORM.video_id == video_id)

        if action_type:
            query = query.filter(HistoryRecordORM.action_type == action_type)

        return query.order_by(HistoryRecordORM.created_at.desc()).offset(offset).limit(limit).all()

    def get_user_history(
        self,
        upload_user: str,
        action_type: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[HistoryRecordORM]:
        video_ids = [
            v.video_id
            for v in self.db.query(VideoORM).filter(VideoORM.upload_user == upload_user).all()
        ]

        if not video_ids:
            return []

        query = self.db.query(HistoryRecordORM).filter(HistoryRecordORM.video_id.in_(video_ids))

        if action_type:
            query = query.filter(HistoryRecordORM.action_type == action_type)

        return query.order_by(HistoryRecordORM.created_at.desc()).offset(offset).limit(limit).all()

    def get_recent_history(
        self,
        hours: int = 24,
        action_type: Optional[str] = None,
        limit: int = 100,
    ) -> List[HistoryRecordORM]:
        since = datetime.now() - timedelta(hours=hours)

        query = self.db.query(HistoryRecordORM).filter(HistoryRecordORM.created_at >= since)

        if action_type:
            query = query.filter(HistoryRecordORM.action_type == action_type)

        return query.order_by(HistoryRecordORM.created_at.desc()).limit(limit).all()

    def list_all_history(
        self,
        action_type: Optional[str] = None,
        status: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[HistoryRecordORM]:
        query = self.db.query(HistoryRecordORM)

        if action_type:
            query = query.filter(HistoryRecordORM.action_type == action_type)
        if status:
            query = query.filter(HistoryRecordORM.status == status)
        if start_date:
            start_dt = datetime.fromisoformat(start_date) if "T" in start_date else datetime.strptime(start_date, "%Y-%m-%d")
            query = query.filter(HistoryRecordORM.created_at >= start_dt)
        if end_date:
            end_dt = datetime.fromisoformat(end_date) if "T" in end_date else datetime.strptime(end_date, "%Y-%m-%d")
            end_dt = end_dt.replace(hour=23, minute=59, second=59)
            query = query.filter(HistoryRecordORM.created_at <= end_dt)

        return query.order_by(HistoryRecordORM.created_at.desc()).offset(offset).limit(limit).all()

    def get_action_statistics(self, video_id: Optional[str] = None) -> Dict[str, Any]:
        query = self.db.query(HistoryRecordORM)
        if video_id:
            query = query.filter(HistoryRecordORM.video_id == video_id)

        total = query.count()

        action_counts = {}
        actions = self.db.query(HistoryRecordORM.action_type, HistoryRecordORM.video_id).filter(
            (HistoryRecordORM.video_id == video_id) if video_id else True
        ).all() if video_id else self.db.query(HistoryRecordORM.action_type).all()

        action_types = {}
        all_records = query.all()
        for r in all_records:
            action_types[r.action_type] = action_types.get(r.action_type, 0) + 1

        status_counts = {}
        for r in all_records:
            status_counts[r.status] = status_counts.get(r.status, 0) + 1

        return {
            "total_records": total,
            "action_distribution": action_types,
            "status_distribution": status_counts,
            "video_id": video_id,
        }

    def delete_video_history(self, video_id: str) -> int:
        count = self.db.query(HistoryRecordORM).filter(HistoryRecordORM.video_id == video_id).delete()
        self.db.commit()
        return count

    def delete_old_history(self, days: int = 90) -> int:
        cutoff = datetime.now() - timedelta(days=days)
        count = self.db.query(HistoryRecordORM).filter(HistoryRecordORM.created_at < cutoff).delete()
        self.db.commit()
        return count

    def get_available_action_types(self) -> List[str]:
        types = self.db.query(HistoryRecordORM.action_type).distinct().all()
        return [t[0] for t in types]
