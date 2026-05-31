from typing import Any, Dict, List, Optional
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from models import ResponseModel
from .drivers import DriverFactory
from .manager import protocol_adapter_manager

router = APIRouter(prefix="/api/v1/protocol", tags=["Protocol Adapter"])


class EndpointCreate(BaseModel):
    endpoint_id: str
    driver_type: str
    config: Dict[str, Any] = Field(default_factory=dict)
    polling_interval: int = 1000
    points: List[Dict[str, Any]] = Field(default_factory=list)
    transformations: Dict[str, Any] = Field(default_factory=dict)


class EndpointUpdate(BaseModel):
    config: Optional[Dict[str, Any]] = None
    polling_interval: Optional[int] = None
    points: Optional[List[Dict[str, Any]]] = None
    enabled: Optional[bool] = None


class WritePointRequest(BaseModel):
    value: Any


@router.get("/drivers", response_model=ResponseModel[List[str]])
async def list_drivers():
    return ResponseModel(data=DriverFactory.available_drivers())


@router.post("/endpoints", response_model=ResponseModel[Dict[str, Any]])
async def create_endpoint(data: EndpointCreate):
    endpoint = protocol_adapter_manager.add_endpoint(
        endpoint_id=data.endpoint_id,
        driver_type=data.driver_type,
        config=data.config,
        polling_interval=data.polling_interval,
        points=data.points,
        transformations=data.transformations,
    )
    return ResponseModel(data=endpoint.to_dict())


@router.get("/endpoints", response_model=ResponseModel[List[Dict[str, Any]]])
async def list_endpoints():
    endpoints = protocol_adapter_manager.list_endpoints()
    return ResponseModel(data=endpoints)


@router.get("/endpoints/{endpoint_id}", response_model=ResponseModel[Dict[str, Any]])
async def get_endpoint(endpoint_id: str):
    endpoint = protocol_adapter_manager.get_endpoint(endpoint_id)
    if not endpoint:
        raise HTTPException(status_code=404, detail="Endpoint not found")
    return ResponseModel(data=endpoint.to_dict())


@router.delete("/endpoints/{endpoint_id}", response_model=ResponseModel)
async def delete_endpoint(endpoint_id: str):
    protocol_adapter_manager.remove_endpoint(endpoint_id)
    return ResponseModel(message="Endpoint deleted successfully")


@router.post("/endpoints/{endpoint_id}/connect", response_model=ResponseModel[Dict[str, Any]])
async def connect_endpoint(endpoint_id: str):
    connected = await protocol_adapter_manager.connect_endpoint(endpoint_id)
    endpoint = protocol_adapter_manager.get_endpoint(endpoint_id)
    return ResponseModel(data={"connected": connected, "endpoint": endpoint.to_dict() if endpoint else None})


@router.post("/endpoints/{endpoint_id}/disconnect", response_model=ResponseModel)
async def disconnect_endpoint(endpoint_id: str):
    await protocol_adapter_manager.disconnect_endpoint(endpoint_id)
    return ResponseModel(message="Disconnected successfully")


@router.post("/endpoints/{endpoint_id}/polling/start", response_model=ResponseModel)
async def start_polling(endpoint_id: str):
    await protocol_adapter_manager.start_polling(endpoint_id)
    return ResponseModel(message="Polling started successfully")


@router.post("/endpoints/{endpoint_id}/polling/stop", response_model=ResponseModel)
async def stop_polling(endpoint_id: str):
    await protocol_adapter_manager.stop_polling(endpoint_id)
    return ResponseModel(message="Polling stopped successfully")


@router.get("/endpoints/{endpoint_id}/read", response_model=ResponseModel[Dict[str, Any]])
async def read_all_points(endpoint_id: str):
    values = await protocol_adapter_manager.read_all(endpoint_id)
    return ResponseModel(data=values)


@router.get("/endpoints/{endpoint_id}/points/{point_id}", response_model=ResponseModel[Any])
async def read_point(endpoint_id: str, point_id: str):
    value = await protocol_adapter_manager.read_point(endpoint_id, point_id)
    return ResponseModel(data=value)


@router.post("/endpoints/{endpoint_id}/points/{point_id}", response_model=ResponseModel[Dict[str, Any]])
async def write_point(endpoint_id: str, point_id: str, data: WritePointRequest):
    success = await protocol_adapter_manager.write_point(endpoint_id, point_id, data.value)
    return ResponseModel(data={"success": success})


@router.get("/endpoints/{endpoint_id}/values", response_model=ResponseModel[Dict[str, Any]])
async def get_last_values(endpoint_id: str):
    values = protocol_adapter_manager.get_last_values(endpoint_id)
    return ResponseModel(data=values)
