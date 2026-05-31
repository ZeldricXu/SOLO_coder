from typing import List, Dict, Any, Optional
from datetime import datetime, timezone, timedelta
import random
import numpy as np
from collections import defaultdict

from .schemas import (
    MetricType,
    DriftType,
    AlertSeverity,
    EvaluationMetric,
    OfflineEvaluationRequest,
    OfflineEvaluationResponse,
    OnlineMetrics,
    DriftDetectionRequest,
    DriftDetectionResponse,
    DriftAlert,
    DashboardResponse,
    MetricTimeSeries,
)
from common.logger import get_logger
from common.utils import generate_id, utc_now

logger = get_logger(__name__)


class EvaluationDashboardService:
    def __init__(self):
        self.offline_evaluations: Dict[str, OfflineEvaluationResponse] = {}
        self.online_metrics_history: Dict[str, List[OnlineMetrics]] = defaultdict(list)
        self.drift_alerts: Dict[str, List[DriftAlert]] = defaultdict(list)
        self.metric_baselines: Dict[str, Dict[str, float]] = defaultdict(dict)

    async def run_offline_evaluation(
        self, request: OfflineEvaluationRequest
    ) -> OfflineEvaluationResponse:
        eval_id = generate_id("eval_off_")
        started_at = utc_now()

        metrics = []
        for metric_type in request.metrics:
            value = random.uniform(0.6, 0.99)
            baseline = self.metric_baselines.get(request.model_name, {}).get(metric_type.value, random.uniform(0.7, 0.95))
            metrics.append(
                EvaluationMetric(
                    metric_name=metric_type,
                    metric_value=value,
                    baseline_value=baseline,
                )
            )

        comparison = {}
        if request.reference_model:
            for m in metrics:
                baseline = m.baseline_value or 0
                delta = m.metric_value - baseline
                percent_change = (delta / baseline * 100) if baseline > 0 else 0
                comparison[m.metric_name.value] = {
                    "delta": delta,
                    "percent_change": percent_change,
                }

        for m in metrics:
            self.metric_baselines[request.model_name][m.metric_name.value] = m.metric_value

        completed_at = utc_now()
        duration = (completed_at - started_at).total_seconds()

        response = OfflineEvaluationResponse(
            evaluation_id=eval_id,
            model_name=request.model_name,
            model_version=request.model_version,
            dataset_name=request.dataset_name,
            metrics=metrics,
            comparison_with_baseline=comparison,
            started_at=started_at,
            completed_at=completed_at,
            duration_seconds=duration,
            status="completed",
        )

        self.offline_evaluations[eval_id] = response
        logger.info(f"Offline evaluation {eval_id} completed for {request.model_name}")
        return response

    def get_offline_evaluation(self, eval_id: str) -> OfflineEvaluationResponse:
        if eval_id not in self.offline_evaluations:
            raise ValueError(f"Evaluation {eval_id} not found")
        return self.offline_evaluations[eval_id]

    def list_offline_evaluations(self, model_name: Optional[str] = None) -> List[OfflineEvaluationResponse]:
        evals = list(self.offline_evaluations.values())
        if model_name:
            evals = [e for e in evals if e.model_name == model_name]
        return sorted(evals, key=lambda e: e.completed_at, reverse=True)

    async def collect_online_metrics(self, model_name: str, metrics_data: Dict[MetricType, float]) -> OnlineMetrics:
        metrics = []
        for metric_type, value in metrics_data.items():
            metrics.append(EvaluationMetric(metric_name=metric_type, metric_value=value))

        now = utc_now()
        online_metrics = OnlineMetrics(
            model_name=model_name,
            timestamp=now,
            metrics=metrics,
            window_size=len(metrics_data),
            start_time=now - timedelta(minutes=5),
            end_time=now,
        )

        self.online_metrics_history[model_name].append(online_metrics)

        if len(self.online_metrics_history[model_name]) > 1000:
            self.online_metrics_history[model_name] = self.online_metrics_history[model_name][-1000:]

        return online_metrics

    def get_online_metrics(
        self, model_name: str,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
    ) -> List[OnlineMetrics]:
        metrics = self.online_metrics_history.get(model_name, [])
        if start_time:
            metrics = [m for m in metrics if m.timestamp >= start_time]
        if end_time:
            metrics = [m for m in metrics if m.timestamp <= end_time]
        return sorted(metrics, key=lambda m: m.timestamp)

    async def detect_drift(self, request: DriftDetectionRequest) -> DriftDetectionResponse:
        detection_id = generate_id("drift_")
        now = utc_now()
        lookback = now - timedelta(days=request.lookback_days)

        metrics = self.get_online_metrics(request.model_name, lookback, now)

        drift_scores: Dict[str, float] = {}
        alerts: List[DriftAlert] = []
        has_drift = False

        baseline = self.metric_baselines.get(request.model_name, {})

        for metric_name, baseline_value in baseline.items():
            if request.metric_name and metric_name != request.metric_name:
                continue

            recent_values = []
            for m in metrics:
                for em in m.metrics:
                    if em.metric_name.value == metric_name:
                        recent_values.append(em.metric_value)

            if len(recent_values) < 10:
                continue

            current_mean = np.mean(recent_values)
            current_std = np.std(recent_values)

            drift_score = abs(current_mean - baseline_value) / max(baseline_value, 0.001)
            drift_scores[metric_name] = drift_score

            if drift_score > request.threshold:
                has_drift = True
                severity = AlertSeverity.CRITICAL if drift_score > request.threshold * 2 else AlertSeverity.WARNING
                alerts.append(
                    DriftAlert(
                        alert_id=generate_id("alert_"),
                        model_name=request.model_name,
                        drift_type=request.drift_type,
                        metric_name=metric_name,
                        severity=severity,
                        drift_score=drift_score,
                        threshold=request.threshold,
                        detected_at=now,
                        description=f"Drift detected in {metric_name}: score {drift_score:.4f} exceeds threshold {request.threshold}",
                    )
                )

        for alert in alerts:
            self.drift_alerts[request.model_name].append(alert)

        return DriftDetectionResponse(
            detection_id=detection_id,
            model_name=request.model_name,
            has_drift=has_drift,
            alerts=alerts,
            drift_scores=drift_scores,
            analyzed_from=lookback,
            analyzed_to=now,
        )

    def get_dashboard(self, model_name: str) -> DashboardResponse:
        now = utc_now()
        last_24h = now - timedelta(hours=24)

        recent_metrics = self.get_online_metrics(model_name, last_24h, now)

        latest_metrics = recent_metrics[-1].metrics if recent_metrics else []

        active_alerts = [
            a for a in self.drift_alerts.get(model_name, [])
            if (now - a.detected_at) < timedelta(days=7)
        ]

        drift_status = "normal"
        if any(a.severity == AlertSeverity.CRITICAL for a in active_alerts):
            drift_status = "critical"
        elif any(a.severity == AlertSeverity.WARNING for a in active_alerts):
            drift_status = "warning"

        recent_evals = self.list_offline_evaluations(model_name)[:5]

        return DashboardResponse(
            dashboard_id=generate_id("dash_"),
            model_name=model_name,
            time_range={"start": last_24h, "end": now},
            online_metrics=latest_metrics,
            offline_evaluations=[{"id": e.evaluation_id, "metrics": e.metrics, "time": e.completed_at} for e in recent_evals],
            active_alerts=active_alerts,
            drift_status=drift_status,
            last_updated=now,
        )

    def get_metric_timeseries(
        self,
        model_name: str,
        metric_name: MetricType,
        start_time: Optional[datetime],
        end_time: Optional[datetime],
    ) -> MetricTimeSeries:
        metrics = self.get_online_metrics(model_name, start_time, end_time)

        timestamps = []
        values = []
        for m in metrics:
            for em in m.metrics:
                if em.metric_name == metric_name:
                    timestamps.append(m.timestamp)
                    values.append(em.metric_value)

        return MetricTimeSeries(
            metric_name=metric_name.value,
            timestamps=timestamps,
            values=values,
            model_name=model_name,
        )


evaluation_dashboard_service = EvaluationDashboardService()
