from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class DeviceCreate(BaseModel):
    device_id: str
    name: str
    description: Optional[str] = None
    device_model: str
    manufacturer: Optional[str] = None
    serial_number: Optional[str] = None
    firmware_version: Optional[str] = None
    hardware_version: Optional[str] = None
    ip_address: Optional[str] = None
    mac_address: Optional[str] = None
    location: Dict[str, Any] = Field(default_factory=dict)
    tags: List[str] = Field(default_factory=list)
    config: Dict[str, Any] = Field(default_factory=dict)
    capabilities: Dict[str, Any] = Field(default_factory=dict)
    heartbeat_interval: int = 60
    heartbeat_timeout: int = 300
    is_gateway: bool = False
    parent_device_id: Optional[str] = None
    labels: Dict[str, Any] = Field(default_factory=dict)


class DeviceUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    firmware_version: Optional[str] = None
    status: Optional[str] = None
    ip_address: Optional[str] = None
    location: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    config: Optional[Dict[str, Any]] = None
    capabilities: Optional[Dict[str, Any]] = None
    heartbeat_interval: Optional[int] = None
    heartbeat_timeout: Optional[int] = None


class DeviceResponse(BaseModel):
    id: str
    device_id: str
    name: str
    description: Optional[str]
    device_model: str
    manufacturer: Optional[str]
    serial_number: Optional[str]
    firmware_version: Optional[str]
    hardware_version: Optional[str]
    status: str
    activation_status: str
    activated_at: Optional[datetime]
    last_seen_at: Optional[datetime]
    ip_address: Optional[str]
    mac_address: Optional[str]
    location: Dict[str, Any]
    tags: List[str]
    config: Dict[str, Any]
    capabilities: Dict[str, Any]
    heartbeat_interval: int
    heartbeat_timeout: int
    is_gateway: bool
    parent_device_id: Optional[str]
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class DeviceActivateRequest(BaseModel):
    device_id: str
    activation_code: Optional[str] = None
    firmware_version: Optional[str] = None
    hardware_version: Optional[str] = None


class DeviceHeartbeatRequest(BaseModel):
    device_id: str
    status: Optional[str] = None
    cpu_usage: Optional[float] = None
    memory_usage: Optional[float] = None
    disk_usage: Optional[float] = None
    network_usage: Dict[str, Any] = Field(default_factory=dict)
    metrics: Dict[str, Any] = Field(default_factory=dict)


class DeviceAuthResponse(BaseModel):
    device_id: str
    auth_type: str
    api_key: Optional[str]
    token: Optional[str]
    token_expires_at: Optional[datetime]
    last_authenticated_at: Optional[datetime]

    class Config:
        from_attributes = True
