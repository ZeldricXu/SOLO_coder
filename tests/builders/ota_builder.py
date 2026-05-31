from typing import Any, Dict, List, Optional
import uuid
from datetime import datetime


class FirmwareInfoBuilder:
    def __init__(self):
        self._version: str = "1.0.0"
        self._device_model: str = "sensor-v1"
        self._firmware_url: str = "https://example.com/firmware.bin"
        self._checksum: str = "sha256:abc123"
        self._size: int = 1024000
        self._metadata: Dict[str, Any] = {}

    def with_version(self, version: str) -> "FirmwareInfoBuilder":
        self._version = version
        return self

    def with_device_model(self, device_model: str) -> "FirmwareInfoBuilder":
        self._device_model = device_model
        return self

    def with_firmware_url(self, url: str) -> "FirmwareInfoBuilder":
        self._firmware_url = url
        return self

    def with_checksum(self, checksum: str) -> "FirmwareInfoBuilder":
        self._checksum = checksum
        return self

    def with_size(self, size: int) -> "FirmwareInfoBuilder":
        self._size = size
        return self

    def with_metadata(self, metadata: Dict[str, Any]) -> "FirmwareInfoBuilder":
        self._metadata = metadata
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "version": self._version,
            "device_model": self._device_model,
            "firmware_url": self._firmware_url,
            "checksum": self._checksum,
            "size": self._size,
            "metadata": self._metadata,
        }


class OTAUpgradeTaskBuilder:
    def __init__(self):
        self._task_id: str = f"ota_task_{uuid.uuid4().hex[:8]}"
        self._device_ids: List[str] = [f"dev_{i}" for i in range(5)]
        self._firmware_info: Dict[str, Any] = FirmwareInfoBuilder().build()
        self._strategy: str = "instant"
        self._batch_size: int = 10
        self._auto_rollback: bool = True
        self._rollback_threshold: float = 0.2

    def with_task_id(self, task_id: str) -> "OTAUpgradeTaskBuilder":
        self._task_id = task_id
        return self

    def with_device_ids(self, device_ids: List[str]) -> "OTAUpgradeTaskBuilder":
        self._device_ids = device_ids
        return self

    def with_device_count(self, count: int, prefix: Optional[str] = None) -> "OTAUpgradeTaskBuilder":
        prefix = prefix or uuid.uuid4().hex[:4]
        self._device_ids = [f"{prefix}_dev_{i}" for i in range(count)]
        return self

    def with_firmware_info(self, firmware_info: Dict[str, Any]) -> "OTAUpgradeTaskBuilder":
        self._firmware_info = firmware_info
        return self

    def with_strategy(self, strategy: str) -> "OTAUpgradeTaskBuilder":
        self._strategy = strategy
        return self

    def with_batch_size(self, batch_size: int) -> "OTAUpgradeTaskBuilder":
        self._batch_size = batch_size
        return self

    def with_auto_rollback(self, auto_rollback: bool) -> "OTAUpgradeTaskBuilder":
        self._auto_rollback = auto_rollback
        return self

    def with_rollback_threshold(self, threshold: float) -> "OTAUpgradeTaskBuilder":
        self._rollback_threshold = threshold
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "task_id": self._task_id,
            "device_ids": self._device_ids,
            "firmware_info": self._firmware_info,
            "strategy": self._strategy,
            "batch_size": self._batch_size,
            "auto_rollback": self._auto_rollback,
            "rollback_threshold": self._rollback_threshold,
        }


class DeviceProgressBuilder:
    def __init__(self):
        self._device_id: str = f"dev_{uuid.uuid4().hex[:6]}"
        self._task_id: str = f"ota_task_{uuid.uuid4().hex[:8]}"
        self._phase: str = "pending"
        self._progress: float = 0.0
        self._error: Optional[str] = None
        self._rollback_triggered: bool = False

    def with_device_id(self, device_id: str) -> "DeviceProgressBuilder":
        self._device_id = device_id
        return self

    def with_task_id(self, task_id: str) -> "DeviceProgressBuilder":
        self._task_id = task_id
        return self

    def with_phase(self, phase: str) -> "DeviceProgressBuilder":
        self._phase = phase
        return self

    def with_progress(self, progress: float) -> "DeviceProgressBuilder":
        self._progress = progress
        return self

    def with_error(self, error: str) -> "DeviceProgressBuilder":
        self._error = error
        return self

    def with_rollback_triggered(self, triggered: bool) -> "DeviceProgressBuilder":
        self._rollback_triggered = triggered
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "device_id": self._device_id,
            "task_id": self._task_id,
            "phase": self._phase,
            "progress": self._progress,
            "error": self._error,
            "rollback_triggered": self._rollback_triggered,
        }
