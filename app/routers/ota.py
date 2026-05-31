from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_async_db
from app.modules.ota_upgrade import OTAManager, OTAError
from app.modules.api_gateway import get_current_user, Permission, require_permission
from app.schemas import (
    FirmwareCreate,
    OTACampaignCreate,
    OTADeviceStatusUpdate,
    APIResponse
)
from app.logger import logger

router = APIRouter(prefix="/api/v1/ota", tags=["OTA Firmware"])


@router.post("/firmware", response_model=APIResponse)
async def create_firmware(
    data: FirmwareCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = OTAManager(db)
    
    try:
        firmware = await manager.create_firmware(
            version=data.version,
            device_model=data.device_model,
            file_path=data.file_path,
            release_notes=data.release_notes,
            diff_from_version=data.diff_from_version
        )
        await db.commit()
        
        return APIResponse(
            code=201,
            data={
                "id": firmware.id,
                "version": firmware.version,
                "device_model": firmware.device_model,
                "file_path": firmware.file_path,
                "file_size": firmware.file_size,
                "checksum": firmware.checksum
            }
        )
    except OTAError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )


@router.get("/firmware", response_model=APIResponse)
async def list_firmwares(
    device_model: str = None,
    limit: int = 100,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = OTAManager(db)
    firmwares = await manager.list_firmwares(device_model, limit)
    
    return APIResponse(
        code=200,
        data=[
            {
                "id": f.id,
                "version": f.version,
                "device_model": f.device_model,
                "file_size": f.file_size,
                "checksum": f.checksum,
                "is_enabled": f.is_enabled,
                "created_at": f.created_at.isoformat() if f.created_at else None
            }
            for f in firmwares
        ]
    )


@router.get("/firmware/{firmware_id}", response_model=APIResponse)
async def get_firmware(
    firmware_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = OTAManager(db)
    firmware = await manager.get_firmware(firmware_id)
    
    if not firmware:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Firmware not found"
        )
    
    return APIResponse(
        code=200,
        data={
            "id": firmware.id,
            "version": firmware.version,
            "device_model": firmware.device_model,
            "file_path": firmware.file_path,
            "file_size": firmware.file_size,
            "checksum": firmware.checksum,
            "is_enabled": firmware.is_enabled,
            "release_notes": firmware.release_notes,
            "created_at": firmware.created_at.isoformat() if firmware.created_at else None
        }
    )


@router.get("/firmware/latest/{device_model}", response_model=APIResponse)
async def get_latest_firmware(
    device_model: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = OTAManager(db)
    firmware = await manager.get_latest_firmware(device_model)
    
    if not firmware:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No firmware found for this device model"
        )
    
    return APIResponse(
        code=200,
        data={
            "id": firmware.id,
            "version": firmware.version,
            "device_model": firmware.device_model,
            "file_path": firmware.file_path,
            "checksum": firmware.checksum
        }
    )


@router.post("/campaigns", response_model=APIResponse)
async def create_campaign(
    data: OTACampaignCreate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = OTAManager(db)
    
    try:
        campaign = await manager.create_campaign(
            firmware_id=data.firmware_id,
            name=data.name,
            device_ids=data.device_ids,
            grayscale_percent=data.grayscale_percent,
            auto_rollback=data.auto_rollback
        )
        await db.commit()
        
        return APIResponse(
            code=201,
            data={
                "id": campaign.id,
                "name": campaign.name,
                "status": campaign.status,
                "total_devices": campaign.total_devices
            }
        )
    except OTAError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )


@router.post("/campaigns/{campaign_id}/start", response_model=APIResponse)
async def start_campaign(
    campaign_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.EXECUTE))
):
    manager = OTAManager(db)
    
    try:
        campaign = await manager.start_campaign(campaign_id)
        await db.commit()
        
        return APIResponse(
            code=200,
            data={
                "id": campaign.id,
                "status": campaign.status,
                "started_at": campaign.started_at.isoformat() if campaign.started_at else None
            }
        )
    except OTAError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )


@router.get("/campaigns/{campaign_id}/status", response_model=APIResponse)
async def get_campaign_status(
    campaign_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = OTAManager(db)
    
    try:
        status_data = await manager.get_campaign_status(campaign_id)
        return APIResponse(code=200, data=status_data)
    except OTAError as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e)
        )


@router.post("/campaigns/{campaign_id}/rollback", response_model=APIResponse)
async def rollback_campaign(
    campaign_id: str,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.EXECUTE))
):
    manager = OTAManager(db)
    
    try:
        campaign = await manager.rollback_campaign(campaign_id)
        await db.commit()
        
        return APIResponse(
            code=200,
            data={
                "id": campaign.id,
                "status": campaign.status
            }
        )
    except OTAError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e)
        )


@router.post("/campaigns/{campaign_id}/devices/{device_id}/status", response_model=APIResponse)
async def update_device_ota_status(
    campaign_id: str,
    device_id: str,
    data: OTADeviceStatusUpdate,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.WRITE))
):
    manager = OTAManager(db)
    
    try:
        ota_status = await manager.update_device_status(
            campaign_id=campaign_id,
            device_id=device_id,
            status=data.status,
            error_message=data.error_message,
            current_version=data.current_version
        )
        await db.commit()
        
        return APIResponse(
            code=200,
            data={
                "device_id": ota_status.device_id,
                "status": ota_status.status,
                "current_version": ota_status.current_version
            }
        )
    except OTAError as e:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(e)
        )


@router.get("/campaigns", response_model=APIResponse)
async def list_campaigns(
    status: str = None,
    limit: int = 100,
    db: AsyncSession = Depends(get_async_db),
    user: dict = Depends(require_permission(Permission.READ))
):
    manager = OTAManager(db)
    campaigns = await manager.list_campaigns(status, limit)
    
    return APIResponse(
        code=200,
        data=[
            {
                "id": c.id,
                "name": c.name,
                "status": c.status,
                "total_devices": c.total_devices,
                "success_count": c.success_count,
                "failed_count": c.failed_count
            }
            for c in campaigns
        ]
    )
