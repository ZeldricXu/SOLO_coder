from enum import Enum
from datetime import datetime
from typing import Dict, Any, Optional
from pydantic import BaseModel, Field


class EventType(str, Enum):
    DEVICE_REGISTERED = "device_registered"
    DEVICE_ACTIVATED = "device_activated"
    DEVICE_DEACTIVATED = "device_deactivated"
    DEVICE_ONLINE = "device_online"
    DEVICE_OFFLINE = "device_offline"
    DEVICE_DELETED = "device_deleted"

    TELEMETRY_RECEIVED = "telemetry_received"
    TELEMETRY_AGGREGATED = "telemetry_aggregated"

    SHADOW_DESIRED_UPDATED = "shadow_desired_updated"
    SHADOW_REPORTED_UPDATED = "shadow_reported_updated"
    SHADOW_SYNCED = "shadow_synced"
    SHADOW_SYNC_FAILED = "shadow_sync_failed"

    RULE_TRIGGERED = "rule_triggered"
    RULE_ACTION_EXECUTED = "rule_action_executed"
    RULE_ACTION_FAILED = "rule_action_failed"

    INFERENCE_TASK_CREATED = "inference_task_created"
    INFERENCE_COMPLETED = "inference_completed"
    INFERENCE_FAILED = "inference_failed"

    OTA_PACKAGE_CREATED = "ota_package_created"
    OTA_UPGRADE_STARTED = "ota_upgrade_started"
    OTA_UPGRADE_COMPLETED = "ota_upgrade_completed"
    OTA_UPGRADE_FAILED = "ota_upgrade_failed"
    OTA_ROLLBACK_STARTED = "ota_rollback_started"
    OTA_ROLLBACK_COMPLETED = "ota_rollback_completed"

    OFFLINE_DATA_STORED = "offline_data_stored"
    OFFLINE_DATA_SYNCED = "offline_data_synced"
    OFFLINE_DATA_SYNC_FAILED = "offline_data_sync_failed"

    CUSTOM = "custom"


class DomainEvent(BaseModel):
    event_id: str
    event_type: EventType
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    source: str = "edge-node"

    device_id: Optional[str] = None
    data: Dict[str, Any] = Field(default_factory=dict)

    correlation_id: Optional[str] = None
    causation_id: Optional[str] = None

    metadata: Dict[str, Any] = Field(default_factory=dict)

    def get_data_value(self, key: str, default: Any = None) -> Any:
        return self.data.get(key, default)

    def to_dict(self) -> Dict[str, Any]:
        return self.model_dump()
