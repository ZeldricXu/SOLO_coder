from typing import Optional, List, Dict, Any, Tuple, AsyncGenerator
from uuid import UUID
from datetime import datetime, timezone, timedelta
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, and_, func, text
from collections import defaultdict, deque
import time
import json
import asyncio
import hashlib
from prometheus_client import Counter, Gauge, Histogram, CollectorRegistry, generate_latest, CONTENT_TYPE_LATEST

from app.models import MetricSnapshot, AuditLog
from app.schemas import (
    MetricSnapshotCreate,
    MetricsQuery,
    AuditLogCreate,
)
from app.logging import get_logger, log_operation
from app.exceptions import ValidationError, NotFoundError

logger = get_logger(__name__)

DEFAULT_BATCH_SIZE = 1000
MAX_BATCH_SIZE = 5000
CACHE_TTL_SECONDS = 60
MAX_CACHE_ENTRIES = 1000


class QueryCache:
    def __init__(self, ttl_seconds: int = CACHE_TTL_SECONDS, max_entries: int = MAX_CACHE_ENTRIES):
        self._cache: Dict[str, Tuple[float, Any, Dict[str, Any]]] = {}
        self._access_order = deque()
        self._ttl = ttl_seconds
        self._max_entries = max_entries
        self._lock = asyncio.Lock()

    def _generate_key(self, query: Dict[str, Any]) -> str:
        def _serialize(obj: Any) -> Any:
            if isinstance(obj, datetime):
                return obj.isoformat()
            if isinstance(obj, UUID):
                return str(obj)
            raise TypeError(f"Object of type {obj.__class__.__name__} is not JSON serializable")
        
        query_str = json.dumps(query, sort_keys=True, default=_serialize)
        return hashlib.md5(query_str.encode()).hexdigest()

    async def get(self, query: Dict[str, Any]) -> Optional[Any]:
        key = self._generate_key(query)
        async with self._lock:
            if key not in self._cache:
                return None

            timestamp, value, _ = self._cache[key]
            if time.time() - timestamp > self._ttl:
                del self._cache[key]
                self._access_order.remove(key)
                return None

            self._access_order.remove(key)
            self._access_order.append(key)
            return value

    async def set(self, query: Dict[str, Any], value: Any) -> None:
        key = self._generate_key(query)
        async with self._lock:
            if key in self._cache:
                self._access_order.remove(key)
            elif len(self._cache) >= self._max_entries:
                oldest_key = self._access_order.popleft()
                del self._cache[oldest_key]

            self._cache[key] = (time.time(), value, query)
            self._access_order.append(key)

    async def invalidate(self, pattern: Optional[str] = None) -> None:
        async with self._lock:
            if pattern:
                keys_to_remove = []
                for k, (_, _, query) in self._cache.items():
                    query_str = json.dumps(query, sort_keys=True, default=lambda o: o.isoformat() if isinstance(o, datetime) else str(o) if isinstance(o, UUID) else None)
                    if pattern in query_str:
                        keys_to_remove.append(k)
                for k in keys_to_remove:
                    del self._cache[k]
                    self._access_order.remove(k)
            else:
                self._cache.clear()
                self._access_order.clear()


class MetricsAggregator:
    @staticmethod
    def aggregate_by_time_window(
        timestamps: List[datetime],
        metric_data: Dict[str, List[float]],
        window_size_seconds: int = 60,
    ) -> Dict[str, Any]:
        if not timestamps:
            return {"timestamps": [], "metrics": {}}

        window_start = timestamps[0].replace(second=0, microsecond=0)
        windows: Dict[datetime, Dict[str, List[float]]] = defaultdict(lambda: defaultdict(list))

        for i, ts in enumerate(timestamps):
            window_ts = window_start + timedelta(
                seconds=int((ts - window_start).total_seconds() // window_size_seconds) * window_size_seconds
            )
            for metric_name, values in metric_data.items():
                if i < len(values):
                    windows[window_ts][metric_name].append(values[i])

        aggregated_timestamps = sorted(windows.keys())
        aggregated_metrics: Dict[str, List[float]] = defaultdict(list)

        for ts in aggregated_timestamps:
            for metric_name in metric_data.keys():
                values = windows[ts].get(metric_name, [])
                if values:
                    aggregated_metrics[metric_name].append(sum(values) / len(values))
                else:
                    aggregated_metrics[metric_name].append(None)

        return {
            "timestamps": [ts.isoformat() for ts in aggregated_timestamps],
            "metrics": dict(aggregated_metrics),
            "aggregation": {
                "window_size_seconds": window_size_seconds,
                "original_points": len(timestamps),
                "aggregated_points": len(aggregated_timestamps),
            },
        }

    @staticmethod
    def compute_statistics(values: List[float]) -> Dict[str, float]:
        if not values:
            return {}

        sorted_values = sorted(values)
        n = len(sorted_values)

        def percentile(p: float) -> float:
            k = (n - 1) * p
            f = int(k)
            c = min(f + 1, n - 1)
            return sorted_values[f] + (sorted_values[c] - sorted_values[f]) * (k - f)

        return {
            "count": n,
            "sum": sum(values),
            "mean": sum(values) / n,
            "min": sorted_values[0],
            "max": sorted_values[-1],
            "p50": percentile(0.5),
            "p95": percentile(0.95),
            "p99": percentile(0.99),
        }


class MetricsCollector:
    _instance = None
    _registry: CollectorRegistry
    _counters: Dict[str, Counter]
    _gauges: Dict[str, Gauge]
    _histograms: Dict[str, Histogram]

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._registry = CollectorRegistry()
            cls._instance._counters = {}
            cls._instance._gauges = {}
            cls._instance._histograms = {}
        return cls._instance

    def _get_or_create_counter(self, name: str, description: str = "", labels: Optional[List[str]] = None) -> Counter:
        if name not in self._counters:
            self._counters[name] = Counter(
                name, description, labelnames=labels or [], registry=self._registry
            )
        return self._counters[name]

    def _get_or_create_gauge(self, name: str, description: str = "", labels: Optional[List[str]] = None) -> Gauge:
        if name not in self._gauges:
            self._gauges[name] = Gauge(
                name, description, labelnames=labels or [], registry=self._registry
            )
        return self._gauges[name]

    def _get_or_create_histogram(
        self, name: str, description: str = "", labels: Optional[List[str]] = None
    ) -> Histogram:
        if name not in self._histograms:
            self._histograms[name] = Histogram(
                name, description, labelnames=labels or [], registry=self._registry
            )
        return self._histograms[name]

    def increment_counter(self, name: str, amount: float = 1, labels: Optional[Dict[str, str]] = None):
        counter = self._get_or_create_counter(name, labels=list(labels.keys()) if labels else None)
        if labels:
            counter.labels(**labels).inc(amount)
        else:
            counter.inc(amount)

    def set_gauge(self, name: str, value: float, labels: Optional[Dict[str, str]] = None):
        gauge = self._get_or_create_gauge(name, labels=list(labels.keys()) if labels else None)
        if labels:
            gauge.labels(**labels).set(value)
        else:
            gauge.set(value)

    def observe_histogram(self, name: str, value: float, labels: Optional[Dict[str, str]] = None):
        histogram = self._get_or_create_histogram(name, labels=list(labels.keys()) if labels else None)
        if labels:
            histogram.labels(**labels).observe(value)
        else:
            histogram.observe(value)

    def generate_metrics(self) -> bytes:
        return generate_latest(self._registry)

    @property
    def content_type(self) -> str:
        return CONTENT_TYPE_LATEST


class MonitoringService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.collector = MetricsCollector()
        self._cache = QueryCache()
        self._aggregator = MetricsAggregator()

    async def record_metric_snapshot(self, snapshot_in: MetricSnapshotCreate) -> MetricSnapshot:
        with log_operation(
            "app.monitoring",
            "record_metric_snapshot",
            reraise=True,
            metric_count=len(snapshot_in.metrics),
            host=snapshot_in.host,
        ):
            snapshot = MetricSnapshot(
                timestamp=datetime.now(timezone.utc),
                metrics=snapshot_in.metrics,
                dimensions=snapshot_in.dimensions,
                host=snapshot_in.host,
                region=snapshot_in.region,
                service=snapshot_in.service,
                meta_data=snapshot_in.metadata,
            )
            self.db.add(snapshot)
            await self.db.commit()
            await self.db.refresh(snapshot)

            for metric_name, metric_value in snapshot_in.metrics.items():
                if isinstance(metric_value, (int, float)):
                    self.collector.set_gauge(metric_name, float(metric_value))

            await self._cache.invalidate("metrics_query")
            logger.debug("Metric snapshot recorded", snapshot_id=str(snapshot.id))
            return snapshot

    async def _batch_query_snapshots(
        self,
        stmt,
        batch_size: int = DEFAULT_BATCH_SIZE,
    ) -> AsyncGenerator[List[MetricSnapshot], None]:
        offset = 0
        while True:
            batch_stmt = stmt.offset(offset).limit(batch_size)
            result = await self.db.execute(batch_stmt)
            batch = result.scalars().all()

            if not batch:
                break

            yield list(batch)
            offset += batch_size

            if len(batch) < batch_size:
                break

    async def query_metrics(
        self,
        query: MetricsQuery,
        use_cache: bool = True,
        aggregate: bool = False,
        aggregation_window: int = 60,
    ) -> Dict[str, Any]:
        query_dict = query.model_dump()

        if use_cache:
            cached = await self._cache.get({"type": "metrics_query", **query_dict})
            if cached is not None:
                logger.debug("Returning cached metrics result")
                return cached

        if query.start_time >= query.end_time:
            raise ValidationError(
                "start_time must be before end_time",
                details={"start_time": query.start_time.isoformat(), "end_time": query.end_time.isoformat()},
            )

        time_diff = (query.end_time - query.start_time).total_seconds()
        if time_diff > 86400 * 30:
            raise ValidationError(
                "Time range exceeds maximum allowed (30 days)",
                details={"range_days": time_diff / 86400, "max_days": 30},
            )

        stmt = select(MetricSnapshot).where(
            and_(
                MetricSnapshot.timestamp >= query.start_time,
                MetricSnapshot.timestamp <= query.end_time,
            )
        )

        if query.host:
            stmt = stmt.where(MetricSnapshot.host == query.host)
        if query.service:
            stmt = stmt.where(MetricSnapshot.service == query.service)
        if query.dimensions:
            for key, value in query.dimensions.items():
                stmt = stmt.where(MetricSnapshot.dimensions[key].astext == str(value))

        count_stmt = select(func.count(MetricSnapshot.id)).where(stmt.whereclause)
        count_result = await self.db.execute(count_stmt)
        total_snapshots = count_result.scalar_one()

        if total_snapshots == 0:
            result = {
                "timestamps": [],
                "metrics": {},
                "metadata": {
                    "query": query_dict,
                    "snapshot_count": 0,
                    "from_cache": False,
                },
            }
            if use_cache:
                await self._cache.set({"type": "metrics_query", **query_dict}, result)
            return result

        estimated_memory_mb = total_snapshots * 0.001
        batch_size = min(DEFAULT_BATCH_SIZE, MAX_BATCH_SIZE)

        if estimated_memory_mb > 100:
            batch_size = max(100, int(MAX_BATCH_SIZE * (100 / estimated_memory_mb)))
            logger.warning(
                f"Large query detected: {total_snapshots} snapshots, using batch size {batch_size}",
                estimated_memory_mb=round(estimated_memory_mb, 2),
            )

        stmt = stmt.order_by(MetricSnapshot.timestamp.asc())

        all_timestamps: List[datetime] = []
        all_metric_data: Dict[str, List[float]] = defaultdict(list)
        processed_count = 0

        async for batch in self._batch_query_snapshots(stmt, batch_size=batch_size):
            for snapshot in batch:
                all_timestamps.append(snapshot.timestamp)
                for metric_name, metric_value in snapshot.metrics.items():
                    if query.metric_names and metric_name not in query.metric_names:
                        continue
                    if isinstance(metric_value, (int, float)):
                        all_metric_data[metric_name].append(float(metric_value))
                    else:
                        all_metric_data[metric_name].append(None)

                processed_count += 1
                if processed_count % 10000 == 0:
                    logger.debug(f"Processed {processed_count}/{total_snapshots} snapshots")

        result_data = {
            "timestamps": [ts.isoformat() for ts in all_timestamps],
            "metrics": dict(all_metric_data),
            "metadata": {
                "query": query_dict,
                "snapshot_count": len(all_timestamps),
                "from_cache": False,
                "batch_size": batch_size,
                "total_expected": total_snapshots,
            },
        }

        if aggregate and all_timestamps:
            aggregated = self._aggregator.aggregate_by_time_window(
                all_timestamps, all_metric_data, aggregation_window
            )
            result_data["aggregated"] = aggregated

            metric_stats = {}
            for metric_name, values in all_metric_data.items():
                numeric_values = [v for v in values if v is not None]
                if numeric_values:
                    metric_stats[metric_name] = self._aggregator.compute_statistics(numeric_values)
            result_data["statistics"] = metric_stats

        if use_cache:
            await self._cache.set({"type": "metrics_query", **query_dict}, result_data)

        return result_data

    async def list_snapshots(
        self,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        host: Optional[str] = None,
        service: Optional[str] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[MetricSnapshot], int]:
        limit = min(limit, MAX_BATCH_SIZE)

        stmt = select(MetricSnapshot)
        conditions = []

        if start_time:
            conditions.append(MetricSnapshot.timestamp >= start_time)
        if end_time:
            conditions.append(MetricSnapshot.timestamp <= end_time)
        if host:
            conditions.append(MetricSnapshot.host == host)
        if service:
            conditions.append(MetricSnapshot.service == service)

        if conditions:
            stmt = stmt.where(and_(*conditions))

        count_stmt = (
            select(func.count(MetricSnapshot.id)).where(and_(*conditions))
            if conditions
            else select(func.count(MetricSnapshot.id))
        )
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(MetricSnapshot.timestamp.desc())
        result = await self.db.execute(stmt)
        snapshots = result.scalars().all()

        return list(snapshots), total

    async def get_snapshot(self, snapshot_id: UUID) -> MetricSnapshot:
        stmt = select(MetricSnapshot).where(MetricSnapshot.id == snapshot_id)
        result = await self.db.execute(stmt)
        snapshot = result.scalar_one_or_none()

        if not snapshot:
            raise NotFoundError(
                f"Metric snapshot {snapshot_id} not found",
                details={"snapshot_id": str(snapshot_id)},
            )

        return snapshot

    async def record_audit_log(self, audit_in: AuditLogCreate, user_id: Optional[UUID] = None) -> AuditLog:
        with log_operation(
            "app.monitoring",
            "record_audit_log",
            reraise=True,
            action=audit_in.action,
            resource_type=audit_in.resource_type,
        ):
            audit_log = AuditLog(
                timestamp=datetime.now(timezone.utc),
                user_id=user_id,
                action=audit_in.action,
                resource_type=audit_in.resource_type,
                resource_id=audit_in.resource_id,
                status=audit_in.status,
                request_details=audit_in.request_details,
                response_details=audit_in.response_details,
                ip_address=audit_in.ip_address,
                user_agent=audit_in.user_agent,
                meta_data=audit_in.metadata,
            )
            self.db.add(audit_log)
            await self.db.commit()
            await self.db.refresh(audit_log)
            return audit_log

    async def list_audit_logs(
        self,
        user_id: Optional[UUID] = None,
        action: Optional[str] = None,
        resource_type: Optional[str] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        skip: int = 0,
        limit: int = 100,
    ) -> Tuple[List[AuditLog], int]:
        limit = min(limit, MAX_BATCH_SIZE)

        stmt = select(AuditLog)
        conditions = []

        if user_id:
            conditions.append(AuditLog.user_id == user_id)
        if action:
            conditions.append(AuditLog.action == action)
        if resource_type:
            conditions.append(AuditLog.resource_type == resource_type)
        if start_time:
            conditions.append(AuditLog.timestamp >= start_time)
        if end_time:
            conditions.append(AuditLog.timestamp <= end_time)

        if conditions:
            stmt = stmt.where(and_(*conditions))

        count_stmt = (
            select(func.count(AuditLog.id)).where(and_(*conditions))
            if conditions
            else select(func.count(AuditLog.id))
        )
        count_result = await self.db.execute(count_stmt)
        total = count_result.scalar_one()

        stmt = stmt.offset(skip).limit(limit).order_by(AuditLog.timestamp.desc())
        result = await self.db.execute(stmt)
        logs = result.scalars().all()

        return list(logs), total

    async def get_current_metrics(self) -> bytes:
        return self.collector.generate_metrics()

    async def get_cache_stats(self) -> Dict[str, Any]:
        return {
            "cache_size": len(self._cache._cache),
            "max_cache_entries": self._cache._max_entries,
            "ttl_seconds": self._cache._ttl,
        }

    async def clear_cache(self) -> None:
        await self._cache.invalidate()
        logger.info("Query cache cleared")
