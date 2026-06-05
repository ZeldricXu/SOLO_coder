import random
import json
from typing import List, Optional, Dict, Any, Tuple
from datetime import datetime, timedelta
from sqlalchemy import and_, func

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.model import (
    ABTestExperimentCreate,
    ABTestExperimentUpdate,
    ABTestStatusEnum,
    TrafficSplitStrategyEnum,
    ABTestResultCreate,
)
from app.models.model import ModelVersion, ABTestExperiment, ABTestResult
from app.core.database import get_sync_db

logger = get_logger(__name__)
settings = get_settings()


class ABTestService:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        from app.services.model_service import ModelService

        self.model_service = ModelService()

    def create_experiment(
        self,
        request: ABTestExperimentCreate,
    ) -> ABTestExperiment:
        db = next(get_sync_db())
        try:
            variant_a = db.query(ModelVersion).filter(
                ModelVersion.id == request.variant_a_model_id
            ).first()
            variant_b = db.query(ModelVersion).filter(
                ModelVersion.id == request.variant_b_model_id
            ).first()

            if not variant_a or not variant_b:
                raise ValueError("One or both model variants not found")

            if variant_a.model_name != variant_b.model_name:
                raise ValueError("Both variants must be of the same model type")

            active_experiment = db.query(ABTestExperiment).filter(
                and_(
                    ABTestExperiment.model_name == variant_a.model_name,
                    ABTestExperiment.status.in_([
                        ABTestStatusEnum.RUNNING,
                        ABTestStatusEnum.SCHEDULED,
                    ])
                )
            ).first()

            if active_experiment:
                raise ValueError(
                    f"An active experiment already exists for model {variant_a.model_name}"
                )

            experiment = ABTestExperiment(**request.model_dump())
            experiment.model_name = variant_a.model_name

            if not experiment.traffic_split_a:
                experiment.traffic_split_a = 50.0
            if not experiment.traffic_split_b:
                experiment.traffic_split_b = 50.0

            db.add(experiment)
            db.commit()
            db.refresh(experiment)

            logger.info(
                f"Created A/B test experiment {experiment.id} for model {experiment.model_name}: "
                f"{variant_a.version} ({experiment.traffic_split_a}%) vs "
                f"{variant_b.version} ({experiment.traffic_split_b}%)"
            )

            return experiment

        except Exception as e:
            logger.error(f"Failed to create A/B test experiment: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_experiment(
        self,
        experiment_id: int,
    ) -> Optional[ABTestExperiment]:
        db = next(get_sync_db())
        try:
            return db.query(ABTestExperiment).filter(
                ABTestExperiment.id == experiment_id
            ).first()
        finally:
            db.close()

    def list_experiments(
        self,
        model_name: Optional[str] = None,
        status: Optional[ABTestStatusEnum] = None,
        page: int = 1,
        page_size: int = 20,
    ) -> Tuple[List[Dict[str, Any]], int]:
        db = next(get_sync_db())
        try:
            query = db.query(ABTestExperiment)

            if model_name:
                query = query.filter(ABTestExperiment.model_name == model_name)
            if status:
                query = query.filter(ABTestExperiment.status == status)

            query = query.order_by(ABTestExperiment.created_at.desc())

            total = query.count()
            experiments = query.offset((page - 1) * page_size).limit(page_size).all()

            result = []
            for exp in experiments:
                variant_a = db.query(ModelVersion).filter(
                    ModelVersion.id == exp.variant_a_model_id
                ).first()
                variant_b = db.query(ModelVersion).filter(
                    ModelVersion.id == exp.variant_b_model_id
                ).first()

                result.append({
                    "id": exp.id,
                    "experiment_name": exp.experiment_name,
                    "model_name": exp.model_name,
                    "status": exp.status.value,
                    "variant_a": {
                        "model_id": exp.variant_a_model_id,
                        "version": variant_a.version if variant_a else None,
                        "traffic_split": exp.traffic_split_a,
                    },
                    "variant_b": {
                        "model_id": exp.variant_b_model_id,
                        "version": variant_b.version if variant_b else None,
                        "traffic_split": exp.traffic_split_b,
                    },
                    "primary_metric": exp.primary_metric,
                    "sample_size_a": exp.sample_size_a,
                    "sample_size_b": exp.sample_size_b,
                    "created_at": exp.created_at,
                    "started_at": exp.started_at,
                    "ended_at": exp.ended_at,
                })

            return result, total

        finally:
            db.close()

    def update_experiment(
        self,
        experiment_id: int,
        update: ABTestExperimentUpdate,
    ) -> Optional[ABTestExperiment]:
        db = next(get_sync_db())
        try:
            experiment = db.query(ABTestExperiment).filter(
                ABTestExperiment.id == experiment_id
            ).first()

            if not experiment:
                return None

            for field, value in update.model_dump(exclude_unset=True).items():
                setattr(experiment, field, value)

            db.commit()
            db.refresh(experiment)

            logger.info(f"Updated A/B test experiment {experiment_id}")
            return experiment

        except Exception as e:
            logger.error(f"Failed to update A/B test experiment {experiment_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def start_experiment(
        self,
        experiment_id: int,
    ) -> Optional[ABTestExperiment]:
        db = next(get_sync_db())
        try:
            experiment = db.query(ABTestExperiment).filter(
                ABTestExperiment.id == experiment_id
            ).first()

            if not experiment:
                return None

            if experiment.status not in [
                ABTestStatusEnum.DRAFT,
                ABTestStatusEnum.SCHEDULED,
            ]:
                raise ValueError(f"Cannot start experiment in status {experiment.status.value}")

            experiment.status = ABTestStatusEnum.RUNNING
            experiment.started_at = datetime.utcnow()

            db.commit()
            db.refresh(experiment)

            logger.info(f"Started A/B test experiment {experiment_id}")
            return experiment

        except Exception as e:
            logger.error(f"Failed to start A/B test experiment {experiment_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def stop_experiment(
        self,
        experiment_id: int,
        winner: Optional[str] = None,
        notes: Optional[str] = None,
    ) -> Optional[ABTestExperiment]:
        db = next(get_sync_db())
        try:
            experiment = db.query(ABTestExperiment).filter(
                ABTestExperiment.id == experiment_id
            ).first()

            if not experiment:
                return None

            if experiment.status != ABTestStatusEnum.RUNNING:
                raise ValueError(f"Cannot stop experiment in status {experiment.status.value}")

            experiment.status = ABTestStatusEnum.COMPLETED
            experiment.ended_at = datetime.utcnow()
            experiment.winner = winner
            experiment.conclusion_notes = notes

            db.commit()
            db.refresh(experiment)

            logger.info(
                f"Stopped A/B test experiment {experiment_id}. "
                f"Winner: {winner}"
            )

            return experiment

        except Exception as e:
            logger.error(f"Failed to stop A/B test experiment {experiment_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_active_experiment(
        self,
        model_name: str,
    ) -> Optional[ABTestExperiment]:
        db = next(get_sync_db())
        try:
            return db.query(ABTestExperiment).filter(
                and_(
                    ABTestExperiment.model_name == model_name,
                    ABTestExperiment.status == ABTestStatusEnum.RUNNING,
                )
            ).first()
        finally:
            db.close()

    def route_traffic(
        self,
        model_name: str,
        document_id: Optional[int] = None,
    ) -> Dict[str, Any]:
        experiment = self.get_active_experiment(model_name)

        if not experiment:
            production_model = self.model_service.get_production_model(model_name)
            if production_model:
                return {
                    "variant": "production",
                    "model_id": production_model.id,
                    "model_name": production_model.model_name,
                    "version": production_model.version,
                    "experiment_id": None,
                }

            default = self.model_service.get_available_versions(model_name)
            if default:
                return {
                    "variant": "default",
                    "model_id": default[0].id,
                    "model_name": default[0].model_name,
                    "version": default[0].version,
                    "experiment_id": None,
                }

            raise ValueError(f"No model available for {model_name}")

        if experiment.strategy == TrafficSplitStrategyEnum.RANDOM:
            rand_val = random.random() * 100
            if rand_val < experiment.traffic_split_a:
                variant = "a"
                model_id = experiment.variant_a_model_id
            else:
                variant = "b"
                model_id = experiment.variant_b_model_id

        elif experiment.strategy == TrafficSplitStrategyEnum.HASH:
            if not document_id:
                raise ValueError("document_id required for hash-based routing")

            hash_val = hash(f"{experiment.id}:{document_id}") % 100
            if hash_val < experiment.traffic_split_a:
                variant = "a"
                model_id = experiment.variant_a_model_id
            else:
                variant = "b"
                model_id = experiment.variant_b_model_id

        elif experiment.strategy == TrafficSplitStrategyEnum.ROUND_ROBIN:
            key = f"abtest:rr:{experiment.id}"
            from app.services.storage import StorageService
            storage = StorageService()

            try:
                current_val = storage.cache_get(key)
                current = int(current_val) if current_val else 0
                variant = "a" if current % 2 == 0 else "b"
                model_id = experiment.variant_a_model_id if variant == "a" else experiment.variant_b_model_id
                storage.cache_set(key, str(current + 1), 3600)
            except Exception:
                rand_val = random.random() * 100
                if rand_val < experiment.traffic_split_a:
                    variant = "a"
                    model_id = experiment.variant_a_model_id
                else:
                    variant = "b"
                    model_id = experiment.variant_b_model_id
        else:
            raise ValueError(f"Unknown traffic split strategy: {experiment.strategy}")

        db = next(get_sync_db())
        try:
            model = db.query(ModelVersion).filter(ModelVersion.id == model_id).first()

            return {
                "variant": variant,
                "model_id": model_id,
                "model_name": model.model_name if model else model_name,
                "version": model.version if model else None,
                "experiment_id": experiment.id,
            }
        finally:
            db.close()

    def record_result(
        self,
        request: ABTestResultCreate,
    ) -> ABTestResult:
        db = next(get_sync_db())
        try:
            result = ABTestResult(**request.model_dump())
            db.add(result)

            experiment = db.query(ABTestExperiment).filter(
                ABTestExperiment.id == request.experiment_id
            ).first()

            if experiment:
                if request.variant == "a":
                    experiment.sample_size_a = (experiment.sample_size_a or 0) + 1
                elif request.variant == "b":
                    experiment.sample_size_b = (experiment.sample_size_b or 0) + 1

            db.commit()
            db.refresh(result)

            logger.info(
                f"Recorded A/B test result for experiment {request.experiment_id}, "
                f"variant {request.variant}, metric {request.metric_name}: {request.metric_value}"
            )

            return result

        except Exception as e:
            logger.error(f"Failed to record A/B test result: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_experiment_results(
        self,
        experiment_id: int,
    ) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            experiment = db.query(ABTestExperiment).filter(
                ABTestExperiment.id == experiment_id
            ).first()

            if not experiment:
                return None

            variant_a = db.query(ModelVersion).filter(
                ModelVersion.id == experiment.variant_a_model_id
            ).first()
            variant_b = db.query(ModelVersion).filter(
                ModelVersion.id == experiment.variant_b_model_id
            ).first()

            results_a = db.query(ABTestResult).filter(
                and_(
                    ABTestResult.experiment_id == experiment_id,
                    ABTestResult.variant == "a",
                )
            ).all()

            results_b = db.query(ABTestResult).filter(
                and_(
                    ABTestResult.experiment_id == experiment_id,
                    ABTestResult.variant == "b",
                )
            ).all()

            metric_a = {}
            metric_b = {}

            for r in results_a:
                if r.metric_name not in metric_a:
                    metric_a[r.metric_name] = []
                metric_a[r.metric_name].append(r.metric_value)

            for r in results_b:
                if r.metric_name not in metric_b:
                    metric_b[r.metric_name] = []
                metric_b[r.metric_name].append(r.metric_value)

            stats_a = self._calculate_statistics(metric_a)
            stats_b = self._calculate_statistics(metric_b)

            winner = None
            confidence = None
            primary_metric = experiment.primary_metric

            if primary_metric and primary_metric in stats_a and primary_metric in stats_b:
                mean_a = stats_a[primary_metric]["mean"]
                mean_b = stats_b[primary_metric]["mean"]

                if mean_a > mean_b:
                    winner = "a"
                elif mean_b > mean_a:
                    winner = "b"

                confidence = self._calculate_confidence(
                    metric_a.get(primary_metric, []),
                    metric_b.get(primary_metric, []),
                )

            return {
                "experiment_id": experiment.id,
                "experiment_name": experiment.experiment_name,
                "model_name": experiment.model_name,
                "status": experiment.status.value,
                "variant_a": {
                    "model_id": experiment.variant_a_model_id,
                    "version": variant_a.version if variant_a else None,
                    "traffic_split": experiment.traffic_split_a,
                    "sample_size": experiment.sample_size_a or 0,
                    "metrics": stats_a,
                },
                "variant_b": {
                    "model_id": experiment.variant_b_model_id,
                    "version": variant_b.version if variant_b else None,
                    "traffic_split": experiment.traffic_split_b,
                    "sample_size": experiment.sample_size_b or 0,
                    "metrics": stats_b,
                },
                "primary_metric": primary_metric,
                "current_winner": winner,
                "confidence": confidence,
                "started_at": experiment.started_at,
                "ended_at": experiment.ended_at,
                "duration_hours": (
                    (experiment.ended_at - experiment.started_at).total_seconds() / 3600
                    if experiment.started_at and experiment.ended_at
                    else (
                        (datetime.utcnow() - experiment.started_at).total_seconds() / 3600
                        if experiment.started_at
                        else None
                    )
                ),
            }

        finally:
            db.close()

    def _calculate_statistics(self, metrics: Dict[str, List[float]]) -> Dict[str, Any]:
        result = {}

        for name, values in metrics.items():
            if not values:
                result[name] = {
                    "count": 0,
                    "mean": None,
                    "std": None,
                    "min": None,
                    "max": None,
                }
                continue

            n = len(values)
            mean = sum(values) / n
            variance = sum((x - mean) ** 2 for x in values) / n if n > 1 else 0
            std = variance ** 0.5

            result[name] = {
                "count": n,
                "mean": mean,
                "std": std,
                "min": min(values),
                "max": max(values),
            }

        return result

    def _calculate_confidence(
        self,
        values_a: List[float],
        values_b: List[float],
    ) -> float:
        if len(values_a) < 2 or len(values_b) < 2:
            return 0.0

        import math

        n_a, n_b = len(values_a), len(values_b)
        mean_a = sum(values_a) / n_a
        mean_b = sum(values_b) / n_b
        var_a = sum((x - mean_a) ** 2 for x in values_a) / (n_a - 1)
        var_b = sum((x - mean_b) ** 2 for x in values_b) / (n_b - 1)

        se = math.sqrt(var_a / n_a + var_b / n_b)
        if se == 0:
            return 0.99

        z_score = abs(mean_a - mean_b) / se

        confidence = min(0.99, max(0.0, 1 - math.exp(-z_score)))

        return confidence

    def delete_experiment(
        self,
        experiment_id: int,
    ) -> bool:
        db = next(get_sync_db())
        try:
            experiment = db.query(ABTestExperiment).filter(
                ABTestExperiment.id == experiment_id
            ).first()

            if not experiment:
                return False

            if experiment.status == ABTestStatusEnum.RUNNING:
                raise ValueError("Cannot delete running experiment")

            db.query(ABTestResult).filter(
                ABTestResult.experiment_id == experiment_id
            ).delete()

            db.delete(experiment)
            db.commit()

            logger.info(f"Deleted A/B test experiment {experiment_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to delete A/B test experiment {experiment_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()
