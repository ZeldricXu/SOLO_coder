from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from core import get_db
from models import ResponseModel, PaginatedResponse, StatusResponse
from .schemas import (
    DeltaGenerationRequest,
    FirmwareVersionCreate,
    FirmwareVersionResponse,
    FirmwareVersionUpdate,
    OTAUpgradeTaskCreate,
    OTAUpgradeTaskResponse,
    RollbackRequest,
    UpgradeProgressUpdate,
    DeviceUpgradeRecordResponse,
)
from .service import FirmwareOTAService

router = APIRouter(prefix="/api/v1/ota", tags=["Firmware OTA"])


@router.post("/firmware", response_model=ResponseModel[FirmwareVersionResponse], status_code=status.HTTP_201_CREATED)
async def create_firmware(
    data: FirmwareVersionCreate,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    firmware = await service.create_firmware(data)
    return ResponseModel(data=FirmwareVersionResponse.model_validate(firmware))


@router.get("/firmware", response_model=PaginatedResponse[FirmwareVersionResponse])
async def list_firmware(
    page: int = 1,
    page_size: int = 20,
    device_model: Optional[str] = None,
    is_deprecated: Optional[bool] = None,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    skip = (page - 1) * page_size
    firmware_list = await service.list_firmware(device_model, is_deprecated, skip, page_size)
    return PaginatedResponse(
        data=[FirmwareVersionResponse.model_validate(f) for f in firmware_list],
        total=len(firmware_list),
        page=page,
        page_size=page_size,
    )


@router.get("/firmware/{firmware_id}", response_model=ResponseModel[FirmwareVersionResponse])
async def get_firmware(
    firmware_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    firmware = await service.get_firmware(firmware_id)
    return ResponseModel(data=FirmwareVersionResponse.model_validate(firmware))


@router.put("/firmware/{firmware_id}", response_model=ResponseModel[FirmwareVersionResponse])
async def update_firmware(
    firmware_id: str,
    data: FirmwareVersionUpdate,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    firmware = await service.update_firmware(firmware_id, data)
    return ResponseModel(data=FirmwareVersionResponse.model_validate(firmware))


@router.post("/delta/generate", response_model=ResponseModel)
async def generate_delta(
    data: DeltaGenerationRequest,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    result = await service.generate_delta(data)
    return ResponseModel(data=result)


@router.post("/tasks", response_model=ResponseModel[OTAUpgradeTaskResponse], status_code=status.HTTP_201_CREATED)
async def create_upgrade_task(
    data: OTAUpgradeTaskCreate,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    task = await service.create_upgrade_task(data)
    return ResponseModel(data=OTAUpgradeTaskResponse.model_validate(task))


@router.post("/tasks/{task_id}/start", response_model=ResponseModel[OTAUpgradeTaskResponse])
async def start_upgrade_task(
    task_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    task = await service.start_upgrade_task(task_id)
    return ResponseModel(data=OTAUpgradeTaskResponse.model_validate(task))


@router.get("/tasks/{task_id}", response_model=ResponseModel[OTAUpgradeTaskResponse])
async def get_task(
    task_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    task = await service.get_task(task_id)
    return ResponseModel(data=OTAUpgradeTaskResponse.model_validate(task))


@router.get("/tasks/{task_id}/status", response_model=ResponseModel[StatusResponse])
async def get_task_status(
    task_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    task = await service.get_task(task_id)
    return ResponseModel(
        data=StatusResponse(
            id=task.id,
            status=task.status,
            progress=task.progress,
            phase=task.phase,
            updated_at=task.updated_at,
        )
    )


@router.post("/devices/progress", response_model=ResponseModel[DeviceUpgradeRecordResponse])
async def update_device_progress(
    data: UpgradeProgressUpdate,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    record = await service.update_device_progress(data)
    return ResponseModel(data=DeviceUpgradeRecordResponse.model_validate(record))


@router.post("/rollback", response_model=ResponseModel)
async def rollback_upgrade(
    data: RollbackRequest,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    result = await service.rollback_upgrade(data)
    return ResponseModel(data=result)


@router.get("/devices/{device_id}/history", response_model=ResponseModel[List[DeviceUpgradeRecordResponse]])
async def get_device_upgrade_history(
    device_id: str,
    limit: int = 50,
    db: AsyncSession = Depends(get_db),
):
    service = FirmwareOTAService(db)
    records = await service.get_device_upgrade_history(device_id, limit)
    return ResponseModel(
        data=[DeviceUpgradeRecordResponse.model_validate(r) for r in records]
    )
