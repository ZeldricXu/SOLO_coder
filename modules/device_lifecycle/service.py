from typing import Optional, List, Dict, Any
from datetime import datetime, timedelta
import uuid
import hashlib
import secrets

from domain.models.device import Device, DeviceStatus, DeviceProtocol
from domain.models.device_shadow import DeviceShadow
from domain.models.event import EventType

from infrastructure.persistence.repositories.device_repository import DeviceRepository
from infrastructure.persistence.repositories.shadow_repository import DeviceShadowRepository
from infrastructure.messaging.event_bus import EventBus, get_event_bus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class DeviceLifecycleService:
    def __init__(
        self,
        device_repo: DeviceRepository,
        shadow_repo: DeviceShadowRepository,
        event_bus: Optional[EventBus] = None,
    ):
        self.device_repo = device_repo
        self.shadow_repo = shadow_repo
        self.event_bus = event_bus or get_event_bus()

    def register_device(
        self,
        device_id: str,
        device_name: str,
        device_type: str,
        protocol: DeviceProtocol,
        protocol_config: Optional[Dict[str, Any]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        manufacturer: Optional[str] = None,
        model: Optional[str] = None,
    ) -> Device:
        if self.device_repo.exists(device_id):
            raise ValueError(f"Device {device_id} already exists")

        auth_token = self._generate_auth_token()
        device = Device(
            device_id=device_id,
            device_name=device_name,
            device_type=device_type,
            protocol=protocol,
            protocol_config=protocol_config or {},
            metadata=metadata or {},
            manufacturer=manufacturer,
            model=model,
            status=DeviceStatus.REGISTERING,
            registered_at=datetime.utcnow(),
            auth_token=auth_token,
        )

        self.device_repo.create_device(device)

        shadow = DeviceShadow(device_id=device_id)
        self.shadow_repo.create(shadow)

        event = self.event_bus.create_event(
            event_type=EventType.DEVICE_REGISTERED,
            device_id=device_id,
            data=device.model_dump(),
        )
        self.event_bus.publish(event)

        logger.info(f"Device {device_id} registered successfully")
        return device

    def activate_device(self, device_id: str) -> Optional[Device]:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            logger.error(f"Device {device_id} not found")
            return None

        device.activate()
        self.device_repo.update_device(device_id, device.model_dump())

        event = self.event_bus.create_event(
            event_type=EventType.DEVICE_ACTIVATED,
            device_id=device_id,
        )
        self.event_bus.publish(event)

        logger.info(f"Device {device_id} activated")
        return device

    def deactivate_device(self, device_id: str, reason: Optional[str] = None) -> Optional[Device]:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            logger.error(f"Device {device_id} not found")
            return None

        device.deactivate()
        self.device_repo.update_device(device_id, device.model_dump())

        event = self.event_bus.create_event(
            event_type=EventType.DEVICE_DEACTIVATED,
            device_id=device_id,
            data={"reason": reason},
        )
        self.event_bus.publish(event)

        logger.info(f"Device {device_id} deactivated")
        return device

    def mark_device_online(self, device_id: str, ip: Optional[str] = None) -> Optional[Device]:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            logger.error(f"Device {device_id} not found")
            return None

        device.mark_online(ip)
        self.device_repo.update_device(device_id, device.model_dump())

        event = self.event_bus.create_event(
            event_type=EventType.DEVICE_ONLINE,
            device_id=device_id,
            data={"ip": ip},
        )
        self.event_bus.publish(event)

        logger.info(f"Device {device_id} marked online")
        return device

    def mark_device_offline(self, device_id: str) -> Optional[Device]:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            logger.error(f"Device {device_id} not found")
            return None

        device.mark_offline()
        self.device_repo.update_device(device_id, device.model_dump())

        event = self.event_bus.create_event(
            event_type=EventType.DEVICE_OFFLINE,
            device_id=device_id,
        )
        self.event_bus.publish(event)

        logger.info(f"Device {device_id} marked offline")
        return device

    def update_device(self, device_id: str, update_data: Dict[str, Any]) -> Optional[Device]:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            logger.error(f"Device {device_id} not found")
            return None

        update_data["updated_at"] = datetime.utcnow()
        return self.device_repo.update_device(device_id, update_data)

    def delete_device(self, device_id: str) -> bool:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            logger.error(f"Device {device_id} not found")
            return False

        self.shadow_repo.delete(device_id)
        success = self.device_repo.delete_device(device_id)

        if success:
            event = self.event_bus.create_event(
                event_type=EventType.DEVICE_DELETED,
                device_id=device_id,
            )
            self.event_bus.publish(event)
            logger.info(f"Device {device_id} deleted")

        return success

    def get_device(self, device_id: str) -> Optional[Device]:
        return self.device_repo.get_by_device_id(device_id)

    def list_devices(
        self,
        status: Optional[DeviceStatus] = None,
        protocol: Optional[DeviceProtocol] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> List[Device]:
        devices = self.device_repo.get_all(skip=skip, limit=limit)

        if status:
            devices = [d for d in devices if d.status == status]
        if protocol:
            devices = [d for d in devices if d.protocol == protocol]

        return devices

    def authenticate_device(self, device_id: str, token: str) -> bool:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            return False

        if device.status != DeviceStatus.ACTIVE:
            return False

        return device.auth_token == token

    def rotate_auth_token(self, device_id: str) -> Optional[str]:
        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            return None

        new_token = self._generate_auth_token()
        self.device_repo.update_device(device_id, {"auth_token": new_token})
        logger.info(f"Auth token rotated for device {device_id}")
        return new_token

    def check_and_update_offline_devices(self, timeout_seconds: int = 300) -> List[str]:
        devices = self.device_repo.get_by_status(DeviceStatus.ACTIVE)
        offline_devices = []
        cutoff_time = datetime.utcnow() - timedelta(seconds=timeout_seconds)

        for device in devices:
            if device.last_seen and device.last_seen < cutoff_time:
                self.mark_device_offline(device.device_id)
                offline_devices.append(device.device_id)

        if offline_devices:
            logger.info(f"Marked {len(offline_devices)} devices as offline")

        return offline_devices

    def get_device_stats(self) -> Dict[str, Any]:
        all_devices = self.device_repo.get_all(limit=10000)
        status_counts = {}
        protocol_counts = {}

        for device in all_devices:
            status = device.status.value
            status_counts[status] = status_counts.get(status, 0) + 1

            protocol = device.protocol.value
            protocol_counts[protocol] = protocol_counts.get(protocol, 0) + 1

        return {
            "total": len(all_devices),
            "by_status": status_counts,
            "by_protocol": protocol_counts,
        }

    def _generate_auth_token(self) -> str:
        return secrets.token_urlsafe(32)

    def batch_register(
        self,
        devices_data: List[Dict[str, Any]],
    ) -> List[Device]:
        registered_devices = []
        for device_data in devices_data:
            try:
                device = self.register_device(**device_data)
                registered_devices.append(device)
            except Exception as e:
                logger.error(f"Failed to register device {device_data.get('device_id')}: {str(e)}")
        return registered_devices
