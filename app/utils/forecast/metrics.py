from __future__ import annotations
import numpy as np
import pandas as pd
from typing import Optional, Dict, Any, List, Tuple, Union
from dataclasses import dataclass, field


@dataclass
class ForecastMetrics:
    mape: float = 0.0
    rmse: float = 0.0
    mae: float = 0.0
    mse: float = 0.0
    smape: float = 0.0
    mean_abs_error: float = 0.0
    max_abs_error: float = 0.0
    min_abs_error: float = 0.0
    bias: float = 0.0
    tracking_signal: float = 0.0
    accuracy: float = 0.0
    r_squared: float = 0.0
    adjusted_r_squared: float = 0.0
    aic: Optional[float] = None
    bic: Optional[float] = None
    errors: List[float] = field(default_factory=list)
    abs_errors: List[float] = field(default_factory=list)
    percentage_errors: List[float] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "mape": round(self.mape, 4),
            "rmse": round(self.rmse, 4),
            "mae": round(self.mae, 4),
            "mse": round(self.mse, 4),
            "smape": round(self.smape, 4),
            "mean_abs_error": round(self.mean_abs_error, 4),
            "max_abs_error": round(self.max_abs_error, 4),
            "min_abs_error": round(self.min_abs_error, 4),
            "bias": round(self.bias, 4),
            "tracking_signal": round(self.tracking_signal, 4),
            "accuracy": round(self.accuracy, 4),
            "r_squared": round(self.r_squared, 4),
            "adjusted_r_squared": round(self.adjusted_r_squared, 4),
            "aic": round(self.aic, 4) if self.aic is not None else None,
            "bic": round(self.bic, 4) if self.bic is not None else None,
        }

    def get_grade(self) -> str:
        if self.mape < 0.05:
            return "EXCELLENT"
        elif self.mape < 0.10:
            return "GOOD"
        elif self.mape < 0.20:
            return "FAIR"
        elif self.mape < 0.30:
            return "POOR"
        else:
            return "VERY_POOR"


def calculate_mape(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
    epsilon: float = 1e-10,
) -> float:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast):
        raise ValueError("Actual and forecast arrays must have the same length")

    mask = actual != 0
    if not np.any(mask):
        return 0.0

    abs_percentage_errors = np.abs((actual[mask] - forecast[mask]) / (np.abs(actual[mask]) + epsilon))
    mape = np.mean(abs_percentage_errors)

    return float(mape)


def calculate_smape(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
    epsilon: float = 1e-10,
) -> float:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast):
        raise ValueError("Actual and forecast arrays must have the same length")

    denominator = np.abs(actual) + np.abs(forecast) + epsilon
    smape = np.mean(200 * np.abs(actual - forecast) / denominator) / 100.0

    return float(smape)


def calculate_mae(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
) -> float:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast):
        raise ValueError("Actual and forecast arrays must have the same length")

    mae = np.mean(np.abs(actual - forecast))
    return float(mae)


def calculate_mse(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
) -> float:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast):
        raise ValueError("Actual and forecast arrays must have the same length")

    mse = np.mean((actual - forecast) ** 2)
    return float(mse)


def calculate_rmse(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
) -> float:
    mse = calculate_mse(actual, forecast)
    return float(np.sqrt(mse))


def calculate_bias(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
) -> float:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast):
        raise ValueError("Actual and forecast arrays must have the same length")

    bias = np.mean(forecast - actual)
    return float(bias)


def calculate_r_squared(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
) -> float:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast):
        raise ValueError("Actual and forecast arrays must have the same length")

    ss_res = np.sum((actual - forecast) ** 2)
    ss_tot = np.sum((actual - np.mean(actual)) ** 2)

    if ss_tot == 0:
        return 1.0 if ss_res == 0 else 0.0

    r_squared = 1 - (ss_res / ss_tot)
    return float(r_squared)


def calculate_adjusted_r_squared(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
    num_predictors: int = 1,
) -> float:
    r_squared = calculate_r_squared(actual, forecast)
    n = len(actual)

    if n - num_predictors - 1 <= 0:
        return r_squared

    adjusted_r2 = 1 - (1 - r_squared) * (n - 1) / (n - num_predictors - 1)
    return float(adjusted_r2)


def calculate_tracking_signal(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
) -> float:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast):
        raise ValueError("Actual and forecast arrays must have the same length")

    errors = forecast - actual
    cumulative_error = np.sum(errors)
    mean_abs_error = np.mean(np.abs(errors))

    if mean_abs_error == 0:
        return 0.0

    tracking_signal = cumulative_error / mean_abs_error
    return float(tracking_signal)


def calculate_accuracy(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
    tolerance: float = 0.1,
) -> float:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast):
        raise ValueError("Actual and forecast arrays must have the same length")

    mask = actual != 0
    if not np.any(mask):
        return 1.0

    percentage_errors = np.abs((actual[mask] - forecast[mask]) / actual[mask])
    accurate_count = np.sum(percentage_errors <= tolerance)
    accuracy = accurate_count / len(actual)

    return float(accuracy)


def calculate_directional_accuracy(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
) -> float:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast) or len(actual) < 2:
        raise ValueError("Arrays must have at least 2 elements")

    actual_direction = np.diff(actual) > 0
    forecast_direction = np.diff(forecast) > 0

    correct_directions = actual_direction == forecast_direction
    directional_accuracy = np.mean(correct_directions)

    return float(directional_accuracy)


def evaluate_forecast(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
    num_predictors: int = 1,
    tolerance: float = 0.1,
) -> ForecastMetrics:
    actual = np.array(actual)
    forecast = np.array(forecast)

    if len(actual) != len(forecast):
        raise ValueError("Actual and forecast arrays must have the same length")

    if len(actual) == 0:
        return ForecastMetrics()

    errors = (forecast - actual).tolist()
    abs_errors = np.abs(actual - forecast).tolist()
    percentage_errors = []
    for a, f in zip(actual, forecast):
        if a != 0:
            percentage_errors.append((f - a) / a)
        else:
            percentage_errors.append(0.0)

    metrics = ForecastMetrics(
        mape=calculate_mape(actual, forecast),
        rmse=calculate_rmse(actual, forecast),
        mae=calculate_mae(actual, forecast),
        mse=calculate_mse(actual, forecast),
        smape=calculate_smape(actual, forecast),
        mean_abs_error=np.mean(abs_errors),
        max_abs_error=np.max(abs_errors),
        min_abs_error=np.min(abs_errors),
        bias=calculate_bias(actual, forecast),
        tracking_signal=calculate_tracking_signal(actual, forecast),
        accuracy=calculate_accuracy(actual, forecast, tolerance),
        r_squared=calculate_r_squared(actual, forecast),
        adjusted_r_squared=calculate_adjusted_r_squared(actual, forecast, num_predictors),
        errors=errors,
        abs_errors=abs_errors,
        percentage_errors=percentage_errors,
    )

    return metrics


def rolling_forecast_evaluation(
    data: pd.Series | list | np.ndarray,
    forecast_func,
    train_window: int = 30,
    test_window: int = 7,
    step: int = 1,
    **kwargs,
) -> Tuple[ForecastMetrics, List[Dict[str, Any]]]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    n = len(data)
    all_actual = []
    all_forecast = []
    rolling_results = []

    for start in range(0, n - train_window - test_window + 1, step):
        train_end = start + train_window
        test_end = train_end + test_window

        if test_end > n:
            break

        train_data = data.iloc[start:train_end]
        test_data = data.iloc[train_end:test_end]

        try:
            _, forecast = forecast_func(train_data, periods=test_window, **kwargs)

            all_actual.extend(test_data.values)
            all_forecast.extend(forecast[:len(test_data)])

            metrics = evaluate_forecast(test_data.values, forecast[:len(test_data)])
            rolling_results.append({
                "train_start": start,
                "train_end": train_end,
                "test_start": train_end,
                "test_end": test_end,
                "metrics": metrics.to_dict(),
            })
        except Exception as e:
            rolling_results.append({
                "train_start": start,
                "train_end": train_end,
                "test_start": train_end,
                "test_end": test_end,
                "error": str(e),
            })

    if len(all_actual) == 0:
        return ForecastMetrics(), rolling_results

    overall_metrics = evaluate_forecast(all_actual, all_forecast)
    return overall_metrics, rolling_results


def select_best_model(
    data: Union[pd.Series, list, np.ndarray],
    models: Optional[List[str]] = None,
    metric: str = "mape",
    train_ratio: float = 0.8,
    periods: int = 30,
) -> Tuple[str, ForecastMetrics, Dict[str, ForecastMetrics]]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    n = len(data)
    train_size = int(n * train_ratio)
    train_data = data.iloc[:train_size]
    test_data = data.iloc[train_size:]

    if models is None:
        models = ["ma", "es", "des", "lr", "arima"]

    from app.utils.forecast.algorithms import (
        moving_average,
        exponential_smoothing,
        double_exponential_smoothing,
        linear_regression_forecast,
        auto_arima_forecast,
    )

    model_funcs = {
        "ma": lambda d, p: moving_average(d, periods=p),
        "es": lambda d, p: exponential_smoothing(d, periods=p),
        "des": lambda d, p: double_exponential_smoothing(d, periods=p),
        "lr": lambda d, p: linear_regression_forecast(d, periods=p),
        "arima": lambda d, p: auto_arima_forecast(d, periods=p)[:2],
    }

    results: Dict[str, ForecastMetrics] = {}
    best_model = None
    best_metric_value = float("inf")

    for model_name in models:
        if model_name not in model_funcs:
            continue

        try:
            _, forecast = model_funcs[model_name](train_data, len(test_data))
            metrics = evaluate_forecast(test_data.values, forecast[:len(test_data)])
            results[model_name] = metrics

            current_metric = getattr(metrics, metric)
            if current_metric < best_metric_value:
                best_metric_value = current_metric
                best_model = model_name
        except Exception:
            results[model_name] = ForecastMetrics(mape=float("inf"))

    if best_model is None:
        best_model = "es"

    return best_model, results[best_model], results


def forecast_confidence_interval(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
    confidence_level: float = 0.95,
) -> Tuple[np.ndarray, np.ndarray]:
    actual = np.array(actual)
    forecast = np.array(forecast)

    errors = actual - forecast
    std_error = np.std(errors, ddof=1)

    from scipy import stats
    z_score = stats.norm.ppf((1 + confidence_level) / 2)

    margin = z_score * std_error
    lower_bound = forecast - margin
    upper_bound = forecast + margin

    return lower_bound, upper_bound


def calculate_error_distribution(
    actual: Union[np.ndarray, list],
    forecast: Union[np.ndarray, list],
    bins: int = 10,
) -> Dict[str, Any]:
    actual = np.array(actual)
    forecast = np.array(forecast)
    errors = forecast - actual

    hist, bin_edges = np.histogram(errors, bins=bins)

    return {
        "mean": float(np.mean(errors)),
        "std": float(np.std(errors)),
        "skewness": float(stats.skew(errors)),
        "kurtosis": float(stats.kurtosis(errors)),
        "histogram": {
            "counts": hist.tolist(),
            "bin_edges": bin_edges.tolist(),
        },
        "normal_test": {
            "statistic": float(stats.normaltest(errors)[0]),
            "p_value": float(stats.normaltest(errors)[1]),
        },
    }
