from typing import Optional, Dict, Any, List
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from datetime import datetime

from application.services.device_service import DeviceService

router = APIRouter(prefix="/devices", tags=["devices"])


class DeviceCreateRequest(BaseModel):
    device_id: str
    name: str
    device_type: str
    protocol: str
    metadata: Optional[Dict[str, Any]] = None


class DeviceActivateRequest(BaseModel):
    credentials: Dict[str, Any]


class DeviceDataWriteRequest(BaseModel):
    data: Dict[str, Any]


class DeviceCommandRequest(BaseModel):
    command: str
    params: Optional[Dict[str, Any]] = None


class DeviceShadowUpdateRequest(BaseModel):
    desired_state: Dict[str, Any]


class DeviceTelemetryRequest(BaseModel):
    data: Dict[str, Any]
    timestamp: Optional[datetime] = None


_device_service: Optional[DeviceService] = None


def set_device_service(service: DeviceService) -> None:
    global _device_service
    _device_service = service


def get_device_service() -> DeviceService:
    if _device_service is None:
        raise RuntimeError("DeviceService not initialized")
    return _device_service


@router.post("")
def create_device(request: DeviceCreateRequest):
    service = get_device_service()
    try:
        device = service.register_device(
            device_id=request.device_id,
            name=request.name,
            device_type=request.device_type,
            protocol=request.protocol,
            metadata=request.metadata,
        )
        return {
            "device_id": device.device_id,
            "name": device.name,
            "status": device.status.value,
            "message": "Device registered successfully",
        }
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("")
def list_devices(
    status: Optional[str] = None,
    device_type: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
):
    service = get_device_service()
    devices = service.list_devices(status=status, device_type=device_type, limit=limit, offset=offset)
    return {
        "devices": [
            {
                "device_id": d.device_id,
                "name": d.name,
                "device_type": d.device_type,
                "status": d.status.value,
                "protocol": d.protocol.value if hasattr(d.protocol, "value") else d.protocol,
                "last_seen": d.last_seen.isoformat() if d.last_seen else None,
            }
            for d in devices
        ],
        "count": len(devices),
    }


@router.get("/{device_id}")
def get_device(device_id: str):
    service = get_device_service()
    device = service.get_device(device_id)
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    return {
        "device_id": device.device_id,
        "name": device.name,
        "device_type": device.device_type,
        "status": device.status.value,
        "protocol": device.protocol.value if hasattr(device.protocol, "value") else device.protocol,
        "metadata": device.metadata,
        "firmware_version": device.firmware_version,
        "last_seen": device.last_seen.isoformat() if device.last_seen else None,
        "activated_at": device.activated_at.isoformat() if device.activated_at else None,
    }


@router.post("/{device_id}/activate")
def activate_device(device_id: str, request: DeviceActivateRequest):
    service = get_device_service()
    success = service.activate_device(device_id, request.credentials)
    if not success:
        raise HTTPException(status_code=401, detail="Activation failed")
    return {"message": "Device activated successfully"}


@router.post("/{device_id}/deactivate")
def deactivate_device(device_id: str):
    service = get_device_service()
    success = service.deactivate_device(device_id)
    if not success:
        raise HTTPException(status_code=404, detail="Device not found")
    return {"message": "Device deactivated successfully"}


@router.delete("/{device_id}")
def delete_device(device_id: str):
    service = get_device_service()
    success = service.delete_device(device_id)
    if not success:
        raise HTTPException(status_code=404, detail="Device not found")
    return {"message": "Device deleted successfully"}


@router.post("/{device_id}/online")
def mark_device_online(device_id: str, connection_info: Optional[Dict[str, Any]] = None):
    service = get_device_service()
    success = service.mark_device_online(device_id, connection_info)
    if not success:
        raise HTTPException(status_code=404, detail="Device not found")
    return {"message": "Device marked as online"}


@router.post("/{device_id}/offline")
def mark_device_offline(device_id: str):
    service = get_device_service()
    success = service.mark_device_offline(device_id)
    if not success:
        raise HTTPException(status_code=404, detail="Device not found")
    return {"message": "Device marked as offline"}


@router.get("/{device_id}/data")
def read_device_data(device_id: str, points: str = Query(..., description="Comma-separated list of data points")):
    service = get_device_service()
    point_list = [p.strip() for p in points.split(",")]
    data = service.read_device_data(device_id, point_list)
    return {"device_id": device_id, "data": data}


@router.post("/{device_id}/data")
def write_device_data(device_id: str, request: DeviceDataWriteRequest):
    service = get_device_service()
    success = service.write_device_data(device_id, request.data)
    if not success:
        raise HTTPException(status_code=400, detail="Failed to write data")
    return {"message": "Data written successfully"}


@router.post("/{device_id}/command")
def send_device_command(device_id: str, request: DeviceCommandRequest):
    service = get_device_service()
    result = service.send_device_command(device_id, request.command, request.params or {})
    return {"device_id": device_id, "command": request.command, "result": result}


@router.get("/{device_id}/shadow")
def get_device_shadow(device_id: str):
    service = get_device_service()
    shadow = service.get_device_shadow(device_id)
    if not shadow:
        raise HTTPException(status_code=404, detail="Device shadow not found")
    return shadow


@router.patch("/{device_id}/shadow")
def update_device_shadow(device_id: str, request: DeviceShadowUpdateRequest):
    service = get_device_service()
    delta = service.update_device_desired_state(device_id, request.desired_state)
    return {"device_id": device_id, "delta": delta}


@router.post("/{device_id}/telemetry")
def process_telemetry(device_id: str, request: DeviceTelemetryRequest):
    service = get_device_service()
    service.process_device_telemetry(device_id, request.data)
    return {"message": "Telemetry processed successfully"}


@router.get("/{device_id}/rules")
def get_device_rules(device_id: str):
    service = get_device_service()
    rules = service.get_device_rules(device_id)
    return {"device_id": device_id, "rules": rules}
