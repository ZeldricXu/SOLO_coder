from typing import Dict, List, Optional, Any
from datetime import datetime
from collections import defaultdict
from .types import (
    MetricDefinition,
    MetricType,
    OnlineMetricPoint,
)
import logging

logger = logging.getLogger(__name__)


class MetricsStore:
    def __init__(self):
        self._metric_defs: Dict[str, MetricDefinition] = {}
        self._online_metrics: Dict[str, List[OnlineMetricPoint]] = defaultdict(list)
        self._offline_metrics: Dict[str, Dict[str, List[Dict[str, Any]]]] = defaultdict(
            lambda: defaultdict(list)
        )

    async def define_metric(self, metric: MetricDefinition) -> MetricDefinition:
        from src.core import generate_id
        metric.metric_id = metric.metric_id or generate_id("metric")
        self._metric_defs[metric.metric_id] = metric
        logger.info(f"Defined metric: {metric.name} (id={metric.metric_id})")
        return metric

    async def get_metric_definition(self, metric_id: str) -> MetricDefinition:
        from src.core import NotFoundError
        metric = self._metric_defs.get(metric_id)
        if not metric:
            raise NotFoundError(f"Metric definition not found: {metric_id}")
        return metric

    async def list_metric_definitions(self) -> List[MetricDefinition]:
        return list(self._metric_defs.values())

    async def record_online_metric(self, point: OnlineMetricPoint) -> None:
        key = f"{point.model_id}:{point.version_id}:{point.metric_name}"
        self._online_metrics[key].append(point)
        if len(self._online_metrics[key]) > 100000:
            self._online_metrics[key] = self._online_metrics[key][-100000:]

    async def get_online_metrics(
        self,
        model_id: str,
        version_id: str,
        metric_name: str,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
    ) -> List[OnlineMetricPoint]:
        key = f"{model_id}:{version_id}:{metric_name}"
        points = self._online_metrics.get(key, [])
        if start_time:
            points = [p for p in points if p.timestamp >= start_time]
        if end_time:
            points = [p for p in points if p.timestamp <= end_time]
        return points

    async def get_online_metric_stats(
        self,
        model_id: str,
        version_id: str,
        metric_name: str,
        window_minutes: int = 60,
    ) -> Dict[str, float]:
        start_time = datetime.utcnow() - __import__("datetime").timedelta(minutes=window_minutes)
        points = await self.get_online_metrics(model_id, version_id, metric_name, start_time)
        if not points:
            return {"count": 0, "avg": 0.0, "min": 0.0, "max": 0.0, "p50": 0.0, "p95": 0.0, "p99": 0.0}

        values = sorted(p.value for p in points)
        n = len(values)

        def percentile(p: float) -> float:
            k = (n - 1) * (p / 100.0)
            f = int(k)
            c = min(f + 1, n - 1)
            if f == c:
                return values[f]
            return values[f] + (values[c] - values[f]) * (k - f)

        return {
            "count": n,
            "avg": sum(values) / n,
            "min": values[0],
            "max": values[-1],
            "p50": percentile(50),
            "p95": percentile(95),
            "p99": percentile(99),
        }

    async def record_offline_evaluation(
        self,
        model_id: str,
        version_id: str,
        evaluation_id: str,
        metrics: Dict[str, float],
    ) -> None:
        model_metrics = self._offline_metrics[model_id][version_id]
        model_metrics.append({
            "evaluation_id": evaluation_id,
            "metrics": metrics,
            "timestamp": datetime.utcnow(),
        })

    async def get_offline_evaluations(
        self,
        model_id: str,
        version_id: str,
        limit: int = 100,
    ) -> List[Dict[str, Any]]:
        return self._offline_metrics.get(model_id, {}).get(version_id, [])[-limit:]
