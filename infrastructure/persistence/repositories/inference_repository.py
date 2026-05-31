from typing import List, Optional
from sqlalchemy.orm import Session

from domain.models.inference import AIModel, InferenceTask, InferenceResult, InferenceStatus
from infrastructure.persistence.models.inference_model import AIModelModel, InferenceTaskModel, InferenceResultModel


class InferenceRepository:
    def __init__(self, db: Session):
        self.db = db

    def save_model(self, model: AIModel) -> AIModel:
        col_key_map = {c.name: c.key for c in AIModelModel.__table__.columns}
        db_obj = AIModelModel(**{col_key_map.get(k, k): v for k, v in model.model_dump().items()})
        self.db.add(db_obj)
        self.db.commit()
        return model

    def get_model(self, model_id: str) -> Optional[AIModel]:
        db_obj = self.db.query(AIModelModel).filter(AIModelModel.model_id == model_id).first()
        return AIModel(**{c.name: getattr(db_obj, c.key) for c in db_obj.__table__.columns}) if db_obj else None

    def get_all_models(self) -> List[AIModel]:
        db_objs = self.db.query(AIModelModel).all()
        return [AIModel(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]

    def save_task(self, task: InferenceTask) -> InferenceTask:
        col_key_map = {c.name: c.key for c in InferenceTaskModel.__table__.columns}
        db_obj = InferenceTaskModel(**{col_key_map.get(k, k): v for k, v in task.model_dump().items()})
        self.db.add(db_obj)
        self.db.commit()
        return task

    def update_task(self, task_id: str, update_data: dict) -> Optional[InferenceTask]:
        db_obj = self.db.query(InferenceTaskModel).filter(InferenceTaskModel.task_id == task_id).first()
        if db_obj:
            col_key_map = {c.name: c.key for c in InferenceTaskModel.__table__.columns}
            for key, value in update_data.items():
                setattr(db_obj, col_key_map.get(key, key), value)
            self.db.commit()
            return InferenceTask(**{c.name: getattr(db_obj, c.key) for c in db_obj.__table__.columns})
        return None

    def get_task(self, task_id: str) -> Optional[InferenceTask]:
        db_obj = self.db.query(InferenceTaskModel).filter(InferenceTaskModel.task_id == task_id).first()
        return InferenceTask(**{c.name: getattr(db_obj, c.key) for c in db_obj.__table__.columns}) if db_obj else None

    def get_pending_tasks(self, limit: int = 100) -> List[InferenceTask]:
        db_objs = self.db.query(InferenceTaskModel).filter(
            InferenceTaskModel.status.in_([InferenceStatus.PENDING.value, InferenceStatus.SCHEDULED.value])
        ).order_by(InferenceTaskModel.priority.desc(), InferenceTaskModel.created_at.asc()).limit(limit).all()
        return [InferenceTask(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]

    def save_result(self, result: InferenceResult) -> InferenceResult:
        col_key_map = {c.name: c.key for c in InferenceResultModel.__table__.columns}
        db_obj = InferenceResultModel(**{col_key_map.get(k, k): v for k, v in result.model_dump().items()})
        self.db.add(db_obj)
        self.db.commit()
        return result

    def get_results_by_task(self, task_id: str) -> List[InferenceResult]:
        db_objs = self.db.query(InferenceResultModel).filter(InferenceResultModel.task_id == task_id).all()
        return [InferenceResult(**{c.name: getattr(obj, c.key) for c in obj.__table__.columns}) for obj in db_objs]
