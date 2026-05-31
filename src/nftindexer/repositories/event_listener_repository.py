from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from ..db import async_session
from ..db.models import EventFilter, EventLog
from ..interfaces.repositories import IEventListenerRepository
from ..utils import get_logger

logger = get_logger(__name__)


class EventListenerRepository(IEventListenerRepository):
    def __init__(self, session: Optional[AsyncSession] = None):
        self._session = session

    async def _get_session(self) -> AsyncSession:
        if self._session:
            return self._session
        return async_session()

    async def create_filter(self, filter_obj: EventFilter) -> EventFilter:
        session = await self._get_session()
        session.add(filter_obj)
        await session.commit()
        await session.refresh(filter_obj)
        return filter_obj

    async def get_filter(self, filter_id: str) -> Optional[EventFilter]:
        session = await self._get_session()
        return await session.get(EventFilter, {"filter_id": filter_id})

    async def list_filters(
        self,
        chain_id: Optional[int] = None,
        is_active: Optional[bool] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Tuple[List[EventFilter], int]:
        session = await self._get_session()
        query = select(EventFilter)
        if chain_id:
            query = query.where(EventFilter.chain_id == chain_id)
        if is_active is not None:
            query = query.where(EventFilter.is_active == is_active)

        count_query = select(func.count()).select_from(query.subquery())
        total = await session.scalar(count_query) or 0

        query = query.order_by(EventFilter.created_at.desc()).offset(offset).limit(limit)
        result = await session.execute(query)
        filters = result.scalars().all()

        return list(filters), total

    async def list_active_filters(self) -> List[EventFilter]:
        session = await self._get_session()
        query = select(EventFilter).where(EventFilter.is_active == True)
        result = await session.execute(query)
        return list(result.scalars().all())

    async def update_filter_status(self, filter_id: str, is_active: bool) -> None:
        session = await self._get_session()
        f = await session.get(EventFilter, {"filter_id": filter_id})
        if f:
            f.is_active = is_active
            await session.commit()

    async def update_last_processed_block(self, filter_id: str, block_number: int) -> None:
        session = await self._get_session()
        f = await session.get(EventFilter, {"filter_id": filter_id})
        if f:
            f.last_processed_block = block_number
            await session.commit()

    async def record_filter_error(self, filter_id: str, error: str) -> None:
        session = await self._get_session()
        f = await session.get(EventFilter, {"filter_id": filter_id})
        if f:
            f.error_count += 1
            f.last_error = error
            await session.commit()

    async def delete_filter(self, filter_id: str) -> None:
        session = await self._get_session()
        f = await session.get(EventFilter, {"filter_id": filter_id})
        if f:
            await session.delete(f)
            await session.commit()

    async def create_event_log(self, log: EventLog) -> EventLog:
        session = await self._get_session()
        session.add(log)
        await session.commit()
        await session.refresh(log)
        return log

    async def mark_log_processed(self, log_id: str, error: Optional[str] = None) -> None:
        from datetime import datetime, timezone
        session = await self._get_session()
        log = await session.get(EventLog, {"log_id": log_id})
        if log:
            log.processed = True
            log.processed_at = datetime.now(timezone.utc)
            if error:
                log.processing_error = error
            await session.commit()

    async def list_event_logs(
        self, filter_id: str, offset: int = 0, limit: int = 50
    ) -> Tuple[List[EventLog], int]:
        session = await self._get_session()
        query = (
            select(EventLog)
            .where(EventLog.filter_id == filter_id)
            .order_by(EventLog.created_at.desc())
        )

        count_query = select(func.count()).select_from(query.subquery())
        total = await session.scalar(count_query) or 0

        query = query.offset(offset).limit(limit)
        result = await session.execute(query)
        logs = result.scalars().all()

        return list(logs), total
