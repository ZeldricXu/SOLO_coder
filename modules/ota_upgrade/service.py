from typing import Optional, Dict, Any, List, Tuple
from datetime import datetime, timedelta
import threading
import time
import uuid
import hashlib
import os
import json
import difflib

from domain.models.ota import OTAPackage, UpgradeTask, UpgradeStatus, UpgradeStrategy, UpgradeBatch
from domain.models.event import EventType

from infrastructure.persistence.repositories.ota_repository import OTARepository
from infrastructure.persistence.repositories.device_repository import DeviceRepository
from infrastructure.messaging.event_bus import EventBus, get_event_bus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class OTAService:
    def __init__(
        self,
        ota_repo: OTARepository,
        device_repo: DeviceRepository,
        event_bus: Optional[EventBus] = None,
    ):
        self.ota_repo = ota_repo
        self.device_repo = device_repo
        self.event_bus = event_bus or get_event_bus()

        self._upgrade_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._is_running = False

        self._packages_dir = "./ota_packages"
        self._ensure_packages_dir()

    def _ensure_packages_dir(self) -> None:
        os.makedirs(self._packages_dir, exist_ok=True)

    def create_package(
        self,
        package_name: str,
        version: str,
        firmware_version: str,
        file_path: str,
        release_notes: Optional[str] = None,
        is_delta: bool = False,
        base_version: Optional[str] = None,
        min_firmware_version: Optional[str] = None,
        force_upgrade: bool = False,
        auto_apply: bool = False,
    ) -> OTAPackage:
        if not os.path.exists(file_path):
            raise ValueError(f"Package file not found: {file_path}")

        file_size = os.path.getsize(file_path)
        checksum = self._calculate_checksum(file_path)

        package = OTAPackage(
            package_id=str(uuid.uuid4()),
            package_name=package_name,
            version=version,
            firmware_version=firmware_version,
            file_path=file_path,
            file_size=file_size,
            checksum=checksum,
            release_notes=release_notes,
            is_delta=is_delta,
            base_version=base_version,
            min_firmware_version=min_firmware_version,
            force_upgrade=force_upgrade,
            auto_apply=auto_apply,
        )

        self.ota_repo.save_package(package)

        event = self.event_bus.create_event(
            event_type=EventType.OTA_PACKAGE_CREATED,
            data={"package_id": package.package_id, "version": version},
        )
        self.event_bus.publish(event)

        logger.info(f"Created OTA package: {package.package_id} v{version}")
        return package

    def _calculate_checksum(self, file_path: str) -> str:
        sha256_hash = hashlib.sha256()
        with open(file_path, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()

    def create_delta_package(
        self,
        base_package: OTAPackage,
        new_package_file: str,
        new_version: str,
    ) -> OTAPackage:
        diff_file_path = self._generate_diff(base_package.file_path, new_package_file)
        return self.create_package(
            package_name=f"{base_package.package_name}_delta",
            version=new_version,
            firmware_version=new_version,
            file_path=new_package_file,
            is_delta=True,
            base_version=base_package.version,
        )

    def _generate_diff(self, old_file: str, new_file: str) -> str:
        diff_path = f"{new_file}.diff"
        try:
            with open(old_file, "r") as f_old, open(new_file, "r") as f_new:
                old_lines = f_old.readlines()
                new_lines = f_new.readlines()

            diff = difflib.unified_diff(old_lines, new_lines)
            with open(diff_path, "w") as f_diff:
                f_diff.writelines(diff)
        except Exception:
            pass
        return diff_path

    def get_package(self, package_id: str) -> Optional[OTAPackage]:
        return self.ota_repo.get_package(package_id)

    def list_packages(self) -> List[OTAPackage]:
        return self.ota_repo.get_all_packages()

    def delete_package(self, package_id: str) -> bool:
        return self.ota_repo.delete_package(package_id)

    def create_upgrade_task(
        self,
        package_id: str,
        device_id: str,
        strategy: UpgradeStrategy = UpgradeStrategy.SEQUENTIAL,
        scheduled_at: Optional[datetime] = None,
        rollback_on_failure: bool = True,
    ) -> UpgradeTask:
        package = self.ota_repo.get_package(package_id)
        if not package:
            raise ValueError(f"Package not found: {package_id}")

        device = self.device_repo.get_by_device_id(device_id)
        if not device:
            raise ValueError(f"Device not found: {device_id}")

        if package.min_firmware_version and device.firmware_version:
            if not self._version_compare(device.firmware_version, package.min_firmware_version):
                raise ValueError(f"Device firmware version {device.firmware_version} is below minimum required {package.min_firmware_version}")

        task = UpgradeTask(
            task_id=str(uuid.uuid4()),
            package_id=package_id,
            device_id=device_id,
            strategy=strategy,
            scheduled_at=scheduled_at,
            current_version=device.firmware_version,
            target_version=package.firmware_version,
            rollback_on_failure=rollback_on_failure,
            rollback_version=device.firmware_version,
        )

        self.ota_repo.save_upgrade_task(task)

        event = self.event_bus.create_event(
            event_type=EventType.OTA_UPGRADE_STARTED,
            device_id=device_id,
            data={"task_id": task.task_id, "package_id": package_id},
        )
        self.event_bus.publish(event)

        logger.info(f"Created upgrade task {task.task_id} for device {device_id}")
        return task

    def create_batch_upgrade(
        self,
        package_id: str,
        device_ids: List[str],
        strategy: UpgradeStrategy = UpgradeStrategy.BATCH,
        batch_size: int = 10,
        delay_between_batches: int = 300,
    ) -> List[UpgradeTask]:
        tasks = []
        batch_id = str(uuid.uuid4())
        total_batches = (len(device_ids) + batch_size - 1) // batch_size

        for batch_num in range(total_batches):
            start_idx = batch_num * batch_size
            end_idx = min(start_idx + batch_size, len(device_ids))
            batch_device_ids = device_ids[start_idx:end_idx]

            for i, device_id in enumerate(batch_device_ids):
                scheduled_at = None
                if strategy == UpgradeStrategy.BATCH and batch_num > 0:
                    scheduled_at = datetime.utcnow() + timedelta(seconds=batch_num * delay_between_batches)
                elif strategy == UpgradeStrategy.SEQUENTIAL:
                    scheduled_at = datetime.utcnow() + timedelta(seconds=(start_idx + i) * delay_between_batches)

                task = self.create_upgrade_task(
                    package_id=package_id,
                    device_id=device_id,
                    strategy=strategy,
                    scheduled_at=scheduled_at,
                )
                task.batch_id = batch_id
                task.batch_number = batch_num + 1
                task.total_batches = total_batches
                self.ota_repo.update_upgrade_task(task.task_id, task.model_dump())
                tasks.append(task)

        logger.info(f"Created batch upgrade {batch_id} with {len(tasks)} tasks in {total_batches} batches")
        return tasks

    def create_canary_upgrade(
        self,
        package_id: str,
        canary_device_ids: List[str],
        remaining_device_ids: List[str],
        success_threshold: float = 1.0,
        monitoring_period_seconds: int = 3600,
    ) -> Dict[str, Any]:
        canary_tasks = self.create_batch_upgrade(
            package_id=package_id,
            device_ids=canary_device_ids,
            strategy=UpgradeStrategy.CANARY,
            batch_size=len(canary_device_ids),
        )

        return {
            "canary_tasks": canary_tasks,
            "remaining_devices": remaining_device_ids,
            "success_threshold": success_threshold,
            "monitoring_period_seconds": monitoring_period_seconds,
            "canary_complete": False,
            "canary_success": False,
        }

    def get_task(self, task_id: str) -> Optional[UpgradeTask]:
        return self.ota_repo.get_upgrade_task(task_id)

    def get_device_upgrade_history(self, device_id: str) -> List[UpgradeTask]:
        return self.ota_repo.get_tasks_by_device(device_id)

    def update_task_status(
        self,
        task_id: str,
        status: UpgradeStatus,
        error_message: Optional[str] = None,
        error_code: Optional[int] = None,
        download_progress: Optional[int] = None,
        install_progress: Optional[int] = None,
    ) -> Optional[UpgradeTask]:
        task = self.ota_repo.get_upgrade_task(task_id)
        if not task:
            return None

        update_data = {
            "status": status,
        }

        if download_progress is not None:
            update_data["download_progress"] = download_progress
        if install_progress is not None:
            update_data["install_progress"] = install_progress
        if error_message:
            update_data["error_message"] = error_message
        if error_code is not None:
            update_data["error_code"] = error_code

        updated = self.ota_repo.update_upgrade_task(task_id, update_data)
        if updated:
            if status == UpgradeStatus.COMPLETED:
                event = self.event_bus.create_event(
                    event_type=EventType.OTA_UPGRADE_COMPLETED,
                    device_id=task.device_id,
                    data={"task_id": task_id, "package_id": task.package_id},
                )
                self.event_bus.publish(event)

                device = self.device_repo.get_by_device_id(task.device_id)
                if device:
                    self.device_repo.update_device(task.device_id, {"firmware_version": task.target_version})

            elif status == UpgradeStatus.FAILED:
                event = self.event_bus.create_event(
                    event_type=EventType.OTA_UPGRADE_FAILED,
                    device_id=task.device_id,
                    data={"task_id": task_id, "error": error_message},
                )
                self.event_bus.publish(event)

            elif status == UpgradeStatus.ROLLING_BACK:
                event = self.event_bus.create_event(
                    event_type=EventType.OTA_ROLLBACK_STARTED,
                    device_id=task.device_id,
                    data={"task_id": task_id},
                )
                self.event_bus.publish(event)

            elif status == UpgradeStatus.ROLLED_BACK:
                event = self.event_bus.create_event(
                    event_type=EventType.OTA_ROLLBACK_COMPLETED,
                    device_id=task.device_id,
                    data={"task_id": task_id},
                )
                self.event_bus.publish(event)

        return updated

    def initiate_rollback(self, task_id: str) -> Optional[UpgradeTask]:
        task = self.ota_repo.get_upgrade_task(task_id)
        if not task or not task.rollback_on_failure:
            return None

        return self.update_task_status(task_id, UpgradeStatus.ROLLING_BACK)

    def complete_rollback(self, task_id: str) -> Optional[UpgradeTask]:
        task = self.update_task_status(task_id, UpgradeStatus.ROLLED_BACK)
        if task:
            device = self.device_repo.get_by_device_id(task.device_id)
            if device and task.rollback_version:
                self.device_repo.update_device(task.device_id, {"firmware_version": task.rollback_version})
        return task

    def cancel_task(self, task_id: str) -> bool:
        task = self.ota_repo.get_upgrade_task(task_id)
        if not task or task.is_complete():
            return False

        self.update_task_status(task_id, UpgradeStatus.CANCELLED)
        return True

    def verify_package(self, package: OTAPackage) -> bool:
        if not os.path.exists(package.file_path):
            logger.error(f"Package file not found: {package.file_path}")
            return False

        actual_checksum = self._calculate_checksum(package.file_path)
        if actual_checksum != package.checksum:
            logger.error(f"Checksum mismatch for package {package.package_id}")
            return False

        return True

    def _version_compare(self, version1: str, version2: str) -> bool:
        try:
            v1_parts = [int(x) for x in version1.split(".")]
            v2_parts = [int(x) for x in version2.split(".")]
            return v1_parts >= v2_parts
        except Exception:
            return version1 >= version2

    def get_upgrade_stats(self) -> Dict[str, Any]:
        from infrastructure.persistence.database import SessionLocal
        from infrastructure.persistence.models.ota_model import UpgradeTaskModel
        from sqlalchemy import func

        db = SessionLocal()
        try:
            total_tasks = db.query(func.count(UpgradeTaskModel.task_id)).scalar()
            completed = db.query(func.count(UpgradeTaskModel.task_id)).filter(
                UpgradeTaskModel.status == UpgradeStatus.COMPLETED.value
            ).scalar()
            failed = db.query(func.count(UpgradeTaskModel.task_id)).filter(
                UpgradeTaskModel.status == UpgradeStatus.FAILED.value
            ).scalar()
            in_progress = db.query(func.count(UpgradeTaskModel.task_id)).filter(
                UpgradeTaskModel.status.in_([
                    UpgradeStatus.DOWNLOADING.value,
                    UpgradeStatus.INSTALLING.value,
                    UpgradeStatus.REBOOTING.value,
                    UpgradeStatus.VERIFYING.value,
                ])
            ).scalar()

            return {
                "total_packages": len(self.ota_repo.get_all_packages()),
                "total_tasks": total_tasks,
                "completed": completed,
                "failed": failed,
                "in_progress": in_progress,
                "success_rate": (completed / total_tasks * 100) if total_tasks > 0 else 0,
            }
        finally:
            db.close()

    def start(self) -> None:
        if self._is_running:
            return

        self._is_running = True
        self._stop_event.clear()
        self._upgrade_thread = threading.Thread(target=self._upgrade_loop, daemon=True)
        self._upgrade_thread.start()
        logger.info("OTA upgrade service started")

    def stop(self) -> None:
        self._is_running = False
        self._stop_event.set()
        if self._upgrade_thread:
            self._upgrade_thread.join(timeout=5)
        logger.info("OTA upgrade service stopped")

    def _upgrade_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self._process_scheduled_tasks()
                self._check_batch_progress()
            except Exception as e:
                logger.error(f"Error in upgrade loop: {str(e)}")

            self._stop_event.wait(10)

    def _process_scheduled_tasks(self) -> None:
        now = datetime.utcnow()
        pending_tasks = self.ota_repo.get_pending_tasks()

        for task in pending_tasks:
            if task.scheduled_at and task.scheduled_at <= now:
                self.update_task_status(task.task_id, UpgradeStatus.DOWNLOADING)
                logger.info(f"Started processing upgrade task {task.task_id}")

    def _check_batch_progress(self) -> None:
        pass
