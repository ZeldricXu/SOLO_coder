import numpy as np
from typing import Dict, Any, List, Optional, Tuple, Union
from scipy import stats
from dataclasses import dataclass, field

class StatsEngineError(Exception):
    pass

class InvalidDataError(StatsEngineError):
    pass

class InsufficientDataError(StatsEngineError):
    pass

@dataclass
class DescriptiveStats:
    count: int
    mean: float
    median: float
    mode: Optional[float]
    std_dev: float
    variance: float
    min: float
    max: float
    range: float
    q1: float
    q3: float
    iqr: float
    skewness: float
    kurtosis: float
    data: np.ndarray = field(repr=False)

@dataclass
class LinearRegressionResult:
    slope: float
    intercept: float
    r_value: float
    r_squared: float
    p_value: float
    std_err: float
    predicted_values: List[float]
    residuals: List[float]

class StatsComputeEngine:
    
    MIN_SAMPLE_SIZE = 2
    
    def __init__(self):
        pass
    
    def _validate_data(
        self, 
        data: Any, 
        name: str = "data",
        min_size: int = None
    ) -> np.ndarray:
        if min_size is None:
            min_size = self.MIN_SAMPLE_SIZE
        
        try:
            if isinstance(data, np.ndarray):
                arr = data
            elif isinstance(data, (list, tuple)):
                arr = np.array(data, dtype=np.float64)
            elif isinstance(data, dict):
                values = []
                for v in data.values():
                    if isinstance(v, (int, float)):
                        values.append(v)
                arr = np.array(values, dtype=np.float64)
            else:
                raise InvalidDataError(f"{name} must be a list, tuple, or numpy array")
            
            if arr.ndim > 1:
                arr = arr.flatten()
            
            if len(arr) < min_size:
                raise InsufficientDataError(
                    f"{name} has insufficient samples: {len(arr)} < {min_size}"
                )
            
            if not np.isfinite(arr).all():
                raise InvalidDataError(f"{name} contains infinite or NaN values")
            
            return arr
            
        except StatsEngineError:
            raise
        except Exception as e:
            raise InvalidDataError(f"Failed to validate {name}: {str(e)}")
    
    def descriptive_stats(
        self, 
        data: Union[List[float], np.ndarray],
        include_mode: bool = True
    ) -> Dict[str, Any]:
        try:
            arr = self._validate_data(data, "data")
            
            mean = float(np.mean(arr))
            median = float(np.median(arr))
            
            if include_mode and len(arr) > 0:
                try:
                    mode_result = stats.mode(arr, keepdims=True)
                    mode_value = float(mode_result.mode[0]) if len(mode_result.mode) > 0 else None
                except Exception:
                    mode_value = None
            else:
                mode_value = None
            
            std_dev = float(np.std(arr, ddof=1))
            variance = float(np.var(arr, ddof=1))
            min_val = float(np.min(arr))
            max_val = float(np.max(arr))
            range_val = max_val - min_val
            
            q1 = float(np.percentile(arr, 25))
            q3 = float(np.percentile(arr, 75))
            iqr = q3 - q1
            
            if len(arr) >= 3:
                try:
                    skewness = float(stats.skew(arr, bias=False))
                except Exception:
                    skewness = 0.0
            else:
                skewness = 0.0
            
            if len(arr) >= 4:
                try:
                    kurtosis = float(stats.kurtosis(arr, bias=False))
                except Exception:
                    kurtosis = 0.0
            else:
                kurtosis = 0.0
            
            result = DescriptiveStats(
                count=len(arr),
                mean=mean,
                median=median,
                mode=mode_value,
                std_dev=std_dev,
                variance=variance,
                min=min_val,
                max=max_val,
                range=range_val,
                q1=q1,
                q3=q3,
                iqr=iqr,
                skewness=skewness,
                kurtosis=kurtosis,
                data=arr
            )
            
            return {
                'count': result.count,
                'mean': result.mean,
                'median': result.median,
                'mode': result.mode,
                'std_dev': result.std_dev,
                'variance': result.variance,
                'min': result.min,
                'max': result.max,
                'range': result.range,
                'q1': result.q1,
                'q3': result.q3,
                'iqr': result.iqr,
                'skewness': result.skewness,
                'kurtosis': result.kurtosis,
                'operation': 'descriptive_stats'
            }
            
        except StatsEngineError:
            raise
        except Exception as e:
            raise StatsEngineError(f"Descriptive statistics failed: {str(e)}")
    
    def linear_regression(
        self,
        x_data: Union[List[float], np.ndarray],
        y_data: Union[List[float], np.ndarray]
    ) -> Dict[str, Any]:
        try:
            x = self._validate_data(x_data, "x_data", min_size=3)
            y = self._validate_data(y_data, "y_data", min_size=3)
            
            if len(x) != len(y):
                raise InvalidDataError(
                    f"x_data and y_data must have same length: {len(x)} != {len(y)}"
                )
            
            if np.std(x) < 1e-10:
                raise InvalidDataError("x_data has zero variance, cannot perform regression")
            
            slope, intercept, r_value, p_value, std_err = stats.linregress(x, y)
            
            predicted = slope * x + intercept
            residuals = y - predicted
            
            result = LinearRegressionResult(
                slope=float(slope),
                intercept=float(intercept),
                r_value=float(r_value),
                r_squared=float(r_value ** 2),
                p_value=float(p_value),
                std_err=float(std_err),
                predicted_values=predicted.tolist(),
                residuals=residuals.tolist()
            )
            
            return {
                'slope': result.slope,
                'intercept': result.intercept,
                'r_value': result.r_value,
                'r_squared': result.r_squared,
                'p_value': result.p_value,
                'std_err': result.std_err,
                'equation': f"y = {result.slope:.4f}x + {result.intercept:.4f}",
                'predicted_values': result.predicted_values,
                'residuals': result.residuals,
                'x_values': x.tolist(),
                'y_values': y.tolist(),
                'operation': 'linear_regression'
            }
            
        except StatsEngineError:
            raise
        except Exception as e:
            raise StatsEngineError(f"Linear regression failed: {str(e)}")
    
    def t_test(
        self,
        data1: Union[List[float], np.ndarray],
        data2: Optional[Union[List[float], np.ndarray]] = None,
        popmean: Optional[float] = None,
        test_type: str = "independent"
    ) -> Dict[str, Any]:
        try:
            arr1 = self._validate_data(data1, "data1", min_size=2)
            
            if test_type == "one_sample":
                if popmean is None:
                    raise InvalidDataError("popmean is required for one-sample t-test")
                
                t_stat, p_value = stats.ttest_1samp(arr1, popmean)
                
                return {
                    'test_type': 'one_sample',
                    't_statistic': float(t_stat),
                    'p_value': float(p_value),
                    'popmean': popmean,
                    'sample_mean': float(np.mean(arr1)),
                    'operation': 't_test'
                }
            
            elif test_type == "independent":
                if data2 is None:
                    raise InvalidDataError("data2 is required for independent t-test")
                
                arr2 = self._validate_data(data2, "data2", min_size=2)
                
                t_stat, p_value = stats.ttest_ind(arr1, arr2, equal_var=False)
                
                return {
                    'test_type': 'independent',
                    't_statistic': float(t_stat),
                    'p_value': float(p_value),
                    'mean1': float(np.mean(arr1)),
                    'mean2': float(np.mean(arr2)),
                    'std1': float(np.std(arr1, ddof=1)),
                    'std2': float(np.std(arr2, ddof=1)),
                    'operation': 't_test'
                }
            
            elif test_type == "paired":
                if data2 is None:
                    raise InvalidDataError("data2 is required for paired t-test")
                
                arr2 = self._validate_data(data2, "data2", min_size=2)
                
                if len(arr1) != len(arr2):
                    raise InvalidDataError("data1 and data2 must have same length for paired t-test")
                
                t_stat, p_value = stats.ttest_rel(arr1, arr2)
                
                return {
                    'test_type': 'paired',
                    't_statistic': float(t_stat),
                    'p_value': float(p_value),
                    'mean_diff': float(np.mean(arr1 - arr2)),
                    'operation': 't_test'
                }
            
            else:
                raise InvalidDataError(f"Unknown test type: {test_type}")
                
        except StatsEngineError:
            raise
        except Exception as e:
            raise StatsEngineError(f"T-test failed: {str(e)}")
    
    def correlation(
        self,
        x_data: Union[List[float], np.ndarray],
        y_data: Union[List[float], np.ndarray],
        method: str = "pearson"
    ) -> Dict[str, Any]:
        try:
            x = self._validate_data(x_data, "x_data", min_size=3)
            y = self._validate_data(y_data, "y_data", min_size=3)
            
            if len(x) != len(y):
                raise InvalidDataError(
                    f"x_data and y_data must have same length: {len(x)} != {len(y)}"
                )
            
            if method == "pearson":
                corr, p_value = stats.pearsonr(x, y)
            elif method == "spearman":
                corr, p_value = stats.spearmanr(x, y)
            elif method == "kendall":
                corr, p_value = stats.kendalltau(x, y)
            else:
                raise InvalidDataError(f"Unknown correlation method: {method}")
            
            return {
                'method': method,
                'correlation': float(corr),
                'p_value': float(p_value),
                'absolute_value': abs(float(corr)),
                'strength': self._interpret_correlation(abs(float(corr))),
                'operation': 'correlation'
            }
            
        except StatsEngineError:
            raise
        except Exception as e:
            raise StatsEngineError(f"Correlation analysis failed: {str(e)}")
    
    def _interpret_correlation(self, r: float) -> str:
        if r >= 0.9:
            return "very strong"
        elif r >= 0.7:
            return "strong"
        elif r >= 0.5:
            return "moderate"
        elif r >= 0.3:
            return "weak"
        else:
            return "very weak or none"
    
    def probability_distribution(
        self,
        data: Union[List[float], np.ndarray],
        distribution: str = "normal",
        fit_params: bool = True
    ) -> Dict[str, Any]:
        try:
            arr = self._validate_data(data, "data", min_size=5)
            
            dist_map = {
                'normal': stats.norm,
                'exponential': stats.expon,
                'uniform': stats.uniform,
                'gamma': stats.gamma,
                'beta': stats.beta,
                'lognormal': stats.lognorm
            }
            
            if distribution not in dist_map:
                raise InvalidDataError(
                    f"Unknown distribution: {distribution}. "
                    f"Supported: {list(dist_map.keys())}"
                )
            
            dist = dist_map[distribution]
            
            if fit_params:
                params = dist.fit(arr)
            else:
                params = ()
            
            ks_stat, ks_pvalue = stats.kstest(arr, distribution, args=params)
            
            pdf_x = np.linspace(np.min(arr), np.max(arr), 100)
            pdf_y = dist.pdf(pdf_x, *params)
            
            return {
                'distribution': distribution,
                'parameters': [float(p) for p in params],
                'ks_statistic': float(ks_stat),
                'ks_p_value': float(ks_pvalue),
                'pdf_x': pdf_x.tolist(),
                'pdf_y': pdf_y.tolist(),
                'data_histogram': self._create_histogram(arr),
                'operation': 'probability_distribution'
            }
            
        except StatsEngineError:
            raise
        except Exception as e:
            raise StatsEngineError(f"Probability distribution fitting failed: {str(e)}")
    
    def _create_histogram(self, arr: np.ndarray, bins: int = 20) -> Dict[str, Any]:
        try:
            counts, bin_edges = np.histogram(arr, bins=bins)
            bin_centers = (bin_edges[:-1] + bin_edges[1:]) / 2
            
            return {
                'counts': counts.tolist(),
                'bin_edges': bin_edges.tolist(),
                'bin_centers': bin_centers.tolist(),
                'num_bins': len(counts)
            }
        except Exception:
            return {'counts': [], 'bin_edges': [], 'bin_centers': [], 'num_bins': 0}
