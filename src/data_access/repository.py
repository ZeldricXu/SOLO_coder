from __future__ import annotations

import logging
from typing import Any, AsyncGenerator, Dict, Generic, List, Optional, Type, TypeVar

from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from src.common.database import Base
from src.common.exceptions import NotFoundError, ConflictError
from src.common.models import PaginationParams, Status
from src.common.utils import async_retry

logger = logging.getLogger(__name__)

T = TypeVar("T", bound=Base)
ID = TypeVar("ID", str, int)


class BaseRepository(Generic[T, ID]):
    def __init__(self, session: AsyncSession, model: Type[T]) -> None:
        self.session = session
        self.model = model

    @async_retry(max_attempts=3, exceptions=(ConflictError,))
    async def create(self, entity: T) -> T:
        try:
            self.session.add(entity)
            await self.session.flush()
            await self.session.refresh(entity)
            logger.info(f"Created {self.model.__name__}: {getattr(entity, 'id', 'unknown')}")
            return entity
        except Exception as e:
            logger.error(f"Failed to create {self.model.__name__}: {e}")
            raise ConflictError(f"Failed to create entity: {e}")

    async def get_by_id(self, entity_id: ID) -> Optional[T]:
        result = await self.session.execute(select(self.model).where(self.model.id == entity_id))
        return result.scalar_one_or_none()

    async def get_by_id_or_404(self, entity_id: ID) -> T:
        entity = await self.get_by_id(entity_id)
        if entity is None:
            raise NotFoundError(f"{self.model.__name__} with id {entity_id} not found")
        return entity

    async def list(
        self,
        filters: Optional[Dict[str, Any]] = None,
        pagination: Optional[PaginationParams] = None,
        order_by: Optional[Any] = None,
    ) -> List[T]:
        query = select(self.model)
        if filters:
            for key, value in filters.items():
                if hasattr(self.model, key):
                    query = query.where(getattr(self.model, key) == value)
        if order_by is not None:
            query = query.order_by(order_by)
        if pagination:
            query = query.offset(pagination.offset).limit(pagination.limit)
        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def count(self, filters: Optional[Dict[str, Any]] = None) -> int:
        query = select(func.count()).select_from(self.model)
        if filters:
            for key, value in filters.items():
                if hasattr(self.model, key):
                    query = query.where(getattr(self.model, key) == value)
        result = await self.session.execute(query)
        return result.scalar_one()

    @async_retry(max_attempts=3, exceptions=(ConflictError,))
    async def update(self, entity: T, data: Dict[str, Any]) -> T:
        for key, value in data.items():
            if hasattr(entity, key):
                setattr(entity, key, value)
        try:
            await self.session.flush()
            logger.info(f"Updated {self.model.__name__}: {getattr(entity, 'id', 'unknown')}")
            return entity
        except Exception as e:
            logger.error(f"Failed to update {self.model.__name__}: {e}")
            raise ConflictError(f"Failed to update entity: {e}")

    async def delete(self, entity: T) -> None:
        await self.session.delete(entity)
        await self.session.flush()
        logger.info(f"Deleted {self.model.__name__}: {getattr(entity, 'id', 'unknown')}")

    async def update_status(self, entity_id: ID, status: Status) -> T:
        entity = await self.get_by_id_or_404(entity_id)
        if hasattr(entity, "status"):
            entity.status = status
            await self.session.flush()
        return entity

    async def stream(
        self,
        filters: Optional[Dict[str, Any]] = None,
        batch_size: int = 100,
    ) -> AsyncGenerator[T, None]:
        query = select(self.model)
        if filters:
            for key, value in filters.items():
                if hasattr(self.model, key):
                    query = query.where(getattr(self.model, key) == value)
        query = query.order_by(self.model.id)
        offset = 0
        while True:
            batch_query = query.offset(offset).limit(batch_size)
            result = await self.session.execute(batch_query)
            batch = list(result.scalars().all())
            if not batch:
                break
            for entity in batch:
                yield entity
            offset += batch_size


class UnitOfWork:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session
        self._repositories: Dict[str, BaseRepository[Any, Any]] = {}

    def get_repository(self, model: Type[T]) -> BaseRepository[T, Any]:
        repo_key = model.__name__
        if repo_key not in self._repositories:
            self._repositories[repo_key] = BaseRepository(self.session, model)
        return self._repositories[repo_key]

    async def commit(self) -> None:
        await self.session.commit()

    async def rollback(self) -> None:
        await self.session.rollback()

    async def close(self) -> None:
        await self.session.close()
