from typing import Optional, List, Dict, Any
from datetime import datetime
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, func, desc
from app.repositories.base import BaseRepository
from app.models.models import RequestLog


class RequestLogRepository(BaseRepository[RequestLog]):
    def __init__(self, session: AsyncSession):
        super().__init__(RequestLog, session)

    async def create_log(
        self,
        model_name: Optional[str],
        duration_ms: float,
        status_code: int,
        endpoint: Optional[str] = None,
        method: Optional[str] = None,
        request_time: Optional[datetime] = None
    ) -> RequestLog:
        log = RequestLog(
            model_name=model_name,
            request_time=request_time,
            duration_ms=duration_ms,
            status_code=status_code,
            endpoint=endpoint,
            method=method
        )
        self.session.add(log)
        await self.session.commit()
        await self.session.refresh(log)
        return log

    async def get_by_model_name(
        self,
        model_name: str,
        limit: int = 100
    ) -> List[RequestLog]:
        result = await self.session.execute(
            select(RequestLog)
            .where(RequestLog.model_name == model_name)
            .order_by(desc(RequestLog.request_time))
            .limit(limit)
        )
        return list(result.scalars().all())

    async def get_paginated(
        self,
        limit: int = 100,
        offset: int = 0,
        model_name: Optional[str] = None
    ) -> List[RequestLog]:
        query = select(RequestLog)
        if model_name:
            query = query.where(RequestLog.model_name == model_name)
        query = query.order_by(desc(RequestLog.request_time)).limit(limit).offset(offset)
        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def get_stats(
        self,
        model_name: Optional[str] = None
    ) -> Dict[str, Any]:
        total_query = select(func.count(RequestLog.id))
        if model_name:
            total_query = total_query.where(RequestLog.model_name == model_name)
        total_result = await self.session.execute(total_query)
        total_count = total_result.scalar_one()

        avg_query = select(func.avg(RequestLog.duration_ms))
        if model_name:
            avg_query = avg_query.where(RequestLog.model_name == model_name)
        avg_result = await self.session.execute(avg_query)
        avg_duration = avg_result.scalar_one() or 0.0

        status_query = select(
            RequestLog.status_code,
            func.count(RequestLog.id).label("count")
        )
        if model_name:
            status_query = status_query.where(RequestLog.model_name == model_name)
        status_query = status_query.group_by(RequestLog.status_code)
        status_result = await self.session.execute(status_query)
        status_counts = {}
        for row in status_result:
            status_counts[str(row.status_code)] = row.count

        success_count = 0
        error_count = 0
        for status_code_str, count in status_counts.items():
            try:
                status_code = int(status_code_str)
                if 200 <= status_code < 400:
                    success_count += count
                else:
                    error_count += count
            except ValueError:
                error_count += count

        stream_query = select(
            func.count(RequestLog.id)
        )
        if model_name:
            stream_query = stream_query.where(RequestLog.model_name == model_name)

        model_stats_query = select(
            RequestLog.model_name,
            func.count(RequestLog.id).label("total"),
            func.avg(RequestLog.duration_ms).label("avg_duration")
        ).group_by(RequestLog.model_name)
        model_stats_result = await self.session.execute(model_stats_query)

        model_stats = []
        for row in model_stats_result:
            model_stats.append({
                "model_name": row.model_name or "unknown",
                "total_requests": row.total,
                "avg_duration_ms": round(row.avg_duration or 0.0, 2)
            })

        return {
            "total_count": total_count,
            "success_count": success_count,
            "error_count": error_count,
            "average_duration_ms": round(avg_duration, 2),
            "status_code_distribution": status_counts,
            "model_stats": model_stats,
            "model_name": model_name
        }
