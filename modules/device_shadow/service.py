from typing import Optional, Dict, Any, List
from datetime import datetime

from domain.models.device_shadow import DeviceShadow, ShadowState
from domain.models.event import EventType

from infrastructure.persistence.repositories.shadow_repository import DeviceShadowRepository
from infrastructure.messaging.event_bus import EventBus, get_event_bus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class DeviceShadowService:
    def __init__(
        self,
        shadow_repo: DeviceShadowRepository,
        event_bus: Optional[EventBus] = None,
    ):
        self.shadow_repo = shadow_repo
        self.event_bus = event_bus or get_event_bus()

    def get_shadow(self, device_id: str) -> Optional[DeviceShadow]:
        return self.shadow_repo.get_by_device_id(device_id)

    def get_or_create_shadow(self, device_id: str) -> DeviceShadow:
        shadow = self.shadow_repo.get_by_device_id(device_id)
        if not shadow:
            shadow = DeviceShadow(device_id=device_id)
            self.shadow_repo.create(shadow)
        return shadow

    def update_reported_state(self, device_id: str, reported: Dict[str, Any]) -> DeviceShadow:
        shadow = self.get_or_create_shadow(device_id)
        shadow.update_reported(reported)
        shadow.version += 1
        self.shadow_repo.upsert(shadow)

        event = self.event_bus.create_event(
            event_type=EventType.SHADOW_REPORTED_UPDATED,
            device_id=device_id,
            data={"reported": reported, "version": shadow.version},
        )
        self.event_bus.publish(event)

        logger.debug(f"Updated reported state for device {device_id}")
        return shadow

    def update_desired_state(self, device_id: str, desired: Dict[str, Any]) -> DeviceShadow:
        shadow = self.get_or_create_shadow(device_id)
        shadow.update_desired(desired)
        shadow.version += 1
        self.shadow_repo.upsert(shadow)

        event = self.event_bus.create_event(
            event_type=EventType.SHADOW_DESIRED_UPDATED,
            device_id=device_id,
            data={"desired": desired, "version": shadow.version},
        )
        self.event_bus.publish(event)

        logger.info(f"Updated desired state for device {device_id}")
        return shadow

    def merge_reported_state(self, device_id: str, reported: Dict[str, Any]) -> DeviceShadow:
        shadow = self.get_or_create_shadow(device_id)
        shadow.reported.update(reported)
        shadow._compute_delta()
        shadow.version += 1
        shadow.updated_at = datetime.utcnow()
        self.shadow_repo.upsert(shadow)
        return shadow

    def merge_desired_state(self, device_id: str, desired: Dict[str, Any]) -> DeviceShadow:
        shadow = self.get_or_create_shadow(device_id)
        shadow.desired.update(desired)
        shadow._compute_delta()
        shadow.state = ShadowState.OUT_OF_SYNC
        shadow.version += 1
        shadow.updated_at = datetime.utcnow()
        self.shadow_repo.upsert(shadow)

        event = self.event_bus.create_event(
            event_type=EventType.SHADOW_DESIRED_UPDATED,
            device_id=device_id,
            data={"desired": desired, "version": shadow.version},
        )
        self.event_bus.publish(event)

        return shadow

    def mark_synced(self, device_id: str) -> Optional[DeviceShadow]:
        shadow = self.shadow_repo.get_by_device_id(device_id)
        if shadow:
            shadow.mark_synced()
            self.shadow_repo.upsert(shadow)

            event = self.event_bus.create_event(
                event_type=EventType.SHADOW_SYNCED,
                device_id=device_id,
                data={"version": shadow.version},
            )
            self.event_bus.publish(event)

            logger.info(f"Device {device_id} shadow marked as synced")
        return shadow

    def mark_syncing(self, device_id: str) -> Optional[DeviceShadow]:
        shadow = self.shadow_repo.get_by_device_id(device_id)
        if shadow:
            shadow.mark_syncing()
            self.shadow_repo.upsert(shadow)
        return shadow

    def mark_error(self, device_id: str, error_message: str) -> Optional[DeviceShadow]:
        shadow = self.shadow_repo.get_by_device_id(device_id)
        if shadow:
            shadow.mark_error(error_message)
            self.shadow_repo.upsert(shadow)

            event = self.event_bus.create_event(
                event_type=EventType.SHADOW_SYNC_FAILED,
                device_id=device_id,
                data={"error": error_message},
            )
            self.event_bus.publish(event)
        return shadow

    def get_delta(self, device_id: str) -> Dict[str, Any]:
        shadow = self.get_shadow(device_id)
        if shadow:
            return shadow.delta
        return {}

    def has_changes(self, device_id: str) -> bool:
        shadow = self.get_shadow(device_id)
        return shadow is not None and shadow.has_changes()

    def get_full_state(self, device_id: str) -> Optional[Dict[str, Any]]:
        shadow = self.get_shadow(device_id)
        if shadow:
            return shadow.get_full_state()
        return None

    def delete_shadow(self, device_id: str) -> bool:
        return self.shadow_repo.delete(device_id)

    def get_shadows_by_state(self, state: ShadowState) -> List[DeviceShadow]:
        from infrastructure.persistence.database import SessionLocal
        from infrastructure.persistence.models.shadow_model import DeviceShadowModel

        db = SessionLocal()
        try:
            db_objs = db.query(DeviceShadowModel).filter(DeviceShadowModel.state == state.value).all()
            return [
                DeviceShadow(
                    device_id=obj.device_id,
                    version=obj.version,
                    desired=obj.desired,
                    reported=obj.reported,
                    delta=obj.delta,
                    state=obj.state,
                    last_sync_time=obj.last_sync_time,
                    last_cloud_sync_time=obj.last_cloud_sync_time,
                    metadata=obj.shadow_metadata,
                    error_message=obj.error_message,
                    created_at=obj.created_at,
                    updated_at=obj.updated_at,
                )
                for obj in db_objs
            ]
        finally:
            db.close()

    def get_out_of_sync_shadows(self) -> List[DeviceShadow]:
        return self.get_shadows_by_state(ShadowState.OUT_OF_SYNC)

    def sync_cloud_desired(self, device_id: str, cloud_desired: Dict[str, Any]) -> DeviceShadow:
        shadow = self.get_or_create_shadow(device_id)
        shadow.desired = cloud_desired
        shadow._compute_delta()
        shadow.state = ShadowState.OUT_OF_SYNC
        shadow.last_cloud_sync_time = datetime.utcnow()
        shadow.version += 1
        shadow.updated_at = datetime.utcnow()
        self.shadow_repo.upsert(shadow)
        return shadow

    def update_cloud_sync_time(self, device_id: str) -> Optional[DeviceShadow]:
        shadow = self.shadow_repo.get_by_device_id(device_id)
        if shadow:
            shadow.last_cloud_sync_time = datetime.utcnow()
            self.shadow_repo.upsert(shadow)
        return shadow

    def reset_shadow(self, device_id: str) -> DeviceShadow:
        self.shadow_repo.delete(device_id)
        shadow = DeviceShadow(device_id=device_id)
        self.shadow_repo.create(shadow)
        logger.info(f"Reset shadow for device {device_id}")
        return shadow

    def get_shadow_stats(self) -> Dict[str, Any]:
        from infrastructure.persistence.database import SessionLocal
        from infrastructure.persistence.models.shadow_model import DeviceShadowModel
        from sqlalchemy import func

        db = SessionLocal()
        try:
            total = db.query(func.count(DeviceShadowModel.id)).scalar()
            synced = db.query(func.count(DeviceShadowModel.id)).filter(
                DeviceShadowModel.state == ShadowState.SYNCED.value
            ).scalar()
            out_of_sync = db.query(func.count(DeviceShadowModel.id)).filter(
                DeviceShadowModel.state == ShadowState.OUT_OF_SYNC.value
            ).scalar()
            error = db.query(func.count(DeviceShadowModel.id)).filter(
                DeviceShadowModel.state == ShadowState.ERROR.value
            ).scalar()

            return {
                "total": total,
                "synced": synced,
                "out_of_sync": out_of_sync,
                "error": error,
            }
        finally:
            db.close()
