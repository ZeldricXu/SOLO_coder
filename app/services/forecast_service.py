import pandas as pd
import numpy as np
from datetime import datetime, timedelta
from typing import Optional, List, Dict, Any, Tuple
from sqlalchemy.orm import Session
from sqlalchemy import func, and_

from app.core.cache import cache
from app.core.logging import get_logger
from app.models.inventory_transaction import InventoryTransaction, TransactionType
from app.models.sku import SKU
from app.models.product import Product
from app.utils.forecast import (
    moving_average,
    exponential_smoothing,
    double_exponential_smoothing,
    arima_forecast,
    linear_regression_forecast,
    auto_arima_forecast,
    seasonal_decompose,
    seasonal_forecast,
    detect_seasonality,
    evaluate_forecast,
    select_best_model,
    calculate_mape,
    ForecastMetrics,
)
from app.schemas.purchase_order import ForecastMethodEnum

logger = get_logger(__name__)


class ForecastService:
    def __init__(self, db: Session):
        self.db = db

    def get_sales_history(
        self,
        sku_id: int,
        warehouse_id: Optional[int] = None,
        days: int = 90,
        aggregate: str = "daily",
    ) -> pd.DataFrame:
        cache_key = f"forecast:sales_history:{sku_id}:{warehouse_id}:{days}:{aggregate}"
        cached = cache.get(cache_key)
        if cached is not None:
            return cached

        end_date = datetime.utcnow()
        start_date = end_date - timedelta(days=days)

        query = self.db.query(
            func.date(InventoryTransaction.created_at).label("date"),
            func.sum(InventoryTransaction.quantity).label("quantity"),
        ).filter(
            and_(
                InventoryTransaction.sku_id == sku_id,
                InventoryTransaction.transaction_type.in_([
                    TransactionType.OUTBOUND,
                    TransactionType.SALE,
                    TransactionType.SHIPMENT,
                ]),
                InventoryTransaction.created_at >= start_date,
                InventoryTransaction.created_at <= end_date,
                InventoryTransaction.quantity < 0,
            )
        )

        if warehouse_id:
            query = query.filter(InventoryTransaction.warehouse_id == warehouse_id)

        results = query.group_by(
            func.date(InventoryTransaction.created_at)
        ).order_by("date").all()

        date_range = pd.date_range(start=start_date.date(), end=end_date.date(), freq="D")
        df = pd.DataFrame(index=date_range)
        df.index.name = "date"
        df["quantity"] = 0

        for row in results:
            if row.date in df.index:
                df.loc[row.date, "quantity"] = abs(row.quantity)

        if aggregate == "weekly":
            df = df.resample("W").sum()
        elif aggregate == "monthly":
            df = df.resample("M").sum()

        cache.set(cache_key, df, ttl=3600)
        return df

    def get_history_data(
        self,
        sku_id: int,
        warehouse_id: Optional[int] = None,
        days: int = 90,
    ) -> pd.Series:
        df = self.get_sales_history(sku_id, warehouse_id, days)
        return df["quantity"].fillna(0)

    def forecast_demand(
        self,
        sku_id: int,
        method: ForecastMethodEnum = ForecastMethodEnum.MOVING_AVERAGE,
        periods: int = 30,
        warehouse_id: Optional[int] = None,
        history_days: int = 90,
        **kwargs,
    ) -> Tuple[np.ndarray, np.ndarray, ForecastMetrics]:
        data = self.get_history_data(sku_id, warehouse_id, history_days)

        if len(data) == 0:
            sku = self.db.query(SKU).filter(SKU.id == sku_id).first()
            default_demand = sku.reorder_point / 30 if sku and sku.reorder_point > 0 else 1
            forecast = np.full(periods, default_demand)
            return np.array([]), forecast, ForecastMetrics()

        train_size = int(len(data) * 0.8)
        if train_size < 7:
            train_size = max(7, len(data) - 7)

        train_data = data.iloc[:train_size]
        test_data = data.iloc[train_size:]

        forecast_funcs = {
            ForecastMethodEnum.MOVING_AVERAGE: lambda d, p: (
                moving_average(d, window=kwargs.get("window", 7), periods=p)
            ),
            ForecastMethodEnum.EXPONENTIAL_SMOOTHING: lambda d, p: (
                exponential_smoothing(d, alpha=kwargs.get("alpha"), periods=p)
            ),
            ForecastMethodEnum.ARIMA: lambda d, p: (
                arima_forecast(d, order=kwargs.get("order", (1, 1, 1)), periods=p)
            ),
            ForecastMethodEnum.LINEAR_REGRESSION: lambda d, p: (
                linear_regression_forecast(d, periods=p, degree=kwargs.get("degree", 1))
            ),
        }

        try:
            fitted, forecast = forecast_funcs[method](train_data, len(test_data))
            if len(fitted) > 0:
                metrics = evaluate_forecast(test_data.values, forecast[:len(test_data)])
            else:
                metrics = ForecastMetrics()
        except Exception as e:
            logger.warning(f"Forecast method {method} failed, using fallback: {e}")
            fitted, forecast = moving_average(train_data, periods=len(test_data))
            metrics = evaluate_forecast(test_data.values, forecast[:len(test_data)])

        try:
            _, final_forecast = forecast_funcs[method](data, periods)
            final_forecast = np.maximum(0, final_forecast)
        except Exception as e:
            logger.warning(f"Final forecast failed: {e}")
            _, final_forecast = moving_average(data, periods=periods)
            final_forecast = np.maximum(0, final_forecast)

        return fitted, final_forecast, metrics

    def auto_forecast(
        self,
        sku_id: int,
        periods: int = 30,
        warehouse_id: Optional[int] = None,
        history_days: int = 90,
    ) -> Tuple[str, np.ndarray, Dict[str, ForecastMetrics]]:
        data = self.get_history_data(sku_id, warehouse_id, history_days)

        if len(data) < 14:
            _, forecast = moving_average(data, periods=periods)
            return "ma", np.maximum(0, forecast), {"ma": ForecastMetrics(mape=0.1)}

        try:
            best_model, best_metrics, all_metrics = select_best_model(
                data,
                metric="mape",
                periods=periods,
            )

            model_funcs = {
                "ma": moving_average,
                "es": exponential_smoothing,
                "des": double_exponential_smoothing,
                "lr": linear_regression_forecast,
                "arima": lambda d, p: auto_arima_forecast(d, periods=p)[:2],
            }

            if best_model in model_funcs:
                _, forecast = model_funcs[best_model](data, periods=periods)
            else:
                _, forecast = moving_average(data, periods=periods)

            forecast = np.maximum(0, forecast)
            return best_model, forecast, all_metrics

        except Exception as e:
            logger.warning(f"Auto forecast failed: {e}")
            _, forecast = moving_average(data, periods=periods)
            return "ma", np.maximum(0, forecast), {"ma": ForecastMetrics(mape=0.1)}

    def analyze_seasonality(
        self,
        sku_id: int,
        warehouse_id: Optional[int] = None,
        history_days: int = 180,
    ) -> Dict[str, Any]:
        data = self.get_history_data(sku_id, warehouse_id, history_days)

        if len(data) < 30:
            return {
                "has_seasonality": False,
                "period": None,
                "strength": 0.0,
            }

        period = detect_seasonality(data)
        strength = 0.0
        decomposition = {}

        if period:
            try:
                decomposition = seasonal_decompose(data, period=period)
                seasonal_var = np.var(decomposition["seasonal"])
                total_var = np.var(data)
                strength = seasonal_var / total_var if total_var > 0 else 0
            except Exception as e:
                logger.warning(f"Seasonal decomposition failed: {e}")
                period = None

        return {
            "has_seasonality": period is not None and strength > 0.3,
            "period": period,
            "strength": round(strength, 4),
            "trend": decomposition.get("trend", []).tolist() if decomposition else [],
            "seasonal": decomposition.get("seasonal", []).tolist() if decomposition else [],
            "residual": decomposition.get("residual", []).tolist() if decomposition else [],
        }

    def calculate_safety_stock(
        self,
        sku_id: int,
        warehouse_id: Optional[int] = None,
        service_level: float = 0.95,
        lead_time_days: Optional[int] = None,
        history_days: int = 90,
    ) -> int:
        cache_key = f"forecast:safety_stock:{sku_id}:{warehouse_id}:{service_level}:{lead_time_days}"
        cached = cache.get(cache_key)
        if cached is not None:
            return cached

        sku = self.db.query(SKU).filter(SKU.id == sku_id).first()
        if not sku:
            return 0

        if lead_time_days is None:
            lead_time_days = sku.lead_time_days or 7

        data = self.get_history_data(sku_id, warehouse_id, history_days)

        if len(data) < 7:
            safety_stock = int(sku.safety_stock or (sku.reorder_point // 2))
            cache.set(cache_key, safety_stock, ttl=86400)
            return safety_stock

        daily_demand_std = np.std(data.values)

        from scipy import stats
        z_score = stats.norm.ppf(service_level)

        safety_stock = int(z_score * daily_demand_std * np.sqrt(lead_time_days))
        safety_stock = max(0, safety_stock)

        if sku.safety_stock and safety_stock < sku.safety_stock:
            safety_stock = sku.safety_stock

        cache.set(cache_key, safety_stock, ttl=86400)
        return safety_stock

    def calculate_reorder_point(
        self,
        sku_id: int,
        warehouse_id: Optional[int] = None,
        service_level: float = 0.95,
        lead_time_days: Optional[int] = None,
        history_days: int = 90,
    ) -> int:
        sku = self.db.query(SKU).filter(SKU.id == sku_id).first()
        if not sku:
            return 0

        if lead_time_days is None:
            lead_time_days = sku.lead_time_days or 7

        _, forecast, _ = self.forecast_demand(
            sku_id,
            method=ForecastMethodEnum.MOVING_AVERAGE,
            periods=lead_time_days,
            warehouse_id=warehouse_id,
            history_days=history_days,
        )

        lead_time_demand = int(np.sum(forecast[:lead_time_days]))
        safety_stock = self.calculate_safety_stock(
            sku_id, warehouse_id, service_level, lead_time_days, history_days
        )

        reorder_point = lead_time_demand + safety_stock
        reorder_point = max(reorder_point, sku.reorder_point or 0)

        return reorder_point

    def forecast_multiple_skus(
        self,
        sku_ids: List[int],
        method: ForecastMethodEnum = ForecastMethodEnum.MOVING_AVERAGE,
        periods: int = 30,
        warehouse_id: Optional[int] = None,
        history_days: int = 90,
    ) -> Dict[int, Dict[str, Any]]:
        results = {}

        for sku_id in sku_ids:
            try:
                _, forecast, metrics = self.forecast_demand(
                    sku_id, method, periods, warehouse_id, history_days
                )
                results[sku_id] = {
                    "sku_id": sku_id,
                    "forecast": forecast.tolist(),
                    "total_demand": float(np.sum(forecast)),
                    "daily_demand": float(np.mean(forecast)),
                    "metrics": metrics.to_dict(),
                    "grade": metrics.get_grade(),
                }
            except Exception as e:
                logger.error(f"Forecast failed for SKU {sku_id}: {e}")
                results[sku_id] = {
                    "sku_id": sku_id,
                    "forecast": [0] * periods,
                    "total_demand": 0,
                    "daily_demand": 0,
                    "metrics": {},
                    "grade": "ERROR",
                    "error": str(e),
                }

        return results

    def compare_models(
        self,
        sku_id: int,
        models: List[ForecastMethodEnum],
        warehouse_id: Optional[int] = None,
        history_days: int = 90,
    ) -> Dict[str, Any]:
        data = self.get_history_data(sku_id, warehouse_id, history_days)

        if len(data) < 14:
            return {"error": "Insufficient data for model comparison"}

        train_size = int(len(data) * 0.8)
        train_data = data.iloc[:train_size]
        test_data = data.iloc[train_size:]
        test_periods = len(test_data)

        model_funcs = {
            ForecastMethodEnum.MOVING_AVERAGE: ("ma", lambda d, p: moving_average(d, periods=p)),
            ForecastMethodEnum.EXPONENTIAL_SMOOTHING: ("es", lambda d, p: exponential_smoothing(d, periods=p)),
            ForecastMethodEnum.LINEAR_REGRESSION: ("lr", lambda d, p: linear_regression_forecast(d, periods=p)),
        }

        if len(data) >= 30:
            model_funcs[ForecastMethodEnum.ARIMA] = (
                "arima", lambda d, p: auto_arima_forecast(d, periods=p)[:2]
            )

        results = {}
        for model_enum in models:
            if model_enum not in model_funcs:
                continue

            model_name, func = model_funcs[model_enum]
            try:
                _, forecast = func(train_data, test_periods)
                metrics = evaluate_forecast(test_data.values, forecast[:len(test_data)])
                results[model_name] = {
                    "metrics": metrics.to_dict(),
                    "grade": metrics.get_grade(),
                    "forecast": forecast.tolist(),
                    "actual": test_data.values.tolist(),
                }
            except Exception as e:
                results[model_name] = {"error": str(e)}

        best_model = min(
            (k for k in results if "error" not in results[k]),
            key=lambda k: results[k]["metrics"]["mape"],
            default=None,
        )

        return {
            "sku_id": sku_id,
            "best_model": best_model,
            "models": results,
            "history_days": history_days,
            "train_size": train_size,
            "test_size": len(test_data),
        }

    def get_forecast_with_confidence(
        self,
        sku_id: int,
        method: ForecastMethodEnum = ForecastMethodEnum.MOVING_AVERAGE,
        periods: int = 30,
        confidence_level: float = 0.95,
        warehouse_id: Optional[int] = None,
        history_days: int = 90,
    ) -> Dict[str, Any]:
        data = self.get_history_data(sku_id, warehouse_id, history_days)

        if len(data) < 7:
            default = np.full(periods, 0.0)
            return {
                "forecast": default.tolist(),
                "lower_bound": default.tolist(),
                "upper_bound": default.tolist(),
                "confidence_level": confidence_level,
            }

        fitted, forecast, _ = self.forecast_demand(
            sku_id, method, periods, warehouse_id, history_days
        )

        if len(fitted) > 0:
            errors = data.values[-len(fitted):] - fitted
            std_error = np.std(errors, ddof=1) if len(errors) > 1 else 1.0

            from scipy import stats
            z_score = stats.norm.ppf((1 + confidence_level) / 2)
            margin = z_score * std_error

            lower_bound = np.maximum(0, forecast - margin)
            upper_bound = forecast + margin
        else:
            lower_bound = forecast * 0.7
            upper_bound = forecast * 1.3

        return {
            "sku_id": sku_id,
            "forecast": forecast.tolist(),
            "lower_bound": lower_bound.tolist(),
            "upper_bound": upper_bound.tolist(),
            "confidence_level": confidence_level,
            "method": method.value,
        }

    def calculate_lead_time_demand(
        self,
        sku_id: int,
        lead_time_days: int,
        warehouse_id: Optional[int] = None,
        method: ForecastMethodEnum = ForecastMethodEnum.MOVING_AVERAGE,
        history_days: int = 90,
    ) -> int:
        _, forecast, _ = self.forecast_demand(
            sku_id,
            method=method,
            periods=lead_time_days,
            warehouse_id=warehouse_id,
            history_days=history_days,
        )
        return int(np.sum(forecast[:lead_time_days]))

    def detect_trend(
        self,
        sku_id: int,
        warehouse_id: Optional[int] = None,
        history_days: int = 180,
    ) -> Dict[str, Any]:
        data = self.get_history_data(sku_id, warehouse_id, history_days)

        if len(data) < 14:
            return {"trend": "stable", "slope": 0.0, "p_value": 1.0}

        x = np.arange(len(data))
        y = data.values

        from scipy import stats
        slope, intercept, r_value, p_value, std_err = stats.linregress(x, y)

        if p_value < 0.05:
            if slope > 0:
                trend = "increasing"
            elif slope < 0:
                trend = "decreasing"
            else:
                trend = "stable"
        else:
            trend = "stable"

        return {
            "trend": trend,
            "slope": round(slope, 4),
            "intercept": round(intercept, 4),
            "r_squared": round(r_value ** 2, 4),
            "p_value": round(p_value, 4),
            "std_error": round(std_err, 4),
            "is_significant": p_value < 0.05,
        }
