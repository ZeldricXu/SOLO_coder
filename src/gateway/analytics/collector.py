from dataclasses import dataclass, field, asdict
from typing import Any, Dict, List, Optional
from datetime import datetime, timezone
import asyncio
import statistics

from gateway.config import get_settings
from gateway.db.clickhouse_client import insert_batch, get_clickhouse
from gateway.logger import get_logger

logger = get_logger("analytics")


@dataclass
class RequestRecord:
    timestamp: float
    request_id: str
    user_id: str
    tenant_id: str
    api_key: str
    api_path: str
    api_method: str
    route_name: str
    status_code: int
    latency_ms: int
    upstream_latency_ms: int
    client_ip: str
    user_agent: str
    error_type: str
    rate_limited: bool
    circuit_broken: bool
    tags: Dict[str, str] = field(default_factory=dict)

    def to_clickhouse_dict(self) -> Dict[str, Any]:
        return {
            "timestamp": datetime.fromtimestamp(self.timestamp, tz=timezone.utc),
            "request_id": self.request_id,
            "user_id": self.user_id,
            "tenant_id": self.tenant_id,
            "api_key": self.api_key,
            "api_path": self.api_path,
            "api_method": self.api_method,
            "route_name": self.route_name,
            "status_code": self.status_code,
            "latency_ms": self.latency_ms,
            "upstream_latency_ms": self.upstream_latency_ms,
            "client_ip": self.client_ip,
            "user_agent": self.user_agent,
            "error_type": self.error_type,
            "rate_limited": self.rate_limited,
            "circuit_broken": self.circuit_broken,
            "tags": self.tags,
        }


@dataclass
class LatencyStats:
    count: int = 0
    p50: float = 0.0
    p90: float = 0.0
    p95: float = 0.0
    p99: float = 0.0
    avg: float = 0.0
    min: float = 0.0
    max: float = 0.0


class AnalyticsCollector:
    def __init__(self):
        self.settings = get_settings()
        self.analytics_settings = self.settings.analytics
        self._buffer: List[RequestRecord] = []
        self._lock = asyncio.Lock()
        self._flush_task: Optional[asyncio.Task] = None
        self._running = False
        self._latency_buffer: Dict[str, List[int]] = {}

    async def start(self) -> None:
        if self._running or not self.analytics_settings.enabled:
            return

        self._running = True
        logger.info("Starting analytics collector",
                    batch_size=self.analytics_settings.batch_size,
                    flush_interval=self.analytics_settings.flush_interval)

        self._flush_task = asyncio.create_task(self._flush_loop())

    async def stop(self) -> None:
        self._running = False

        if self._flush_task:
            self._flush_task.cancel()
            try:
                await self._flush_task
            except asyncio.CancelledError:
                pass
            self._flush_task = None

        await self._flush_buffer(force=True)
        logger.info("Analytics collector stopped")

    async def collect(self, record: RequestRecord) -> None:
        if not self.analytics_settings.enabled:
            return

        async with self._lock:
            self._buffer.append(record)

            path_key = f"{record.api_method}:{record.api_path}"
            if path_key not in self._latency_buffer:
                self._latency_buffer[path_key] = []
            self._latency_buffer[path_key].append(record.latency_ms)
            if len(self._latency_buffer[path_key]) > 1000:
                self._latency_buffer[path_key] = self._latency_buffer[path_key][-1000:]

            if len(self._buffer) >= self.analytics_settings.batch_size:
                asyncio.create_task(self._flush_buffer())

    async def _flush_loop(self) -> None:
        while self._running:
            try:
                await asyncio.sleep(self.analytics_settings.flush_interval)
                await self._flush_buffer()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Error in analytics flush loop", error=str(e), exc_info=True)

    async def _flush_buffer(self, force: bool = False) -> None:
        if not self._buffer:
            return

        if not force and len(self._buffer) < self.analytics_settings.batch_size // 10:
            return

        async with self._lock:
            if not self._buffer:
                return

            records_to_flush = self._buffer.copy()
            self._buffer.clear()

        try:
            data = [r.to_clickhouse_dict() for r in records_to_flush]
            await insert_batch("api_requests", data)
            logger.debug("Flushed analytics records", count=len(records_to_flush))

        except Exception as e:
            logger.error("Failed to flush analytics records", error=str(e), count=len(records_to_flush))

            async with self._lock:
                self._buffer = records_to_flush + self._buffer
                if len(self._buffer) > self.analytics_settings.batch_size * 10:
                    self._buffer = self._buffer[-self.analytics_settings.batch_size * 10:]

    def get_latency_stats(self, api_path: Optional[str] = None, method: Optional[str] = None) -> Dict[str, LatencyStats]:
        results = {}

        for key, latencies in self._latency_buffer.items():
            if not latencies:
                continue

            path_method, path = key.split(":", 1)

            if method and method != path_method:
                continue
            if api_path and not path.startswith(api_path):
                continue

            sorted_latencies = sorted(latencies)
            stats = LatencyStats(
                count=len(sorted_latencies),
                p50=self._percentile(sorted_latencies, 50),
                p90=self._percentile(sorted_latencies, 90),
                p95=self._percentile(sorted_latencies, 95),
                p99=self._percentile(sorted_latencies, 99),
                avg=sum(sorted_latencies) / len(sorted_latencies),
                min=min(sorted_latencies),
                max=max(sorted_latencies),
            )
            results[key] = stats

        return results

    def _percentile(self, sorted_data: List[int], percentile: int) -> float:
        if not sorted_data:
            return 0.0

        k = (len(sorted_data) - 1) * (percentile / 100)
        f = int(k)
        c = f + 1

        if f == c:
            return float(sorted_data[int(k)])

        d0 = sorted_data[f] * (c - k)
        d1 = sorted_data[c] * (k - f)
        return d0 + d1

    async def get_top_apis(self, limit: int = 10, hours: int = 24) -> List[Dict[str, Any]]:
        try:
            client = get_clickhouse()
            query = f"""
                SELECT
                    api_path,
                    api_method,
                    count() AS request_count,
                    countIf(status_code >= 400) AS error_count,
                    avg(latency_ms) AS avg_latency,
                    quantile(0.95)(latency_ms) AS p95_latency
                FROM api_requests
                WHERE timestamp >= now() - INTERVAL {hours} HOUR
                GROUP BY api_path, api_method
                ORDER BY request_count DESC
                LIMIT {limit}
            """
            result = await client.query(query)
            return [
                {
                    "api_path": row[0],
                    "api_method": row[1],
                    "request_count": row[2],
                    "error_count": row[3],
                    "error_rate": round(row[3] / row[2] * 100, 2) if row[2] > 0 else 0,
                    "avg_latency_ms": round(row[4], 2),
                    "p95_latency_ms": round(row[5], 2),
                }
                for row in result.result_rows
            ]
        except Exception as e:
            logger.error("Failed to get top APIs", error=str(e))
            return []

    async def get_usage_summary(self, user_id: Optional[str] = None, hours: int = 24) -> Dict[str, Any]:
        try:
            client = get_clickhouse()
            where_clause = f"WHERE timestamp >= now() - INTERVAL {hours} HOUR"
            if user_id:
                where_clause += f" AND user_id = '{user_id}'"

            query = f"""
                SELECT
                    count() AS total_requests,
                    countIf(status_code >= 200 AND status_code < 400) AS success_requests,
                    countIf(status_code >= 400 AND status_code < 500) AS client_errors,
                    countIf(status_code >= 500) AS server_errors,
                    countIf(rate_limited = true) AS rate_limited_count,
                    countIf(circuit_broken = true) AS circuit_broken_count,
                    avg(latency_ms) AS avg_latency,
                    quantile(0.5)(latency_ms) AS p50_latency,
                    quantile(0.9)(latency_ms) AS p90_latency,
                    quantile(0.95)(latency_ms) AS p95_latency,
                    quantile(0.99)(latency_ms) AS p99_latency
                FROM api_requests
                {where_clause}
            """
            result = await client.query(query)
            if not result.result_rows:
                return {}

            row = result.result_rows[0]
            total = row[0]

            return {
                "time_range_hours": hours,
                "total_requests": row[0],
                "success_requests": row[1],
                "success_rate": round(row[1] / total * 100, 2) if total > 0 else 0,
                "client_errors": row[2],
                "server_errors": row[3],
                "error_rate": round((row[2] + row[3]) / total * 100, 2) if total > 0 else 0,
                "rate_limited_count": row[4],
                "circuit_broken_count": row[5],
                "avg_latency_ms": round(row[6], 2),
                "p50_latency_ms": round(row[7], 2),
                "p90_latency_ms": round(row[8], 2),
                "p95_latency_ms": round(row[9], 2),
                "p99_latency_ms": round(row[10], 2),
            }
        except Exception as e:
            logger.error("Failed to get usage summary", error=str(e))
            return {}

    async def get_user_usage(self, user_id: str, days: int = 7) -> List[Dict[str, Any]]:
        try:
            client = get_clickhouse()
            query = f"""
                SELECT
                    toDate(timestamp) AS date,
                    count() AS request_count,
                    countIf(status_code >= 400) AS error_count,
                    avg(latency_ms) AS avg_latency
                FROM api_requests
                WHERE user_id = '{user_id}'
                  AND timestamp >= now() - INTERVAL {days} DAY
                GROUP BY date
                ORDER BY date
            """
            result = await client.query(query)
            return [
                {
                    "date": row[0].isoformat() if hasattr(row[0], "isoformat") else str(row[0]),
                    "request_count": row[1],
                    "error_count": row[2],
                    "error_rate": round(row[2] / row[1] * 100, 2) if row[1] > 0 else 0,
                    "avg_latency_ms": round(row[3], 2),
                }
                for row in result.result_rows
            ]
        except Exception as e:
            logger.error("Failed to get user usage", error=str(e))
            return []


_collector_instance: Optional[AnalyticsCollector] = None


def get_analytics_collector() -> AnalyticsCollector:
    global _collector_instance
    if _collector_instance is None:
        _collector_instance = AnalyticsCollector()
    return _collector_instance
