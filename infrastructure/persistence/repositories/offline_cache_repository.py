from typing import List, Optional
from sqlalchemy.orm import Session
from datetime import datetime

from infrastructure.persistence.models.offline_cache_model import OfflineCacheModel


class OfflineCacheRepository:
    def __init__(self, db: Session):
        self.db = db

    def save(self, cache_key: str, data_type: str, data: dict, device_id: Optional[str] = None,
             priority: int = 0, ttl_seconds: int = 86400) -> OfflineCacheModel:
        expires_at = datetime.utcnow().timestamp() + ttl_seconds
        db_obj = OfflineCacheModel(
            cache_key=cache_key,
            data_type=data_type,
            device_id=device_id,
            data=data,
            data_size=len(str(data)),
            status="pending",
            sync_attempts=0,
            priority=priority,
            expires_at=datetime.fromtimestamp(expires_at),
        )
        self.db.add(db_obj)
        self.db.commit()
        self.db.refresh(db_obj)
        return db_obj

    def get_pending(self, limit: int = 100) -> List[OfflineCacheModel]:
        return self.db.query(OfflineCacheModel).filter(
            OfflineCacheModel.status == "pending",
            OfflineCacheModel.expires_at > datetime.utcnow()
        ).order_by(OfflineCacheModel.priority.desc(), OfflineCacheModel.stored_at.asc()).limit(limit).all()

    def update_status(self, cache_id: int, status: str, error: Optional[str] = None) -> bool:
        db_obj = self.db.query(OfflineCacheModel).filter(OfflineCacheModel.id == cache_id).first()
        if db_obj:
            db_obj.status = status
            db_obj.sync_attempts += 1
            db_obj.last_sync_attempt = datetime.utcnow()
            if error:
                db_obj.last_sync_error = error
            if status == "synced":
                db_obj.synced_at = datetime.utcnow()
            self.db.commit()
            return True
        return False

    def delete_synced(self, older_than_days: int = 7) -> int:
        cutoff = datetime.utcnow() - timedelta(days=older_than_days)
        count = self.db.query(OfflineCacheModel).filter(
            OfflineCacheModel.status == "synced",
            OfflineCacheModel.synced_at < cutoff
        ).delete()
        self.db.commit()
        return count

    def delete_expired(self) -> int:
        count = self.db.query(OfflineCacheModel).filter(
            OfflineCacheModel.expires_at < datetime.utcnow()
        ).delete()
        self.db.commit()
        return count

    def get_total_size(self) -> int:
        from sqlalchemy import func
        result = self.db.query(func.sum(OfflineCacheModel.data_size)).scalar()
        return result or 0

    def get_count(self) -> int:
        return self.db.query(OfflineCacheModel).count()


from datetime import timedelta
