from typing import Optional, List
from sqlalchemy.orm import Session

from domain.models.device import Device, DeviceStatus
from infrastructure.persistence.models.device_model import DeviceModel
from infrastructure.persistence.repositories.base_repository import BaseRepository


class DeviceRepository(BaseRepository[DeviceModel, Device]):
    def __init__(self, db: Session):
        super().__init__(db, DeviceModel, Device)

    def get_by_device_id(self, device_id: str) -> Optional[Device]:
        db_obj = self.db.query(DeviceModel).filter(DeviceModel.device_id == device_id).first()
        return self._to_domain(db_obj) if db_obj else None

    def get_by_status(self, status: DeviceStatus) -> List[Device]:
        db_objs = self.db.query(DeviceModel).filter(DeviceModel.status == status.value).all()
        return [self._to_domain(obj) for obj in db_objs]

    def get_by_protocol(self, protocol: str) -> List[Device]:
        db_objs = self.db.query(DeviceModel).filter(DeviceModel.protocol == protocol).all()
        return [self._to_domain(obj) for obj in db_objs]

    def get_online_devices(self) -> List[Device]:
        db_objs = self.db.query(DeviceModel).filter(DeviceModel.status == DeviceStatus.ACTIVE.value).all()
        return [self._to_domain(obj) for obj in db_objs]

    def create_device(self, device: Device) -> Device:
        db_obj = DeviceModel(**self._to_db(device))
        self.db.add(db_obj)
        self.db.commit()
        self.db.refresh(db_obj)
        return self._to_domain(db_obj)

    def update_device(self, device_id: str, update_data: dict) -> Optional[Device]:
        db_obj = self.db.query(DeviceModel).filter(DeviceModel.device_id == device_id).first()
        if db_obj:
            col_key_map = {c.name: c.key for c in DeviceModel.__table__.columns}
            for key, value in update_data.items():
                setattr(db_obj, col_key_map.get(key, key), value)
            self.db.commit()
            self.db.refresh(db_obj)
            return self._to_domain(db_obj)
        return None

    def delete_device(self, device_id: str) -> bool:
        db_obj = self.db.query(DeviceModel).filter(DeviceModel.device_id == device_id).first()
        if db_obj:
            self.db.delete(db_obj)
            self.db.commit()
            return True
        return False

    def exists(self, device_id: str) -> bool:
        return self.db.query(DeviceModel).filter(DeviceModel.device_id == device_id).first() is not None
