from __future__ import annotations

import asyncio
import time
from abc import ABC, abstractmethod
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
import threading
from typing import Any, AsyncGenerator, Callable, Dict, List, Optional, Set, Tuple
from uuid import uuid4


class QueryStatus(str, Enum):
    SUCCESS = "success"
    FAILED = "failed"
    TIMEOUT = "timeout"
    CANCELED = "canceled"


class RepositoryOperation(str, Enum):
    CREATE = "create"
    READ = "read"
    UPDATE = "update"
    DELETE = "delete"
    QUERY = "query"
    LIST = "list"
    COUNT = "count"


@dataclass
class QueryLatency:
    p50_ms: float = 0.0
    p95_ms: float = 0.0
    p99_ms: float = 0.0
    avg_ms: float = 0.0
    min_ms: float = 0.0
    max_ms: float = 0.0


@dataclass
class QueryStats:
    repository: str
    operation: RepositoryOperation
    total_queries: int = 0
    failed_queries: int = 0
    timeout_queries: int = 0
    total_time_ms: float = 0.0
    slow_queries: int = 0
    last_100_latencies: deque[float] = field(default_factory=lambda: deque(maxlen=100))

    @property
    def success_rate(self) -> float:
        return 1.0 - (self.failed_queries / self.total_queries) if self.total_queries > 0 else 1.0

    @property
    def avg_latency_ms(self) -> float:
        return self.total_time_ms / self.total_queries if self.total_queries > 0 else 0.0

    def record(self, duration_ms: float, success: bool, timeout: bool = False, slow_threshold: float = 1000.0) -> None:
        self.total_queries += 1
        self.total_time_ms += duration_ms
        if not success:
            self.failed_queries += 1
        if timeout:
            self.timeout_queries += 1
        if duration_ms > slow_threshold:
            self.slow_queries += 1
        self.last_100_latencies.append(duration_ms)

    def calculate_latency_percentiles(self) -> QueryLatency:
        if not self.last_100_latencies:
            return QueryLatency()

        sorted_latencies = sorted(self.last_100_latencies)
        n = len(sorted_latencies)

        def percentile(p: int) -> float:
            idx = min(int(n * p / 100), n - 1)
            return sorted_latencies[idx]

        return QueryLatency(
            p50_ms=percentile(50),
            p95_ms=percentile(95),
            p99_ms=percentile(99),
            avg_ms=sum(sorted_latencies) / n,
            min_ms=sorted_latencies[0],
            max_ms=sorted_latencies[-1],
        )


@dataclass
class ActiveQuery:
    query_id: str
    repository: str
    operation: RepositoryOperation
    started_at: datetime
    elapsed_ms: float = 0.0
    query: Optional[str] = None

    @property
    def is_long_running(self, threshold_ms: float = 5000.0) -> bool:
        return self.elapsed_ms > threshold_ms


@dataclass
class HealthStatus:
    healthy: bool
    last_checked_at: datetime
    error_message: Optional[str] = None
    response_time_ms: float = 0.0
    available_connections: int = 0
    total_connections: int = 0


class MetricsCollector:
    def __init__(
        self,
        slow_query_threshold_ms: float = 1000.0,
        long_running_threshold_ms: float = 5000.0,
        max_active_queries: int = 100,
    ):
        self._slow_threshold = slow_query_threshold_ms
        self._long_running_threshold = long_running_threshold_ms
        self._max_active = max_active_queries
        self._stats: Dict[Tuple[str, RepositoryOperation], QueryStats] = {}
        self._active_queries: Dict[str, ActiveQuery] = {}
        self._lock = threading.RLock()
        self._started_at = datetime.utcnow()
        self._health_status = HealthStatus(
            healthy=True,
            last_checked_at=datetime.utcnow(),
        )

    def _get_or_create_stats(self, repository: str, operation: RepositoryOperation) -> QueryStats:
        key = (repository, operation)
        if key not in self._stats:
            self._stats[key] = QueryStats(repository=repository, operation=operation)
        return self._stats[key]

    def start_query(
        self,
        repository: str,
        operation: RepositoryOperation,
        query: Optional[str] = None,
    ) -> str:
        query_id = uuid4().hex[:16]
        if len(self._active_queries) >= self._max_active:
            oldest = min(self._active_queries.values(), key=lambda q: q.started_at)
            del self._active_queries[oldest.query_id]

        self._active_queries[query_id] = ActiveQuery(
            query_id=query_id,
            repository=repository,
            operation=operation,
            started_at=datetime.utcnow(),
            query=query,
        )
        return query_id

    async def record_query(
        self,
        query_id: str,
        success: bool,
        timeout: bool = False,
    ) -> Optional[QueryStats]:
        if query_id not in self._active_queries:
            return None

        query = self._active_queries.pop(query_id)
        elapsed_ms = (datetime.utcnow() - query.started_at).total_seconds() * 1000

        stats = self._get_or_create_stats(query.repository, query.operation)
        stats.record(
            duration_ms=elapsed_ms,
            success=success,
            timeout=timeout,
            slow_threshold=self._slow_threshold,
        )
        return stats

    def get_long_running_queries(self) -> List[ActiveQuery]:
        now = datetime.utcnow()
        result: List[ActiveQuery] = []
        for query in self._active_queries.values():
            query.elapsed_ms = (now - query.started_at).total_seconds() * 1000
            if query.is_long_running(self._long_running_threshold):
                result.append(query)
        return result

    def get_active_query_count(self) -> int:
        return len(self._active_queries)

    def get_repository_stats(self, repository: Optional[str] = None) -> List[QueryStats]:
        if repository:
            return [
                stats
                for (repo, _), stats in self._stats.items()
                if repo == repository
            ]
        return list(self._stats.values())

    def get_all_stats(self) -> Dict[str, Any]:
        total_queries = 0
        total_failed = 0
        total_time_ms = 0.0

        for stats in self._stats.values():
            total_queries += stats.total_queries
            total_failed += stats.failed_queries
            total_time_ms += stats.total_time_ms

        by_repository: Dict[str, Dict[str, Any]] = {}
        for (repo, op), stats in self._stats.items():
            if repo not in by_repository:
                by_repository[repo] = {
                    "total": 0,
                    "failed": 0,
                    "operations": {},
                }
            by_repository[repo]["total"] += stats.total_queries
            by_repository[repo]["failed"] += stats.failed_queries

            latency = stats.calculate_latency_percentiles()
            by_repository[repo]["operations"][op.value] = {
                "count": stats.total_queries,
                "failed": stats.failed_queries,
                "success_rate": stats.success_rate,
                "avg_latency_ms": stats.avg_latency_ms,
                "p50_ms": latency.p50_ms,
                "p95_ms": latency.p95_ms,
                "p99_ms": latency.p99_ms,
                "slow_queries": stats.slow_queries,
            }

        return {
            "summary": {
                "total_queries": total_queries,
                "total_failed": total_failed,
                "success_rate": 1.0 - (total_failed / total_queries) if total_queries > 0 else 1.0,
                "avg_latency_ms": total_time_ms / total_queries if total_queries > 0 else 0.0,
                "uptime_seconds": (datetime.utcnow() - self._started_at).total_seconds(),
                "active_queries": self.get_active_query_count(),
                "long_running_queries": len(self.get_long_running_queries()),
            },
            "health": self._health_status.__dict__,
            "by_repository": by_repository,
        }

    def update_health_status(
        self,
        healthy: bool,
        response_time_ms: float,
        available_connections: int,
        total_connections: int,
        error_message: Optional[str] = None,
    ) -> None:
        self._health_status = HealthStatus(
            healthy=healthy,
            last_checked_at=datetime.utcnow(),
            error_message=error_message,
            response_time_ms=response_time_ms,
            available_connections=available_connections,
            total_connections=total_connections,
        )


class PrometheusExporter:
    def __init__(self, collector: MetricsCollector, prefix: str = "top"):
        self._collector = collector
        self._prefix = prefix
        self._custom_labels: Dict[str, str] = {}

    def set_labels(self, labels: Dict[str, str]) -> None:
        self._custom_labels = labels.copy()

    def _format_labels(self, labels: Dict[str, str]) -> str:
        if not labels:
            return ""
        parts = [f'{k}="{v}"' for k, v in labels.items()]
        return "{" + ",".join(parts) + "}"

    def export(self) -> str:
        lines: List[str] = []

        stats = self._collector.get_all_stats()
        summary = stats["summary"]

        lines.append(f"# HELP {self._prefix}_db_queries_total Total database queries")
        lines.append(f"# TYPE {self._prefix}_db_queries_total counter")
        lines.append(f"{self._prefix}_db_queries_total {summary['total_queries']}")

        lines.append(f"# HELP {self._prefix}_db_queries_failed_total Total failed database queries")
        lines.append(f"# TYPE {self._prefix}_db_queries_failed_total counter")
        lines.append(f"{self._prefix}_db_queries_failed_total {summary['total_failed']}")

        lines.append(f"# HELP {self._prefix}_db_active_queries Active database queries")
        lines.append(f"# TYPE {self._prefix}_db_active_queries gauge")
        lines.append(f"{self._prefix}_db_active_queries {summary['active_queries']}")

        lines.append(f"# HELP {self._prefix}_db_long_running_queries Long-running database queries")
        lines.append(f"# TYPE {self._prefix}_db_long_running_queries gauge")
        lines.append(f"{self._prefix}_db_long_running_queries {summary['long_running_queries']}")

        for repo, repo_stats in stats["by_repository"].items():
            labels = {"repository": repo, **self._custom_labels}
            label_str = self._format_labels(labels)

            lines.append(f"# HELP {self._prefix}_db_repository_queries_total Queries per repository")
            lines.append(f"# TYPE {self._prefix}_db_repository_queries_total counter")
            lines.append(f"{self._prefix}_db_repository_queries_total{label_str} {repo_stats['total']}")

            for op, op_stats in repo_stats["operations"].items():
                op_labels = {
                    "repository": repo,
                    "operation": op,
                    **self._custom_labels,
                }
                op_label_str = self._format_labels(op_labels)

                lines.append(f"# HELP {self._prefix}_db_operation_queries_total Queries per operation")
                lines.append(f"# TYPE {self._prefix}_db_operation_queries_total counter")
                lines.append(f"{self._prefix}_db_operation_queries_total{op_label_str} {op_stats['count']}")

                lines.append(f"# HELP {self._prefix}_db_operation_latency_p50_ms P50 latency per operation")
                lines.append(f"# TYPE {self._prefix}_db_operation_latency_p50_ms gauge")
                lines.append(f"{self._prefix}_db_operation_latency_p50_ms{op_label_str} {op_stats['p50_ms']}")

                lines.append(f"# HELP {self._prefix}_db_operation_latency_p95_ms P95 latency per operation")
                lines.append(f"# TYPE {self._prefix}_db_operation_latency_p95_ms gauge")
                lines.append(f"{self._prefix}_db_operation_latency_p95_ms{op_label_str} {op_stats['p95_ms']}")

                lines.append(f"# HELP {self._prefix}_db_operation_latency_p99_ms P99 latency per operation")
                lines.append(f"# TYPE {self._prefix}_db_operation_latency_p99_ms gauge")
                lines.append(f"{self._prefix}_db_operation_latency_p99_ms{op_label_str} {op_stats['p99_ms']}")

                lines.append(f"# HELP {self._prefix}_db_operation_slow_queries_total Slow queries per operation")
                lines.append(f"# TYPE {self._prefix}_db_operation_slow_queries_total counter")
                lines.append(f"{self._prefix}_db_operation_slow_queries_total{op_label_str} {op_stats['slow_queries']}")

        health = stats["health"]
        lines.append(f"# HELP {self._prefix}_db_health Database health status (1=healthy, 0=unhealthy)")
        lines.append(f"# TYPE {self._prefix}_db_health gauge")
        lines.append(f"{self._prefix}_db_health {1 if health['healthy'] else 0}")

        lines.append(f"# HELP {self._prefix}_db_pool_connections Database pool connections")
        lines.append(f"# TYPE {self._prefix}_db_pool_connections gauge")
        lines.append(f"{self._prefix}_db_pool_connections{{state='total'}} {health['total_connections']}")
        lines.append(f"{self._prefix}_db_pool_connections{{state='available'}} {health['available_connections']}")
        lines.append(f"{self._prefix}_db_pool_connections{{state='used'}} {health['total_connections'] - health['available_connections']}")

        return "\n".join(lines) + "\n"


class MonitoredRepository:
    def __init__(
        self,
        repository_name: str,
        collector: MetricsCollector,
    ):
        self._repo_name = repository_name
        self._collector = collector

    @asynccontextmanager
    async def measure(
        self,
        operation: RepositoryOperation,
        query: Optional[str] = None,
    ) -> AsyncGenerator[Tuple[str, Callable[[bool, bool], None]], None]:
        query_id = self._collector.start_query(
            repository=self._repo_name,
            operation=operation,
            query=query,
        )

        def callback(success: bool, timeout: bool = False):
            asyncio.create_task(
                self._collector.record_query(
                    query_id=query_id,
                    success=success,
                    timeout=timeout,
                )
            )

        try:
            yield query_id, callback
        except asyncio.TimeoutError:
            callback(False, True)
            raise
        except Exception:
            callback(False, False)
            raise
        else:
            callback(True, False)


_collector_instance: Optional[MetricsCollector] = None
_exporter_instance: Optional[PrometheusExporter] = None


def get_metrics_collector(
    slow_query_threshold_ms: float = 1000.0,
    long_running_threshold_ms: float = 5000.0,
) -> MetricsCollector:
    global _collector_instance
    if _collector_instance is None:
        _collector_instance = MetricsCollector(
            slow_query_threshold_ms=slow_query_threshold_ms,
            long_running_threshold_ms=long_running_threshold_ms,
        )
    return _collector_instance


def get_prometheus_exporter(prefix: str = "top") -> PrometheusExporter:
    global _exporter_instance
    if _exporter_instance is None:
        _exporter_instance = PrometheusExporter(
            collector=get_metrics_collector(),
            prefix=prefix,
        )
    return _exporter_instance


def set_metrics_collector(collector: MetricsCollector) -> None:
    global _collector_instance
    _collector_instance = collector


def export_prometheus_metrics() -> str:
    return get_prometheus_exporter().export()


def get_database_metrics() -> Dict[str, Any]:
    return get_metrics_collector().get_all_stats()
