from typing import Any, Dict, Generic, List, Optional, Type, TypeVar
from sqlalchemy import select, func, desc
from sqlalchemy.ext.asyncio import AsyncSession

from core import BaseRepository, NotFoundError
from models import BaseModel

ModelType = TypeVar("ModelType", bound=BaseModel)


class GenericRepository(Generic[ModelType], BaseRepository):
    def __init__(self, db: AsyncSession, model: Type[ModelType]):
        super().__init__(db)
        self.model = model

    async def create(self, data: Dict[str, Any]) -> ModelType:
        instance = self.model(**data)
        self.db.add(instance)
        await self.db.flush()
        return instance

    async def get_by_id(self, instance_id: str) -> Optional[ModelType]:
        result = await self.db.execute(
            select(self.model).where(self.model.id == instance_id)
        )
        return result.scalar_one_or_none()

    async def get_or_create(
        self, instance_id: str, default_data: Optional[Dict[str, Any]] = None
    ) -> ModelType:
        instance = await self.get_by_id(instance_id)
        if instance:
            return instance
        if default_data:
            return await self.create(default_data)
        raise NotFoundError(self.model.__name__, instance_id)

    async def list(
        self, skip: int = 0, limit: int = 100, filters: Optional[Dict[str, Any]] = None
    ) -> List[ModelType]:
        query = select(self.model)
        if filters:
            for key, value in filters.items():
                if hasattr(self.model, key):
                    query = query.where(getattr(self.model, key) == value)
        query = query.offset(skip).limit(limit).order_by(desc(self.model.created_at))
        result = await self.db.execute(query)
        return list(result.scalars().all())

    async def update(
        self, instance: ModelType, data: Dict[str, Any]
    ) -> ModelType:
        for key, value in data.items():
            if value is not None and hasattr(instance, key):
                setattr(instance, key, value)
        await self.db.flush()
        return instance

    async def delete(self, instance: ModelType) -> None:
        await self.db.delete(instance)

    async def count(self, filters: Optional[Dict[str, Any]] = None) -> int:
        query = select(func.count(self.model.id))
        if filters:
            for key, value in filters.items():
                if hasattr(self.model, key):
                    query = query.where(getattr(self.model, key) == value)
        result = await self.db.execute(query)
        return result.scalar() or 0

    async def exists(self, instance_id: str) -> bool:
        result = await self.db.execute(
            select(func.count(self.model.id)).where(self.model.id == instance_id)
        )
        return (result.scalar() or 0) > 0

    async def bulk_create(self, data_list: List[Dict[str, Any]]) -> List[ModelType]:
        instances = [self.model(**data) for data in data_list]
        self.db.add_all(instances)
        await self.db.flush()
        return instances

    async def bulk_update(self, instances: List[ModelType], data: Dict[str, Any]) -> None:
        for instance in instances:
            for key, value in data.items():
                if value is not None and hasattr(instance, key):
                    setattr(instance, key, value)
        await self.db.flush()

    async def bulk_delete(self, instances: List[ModelType]) -> None:
        for instance in instances:
            await self.db.delete(instance)
        await self.db.flush()


class CachedRepository(Generic[ModelType], GenericRepository[ModelType]):
    def __init__(
        self,
        db: AsyncSession,
        model: Type[ModelType],
        cache_manager: Optional["CacheManager"] = None,
        cache_ttl: int = 300,
    ):
        super().__init__(db, model)
        from .cache_manager import CacheManager, cache_manager as default_cache
        self._cache = cache_manager or default_cache
        self._cache_ttl = cache_ttl

    def _get_cache_key(self, instance_id: str) -> str:
        return f"{self.model.__name__}:{instance_id}"

    async def get_by_id(self, instance_id: str) -> Optional[ModelType]:
        cache_key = self._get_cache_key(instance_id)
        cached = await self._cache.get(cache_key)
        if cached:
            return cached

        instance = await super().get_by_id(instance_id)
        if instance:
            await self._cache.set(cache_key, instance, self._cache_ttl)
        return instance

    async def create(self, data: Dict[str, Any]) -> ModelType:
        instance = await super().create(data)
        cache_key = self._get_cache_key(instance.id)
        await self._cache.set(cache_key, instance, self._cache_ttl)
        return instance

    async def update(
        self, instance: ModelType, data: Dict[str, Any]
    ) -> ModelType:
        updated = await super().update(instance, data)
        cache_key = self._get_cache_key(updated.id)
        await self._cache.set(cache_key, updated, self._cache_ttl)
        return updated

    async def delete(self, instance: ModelType) -> None:
        cache_key = self._get_cache_key(instance.id)
        await self._cache.delete(cache_key)
        await super().delete(instance)

    async def invalidate_cache(self, instance_id: str) -> None:
        cache_key = self._get_cache_key(instance_id)
        await self._cache.delete(cache_key)

    async def clear_cache(self) -> None:
        await self._cache.invalidate_pattern(f"{self.model.__name__}:")
