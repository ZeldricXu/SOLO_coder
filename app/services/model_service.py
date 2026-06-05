import json
from typing import List, Optional, Dict, Any, Tuple
from datetime import datetime
from sqlalchemy import and_, func

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.model import (
    ModelVersionCreate,
    ModelVersionUpdate,
    ModelStatusEnum,
    ModelTypeEnum,
)
from app.models.model import ModelVersion, ABTestExperiment, ABTestResult
from app.core.database import get_sync_db

logger = get_logger(__name__)
settings = get_settings()


class ModelService:
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

    def register_model(
        self,
        request: ModelVersionCreate,
    ) -> ModelVersion:
        db = next(get_sync_db())
        try:
            existing = db.query(ModelVersion).filter(
                and_(
                    ModelVersion.model_name == request.model_name,
                    ModelVersion.version == request.version,
                )
            ).first()

            if existing:
                raise ValueError(
                    f"Model {request.model_name} version {request.version} already exists"
                )

            model = ModelVersion(**request.model_dump())
            db.add(model)
            db.commit()
            db.refresh(model)

            logger.info(
                f"Registered model {model.model_name} version {model.version} "
                f"(type: {model.model_type.value})"
            )

            return model

        except Exception as e:
            logger.error(f"Failed to register model: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_model(
        self,
        model_id: int,
    ) -> Optional[ModelVersion]:
        db = next(get_sync_db())
        try:
            return db.query(ModelVersion).filter(ModelVersion.id == model_id).first()
        finally:
            db.close()

    def get_model_by_name_version(
        self,
        model_name: str,
        version: str,
    ) -> Optional[ModelVersion]:
        db = next(get_sync_db())
        try:
            return db.query(ModelVersion).filter(
                and_(
                    ModelVersion.model_name == model_name,
                    ModelVersion.version == version,
                )
            ).first()
        finally:
            db.close()

    def list_models(
        self,
        model_name: Optional[str] = None,
        model_type: Optional[ModelTypeEnum] = None,
        status: Optional[ModelStatusEnum] = None,
        page: int = 1,
        page_size: int = 20,
    ) -> Tuple[List[ModelVersion], int]:
        db = next(get_sync_db())
        try:
            query = db.query(ModelVersion)

            if model_name:
                query = query.filter(ModelVersion.model_name == model_name)
            if model_type:
                query = query.filter(ModelVersion.model_type == model_type)
            if status:
                query = query.filter(ModelVersion.status == status)

            query = query.order_by(
                ModelVersion.model_name,
                ModelVersion.created_at.desc(),
            )

            total = query.count()
            models = query.offset((page - 1) * page_size).limit(page_size).all()

            return models, total

        finally:
            db.close()

    def update_model(
        self,
        model_id: int,
        update: ModelVersionUpdate,
    ) -> Optional[ModelVersion]:
        db = next(get_sync_db())
        try:
            model = db.query(ModelVersion).filter(ModelVersion.id == model_id).first()
            if not model:
                return None

            for field, value in update.model_dump(exclude_unset=True).items():
                setattr(model, field, value)

            db.commit()
            db.refresh(model)

            logger.info(f"Updated model {model_id}")
            return model

        except Exception as e:
            logger.error(f"Failed to update model {model_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def set_model_status(
        self,
        model_id: int,
        status: ModelStatusEnum,
    ) -> Optional[ModelVersion]:
        db = next(get_sync_db())
        try:
            model = db.query(ModelVersion).filter(ModelVersion.id == model_id).first()
            if not model:
                return None

            if status == ModelStatusEnum.PRODUCTION:
                db.query(ModelVersion).filter(
                    and_(
                        ModelVersion.model_name == model.model_name,
                        ModelVersion.id != model_id,
                        ModelVersion.status == ModelStatusEnum.PRODUCTION,
                    )
                ).update({"status": ModelStatusEnum.ARCHIVED})

            model.status = status
            db.commit()
            db.refresh(model)

            logger.info(f"Set model {model_id} status to {status.value}")
            return model

        except Exception as e:
            logger.error(f"Failed to set model {model_id} status: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_production_model(
        self,
        model_name: str,
    ) -> Optional[ModelVersion]:
        db = next(get_sync_db())
        try:
            return db.query(ModelVersion).filter(
                and_(
                    ModelVersion.model_name == model_name,
                    ModelVersion.status == ModelStatusEnum.PRODUCTION,
                )
            ).first()
        finally:
            db.close()

    def get_available_versions(
        self,
        model_name: str,
        include_archived: bool = False,
    ) -> List[ModelVersion]:
        db = next(get_sync_db())
        try:
            query = db.query(ModelVersion).filter(
                ModelVersion.model_name == model_name
            )

            if not include_archived:
                query = query.filter(
                    ModelVersion.status != ModelStatusEnum.ARCHIVED
                )

            return query.order_by(ModelVersion.created_at.desc()).all()

        finally:
            db.close()

    def delete_model(
        self,
        model_id: int,
    ) -> bool:
        db = next(get_sync_db())
        try:
            model = db.query(ModelVersion).filter(ModelVersion.id == model_id).first()
            if not model:
                return False

            if model.status == ModelStatusEnum.PRODUCTION:
                raise ValueError("Cannot delete production model")

            db.delete(model)
            db.commit()

            logger.info(f"Deleted model {model_id}")
            return True

        except Exception as e:
            logger.error(f"Failed to delete model {model_id}: {e}", exc_info=True)
            db.rollback()
            raise
        finally:
            db.close()

    def get_model_statistics(
        self,
        model_id: int,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
    ) -> Optional[Dict[str, Any]]:
        db = next(get_sync_db())
        try:
            model = db.query(ModelVersion).filter(ModelVersion.id == model_id).first()
            if not model:
                return None

            from app.models.extraction import ExtractionResult

            query = db.query(ExtractionResult).filter(
                ExtractionResult.model_version == f"{model.model_name}=={model.version}"
            )

            if start_date:
                query = query.filter(ExtractionResult.created_at >= start_date)
            if end_date:
                query = query.filter(ExtractionResult.created_at <= end_date)

            total_usages = query.count()

            from app.schemas.extraction import ExtractionStatusEnum

            completed = query.filter(
                ExtractionResult.status == ExtractionStatusEnum.COMPLETED
            ).count()

            failed = query.filter(
                ExtractionResult.status == ExtractionStatusEnum.FAILED
            ).count()

            avg_confidence = None
            avg_time = None

            if completed > 0:
                avg_confidence = db.query(
                    func.avg(ExtractionResult.confidence_score)
                ).filter(
                    ExtractionResult.confidence_score.isnot(None),
                    ExtractionResult.model_version == f"{model.model_name}=={model.version}",
                ).scalar()

                avg_time = db.query(
                    func.avg(ExtractionResult.extraction_time)
                ).filter(
                    ExtractionResult.extraction_time.isnot(None),
                    ExtractionResult.model_version == f"{model.model_name}=={model.version}",
                ).scalar()

            ab_test_results = db.query(ABTestResult).filter(
                or_(
                    ABTestResult.variant_a_model_id == model_id,
                    ABTestResult.variant_b_model_id == model_id,
                )
            ).count()

            return {
                "model_id": model.id,
                "model_name": model.model_name,
                "version": model.version,
                "status": model.status.value,
                "total_usages": total_usages,
                "completed": completed,
                "failed": failed,
                "success_rate": completed / total_usages if total_usages > 0 else 0,
                "average_confidence": float(avg_confidence) if avg_confidence else None,
                "average_extraction_time": float(avg_time) if avg_time else None,
                "ab_test_participations": ab_test_results,
                "performance_metrics": model.performance_metrics,
            }

        finally:
            db.close()

    def compare_models(
        self,
        model_ids: List[int],
        metric: str = "accuracy",
    ) -> Dict[str, Any]:
        db = next(get_sync_db())
        try:
            models = db.query(ModelVersion).filter(
                ModelVersion.id.in_(model_ids)
            ).all()

            if len(models) < 2:
                raise ValueError("Need at least 2 models to compare")

            comparisons = []
            for model in models:
                stats = model.performance_metrics or {}
                metric_value = stats.get(metric, 0.0)

                comparisons.append({
                    "model_id": model.id,
                    "model_name": model.model_name,
                    "version": model.version,
                    f"{metric}_value": metric_value,
                    "all_metrics": stats,
                })

            comparisons.sort(key=lambda x: x[f"{metric}_value"], reverse=True)

            best_model = comparisons[0] if comparisons else None

            return {
                "metric": metric,
                "comparisons": comparisons,
                "best_model": best_model,
            }

        finally:
            db.close()
