from enum import Enum
from datetime import datetime
from typing import Dict, Optional, Any
from pydantic import BaseModel, Field


class DeviceStatus(str, Enum):
    UNREGISTERED = "unregistered"
    REGISTERING = "registering"
    ACTIVE = "active"
    OFFLINE = "offline"
    MAINTENANCE = "maintenance"
    DISABLED = "disabled"
    DELETED = "deleted"


class DeviceProtocol(str, Enum):
    MQTT = "mqtt"
    MODBUS = "modbus"
    OPCUA = "opcua"
    HTTP = "http"
    COAP = "coap"
    BACNET = "bacnet"
    PROFIBUS = "profibus"
    CUSTOM = "custom"


class Device(BaseModel):
    device_id: str
    device_name: str
    device_type: str
    protocol: DeviceProtocol
    status: DeviceStatus = DeviceStatus.UNREGISTERED

    manufacturer: Optional[str] = None
    model: Optional[str] = None
    firmware_version: Optional[str] = None
    hardware_version: Optional[str] = None

    protocol_config: Dict[str, Any] = Field(default_factory=dict)
    metadata: Dict[str, Any] = Field(default_factory=dict)
    tags: list[str] = Field(default_factory=list)

    last_seen: Optional[datetime] = None
    last_ip: Optional[str] = None

    registered_at: Optional[datetime] = None
    activated_at: Optional[datetime] = None
    deactivated_at: Optional[datetime] = None

    auth_token: Optional[str] = None
    certificate: Optional[str] = None

    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    def is_active(self) -> bool:
        return self.status == DeviceStatus.ACTIVE

    def is_online(self) -> bool:
        return self.status in [DeviceStatus.ACTIVE]

    def mark_online(self, ip: Optional[str] = None) -> None:
        self.status = DeviceStatus.ACTIVE
        self.last_seen = datetime.utcnow()
        if ip:
            self.last_ip = ip
        self.updated_at = datetime.utcnow()

    def mark_offline(self) -> None:
        self.status = DeviceStatus.OFFLINE
        self.updated_at = datetime.utcnow()

    def activate(self) -> None:
        self.status = DeviceStatus.ACTIVE
        self.activated_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def deactivate(self) -> None:
        self.status = DeviceStatus.DISABLED
        self.deactivated_at = datetime.utcnow()
        self.updated_at = datetime.utcnow()
