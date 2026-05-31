from sqlalchemy import Column, String, DateTime, JSON, Integer, Boolean
from datetime import datetime

from infrastructure.persistence.database import Base


class OfflineCacheModel(Base):
    __tablename__ = "offline_cache"

    id = Column(Integer, primary_key=True, autoincrement=True)
    cache_key = Column(String, unique=True, index=True)
    data_type = Column(String, index=True)
    device_id = Column(String, index=True)

    data = Column(JSON, default=dict)
    data_size = Column(Integer, default=0)

    status = Column(String, default="pending")
    sync_attempts = Column(Integer, default=0)
    last_sync_attempt = Column(DateTime)
    last_sync_error = Column(String)

    stored_at = Column(DateTime, default=datetime.utcnow, index=True)
    synced_at = Column(DateTime, index=True)
    expires_at = Column(DateTime, index=True)

    priority = Column(Integer, default=0)
    model_metadata = Column("metadata", JSON, default=dict)
