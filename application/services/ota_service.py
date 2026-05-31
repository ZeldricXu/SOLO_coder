from typing import Optional, Dict, Any, List
from datetime import datetime

from domain.models.ota import OTAPackage, UpgradeTask, UpgradeStatus, UpgradeStrategy

from modules.ota_upgrade.service import OTAService as OTAUpgradeService
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class OTAService:
    def __init__(self, ota_upgrade_service: OTAUpgradeService):
        self.ota_upgrade_service = ota_upgrade_service

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
    ) -> OTAPackage:
        return self.ota_upgrade_service.create_package(
            package_name=package_name,
            version=version,
            firmware_version=firmware_version,
            file_path=file_path,
            release_notes=release_notes,
            is_delta=is_delta,
            base_version=base_version,
            min_firmware_version=min_firmware_version,
            force_upgrade=force_upgrade,
        )

    def get_package(self, package_id: str) -> Optional[OTAPackage]:
        return self.ota_upgrade_service.get_package(package_id)

    def list_packages(self) -> List[OTAPackage]:
        return self.ota_upgrade_service.list_packages()

    def delete_package(self, package_id: str) -> bool:
        return self.ota_upgrade_service.delete_package(package_id)

    def create_upgrade_task(
        self,
        package_id: str,
        device_id: str,
        strategy: str = "sequential",
        scheduled_at: Optional[datetime] = None,
        rollback_on_failure: bool = True,
    ) -> UpgradeTask:
        strategy_enum = UpgradeStrategy(strategy.lower())
        return self.ota_upgrade_service.create_upgrade_task(
            package_id=package_id,
            device_id=device_id,
            strategy=strategy_enum,
            scheduled_at=scheduled_at,
            rollback_on_failure=rollback_on_failure,
        )

    def create_batch_upgrade(
        self,
        package_id: str,
        device_ids: List[str],
        strategy: str = "batch",
        batch_size: int = 10,
        delay_between_batches: int = 300,
    ) -> List[UpgradeTask]:
        strategy_enum = UpgradeStrategy(strategy.lower())
        return self.ota_upgrade_service.create_batch_upgrade(
            package_id=package_id,
            device_ids=device_ids,
            strategy=strategy_enum,
            batch_size=batch_size,
            delay_between_batches=delay_between_batches,
        )

    def create_canary_upgrade(
        self,
        package_id: str,
        canary_device_ids: List[str],
        remaining_device_ids: List[str],
        success_threshold: float = 1.0,
        monitoring_period_seconds: int = 3600,
    ) -> Dict[str, Any]:
        return self.ota_upgrade_service.create_canary_upgrade(
            package_id=package_id,
            canary_device_ids=canary_device_ids,
            remaining_device_ids=remaining_device_ids,
            success_threshold=success_threshold,
            monitoring_period_seconds=monitoring_period_seconds,
        )

    def get_task(self, task_id: str) -> Optional[UpgradeTask]:
        return self.ota_upgrade_service.get_task(task_id)

    def get_device_upgrade_history(self, device_id: str) -> List[UpgradeTask]:
        return self.ota_upgrade_service.get_device_upgrade_history(device_id)

    def update_task_status(
        self,
        task_id: str,
        status: str,
        error_message: Optional[str] = None,
        error_code: Optional[int] = None,
        download_progress: Optional[int] = None,
        install_progress: Optional[int] = None,
    ) -> Optional[UpgradeTask]:
        status_enum = UpgradeStatus(status.lower())
        return self.ota_upgrade_service.update_task_status(
            task_id=task_id,
            status=status_enum,
            error_message=error_message,
            error_code=error_code,
            download_progress=download_progress,
            install_progress=install_progress,
        )

    def initiate_rollback(self, task_id: str) -> Optional[UpgradeTask]:
        return self.ota_upgrade_service.initiate_rollback(task_id)

    def complete_rollback(self, task_id: str) -> Optional[UpgradeTask]:
        return self.ota_upgrade_service.complete_rollback(task_id)

    def cancel_task(self, task_id: str) -> bool:
        return self.ota_upgrade_service.cancel_task(task_id)

    def verify_package(self, package_id: str) -> bool:
        package = self.get_package(package_id)
        if not package:
            return False
        return self.ota_upgrade_service.verify_package(package)

    def get_upgrade_stats(self) -> Dict[str, Any]:
        return self.ota_upgrade_service.get_upgrade_stats()

    def start(self) -> None:
        self.ota_upgrade_service.start()

    def stop(self) -> None:
        self.ota_upgrade_service.stop()
