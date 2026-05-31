from typing import List, Optional
from sqlalchemy.orm import Session

from domain.models.ota import OTAPackage, UpgradeTask, UpgradeStatus
from infrastructure.persistence.models.ota_model import OTAPackageModel, UpgradeTaskModel


class OTARepository:
    def __init__(self, db: Session):
        self.db = db

    def save_package(self, package: OTAPackage) -> OTAPackage:
        col_key_map = {c.name: c.key for c in OTAPackageModel.__table__.columns}
        db_obj = OTAPackageModel(**{col_key_map.get(k, k): v for k, v in package.model_dump().items()})
        self.db.add(db_obj)
        self.db.commit()
        return package

    def get_package(self, package_id: str) -> Optional[OTAPackage]:
        db_obj = self.db.query(OTAPackageModel).filter(OTAPackageModel.package_id == package_id).first()
        return OTAPackage(**{c.name: getattr(db_obj, c.key) for c in db_obj.__table__.columns}) if db_obj else None

    def get_all_packages(self) -> List[OTAPackage]:
        db_objs = self.db.query(OTAPackageModel).all()
        return [OTAPackage(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]

    def delete_package(self, package_id: str) -> bool:
        db_obj = self.db.query(OTAPackageModel).filter(OTAPackageModel.package_id == package_id).first()
        if db_obj:
            self.db.delete(db_obj)
            self.db.commit()
            return True
        return False

    def save_upgrade_task(self, task: UpgradeTask) -> UpgradeTask:
        col_key_map = {c.name: c.key for c in UpgradeTaskModel.__table__.columns}
        db_obj = UpgradeTaskModel(**{col_key_map.get(k, k): v for k, v in task.model_dump().items()})
        self.db.add(db_obj)
        self.db.commit()
        return task

    def update_upgrade_task(self, task_id: str, update_data: dict) -> Optional[UpgradeTask]:
        db_obj = self.db.query(UpgradeTaskModel).filter(UpgradeTaskModel.task_id == task_id).first()
        if db_obj:
            col_key_map = {c.name: c.key for c in UpgradeTaskModel.__table__.columns}
            for key, value in update_data.items():
                setattr(db_obj, col_key_map.get(key, key), value)
            self.db.commit()
            return UpgradeTask(**{c.name: getattr(db_obj, c.key) for c in db_obj.__table__.columns})
        return None

    def get_upgrade_task(self, task_id: str) -> Optional[UpgradeTask]:
        db_obj = self.db.query(UpgradeTaskModel).filter(UpgradeTaskModel.task_id == task_id).first()
        return UpgradeTask(**{c.name: getattr(db_obj, c.key) for c in db_obj.__table__.columns}) if db_obj else None

    def get_tasks_by_device(self, device_id: str) -> List[UpgradeTask]:
        db_objs = self.db.query(UpgradeTaskModel).filter(UpgradeTaskModel.device_id == device_id).order_by(UpgradeTaskModel.created_at.desc()).all()
        return [UpgradeTask(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]

    def get_pending_tasks(self) -> List[UpgradeTask]:
        db_objs = self.db.query(UpgradeTaskModel).filter(
            UpgradeTaskModel.status.in_([UpgradeStatus.PENDING.value, UpgradeStatus.DOWNLOADING.value])
        ).all()
        return [UpgradeTask(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]

    def get_tasks_by_batch(self, batch_id: str) -> List[UpgradeTask]:
        db_objs = self.db.query(UpgradeTaskModel).filter(UpgradeTaskModel.batch_id == batch_id).all()
        return [UpgradeTask(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]
