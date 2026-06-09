import numpy as np
import pandas as pd
from typing import Optional, Tuple, Dict, Any, Union, List

try:
    from statsmodels.tsa.holtwinters import ExponentialSmoothing
    _has_holtwinters = True
except ImportError:
    _has_holtwinters = False

try:
    from statsmodels.tsa.arima.model import ARIMA
    _has_arima = True
except ImportError:
    _has_arima = False

from sklearn.linear_model import LinearRegression
from sklearn.preprocessing import PolynomialFeatures


def moving_average(
    data: Union[pd.Series, list, np.ndarray],
    window: int = 7,
    periods: int = 30,
    min_periods: int = 1,
    center: bool = False,
) -> Tuple[np.ndarray, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    if len(data) < window:
        window = max(2, len(data) // 2)

    ma_series = data.rolling(
        window=window,
        min_periods=min_periods,
        center=center
    ).mean()

    last_ma = ma_series.iloc[-1] if not pd.isna(ma_series.iloc[-1]) else data.mean()
    forecast = np.full(periods, last_ma)

    fitted = ma_series.fillna(method="bfill").fillna(method="ffill").values

    return fitted, forecast


def exponential_smoothing(
    data: Union[pd.Series, list, np.ndarray],
    alpha: Optional[float] = None,
    periods: int = 30,
    optimized: bool = True,
) -> Tuple[np.ndarray, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    if len(data) < 2:
        mean_val = data.mean() if len(data) > 0 else 0
        return np.full(len(data), mean_val), np.full(periods, mean_val)

    if alpha is None and optimized:
        try:
            model = ExponentialSmoothing(
                data.values,
                initialization_method="estimated"
            )
            fitted_model = model.fit(optimized=True)
            fitted = fitted_model.fittedvalues
            forecast = fitted_model.forecast(periods)
        except Exception:
            alpha = 0.3
            return _simple_exp_smoothing(data, alpha, periods)
    else:
        alpha = alpha if alpha is not None else 0.3
        fitted, forecast = _simple_exp_smoothing(data, alpha, periods)

    return np.array(fitted), np.array(forecast)


def _simple_exp_smoothing(
    data: pd.Series,
    alpha: float,
    periods: int
) -> Tuple[np.ndarray, np.ndarray]:
    n = len(data)
    fitted = np.zeros(n)
    fitted[0] = data.iloc[0]

    for i in range(1, n):
        fitted[i] = alpha * data.iloc[i] + (1 - alpha) * fitted[i - 1]

    last_val = fitted[-1]
    forecast = np.full(periods, last_val)

    return fitted, forecast


def double_exponential_smoothing(
    data: Union[pd.Series, list, np.ndarray],
    alpha: Optional[float] = None,
    beta: Optional[float] = None,
    periods: int = 30,
) -> Tuple[np.ndarray, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    if len(data) < 4:
        return exponential_smoothing(data, alpha, periods)

    try:
        model = ExponentialSmoothing(
            data.values,
            trend="add",
            initialization_method="estimated"
        )
        fitted_model = model.fit(
            smoothing_level=alpha,
            smoothing_trend=beta,
            optimized=(alpha is None or beta is None)
        )
        fitted = fitted_model.fittedvalues
        forecast = fitted_model.forecast(periods)
    except Exception:
        if alpha is None:
            alpha = 0.5
        if beta is None:
            beta = 0.3
        fitted, forecast = _double_exp_smoothing(data, alpha, beta, periods)

    return np.array(fitted), np.array(forecast)


def _double_exp_smoothing(
    data: pd.Series,
    alpha: float,
    beta: float,
    periods: int
) -> Tuple[np.ndarray, np.ndarray]:
    n = len(data)
    level = np.zeros(n)
    trend = np.zeros(n)
    fitted = np.zeros(n)

    level[0] = data.iloc[0]
    trend[0] = data.iloc[1] - data.iloc[0] if n > 1 else 0

    for i in range(1, n):
        prev_level = level[i - 1]
        prev_trend = trend[i - 1]

        level[i] = alpha * data.iloc[i] + (1 - alpha) * (prev_level + prev_trend)
        trend[i] = beta * (level[i] - prev_level) + (1 - beta) * prev_trend
        fitted[i] = level[i - 1] + trend[i - 1]

    fitted[0] = data.iloc[0]

    last_level = level[-1]
    last_trend = trend[-1]
    forecast = np.array([last_level + last_trend * (h + 1) for h in range(periods)])

    return fitted, forecast


def triple_exponential_smoothing(
    data: Union[pd.Series, list, np.ndarray],
    seasonal_periods: int = 7,
    alpha: Optional[float] = None,
    beta: Optional[float] = None,
    gamma: Optional[float] = None,
    periods: int = 30,
) -> Tuple[np.ndarray, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    if len(data) < 2 * seasonal_periods or not _has_holtwinters:
        return double_exponential_smoothing(data, alpha, beta, periods)

    try:
        model = ExponentialSmoothing(
            data.values,
            trend="add",
            seasonal="add",
            seasonal_periods=seasonal_periods,
            initialization_method="estimated"
        )
        fitted_model = model.fit(
            smoothing_level=alpha,
            smoothing_trend=beta,
            smoothing_seasonal=gamma,
            optimized=(alpha is None or beta is None or gamma is None)
        )
        fitted = fitted_model.fittedvalues
        forecast = fitted_model.forecast(periods)
    except Exception:
        return double_exponential_smoothing(data, alpha, beta, periods)

    return np.array(fitted), np.array(forecast)


def arima_forecast(
    data: Union[pd.Series, list, np.ndarray],
    order: Tuple[int, int, int] = (1, 1, 1),
    periods: int = 30,
) -> Tuple[np.ndarray, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    if len(data) < 10 or not _has_arima:
        return exponential_smoothing(data, periods=periods)

    try:
        model = ARIMA(data.values, order=order)
        fitted_model = model.fit()
        fitted = fitted_model.fittedvalues
        forecast = fitted_model.forecast(periods)

        fitted = np.where(np.isnan(fitted), data.values, fitted)
    except Exception:
        return double_exponential_smoothing(data, periods=periods)

    return np.array(fitted), np.array(forecast)


def auto_arima_forecast(
    data: Union[pd.Series, list, np.ndarray],
    periods: int = 30,
    max_p: int = 3,
    max_d: int = 2,
    max_q: int = 3,
) -> Tuple[np.ndarray, np.ndarray, Tuple[int, int, int]]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    if len(data) < 20 or not _has_arima:
        fitted, forecast = exponential_smoothing(data, periods=periods)
        return fitted, forecast, (0, 0, 0)

    best_aic = np.inf
    best_order = (1, 1, 1)
    best_fitted = None
    best_forecast = None

    for p in range(max_p + 1):
        for d in range(max_d + 1):
            for q in range(max_q + 1):
                if p == 0 and d == 0 and q == 0:
                    continue
                try:
                    model = ARIMA(data.values, order=(p, d, q))
                    fitted_model = model.fit()
                    aic = fitted_model.aic

                    if aic < best_aic:
                        best_aic = aic
                        best_order = (p, d, q)
                        best_fitted = fitted_model.fittedvalues
                        best_forecast = fitted_model.forecast(periods)
                except Exception:
                    continue

    if best_fitted is None:
        best_fitted, best_forecast = exponential_smoothing(data, periods=periods)
        best_order = (0, 0, 0)

    best_fitted = np.where(np.isnan(best_fitted), data.values, best_fitted)

    return np.array(best_fitted), np.array(best_forecast), best_order


def linear_regression_forecast(
    data: Union[pd.Series, list, np.ndarray],
    periods: int = 30,
    degree: int = 1,
) -> Tuple[np.ndarray, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    n = len(data)
    if n < 2:
        mean_val = data.mean() if n > 0 else 0
        return np.full(n, mean_val), np.full(periods, mean_val)

    X = np.arange(n).reshape(-1, 1)
    y = data.values

    if degree > 1:
        poly_features = PolynomialFeatures(degree=degree)
        X_poly = poly_features.fit_transform(X)
        model = LinearRegression()
        model.fit(X_poly, y)
        fitted = model.predict(X_poly)

        X_forecast = np.arange(n, n + periods).reshape(-1, 1)
        X_forecast_poly = poly_features.transform(X_forecast)
        forecast = model.predict(X_forecast_poly)
    else:
        model = LinearRegression()
        model.fit(X, y)
        fitted = model.predict(X)

        X_forecast = np.arange(n, n + periods).reshape(-1, 1)
        forecast = model.predict(X_forecast)

    return np.array(fitted), np.array(forecast)


def weighted_moving_average(
    data: Union[pd.Series, list, np.ndarray],
    window: int = 7,
    weights: Optional[np.ndarray] = None,
    periods: int = 30,
) -> Tuple[np.ndarray, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    n = len(data)

    if weights is None:
        weights = np.arange(1, window + 1, dtype=float)
        weights /= weights.sum()
    else:
        weights = np.array(weights, dtype=float)
        weights /= weights.sum()

    if window > n:
        window = n

    fitted = np.zeros(n)

    for i in range(n):
        if i < window - 1:
            available_weights = weights[-(i + 1):]
            available_weights /= available_weights.sum()
            fitted[i] = np.sum(data.iloc[:i + 1] * available_weights)
        else:
            window_data = data.iloc[i - window + 1:i + 1].values
            fitted[i] = np.sum(window_data * weights)

    last_val = fitted[-1]
    forecast = np.full(periods, last_val)

    return fitted, forecast


def croston_method(
    data: Union[pd.Series, list, np.ndarray],
    alpha: float = 0.1,
    periods: int = 30,
) -> Tuple[np.ndarray, np.ndarray]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    n = len(data)
    demand = np.zeros(n)
    interval = np.zeros(n)
    fitted = np.zeros(n)

    non_zero = data[data > 0]
    if len(non_zero) == 0:
        return np.zeros(n), np.zeros(periods)

    demand[0] = non_zero.iloc[0]
    interval[0] = 1
    last_non_zero_idx = 0

    for i in range(1, n):
        if data.iloc[i] > 0:
            demand[i] = alpha * data.iloc[i] + (1 - alpha) * demand[i - 1]
            gap = i - last_non_zero_idx
            interval[i] = alpha * gap + (1 - alpha) * interval[i - 1]
            last_non_zero_idx = i
        else:
            demand[i] = demand[i - 1]
            interval[i] = interval[i - 1]

        fitted[i] = demand[i] / interval[i] if interval[i] > 0 else 0

    last_forecast = demand[-1] / interval[-1] if interval[-1] > 0 else 0
    forecast = np.full(periods, last_forecast)

    return fitted, forecast


def forecast_combination(
    data: Union[pd.Series, list, np.ndarray],
    periods: int = 30,
    methods: Optional[list[str]] = None,
    weights: Optional[np.ndarray] = None,
) -> Tuple[np.ndarray, np.ndarray, Dict[str, Any]]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    if methods is None:
        methods = ["ma", "es", "lr"]

    available_methods = {
        "ma": lambda: moving_average(data, periods=periods),
        "es": lambda: exponential_smoothing(data, periods=periods),
        "des": lambda: double_exponential_smoothing(data, periods=periods),
        "lr": lambda: linear_regression_forecast(data, periods=periods),
    }

    forecasts = {}
    fitted_values = {}

    for method in methods:
        if method in available_methods:
            try:
                fitted, forecast = available_methods[method]()
                fitted_values[method] = fitted
                forecasts[method] = forecast
            except Exception:
                continue

    if len(forecasts) == 0:
        fitted, forecast = exponential_smoothing(data, periods=periods)
        return fitted, forecast, {"methods_used": ["es"]}

    if weights is None:
        weights = np.ones(len(forecasts)) / len(forecasts)

    method_list = list(forecasts.keys())
    combined_fitted = np.zeros_like(fitted_values[method_list[0]])
    combined_forecast = np.zeros_like(forecasts[method_list[0]])

    for i, method in enumerate(method_list):
        combined_fitted += weights[i] * fitted_values[method]
        combined_forecast += weights[i] * forecasts[method]

    return combined_fitted, combined_forecast, {
        "methods_used": method_list,
        "weights": weights.tolist(),
        "individual_forecasts": forecasts,
    }
