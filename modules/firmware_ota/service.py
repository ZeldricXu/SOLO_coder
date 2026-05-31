from typing import Any, Dict, List, Optional
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from config import settings
from core import BaseRepository, NotFoundError, ValidationError
from models import generate_uuid, utc_now
from .models import (
    DeltaPackage,
    DeviceUpgradeRecord,
    FirmwareVersion,
    OTAUpgradeTask,
)
from .schemas import (
    DeltaGenerationRequest,
    FirmwareVersionCreate,
    FirmwareVersionUpdate,
    OTAUpgradeTaskCreate,
    OTAUpgradeTaskUpdate,
    UpgradeProgressUpdate,
    RollbackRequest,
)
from .diff_generator import DeltaGenerator
from .upgrade_manager import upgrade_manager


class FirmwareRepository(BaseRepository):
    async def create_firmware(self, data: Dict[str, Any]) -> FirmwareVersion:
        firmware = FirmwareVersion(**data)
        self.db.add(firmware)
        await self.db.flush()
        return firmware

    async def get_firmware(self, firmware_id: str) -> Optional[FirmwareVersion]:
        result = await self.db.execute(
            select(FirmwareVersion).where(FirmwareVersion.id == firmware_id)
        )
        return result.scalar_one_or_none()

    async def get_firmware_by_version(
        self, version: str, device_model: str
    ) -> Optional[FirmwareVersion]:
        result = await self.db.execute(
            select(FirmwareVersion).where(
                FirmwareVersion.version == version,
                FirmwareVersion.device_model == device_model,
            )
        )
        return result.scalar_one_or_none()

    async def list_firmware(
        self,
        device_model: Optional[str] = None,
        is_deprecated: Optional[bool] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> List[FirmwareVersion]:
        query = select(FirmwareVersion)
        if device_model:
            query = query.where(FirmwareVersion.device_model == device_model)
        if is_deprecated is not None:
            query = query.where(FirmwareVersion.is_deprecated == is_deprecated)
        query = query.offset(skip).limit(limit).order_by(FirmwareVersion.created_at.desc())
        result = await self.db.execute(query)
        return list(result.scalars().all())

    async def update_firmware(
        self, firmware: FirmwareVersion, data: Dict[str, Any]
    ) -> FirmwareVersion:
        for key, value in data.items():
            if value is not None:
                setattr(firmware, key, value)
        await self.db.flush()
        return firmware


class OTATaskRepository(BaseRepository):
    async def create_task(self, data: Dict[str, Any]) -> OTAUpgradeTask:
        task = OTAUpgradeTask(**data)
        self.db.add(task)
        await self.db.flush()
        return task

    async def get_task(self, task_id: str) -> Optional[OTAUpgradeTask]:
        result = await self.db.execute(
            select(OTAUpgradeTask).where(OTAUpgradeTask.id == task_id)
        )
        return result.scalar_one_or_none()

    async def list_tasks(
        self,
        status: Optional[str] = None,
        firmware_version_id: Optional[str] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> List[OTAUpgradeTask]:
        query = select(OTAUpgradeTask)
        if status:
            query = query.where(OTAUpgradeTask.status == status)
        if firmware_version_id:
            query = query.where(OTAUpgradeTask.firmware_version_id == firmware_version_id)
        query = query.offset(skip).limit(limit).order_by(OTAUpgradeTask.created_at.desc())
        result = await self.db.execute(query)
        return list(result.scalars().all())

    async def update_task(
        self, task: OTAUpgradeTask, data: Dict[str, Any]
    ) -> OTAUpgradeTask:
        for key, value in data.items():
            if value is not None:
                setattr(task, key, value)
        await self.db.flush()
        return task


class DeviceUpgradeRecordRepository(BaseRepository):
    async def create_record(self, data: Dict[str, Any]) -> DeviceUpgradeRecord:
        record = DeviceUpgradeRecord(**data)
        self.db.add(record)
        await self.db.flush()
        return record

    async def get_records_by_device(
        self, device_id: str, limit: int = 50
    ) -> List[DeviceUpgradeRecord]:
        result = await self.db.execute(
            select(DeviceUpgradeRecord)
            .where(DeviceUpgradeRecord.device_id == device_id)
            .order_by(DeviceUpgradeRecord.created_at.desc())
            .limit(limit)
        )
        return list(result.scalars().all())

    async def get_records_by_task(
        self, task_id: str
    ) -> List[DeviceUpgradeRecord]:
        result = await self.db.execute(
            select(DeviceUpgradeRecord)
            .where(DeviceUpgradeRecord.task_id == task_id)
        )
        return list(result.scalars().all())


class FirmwareOTAService:
    def __init__(self, db: AsyncSession):
        self.firmware_repo = FirmwareRepository(db)
        self.task_repo = OTATaskRepository(db)
        self.record_repo = DeviceUpgradeRecordRepository(db)
        self.delta_generator = DeltaGenerator(settings.ota_storage_path)
        self.upgrade_manager = upgrade_manager

    async def create_firmware(self, data: FirmwareVersionCreate) -> FirmwareVersion:
        existing = await self.firmware_repo.get_firmware_by_version(
            data.version, data.device_model
        )
        if existing:
            raise ValidationError(
                f"Firmware version {data.version} already exists for model {data.device_model}"
            )

        firmware_dict = data.model_dump()
        firmware_dict["type"] = "firmware"
        firmware_dict["status"] = "active"
        return await self.firmware_repo.create_firmware(firmware_dict)

    async def get_firmware(self, firmware_id: str) -> FirmwareVersion:
        firmware = await self.firmware_repo.get_firmware(firmware_id)
        if not firmware:
            raise NotFoundError("FirmwareVersion", firmware_id)
        return firmware

    async def list_firmware(
        self,
        device_model: Optional[str] = None,
        is_deprecated: Optional[bool] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> List[FirmwareVersion]:
        return await self.firmware_repo.list_firmware(
            device_model, is_deprecated, skip, limit
        )

    async def update_firmware(
        self, firmware_id: str, data: FirmwareVersionUpdate
    ) -> FirmwareVersion:
        firmware = await self.get_firmware(firmware_id)
        update_dict = data.model_dump(exclude_unset=True)
        return await self.firmware_repo.update_firmware(firmware, update_dict)

    async def generate_delta(self, data: DeltaGenerationRequest) -> Dict[str, Any]:
        from_firmware = await self.firmware_repo.get_firmware_by_version(
            data.from_version, data.device_model
        )
        to_firmware = await self.firmware_repo.get_firmware_by_version(
            data.to_version, data.device_model
        )

        if not from_firmware or not to_firmware:
            raise ValidationError("Firmware versions not found")

        delta_info = self.delta_generator.generate_delta_bsdiff(
            from_firmware.file_path,
            to_firmware.file_path,
        )

        delta = DeltaPackage(
            type="delta_package",
            status="available",
            delta_id=generate_uuid(),
            from_version=data.from_version,
            to_version=data.to_version,
            device_model=data.device_model,
            file_path=delta_info["file_path"],
            file_size=delta_info["file_size"],
            checksum=delta_info["checksum"],
            compression_method=delta_info["compression_method"],
        )
        self.db.add(delta)
        await self.db.flush()

        return delta_info

    async def create_upgrade_task(self, data: OTAUpgradeTaskCreate) -> OTAUpgradeTask:
        firmware = await self.get_firmware(data.firmware_version_id)

        task_dict = data.model_dump()
        task_dict["type"] = "ota_task"
        task_dict["status"] = "created"
        task_dict["phase"] = "pending"
        task_dict["run_id"] = generate_uuid()
        task_dict["total_count"] = len(data.device_ids)
        task_dict["progress"] = 0.0

        task = await self.task_repo.create_task(task_dict)

        for device_id in data.device_ids:
            await self.record_repo.create_record({
                "type": "upgrade_record",
                "status": "pending",
                "device_id": device_id,
                "task_id": task.id,
                "from_version": "unknown",
                "to_version": firmware.version,
                "phase": "pending",
                "progress": 0.0,
            })

        return task

    async def start_upgrade_task(self, task_id: str) -> OTAUpgradeTask:
        task = await self.task_repo.get_task(task_id)
        if not task:
            raise NotFoundError("OTAUpgradeTask", task_id)

        firmware = await self.get_firmware(task.firmware_version_id)

        await self.upgrade_manager.start_upgrade(
            task_id=task_id,
            device_ids=task.device_ids,
            firmware_info={
                "id": firmware.id,
                "version": firmware.version,
                "device_model": firmware.device_model,
                "file_path": firmware.file_path,
                "checksum": firmware.checksum,
            },
            strategy=task.strategy,
            batch_size=task.batch_size,
            auto_rollback=task.auto_rollback,
            rollback_threshold=task.rollback_threshold,
        )

        task.status = "running"
        task.phase = "upgrading"
        task.started_at = utc_now()
        await self.db.flush()

        return task

    async def get_task(self, task_id: str) -> OTAUpgradeTask:
        task = await self.task_repo.get_task(task_id)
        if not task:
            raise NotFoundError("OTAUpgradeTask", task_id)

        task_status = self.upgrade_manager.get_task_status(task_id)
        if task_status:
            task.progress = (task_status["success_count"] + task_status["failed_count"]) / max(1, task.total_count) * 100
            task.success_count = task_status["success_count"]
            task.failed_count = task_status["failed_count"]

        return task

    async def update_device_progress(self, data: UpgradeProgressUpdate) -> DeviceUpgradeRecord:
        self.upgrade_manager.update_device_progress(
            data.device_id, data.phase, data.progress, data.error_message
        )

        records = await self.record_repo.get_records_by_device(data.device_id)
        if not records:
            raise NotFoundError("DeviceUpgradeRecord", data.device_id)

        record = records[0]
        record.phase = data.phase
        record.progress = data.progress
        record.error_code = data.error_code
        record.error_message = data.error_message

        if data.phase == "completed":
            record.upgrade_completed_at = utc_now()
            record.status = "success"
        elif data.phase == "failed":
            record.status = "failed"

        await self.db.flush()
        return record

    async def rollback_upgrade(self, data: RollbackRequest) -> Dict[str, Any]:
        await self.upgrade_manager._trigger_rollback(data.task_id, data.device_ids)

        records = await self.record_repo.get_records_by_task(data.task_id)
        for record in records:
            if data.device_ids is None or record.device_id in data.device_ids:
                record.rollback_triggered = True
                record.status = "rollback"
                record.rollback_completed_at = utc_now()

        await self.db.flush()

        return {
            "task_id": data.task_id,
            "rollback_triggered": True,
            "device_count": len(data.device_ids) if data.device_ids else len(records),
        }

    async def get_device_upgrade_history(
        self, device_id: str, limit: int = 50
    ) -> List[DeviceUpgradeRecord]:
        return await self.record_repo.get_records_by_device(device_id, limit)
