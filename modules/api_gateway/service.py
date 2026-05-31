from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from sqlalchemy import select, func, desc, and_
from sqlalchemy.ext.asyncio import AsyncSession

from core import BaseRepository, NotFoundError
from .models import APIRateLimit, RequestLog, TraceSpan
from .schemas import (
    LogQueryRequest,
    MetricResponse,
    RateLimitConfigCreate,
)


class RequestLogRepository(BaseRepository):
    async def get_by_trace_id(self, trace_id: str) -> List[RequestLog]:
        result = await self.db.execute(
            select(RequestLog)
            .where(RequestLog.trace_id == trace_id)
            .order_by(RequestLog.started_at.asc())
        )
        return list(result.scalars().all())

    async def query(
        self,
        query_params: LogQueryRequest,
        skip: int = 0,
        limit: int = 100,
    ) -> List[RequestLog]:
        q = select(RequestLog)

        if query_params.trace_id:
            q = q.where(RequestLog.trace_id == query_params.trace_id)
        if query_params.service_name:
            q = q.where(RequestLog.service_name == query_params.service_name)
        if query_params.method:
            q = q.where(RequestLog.method == query_params.method)
        if query_params.path:
            q = q.where(RequestLog.path.like(f"%{query_params.path}%"))
        if query_params.status_code is not None:
            q = q.where(RequestLog.status_code == query_params.status_code)
        if query_params.start_time:
            q = q.where(RequestLog.started_at >= query_params.start_time)
        if query_params.end_time:
            q = q.where(RequestLog.started_at <= query_params.end_time)
        if query_params.user_id:
            q = q.where(RequestLog.user_id == query_params.user_id)
        if query_params.client_ip:
            q = q.where(RequestLog.client_ip == query_params.client_ip)

        q = q.order_by(desc(RequestLog.started_at)).offset(skip).limit(limit)
        result = await self.db.execute(q)
        return list(result.scalars().all())


class TraceSpanRepository(BaseRepository):
    async def get_by_trace_id(self, trace_id: str) -> List[TraceSpan]:
        result = await self.db.execute(
            select(TraceSpan)
            .where(TraceSpan.trace_id == trace_id)
            .order_by(TraceSpan.started_at.asc())
        )
        return list(result.scalars().all())

    async def get_by_span_id(self, span_id: str) -> Optional[TraceSpan]:
        result = await self.db.execute(
            select(TraceSpan).where(TraceSpan.span_id == span_id)
        )
        return result.scalar_one_or_none()


class RateLimitRepository(BaseRepository):
    async def create(self, data: Dict[str, Any]) -> APIRateLimit:
        rate_limit = APIRateLimit(**data)
        self.db.add(rate_limit)
        await self.db.flush()
        return rate_limit

    async def get_by_id(self, rate_limit_id: str) -> Optional[APIRateLimit]:
        result = await self.db.execute(
            select(APIRateLimit).where(APIRateLimit.id == rate_limit_id)
        )
        return result.scalar_one_or_none()

    async def list(self, skip: int = 0, limit: int = 100) -> List[APIRateLimit]:
        result = await self.db.execute(
            select(APIRateLimit).offset(skip).limit(limit)
        )
        return list(result.scalars().all())

    async def update(
        self, rate_limit: APIRateLimit, data: Dict[str, Any]
    ) -> APIRateLimit:
        for key, value in data.items():
            if value is not None:
                setattr(rate_limit, key, value)
        await self.db.flush()
        return rate_limit

    async def delete(self, rate_limit: APIRateLimit) -> None:
        await self.db.delete(rate_limit)


class APIGatewayService:
    def __init__(self, db: AsyncSession):
        self.log_repo = RequestLogRepository(db)
        self.span_repo = TraceSpanRepository(db)
        self.rate_limit_repo = RateLimitRepository(db)

    async def get_request_logs(
        self,
        query_params: LogQueryRequest,
        page: int = 1,
        page_size: int = 20,
    ) -> List[RequestLog]:
        skip = (page - 1) * page_size
        return await self.log_repo.query(query_params, skip, page_size)

    async def get_trace_detail(self, trace_id: str) -> Dict[str, Any]:
        spans = await self.span_repo.get_by_trace_id(trace_id)
        logs = await self.log_repo.get_by_trace_id(trace_id)

        if not spans and not logs:
            raise NotFoundError("Trace", trace_id)

        sorted_spans = sorted(spans, key=lambda s: s.started_at)
        start_time = sorted_spans[0].started_at if sorted_spans else None
        end_time = sorted_spans[-1].ended_at if sorted_spans else None

        total_duration = None
        if start_time and end_time:
            total_duration = (end_time - start_time).total_seconds() * 1000

        has_error = any(
            span.status == "error" for span in spans
        ) or any(
            log.status_code and log.status_code >= 400 for log in logs
        )

        return {
            "trace_id": trace_id,
            "spans": spans,
            "logs": logs,
            "total_duration_ms": total_duration,
            "start_time": start_time,
            "end_time": end_time,
            "status": "error" if has_error else "success",
        }

    async def get_metrics(self, hours: int = 24) -> MetricResponse:
        end_time = datetime.utcnow()
        start_time = end_time - timedelta(hours=hours)

        total_requests_q = select(func.count(RequestLog.id)).where(
            RequestLog.started_at >= start_time
        )
        success_count_q = select(func.count(RequestLog.id)).where(
            and_(
                RequestLog.started_at >= start_time,
                RequestLog.status_code >= 200,
                RequestLog.status_code < 400,
            )
        )
        error_count_q = select(func.count(RequestLog.id)).where(
            and_(
                RequestLog.started_at >= start_time,
                RequestLog.status_code >= 400,
            )
        )

        total_result = await self.db.execute(total_requests_q)
        success_result = await self.db.execute(success_count_q)
        error_result = await self.db.execute(error_count_q)

        total_requests = total_result.scalar() or 0
        success_count = success_result.scalar() or 0
        error_count = error_result.scalar() or 0

        durations_q = select(RequestLog.duration_ms).where(
            and_(
                RequestLog.started_at >= start_time,
                RequestLog.duration_ms.isnot(None),
            )
        )
        durations_result = await self.db.execute(durations_q)
        durations = [d for d in durations_result.scalars().all() if d is not None]

        avg_duration = sum(durations) / len(durations) if durations else 0.0
        p95_duration = self._percentile(durations, 95) if durations else 0.0
        p99_duration = self._percentile(durations, 99) if durations else 0.0

        minutes = max(1, hours * 60)
        requests_per_minute = total_requests / minutes

        top_endpoints_q = (
            select(
                RequestLog.path,
                RequestLog.method,
                func.count(RequestLog.id).label("count"),
            )
            .where(RequestLog.started_at >= start_time)
            .group_by(RequestLog.path, RequestLog.method)
            .order_by(desc("count"))
            .limit(10)
        )
        top_result = await self.db.execute(top_endpoints_q)
        top_endpoints = [
            {"path": row.path, "method": row.method, "count": row.count}
            for row in top_result.all()
        ]

        error_rates_q = (
            select(
                RequestLog.path,
                func.count(RequestLog.id).label("total"),
                func.sum(
                    func.case(
                        (RequestLog.status_code >= 400, 1),
                        else_=0,
                    )
                ).label("errors"),
            )
            .where(RequestLog.started_at >= start_time)
            .group_by(RequestLog.path)
            .having(func.sum(func.case((RequestLog.status_code >= 400, 1), else_=0)) > 0)
            .order_by(desc("errors"))
            .limit(5)
        )
        error_result = await self.db.execute(error_rates_q)
        error_rates = [
            {
                "path": row.path,
                "total": row.total,
                "errors": row.errors,
                "rate": (row.errors / row.total) * 100 if row.total > 0 else 0,
            }
            for row in error_result.all()
        ]

        return MetricResponse(
            total_requests=total_requests,
            success_count=success_count,
            error_count=error_count,
            avg_duration_ms=avg_duration,
            p95_duration_ms=p95_duration,
            p99_duration_ms=p99_duration,
            requests_per_minute=requests_per_minute,
            top_endpoints=top_endpoints,
            error_rates=error_rates,
            timestamp=end_time,
        )

    async def create_rate_limit(self, data: RateLimitConfigCreate) -> APIRateLimit:
        rate_limit_dict = data.model_dump()
        rate_limit_dict["enabled"] = str(data.enabled).lower()
        return await self.rate_limit_repo.create(rate_limit_dict)

    async def get_rate_limit(self, rate_limit_id: str) -> APIRateLimit:
        rate_limit = await self.rate_limit_repo.get_by_id(rate_limit_id)
        if not rate_limit:
            raise NotFoundError("RateLimit", rate_limit_id)
        return rate_limit

    async def list_rate_limits(
        self, skip: int = 0, limit: int = 100
    ) -> List[APIRateLimit]:
        return await self.rate_limit_repo.list(skip, limit)

    async def delete_rate_limit(self, rate_limit_id: str) -> None:
        rate_limit = await self.get_rate_limit(rate_limit_id)
        await self.rate_limit_repo.delete(rate_limit)

    @staticmethod
    def _percentile(sorted_values: List[float], percentile: int) -> float:
        if not sorted_values:
            return 0.0
        sorted_vals = sorted(sorted_values)
        k = (len(sorted_vals) - 1) * (percentile / 100.0)
        f = int(k)
        c = min(f + 1, len(sorted_vals) - 1)
        if f == c:
            return sorted_vals[f]
        return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)
