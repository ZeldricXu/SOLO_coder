import asyncio
import logging
import hashlib
import zlib
import os
import json
from typing import Dict, List, Optional, Tuple, Any
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
import uuid

from ..common.event_bus import EventBus, Event, event_bus
from ..common.config import config
from ..common.exceptions import (
    DeltaGenerationException,
    RollbackException,
    OTAException
)

logger = logging.getLogger(__name__)


class UpgradeStatus(str, Enum):
    PENDING = "pending"
    DOWNLOADING = "downloading"
    INSTALLING = "installing"
    VERIFYING = "verifying"
    SUCCESS = "success"
    FAILED = "failed"
    ROLLBACK = "rollback"
    ROLLBACK_COMPLETE = "rollback_complete"


@dataclass
class FirmwareVersion:
    version_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    version: str = ""
    description: str = ""
    hardware_model: str = ""
    file_path: str = ""
    file_size: int = 0
    md5_hash: str = ""
    released_at: datetime = field(default_factory=datetime.now)
    is_active: bool = False
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class DeltaPackage:
    delta_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    from_version: str = ""
    to_version: str = ""
    hardware_model: str = ""
    file_path: str = ""
    file_size: int = 0
    md5_hash: str = ""
    compression_ratio: float = 0.0
    created_at: datetime = field(default_factory=datetime.now)


@dataclass
class UpgradeBatch:
    batch_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    target_version: str = ""
    hardware_model: str = ""
    device_ids: List[str] = field(default_factory=list)
    batch_percentage: float = 10.0
    status: UpgradeStatus = UpgradeStatus.PENDING
    success_count: int = 0
    failed_count: int = 0
    created_at: datetime = field(default_factory=datetime.now)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


@dataclass
class DeviceUpgradeRecord:
    record_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    device_id: str = ""
    batch_id: str = ""
    from_version: str = ""
    to_version: str = ""
    status: UpgradeStatus = UpgradeStatus.PENDING
    error_message: Optional[str] = None
    created_at: datetime = field(default_factory=datetime.now)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class DeltaGenerator:
    def __init__(self, algorithm: str = "bsdiff"):
        self.algorithm = algorithm

    def generate_delta(
        self,
        old_file_path: str,
        new_file_path: str,
        output_path: str
    ) -> Tuple[int, float]:
        try:
            with open(old_file_path, "rb") as f:
                old_data = f.read()
            with open(new_file_path, "rb") as f:
                new_data = f.read()

            delta_data = self._simple_delta(old_data, new_data)
            compressed_delta = zlib.compress(delta_data)

            os.makedirs(os.path.dirname(output_path), exist_ok=True)
            with open(output_path, "wb") as f:
                f.write(compressed_delta)

            file_size = len(compressed_delta)
            compression_ratio = file_size / len(new_data) if len(new_data) > 0 else 0

            return file_size, compression_ratio
        except Exception as e:
            raise DeltaGenerationException(f"Failed to generate delta: {e}")

    def _simple_delta(self, old_data: bytes, new_data: bytes) -> bytes:
        delta = bytearray()
        i = 0
        while i < len(new_data):
            if i < len(old_data) and old_data[i] == new_data[i]:
                same_count = 0
                while (i + same_count < len(old_data) and
                       i + same_count < len(new_data) and
                       old_data[i + same_count] == new_data[i + same_count] and
                       same_count < 255):
                    same_count += 1
                delta.append(0)
                delta.append(same_count)
                i += same_count
            else:
                diff_bytes = bytearray()
                while (i + len(diff_bytes) < len(new_data) and
                       len(diff_bytes) < 255 and
                       (i + len(diff_bytes) >= len(old_data) or
                        old_data[i + len(diff_bytes)] != new_data[i + len(diff_bytes)])):
                    diff_bytes.append(new_data[i + len(diff_bytes)])
                delta.append(1)
                delta.append(len(diff_bytes))
                delta.extend(diff_bytes)
                i += len(diff_bytes)

        return bytes(delta)

    def apply_delta(self, old_data: bytes, delta_path: str) -> bytes:
        with open(delta_path, "rb") as f:
            compressed_delta = f.read()

        delta_data = zlib.decompress(compressed_delta)
        result = bytearray(old_data)
        i = 0
        delta_pos = 0

        while delta_pos < len(delta_data):
            is_diff = delta_data[delta_pos]
            delta_pos += 1
            count = delta_data[delta_pos]
            delta_pos += 1

            if is_diff:
                new_bytes = delta_data[delta_pos:delta_pos + count]
                delta_pos += count
                if i + count > len(result):
                    result.extend([0] * (i + count - len(result)))
                result[i:i + count] = new_bytes
            i += count

        return bytes(result)


class OTAManager:
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus
        self._delta_generator = DeltaGenerator(config.get("ota.delta_algorithm", "bsdiff"))
        self._firmware_versions: Dict[str, FirmwareVersion] = {}
        self._delta_packages: Dict[str, DeltaPackage] = {}
        self._upgrade_batches: Dict[str, UpgradeBatch] = {}
        self._device_records: Dict[str, DeviceUpgradeRecord] = {}
        self._device_current_versions: Dict[str, str] = {}
        self._max_batch_size = config.get("ota.max_batch_size", 100)
        self._auto_rollback_enabled = config.get("ota.auto_rollback_enabled", True)
        self._rollback_threshold = config.get("ota.rollback_threshold", 0.3)
        self._storage_path = "./data/ota"
        os.makedirs(self._storage_path, exist_ok=True)

    def register_firmware_version(
        self,
        version: str,
        hardware_model: str,
        file_path: str,
        description: str = "",
        metadata: Optional[Dict[str, Any]] = None
    ) -> FirmwareVersion:
        if not os.path.exists(file_path):
            raise OTAException(f"Firmware file not found: {file_path}")

        file_size = os.path.getsize(file_path)
        md5_hash = self._calculate_md5(file_path)

        firmware = FirmwareVersion(
            version=version,
            description=description,
            hardware_model=hardware_model,
            file_path=file_path,
            file_size=file_size,
            md5_hash=md5_hash,
            metadata=metadata or {}
        )

        key = f"{hardware_model}_{version}"
        self._firmware_versions[key] = firmware

        self._event_bus.publish(Event(
            event_type="ota.firmware.registered",
            source="ota",
            payload={
                "version": version,
                "hardware_model": hardware_model,
                "file_size": file_size
            }
        ))

        logger.info(f"Registered firmware {version} for {hardware_model}")
        return firmware

    def generate_delta_package(
        self,
        from_version: str,
        to_version: str,
        hardware_model: str
    ) -> DeltaPackage:
        from_key = f"{hardware_model}_{from_version}"
        to_key = f"{hardware_model}_{to_version}"

        if from_key not in self._firmware_versions:
            raise DeltaGenerationException(f"From version {from_version} not found")
        if to_key not in self._firmware_versions:
            raise DeltaGenerationException(f"To version {to_version} not found")

        delta_id = str(uuid.uuid4())
        delta_path = os.path.join(self._storage_path, "deltas", f"{delta_id}.delta")

        old_firmware = self._firmware_versions[from_key]
        new_firmware = self._firmware_versions[to_key]

        file_size, compression_ratio = self._delta_generator.generate_delta(
            old_firmware.file_path,
            new_firmware.file_path,
            delta_path
        )

        delta = DeltaPackage(
            delta_id=delta_id,
            from_version=from_version,
            to_version=to_version,
            hardware_model=hardware_model,
            file_path=delta_path,
            file_size=file_size,
            md5_hash=self._calculate_md5(delta_path),
            compression_ratio=compression_ratio
        )

        self._delta_packages[delta_id] = delta

        self._event_bus.publish(Event(
            event_type="ota.delta.generated",
            source="ota",
            payload={
                "delta_id": delta_id,
                "from_version": from_version,
                "to_version": to_version,
                "compression_ratio": compression_ratio
            }
        ))

        return delta

    def create_upgrade_batch(
        self,
        target_version: str,
        hardware_model: str,
        device_ids: List[str],
        batch_percentage: float = 10.0
    ) -> UpgradeBatch:
        batch = UpgradeBatch(
            target_version=target_version,
            hardware_model=hardware_model,
            device_ids=device_ids.copy(),
            batch_percentage=batch_percentage
        )

        self._upgrade_batches[batch.batch_id] = batch

        for device_id in device_ids:
            record = DeviceUpgradeRecord(
                device_id=device_id,
                batch_id=batch.batch_id,
                from_version=self._device_current_versions.get(device_id, "unknown"),
                to_version=target_version
            )
            self._device_records[f"{batch.batch_id}_{device_id}"] = record

        self._event_bus.publish(Event(
            event_type="ota.batch.created",
            source="ota",
            payload={
                "batch_id": batch.batch_id,
                "target_version": target_version,
                "device_count": len(device_ids)
            }
        ))

        return batch

    async def start_upgrade_batch(self, batch_id: str) -> None:
        batch = self._get_batch(batch_id)
        batch.status = UpgradeStatus.DOWNLOADING
        batch.started_at = datetime.now()

        batch_size = max(1, int(len(batch.device_ids) * batch.batch_percentage / 100))
        batch_size = min(batch_size, self._max_batch_size)

        current_batch_devices = batch.device_ids[:batch_size]

        self._event_bus.publish(Event(
            event_type="ota.batch.started",
            source="ota",
            payload={
                "batch_id": batch_id,
                "target_version": batch.target_version,
                "batch_size": batch_size
            }
        ))

        await self._process_device_upgrades(batch, current_batch_devices)

    async def _process_device_upgrades(
        self,
        batch: UpgradeBatch,
        device_ids: List[str]
    ) -> None:
        tasks = [
            self._upgrade_device(batch, device_id)
            for device_id in device_ids
        ]
        await asyncio.gather(*tasks, return_exceptions=True)

        failure_rate = batch.failed_count / len(batch.device_ids) if batch.device_ids else 0

        if failure_rate > self._rollback_threshold and self._auto_rollback_enabled:
            logger.warning(
                f"Failure rate {failure_rate:.2%} exceeds threshold {self._rollback_threshold:.2%}, "
                f"initiating rollback for batch {batch.batch_id}"
            )
            await self.rollback_batch(batch.batch_id)
        else:
            processed = batch.success_count + batch.failed_count
            if processed < len(batch.device_ids):
                next_batch_size = max(1, int(len(batch.device_ids) * batch.batch_percentage / 100))
                next_batch = batch.device_ids[processed:processed + next_batch_size]
                if next_batch:
                    await self._process_device_upgrades(batch, next_batch)
            else:
                batch.status = UpgradeStatus.SUCCESS
                batch.completed_at = datetime.now()
                self._event_bus.publish(Event(
                    event_type="ota.batch.completed",
                    source="ota",
                    payload={
                        "batch_id": batch.batch_id,
                        "success_count": batch.success_count,
                        "failed_count": batch.failed_count
                    }
                ))

    async def _upgrade_device(self, batch: UpgradeBatch, device_id: str) -> None:
        record_key = f"{batch.batch_id}_{device_id}"
        record = self._device_records.get(record_key)
        if not record:
            return

        record.status = UpgradeStatus.DOWNLOADING
        record.started_at = datetime.now()

        try:
            await self._download_firmware(device_id, batch.target_version, batch.hardware_model)
            record.status = UpgradeStatus.INSTALLING

            await self._install_firmware(device_id, batch.target_version)
            record.status = UpgradeStatus.VERIFYING

            if await self._verify_upgrade(device_id, batch.target_version):
                record.status = UpgradeStatus.SUCCESS
                record.completed_at = datetime.now()
                self._device_current_versions[device_id] = batch.target_version
                batch.success_count += 1

                self._event_bus.publish(Event(
                    event_type="ota.device.upgraded",
                    source="ota",
                    payload={
                        "device_id": device_id,
                        "version": batch.target_version,
                        "batch_id": batch.batch_id
                    }
                ))
            else:
                raise Exception("Verification failed")

        except Exception as e:
            record.status = UpgradeStatus.FAILED
            record.error_message = str(e)
            record.completed_at = datetime.now()
            batch.failed_count += 1

            self._event_bus.publish(Event(
                event_type="ota.device.failed",
                source="ota",
                payload={
                    "device_id": device_id,
                    "error": str(e),
                    "batch_id": batch.batch_id
                }
            ))

    async def _download_firmware(self, device_id: str, version: str, hardware_model: str) -> None:
        await asyncio.sleep(0.1)
        logger.debug(f"Downloaded firmware {version} to device {device_id}")

    async def _install_firmware(self, device_id: str, version: str) -> None:
        await asyncio.sleep(0.2)
        logger.debug(f"Installed firmware {version} on device {device_id}")

    async def _verify_upgrade(self, device_id: str, version: str) -> bool:
        await asyncio.sleep(0.05)
        return True

    async def rollback_batch(self, batch_id: str) -> None:
        batch = self._get_batch(batch_id)
        batch.status = UpgradeStatus.ROLLBACK

        self._event_bus.publish(Event(
            event_type="ota.batch.rollback_started",
            source="ota",
            payload={"batch_id": batch_id}
        ))

        upgraded_devices = [
            device_id
            for (key, record) in self._device_records.items()
            if record.batch_id == batch_id and record.status == UpgradeStatus.SUCCESS
        ]

        tasks = [self._rollback_device(batch, device_id) for device_id in upgraded_devices]
        await asyncio.gather(*tasks, return_exceptions=True)

        batch.status = UpgradeStatus.ROLLBACK_COMPLETE
        batch.completed_at = datetime.now()

        self._event_bus.publish(Event(
            event_type="ota.batch.rollback_complete",
            source="ota",
            payload={"batch_id": batch_id}
        ))

    async def _rollback_device(self, batch: UpgradeBatch, device_id: str) -> None:
        record_key = f"{batch.batch_id}_{device_id}"
        record = self._device_records.get(record_key)
        if not record:
            return

        record.status = UpgradeStatus.ROLLBACK

        try:
            await self._install_firmware(device_id, record.from_version)
            if await self._verify_upgrade(device_id, record.from_version):
                record.status = UpgradeStatus.ROLLBACK_COMPLETE
                self._device_current_versions[device_id] = record.from_version

                self._event_bus.publish(Event(
                    event_type="ota.device.rollback_complete",
                    source="ota",
                    payload={
                        "device_id": device_id,
                        "version": record.from_version
                    }
                ))
            else:
                raise RollbackException("Rollback verification failed")
        except Exception as e:
            record.status = UpgradeStatus.FAILED
            record.error_message = f"Rollback failed: {e}"

    def _get_batch(self, batch_id: str) -> UpgradeBatch:
        batch = self._upgrade_batches.get(batch_id)
        if not batch:
            raise OTAException(f"Batch {batch_id} not found")
        return batch

    def _calculate_md5(self, file_path: str) -> str:
        md5_hash = hashlib.md5()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(4096), b""):
                md5_hash.update(chunk)
        return md5_hash.hexdigest()

    def get_upgrade_stats(self) -> Dict[str, Any]:
        total_batches = len(self._upgrade_batches)
        status_counts = {}
        for status in UpgradeStatus:
            status_counts[status.value] = sum(
                1 for b in self._upgrade_batches.values()
                if b.status == status
            )

        return {
            "total_batches": total_batches,
            "batches_by_status": status_counts,
            "total_firmware_versions": len(self._firmware_versions),
            "total_delta_packages": len(self._delta_packages)
        }
