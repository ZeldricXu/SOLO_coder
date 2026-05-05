import numpy as np
from typing import Dict, Any, Optional, Tuple, List
from scipy.signal import butter, filtfilt, lfilter
import warnings
from dataclasses import dataclass


@dataclass
class OrderRecommendation:
    recommended_order: int
    reason: str
    performance_impact: str
    alternatives: List[int]


class OrderAdvisor:
    MIN_ORDER = 1
    MAX_SAFE_ORDER = 10
    MAX_RECOMMENDED_ORDER = 20
    ABSOLUTE_MAX_ORDER = 30

    @staticmethod
    def estimate_computational_cost(order: int, data_length: int) -> float:
        return order * data_length * np.log2(data_length) if data_length > 0 else 0.0

    @staticmethod
    def get_transition_bandwidth_factor(filter_type: str) -> float:
        factors = {
            "lowpass": 1.0,
            "highpass": 1.0,
            "bandpass": 0.8,
            "bandstop": 1.2,
        }
        return factors.get(filter_type, 1.0)

    @staticmethod
    def recommend_order(
        data_length: int,
        sample_rate: float,
        filter_type: str,
        cutoff_freq: float,
        high_cutoff_freq: Optional[float] = None,
    ) -> OrderRecommendation:
        nyquist = 0.5 * sample_rate

        if filter_type in ["bandpass", "bandstop"]:
            if high_cutoff_freq is None:
                band_width = nyquist * 0.1
            else:
                band_width = high_cutoff_freq - cutoff_freq
        else:
            if filter_type == "lowpass":
                band_width = nyquist - cutoff_freq
            else:
                band_width = cutoff_freq

        transition_ratio = band_width / nyquist

        if transition_ratio >= 0.4:
            base_order = 2
            reason = "Wide transition band, low order sufficient"
        elif transition_ratio >= 0.2:
            base_order = 3
            reason = "Moderate transition band, standard low order"
        elif transition_ratio >= 0.1:
            base_order = 4
            reason = "Narrow transition band, moderate order needed"
        elif transition_ratio >= 0.05:
            base_order = 6
            reason = "Very narrow transition band, higher order required"
        else:
            base_order = 8
            reason = "Extremely narrow transition band, high order required"

        length_factor = min(3, max(1, int(np.log10(data_length + 1))))
        if data_length > 100000:
            base_order = max(2, base_order - 1)
            reason = f"{reason} (reduced for large dataset)"

        recommended_order = min(base_order, OrderAdvisor.MAX_SAFE_ORDER)

        if recommended_order <= 4:
            performance_impact = "Low computational cost, suitable for real-time processing"
        elif recommended_order <= 8:
            performance_impact = "Moderate computational cost, suitable for most applications"
        else:
            performance_impact = "Higher computational cost, may impact performance for large signals"

        alternatives = list(range(max(2, recommended_order - 2), min(recommended_order + 5, OrderAdvisor.MAX_RECOMMENDED_ORDER + 1)))

        return OrderRecommendation(
            recommended_order=recommended_order,
            reason=reason,
            performance_impact=performance_impact,
            alternatives=alternatives,
        )

    @staticmethod
    def validate_order(
        order: int,
        data_length: int,
        sample_rate: float,
        filter_type: str,
        cutoff_freq: float,
        high_cutoff_freq: Optional[float] = None,
    ) -> Tuple[bool, str, Optional[OrderRecommendation]]:
        if order < OrderAdvisor.MIN_ORDER:
            return (
                False,
                f"Filter order must be at least {OrderAdvisor.MIN_ORDER}",
                None,
            )

        if order > OrderAdvisor.ABSOLUTE_MAX_ORDER:
            return (
                False,
                f"Filter order cannot exceed {OrderAdvisor.ABSOLUTE_MAX_ORDER} (numerical stability reasons)",
                None,
            )

        recommendation = OrderAdvisor.recommend_order(
            data_length=data_length,
            sample_rate=sample_rate,
            filter_type=filter_type,
            cutoff_freq=cutoff_freq,
            high_cutoff_freq=high_cutoff_freq,
        )

        if order > OrderAdvisor.MAX_RECOMMENDED_ORDER:
            return (
                False,
                f"Order {order} exceeds recommended maximum ({OrderAdvisor.MAX_RECOMMENDED_ORDER}). "
                f"Recommended order: {recommendation.recommended_order}. "
                f"Performance warning: Very high orders may cause numerical instability and significant performance impact.",
                recommendation,
            )

        if order > recommendation.recommended_order + 2:
            return (
                True,
                f"Order {order} is higher than recommended ({recommendation.recommended_order}). "
                f"This may increase computational cost without significant benefit. "
                f"Consider using order {recommendation.recommended_order} instead.",
                recommendation,
            )

        return (
            True,
            f"Order {order} is within recommended range. Recommended: {recommendation.recommended_order}",
            recommendation,
        )

    @staticmethod
    def get_performance_warning(order: int, data_length: int) -> Optional[str]:
        cost = OrderAdvisor.estimate_computational_cost(order, data_length)

        if cost > 1e9:
            return (
                f"CRITICAL: Estimated computational cost is very high ({cost:.2e} operations). "
                f"Consider reducing filter order or using signal segmentation."
            )
        elif cost > 1e8:
            return (
                f"WARNING: Estimated computational cost is high ({cost:.2e} operations). "
                f"Processing large signals may be slow."
            )
        elif cost > 1e7:
            return (
                f"INFO: Estimated computational cost is moderate ({cost:.2e} operations)."
            )
        else:
            return None


class FilterConfig:
    def __init__(
        self,
        filter_type: str,
        cutoff_freq: float,
        high_cutoff_freq: Optional[float] = None,
        order: int = 4,
        method: str = "butterworth",
        allow_auto_order: bool = True,
    ):
        self.filter_id = f"filter_{filter_type}_{order}"
        self.filter_type = filter_type
        self.cutoff_freq = cutoff_freq
        self.high_cutoff_freq = high_cutoff_freq
        self.order = order
        self.method = method
        self.allow_auto_order = allow_auto_order
        self._validate()

    def _validate(self) -> None:
        valid_types = ["lowpass", "highpass", "bandpass", "bandstop"]
        if self.filter_type not in valid_types:
            raise ValueError(
                f"Invalid filter type: {self.filter_type}. Must be one of {valid_types}"
            )

        if self.order <= 0:
            raise ValueError("Filter order must be positive")

        if self.filter_type in ["bandpass", "bandstop"]:
            if self.high_cutoff_freq is None:
                raise ValueError("Bandpass/bandstop filters require high_cutoff_freq")
            if self.cutoff_freq >= self.high_cutoff_freq:
                raise ValueError("Low cutoff must be less than high cutoff")

        if self.cutoff_freq <= 0:
            raise ValueError("Cutoff frequency must be positive")

        valid_methods = ["butterworth"]
        if self.method not in valid_methods:
            raise ValueError(
                f"Invalid filter method: {self.method}. Must be one of {valid_methods}"
            )

    def to_dict(self) -> Dict[str, Any]:
        d = {
            "filter_id": self.filter_id,
            "filter_type": self.filter_type,
            "cutoff_freq": self.cutoff_freq,
            "order": self.order,
            "method": self.method,
        }
        if self.high_cutoff_freq is not None:
            d["high_cutoff_freq"] = self.high_cutoff_freq
        return d

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "FilterConfig":
        return cls(
            filter_type=data["filter_type"],
            cutoff_freq=data["cutoff_freq"],
            high_cutoff_freq=data.get("high_cutoff_freq"),
            order=data.get("order", 4),
            method=data.get("method", "butterworth"),
            allow_auto_order=data.get("allow_auto_order", True),
        )


@dataclass
class FilterValidationResult:
    valid: bool
    message: str
    warnings: List[str]
    order_recommendation: Optional[OrderRecommendation]


class FilterProcessor:
    def __init__(self):
        self.order_advisor = OrderAdvisor()

    @staticmethod
    def _design_butterworth(
        filter_type: str,
        cutoff_freq: float,
        high_cutoff_freq: Optional[float],
        order: int,
        sample_rate: float,
    ) -> Tuple[np.ndarray, np.ndarray]:
        nyquist = 0.5 * sample_rate

        if filter_type == "lowpass":
            normal_cutoff = cutoff_freq / nyquist
            b, a = butter(order, normal_cutoff, btype="low", analog=False)
        elif filter_type == "highpass":
            normal_cutoff = cutoff_freq / nyquist
            b, a = butter(order, normal_cutoff, btype="high", analog=False)
        elif filter_type == "bandpass":
            if high_cutoff_freq is None:
                raise ValueError("Bandpass filter requires high_cutoff_freq")
            low = cutoff_freq / nyquist
            high = high_cutoff_freq / nyquist
            b, a = butter(order, [low, high], btype="band", analog=False)
        elif filter_type == "bandstop":
            if high_cutoff_freq is None:
                raise ValueError("Bandstop filter requires high_cutoff_freq")
            low = cutoff_freq / nyquist
            high = high_cutoff_freq / nyquist
            b, a = butter(order, [low, high], btype="bandstop", analog=False)
        else:
            raise ValueError(f"Unknown filter type: {filter_type}")

        return b, a

    @staticmethod
    def validate_filter_params(
        config: FilterConfig, sample_rate: float
    ) -> Tuple[bool, str]:
        nyquist = 0.5 * sample_rate

        if config.cutoff_freq >= nyquist:
            return (
                False,
                f"Cutoff frequency ({config.cutoff_freq} Hz) must be less than Nyquist frequency ({nyquist} Hz)",
            )

        if config.high_cutoff_freq is not None:
            if config.high_cutoff_freq >= nyquist:
                return (
                    False,
                    f"High cutoff frequency ({config.high_cutoff_freq} Hz) must be less than Nyquist frequency ({nyquist} Hz)",
                )

        return True, "Parameters valid"

    @staticmethod
    def validate_and_advise(
        config: FilterConfig,
        data_length: int,
        sample_rate: float,
    ) -> FilterValidationResult:
        warnings = []

        valid, message = FilterProcessor.validate_filter_params(config, sample_rate)
        if not valid:
            return FilterValidationResult(
                valid=False,
                message=message,
                warnings=[],
                order_recommendation=None,
            )

        order_valid, order_message, recommendation = OrderAdvisor.validate_order(
            order=config.order,
            data_length=data_length,
            sample_rate=sample_rate,
            filter_type=config.filter_type,
            cutoff_freq=config.cutoff_freq,
            high_cutoff_freq=config.high_cutoff_freq,
        )

        if not order_valid:
            return FilterValidationResult(
                valid=False,
                message=order_message,
                warnings=[],
                order_recommendation=recommendation,
            )

        if "higher than recommended" in order_message:
            warnings.append(order_message)

        perf_warning = OrderAdvisor.get_performance_warning(config.order, data_length)
        if perf_warning:
            warnings.append(perf_warning)

        return FilterValidationResult(
            valid=True,
            message=message,
            warnings=warnings,
            order_recommendation=recommendation,
        )

    @staticmethod
    def recommend_order_for_signal(
        data: np.ndarray,
        sample_rate: float,
        filter_type: str,
        cutoff_freq: float,
        high_cutoff_freq: Optional[float] = None,
    ) -> OrderRecommendation:
        return OrderAdvisor.recommend_order(
            data_length=len(data),
            sample_rate=sample_rate,
            filter_type=filter_type,
            cutoff_freq=cutoff_freq,
            high_cutoff_freq=high_cutoff_freq,
        )

    @staticmethod
    def filter(
        data: np.ndarray,
        config: FilterConfig,
        sample_rate: float,
        use_filtfilt: bool = True,
        emit_warnings: bool = True,
    ) -> np.ndarray:
        if len(data) == 0:
            raise ValueError("Signal data is empty")

        validation = FilterProcessor.validate_and_advise(
            config=config,
            data_length=len(data),
            sample_rate=sample_rate,
        )

        if not validation.valid:
            raise ValueError(validation.message)

        if emit_warnings and validation.warnings:
            for warning in validation.warnings:
                warnings.warn(warning, UserWarning)

        if config.method == "butterworth":
            b, a = FilterProcessor._design_butterworth(
                filter_type=config.filter_type,
                cutoff_freq=config.cutoff_freq,
                high_cutoff_freq=config.high_cutoff_freq,
                order=config.order,
                sample_rate=sample_rate,
            )
        else:
            raise ValueError(f"Unsupported filter method: {config.method}")

        with warnings.catch_warnings():
            warnings.simplefilter("ignore", category=RuntimeWarning)
            if use_filtfilt:
                min_length = 3 * max(len(b), len(a))
                if len(data) < min_length:
                    if emit_warnings:
                        warnings.warn(
                            f"Signal length ({len(data)}) is short for filtfilt (requires at least {min_length}); "
                            f"using forward-only filtering instead",
                            UserWarning,
                        )
                    filtered = lfilter(b, a, data)
                else:
                    filtered = filtfilt(b, a, data)
            else:
                filtered = lfilter(b, a, data)

        if np.isnan(filtered).any() or np.isinf(filtered).any():
            raise RuntimeError("Filtering produced NaN or Inf values. "
                             "Consider reducing filter order or checking input data.")

        return filtered

    @staticmethod
    def filter_with_validation(
        data: np.ndarray,
        config: FilterConfig,
        sample_rate: float,
        use_filtfilt: bool = True,
    ) -> Tuple[np.ndarray, FilterValidationResult]:
        validation = FilterProcessor.validate_and_advise(
            config=config,
            data_length=len(data),
            sample_rate=sample_rate,
        )

        if not validation.valid:
            raise ValueError(validation.message)

        filtered = FilterProcessor.filter(
            data=data,
            config=config,
            sample_rate=sample_rate,
            use_filtfilt=use_filtfilt,
            emit_warnings=False,
        )

        return filtered, validation

    @staticmethod
    def lowpass_filter(
        data: np.ndarray,
        cutoff_freq: float,
        sample_rate: float,
        order: Optional[int] = None,
        use_filtfilt: bool = True,
        use_auto_order: bool = True,
    ) -> np.ndarray:
        if order is None and use_auto_order:
            recommendation = OrderAdvisor.recommend_order(
                data_length=len(data),
                sample_rate=sample_rate,
                filter_type="lowpass",
                cutoff_freq=cutoff_freq,
            )
            order = recommendation.recommended_order
        elif order is None:
            order = 4

        config = FilterConfig(
            filter_type="lowpass",
            cutoff_freq=cutoff_freq,
            order=order,
        )
        return FilterProcessor.filter(data, config, sample_rate, use_filtfilt)

    @staticmethod
    def highpass_filter(
        data: np.ndarray,
        cutoff_freq: float,
        sample_rate: float,
        order: Optional[int] = None,
        use_filtfilt: bool = True,
        use_auto_order: bool = True,
    ) -> np.ndarray:
        if order is None and use_auto_order:
            recommendation = OrderAdvisor.recommend_order(
                data_length=len(data),
                sample_rate=sample_rate,
                filter_type="highpass",
                cutoff_freq=cutoff_freq,
            )
            order = recommendation.recommended_order
        elif order is None:
            order = 4

        config = FilterConfig(
            filter_type="highpass",
            cutoff_freq=cutoff_freq,
            order=order,
        )
        return FilterProcessor.filter(data, config, sample_rate, use_filtfilt)

    @staticmethod
    def bandpass_filter(
        data: np.ndarray,
        low_cutoff: float,
        high_cutoff: float,
        sample_rate: float,
        order: Optional[int] = None,
        use_filtfilt: bool = True,
        use_auto_order: bool = True,
    ) -> np.ndarray:
        if order is None and use_auto_order:
            recommendation = OrderAdvisor.recommend_order(
                data_length=len(data),
                sample_rate=sample_rate,
                filter_type="bandpass",
                cutoff_freq=low_cutoff,
                high_cutoff_freq=high_cutoff,
            )
            order = recommendation.recommended_order
        elif order is None:
            order = 4

        config = FilterConfig(
            filter_type="bandpass",
            cutoff_freq=low_cutoff,
            high_cutoff_freq=high_cutoff,
            order=order,
        )
        return FilterProcessor.filter(data, config, sample_rate, use_filtfilt)

    @staticmethod
    def bandstop_filter(
        data: np.ndarray,
        low_cutoff: float,
        high_cutoff: float,
        sample_rate: float,
        order: Optional[int] = None,
        use_filtfilt: bool = True,
        use_auto_order: bool = True,
    ) -> np.ndarray:
        if order is None and use_auto_order:
            recommendation = OrderAdvisor.recommend_order(
                data_length=len(data),
                sample_rate=sample_rate,
                filter_type="bandstop",
                cutoff_freq=low_cutoff,
                high_cutoff_freq=high_cutoff,
            )
            order = recommendation.recommended_order
        elif order is None:
            order = 4

        config = FilterConfig(
            filter_type="bandstop",
            cutoff_freq=low_cutoff,
            high_cutoff_freq=high_cutoff,
            order=order,
        )
        return FilterProcessor.filter(data, config, sample_rate, use_filtfilt)
