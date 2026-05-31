import asyncio
import uuid
import time
import math
from typing import Dict, List, Optional, Any
from collections import defaultdict, deque
from datetime import datetime, timedelta
import numpy as np
from scipy import stats
from app.logging_module import get_logger
from .models import (
    OfflineMetric, OnlineMetric, MetricType,
    DriftAlert, DriftType, DriftSeverity,
    ModelEvaluation, DashboardSnapshot, ModelComparison
)


logger = get_logger(__name__)


class DriftDetector:
    def __init__(self, alert_threshold_p: float = 0.05, min_samples: int = 30):
        self._alert_threshold = alert_threshold_p
        self._min_samples = min_samples
        self._baselines: Dict[str, Dict[str, Any]] = {}
        self._current_data: Dict[str, deque] = defaultdict(
            lambda: deque(maxlen=1000)
        )
        self._alerts: List[DriftAlert] = []
    
    def set_baseline(self, metric_name: str, baseline_data: List[float], metadata: Dict[str, Any] = None):
        self._baselines[metric_name] = {
            "data": np.array(baseline_data),
            "mean": np.mean(baseline_data) if baseline_data else 0,
            "std": np.std(baseline_data) if len(baseline_data) > 1 else 1,
            "metadata": metadata or {},
            "set_at": datetime.utcnow()
        }
        logger.info(f"Set baseline for drift detection", metric=metric_name, samples=len(baseline_data))
    
    def record_data_point(self, metric_name: str, value: float):
        self._current_data[metric_name].append(value)
    
    def check_drift(self, metric_name: str) -> Optional[DriftAlert]:
        baseline = self._baselines.get(metric_name)
        if not baseline:
            return None
        
        current = list(self._current_data[metric_name])
        if len(current) < self._min_samples:
            return None
        
        current_arr = np.array(current)
        
        try:
            stat, p_value = stats.ks_2samp(baseline["data"], current_arr)
        except Exception as e:
            logger.warning(f"KS test failed", metric=metric_name, error=str(e))
            return None
        
        effect_size = self._calculate_effect_size(baseline["data"], current_arr)
        
        if p_value < self._alert_threshold:
            severity = self._determine_severity(p_value, effect_size)
            drift_type = self._detect_drift_type(baseline, current_arr)
            
            alert = DriftAlert(
                alert_id=f"drift_{uuid.uuid4().hex[:12]}",
                drift_type=drift_type,
                severity=severity,
                metric_name=metric_name,
                p_value=p_value,
                effect_size=effect_size,
                details={
                    "baseline_mean": float(baseline["mean"]),
                    "current_mean": float(np.mean(current_arr)),
                    "baseline_std": float(baseline["std"]),
                    "current_std": float(np.std(current_arr) if len(current_arr) > 1 else 0),
                    "ks_statistic": float(stat),
                    "sample_size": len(current)
                }
            )
            
            self._alerts.append(alert)
            logger.warning(
                f"Drift detected",
                metric=metric_name,
                severity=severity,
                p_value=p_value
            )
            return alert
        
        return None
    
    def _calculate_effect_size(self, baseline: np.ndarray, current: np.ndarray) -> float:
        mean_diff = abs(np.mean(baseline) - np.mean(current))
        pooled_std = np.sqrt((np.var(baseline) + np.var(current)) / 2)
        return mean_diff / pooled_std if pooled_std > 0 else 0
    
    def _determine_severity(self, p_value: float, effect_size: float) -> DriftSeverity:
        if p_value < 0.001 and effect_size > 0.8:
            return DriftSeverity.CRITICAL
        elif p_value < 0.01 and effect_size > 0.5:
            return DriftSeverity.HIGH
        elif p_value < 0.05 and effect_size > 0.3:
            return DriftSeverity.MEDIUM
        else:
            return DriftSeverity.LOW
    
    def _detect_drift_type(self, baseline: Dict[str, Any], current: np.ndarray) -> DriftType:
        baseline_mean = baseline["mean"]
        current_mean = np.mean(current)
        
        mean_shift = abs(baseline_mean - current_mean) / (baseline["std"] if baseline["std"] > 0 else 1)
        
        if mean_shift > 0.5:
            return DriftType.CONCEPT_DRIFT
        
        baseline_var = baseline["std"] ** 2
        current_var = np.var(current) if len(current) > 1 else 0
        var_ratio = max(baseline_var, current_var) / min(baseline_var, current_var) if min(baseline_var, current_var) > 0 else 1
        
        if var_ratio > 2:
            return DriftType.DATA_DRIFT
        
        return DriftType.PREDICTION_DRIFT
    
    def get_active_alerts(self) -> List[DriftAlert]:
        return [a for a in self._alerts if not a.acknowledged]
    
    def acknowledge_alert(self, alert_id: str) -> bool:
        for alert in self._alerts:
            if alert.alert_id == alert_id:
                alert.acknowledged = True
                return True
        return False


class OnlineMonitor:
    def __init__(self, window_seconds: int = 60, max_windows: int = 24):
        self._window_seconds = window_seconds
        self._max_windows = max_windows
        self._metrics: Dict[str, deque] = defaultdict(
            lambda: deque(maxlen=max_windows)
        )
        self._current_window: Dict[str, List[float]] = defaultdict(list)
        self._window_start: Dict[str, float] = {}
        self._task: Optional[asyncio.Task] = None
        self._running = False
    
    async def start(self):
        if self._running:
            return
        
        self._running = True
        self._task = asyncio.create_task(self._window_loop())
        logger.info("Online monitor started")
    
    async def stop(self):
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("Online monitor stopped")
    
    async def _window_loop(self):
        while self._running:
            await asyncio.sleep(self._window_seconds)
            await self._rollover_windows()
    
    async def _rollover_windows(self):
        now = time.time()
        
        for metric_name, values in list(self._current_window.items()):
            if values:
                metric = OnlineMetric(
                    metric_name=metric_name,
                    metric_type=MetricType.LATENCY if "latency" in metric_name.lower() else MetricType.THROUGHPUT,
                    value=sum(values) / len(values),
                    window_seconds=self._window_seconds,
                    sample_count=len(values)
                )
                self._metrics[metric_name].append(metric)
            
            self._current_window[metric_name] = []
    
    def record(self, metric_name: str, value: float):
        self._current_window[metric_name].append(value)
    
    def get_metric_history(self, metric_name: str, windows: int = 10) -> List[OnlineMetric]:
        history = list(self._metrics.get(metric_name, []))
        return history[-windows:]
    
    def get_current_value(self, metric_name: str) -> Optional[float]:
        current = self._current_window.get(metric_name, [])
        if not current:
            history = list(self._metrics.get(metric_name, []))
            if history:
                return history[-1].value
            return None
        return sum(current) / len(current)
    
    def get_statistics(self, metric_name: str, windows: int = 10) -> Dict[str, float]:
        history = self.get_metric_history(metric_name, windows)
        if not history:
            return {}
        
        values = [m.value for m in history]
        return {
            "mean": sum(values) / len(values),
            "min": min(values),
            "max": max(values),
            "std": np.std(values) if len(values) > 1 else 0,
            "latest": values[-1] if values else None
        }


class EvaluationDashboard:
    def __init__(self):
        self._offline_evaluations: Dict[str, List[OfflineMetric]] = defaultdict(list)
        self._model_versions: Dict[str, List[str]] = defaultdict(list)
        self._drift_detector = DriftDetector()
        self._online_monitor = OnlineMonitor()
        self._task: Optional[asyncio.Task] = None
        self._running = False
    
    async def start(self):
        await self._online_monitor.start()
        self._running = True
        self._task = asyncio.create_task(self._monitoring_loop())
        logger.info("Evaluation dashboard started")
    
    async def stop(self):
        self._running = False
        await self._online_monitor.stop()
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("Evaluation dashboard stopped")
    
    async def _monitoring_loop(self):
        while self._running:
            await asyncio.sleep(60)
            self._check_all_metrics_for_drift()
    
    def _check_all_metrics_for_drift(self):
        for metric_name in list(self._drift_detector._baselines.keys()):
            alert = self._drift_detector.check_drift(metric_name)
            if alert:
                logger.warning(
                    f"Drift alert generated",
                    metric=metric_name,
                    severity=alert.severity
                )
    
    def record_offline_metric(self, metric: OfflineMetric):
        model_key = f"{metric.model_version}"
        self._offline_evaluations[model_key].append(metric)
        
        if metric.model_version not in self._model_versions[metric.model_name]:
            self._model_versions[metric.model_name].append(metric.model_version)
        
        logger.info(
            f"Recorded offline metric",
            model=metric.model_name,
            version=metric.model_version,
            metric=metric.metric_name,
            value=metric.value
        )
    
    def record_online_metric(self, metric_name: str, value: float):
        self._online_monitor.record(metric_name, value)
        self._drift_detector.record_data_point(metric_name, value)
    
    def compare_models(
        self,
        model_a_version: str,
        model_b_version: str,
        metrics: Optional[List[str]] = None
    ) -> ModelComparison:
        metrics_a = self._offline_evaluations.get(model_a_version, [])
        metrics_b = self._offline_evaluations.get(model_b_version, [])
        
        a_dict = {m.metric_name: m for m in metrics_a}
        b_dict = {m.metric_name: m for m in metrics_b}
        
        all_metric_names = set(a_dict.keys()) | set(b_dict.keys())
        if metrics:
            all_metric_names = all_metric_names & set(metrics)
        
        comparison = {}
        a_wins = 0
        b_wins = 0
        
        for metric_name in all_metric_names:
            a_val = a_dict.get(metric_name)
            b_val = b_dict.get(metric_name)
            
            comparison[metric_name] = {
                "model_a": a_val.value if a_val else None,
                "model_b": b_val.value if b_val else None,
                "difference": (b_val.value - a_val.value) if (a_val and b_val) else None
            }
            
            if a_val and b_val:
                if metric_name in ["error_rate", "latency", "perplexity"]:
                    a_better = a_val.value < b_val.value
                else:
                    a_better = a_val.value > b_val.value
                
                if a_better:
                    a_wins += 1
                else:
                    b_wins += 1
        
        winner = None
        if a_wins > b_wins:
            winner = model_a_version
        elif b_wins > a_wins:
            winner = model_b_version
        
        return ModelComparison(
            model_a=model_a_version,
            model_b=model_b_version,
            metrics=comparison,
            winner=winner,
            statistical_significance=None
        )
    
    def get_model_evaluation(self, model_name: str, version: Optional[str] = None) -> Optional[ModelEvaluation]:
        if version is None:
            versions = self._model_versions.get(model_name, [])
            if not versions:
                return None
            version = versions[-1]
        
        offline_metrics = self._offline_evaluations.get(version, [])
        online_metrics = []
        active_alerts = self._drift_detector.get_active_alerts()
        
        overall_score = None
        if offline_metrics:
            values = []
            for m in offline_metrics:
                if m.metric_type in [MetricType.ERROR_RATE, MetricType.LATENCY, MetricType.PERPLEXITY]:
                    values.append(1 - min(m.value, 1))
                else:
                    values.append(m.value)
            overall_score = sum(values) / len(values) if values else None
        
        return ModelEvaluation(
            evaluation_id=f"eval_{uuid.uuid4().hex[:12]}",
            model_name=model_name,
            model_version=version,
            offline_metrics=offline_metrics,
            online_metrics=online_metrics,
            drift_alerts=active_alerts,
            overall_score=overall_score
        )
    
    def get_dashboard_snapshot(self) -> DashboardSnapshot:
        models_summary = {}
        
        for model_name, versions in self._model_versions.items():
            if versions:
                latest_version = versions[-1]
                evaluation = self.get_model_evaluation(model_name, latest_version)
                
                models_summary[model_name] = {
                    "latest_version": latest_version,
                    "versions_count": len(versions),
                    "overall_score": evaluation.overall_score if evaluation else None,
                    "metrics": {
                        m.metric_name: m.value
                        for m in (evaluation.offline_metrics if evaluation else [])
                    }
                }
        
        active_alerts = self._drift_detector.get_active_alerts()
        
        if active_alerts:
            max_severity = max(
                [DriftSeverity.LOW.value] + 
                [a.severity.value for a in active_alerts]
            )
            if max_severity in [DriftSeverity.CRITICAL.value, DriftSeverity.HIGH.value]:
                health = "degraded"
            else:
                health = "warning"
        else:
            health = "healthy"
        
        return DashboardSnapshot(
            snapshot_id=f"snap_{uuid.uuid4().hex[:12]}",
            models=models_summary,
            active_alerts=active_alerts,
            overall_health=health,
            summary={
                "total_models": len(self._model_versions),
                "total_versions": sum(len(v) for v in self._model_versions.values()),
                "active_alerts_count": len(active_alerts),
                "critical_alerts_count": sum(
                    1 for a in active_alerts
                    if a.severity in [DriftSeverity.CRITICAL, DriftSeverity.HIGH]
                )
            }
        )
    
    def set_drift_baseline(self, metric_name: str, baseline_data: List[float]):
        self._drift_detector.set_baseline(metric_name, baseline_data)
    
    def acknowledge_alert(self, alert_id: str) -> bool:
        return self._drift_detector.acknowledge_alert(alert_id)
    
    def get_online_statistics(self, metric_name: str) -> Dict[str, float]:
        return self._online_monitor.get_statistics(metric_name)
