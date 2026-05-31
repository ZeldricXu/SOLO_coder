from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
import math
import statistics


@dataclass
class AlgorithmConfig:
    name: str
    sensitivity: float = 0.05
    params: Dict[str, Any] = field(default_factory=dict)


class AnomalyAlgorithm(ABC):
    def __init__(self, config: Optional[AlgorithmConfig] = None):
        self._config = config or AlgorithmConfig(name="generic")
        self._name = self._config.name
        self._history: List[float] = []
        self._baseline: Optional[Dict[str, Any]] = None

    @abstractmethod
    def fit(self, data: List[float]) -> None:
        pass

    @abstractmethod
    def detect(self, value: float) -> Dict[str, Any]:
        pass

    @abstractmethod
    def batch_detect(self, values: List[float]) -> List[Dict[str, Any]]:
        pass

    def get_name(self) -> str:
        return self._name

    def get_config(self) -> AlgorithmConfig:
        return self._config

    def get_baseline(self) -> Optional[Dict[str, Any]]:
        return self._baseline

    def update_history(self, value: float, max_size: int = 10000) -> None:
        self._history.append(value)
        if len(self._history) > max_size:
            self._history = self._history[-max_size:]


class ZScoreAlgorithm(AnomalyAlgorithm):
    def __init__(self, config: Optional[AlgorithmConfig] = None, threshold: float = 3.0):
        super().__init__(config or AlgorithmConfig(name="zscore"))
        self._threshold = threshold
        self._mean = 0.0
        self._std = 0.0

    def fit(self, data: List[float]) -> None:
        if not data:
            return
        self._history = list(data)
        self._mean = statistics.mean(data)
        self._std = statistics.stdev(data) if len(data) > 1 else 0.0
        self._baseline = {
            "mean": self._mean,
            "std": self._std,
            "threshold": self._threshold,
            "sample_count": len(data),
        }

    def detect(self, value: float) -> Dict[str, Any]:
        if self._std == 0:
            return {
                "is_anomaly": False,
                "score": 0.0,
                "z_score": 0.0,
                "threshold": self._threshold,
                "baseline": {"mean": self._mean, "std": 0},
            }
        z_score = (value - self._mean) / self._std
        is_anomaly = abs(z_score) > self._threshold
        return {
            "is_anomaly": is_anomaly,
            "score": abs(z_score),
            "z_score": z_score,
            "threshold": self._threshold,
            "baseline": {"mean": self._mean, "std": self._std},
        }

    def batch_detect(self, values: List[float]) -> List[Dict[str, Any]]:
        return [self.detect(v) for v in values]


class IQRAlgorithm(AnomalyAlgorithm):
    def __init__(self, config: Optional[AlgorithmConfig] = None, k: float = 1.5):
        super().__init__(config or AlgorithmConfig(name="iqr"))
        self._k = k
        self._q1 = 0.0
        self._q3 = 0.0
        self._iqr = 0.0
        self._lower_bound = 0.0
        self._upper_bound = 0.0

    def _quantile(self, data: List[float], q: float) -> float:
        sorted_data = sorted(data)
        n = len(sorted_data)
        if n == 0:
            return 0.0
        pos = (n - 1) * q
        lower = int(pos)
        upper = min(lower + 1, n - 1)
        if lower == upper:
            return sorted_data[lower]
        return sorted_data[lower] + (sorted_data[upper] - sorted_data[lower]) * (pos - lower)

    def fit(self, data: List[float]) -> None:
        if not data:
            return
        self._history = list(data)
        self._q1 = self._quantile(data, 0.25)
        self._q3 = self._quantile(data, 0.75)
        self._iqr = self._q3 - self._q1
        self._lower_bound = self._q1 - self._k * self._iqr
        self._upper_bound = self._q3 + self._k * self._iqr
        self._baseline = {
            "q1": self._q1,
            "q3": self._q3,
            "iqr": self._iqr,
            "lower_bound": self._lower_bound,
            "upper_bound": self._upper_bound,
            "k": self._k,
            "sample_count": len(data),
        }

    def detect(self, value: float) -> Dict[str, Any]:
        is_anomaly = value < self._lower_bound or value > self._upper_bound
        score = 0.0
        if self._iqr > 0:
            if value > self._upper_bound:
                score = (value - self._upper_bound) / self._iqr
            elif value < self._lower_bound:
                score = (self._lower_bound - value) / self._iqr
        return {
            "is_anomaly": is_anomaly,
            "score": score,
            "value": value,
            "bounds": {"lower": self._lower_bound, "upper": self._upper_bound},
            "baseline": {"q1": self._q1, "q3": self._q3, "iqr": self._iqr},
        }

    def batch_detect(self, values: List[float]) -> List[Dict[str, Any]]:
        return [self.detect(v) for v in values]


class MovingAverageAlgorithm(AnomalyAlgorithm):
    def __init__(
        self,
        config: Optional[AlgorithmConfig] = None,
        window_size: int = 20,
        threshold_std: float = 3.0,
    ):
        super().__init__(config or AlgorithmConfig(name="moving_avg"))
        self._window_size = window_size
        self._threshold_std = threshold_std

    def fit(self, data: List[float]) -> None:
        self._history = list(data)
        if len(data) >= self._window_size:
            window = data[-self._window_size:]
            mean = statistics.mean(window)
            std = statistics.stdev(window) if len(window) > 1 else 0.0
            self._baseline = {
                "window_size": self._window_size,
                "mean": mean,
                "std": std,
                "threshold_std": self._threshold_std,
                "sample_count": len(data),
            }

    def detect(self, value: float) -> Dict[str, Any]:
        window = self._history[-self._window_size:] if len(self._history) >= self._window_size else self._history
        if not window:
            return {
                "is_anomaly": False,
                "score": 0.0,
                "window_size": self._window_size,
                "baseline": {},
            }
        mean = statistics.mean(window)
        std = statistics.stdev(window) if len(window) > 1 else 0.0
        std = max(std, 1e-10)
        z_score = (value - mean) / std
        is_anomaly = abs(z_score) > self._threshold_std
        self._history.append(value)
        return {
            "is_anomaly": is_anomaly,
            "score": abs(z_score),
            "z_score": z_score,
            "window_size": len(window),
            "baseline": {"mean": mean, "std": std},
        }

    def batch_detect(self, values: List[float]) -> List[Dict[str, Any]]:
        return [self.detect(v) for v in values]


class EWMAAlgorithm(AnomalyAlgorithm):
    def __init__(
        self,
        config: Optional[AlgorithmConfig] = None,
        alpha: float = 0.3,
        threshold_std: float = 3.0,
    ):
        super().__init__(config or AlgorithmConfig(name="ewma"))
        self._alpha = alpha
        self._threshold_std = threshold_std
        self._ewma: Optional[float] = None
        self._ewm_var: Optional[float] = None
        self._count = 0

    def fit(self, data: List[float]) -> None:
        if not data:
            return
        self._history = list(data)
        self._count = 0
        self._ewma = None
        self._ewm_var = None
        for value in data:
            self._update(value)
        self._baseline = {
            "alpha": self._alpha,
            "ewma": self._ewma,
            "ewm_std": math.sqrt(self._ewm_var) if self._ewm_var else 0.0,
            "threshold_std": self._threshold_std,
            "sample_count": len(data),
        }

    def _update(self, value: float) -> None:
        if self._ewma is None:
            self._ewma = value
            self._ewm_var = 0.0
        else:
            diff = value - self._ewma
            self._ewma = self._alpha * value + (1 - self._alpha) * self._ewma
            var_inc = self._alpha * diff * diff
            self._ewm_var = (1 - self._alpha) * (self._ewm_var or 0.0) + var_inc
        self._count += 1

    def detect(self, value: float) -> Dict[str, Any]:
        if self._ewma is None:
            self._update(value)
            return {
                "is_anomaly": False,
                "score": 0.0,
                "baseline": {"ewma": self._ewma, "ewm_std": 0.0},
            }
        ewm_std = math.sqrt(self._ewm_var) if self._ewm_var else 0.0
        ewm_std = max(ewm_std, 1e-10)
        z_score = (value - self._ewma) / ewm_std
        is_anomaly = abs(z_score) > self._threshold_std
        result = {
            "is_anomaly": is_anomaly,
            "score": abs(z_score),
            "z_score": z_score,
            "baseline": {"ewma": self._ewma, "ewm_std": ewm_std},
        }
        self._update(value)
        return result

    def batch_detect(self, values: List[float]) -> List[Dict[str, Any]]:
        return [self.detect(v) for v in values]


class IsolationForestAlgorithm(AnomalyAlgorithm):
    def __init__(
        self,
        config: Optional[AlgorithmConfig] = None,
        n_trees: int = 100,
        subsample_size: int = 256,
        contamination: float = 0.1,
    ):
        super().__init__(config or AlgorithmConfig(name="isolation_forest"))
        self._n_trees = n_trees
        self._subsample_size = subsample_size
        self._contamination = contamination
        self._trees: List[Any] = []
        self._threshold: float = 0.5
        self._max_samples = 0

    def _average_path_length(self, n: int) -> float:
        if n <= 1:
            return 0.0
        return 2 * (math.log(n - 1) + 0.5772156649) - 2 * (n - 1) / n

    def _build_tree(self, data: List[float], depth: int = 0, max_depth: Optional[int] = None) -> Dict[str, Any]:
        if max_depth is None:
            max_depth = math.ceil(math.log2(len(data))) if data else 0
        if len(data) <= 1 or depth >= max_depth:
            return {"type": "leaf", "size": len(data)}
        min_val, max_val = min(data), max(data)
        if min_val == max_val:
            return {"type": "leaf", "size": len(data)}
        split_value = min_val + (max_val - min_val) * 0.5
        left = [v for v in data if v <= split_value]
        right = [v for v in data if v > split_value]
        if not left or not right:
            return {"type": "leaf", "size": len(data)}
        return {
            "type": "split",
            "split_value": split_value,
            "left": self._build_tree(left, depth + 1, max_depth),
            "right": self._build_tree(right, depth + 1, max_depth),
        }

    def _path_length(self, value: float, tree: Dict[str, Any], current_path: int = 0) -> float:
        if tree["type"] == "leaf":
            return current_path + self._average_path_length(max(1, tree["size"]))
        if value <= tree["split_value"]:
            return self._path_length(value, tree["left"], current_path + 1)
        return self._path_length(value, tree["right"], current_path + 1)

    def fit(self, data: List[float]) -> None:
        if not data:
            return
        self._history = list(data)
        self._trees = []
        self._max_samples = len(data)
        import random
        for _ in range(self._n_trees):
            subsample = random.sample(data, min(self._subsample_size, len(data))) if len(data) > 0 else []
            self._trees.append(self._build_tree(subsample))
        scores = [self._anomaly_score(v) for v in data]
        scores.sort()
        threshold_idx = int(len(scores) * (1 - self._contamination))
        self._threshold = scores[threshold_idx] if scores else 0.5
        self._baseline = {
            "n_trees": self._n_trees,
            "subsample_size": self._subsample_size,
            "contamination": self._contamination,
            "threshold": self._threshold,
            "sample_count": len(data),
        }

    def _anomaly_score(self, value: float) -> float:
        if not self._trees:
            return 0.0
        avg_path = sum(self._path_length(value, tree) for tree in self._trees) / len(self._trees)
        c = self._average_path_length(self._subsample_size)
        if c == 0:
            return 0.0
        return math.pow(2, -avg_path / c)

    def detect(self, value: float) -> Dict[str, Any]:
        score = self._anomaly_score(value)
        is_anomaly = score >= self._threshold
        return {
            "is_anomaly": is_anomaly,
            "score": score,
            "threshold": self._threshold,
            "baseline": {"n_trees": self._n_trees},
        }

    def batch_detect(self, values: List[float]) -> List[Dict[str, Any]]:
        return [self.detect(v) for v in values]


class SeasonalAlgorithm(AnomalyAlgorithm):
    def __init__(
        self,
        config: Optional[AlgorithmConfig] = None,
        period: int = 24,
        threshold_std: float = 3.0,
    ):
        super().__init__(config or AlgorithmConfig(name="seasonal"))
        self._period = period
        self._threshold_std = threshold_std
        self._seasonal_means: Dict[int, float] = {}
        self._seasonal_stds: Dict[int, float] = {}

    def fit(self, data: List[float]) -> None:
        if not data:
            return
        self._history = list(data)
        seasonal_data: Dict[int, List[float]] = {}
        for idx, value in enumerate(data):
            season = idx % self._period
            if season not in seasonal_data:
                seasonal_data[season] = []
            seasonal_data[season].append(value)
        for season, values in seasonal_data.items():
            self._seasonal_means[season] = statistics.mean(values)
            self._seasonal_stds[season] = statistics.stdev(values) if len(values) > 1 else 0.0
        self._baseline = {
            "period": self._period,
            "seasonal_means": self._seasonal_means,
            "seasonal_stds": self._seasonal_stds,
            "threshold_std": self._threshold_std,
            "sample_count": len(data),
        }

    def detect(self, value: float, season: Optional[int] = None) -> Dict[str, Any]:
        if season is None:
            season = len(self._history) % self._period
        self._history.append(value)
        mean = self._seasonal_means.get(season, 0.0)
        std = self._seasonal_stds.get(season, 0.0)
        std = max(std, 1e-10)
        z_score = (value - mean) / std
        is_anomaly = abs(z_score) > self._threshold_std
        return {
            "is_anomaly": is_anomaly,
            "score": abs(z_score),
            "z_score": z_score,
            "season": season,
            "baseline": {"mean": mean, "std": std},
        }

    def batch_detect(self, values: List[float]) -> List[Dict[str, Any]]:
        results = []
        start_season = len(self._history) % self._period
        for idx, value in enumerate(values):
            season = (start_season + idx) % self._period
            results.append(self.detect(value, season))
        return results


ALGORITHM_REGISTRY = {
    "zscore": ZScoreAlgorithm,
    "iqr": IQRAlgorithm,
    "moving_avg": MovingAverageAlgorithm,
    "ewma": EWMAAlgorithm,
    "isolation_forest": IsolationForestAlgorithm,
    "seasonal": SeasonalAlgorithm,
}


def create_algorithm(name: str, **kwargs) -> AnomalyAlgorithm:
    if name not in ALGORITHM_REGISTRY:
        raise ValueError(f"Unknown algorithm: {name}. Available: {list(ALGORITHM_REGISTRY.keys())}")
    return ALGORITHM_REGISTRY[name](**kwargs)
