from typing import Optional, Dict, Any, List
from datetime import datetime

from domain.models.device import Device, DeviceStatus

from modules.device_lifecycle.service import DeviceLifecycleService
from modules.device_shadow.service import DeviceShadowService
from modules.protocol_adapter.service import ProtocolAdapterService
from modules.rule_engine.service import RuleEngineService
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class DeviceService:
    def __init__(
        self,
        lifecycle_service: DeviceLifecycleService,
        shadow_service: DeviceShadowService,
        protocol_adapter: ProtocolAdapterService,
        rule_engine: RuleEngineService,
    ):
        self.lifecycle_service = lifecycle_service
        self.shadow_service = shadow_service
        self.protocol_adapter = protocol_adapter
        self.rule_engine = rule_engine

    def register_device(
        self,
        device_id: str,
        name: str,
        device_type: str,
        protocol: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Device:
        device = self.lifecycle_service.register_device(
            device_id=device_id,
            name=name,
            device_type=device_type,
            protocol=protocol,
            metadata=metadata,
        )
        self.shadow_service.initialize_shadow(device_id)
        return device

    def activate_device(self, device_id: str, credentials: Dict[str, Any]) -> bool:
        device = self.lifecycle_service.activate_device(device_id, credentials)
        if device:
            self.shadow_service.mark_synced(device_id)
            return True
        return False

    def deactivate_device(self, device_id: str) -> bool:
        result = self.lifecycle_service.deactivate_device(device_id)
        if result:
            self.protocol_adapter.disconnect_device(device_id)
        return result

    def get_device(self, device_id: str) -> Optional[Device]:
        return self.lifecycle_service.get_device(device_id)

    def list_devices(
        self,
        status: Optional[str] = None,
        device_type: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[Device]:
        return self.lifecycle_service.list_devices(
            status=status,
            device_type=device_type,
            limit=limit,
            offset=offset,
        )

    def update_device_metadata(self, device_id: str, metadata: Dict[str, Any]) -> bool:
        return self.lifecycle_service.update_device_metadata(device_id, metadata)

    def mark_device_online(self, device_id: str, connection_info: Optional[Dict[str, Any]] = None) -> bool:
        return self.lifecycle_service.mark_device_online(device_id, connection_info)

    def mark_device_offline(self, device_id: str) -> bool:
        return self.lifecycle_service.mark_device_offline(device_id)

    def read_device_data(self, device_id: str, points: List[str]) -> Dict[str, Any]:
        return self.protocol_adapter.read_device_data(device_id, points)

    def write_device_data(self, device_id: str, data: Dict[str, Any]) -> bool:
        return self.protocol_adapter.write_device_data(device_id, data)

    def send_device_command(self, device_id: str, command: str, params: Dict[str, Any]) -> Dict[str, Any]:
        return self.protocol_adapter.send_command(device_id, command, params)

    def get_device_shadow(self, device_id: str) -> Dict[str, Any]:
        shadow = self.shadow_service.get_shadow(device_id)
        if shadow:
            return {
                "device_id": shadow.device_id,
                "reported_state": shadow.reported_state,
                "desired_state": shadow.desired_state,
                "state_version": shadow.state_version,
                "last_sync_time": shadow.last_sync_time.isoformat() if shadow.last_sync_time else None,
            }
        return {}

    def update_device_desired_state(self, device_id: str, desired_state: Dict[str, Any]) -> Dict[str, Any]:
        self.shadow_service.update_desired_state(device_id, desired_state)
        delta = self.shadow_service.get_delta(device_id)
        return delta

    def process_device_telemetry(self, device_id: str, telemetry_data: Dict[str, Any]) -> None:
        self.shadow_service.update_reported_state(device_id, telemetry_data)
        self.rule_engine.evaluate_telemetry_data(device_id, telemetry_data)

    def get_device_rules(self, device_id: str) -> List[Dict[str, Any]]:
        return self.rule_engine.list_rules(device_id=device_id)

    def delete_device(self, device_id: str) -> bool:
        self.protocol_adapter.disconnect_device(device_id)
        return self.lifecycle_service.delete_device(device_id)
