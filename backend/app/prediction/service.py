import logging
import os
from datetime import datetime, timedelta
from typing import List, Dict, Optional
from pathlib import Path

import numpy as np
import pandas as pd
from sqlalchemy.orm import Session
from sqlalchemy import func, and_

from app.config import settings
from app.models import PredictionModel as PredictionModelDB, PredictionResult, TrafficFlowRecord
from app.prediction.model import TrafficPredictor

logger = logging.getLogger(__name__)


class PredictionService:
    def __init__(self):
        self.model_dir = Path(settings.PREDICTION_MODEL_DIR)
        self.model_dir.mkdir(parents=True, exist_ok=True)
        self._models_cache = {}

    def train_model(self, db: Session, sensor_id: str, model_type: str = "lstm",
                    start_date: datetime = None, end_date: datetime = None,
                    epochs: int = 100, sequence_length: int = 24) -> Dict:
        if end_date is None:
            end_date = datetime.utcnow()
        if start_date is None:
            start_date = end_date - timedelta(days=30)

        data = self._get_historical_data(db, sensor_id, start_date, end_date)

        if len(data) < sequence_length * 2:
            return {"error": f"Insufficient data: only {len(data)} records available"}

        flow_values = data['vehicle_count'].values.astype(np.float32)

        flow_values = self._normalize(flow_values)

        predictor = TrafficPredictor(
            model_type=model_type,
            input_size=1,
            hidden_size=64,
            num_layers=2,
        )

        history = predictor.train(
            flow_values,
            epochs=epochs,
            sequence_length=sequence_length,
            batch_size=32,
            learning_rate=0.001,
        )

        model_version = datetime.utcnow().strftime("%Y%m%d%H%M%S")
        model_name = f"{sensor_id}_{model_type}_{model_version}"
        model_path = self.model_dir / f"{model_name}.pth"

        predictor.save_model(str(model_path))

        model_record = PredictionModelDB(
            name=model_name,
            model_type=model_type,
            version=model_version,
            status="completed",
            metrics={
                "final_train_loss": history["final_train_loss"],
                "final_val_loss": history["final_val_loss"],
                "training_samples": len(data),
            },
            config={
                "sensor_id": sensor_id,
                "epochs": epochs,
                "sequence_length": sequence_length,
                "start_date": start_date.isoformat(),
                "end_date": end_date.isoformat(),
            },
            model_path=str(model_path),
            trained_at=datetime.utcnow(),
        )

        db.add(model_record)
        db.commit()
        db.refresh(model_record)

        return {
            "model_id": model_record.id,
            "model_name": model_name,
            "model_type": model_type,
            "version": model_version,
            "metrics": model_record.metrics,
            "training_history": history,
        }

    def predict(self, db: Session, sensor_id: str, horizons: List[int] = None,
                model_type: str = "lstm", model_id: int = None) -> Dict:
        if horizons is None:
            horizons = settings.DEFAULT_PREDICTION_HORIZONS

        predictor = self._load_model(db, sensor_id, model_type, model_id)
        if predictor is None:
            return {"error": "No trained model available"}

        end_time = datetime.utcnow()
        start_time = end_time - timedelta(hours=4)

        data = self._get_historical_data(db, sensor_id, start_time, end_time)

        if len(data) < 10:
            return {"error": "Insufficient recent data for prediction"}

        flow_values = data['vehicle_count'].values.astype(np.float32)
        flow_norm = self._normalize(flow_values)

        sequence_length = 24
        if len(flow_norm) < sequence_length:
            padded = np.zeros(sequence_length)
            padded[-len(flow_norm):] = flow_norm
            flow_norm = padded

        predictions = predictor.predict_multi_horizon(
            flow_norm,
            sequence_length=sequence_length,
            horizons=horizons,
        )

        prediction_time = datetime.utcnow()
        results = []

        for horizon, pred_norm in predictions.items():
            if pred_norm is None:
                continue

            predicted_flow = self._denormalize(pred_norm, flow_values)
            predicted_congestion = min(1.0, predicted_flow / 1000)
            confidence = max(0.5, 1.0 - horizon / 120)

            target_time = prediction_time + timedelta(minutes=horizon)

            result = PredictionResult(
                model_id=None,
                sensor_id=sensor_id,
                prediction_time=prediction_time,
                target_time=target_time,
                horizon_minutes=horizon,
                predicted_flow=float(predicted_flow),
                predicted_congestion=float(predicted_congestion),
                confidence=confidence,
            )
            db.add(result)

            results.append({
                "horizon_minutes": horizon,
                "target_time": target_time.isoformat(),
                "predicted_flow": float(predicted_flow),
                "predicted_congestion": float(predicted_congestion),
                "confidence": confidence,
            })

        db.commit()

        return {
            "sensor_id": sensor_id,
            "prediction_time": prediction_time.isoformat(),
            "model_type": model_type,
            "predictions": results,
        }

    def batch_predict(self, db: Session, sensor_ids: List[str],
                      horizons: List[int] = None) -> Dict:
        if horizons is None:
            horizons = settings.DEFAULT_PREDICTION_HORIZONS

        results = {}
        for sensor_id in sensor_ids:
            try:
                result = self.predict(db, sensor_id, horizons)
                results[sensor_id] = result
            except Exception as e:
                logger.error(f"Prediction failed for sensor {sensor_id}: {e}")
                results[sensor_id] = {"error": str(e)}

        return {
            "prediction_time": datetime.utcnow().isoformat(),
            "sensor_count": len(sensor_ids),
            "results": results,
        }

    def get_prediction_history(self, db: Session, sensor_id: str,
                               start_time: datetime = None,
                               end_time: datetime = None,
                               horizon_minutes: int = 15,
                               limit: int = 100) -> List[Dict]:
        query = db.query(PredictionResult).filter(
            PredictionResult.sensor_id == sensor_id,
            PredictionResult.horizon_minutes == horizon_minutes,
        )

        if start_time:
            query = query.filter(PredictionResult.prediction_time >= start_time)
        if end_time:
            query = query.filter(PredictionResult.prediction_time <= end_time)

        records = query.order_by(PredictionResult.prediction_time.desc()).limit(limit).all()

        return [
            {
                "id": r.id,
                "prediction_time": r.prediction_time.isoformat(),
                "target_time": r.target_time.isoformat(),
                "horizon_minutes": r.horizon_minutes,
                "predicted_flow": r.predicted_flow,
                "predicted_congestion": r.predicted_congestion,
                "confidence": r.confidence,
            }
            for r in records
        ]

    def list_models(self, db: Session, sensor_id: str = None,
                    model_type: str = None, status: str = None,
                    limit: int = 50) -> List[Dict]:
        query = db.query(PredictionModelDB)

        if sensor_id:
            query = query.filter(PredictionModelDB.config['sensor_id'].astext == sensor_id)
        if model_type:
            query = query.filter(PredictionModelDB.model_type == model_type)
        if status:
            query = query.filter(PredictionModelDB.status == status)

        models = query.order_by(PredictionModelDB.created_at.desc()).limit(limit).all()

        return [
            {
                "id": m.id,
                "name": m.name,
                "model_type": m.model_type,
                "version": m.version,
                "status": m.status,
                "metrics": m.metrics,
                "config": m.config,
                "trained_at": m.trained_at.isoformat() if m.trained_at else None,
                "created_at": m.created_at.isoformat(),
            }
            for m in models
        ]

    def _get_historical_data(self, db: Session, sensor_id: str,
                             start_time: datetime, end_time: datetime) -> pd.DataFrame:
        records = db.query(
            TrafficFlowRecord.timestamp,
            TrafficFlowRecord.vehicle_count,
            TrafficFlowRecord.congestion_index,
            TrafficFlowRecord.avg_speed,
        ).filter(
            TrafficFlowRecord.sensor_id == sensor_id,
            TrafficFlowRecord.timestamp >= start_time,
            TrafficFlowRecord.timestamp <= end_time,
        ).order_by(TrafficFlowRecord.timestamp).all()

        df = pd.DataFrame([{
            'timestamp': r.timestamp,
            'vehicle_count': r.vehicle_count or 0,
            'congestion_index': r.congestion_index or 0,
            'avg_speed': r.avg_speed or 0,
        } for r in records])

        if not df.empty:
            df = df.set_index('timestamp')
            df = df.resample('5min').mean().fillna(method='ffill').reset_index()

        return df

    def _normalize(self, data: np.ndarray) -> np.ndarray:
        self._min_val = np.min(data) if len(data) > 0 else 0
        self._max_val = np.max(data) if len(data) > 0 else 1
        if self._max_val == self._min_val:
            return np.zeros_like(data)
        return (data - self._min_val) / (self._max_val - self._min_val)

    def _denormalize(self, value: float, original_data: np.ndarray) -> float:
        min_val = np.min(original_data) if len(original_data) > 0 else 0
        max_val = np.max(original_data) if len(original_data) > 0 else 1
        return value * (max_val - min_val) + min_val

    def _load_model(self, db: Session, sensor_id: str,
                    model_type: str = "lstm", model_id: int = None) -> Optional[TrafficPredictor]:
        cache_key = f"{sensor_id}_{model_type}_{model_id}"

        if cache_key in self._models_cache:
            return self._models_cache[cache_key]

        if model_id:
            model_record = db.query(PredictionModelDB).filter(
                PredictionModelDB.id == model_id
            ).first()
        else:
            model_record = db.query(PredictionModelDB).filter(
                PredictionModelDB.model_type == model_type,
                PredictionModelDB.status == "completed",
                PredictionModelDB.config['sensor_id'].astext == sensor_id,
            ).order_by(PredictionModelDB.trained_at.desc()).first()

        if not model_record or not model_record.model_path:
            return None

        try:
            predictor = TrafficPredictor(model_type=model_type)
            predictor.load_model(model_record.model_path)
            self._models_cache[cache_key] = predictor
            return predictor
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            return None


prediction_service = PredictionService()
