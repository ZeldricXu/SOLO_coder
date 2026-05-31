from typing import Dict, List, Optional, Any
from datetime import datetime, timedelta
from .types import (
    MetricDefinition,
    EvaluationResult,
    EvaluationType,
    OnlineMetricPoint,
    DriftDetectionResult,
    ModelComparisonRequest,
    ModelComparisonResult,
    DashboardSummary,
    DriftType,
)
from .drift import DriftDetector, MetricsCalculator
from .metrics import MetricsStore
from src.core import (
    init_context,
    emit_event,
    get_metrics_collector,
    NotFoundError,
    PlatformError,
    generate_id,
)
import logging

logger = logging.getLogger(__name__)


class EvaluationDashboardService:
    def __init__(
        self):
        self.metrics_store = MetricsStore()
        self.drift_detector = DriftDetector()
        self.metrics_calculator = MetricsCalculator()
        self._evaluations: Dict[str, EvaluationResult] = {}
        self._drift_results: List[DriftDetectionResult] = []
        self._metrics = get_metrics_collector()

    async def define_metric(
        self,
        metric: MetricDefinition,
        trace_id: Optional[str] = None,
    ) -> MetricDefinition:
        with init_context(trace_id, operation="define_metric"):
            try:
                result = await self.metrics_store.define_metric(metric)
                emit_event(
                    "evaluation.metric.defined",
                    {"metric_id": result.metric_id, "name": result.name},
                    source="evaluation_dashboard",
                )
                return result
            except Exception as e:
                logger.error(f"Failed to define metric: {e}")
                raise PlatformError(f"指标定义失败: {str(e)}")

    async def create_evaluation(
        self,
        model_id: str,
        version_id: str,
        evaluation_type: EvaluationType,
        metrics: Dict[str, float],
        dataset: str = "",
        trace_id: Optional[str] = None,
    ) -> EvaluationResult:
        with init_context(trace_id, operation="create_evaluation"):
            try:
                evaluation_id = generate_id("eval")
                evaluation = EvaluationResult(
                    evaluation_id=evaluation_id,
                    model_id=model_id,
                    version_id=version_id,
                    evaluation_type=evaluation_type,
                    metrics=metrics,
                    dataset=dataset,
                    end_time=datetime.utcnow(),
                )

                self._evaluations[evaluation_id] = evaluation

                await self.metrics_store.record_offline_evaluation(
                    model_id, version_id, evaluation_id, metrics
                )

                emit_event(
                    "evaluation.created",
                    {"evaluation_id": evaluation_id, "model_id": model_id},
                    source="evaluation_dashboard",
                )

                self._metrics.increment("evaluation_created")
                return evaluation

            except Exception as e:
                logger.error(f"Failed to create evaluation: {e}")
                raise PlatformError(f"评估创建失败: {str(e)}")

    async def get_evaluation(
        self,
        evaluation_id: str,
        trace_id: Optional[str] = None,
    ) -> EvaluationResult:
        with init_context(trace_id, operation="get_evaluation"):
            evaluation = self._evaluations.get(evaluation_id)
            if not evaluation:
                raise NotFoundError(f"Evaluation not found: {evaluation_id}")
            return evaluation

    async def list_evaluations(
        self,
        model_id: Optional[str] = None,
        evaluation_type: Optional[EvaluationType] = None,
        limit: int = 100,
        trace_id: Optional[str] = None,
    ) -> List[EvaluationResult]:
        with init_context(trace_id, operation="list_evaluations"):
            evaluations = list(self._evaluations.values())
            if model_id:
                evaluations = [e for e in evaluations if e.model_id == model_id]
            if evaluation_type:
                evaluations = [e for e in evaluations if e.evaluation_type == evaluation_type]
            return sorted(evaluations, key=lambda e: e.created_at, reverse=True)[:limit]

    async def record_online_metric(
        self,
        point: OnlineMetricPoint,
        trace_id: Optional[str] = None,
    ) -> None:
        with init_context(trace_id, operation="record_online_metric"):
            try:
                await self.metrics_store.record_online_metric(point)
                self.drift_detector.add_current(
                    f"{point.model_id}:{point.version_id}:{point.metric_name}",
                    point.value,
                )
            except Exception as e:
                logger.error(f"Failed to record online metric: {e}")
                raise PlatformError(f"在线指标记录失败: {str(e)}")

    async def get_online_metric_stats(
        self,
        model_id: str,
        version_id: str,
        metric_name: str,
        window_minutes: int = 60,
        trace_id: Optional[str] = None,
    ) -> Dict[str, float]:
        with init_context(trace_id, operation="get_online_metric_stats"):
            return await self.metrics_store.get_online_metric_stats(
                model_id, version_id, metric_name, window_minutes
            )

    async def compare_models(
        self,
        request: ModelComparisonRequest,
        trace_id: Optional[str] = None,
    ) -> ModelComparisonResult:
        with init_context(trace_id, operation="compare_models"):
            try:
                comparison_id = generate_id("cmp")
                results = []

                for model_id, version_id in zip(request.model_ids, request.version_ids):
                    evaluations = await self.list_evaluations(
                        model_id=model_id,
                        evaluation_type=request.evaluation_type,
                        limit=1,
                    )

                    metrics_values = {}
                    if evaluations:
                        latest_eval = evaluations[0]
                        for metric in request.metrics:
                            metrics_values[metric] = latest_eval.metrics.get(metric, None)

                    online_stats = {}
                    for metric in request.metrics:
                        online_stats[metric] = await self.get_online_metric_stats(
                            model_id, version_id, metric
                        )

                    results.append({
                        "model_id": model_id,
                        "version_id": version_id,
                        "offline_metrics": metrics_values,
                        "online_stats": online_stats,
                    })

                return ModelComparisonResult(
                    comparison_id=comparison_id,
                    models=results,
                    metrics=request.metrics,
                )

            except Exception as e:
                logger.error(f"Failed to compare models: {e}")
                raise PlatformError(f"模型对比失败: {str(e)}")

    async def detect_drift(
        self,
        model_id: str,
        version_id: str,
        feature_name: Optional[str],
        drift_type: DriftType,
        threshold: Optional[float] = None,
        trace_id: Optional[str] = None,
    ) -> DriftDetectionResult:
        with init_context(trace_id, operation="detect_drift"):
            try:
                result = self.drift_detector.detect_drift(
                    model_id=model_id,
                    version_id=version_id,
                    feature_name=feature_name,
                    drift_type=drift_type,
                    threshold=threshold,
                )

                self._drift_results.append(result)

                if result.is_drift:
                    emit_event(
                        "evaluation.drift.detected",
                        {
                            "drift_id": result.drift_id,
                            "model_id": model_id,
                            "feature": feature_name,
                            "drift_type": drift_type.value,
                        },
                        source="evaluation_dashboard",
                    )
                    self._metrics.increment("drift_detected")

                return result

            except Exception as e:
                logger.error(f"Failed to detect drift: {e}")
                raise PlatformError(f"漂移检测失败: {str(e)}")

    async def set_drift_reference(
        self,
        model_id: str,
        version_id: str,
        feature_name: Optional[str],
        data: List[float],
        trace_id: Optional[str] = None,
    ) -> None:
        with init_context(trace_id, operation="set_drift_reference"):
            key = f"{model_id}:{version_id}:{feature_name or 'global'}"
            self.drift_detector.set_reference(key, data)

    async def get_dashboard_summary(
        self,
        trace_id: Optional[str] = None,
    ) -> DashboardSummary:
        with init_context(trace_id, operation="get_dashboard_summary"):
            total_models_set = set(e.model_id for e in self._evaluations.values())
            total_models = len(total_models_set)
            total_evaluations = len(self._evaluations)
            active_drifts = sum(1 for d in self._drift_results[-1000:] if d.is_drift)

            all_accuracies = []
            all_latencies = []

            for eval_result in self._evaluations.values():
                if "accuracy" in eval_result.metrics:
                    all_accuracies.append(eval_result.metrics["accuracy"])

            drift_key = ""
            for key, points in self.metrics_store._online_metrics.items():
                if "latency" in key:
                    for p in points[-1000:]:
                        all_latencies.append(p.value)

            avg_accuracy = sum(all_accuracies) / len(all_accuracies) if all_accuracies else 0.0

            if all_latencies:
                sorted_latencies = sorted(all_latencies)
                avg_latency = sum(sorted_latencies) / len(sorted_latencies)
                n = len(sorted_latencies)
                p99_idx = int(n * 0.99)
                p99_latency = sorted_latencies[min(p99_idx, n - 1)]
            else:
                avg_latency = 0.0
                p99_latency = 0.0

            return DashboardSummary(
                total_models=total_models,
                total_evaluations=total_evaluations,
                active_drifts=active_drifts,
                avg_accuracy=avg_accuracy,
                avg_latency_ms=avg_latency,
                p99_latency_ms=p99_latency,
            )

    def calculate_metric(
        self,
        metric_name: str,
        y_true: List[Any],
        y_pred: List[Any],
        **kwargs,
    ) -> float:
        calc = self.metrics_calculator
        method = getattr(calc, f"calculate_{metric_name.lower()}", None)
        if method:
            return method(y_true, y_pred, **kwargs)
        raise ValueError(f"Unsupported metric: {metric_name}")
