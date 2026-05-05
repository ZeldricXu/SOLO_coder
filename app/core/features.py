import numpy as np
from typing import Dict, Any, Optional, List, Tuple, Type, Callable
from dataclasses import dataclass, field
from enum import Enum
from copy import deepcopy
from scipy.stats import skew, kurtosis


class FeatureCategory(Enum):
    STATISTICAL = "statistical"
    TIME_DOMAIN = "time_domain"
    FREQUENCY_DOMAIN = "frequency_domain"
    ENERGY = "energy"
    SHAPE = "shape"


@dataclass
class FeatureDescriptor:
    name: str
    display_name: str
    description: str
    category: FeatureCategory
    required_params: List[str] = field(default_factory=list)
    optional_params: Dict[str, Any] = field(default_factory=dict)
    is_time_domain: bool = True
    is_frequency_domain: bool = False
    depends_on_sample_rate: bool = False

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "display_name": self.display_name,
            "description": self.description,
            "category": self.category.value,
            "required_params": self.required_params,
            "optional_params": self.optional_params,
            "is_time_domain": self.is_time_domain,
            "is_frequency_domain": self.is_frequency_domain,
            "depends_on_sample_rate": self.depends_on_sample_rate,
        }


@dataclass
class FeatureConfig:
    enabled_features: List[str] = field(default_factory=lambda: [
        "peak", "peak_to_peak", "mean", "variance", "std_dev",
        "rms", "crest_factor", "skewness", "kurtosis", "energy", "zero_crossing_rate"
    ])
    feature_params: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    include_metadata: bool = True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "enabled_features": self.enabled_features,
            "feature_params": self.feature_params,
            "include_metadata": self.include_metadata,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "FeatureConfig":
        return cls(
            enabled_features=data.get("enabled_features", cls().enabled_features),
            feature_params=data.get("feature_params", {}),
            include_metadata=data.get("include_metadata", True),
        )

    @classmethod
    def create_minimal(cls) -> "FeatureConfig":
        return cls(
            enabled_features=["mean", "std_dev", "rms"],
        )

    @classmethod
    def create_complete(cls) -> "FeatureConfig":
        return cls(
            enabled_features=list(FeatureRegistry.list_available_features()),
        )

    @classmethod
    def create_statistical(cls) -> "FeatureConfig":
        return cls(
            enabled_features=[
                "mean", "variance", "std_dev", "skewness", "kurtosis"
            ],
        )

    @classmethod
    def create_energy(cls) -> "FeatureConfig":
        return cls(
            enabled_features=[
                "rms", "energy", "crest_factor"
            ],
        )

    @classmethod
    def create_shape(cls) -> "FeatureConfig":
        return cls(
            enabled_features=[
                "peak", "peak_to_peak", "skewness", "kurtosis", "zero_crossing_rate"
            ],
        )


class FeatureRegistry:
    _descriptors: Dict[str, FeatureDescriptor] = {}
    _functions: Dict[str, Callable] = {}
    _categories: Dict[str, List[str]] = {}

    @classmethod
    def register(
        cls,
        descriptor: FeatureDescriptor,
        function: Callable,
    ) -> None:
        name = descriptor.name.lower()
        if name in cls._descriptors:
            raise ValueError(f"Feature '{name}' is already registered")

        cls._descriptors[name] = descriptor
        cls._functions[name] = function

        category = descriptor.category.value
        if category not in cls._categories:
            cls._categories[category] = []
        if name not in cls._categories[category]:
            cls._categories[category].append(name)

    @classmethod
    def unregister(cls, name: str) -> bool:
        name_lower = name.lower()
        if name_lower not in cls._descriptors:
            return False

        descriptor = cls._descriptors[name_lower]
        category = descriptor.category.value

        if category in cls._categories:
            if name_lower in cls._categories[category]:
                cls._categories[category].remove(name_lower)

        del cls._descriptors[name_lower]
        del cls._functions[name_lower]
        return True

    @classmethod
    def get_descriptor(cls, name: str) -> Optional[FeatureDescriptor]:
        return cls._descriptors.get(name.lower())

    @classmethod
    def get_function(cls, name: str) -> Optional[Callable]:
        return cls._functions.get(name.lower())

    @classmethod
    def list_available_features(cls) -> List[str]:
        return list(cls._descriptors.keys())

    @classmethod
    def list_feature_descriptors(cls) -> List[FeatureDescriptor]:
        return list(cls._descriptors.values())

    @classmethod
    def list_by_category(cls, category: FeatureCategory) -> List[str]:
        return cls._categories.get(category.value, [])

    @classmethod
    def get_all_descriptors(cls) -> List[Dict[str, Any]]:
        return [d.to_dict() for d in cls._descriptors.values()]


class FeatureFunctions:
    @staticmethod
    def compute_peak(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        return float(np.max(np.abs(data)))

    @staticmethod
    def compute_peak_to_peak(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        return float(np.max(data) - np.min(data))

    @staticmethod
    def compute_mean(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        return float(np.mean(data))

    @staticmethod
    def compute_variance(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        ddof = kwargs.get("ddof", 0)
        return float(np.var(data, ddof=ddof))

    @staticmethod
    def compute_std_dev(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        ddof = kwargs.get("ddof", 0)
        return float(np.std(data, ddof=ddof))

    @staticmethod
    def compute_rms(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        return float(np.sqrt(np.mean(data ** 2)))

    @staticmethod
    def compute_crest_factor(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        
        peak = FeatureFunctions.compute_peak(data)
        rms = FeatureFunctions.compute_rms(data)
        
        if rms == 0:
            return float('inf') if peak != 0 else 0.0
        
        return float(peak / rms)

    @staticmethod
    def compute_skewness(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        
        if len(data) < 3:
            return 0.0
        
        bias = kwargs.get("bias", True)
        return float(skew(data, bias=bias))

    @staticmethod
    def compute_kurtosis(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        
        if len(data) < 4:
            return 0.0
        
        fisher = kwargs.get("fisher", True)
        bias = kwargs.get("bias", True)
        return float(kurtosis(data, fisher=fisher, bias=bias))

    @staticmethod
    def compute_energy(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        
        normalize = kwargs.get("normalize", False)
        energy = np.sum(data ** 2)
        
        if normalize:
            energy = energy / len(data)
        
        return float(energy)

    @staticmethod
    def compute_zero_crossing_rate(data: np.ndarray, **kwargs) -> float:
        if len(data) < 2:
            raise ValueError("Signal data is too short")
        
        threshold = kwargs.get("threshold", 0.0)
        
        if threshold != 0:
            data_shifted = data - threshold
        else:
            data_shifted = data
        
        zero_crossings = np.sum(np.diff(np.signbit(data_shifted)))
        return float(zero_crossings / (len(data) - 1))

    @staticmethod
    def compute_median(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        return float(np.median(data))

    @staticmethod
    def compute_min(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        return float(np.min(data))

    @staticmethod
    def compute_max(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        return float(np.max(data))

    @staticmethod
    def compute_range(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        return float(np.max(data) - np.min(data))

    @staticmethod
    def compute_iqr(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        
        q75, q25 = np.percentile(data, [75, 25])
        return float(q75 - q25)

    @staticmethod
    def compute_mad(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        
        median = np.median(data)
        return float(np.median(np.abs(data - median)))

    @staticmethod
    def compute_sma(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        
        return float(np.sum(np.abs(data)))

    @staticmethod
    def compute_log_energy(data: np.ndarray, **kwargs) -> float:
        if len(data) == 0:
            raise ValueError("Signal data is empty")
        
        energy = np.sum(data ** 2)
        if energy <= 0:
            return float('-inf')
        
        return float(np.log10(energy))


STANDARD_FEATURES = [
    (
        FeatureDescriptor(
            name="peak",
            display_name="Peak Amplitude",
            description="Maximum absolute value of the signal",
            category=FeatureCategory.SHAPE,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_peak,
    ),
    (
        FeatureDescriptor(
            name="peak_to_peak",
            display_name="Peak-to-Peak",
            description="Difference between maximum and minimum values",
            category=FeatureCategory.SHAPE,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_peak_to_peak,
    ),
    (
        FeatureDescriptor(
            name="mean",
            display_name="Mean",
            description="Arithmetic mean of the signal",
            category=FeatureCategory.STATISTICAL,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_mean,
    ),
    (
        FeatureDescriptor(
            name="median",
            display_name="Median",
            description="Median value of the signal",
            category=FeatureCategory.STATISTICAL,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_median,
    ),
    (
        FeatureDescriptor(
            name="variance",
            display_name="Variance",
            description="Variance of the signal (ddof=0 by default)",
            category=FeatureCategory.STATISTICAL,
            is_time_domain=True,
            optional_params={"ddof": 0},
        ),
        FeatureFunctions.compute_variance,
    ),
    (
        FeatureDescriptor(
            name="std_dev",
            display_name="Standard Deviation",
            description="Standard deviation of the signal",
            category=FeatureCategory.STATISTICAL,
            is_time_domain=True,
            optional_params={"ddof": 0},
        ),
        FeatureFunctions.compute_std_dev,
    ),
    (
        FeatureDescriptor(
            name="rms",
            display_name="RMS",
            description="Root Mean Square value",
            category=FeatureCategory.ENERGY,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_rms,
    ),
    (
        FeatureDescriptor(
            name="crest_factor",
            display_name="Crest Factor",
            description="Ratio of peak amplitude to RMS value",
            category=FeatureCategory.ENERGY,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_crest_factor,
    ),
    (
        FeatureDescriptor(
            name="skewness",
            display_name="Skewness",
            description="Measure of asymmetry of the signal distribution",
            category=FeatureCategory.SHAPE,
            is_time_domain=True,
            optional_params={"bias": True},
        ),
        FeatureFunctions.compute_skewness,
    ),
    (
        FeatureDescriptor(
            name="kurtosis",
            display_name="Kurtosis",
            description="Measure of 'tailedness' of the signal distribution",
            category=FeatureCategory.SHAPE,
            is_time_domain=True,
            optional_params={"fisher": True, "bias": True},
        ),
        FeatureFunctions.compute_kurtosis,
    ),
    (
        FeatureDescriptor(
            name="energy",
            display_name="Energy",
            description="Sum of squared values",
            category=FeatureCategory.ENERGY,
            is_time_domain=True,
            optional_params={"normalize": False},
        ),
        FeatureFunctions.compute_energy,
    ),
    (
        FeatureDescriptor(
            name="log_energy",
            display_name="Log Energy",
            description="Logarithm of signal energy",
            category=FeatureCategory.ENERGY,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_log_energy,
    ),
    (
        FeatureDescriptor(
            name="zero_crossing_rate",
            display_name="Zero Crossing Rate",
            description="Rate of sign changes in the signal",
            category=FeatureCategory.TIME_DOMAIN,
            is_time_domain=True,
            optional_params={"threshold": 0.0},
        ),
        FeatureFunctions.compute_zero_crossing_rate,
    ),
    (
        FeatureDescriptor(
            name="min",
            display_name="Minimum",
            description="Minimum value of the signal",
            category=FeatureCategory.STATISTICAL,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_min,
    ),
    (
        FeatureDescriptor(
            name="max",
            display_name="Maximum",
            description="Maximum value of the signal",
            category=FeatureCategory.STATISTICAL,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_max,
    ),
    (
        FeatureDescriptor(
            name="range",
            display_name="Range",
            description="Difference between max and min (same as peak_to_peak)",
            category=FeatureCategory.SHAPE,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_range,
    ),
    (
        FeatureDescriptor(
            name="iqr",
            display_name="Interquartile Range",
            description="Difference between 75th and 25th percentiles",
            category=FeatureCategory.STATISTICAL,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_iqr,
    ),
    (
        FeatureDescriptor(
            name="mad",
            display_name="Median Absolute Deviation",
            description="Median of absolute deviations from median",
            category=FeatureCategory.STATISTICAL,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_mad,
    ),
    (
        FeatureDescriptor(
            name="sma",
            display_name="Sum of Magnitudes",
            description="Sum of absolute values",
            category=FeatureCategory.ENERGY,
            is_time_domain=True,
        ),
        FeatureFunctions.compute_sma,
    ),
]


for descriptor, function in STANDARD_FEATURES:
    FeatureRegistry.register(descriptor, function)


@dataclass
class SignalFeatures:
    values: Dict[str, float]
    config_used: FeatureConfig
    metadata: Dict[str, Any]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "values": self.values,
            "config_used": self.config_used.to_dict(),
            "metadata": self.metadata,
        }

    def get_value(self, feature_name: str) -> float:
        return self.values.get(feature_name.lower())

    def list_features(self) -> List[str]:
        return list(self.values.keys())


class FeatureExtractor:
    def __init__(self, default_config: Optional[FeatureConfig] = None):
        self.default_config = default_config or FeatureConfig()

    @staticmethod
    def list_available_features() -> List[str]:
        return FeatureRegistry.list_available_features()

    @staticmethod
    def get_feature_info(feature_name: str) -> Optional[Dict[str, Any]]:
        descriptor = FeatureRegistry.get_descriptor(feature_name)
        if descriptor:
            return descriptor.to_dict()
        return None

    @staticmethod
    def get_all_feature_info() -> List[Dict[str, Any]]:
        return FeatureRegistry.get_all_descriptors()

    @staticmethod
    def list_features_by_category(category: FeatureCategory) -> List[str]:
        return FeatureRegistry.list_by_category(category)

    @staticmethod
    def register_custom_feature(
        name: str,
        display_name: str,
        description: str,
        function: Callable,
        category: FeatureCategory = FeatureCategory.STATISTICAL,
        required_params: Optional[List[str]] = None,
        optional_params: Optional[Dict[str, Any]] = None,
    ) -> None:
        descriptor = FeatureDescriptor(
            name=name,
            display_name=display_name,
            description=description,
            category=category,
            required_params=required_params or [],
            optional_params=optional_params or {},
        )
        FeatureRegistry.register(descriptor, function)

    @staticmethod
    def unregister_feature(name: str) -> bool:
        return FeatureRegistry.unregister(name)

    def extract_features(
        self,
        data: np.ndarray,
        config: Optional[FeatureConfig] = None,
        sample_rate: Optional[float] = None,
    ) -> SignalFeatures:
        if len(data) == 0:
            raise ValueError("Signal data is empty")

        use_config = config or self.default_config

        values: Dict[str, float] = {}
        errors: Dict[str, str] = {}

        for feature_name in use_config.enabled_features:
            function = FeatureRegistry.get_function(feature_name)
            if function is None:
                errors[feature_name] = f"Feature '{feature_name}' is not registered"
                continue

            descriptor = FeatureRegistry.get_descriptor(feature_name)
            params = use_config.feature_params.get(feature_name, {})

            if sample_rate is not None and descriptor and descriptor.depends_on_sample_rate:
                params["sample_rate"] = sample_rate

            try:
                value = function(data, **params)
                values[feature_name] = value
            except Exception as e:
                errors[feature_name] = str(e)

        metadata = {
            "n_samples": len(data),
            "sample_rate_provided": sample_rate is not None,
            "errors": errors,
            "timestamp": None,
        }

        if sample_rate is not None:
            metadata["sample_rate"] = sample_rate

        return SignalFeatures(
            values=values,
            config_used=use_config,
            metadata=metadata,
        )

    @staticmethod
    def extract_all_features(
        data: np.ndarray,
        sample_rate: Optional[float] = None,
    ) -> SignalFeatures:
        config = FeatureConfig.create_complete()
        extractor = FeatureExtractor()
        return extractor.extract_features(data, config, sample_rate)

    @staticmethod
    def compute_segmented_features(
        data: np.ndarray,
        segment_size: int,
        overlap: int = 0,
        config: Optional[FeatureConfig] = None,
        sample_rate: Optional[float] = None,
    ) -> List[Dict[str, Any]]:
        if len(data) == 0:
            raise ValueError("Signal data is empty")

        if segment_size <= 0:
            raise ValueError("Segment size must be positive")

        if overlap >= segment_size:
            raise ValueError("Overlap must be less than segment size")

        hop_size = segment_size - overlap
        n_segments = (len(data) - segment_size) // hop_size + 1

        if n_segments <= 0:
            raise ValueError("Signal too short for given segment size")

        extractor = FeatureExtractor(config)
        results = []

        for i in range(n_segments):
            start = i * hop_size
            end = start + segment_size

            segment = data[start:end]
            features = extractor.extract_features(segment, config, sample_rate)

            results.append({
                "segment_index": i,
                "start_sample": start,
                "end_sample": end,
                "start_time": start / sample_rate if sample_rate else None,
                "end_time": end / sample_rate if sample_rate else None,
                "features": features.values,
                "metadata": features.metadata,
            })

        return results

    @staticmethod
    def compare_signals(
        data1: np.ndarray,
        data2: np.ndarray,
        sample_rate: Optional[float] = None,
        config: Optional[FeatureConfig] = None,
    ) -> Dict[str, float]:
        if len(data1) == 0 or len(data2) == 0:
            raise ValueError("Signal data is empty")

        min_len = min(len(data1), len(data2))
        data1_trim = data1[:min_len]
        data2_trim = data2[:min_len]

        extractor = FeatureExtractor(config)
        features1 = extractor.extract_features(data1_trim, sample_rate=sample_rate)
        features2 = extractor.extract_features(data2_trim, sample_rate=sample_rate)

        common_features = set(features1.values.keys()) & set(features2.values.keys())

        correlation = np.correlate(
            data1_trim - np.mean(data1_trim),
            data2_trim - np.mean(data2_trim),
            mode="valid",
        )
        norm = np.std(data1_trim) * np.std(data2_trim) * min_len
        if norm > 0:
            correlation = correlation[0] / norm
        else:
            correlation = 0.0

        mse = np.mean((data1_trim - data2_trim) ** 2)
        rmse = np.sqrt(mse)

        snr_signal = np.sum(data1_trim ** 2)
        snr_noise = np.sum((data1_trim - data2_trim) ** 2)
        if snr_noise > 0:
            snr = 10 * np.log10(snr_signal / snr_noise)
        else:
            snr = float('inf')

        feature_differences = {}
        for feat in common_features:
            diff = abs(features1.values[feat] - features2.values[feat])
            feature_differences[feat] = float(diff)

        result = {
            "correlation": float(correlation),
            "mse": float(mse),
            "rmse": float(rmse),
            "snr": float(snr),
            "common_features_count": len(common_features),
            "feature_differences": feature_differences,
        }

        return result
