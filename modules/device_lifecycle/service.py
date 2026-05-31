import secrets
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from sqlalchemy import select, desc, func
from sqlalchemy.ext.asyncio import AsyncSession

from core import BaseRepository, NotFoundError, ConflictError, emit_event, EventTypes
from models import generate_uuid, utc_now
from .models import Device, DeviceAuth, DeviceHeartbeat
from .schemas import (
    DeviceCreate,
    DeviceUpdate,
    DeviceActivateRequest,
    DeviceHeartbeatRequest,
)


class DeviceRepository(BaseRepository):
    async def create(self, data: Dict[str, Any]) -> Device:
        device = Device(**data)
        self.db.add(device)
        await self.db.flush()
        return device

    async def get_by_id(self, device_id: str) -> Optional[Device]:
        result = await self.db.execute(
            select(Device).where(Device.device_id == device_id)
        )
        return result.scalar_one_or_none()

    async def get_by_internal_id(self, internal_id: str) -> Optional[Device]:
        result = await self.db.execute(
            select(Device).where(Device.id == internal_id)
        )
        return result.scalar_one_or_none()

    async def list(
        self,
        skip: int = 0,
        limit: int = 100,
        status: Optional[str] = None,
        device_model: Optional[str] = None,
        is_gateway: Optional[bool] = None,
    ) -> List[Device]:
        query = select(Device)
        if status:
            query = query.where(Device.status == status)
        if device_model:
            query = query.where(Device.device_model == device_model)
        if is_gateway is not None:
            query = query.where(Device.is_gateway == is_gateway)
        query = query.offset(skip).limit(limit).order_by(desc(Device.created_at))
        result = await self.db.execute(query)
        return list(result.scalars().all())

    async def update(self, device: Device, data: Dict[str, Any]) -> Device:
        for key, value in data.items():
            if value is not None:
                setattr(device, key, value)
        await self.db.flush()
        return device

    async def delete(self, device: Device) -> None:
        await self.db.delete(device)

    async def count_by_status(self) -> Dict[str, int]:
        result = await self.db.execute(
            select(Device.status, func.count(Device.id)).group_by(Device.status)
        )
        return {row[0]: row[1] for row in result.all()}


class DeviceAuthRepository(BaseRepository):
    async def create(self, data: Dict[str, Any]) -> DeviceAuth:
        auth = DeviceAuth(**data)
        self.db.add(auth)
        await self.db.flush()
        return auth

    async def get_by_device_id(self, device_id: str) -> Optional[DeviceAuth]:
        result = await self.db.execute(
            select(DeviceAuth)
            .where(DeviceAuth.device_id == device_id)
            .order_by(desc(DeviceAuth.created_at))
            .limit(1)
        )
        return result.scalar_one_or_none()

    async def update(self, auth: DeviceAuth, data: Dict[str, Any]) -> DeviceAuth:
        for key, value in data.items():
            if value is not None:
                setattr(auth, key, value)
        await self.db.flush()
        return auth


class DeviceHeartbeatRepository(BaseRepository):
    async def create(self, data: Dict[str, Any]) -> DeviceHeartbeat:
        heartbeat = DeviceHeartbeat(**data)
        self.db.add(heartbeat)
        await self.db.flush()
        return heartbeat

    async def get_recent_by_device(
        self, device_id: str, limit: int = 10
    ) -> List[DeviceHeartbeat]:
        result = await self.db.execute(
            select(DeviceHeartbeat)
            .where(DeviceHeartbeat.device_id == device_id)
            .order_by(desc(DeviceHeartbeat.timestamp))
            .limit(limit)
        )
        return list(result.scalars().all())


class DeviceService:
    def __init__(self, db: AsyncSession):
        self.device_repo = DeviceRepository(db)
        self.auth_repo = DeviceAuthRepository(db)
        self.heartbeat_repo = DeviceHeartbeatRepository(db)

    async def register_device(self, data: DeviceCreate) -> Device:
        existing = await self.device_repo.get_by_id(data.device_id)
        if existing:
            raise ConflictError(f"Device with id {data.device_id} already exists")

        device_dict = data.model_dump()
        device_dict["type"] = "device"
        device_dict["status"] = "inactive"
        device_dict["activation_status"] = "pending"
        device_dict["activation_code"] = secrets.token_urlsafe(16)

        device = await self.device_repo.create(device_dict)

        auth = await self.auth_repo.create({
            "device_id": data.device_id,
            "auth_type": "apikey",
            "api_key": secrets.token_urlsafe(32),
            "api_secret": secrets.token_urlsafe(64),
        })

        emit_event(
            EventTypes.DEVICE_REGISTERED,
            "device_service",
            {"device_id": data.device_id},
        )

        return device

    async def get_device(self, device_id: str) -> Device:
        device = await self.device_repo.get_by_id(device_id)
        if not device:
            raise NotFoundError("Device", device_id)
        return device

    async def list_devices(
        self,
        page: int = 1,
        page_size: int = 20,
        status: Optional[str] = None,
        device_model: Optional[str] = None,
        is_gateway: Optional[bool] = None,
    ) -> List[Device]:
        skip = (page - 1) * page_size
        return await self.device_repo.list(skip, page_size, status, device_model, is_gateway)

    async def update_device(self, device_id: str, data: DeviceUpdate) -> Device:
        device = await self.get_device(device_id)
        update_dict = data.model_dump(exclude_unset=True)
        return await self.device_repo.update(device, update_dict)

    async def delete_device(self, device_id: str) -> None:
        device = await self.get_device(device_id)
        await self.device_repo.delete(device)

    async def activate_device(self, data: DeviceActivateRequest) -> DeviceAuth:
        device = await self.get_device(data.device_id)

        if device.activation_code and data.activation_code != device.activation_code:
            raise ConflictError("Invalid activation code")

        device.activation_status = "activated"
        device.activated_at = utc_now()
        device.status = "online"
        device.last_seen_at = utc_now()

        if data.firmware_version:
            device.firmware_version = data.firmware_version
        if data.hardware_version:
            device.hardware_version = data.hardware_version

        await self.db.flush()

        auth = await self.auth_repo.get_by_device_id(data.device_id)
        if auth:
            auth.token = secrets.token_urlsafe(64)
            auth.token_expires_at = datetime.utcnow() + timedelta(days=365)
            auth.last_authenticated_at = utc_now()
            auth.auth_count += 1
            await self.db.flush()

        emit_event(
            EventTypes.DEVICE_ONLINE,
            "device_service",
            {"device_id": data.device_id},
        )

        return auth

    async def process_heartbeat(self, data: DeviceHeartbeatRequest) -> Device:
        device = await self.get_device(data.device_id)

        device.last_seen_at = utc_now()
        device.status = "online"

        await self.heartbeat_repo.create({
            "device_id": data.device_id,
            "status": data.status,
            "cpu_usage": data.cpu_usage,
            "memory_usage": data.memory_usage,
            "disk_usage": data.disk_usage,
            "network_usage": data.network_usage,
            "metrics": data.metrics,
        })

        emit_event(
            EventTypes.METRICS_REPORTED,
            "device_service",
            {
                "device_id": data.device_id,
                "cpu_usage": data.cpu_usage,
                "memory_usage": data.memory_usage,
            },
        )

        return device

    async def deactivate_device(self, device_id: str) -> Device:
        device = await self.get_device(device_id)
        device.activation_status = "deactivated"
        device.status = "offline"
        await self.db.flush()

        emit_event(
            EventTypes.DEVICE_OFFLINE,
            "device_service",
            {"device_id": device_id},
        )

        return device

    async def get_device_auth(self, device_id: str) -> DeviceAuth:
        await self.get_device(device_id)
        auth = await self.auth_repo.get_by_device_id(device_id)
        if not auth:
            raise NotFoundError("DeviceAuth", device_id)
        return auth

    async def get_device_heartbeats(
        self, device_id: str, limit: int = 10
    ) -> List[DeviceHeartbeat]:
        await self.get_device(device_id)
        return await self.heartbeat_repo.get_recent_by_device(device_id, limit)

    async def get_device_stats(self) -> Dict[str, Any]:
        count_by_status = await self.device_repo.count_by_status()
        total = sum(count_by_status.values())
        return {
            "total": total,
            "by_status": count_by_status,
            "online": count_by_status.get("online", 0),
            "offline": count_by_status.get("offline", 0),
            "inactive": count_by_status.get("inactive", 0),
        }

    async def check_offline_devices(self, timeout_seconds: int = 300) -> List[Device]:
        cutoff_time = datetime.utcnow() - timedelta(seconds=timeout_seconds)
        result = await self.db.execute(
            select(Device).where(
                Device.status == "online",
                Device.last_seen_at < cutoff_time,
            )
        )
        devices = list(result.scalars().all())

        for device in devices:
            device.status = "offline"
            emit_event(
                EventTypes.DEVICE_OFFLINE,
                "device_service",
                {"device_id": device.device_id, "reason": "heartbeat_timeout"},
            )

        await self.db.flush()
        return devices
