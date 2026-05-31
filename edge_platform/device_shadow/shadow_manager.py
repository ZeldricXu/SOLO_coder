import asyncio
import logging
import json
import uuid
from typing import Dict, List, Optional, Any
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
import copy

from ..common.event_bus import EventBus, Event, event_bus
from ..common.config import config
from ..common.exceptions import ShadowSyncException, DeviceShadowException

logger = logging.getLogger(__name__)


class ShadowSyncStatus(str, Enum):
    IN_SYNC = "in_sync"
    SYNCING = "syncing"
    OUT_OF_SYNC = "out_of_sync"
    ERROR = "error"


class ShadowState(str, Enum):
    ONLINE = "online"
    OFFLINE = "offline"
    UNKNOWN = "unknown"


@dataclass
class DeviceShadow:
    device_id: str = ""
    shadow_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    desired: Dict[str, Any] = field(default_factory=dict)
    reported: Dict[str, Any] = field(default_factory=dict)
    delta: Dict[str, Any] = field(default_factory=dict)
    state: ShadowState = ShadowState.UNKNOWN
    sync_status: ShadowSyncStatus = ShadowSyncStatus.OUT_OF_SYNC
    version: int = 1
    last_sync_at: Optional[datetime] = None
    last_connected_at: Optional[datetime] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=datetime.now)
    updated_at: datetime = field(default_factory=datetime.now)


class DeviceShadowManager:
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus
        self._shadows: Dict[str, DeviceShadow] = {}
        self._sync_interval = config.get("device_shadow.sync_interval_seconds", 30)
        self._max_shadow_size = config.get("device_shadow.max_shadow_size", 8192)
        self._sync_tasks: Dict[str, asyncio.Task] = {}
        self._is_running = False

    def create_shadow(self, device_id: str, initial_state: Optional[Dict[str, Any]] = None) -> DeviceShadow:
        if device_id in self._shadows:
            raise DeviceShadowException(f"Shadow for device {device_id} already exists")

        shadow = DeviceShadow(
            device_id=device_id,
            reported=initial_state or {},
            desired=initial_state or {}
        )

        self._shadows[device_id] = shadow
        self._update_delta(shadow)

        self._event_bus.publish(Event(
            event_type="shadow.created",
            source="device_shadow",
            payload={"device_id": device_id}
        ))

        return shadow

    def get_shadow(self, device_id: str) -> DeviceShadow:
        shadow = self._shadows.get(device_id)
        if not shadow:
            raise DeviceShadowException(f"Shadow for device {device_id} not found")
        return shadow

    def delete_shadow(self, device_id: str) -> None:
        if device_id in self._sync_tasks:
            self._sync_tasks[device_id].cancel()
            del self._sync_tasks[device_id]

        if device_id in self._shadows:
            del self._shadows[device_id]

            self._event_bus.publish(Event(
                event_type="shadow.deleted",
                source="device_shadow",
                payload={"device_id": device_id}
            ))

    def update_desired_state(
        self,
        device_id: str,
        desired_state: Dict[str, Any],
        expected_version: Optional[int] = None
    ) -> DeviceShadow:
        shadow = self.get_shadow(device_id)

        if expected_version is not None and shadow.version != expected_version:
            raise ShadowSyncException(
                f"Version conflict: expected {expected_version}, got {shadow.version}"
            )

        self._deep_merge(shadow.desired, desired_state)
        shadow.version += 1
        shadow.updated_at = datetime.now()
        shadow.sync_status = ShadowSyncStatus.OUT_OF_SYNC

        self._update_delta(shadow)
        self._validate_shadow_size(shadow)

        self._event_bus.publish(Event(
            event_type="shadow.desired_updated",
            source="device_shadow",
            payload={
                "device_id": device_id,
                "version": shadow.version,
                "delta": shadow.delta
            }
        ))

        return shadow

    def update_reported_state(
        self,
        device_id: str,
        reported_state: Dict[str, Any]
    ) -> DeviceShadow:
        shadow = self.get_shadow(device_id)

        self._deep_merge(shadow.reported, reported_state)
        shadow.version += 1
        shadow.updated_at = datetime.now()
        shadow.last_connected_at = datetime.now()
        shadow.state = ShadowState.ONLINE

        self._update_delta(shadow)
        self._validate_shadow_size(shadow)

        if not shadow.delta:
            shadow.sync_status = ShadowSyncStatus.IN_SYNC
            shadow.last_sync_at = datetime.now()

        self._event_bus.publish(Event(
            event_type="shadow.reported_updated",
            source="device_shadow",
            payload={
                "device_id": device_id,
                "version": shadow.version,
                "delta": shadow.delta
            }
        ))

        return shadow

    def _update_delta(self, shadow: DeviceShadow) -> None:
        delta = self._calculate_delta(shadow.desired, shadow.reported)
        shadow.delta = delta

    def _calculate_delta(
        self,
        desired: Dict[str, Any],
        reported: Dict[str, Any]
    ) -> Dict[str, Any]:
        delta = {}

        for key, desired_value in desired.items():
            reported_value = reported.get(key)

            if isinstance(desired_value, dict) and isinstance(reported_value, dict):
                nested_delta = self._calculate_delta(desired_value, reported_value)
                if nested_delta:
                    delta[key] = nested_delta
            elif desired_value != reported_value:
                delta[key] = desired_value

        return delta

    def _deep_merge(self, base: Dict[str, Any], update: Dict[str, Any]) -> None:
        for key, value in update.items():
            if (key in base and isinstance(base[key], dict) and isinstance(value, dict)):
                self._deep_merge(base[key], value)
            else:
                base[key] = copy.deepcopy(value)

    def _validate_shadow_size(self, shadow: DeviceShadow) -> None:
        shadow_json = json.dumps({
            "desired": shadow.desired,
            "reported": shadow.reported,
            "metadata": shadow.metadata
        })

        if len(shadow_json.encode("utf-8")) > self._max_shadow_size:
            raise DeviceShadowException(
                f"Shadow size exceeds maximum of {self._max_shadow_size} bytes"
            )

    async def sync_shadow(self, device_id: str) -> DeviceShadow:
        shadow = self.get_shadow(device_id)

        if not shadow.delta:
            shadow.sync_status = ShadowSyncStatus.IN_SYNC
            return shadow

        shadow.sync_status = ShadowSyncStatus.SYNCING

        self._event_bus.publish(Event(
            event_type="shadow.sync_started",
            source="device_shadow",
            payload={
                "device_id": device_id,
                "delta": shadow.delta
            }
        ))

        try:
            await self._send_desired_state_to_device(shadow)

            shadow.sync_status = ShadowSyncStatus.IN_SYNC
            shadow.last_sync_at = datetime.now()

            self._event_bus.publish(Event(
                event_type="shadow.sync_completed",
                source="device_shadow",
                payload={"device_id": device_id}
            ))

        except Exception as e:
            shadow.sync_status = ShadowSyncStatus.ERROR
            self._event_bus.publish(Event(
                event_type="shadow.sync_failed",
                source="device_shadow",
                payload={
                    "device_id": device_id,
                    "error": str(e)
                }
            ))
            raise ShadowSyncException(f"Sync failed for device {device_id}: {e}")

        return shadow

    async def _send_desired_state_to_device(self, shadow: DeviceShadow) -> None:
        await asyncio.sleep(0.1)
        logger.debug(f"Sent desired state to device {shadow.device_id}")

    async def sync_all_shadows(self) -> None:
        tasks = [
            self.sync_shadow(device_id)
            for device_id in self._shadows.keys()
            if self._shadows[device_id].state == ShadowState.ONLINE
        ]
        await asyncio.gather(*tasks, return_exceptions=True)

    async def start_sync_loop(self) -> None:
        if self._is_running:
            return

        self._is_running = True
        logger.info("Starting device shadow sync loop")

        while self._is_running:
            try:
                await self.sync_all_shadows()
            except Exception as e:
                logger.error(f"Error in sync loop: {e}")

            await asyncio.sleep(self._sync_interval)

    def stop_sync_loop(self) -> None:
        self._is_running = False
        logger.info("Stopped device shadow sync loop")

    def set_device_online(self, device_id: str) -> DeviceShadow:
        shadow = self.get_shadow(device_id)
        shadow.state = ShadowState.ONLINE
        shadow.last_connected_at = datetime.now()

        self._event_bus.publish(Event(
            event_type="shadow.device_online",
            source="device_shadow",
            payload={"device_id": device_id}
        ))

        return shadow

    def set_device_offline(self, device_id: str) -> DeviceShadow:
        shadow = self.get_shadow(device_id)
        shadow.state = ShadowState.OFFLINE

        self._event_bus.publish(Event(
            event_type="shadow.device_offline",
            source="device_shadow",
            payload={"device_id": device_id}
        ))

        return shadow

    def get_shadow_diff(self, device_id: str) -> Dict[str, Any]:
        shadow = self.get_shadow(device_id)
        return {
            "device_id": device_id,
            "delta": shadow.delta,
            "sync_status": shadow.sync_status.value,
            "version": shadow.version
        }

    def list_shadows(
        self,
        state: Optional[ShadowState] = None,
        sync_status: Optional[ShadowSyncStatus] = None,
        limit: int = 100
    ) -> List[DeviceShadow]:
        shadows = list(self._shadows.values())

        if state:
            shadows = [s for s in shadows if s.state == state]
        if sync_status:
            shadows = [s for s in shadows if s.sync_status == sync_status]

        shadows.sort(key=lambda s: s.updated_at, reverse=True)
        return shadows[:limit]

    def get_stats(self) -> Dict[str, Any]:
        total = len(self._shadows)
        online = sum(1 for s in self._shadows.values() if s.state == ShadowState.ONLINE)
        offline = sum(1 for s in self._shadows.values() if s.state == ShadowState.OFFLINE)
        in_sync = sum(1 for s in self._shadows.values() if s.sync_status == ShadowSyncStatus.IN_SYNC)
        out_of_sync = sum(1 for s in self._shadows.values() if s.sync_status == ShadowSyncStatus.OUT_OF_SYNC)

        return {
            "total_shadows": total,
            "online": online,
            "offline": offline,
            "in_sync": in_sync,
            "out_of_sync": out_of_sync,
            "sync_interval_seconds": self._sync_interval
        }
