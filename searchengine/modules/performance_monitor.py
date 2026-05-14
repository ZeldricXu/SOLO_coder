import logging
import time
import threading
from typing import Dict, Any, Optional, List
from datetime import datetime, timedelta
from collections import deque
from searchengine.models.base import PerformanceMetrics


class PerformanceMonitor:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self._lock = threading.Lock()
        self._metrics_id = 0
        self._current_metrics = self._create_new_metrics()
        self._history: deque = deque(maxlen=100)
        self._search_times: deque = deque(maxlen=1000)
        self._errors: deque = deque(maxlen=100)
        self._latency_buckets = {
            "lt_10ms": 0,
            "lt_50ms": 0,
            "lt_100ms": 0,
            "lt_500ms": 0,
            "ge_500ms": 0
        }
    
    def _generate_metrics_id(self) -> str:
        self._metrics_id += 1
        return f"metrics_{self._metrics_id:04d}"
    
    def _create_new_metrics(self) -> PerformanceMetrics:
        return PerformanceMetrics(
            metric_id=self._generate_metrics_id(),
            timestamp=datetime.utcnow(),
            total_requests=0,
            total_search_time=0.0,
            avg_search_time=0.0,
            max_search_time=0.0,
            min_search_time=float('inf'),
            cache_hits=0,
            cache_misses=0,
            cache_hit_rate=0.0,
            error_count=0
        )
    
    def record_search(self, search_time_ms: float, from_cache: bool = False) -> None:
        with self._lock:
            self._current_metrics.total_requests += 1
            self._current_metrics.total_search_time += search_time_ms
            self._current_metrics.avg_search_time = (
                self._current_metrics.total_search_time /
                self._current_metrics.total_requests
            )
            self._current_metrics.max_search_time = max(
                self._current_metrics.max_search_time,
                search_time_ms
            )
            self._current_metrics.min_search_time = min(
                self._current_metrics.min_search_time,
                search_time_ms
            )
            
            self._search_times.append(search_time_ms)
            
            if from_cache:
                self._current_metrics.cache_hits += 1
            else:
                self._current_metrics.cache_misses += 1
            
            total_cache_ops = self._current_metrics.cache_hits + self._current_metrics.cache_misses
            if total_cache_ops > 0:
                self._current_metrics.cache_hit_rate = (
                    self._current_metrics.cache_hits / total_cache_ops
                )
            
            self._record_latency_bucket(search_time_ms)
    
    def _record_latency_bucket(self, search_time_ms: float) -> None:
        if search_time_ms < 10:
            self._latency_buckets["lt_10ms"] += 1
        elif search_time_ms < 50:
            self._latency_buckets["lt_50ms"] += 1
        elif search_time_ms < 100:
            self._latency_buckets["lt_100ms"] += 1
        elif search_time_ms < 500:
            self._latency_buckets["lt_500ms"] += 1
        else:
            self._latency_buckets["ge_500ms"] += 1
    
    def record_error(self, error_type: str, error_message: str) -> None:
        with self._lock:
            self._current_metrics.error_count += 1
            self._errors.append({
                "timestamp": datetime.utcnow().isoformat(),
                "error_type": error_type,
                "error_message": error_message
            })
    
    def record_cache_hit(self) -> None:
        with self._lock:
            self._current_metrics.cache_hits += 1
            total_cache_ops = self._current_metrics.cache_hits + self._current_metrics.cache_misses
            if total_cache_ops > 0:
                self._current_metrics.cache_hit_rate = (
                    self._current_metrics.cache_hits / total_cache_ops
                )
    
    def record_cache_miss(self) -> None:
        with self._lock:
            self._current_metrics.cache_misses += 1
            total_cache_ops = self._current_metrics.cache_hits + self._current_metrics.cache_misses
            if total_cache_ops > 0:
                self._current_metrics.cache_hit_rate = (
                    self._current_metrics.cache_hits / total_cache_ops
                )
    
    def get_current_metrics(self) -> PerformanceMetrics:
        with self._lock:
            return self._current_metrics.model_copy()
    
    def get_metrics_summary(self) -> Dict[str, Any]:
        with self._lock:
            metrics = self._current_metrics
            p95 = self._calculate_percentile(95)
            p99 = self._calculate_percentile(99)
            
            return {
                "metric_id": metrics.metric_id,
                "timestamp": metrics.timestamp.isoformat(),
                "total_requests": metrics.total_requests,
                "total_search_time": metrics.total_search_time,
                "avg_search_time": metrics.avg_search_time,
                "max_search_time": metrics.max_search_time,
                "min_search_time": metrics.min_search_time if metrics.min_search_time != float('inf') else 0,
                "p95_latency": p95,
                "p99_latency": p99,
                "cache_hits": metrics.cache_hits,
                "cache_misses": metrics.cache_misses,
                "cache_hit_rate": metrics.cache_hit_rate,
                "error_count": metrics.error_count,
                "latency_buckets": self._latency_buckets.copy(),
                "history_count": len(self._history)
            }
    
    def _calculate_percentile(self, percentile: int) -> float:
        if not self._search_times:
            return 0.0
        
        sorted_times = sorted(self._search_times)
        index = int(len(sorted_times) * percentile / 100)
        if index >= len(sorted_times):
            index = len(sorted_times) - 1
        return sorted_times[index]
    
    def snapshot(self) -> PerformanceMetrics:
        with self._lock:
            snapshot = self._current_metrics.model_copy()
            self._history.append(snapshot)
            self._current_metrics = self._create_new_metrics()
            return snapshot
    
    def get_history(self, limit: int = 10) -> List[PerformanceMetrics]:
        with self._lock:
            history_list = list(self._history)
            return history_list[-limit:]
    
    def get_errors(self, limit: int = 10) -> List[Dict[str, Any]]:
        with self._lock:
            error_list = list(self._errors)
            return error_list[-limit:]
    
    def reset(self) -> None:
        with self._lock:
            self._history.append(self._current_metrics)
            self._current_metrics = self._create_new_metrics()
            self._search_times.clear()
            self._errors.clear()
            self._latency_buckets = {
                "lt_10ms": 0,
                "lt_50ms": 0,
                "lt_100ms": 0,
                "lt_500ms": 0,
                "ge_500ms": 0
            }
            self.logger.info("Performance metrics reset")
    
    def get_health_status(self) -> Dict[str, Any]:
        summary = self.get_metrics_summary()
        
        is_healthy = True
        issues = []
        
        if summary["error_count"] > 10:
            is_healthy = False
            issues.append(f"High error count: {summary['error_count']}")
        
        if summary["p99_latency"] > 1000:
            is_healthy = False
            issues.append(f"High P99 latency: {summary['p99_latency']}ms")
        
        if summary["total_requests"] > 0 and summary["cache_hit_rate"] < 0.3:
            issues.append(f"Low cache hit rate: {summary['cache_hit_rate']:.2%}")
        
        return {
            "healthy": is_healthy,
            "issues": issues,
            "summary": summary
        }


performance_monitor = PerformanceMonitor()
