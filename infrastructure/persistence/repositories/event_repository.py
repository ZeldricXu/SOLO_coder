from typing import List, Optional
from sqlalchemy.orm import Session
from datetime import datetime, timedelta

from domain.models.event import DomainEvent, EventType
from infrastructure.persistence.models.event_model import EventModel


class EventRepository:
    def __init__(self, db: Session):
        self.db = db

    def save(self, event: DomainEvent) -> DomainEvent:
        col_key_map = {c.name: c.key for c in EventModel.__table__.columns}
        db_obj = EventModel(**{col_key_map.get(k, k): v for k, v in event.model_dump().items()})
        self.db.add(db_obj)
        self.db.commit()
        return event

    def get_by_id(self, event_id: str) -> Optional[DomainEvent]:
        db_obj = self.db.query(EventModel).filter(EventModel.event_id == event_id).first()
        return DomainEvent(**{c.name: getattr(db_obj, c.key) for c in db_obj.__table__.columns}) if db_obj else None

    def get_by_device(self, device_id: str, limit: int = 100) -> List[DomainEvent]:
        db_objs = self.db.query(EventModel).filter(EventModel.device_id == device_id).order_by(EventModel.timestamp.desc()).limit(limit).all()
        return [DomainEvent(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]

    def get_by_type(self, event_type: EventType, limit: int = 100) -> List[DomainEvent]:
        db_objs = self.db.query(EventModel).filter(EventModel.event_type == event_type.value).order_by(EventModel.timestamp.desc()).limit(limit).all()
        return [DomainEvent(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]

    def get_recent(self, hours: int = 24, limit: int = 1000) -> List[DomainEvent]:
        since = datetime.utcnow() - timedelta(hours=hours)
        db_objs = self.db.query(EventModel).filter(EventModel.timestamp >= since).order_by(EventModel.timestamp.desc()).limit(limit).all()
        return [DomainEvent(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]
