from enum import Enum
from datetime import datetime
from typing import Dict, Any, Optional
from pydantic import BaseModel, Field


class ShadowState(str, Enum):
    SYNCED = "synced"
    SYNCING = "syncing"
    OUT_OF_SYNC = "out_of_sync"
    ERROR = "error"


class DeviceShadow(BaseModel):
    device_id: str
    version: int = 1

    desired: Dict[str, Any] = Field(default_factory=dict)
    reported: Dict[str, Any] = Field(default_factory=dict)
    delta: Dict[str, Any] = Field(default_factory=dict)

    state: ShadowState = ShadowState.SYNCED
    last_sync_time: Optional[datetime] = None
    last_cloud_sync_time: Optional[datetime] = None

    metadata: Dict[str, Any] = Field(default_factory=dict)
    error_message: Optional[str] = None

    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    def update_reported(self, reported_data: Dict[str, Any]) -> None:
        self.reported.update(reported_data)
        self._compute_delta()
        self.updated_at = datetime.utcnow()

    def update_desired(self, desired_data: Dict[str, Any]) -> None:
        self.desired.update(desired_data)
        self._compute_delta()
        self.state = ShadowState.OUT_OF_SYNC
        self.updated_at = datetime.utcnow()

    def _compute_delta(self) -> None:
        delta = {}
        for key, value in self.desired.items():
            if key not in self.reported or self.reported[key] != value:
                delta[key] = value
        self.delta = delta

    def mark_synced(self) -> None:
        self.state = ShadowState.SYNCED
        self.delta = {}
        self.last_sync_time = datetime.utcnow()
        self.updated_at = datetime.utcnow()

    def mark_syncing(self) -> None:
        self.state = ShadowState.SYNCING
        self.updated_at = datetime.utcnow()

    def mark_error(self, error_message: str) -> None:
        self.state = ShadowState.ERROR
        self.error_message = error_message
        self.updated_at = datetime.utcnow()

    def has_changes(self) -> bool:
        return len(self.delta) > 0

    def get_full_state(self) -> Dict[str, Any]:
        return {
            "device_id": self.device_id,
            "version": self.version,
            "desired": self.desired,
            "reported": self.reported,
            "delta": self.delta,
            "state": self.state,
            "last_sync_time": self.last_sync_time,
        }
