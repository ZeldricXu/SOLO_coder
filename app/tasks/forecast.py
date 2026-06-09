from typing import Optional
from sqlalchemy.orm import Session

from app.tasks.celery_app import celery_app
from app.core.database import SessionLocal
from app.core.logging import get_logger
from app.services.forecast_service import ForecastService
from app.models.sku import SKU

logger = get_logger(__name__)


@celery_app.task(bind=True, max_retries=3, default_retry_delay=600)
def update_weekly_forecast(self) -> dict:
    db = SessionLocal()
    try:
        forecast_service = ForecastService(db)

        active_skus = db.query(SKU).filter(SKU.status == "ACTIVE").all()

        if not active_skus:
            return {"status": "no_active_skus", "forecast_count": 0}

        success_count = 0
        failed_count = 0
        errors = []

        for sku in active_skus:
            try:
                forecast_service.generate_forecast(
                    sku_id=sku.id,
                    forecast_period="WEEKLY",
                    forecast_method="HOLT_WINTERS",
                    history_days=180,
                    forecast_days=90,
                )
                success_count += 1
            except Exception as e:
                failed_count += 1
                errors.append({"sku_id": sku.id, "sku_code": sku.sku_code, "error": str(e)})

        return {
            "status": "completed",
            "total_skus": len(active_skus),
            "success_count": success_count,
            "failed_count": failed_count,
            "errors": errors[:20],
        }
    except Exception as e:
        logger.error("Weekly forecast update failed", error=str(e))
        self.retry(exc=e)
    finally:
        db.close()


@celery_app.task(bind=True, max_retries=2, default_retry_delay=120)
def generate_forecast_for_sku(
    self,
    sku_id: int,
    forecast_method: str = "HOLT_WINTERS",
    history_days: int = 180,
    forecast_days: int = 90,
) -> dict:
    db = SessionLocal()
    try:
        forecast_service = ForecastService(db)
        forecast = forecast_service.generate_forecast(
            sku_id=sku_id,
            forecast_period="DAILY",
            forecast_method=forecast_method,
            history_days=history_days,
            forecast_days=forecast_days,
        )

        return {
            "status": "completed",
            "forecast_id": forecast.id,
            "sku_id": sku_id,
            "method": forecast_method,
            "mape": float(forecast.mape) if forecast.mape else None,
            "rmse": float(forecast.rmse) if forecast.rmse else None,
        }
    except Exception as e:
        logger.error("Generate forecast for SKU failed", sku_id=sku_id, error=str(e))
        self.retry(exc=e)
    finally:
        db.close()


@celery_app.task
def compare_forecast_models(sku_id: int, history_days: int = 180) -> dict:
    db = SessionLocal()
    try:
        forecast_service = ForecastService(db)
        best_model, best_metrics, all_metrics = forecast_service.select_best_model(
            sku_id=sku_id,
            models=None,
            metric="mape",
            history_days=history_days,
        )

        return {
            "status": "completed",
            "sku_id": sku_id,
            "best_model": best_model,
            "best_metrics": {
                "mape": float(best_metrics.mape) if best_metrics.mape else None,
                "rmse": float(best_metrics.rmse) if best_metrics.rmse else None,
                "mae": float(best_metrics.mae) if best_metrics.mae else None,
            },
            "all_models": {
                model: {
                    "mape": float(m.mape) if m.mape else None,
                    "rmse": float(m.rmse) if m.rmse else None,
                    "mae": float(m.mae) if m.mae else None,
                }
                for model, m in all_metrics.items()
            },
        }
    except Exception as e:
        logger.error("Compare forecast models failed", sku_id=sku_id, error=str(e))
        return {"status": "failed", "sku_id": sku_id, "error": str(e)}
    finally:
        db.close()
