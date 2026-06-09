from datetime import datetime, date, timedelta
from typing import Optional, List, Dict, Any, Tuple, Union
import numpy as np
import pandas as pd

from app.core.logging import get_logger
from app.utils.forecast.seasonal import (
    seasonal_decompose as _seasonal_decompose,
    detect_seasonality as _detect_seasonality,
    calculate_seasonal_indices as _calculate_seasonal_indices,
    seasonal_forecast as _seasonal_forecast,
)

logger = get_logger(__name__)


CHINA_HOLIDAYS_2024_2026 = {
    "2024-01-01": {"name": "元旦", "factor": 1.5},
    "2024-02-10": {"name": "春节", "factor": 2.5},
    "2024-02-11": {"name": "春节", "factor": 2.5},
    "2024-02-12": {"name": "春节", "factor": 2.5},
    "2024-02-13": {"name": "春节", "factor": 2.5},
    "2024-02-14": {"name": "春节", "factor": 2.5},
    "2024-02-15": {"name": "春节", "factor": 2.5},
    "2024-02-16": {"name": "春节", "factor": 2.5},
    "2024-02-17": {"name": "春节", "factor": 2.0},
    "2024-04-04": {"name": "清明节", "factor": 1.3},
    "2024-04-05": {"name": "清明节", "factor": 1.5},
    "2024-04-06": {"name": "清明节", "factor": 1.3},
    "2024-05-01": {"name": "劳动节", "factor": 1.8},
    "2024-05-02": {"name": "劳动节", "factor": 1.8},
    "2024-05-03": {"name": "劳动节", "factor": 1.8},
    "2024-05-04": {"name": "劳动节", "factor": 1.8},
    "2024-05-05": {"name": "劳动节", "factor": 1.5},
    "2024-06-10": {"name": "端午节", "factor": 1.4},
    "2024-09-15": {"name": "中秋节", "factor": 1.4},
    "2024-09-16": {"name": "中秋节", "factor": 1.5},
    "2024-09-17": {"name": "中秋节", "factor": 1.4},
    "2024-10-01": {"name": "国庆节", "factor": 2.2},
    "2024-10-02": {"name": "国庆节", "factor": 2.2},
    "2024-10-03": {"name": "国庆节", "factor": 2.2},
    "2024-10-04": {"name": "国庆节", "factor": 2.0},
    "2024-10-05": {"name": "国庆节", "factor": 2.0},
    "2024-10-06": {"name": "国庆节", "factor": 1.8},
    "2024-10-07": {"name": "国庆节", "factor": 1.8},
    "2024-11-11": {"name": "双十一", "factor": 3.0},
    "2024-12-12": {"name": "双十二", "factor": 2.0},
    "2025-01-01": {"name": "元旦", "factor": 1.5},
    "2025-01-28": {"name": "春节", "factor": 2.5},
    "2025-01-29": {"name": "春节", "factor": 2.5},
    "2025-01-30": {"name": "春节", "factor": 2.5},
    "2025-01-31": {"name": "春节", "factor": 2.5},
    "2025-02-01": {"name": "春节", "factor": 2.5},
    "2025-02-02": {"name": "春节", "factor": 2.5},
    "2025-02-03": {"name": "春节", "factor": 2.5},
    "2025-02-04": {"name": "春节", "factor": 2.0},
    "2025-04-04": {"name": "清明节", "factor": 1.3},
    "2025-04-05": {"name": "清明节", "factor": 1.5},
    "2025-04-06": {"name": "清明节", "factor": 1.3},
    "2025-05-01": {"name": "劳动节", "factor": 1.8},
    "2025-05-02": {"name": "劳动节", "factor": 1.8},
    "2025-05-03": {"name": "劳动节", "factor": 1.8},
    "2025-05-04": {"name": "劳动节", "factor": 1.8},
    "2025-05-05": {"name": "劳动节", "factor": 1.5},
    "2025-05-31": {"name": "端午节", "factor": 1.4},
    "2025-06-01": {"name": "端午节", "factor": 1.5},
    "2025-06-02": {"name": "端午节", "factor": 1.4},
    "2025-10-01": {"name": "国庆节", "factor": 2.2},
    "2025-10-02": {"name": "国庆节", "factor": 2.2},
    "2025-10-03": {"name": "国庆节", "factor": 2.2},
    "2025-10-04": {"name": "国庆节", "factor": 2.0},
    "2025-10-05": {"name": "国庆节", "factor": 2.0},
    "2025-10-06": {"name": "国庆节", "factor": 1.8},
    "2025-10-07": {"name": "国庆节", "factor": 1.8},
    "2025-10-08": {"name": "中秋节", "factor": 1.5},
    "2025-11-11": {"name": "双十一", "factor": 3.0},
    "2025-12-12": {"name": "双十二", "factor": 2.0},
    "2026-01-01": {"name": "元旦", "factor": 1.5},
    "2026-02-16": {"name": "春节", "factor": 2.5},
    "2026-02-17": {"name": "春节", "factor": 2.5},
    "2026-02-18": {"name": "春节", "factor": 2.5},
    "2026-02-19": {"name": "春节", "factor": 2.5},
    "2026-02-20": {"name": "春节", "factor": 2.5},
    "2026-02-21": {"name": "春节", "factor": 2.5},
    "2026-02-22": {"name": "春节", "factor": 2.5},
    "2026-02-23": {"name": "春节", "factor": 2.0},
    "2026-04-04": {"name": "清明节", "factor": 1.3},
    "2026-04-05": {"name": "清明节", "factor": 1.5},
    "2026-04-06": {"name": "清明节", "factor": 1.3},
    "2026-05-01": {"name": "劳动节", "factor": 1.8},
    "2026-05-02": {"name": "劳动节", "factor": 1.8},
    "2026-05-03": {"name": "劳动节", "factor": 1.8},
    "2026-05-04": {"name": "劳动节", "factor": 1.8},
    "2026-05-05": {"name": "劳动节", "factor": 1.5},
    "2026-06-18": {"name": "端午节", "factor": 1.4},
    "2026-06-19": {"name": "端午节", "factor": 1.5},
    "2026-06-20": {"name": "端午节", "factor": 1.4},
    "2026-09-24": {"name": "中秋节", "factor": 1.5},
    "2026-09-25": {"name": "中秋节", "factor": 1.5},
    "2026-09-26": {"name": "中秋节", "factor": 1.4},
    "2026-10-01": {"name": "国庆节", "factor": 2.2},
    "2026-10-02": {"name": "国庆节", "factor": 2.2},
    "2026-10-03": {"name": "国庆节", "factor": 2.2},
    "2026-10-04": {"name": "国庆节", "factor": 2.0},
    "2026-10-05": {"name": "国庆节", "factor": 2.0},
    "2026-10-06": {"name": "国庆节", "factor": 1.8},
    "2026-10-07": {"name": "国庆节", "factor": 1.8},
    "2026-11-11": {"name": "双十一", "factor": 3.0},
    "2026-12-12": {"name": "双十二", "factor": 2.0},
}


WEATHER_FACTORS = {
    "sunny": 1.0,
    "cloudy": 0.95,
    "rainy": 0.8,
    "heavy_rain": 0.6,
    "snowy": 0.7,
    "heavy_snow": 0.5,
    "hot": 1.1,
    "cold": 0.9,
}


def calculate_seasonal_indices(
    data: Union[pd.Series, list, np.ndarray],
    period: Optional[int] = None,
    method: str = "multiplicative",
) -> np.ndarray:
    return _calculate_seasonal_indices(data, period, method)


def detect_seasonality(
    data: Union[pd.Series, list, np.ndarray],
    max_period: int = 365,
    confidence_level: float = 0.95,
) -> Optional[int]:
    return _detect_seasonality(data, max_period, confidence_level)


def seasonal_decompose(
    data: Union[pd.Series, list, np.ndarray],
    period: Optional[int] = None,
    model: str = "additive",
    method: str = "stl",
) -> Dict[str, np.ndarray]:
    return _seasonal_decompose(data, period, model, method)


def seasonal_forecast(
    data: Union[pd.Series, list, np.ndarray],
    periods: int = 30,
    period: Optional[int] = None,
    method: str = "additive",
    trend_method: str = "linear",
) -> Tuple[np.ndarray, np.ndarray, Dict[str, Any]]:
    return _seasonal_forecast(data, periods, period, method, trend_method)


def apply_seasonal_factors(
    base_values: Union[list, np.ndarray],
    seasonal_indices: np.ndarray,
    forecast_start_date: Optional[date] = None,
) -> np.ndarray:
    base_values = np.array(base_values)
    n = len(base_values)
    period = len(seasonal_indices)

    if forecast_start_date is None:
        forecast_start_date = date.today()

    adjusted_values = np.zeros(n)
    for i in range(n):
        forecast_date = forecast_start_date + timedelta(days=i)
        day_of_week = forecast_date.weekday()
        seasonal_index = seasonal_indices[i % period]

        holiday_factor = get_holiday_factor(forecast_date)
        adjusted_values[i] = base_values[i] * seasonal_index * holiday_factor

    return adjusted_values


def get_holiday_factor(
    target_date: Union[date, datetime, str],
    custom_holidays: Optional[Dict[str, Dict[str, Any]]] = None,
) -> float:
    if isinstance(target_date, str):
        target_date = date.fromisoformat(target_date)
    elif isinstance(target_date, datetime):
        target_date = target_date.date()

    date_str = target_date.isoformat()

    all_holidays = CHINA_HOLIDAYS_2024_2026.copy()
    if custom_holidays:
        all_holidays.update(custom_holidays)

    if date_str in all_holidays:
        return all_holidays[date_str].get("factor", 1.0)

    days_before = 7
    for i in range(1, days_before + 1):
        check_date = target_date - timedelta(days=i)
        check_str = check_date.isoformat()
        if check_str in all_holidays:
            holiday_info = all_holidays[check_str]
            base_factor = holiday_info.get("factor", 1.0)
            decay = 1.0 - (i / (days_before + 1)) * 0.5
            return max(1.0, base_factor * decay)

    days_after = 3
    for i in range(1, days_after + 1):
        check_date = target_date + timedelta(days=i)
        check_str = check_date.isoformat()
        if check_str in all_holidays:
            holiday_info = all_holidays[check_str]
            base_factor = holiday_info.get("factor", 1.0)
            decay = 1.0 - (i / (days_after + 1)) * 0.5
            return max(1.0, base_factor * decay)

    return 1.0


def get_holiday_list(
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
) -> List[Dict[str, Any]]:
    if start_date is None:
        start_date = date.today()
    if end_date is None:
        end_date = start_date + timedelta(days=365)

    holidays = []
    for date_str, info in CHINA_HOLIDAYS_2024_2026.items():
        holiday_date = date.fromisoformat(date_str)
        if start_date <= holiday_date <= end_date:
            holidays.append({
                "date": date_str,
                "name": info.get("name", ""),
                "factor": info.get("factor", 1.0),
            })

    return sorted(holidays, key=lambda x: x["date"])


def get_weather_factor(
    weather_condition: str,
    custom_factors: Optional[Dict[str, float]] = None,
) -> float:
    factors = WEATHER_FACTORS.copy()
    if custom_factors:
        factors.update(custom_factors)

    return factors.get(weather_condition.lower(), 1.0)


def calculate_composite_seasonal_factor(
    target_date: Union[date, datetime, str],
    seasonal_indices: np.ndarray,
    forecast_start_date: Optional[date] = None,
    weather_condition: Optional[str] = None,
    custom_holidays: Optional[Dict[str, Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    if isinstance(target_date, str):
        target_date = date.fromisoformat(target_date)
    elif isinstance(target_date, datetime):
        target_date = target_date.date()

    if forecast_start_date is None:
        forecast_start_date = date.today()

    days_from_start = (target_date - forecast_start_date).days
    period = len(seasonal_indices)
    seasonal_index = seasonal_indices[days_from_start % period]
    holiday_factor = get_holiday_factor(target_date, custom_holidays)
    weather_factor = get_weather_factor(weather_condition) if weather_condition else 1.0

    composite_factor = seasonal_index * holiday_factor * weather_factor

    return {
        "date": target_date.isoformat(),
        "seasonal_index": float(seasonal_index),
        "holiday_factor": float(holiday_factor),
        "weather_factor": float(weather_factor),
        "composite_factor": float(composite_factor),
        "is_holiday": holiday_factor != 1.0,
        "holiday_name": get_holiday_name(target_date, custom_holidays),
    }


def get_holiday_name(
    target_date: Union[date, datetime, str],
    custom_holidays: Optional[Dict[str, Dict[str, Any]]] = None,
) -> Optional[str]:
    if isinstance(target_date, str):
        target_date = date.fromisoformat(target_date)
    elif isinstance(target_date, datetime):
        target_date = target_date.date()

    date_str = target_date.isoformat()

    all_holidays = CHINA_HOLIDAYS_2024_2026.copy()
    if custom_holidays:
        all_holidays.update(custom_holidays)

    if date_str in all_holidays:
        return all_holidays[date_str].get("name")

    return None


def analyze_seasonal_trends(
    data: Union[pd.Series, list, np.ndarray],
    dates: Optional[List[date]] = None,
    period: Optional[int] = None,
) -> Dict[str, Any]:
    if not isinstance(data, pd.Series):
        data = pd.Series(data)

    if dates is None:
        dates = [date.today() - timedelta(days=len(data) - 1 - i) for i in range(len(data))]

    if period is None:
        period = detect_seasonality(data)
    if period is None:
        period = 7

    decompose_result = seasonal_decompose(data, period=period, method="stl")
    seasonal_indices = calculate_seasonal_indices(data, period=period)

    monthly_patterns = {}
    for i, d in enumerate(dates):
        month = d.month
        if month not in monthly_patterns:
            monthly_patterns[month] = []
        if i < len(data):
            monthly_patterns[month].append(data.iloc[i])

    monthly_avg = {
        month: np.mean(values) for month, values in monthly_patterns.items()
    }

    weekly_patterns = {}
    for i, d in enumerate(dates):
        weekday = d.weekday()
        if weekday not in weekly_patterns:
            weekly_patterns[weekday] = []
        if i < len(data):
            weekly_patterns[weekday].append(data.iloc[i])

    weekly_avg = {
        weekday: np.mean(values) for weekday, values in weekly_patterns.items()
    }

    overall_mean = np.mean(data.values)
    peak_month = max(monthly_avg.items(), key=lambda x: x[1])[0] if monthly_avg else None
    trough_month = min(monthly_avg.items(), key=lambda x: x[1])[0] if monthly_avg else None

    seasonality_strength = np.var(decompose_result["seasonal"]) / np.var(data.values) if np.var(data.values) > 0 else 0

    return {
        "period": period,
        "seasonal_indices": seasonal_indices.tolist(),
        "seasonality_strength": float(seasonality_strength),
        "has_seasonality": seasonality_strength > 0.3,
        "monthly_patterns": monthly_avg,
        "weekly_patterns": weekly_avg,
        "peak_month": peak_month,
        "trough_month": trough_month,
        "overall_mean": float(overall_mean),
        "peak_to_trough_ratio": (
            monthly_avg[peak_month] / monthly_avg[trough_month]
            if peak_month and trough_month and monthly_avg[trough_month] > 0
            else None
        ),
        "trend": decompose_result["trend"].tolist(),
        "seasonal": decompose_result["seasonal"].tolist(),
        "residual": decompose_result["residual"].tolist(),
    }


def adjust_forecast_for_seasonality(
    forecast_values: Union[list, np.ndarray],
    historical_data: Union[pd.Series, list, np.ndarray],
    forecast_start_date: Optional[date] = None,
    consider_holidays: bool = True,
    consider_weather: bool = False,
    weather_forecast: Optional[List[str]] = None,
) -> Dict[str, Any]:
    forecast_values = np.array(forecast_values)

    if forecast_start_date is None:
        forecast_start_date = date.today()

    period = detect_seasonality(historical_data)
    if period is None:
        period = 7

    seasonal_indices = calculate_seasonal_indices(historical_data, period=period)

    adjusted_forecast = forecast_values.copy()
    factors = []

    for i in range(len(forecast_values)):
        forecast_date = forecast_start_date + timedelta(days=i)

        seasonal_index = seasonal_indices[i % period]
        holiday_factor = get_holiday_factor(forecast_date) if consider_holidays else 1.0

        weather_factor = 1.0
        if consider_weather and weather_forecast and i < len(weather_forecast):
            weather_factor = get_weather_factor(weather_forecast[i])

        composite_factor = seasonal_index * holiday_factor * weather_factor
        adjusted_forecast[i] *= composite_factor

        factors.append({
            "date": forecast_date.isoformat(),
            "seasonal_index": float(seasonal_index),
            "holiday_factor": float(holiday_factor),
            "weather_factor": float(weather_factor),
            "composite_factor": float(composite_factor),
        })

    return {
        "original_forecast": forecast_values.tolist(),
        "adjusted_forecast": adjusted_forecast.tolist(),
        "seasonal_indices": seasonal_indices.tolist(),
        "period": period,
        "factors": factors,
        "total_adjustment_ratio": float(np.sum(adjusted_forecast) / np.sum(forecast_values)) if np.sum(forecast_values) > 0 else 1.0,
    }


def generate_seasonal_threshold_adjustments(
    historical_data: Union[pd.Series, list, np.ndarray],
    base_threshold: float,
    forecast_days: int = 30,
    forecast_start_date: Optional[date] = None,
) -> List[Dict[str, Any]]:
    if forecast_start_date is None:
        forecast_start_date = date.today()

    period = detect_seasonality(historical_data)
    if period is None:
        period = 7

    seasonal_indices = calculate_seasonal_indices(historical_data, period=period)

    adjustments = []
    for i in range(forecast_days):
        forecast_date = forecast_start_date + timedelta(days=i)
        seasonal_index = seasonal_indices[i % period]
        holiday_factor = get_holiday_factor(forecast_date)

        adjustment_factor = seasonal_index * holiday_factor
        adjusted_threshold = max(0, base_threshold * adjustment_factor)

        adjustments.append({
            "date": forecast_date.isoformat(),
            "base_threshold": float(base_threshold),
            "seasonal_index": float(seasonal_index),
            "holiday_factor": float(holiday_factor),
            "adjustment_factor": float(adjustment_factor),
            "adjusted_threshold": float(adjusted_threshold),
            "is_holiday": holiday_factor != 1.0,
            "holiday_name": get_holiday_name(forecast_date),
        })

    return adjustments
