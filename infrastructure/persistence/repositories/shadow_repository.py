from typing import Optional
from sqlalchemy.orm import Session

from domain.models.device_shadow import DeviceShadow
from infrastructure.persistence.models.shadow_model import DeviceShadowModel


class DeviceShadowRepository:
    def __init__(self, db: Session):
        self.db = db

    def _to_domain(self, db_obj: DeviceShadowModel) -> DeviceShadow:
        return DeviceShadow(
            device_id=db_obj.device_id,
            version=db_obj.version,
            desired=db_obj.desired,
            reported=db_obj.reported,
            delta=db_obj.delta,
            state=db_obj.state,
            last_sync_time=db_obj.last_sync_time,
            last_cloud_sync_time=db_obj.last_cloud_sync_time,
            metadata=db_obj.shadow_metadata,
            error_message=db_obj.error_message,
            created_at=db_obj.created_at,
            updated_at=db_obj.updated_at,
        )

    def get_by_device_id(self, device_id: str) -> Optional[DeviceShadow]:
        db_obj = self.db.query(DeviceShadowModel).filter(DeviceShadowModel.device_id == device_id).first()
        return self._to_domain(db_obj) if db_obj else None

    def create(self, shadow: DeviceShadow) -> DeviceShadow:
        db_obj = DeviceShadowModel(
            device_id=shadow.device_id,
            version=shadow.version,
            desired=shadow.desired,
            reported=shadow.reported,
            delta=shadow.delta,
            state=shadow.state,
            last_sync_time=shadow.last_sync_time,
            last_cloud_sync_time=shadow.last_cloud_sync_time,
            shadow_metadata=shadow.metadata,
            error_message=shadow.error_message,
        )
        self.db.add(db_obj)
        self.db.commit()
        self.db.refresh(db_obj)
        return self._to_domain(db_obj)

    def update(self, shadow: DeviceShadow) -> Optional[DeviceShadow]:
        db_obj = self.db.query(DeviceShadowModel).filter(DeviceShadowModel.device_id == shadow.device_id).first()
        if db_obj:
            db_obj.version = shadow.version
            db_obj.desired = shadow.desired
            db_obj.reported = shadow.reported
            db_obj.delta = shadow.delta
            db_obj.state = shadow.state
            db_obj.last_sync_time = shadow.last_sync_time
            db_obj.last_cloud_sync_time = shadow.last_cloud_sync_time
            db_obj.shadow_metadata = shadow.metadata
            db_obj.error_message = shadow.error_message
            db_obj.updated_at = shadow.updated_at
            self.db.commit()
            self.db.refresh(db_obj)
            return self._to_domain(db_obj)
        return None

    def upsert(self, shadow: DeviceShadow) -> DeviceShadow:
        existing = self.get_by_device_id(shadow.device_id)
        if existing:
            return self.update(shadow)
        return self.create(shadow)

    def delete(self, device_id: str) -> bool:
        db_obj = self.db.query(DeviceShadowModel).filter(DeviceShadowModel.device_id == device_id).first()
        if db_obj:
            self.db.delete(db_obj)
            self.db.commit()
            return True
        return False
