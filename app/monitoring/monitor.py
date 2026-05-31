import asyncio
import time
import uuid
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Any, Callable
from collections import defaultdict, deque
from dataclasses import dataclass, field
from prometheus_client import (
    Counter, Gauge, Histogram, Summary,
    CollectorRegistry, generate_latest, CONTENT_TYPE_LATEST
)
from app.logging_module import get_logger
from .models import MetricQueryRequest, MetricQueryResponse, HealthStatus


logger = get_logger(__name__)


@dataclass
class TimeSeriesPoint:
    timestamp: float
    value: float
    dimensions: Dict[str, str] = field(default_factory=dict)


class MetricsCollector:
    def __init__(self, max_history_seconds: int = 3600):
        self._registry = CollectorRegistry()
        self._max_history = max_history_seconds
        
        self._counters: Dict[str, Counter] = {}
        self._gauges: Dict[str, Gauge] = {}
        self._histograms: Dict[str, Histogram] = {}
        self._summaries: Dict[str, Summary] = {}
        
        self._time_series: Dict[str, deque] = defaultdict(
            lambda: deque(maxlen=10000)
        )
        self._custom_gauges: Dict[str, float] = {}
        
        self._request_counter = self._create_counter(
            "api_requests_total", "Total API requests", ["method", "endpoint", "status"]
        )
        self._request_duration = self._create_histogram(
            "api_request_duration_seconds", "API request duration", ["method", "endpoint"]
        )
        self._error_counter = self._create_counter(
            "api_errors_total", "Total API errors", ["endpoint", "error_type"]
        )
    
    def _create_counter(self, name: str, documentation: str, labelnames: List[str] = None) -> Counter:
        counter = Counter(
            name, documentation,
            labelnames=labelnames or [],
            registry=self._registry
        )
        self._counters[name] = counter
        return counter
    
    def _create_gauge(self, name: str, documentation: str, labelnames: List[str] = None) -> Gauge:
        gauge = Gauge(
            name, documentation,
            labelnames=labelnames or [],
            registry=self._registry
        )
        self._gauges[name] = gauge
        return gauge
    
    def _create_histogram(self, name: str, documentation: str, labelnames: List[str] = None) -> Histogram:
        histogram = Histogram(
            name, documentation,
            labelnames=labelnames or [],
            registry=self._registry
        )
        self._histograms[name] = histogram
        return histogram
    
    def _create_summary(self, name: str, documentation: str, labelnames: List[str] = None) -> Summary:
        summary = Summary(
            name, documentation,
            labelnames=labelnames or [],
            registry=self._registry
        )
        self._summaries[name] = summary
        return summary
    
    def increment_counter(self, name: str, labels: Dict[str, str] = None, value: float = 1.0):
        if name not in self._counters:
            self._counters[name] = self._create_counter(name, name, list(labels.keys()) if labels else [])
        
        counter = self._counters[name]
        if labels:
            counter.labels(**labels).inc(value)
        else:
            counter.inc(value)
        
        self._record_time_series(name, value, labels or {})
    
    def set_gauge(self, name: str, value: float, labels: Dict[str, str] = None):
        if name not in self._gauges:
            self._gauges[name] = self._create_gauge(name, name, list(labels.keys()) if labels else [])
        
        gauge = self._gauges[name]
        if labels:
            gauge.labels(**labels).set(value)
        else:
            gauge.set(value)
        
        self._custom_gauges[name] = value
        self._record_time_series(name, value, labels or {})
    
    def observe_histogram(self, name: str, value: float, labels: Dict[str, str] = None):
        if name not in self._histograms:
            self._histograms[name] = self._create_histogram(name, name, list(labels.keys()) if labels else [])
        
        histogram = self._histograms[name]
        if labels:
            histogram.labels(**labels).observe(value)
        else:
            histogram.observe(value)
        
        self._record_time_series(name, value, labels or {})
    
    def record_request(self, method: str, endpoint: str, status: int, duration: float):
        self._request_counter.labels(method=method, endpoint=endpoint, status=str(status)).inc()
        self._request_duration.labels(method=method, endpoint=endpoint).observe(duration)
        
        if status >= 400:
            self._error_counter.labels(endpoint=endpoint, error_type=str(status)).inc()
    
    def _record_time_series(self, metric_name: str, value: float, dimensions: Dict[str, str]):
        point = TimeSeriesPoint(
            timestamp=time.time(),
            value=value,
            dimensions=dimensions.copy()
        )
        self._time_series[metric_name].append(point)
        
        cutoff = time.time() - self._max_history
        while self._time_series[metric_name] and self._time_series[metric_name][0].timestamp < cutoff:
            self._time_series[metric_name].popleft()
    
    def query(self, request: MetricQueryRequest) -> MetricQueryResponse:
        points = list(self._time_series.get(request.metric_name, []))
        
        if request.start_time:
            start_ts = request.start_time.timestamp()
            points = [p for p in points if p.timestamp >= start_ts]
        
        if request.end_time:
            end_ts = request.end_time.timestamp()
            points = [p for p in points if p.timestamp <= end_ts]
        
        if request.dimensions:
            points = [
                p for p in points
                if all(p.dimensions.get(k) == v for k, v in request.dimensions.items())
            ]
        
        values = []
        for p in points:
            values.append({
                "timestamp": datetime.fromtimestamp(p.timestamp).isoformat(),
                "value": p.value,
                "dimensions": p.dimensions
            })
        
        return MetricQueryResponse(
            metric_name=request.metric_name,
            values=values,
            count=len(values),
            aggregation=request.aggregation or "none"
        )
    
    def export_prometheus(self) -> bytes:
        return generate_latest(self._registry)
    
    def get_registry(self) -> CollectorRegistry:
        return self._registry
    
    def get_snapshot(self) -> Dict[str, Any]:
        return {
            "counters": {name: self._get_counter_value(name) for name in self._counters},
            "gauges": self._custom_gauges.copy(),
            "time_series_count": {name: len(points) for name, points in self._time_series.items()}
        }
    
    def _get_counter_value(self, name: str) -> float:
        counter = self._counters.get(name)
        if not counter:
            return 0.0
        try:
            return float(counter._value.get())
        except Exception:
            return 0.0


class PrometheusExporter:
    def __init__(self, collector: MetricsCollector, port: int = 8001):
        self._collector = collector
        self._port = port
        self._server = None
    
    async def start(self):
        from aiohttp import web
        
        async def metrics_handler(request):
            data = self._collector.export_prometheus()
            return web.Response(
                body=data,
                content_type=CONTENT_TYPE_LATEST
            )
        
        app = web.Application()
        app.router.add_get("/metrics", metrics_handler)
        
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, port=self._port)
        await site.start()
        
        self._server = runner
        logger.info(f"Prometheus exporter started on port {self._port}")
    
    async def stop(self):
        if self._server:
            await self._server.cleanup()
            logger.info("Prometheus exporter stopped")


class HealthChecker:
    def __init__(self, check_interval_seconds: int = 30):
        self._check_interval = check_interval_seconds
        self._checks: Dict[str, Callable] = {}
        self._statuses: Dict[str, HealthStatus] = {}
        self._task: Optional[asyncio.Task] = None
        self._running = False
    
    def register_check(self, name: str, check_fn: Callable):
        self._checks[name] = check_fn
        logger.info(f"Registered health check: {name}")
    
    def unregister_check(self, name: str):
        if name in self._checks:
            del self._checks[name]
            logger.info(f"Unregistered health check: {name}")
    
    async def start(self):
        if self._running:
            return
        
        self._running = True
        self._task = asyncio.create_task(self._run_checks_loop())
        logger.info("Health checker started")
    
    async def stop(self):
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("Health checker stopped")
    
    async def _run_checks_loop(self):
        while self._running:
            await self._run_all_checks()
            await asyncio.sleep(self._check_interval)
    
    async def _run_all_checks(self):
        for name, check_fn in self._checks.items():
            try:
                if asyncio.iscoroutinefunction(check_fn):
                    result = await check_fn()
                else:
                    result = check_fn()
                
                if isinstance(result, tuple):
                    status, details = result
                else:
                    status = "healthy" if result else "unhealthy"
                    details = None
                
                self._statuses[name] = HealthStatus(
                    service=name,
                    status=status,
                    details=details,
                    last_check=datetime.utcnow()
                )
                
            except Exception as e:
                logger.error(f"Health check failed", service=name, error=str(e))
                self._statuses[name] = HealthStatus(
                    service=name,
                    status="unhealthy",
                    details={"error": str(e)},
                    last_check=datetime.utcnow()
                )
    
    def get_status(self, name: Optional[str] = None) -> Dict[str, Any]:
        if name:
            status = self._statuses.get(name)
            return {
                "service": name,
                "status": status.status if status else "unknown",
                "details": status.details if status else None
            }
        
        return {
            "overall": self._get_overall_status(),
            "services": {
                name: {
                    "status": s.status,
                    "last_check": s.last_check.isoformat(),
                    "details": s.details
                }
                for name, s in self._statuses.items()
            }
        }
    
    def _get_overall_status(self) -> str:
        if not self._statuses:
            return "healthy"
        
        statuses = [s.status for s in self._statuses.values()]
        if any(s == "unhealthy" for s in statuses):
            return "unhealthy"
        if any(s == "degraded" for s in statuses):
            return "degraded"
        return "healthy"
