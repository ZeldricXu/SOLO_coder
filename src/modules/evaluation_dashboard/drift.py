from typing import Dict, List, Optional, Any
from datetime import datetime, timedelta
from collections import defaultdict
import math
from .types import (
    DriftDetectionResult,
    DriftType,
    OnlineMetricPoint,
)
from src.core import generate_id
import logging

logger = logging.getLogger(__name__)


class DriftDetector:
    def __init__(self, default_threshold: float = 0.05):
        self.default_threshold = default_threshold
        self._reference_data: Dict[str, List[float]] = defaultdict(list)
        self._current_data: Dict[str, List[float]] = defaultdict(list)

    def set_reference(self, key: str, data: List[float]) -> None:
        self._reference_data[key] = list(data)
        logger.info(f"Set reference data for {key}: {len(data)} samples")

    def add_current(self, key: str, value: float) -> None:
        self._current_data[key].append(value)
        if len(self._current_data[key]) > 10000:
            self._current_data[key] = self._current_data[key][-10000:]

    def detect_drift(
        self,
        model_id: str,
        version_id: str,
        feature_name: Optional[str],
        drift_type: DriftType,
        threshold: Optional[float] = None,
        window_size: int = 100,
    ) -> DriftDetectionResult:
        key = f"{model_id}:{version_id}:{feature_name or 'global'}"
        reference = self._reference_data.get(key, [])
        current = self._current_data.get(key, [])[-window_size:]

        if not reference or not current:
            return DriftDetectionResult(
                drift_id=generate_id("drift"),
                model_id=model_id,
                version_id=version_id,
                drift_type=drift_type,
                feature_name=feature_name,
                is_drift=False,
                p_value=1.0,
                statistic=0.0,
                threshold=threshold or self.default_threshold,
                window_start=datetime.utcnow() - timedelta(hours=1),
                window_end=datetime.utcnow(),
            )

        ks_stat, p_value = self._ks_test(reference, current)
        is_drift = p_value < (threshold or self.default_threshold)

        result = DriftDetectionResult(
            drift_id=generate_id("drift"),
            model_id=model_id,
            version_id=version_id,
            drift_type=drift_type,
            feature_name=feature_name,
            is_drift=is_drift,
            p_value=round(p_value, 6),
            statistic=round(ks_stat, 6),
            threshold=threshold or self.default_threshold,
            window_start=datetime.utcnow() - timedelta(hours=1),
            window_end=datetime.utcnow(),
        )

        if is_drift:
            logger.warning(
                f"Drift detected for {key}: type={drift_type.value}, "
                f"p_value={p_value:.6f}, statistic={ks_stat:.6f}"
            )

        return result

    def _ks_test(self, reference: List[float], current: List[float]) -> tuple:
        ref_sorted = sorted(reference)
        cur_sorted = sorted(current)

        all_values = sorted(set(ref_sorted + cur_sorted))
        max_diff = 0.0

        for x in all_values:
            ref_cdf = sum(1 for v in ref_sorted if v <= x) / len(ref_sorted)
            cur_cdf = sum(1 for v in cur_sorted if v <= x) / len(cur_sorted)
            diff = abs(ref_cdf - cur_cdf)
            max_diff = max(max_diff, diff)

        n = len(reference)
        m = len(current)
        en = math.sqrt(n * m / (n + m))
        lambda_ = (en + 0.12 + 0.11 / en) * max_diff

        p_value = self._kolmogorov_smirnov_probability(lambda_)

        return max_diff, p_value

    def _kolmogorov_smirnov_probability(self, lambda_: float) -> float:
        if lambda_ < 0.2:
            return 1.0
        if lambda_ > 1.0:
            q_prime = 0.0
            for j in range(1, 101):
                sign = -1 if (j - 1) % 2 else 1
                term = sign * math.exp(-2 * j * j * lambda_ * lambda_)
                q_prime += term
                if abs(term) < 1e-10:
                    break
            return max(0.0, min(1.0, 2 * q_prime))

        q = 0.0
        for j in range(1, 101):
            term = 2 * (-1) ** (j - 1) * math.exp(-2 * j * j * lambda_ * lambda_)
            q += term
            if abs(term) < 1e-10:
                break
        return max(0.0, min(1.0, q))

    def detect_all_drifts(
        self,
        model_id: str,
        version_id: str,
        features: List[str],
    ) -> List[DriftDetectionResult]:
        results = []
        for feature in features:
            result = self.detect_drift(
                model_id=model_id,
                version_id=version_id,
                feature_name=feature,
                drift_type=DriftType.DATA_DRIFT,
            )
            results.append(result)

        pred_result = self.detect_drift(
            model_id=model_id,
            version_id=version_id,
            feature_name=None,
            drift_type=DriftType.PREDICTION_DRIFT,
        )
        results.append(pred_result)

        return results


class MetricsCalculator:
    @staticmethod
    def calculate_accuracy(y_true: List[Any], y_pred: List[Any]) -> float:
        if not y_true or len(y_true) != len(y_pred):
            return 0.0
        correct = sum(1 for t, p in zip(y_true, y_pred) if t == p)
        return correct / len(y_true)

    @staticmethod
    def calculate_precision(y_true: List[Any], y_pred: List[Any], positive_label: Any = 1) -> float:
        tp = sum(1 for t, p in zip(y_true, y_pred) if t == positive_label and p == positive_label)
        fp = sum(1 for t, p in zip(y_true, y_pred) if t != positive_label and p == positive_label)
        return tp / (tp + fp) if (tp + fp) > 0 else 0.0

    @staticmethod
    def calculate_recall(y_true: List[Any], y_pred: List[Any], positive_label: Any = 1) -> float:
        tp = sum(1 for t, p in zip(y_true, y_pred) if t == positive_label and p == positive_label)
        fn = sum(1 for t, p in zip(y_true, y_pred) if t == positive_label and p != positive_label)
        return tp / (tp + fn) if (tp + fn) > 0 else 0.0

    @staticmethod
    def calculate_f1(y_true: List[Any], y_pred: List[Any], positive_label: Any = 1) -> float:
        precision = MetricsCalculator.calculate_precision(y_true, y_pred, positive_label)
        recall = MetricsCalculator.calculate_recall(y_true, y_pred, positive_label)
        return 2 * (precision * recall) / (precision + recall) if (precision + recall) > 0 else 0.0

    @staticmethod
    def calculate_rmse(y_true: List[float], y_pred: List[float]) -> float:
        if not y_true or len(y_true) != len(y_pred):
            return 0.0
        squared_errors = [(t - p) ** 2 for t, p in zip(y_true, y_pred)]
        return math.sqrt(sum(squared_errors) / len(squared_errors))

    @staticmethod
    def calculate_mape(y_true: List[float], y_pred: List[float]) -> float:
        if not y_true or len(y_true) != len(y_pred):
            return 0.0
        errors = []
        for t, p in zip(y_true, y_pred):
            if t != 0:
                errors.append(abs((t - p) / t))
        return sum(errors) / len(errors) * 100 if errors else 0.0
