from typing import Optional, List
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from app.repositories.base import BaseRepository
from app.models.models import APIProvider


class APIProviderRepository(BaseRepository[APIProvider]):
    def __init__(self, session: AsyncSession):
        super().__init__(APIProvider, session)

    async def get_by_model_name(self, model_name: str) -> Optional[APIProvider]:
        result = await self.session.execute(
            select(APIProvider).where(APIProvider.model_name == model_name)
        )
        return result.scalar_one_or_none()

    async def get_all_active(self) -> List[APIProvider]:
        result = await self.session.execute(
            select(APIProvider)
            .where(APIProvider.is_active == True)
            .order_by(APIProvider.created_at.desc())
        )
        return list(result.scalars().all())

    async def get_all_ordered(self) -> List[APIProvider]:
        result = await self.session.execute(
            select(APIProvider).order_by(APIProvider.created_at.desc())
        )
        return list(result.scalars().all())
