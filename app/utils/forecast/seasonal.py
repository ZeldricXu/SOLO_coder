import numpy as np
import pandas as pd
from typing import Optional, Tuple, Dict, Any, Union

try:
    from statsmodels.tsa.seasonal import STL, seasonal_decompose as sm_seasonal_decompose
    _has_stl = True
except ImportError:
    _has_stl = False

from scipy import stats


def seasonal_decompose(
    data: Union[pd.Series, list, np.ndarray],
    period: Optional[int] = None,
    model: str = "additive",
    method: str = "stl",
) -> Dict[str, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    n = len(data)

    if period is None:
        period = detect_seasonality(data)

    if period is None or period < 2 or period >= n:
        period = min(7, max(2, n // 4))

    result: Dict[str, np.ndarray] = {}

    try:
        if _has_stl and method.lower() == "stl":
            stl = STL(data.values, period=period, robust=True)
            decompose_result = stl.fit()
            result["trend"] = decompose_result.trend
            result["seasonal"] = decompose_result.seasonal
            result["residual"] = decompose_result.resid
            result["observed"] = data.values
        elif _has_stl:
            decompose_result = sm_seasonal_decompose(
                data.values,
                model=model,
                period=period,
                extrapolate_trend="freq"
            )
            result["trend"] = np.array(decompose_result.trend)
            result["seasonal"] = np.array(decompose_result.seasonal)
            result["residual"] = np.array(decompose_result.resid)
            result["observed"] = data.values
        else:
            result = _simple_seasonal_decompose(data.values, period, model)

        result["trend"] = np.where(np.isnan(result["trend"]), 0, result["trend"])
        result["seasonal"] = np.where(np.isnan(result["seasonal"]), 0, result["seasonal"])
        result["residual"] = np.where(np.isnan(result["residual"]), 0, result["residual"])

    except Exception:
        result = _simple_seasonal_decompose(data.values, period, model)

    result["period"] = period
    result["model"] = model

    return result


def _simple_seasonal_decompose(
    data: np.ndarray,
    period: int,
    model: str = "additive"
) -> Dict[str, np.ndarray]:
    n = len(data)

    trend = _calculate_trend(data, period)

    if model == "additive":
        detrended = data - trend
    else:
        detrended = data / np.where(trend == 0, 1, trend)

    seasonal = np.zeros(n)
    for i in range(period):
        indices = np.arange(i, n, period)
        if len(indices) > 0:
            seasonal[indices] = np.mean(detrended[indices])

    if model == "additive":
        residual = data - trend - seasonal
    else:
        residual = data / np.where((trend * seasonal) == 0, 1, trend * seasonal)

    return {
        "trend": trend,
        "seasonal": seasonal,
        "residual": residual,
        "observed": data,
    }


def _calculate_trend(data: np.ndarray, period: int) -> np.ndarray:
    n = len(data)
    trend = np.zeros(n)

    if period % 2 == 0:
        weights = np.ones(period + 1) / period
        weights[0] = 0.5 / period
        weights[-1] = 0.5 / period
    else:
        weights = np.ones(period) / period

    half_window = period // 2

    for i in range(n):
        if i < half_window:
            trend[i] = np.mean(data[:i + half_window + 1])
        elif i >= n - half_window:
            trend[i] = np.mean(data[i - half_window:])
        else:
            window = data[i - half_window:i + half_window + 1]
            if len(window) == len(weights):
                trend[i] = np.sum(window * weights)
            else:
                trend[i] = np.mean(window)

    return trend


def detect_seasonality(
    data: Union[pd.Series, list, np.ndarray],
    max_period: int = 365,
    confidence_level: float = 0.95,
) -> Optional[int]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    n = len(data)
    if n < 14:
        return None

    try:
        fft_result = np.fft.fft(data.values - np.mean(data.values))
        power_spectrum = np.abs(fft_result) ** 2

        freqs = np.fft.fftfreq(n)
        positive_mask = freqs > 0
        freqs = freqs[positive_mask]
        power_spectrum = power_spectrum[positive_mask]

        if len(power_spectrum) == 0:
            return None

        significant_peaks = _find_significant_peaks(
            power_spectrum,
            freqs,
            n,
            confidence_level
        )

        if len(significant_peaks) == 0:
            return None

        best_period = None
        best_score = -np.inf

        for freq in significant_peaks:
            period = int(round(1 / freq))

            if period < 2 or period > max_period or period >= n / 2:
                continue

            score = _test_seasonal_strength(data.values, period)

            if score > best_score and score > 0.3:
                best_score = score
                best_period = period

        candidate_periods = [7, 14, 30, 90, 365]
        for cp in candidate_periods:
            if cp < n / 2:
                score = _test_seasonal_strength(data.values, cp)
                if score > best_score and score > 0.3:
                    best_score = score
                    best_period = cp

        return best_period

    except Exception:
        return None


def _find_significant_peaks(
    power_spectrum: np.ndarray,
    freqs: np.ndarray,
    n: int,
    confidence_level: float
) -> list[float]:
    threshold = _calculate_fft_threshold(power_spectrum, confidence_level)

    peaks = []
    for i in range(1, len(power_spectrum) - 1):
        if (power_spectrum[i] > power_spectrum[i - 1] and
            power_spectrum[i] > power_spectrum[i + 1] and
            power_spectrum[i] > threshold):
            peaks.append(freqs[i])

    return sorted(peaks, key=lambda f: power_spectrum[np.where(freqs == f)[0][0]], reverse=True)[:5]


def _calculate_fft_threshold(
    power_spectrum: np.ndarray,
    confidence_level: float
) -> float:
    median_power = np.median(power_spectrum)
    mad = np.median(np.abs(power_spectrum - median_power))
    sigma = 1.4826 * mad

    z_score = stats.norm.ppf(confidence_level)
    return median_power + z_score * sigma


def _test_seasonal_strength(data: np.ndarray, period: int) -> float:
    n = len(data)
    if period < 2 or period >= n:
        return 0.0

    try:
        decompose_result = seasonal_decompose(data, period=period, method="simple")

        seasonal_var = np.var(decompose_result["seasonal"])
        residual_var = np.var(decompose_result["residual"])
        total_var = np.var(data)

        if total_var == 0:
            return 0.0

        strength = seasonal_var / total_var

        if residual_var > 0:
            f_stat = (seasonal_var / (period - 1)) / (residual_var / (n - period))
            p_value = 1 - stats.f.cdf(f_stat, period - 1, n - period)
            if p_value > 0.05:
                strength *= 0.5

        return max(0.0, min(1.0, strength))

    except Exception:
        return 0.0


def calculate_seasonal_indices(
    data: Union[pd.Series, list, np.ndarray],
    period: Optional[int] = None,
    method: str = "multiplicative",
) -> np.ndarray:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    if period is None:
        period = detect_seasonality(data)
    if period is None:
        period = 7

    n = len(data)

    if method == "multiplicative":
        trend = _calculate_trend(data.values, period)
        seasonal_ratios = data.values / np.where(trend == 0, 1, trend)
    else:
        trend = _calculate_trend(data.values, period)
        seasonal_ratios = data.values - trend

    indices = np.zeros(period)
    for i in range(period):
        indices[i] = np.mean([
            seasonal_ratios[j]
            for j in range(i, n, period)
        ])

    if method == "multiplicative":
        indices = indices / np.mean(indices)
    else:
        indices = indices - np.mean(indices)

    return indices


def seasonal_forecast(
    data: Union[pd.Series, list, np.ndarray],
    periods: int = 30,
    period: Optional[int] = None,
    method: str = "additive",
    trend_method: str = "linear",
) -> Tuple[np.ndarray, np.ndarray, Dict[str, Any]]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    n = len(data)

    if period is None:
        period = detect_seasonality(data)
    if period is None:
        period = 7

    decompose_result = seasonal_decompose(data, period=period, model=method)

    trend = decompose_result["trend"]
    seasonal = decompose_result["seasonal"]

    if trend_method == "linear":
        from app.utils.forecast.algorithms import linear_regression_forecast
        _, trend_forecast = linear_regression_forecast(pd.Series(trend), periods=periods)
    elif trend_method == "exponential":
        from app.utils.forecast.algorithms import exponential_smoothing
        _, trend_forecast = exponential_smoothing(pd.Series(trend), periods=periods)
    else:
        last_trend = trend[-1]
        if len(trend) > 1:
            trend_slope = (trend[-1] - trend[-2])
        else:
            trend_slope = 0
        trend_forecast = np.array([last_trend + trend_slope * (i + 1) for i in range(periods)])

    seasonal_indices = calculate_seasonal_indices(data, period=period, method=method)

    forecast_seasonal = np.array([
        seasonal_indices[i % period] for i in range(periods)
    ])

    if method == "additive":
        forecast = trend_forecast + forecast_seasonal
        fitted = trend + seasonal
    else:
        forecast = trend_forecast * forecast_seasonal
        fitted = trend * seasonal

    fitted = np.where(np.isnan(fitted), data.values, fitted)
    forecast = np.where(np.isnan(forecast), np.mean(data.values), forecast)
    forecast = np.maximum(0, forecast)

    return fitted, forecast, {
        "period": period,
        "method": method,
        "trend_method": trend_method,
        "seasonal_indices": seasonal_indices.tolist(),
        "trend_forecast": trend_forecast.tolist(),
        "seasonal_forecast": forecast_seasonal.tolist(),
        "seasonal_strength": _test_seasonal_strength(data.values, period),
    }


def detect_outliers(
    data: Union[pd.Series, list, np.ndarray],
    method: str = "iqr",
    threshold: float = 3.0,
) -> Tuple[np.ndarray, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    values = data.values

    if method == "iqr":
        q1 = np.percentile(values, 25)
        q3 = np.percentile(values, 75)
        iqr = q3 - q1
        lower_bound = q1 - threshold * iqr
        upper_bound = q3 + threshold * iqr
        outliers = (values < lower_bound) | (values > upper_bound)

    elif method == "zscore":
        z_scores = np.abs(stats.zscore(values, nan_policy="omit"))
        outliers = z_scores > threshold

    elif method == "stl":
        try:
            result = seasonal_decompose(data, method="stl")
            residual = result["residual"]
            mad = np.median(np.abs(residual - np.median(residual)))
            threshold_val = threshold * 1.4826 * mad
            outliers = np.abs(residual) > threshold_val
        except Exception:
            return detect_outliers(data, method="iqr", threshold=threshold)

    else:
        raise ValueError(f"Unknown method: {method}")

    cleaned_values = values.copy()
    cleaned_values[outliers] = np.nan

    cleaned_series = pd.Series(cleaned_values).interpolate(method="linear").bfill().ffill().values

    return outliers, cleaned_series


def smooth_data(
    data: Union[pd.Series, list, np.ndarray],
    method: str = "savgol",
    window: int = 7,
    **kwargs,
) -> np.ndarray:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    values = data.values
    n = len(values)

    if n < window:
        window = max(2, n // 2)

    if method == "savgol":
        try:
            from scipy.signal import savgol_filter
            polyorder = kwargs.get("polyorder", min(3, window - 1))
            smoothed = savgol_filter(values, window, polyorder)
        except Exception:
            method = "ma"

    if method == "ma" or method == "moving_average":
        smoothed = pd.Series(values).rolling(
            window=window,
            min_periods=1,
            center=True
        ).mean().values

    elif method == "ema" or method == "exponential":
        alpha = kwargs.get("alpha", 2 / (window + 1))
        smoothed = pd.Series(values).ewm(alpha=alpha, adjust=False).mean().values

    elif method == "lowess":
        try:
            from statsmodels.nonparametric.smoothers_lowess import lowess
            frac = kwargs.get("frac", 0.3)
            smoothed = lowess(values, np.arange(n), frac=frac)[:, 1]
        except Exception:
            method = "ma"
            smoothed = pd.Series(values).rolling(
                window=window, min_periods=1, center=True
            ).mean().values

    else:
        raise ValueError(f"Unknown smoothing method: {method}")

    return smoothed
