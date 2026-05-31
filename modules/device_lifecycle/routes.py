from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core import get_db
from models import ResponseModel, PaginatedResponse
from .schemas import (
    DeviceActivateRequest,
    DeviceCreate,
    DeviceHeartbeatRequest,
    DeviceResponse,
    DeviceUpdate,
    DeviceAuthResponse,
)
from .service import DeviceService

router = APIRouter(prefix="/api/v1/devices", tags=["Device Lifecycle"])


@router.post("", response_model=ResponseModel[DeviceResponse])
async def register_device(
    data: DeviceCreate,
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    device = await service.register_device(data)
    return ResponseModel(data=DeviceResponse.model_validate(device))


@router.get("", response_model=PaginatedResponse[DeviceResponse])
async def list_devices(
    page: int = 1,
    page_size: int = 20,
    status: Optional[str] = None,
    device_model: Optional[str] = None,
    is_gateway: Optional[bool] = None,
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    devices = await service.list_devices(page, page_size, status, device_model, is_gateway)
    return PaginatedResponse(
        data=[DeviceResponse.model_validate(d) for d in devices],
        total=len(devices),
        page=page,
        page_size=page_size,
    )


@router.get("/stats", response_model=ResponseModel[Dict[str, Any]])
async def get_device_stats(
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    stats = await service.get_device_stats()
    return ResponseModel(data=stats)


@router.get("/{device_id}", response_model=ResponseModel[DeviceResponse])
async def get_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    device = await service.get_device(device_id)
    return ResponseModel(data=DeviceResponse.model_validate(device))


@router.put("/{device_id}", response_model=ResponseModel[DeviceResponse])
async def update_device(
    device_id: str,
    data: DeviceUpdate,
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    device = await service.update_device(device_id, data)
    return ResponseModel(data=DeviceResponse.model_validate(device))


@router.delete("/{device_id}", response_model=ResponseModel)
async def delete_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    await service.delete_device(device_id)
    return ResponseModel(message="Device deleted successfully")


@router.post("/activate", response_model=ResponseModel[DeviceAuthResponse])
async def activate_device(
    data: DeviceActivateRequest,
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    auth = await service.activate_device(data)
    return ResponseModel(data=DeviceAuthResponse.model_validate(auth))


@router.post("/heartbeat", response_model=ResponseModel[DeviceResponse])
async def device_heartbeat(
    data: DeviceHeartbeatRequest,
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    device = await service.process_heartbeat(data)
    return ResponseModel(data=DeviceResponse.model_validate(device))


@router.post("/{device_id}/deactivate", response_model=ResponseModel[DeviceResponse])
async def deactivate_device(
    device_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    device = await service.deactivate_device(device_id)
    return ResponseModel(data=DeviceResponse.model_validate(device))


@router.get("/{device_id}/auth", response_model=ResponseModel[DeviceAuthResponse])
async def get_device_auth(
    device_id: str,
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    auth = await service.get_device_auth(device_id)
    return ResponseModel(data=DeviceAuthResponse.model_validate(auth))


@router.get("/{device_id}/heartbeats", response_model=ResponseModel[List[Dict[str, Any]]])
async def get_device_heartbeats(
    device_id: str,
    limit: int = Query(10, ge=1, le=100),
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    heartbeats = await service.get_device_heartbeats(device_id, limit)
    return ResponseModel(
        data=[
            {
                "timestamp": hb.timestamp,
                "status": hb.status,
                "cpu_usage": hb.cpu_usage,
                "memory_usage": hb.memory_usage,
                "disk_usage": hb.disk_usage,
                "metrics": hb.metrics,
            }
            for hb in heartbeats
        ]
    )


@router.post("/check-offline", response_model=ResponseModel[List[DeviceResponse]])
async def check_offline_devices(
    timeout_seconds: int = Query(300, ge=60),
    db: AsyncSession = Depends(get_db),
):
    service = DeviceService(db)
    devices = await service.check_offline_devices(timeout_seconds)
    return ResponseModel(
        data=[DeviceResponse.model_validate(d) for d in devices]
    )
