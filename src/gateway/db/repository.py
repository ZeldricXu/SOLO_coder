from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple
from uuid import UUID
from sqlalchemy import select, and_, or_
from sqlalchemy.ext.asyncio import AsyncSession

from gateway.db.models import Route, APIKey, APIKeyUsage, IdPConfig, TransformRule
from gateway.logger import get_logger

logger = get_logger("repository")


class RouteRepository:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def get_all_active(self) -> List[Route]:
        result = await self.db.execute(
            select(Route).where(Route.is_active == True).order_by(Route.path)
        )
        return list(result.scalars().all())

    async def get_by_id(self, route_id: UUID) -> Optional[Route]:
        result = await self.db.execute(select(Route).where(Route.id == route_id))
        return result.scalar_one_or_none()

    async def get_by_name(self, name: str) -> Optional[Route]:
        result = await self.db.execute(select(Route).where(Route.name == name))
        return result.scalar_one_or_none()

    async def create(self, route_data: Dict[str, Any]) -> Route:
        route = Route(**route_data)
        self.db.add(route)
        await self.db.commit()
        await self.db.refresh(route)
        logger.info("Route created", route_id=str(route.id), name=route.name)
        return route

    async def update(self, route_id: UUID, route_data: Dict[str, Any]) -> Optional[Route]:
        route = await self.get_by_id(route_id)
        if not route:
            return None

        for key, value in route_data.items():
            if hasattr(route, key):
                setattr(route, key, value)
        route.version += 1
        route.updated_at = datetime.now(timezone.utc)

        await self.db.commit()
        await self.db.refresh(route)
        logger.info("Route updated", route_id=str(route.id), version=route.version)
        return route

    async def delete(self, route_id: UUID) -> bool:
        route = await self.get_by_id(route_id)
        if not route:
            return False

        route.is_active = False
        route.updated_at = datetime.now(timezone.utc)
        await self.db.commit()
        logger.info("Route deactivated", route_id=str(route_id))
        return True

    async def get_max_version(self) -> int:
        result = await self.db.execute(select(Route.version).order_by(Route.version.desc()).limit(1))
        row = result.scalar_one_or_none()
        return row or 0


class APIKeyRepository:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def get_by_key(self, key: str) -> Optional[APIKey]:
        result = await self.db.execute(
            select(APIKey).where(
                and_(
                    APIKey.key == key,
                    APIKey.status == "approved",
                    or_(APIKey.expires_at.is_(None), APIKey.expires_at > datetime.now(timezone.utc)),
                )
            )
        )
        return result.scalar_one_or_none()

    async def get_by_id(self, key_id: UUID) -> Optional[APIKey]:
        result = await self.db.execute(select(APIKey).where(APIKey.id == key_id))
        return result.scalar_one_or_none()

    async def list_by_user(self, user_id: str) -> List[APIKey]:
        result = await self.db.execute(
            select(APIKey).where(APIKey.user_id == user_id).order_by(APIKey.created_at.desc())
        )
        return list(result.scalars().all())

    async def create(self, key_data: Dict[str, Any]) -> APIKey:
        api_key = APIKey(**key_data)
        self.db.add(api_key)
        await self.db.commit()
        await self.db.refresh(api_key)
        logger.info("API Key created", key_id=str(api_key.id), user_id=api_key.user_id)
        return api_key

    async def update_status(self, key_id: UUID, status: str, approved_by: Optional[str] = None) -> Optional[APIKey]:
        api_key = await self.get_by_id(key_id)
        if not api_key:
            return None

        api_key.status = status
        if status == "approved" and approved_by:
            api_key.approved_by = approved_by
            api_key.approved_at = datetime.now(timezone.utc)
        api_key.updated_at = datetime.now(timezone.utc)

        await self.db.commit()
        await self.db.refresh(api_key)
        logger.info("API Key status updated", key_id=str(key_id), status=status)
        return api_key

    async def record_usage(self, key_id: UUID, date: datetime, request_count: int = 1,
                           error_count: int = 0, latency_ms: int = 0) -> None:
        date_start = date.replace(minute=0, second=0, microsecond=0)

        result = await self.db.execute(
            select(APIKeyUsage).where(
                and_(
                    APIKeyUsage.api_key_id == key_id,
                    APIKeyUsage.date == date_start,
                )
            )
        )
        usage = result.scalar_one_or_none()

        if usage:
            usage.request_count += request_count
            usage.error_count += error_count
            usage.total_latency_ms += latency_ms
        else:
            usage = APIKeyUsage(
                api_key_id=key_id,
                date=date_start,
                request_count=request_count,
                error_count=error_count,
                total_latency_ms=latency_ms,
            )
            self.db.add(usage)

        await self.db.commit()

    async def update_last_used(self, key_id: UUID) -> None:
        api_key = await self.get_by_id(key_id)
        if api_key:
            api_key.last_used_at = datetime.now(timezone.utc)
            await self.db.commit()


class IdPConfigRepository:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def get_all_active(self) -> List[IdPConfig]:
        result = await self.db.execute(
            select(IdPConfig).where(IdPConfig.is_active == True)
        )
        return list(result.scalars().all())

    async def get_by_name(self, name: str) -> Optional[IdPConfig]:
        result = await self.db.execute(
            select(IdPConfig).where(
                and_(
                    IdPConfig.name == name,
                    IdPConfig.is_active == True,
                )
            )
        )
        return result.scalar_one_or_none()


class TransformRuleRepository:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def get_all_active(self) -> List[TransformRule]:
        result = await self.db.execute(
            select(TransformRule).where(TransformRule.is_active == True).order_by(TransformRule.priority.desc())
        )
        return list(result.scalars().all())

    async def get_by_type(self, rule_type: str) -> List[TransformRule]:
        result = await self.db.execute(
            select(TransformRule).where(
                and_(
                    TransformRule.rule_type == rule_type,
                    TransformRule.is_active == True,
                )
            ).order_by(TransformRule.priority.desc())
        )
        return list(result.scalars().all())
