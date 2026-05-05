import uuid
from datetime import datetime
from typing import List, Dict, Optional, Tuple
from pathlib import Path
import joblib
import numpy as np
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.database import SessionLocal
from app.core.models import (
    ModelVersion as DBModelVersion,
    ModelValidationRecord as DBValidationRecord,
    ModelStatus,
    ValidationStatus
)
from app.modules.classifier import TextClassifier
from app.modules.evaluator import evaluator, EvaluationConfig
from app.modules.preprocessing import text_preprocessor


class ModelValidationResult:
    def __init__(
        self,
        success: bool,
        validation_id: str = None,
        metrics: Dict = None,
        threshold_passed: bool = False,
        passed_checks: List[str] = None,
        failed_checks: List[str] = None,
        message: str = ""
    ):
        self.success = success
        self.validation_id = validation_id
        self.metrics = metrics or {}
        self.threshold_passed = threshold_passed
        self.passed_checks = passed_checks or []
        self.failed_checks = failed_checks or []
        self.message = message


class ModelManager:
    def __init__(self):
        self._current_model: Optional[TextClassifier] = None
        self._current_model_info: Optional[Dict] = None
        self._model_cache: Dict[str, TextClassifier] = {}
        self._validation_config = EvaluationConfig(
            accuracy_threshold=0.7,
            precision_threshold=0.6,
            recall_threshold=0.6,
            f1_threshold=0.6
        )

    def _get_db(self) -> Session:
        return SessionLocal()

    def _generate_model_id(self, version: str, model_type: str = "multilabel_classifier") -> str:
        return f"{model_type}_{version}"

    def _generate_validation_id(self) -> str:
        return f"val_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"

    def list_models(self) -> List[Dict]:
        db = self._get_db()
        try:
            models = db.query(DBModelVersion).order_by(DBModelVersion.created_at.desc()).all()
            return [self._db_model_to_dict(model) for model in models]
        finally:
            db.close()

    def get_model_info(self, model_id: str = None) -> Optional[Dict]:
        if model_id is None:
            return self._current_model_info

        db = self._get_db()
        try:
            model = db.query(DBModelVersion).filter(DBModelVersion.model_id == model_id).first()
            if model:
                return self._db_model_to_dict(model)
            return None
        finally:
            db.close()

    def get_active_model(self) -> Optional[Dict]:
        db = self._get_db()
        try:
            model = db.query(DBModelVersion).filter(
                DBModelVersion.is_active == True,
                DBModelVersion.status == ModelStatus.ACTIVE
            ).first()
            if model:
                return self._db_model_to_dict(model)
            return None
        finally:
            db.close()

    def get_candidate_models(self) -> List[Dict]:
        db = self._get_db()
        try:
            models = db.query(DBModelVersion).filter(
                DBModelVersion.status.in_([
                    ModelStatus.CANDIDATE,
                    ModelStatus.VALIDATING,
                    ModelStatus.VALIDATED
                ])
            ).order_by(DBModelVersion.created_at.desc()).all()
            return [self._db_model_to_dict(model) for model in models]
        finally:
            db.close()

    def load_model(self, model_id: str) -> bool:
        db = self._get_db()
        try:
            model = db.query(DBModelVersion).filter(DBModelVersion.model_id == model_id).first()
            if not model:
                print(f"模型不存在: {model_id}")
                return False

            model_path = Path(model.model_path)
            vectorizer_path = Path(model.vectorizer_path)

            if not model_path.exists():
                print(f"模型文件不存在: {model_path}")
                return False
            if not vectorizer_path.exists():
                print(f"向量器文件不存在: {vectorizer_path}")
                return False

            if model_id in self._model_cache:
                self._current_model = self._model_cache[model_id]
            else:
                classifier = TextClassifier()
                success = classifier.load_model(
                    model_path=model_path,
                    vectorizer_path=vectorizer_path,
                    labels=model.labels
                )
                if not success:
                    print(f"加载模型失败: {model_id}")
                    return False
                self._model_cache[model_id] = classifier
                self._current_model = classifier

            self._current_model_info = self._db_model_to_dict(model)
            return True

        except Exception as e:
            print(f"加载模型异常: {e}")
            return False
        finally:
            db.close()

    def register_candidate_model(
        self,
        version: str,
        model_path: str,
        vectorizer_path: str,
        labels: List[str],
        model_type: str = "multilabel_classifier",
        training_samples: int = 0,
        accuracy: float = 0.0,
        precision: float = 0.0,
        recall: float = 0.0,
        f1_score: float = 0.0,
        description: str = None,
        validation_threshold: float = 0.7
    ) -> Dict:
        db = self._get_db()
        try:
            model_id = self._generate_model_id(version, model_type)

            existing = db.query(DBModelVersion).filter(DBModelVersion.model_id == model_id).first()
            if existing:
                return {
                    "success": False,
                    "message": f"模型版本已存在: {version}"
                }

            new_model = DBModelVersion(
                model_id=model_id,
                model_type=model_type,
                version=version,
                labels=labels,
                training_samples=training_samples,
                accuracy=accuracy,
                precision=precision,
                recall=recall,
                f1_score=f1_score,
                model_path=model_path,
                vectorizer_path=vectorizer_path,
                is_active=False,
                status=ModelStatus.CANDIDATE,
                validation_status=ValidationStatus.PENDING,
                validation_threshold=validation_threshold,
                description=description
            )
            db.add(new_model)
            db.commit()
            db.refresh(new_model)

            return {
                "success": True,
                "message": f"候选模型注册成功: {model_id}",
                "model_info": self._db_model_to_dict(new_model)
            }

        except Exception as e:
            db.rollback()
            return {
                "success": False,
                "message": f"模型注册异常: {str(e)}"
            }
        finally:
            db.close()

    def validate_model(
        self,
        model_id: str,
        validation_data: List[Dict] = None,
        validation_threshold: float = None
    ) -> ModelValidationResult:
        db = self._get_db()
        try:
            model = db.query(DBModelVersion).filter(DBModelVersion.model_id == model_id).first()
            if not model:
                return ModelValidationResult(
                    success=False,
                    message=f"模型不存在: {model_id}"
                )

            if model.status not in [ModelStatus.CANDIDATE, ModelStatus.VALIDATION_FAILED]:
                return ModelValidationResult(
                    success=False,
                    message=f"模型状态不允许验证: {model.status.value}"
                )

            model.status = ModelStatus.VALIDATING
            model.validation_status = ValidationStatus.RUNNING
            db.commit()

            validation_id = self._generate_validation_id()

            validation_record = DBValidationRecord(
                validation_id=validation_id,
                model_id=model_id,
                status=ValidationStatus.RUNNING,
                started_at=datetime.now()
            )
            db.add(validation_record)
            db.commit()

            try:
                if not self._load_model_to_cache(model_id):
                    model.status = ModelStatus.VALIDATION_FAILED
                    model.validation_status = ValidationStatus.FAILED
                    validation_record.status = ValidationStatus.FAILED
                    validation_record.error_message = "模型加载失败"
                    validation_record.completed_at = datetime.now()
                    db.commit()

                    return ModelValidationResult(
                        success=False,
                        validation_id=validation_id,
                        message="模型加载失败"
                    )

                classifier = self._model_cache.get(model_id)
                if not classifier:
                    model.status = ModelStatus.VALIDATION_FAILED
                    model.validation_status = ValidationStatus.FAILED
                    validation_record.status = ValidationStatus.FAILED
                    validation_record.error_message = "模型未加载到缓存"
                    validation_record.completed_at = datetime.now()
                    db.commit()

                    return ModelValidationResult(
                        success=False,
                        validation_id=validation_id,
                        message="模型未加载到缓存"
                    )

                if validation_data and len(validation_data) > 0:
                    X_test, y_test, labels = self._prepare_validation_data(
                        validation_data,
                        model.labels
                    )
                else:
                    X_test, y_test, labels = self._generate_dummy_validation_data(model.labels)

                actual_threshold = validation_threshold or model.validation_threshold or self._validation_config.accuracy_threshold

                eval_config = EvaluationConfig(
                    accuracy_threshold=actual_threshold,
                    precision_threshold=self._validation_config.precision_threshold,
                    recall_threshold=self._validation_config.recall_threshold,
                    f1_threshold=self._validation_config.f1_threshold
                )

                eval_result = evaluator.evaluate(
                    model=classifier.model,
                    vectorizer=classifier.vectorizer,
                    X_test=X_test,
                    y_test=y_test,
                    labels=labels
                )

                validation_record.validation_samples = len(X_test)
                validation_record.accuracy = eval_result.metrics.get('accuracy', 0.0)
                validation_record.precision = eval_result.metrics.get('precision', 0.0)
                validation_record.recall = eval_result.metrics.get('recall', 0.0)
                validation_record.f1_score = eval_result.metrics.get('f1_score', 0.0)
                validation_record.threshold_passed = eval_result.threshold_passed
                validation_record.details = {
                    'passed_checks': eval_result.passed_checks,
                    'failed_checks': eval_result.failed_checks,
                    'metrics': eval_result.metrics
                }

                if eval_result.threshold_passed:
                    model.status = ModelStatus.VALIDATED
                    model.validation_status = ValidationStatus.PASSED
                    model.validation_score = eval_result.metrics.get('accuracy', 0.0)
                    model.validated_at = datetime.now()
                    validation_record.status = ValidationStatus.PASSED
                else:
                    model.status = ModelStatus.VALIDATION_FAILED
                    model.validation_status = ValidationStatus.FAILED
                    model.validation_score = eval_result.metrics.get('accuracy', 0.0)
                    validation_record.status = ValidationStatus.FAILED

                validation_record.completed_at = datetime.now()
                db.commit()

                return ModelValidationResult(
                    success=True,
                    validation_id=validation_id,
                    metrics=eval_result.metrics,
                    threshold_passed=eval_result.threshold_passed,
                    passed_checks=eval_result.passed_checks,
                    failed_checks=eval_result.failed_checks,
                    message=eval_result.message
                )

            except Exception as e:
                model.status = ModelStatus.VALIDATION_FAILED
                model.validation_status = ValidationStatus.FAILED
                validation_record.status = ValidationStatus.FAILED
                validation_record.error_message = str(e)
                validation_record.completed_at = datetime.now()
                db.commit()

                return ModelValidationResult(
                    success=False,
                    validation_id=validation_id,
                    message=f"验证过程异常: {str(e)}"
                )

        except Exception as e:
            return ModelValidationResult(
                success=False,
                message=f"验证异常: {str(e)}"
            )
        finally:
            db.close()

    def switch_to_validated_model(
        self,
        model_id: str,
        auto_rollback_on_failure: bool = True
    ) -> Dict:
        db = self._get_db()
        try:
            target_model = db.query(DBModelVersion).filter(DBModelVersion.model_id == model_id).first()
            if not target_model:
                return {
                    "success": False,
                    "message": f"模型不存在: {model_id}"
                }

            if target_model.status != ModelStatus.VALIDATED:
                return {
                    "success": False,
                    "message": f"模型未通过验证，当前状态: {target_model.status.value}"
                }

            current_active = db.query(DBModelVersion).filter(
                DBModelVersion.is_active == True,
                DBModelVersion.status == ModelStatus.ACTIVE
            ).first()

            previous_model_id = None
            if current_active:
                previous_model_id = current_active.model_id
                current_active.is_active = False
                current_active.status = ModelStatus.INACTIVE

            target_model.is_active = True
            target_model.status = ModelStatus.ACTIVE
            target_model.previous_model_id = previous_model_id
            target_model.activated_at = datetime.now()

            db.commit()

            load_success = self.load_model(model_id)
            if not load_success:
                db.rollback()

                if current_active and auto_rollback_on_failure:
                    current_active.is_active = True
                    current_active.status = ModelStatus.ACTIVE
                    target_model.is_active = False
                    target_model.status = ModelStatus.VALIDATED
                    target_model.previous_model_id = None
                    target_model.activated_at = None
                    db.commit()

                    if self._current_model is None and previous_model_id:
                        self.load_model(previous_model_id)

                return {
                    "success": False,
                    "message": "模型加载失败，已回滚" if auto_rollback_on_failure else "模型加载失败"
                }

            return {
                "success": True,
                "message": f"模型切换成功: {model_id}",
                "model_info": self._db_model_to_dict(target_model),
                "previous_model_id": previous_model_id
            }

        except Exception as e:
            db.rollback()
            return {
                "success": False,
                "message": f"模型切换异常: {str(e)}"
            }
        finally:
            db.close()

    def rollback_to_previous_model(self) -> Dict:
        db = self._get_db()
        try:
            current_active = db.query(DBModelVersion).filter(
                DBModelVersion.is_active == True,
                DBModelVersion.status == ModelStatus.ACTIVE
            ).first()

            if not current_active:
                return {
                    "success": False,
                    "message": "没有活跃的模型可回退"
                }

            if not current_active.previous_model_id:
                return {
                    "success": False,
                    "message": "没有之前的模型可回退"
                }

            previous_model = db.query(DBModelVersion).filter(
                DBModelVersion.model_id == current_active.previous_model_id
            ).first()

            if not previous_model:
                return {
                    "success": False,
                    "message": f"之前的模型不存在: {current_active.previous_model_id}"
                }

            current_active.is_active = False
            current_active.status = ModelStatus.ROLLBACKED

            previous_model.is_active = True
            previous_model.status = ModelStatus.ACTIVE
            previous_model.activated_at = datetime.now()

            db.commit()

            load_success = self.load_model(previous_model.model_id)
            if not load_success:
                db.rollback()
                return {
                    "success": False,
                    "message": "回退模型加载失败"
                }

            return {
                "success": True,
                "message": f"已回退到模型: {previous_model.model_id}",
                "model_info": self._db_model_to_dict(previous_model),
                "rolled_back_from": current_active.model_id
            }

        except Exception as e:
            db.rollback()
            return {
                "success": False,
                "message": f"模型回退异常: {str(e)}"
            }
        finally:
            db.close()

    def switch_model(self, model_id: str) -> Dict:
        db = self._get_db()
        try:
            target_model = db.query(DBModelVersion).filter(DBModelVersion.model_id == model_id).first()
            if not target_model:
                return {
                    "success": False,
                    "message": f"模型不存在: {model_id}"
                }

            current_active = db.query(DBModelVersion).filter(DBModelVersion.is_active == True).first()

            previous_model_id = None
            if current_active:
                previous_model_id = current_active.model_id
                current_active.is_active = False
                current_active.status = ModelStatus.INACTIVE

            target_model.is_active = True
            target_model.status = ModelStatus.ACTIVE
            target_model.previous_model_id = previous_model_id
            target_model.activated_at = datetime.now()

            db.commit()

            load_success = self.load_model(model_id)
            if not load_success:
                db.rollback()
                if current_active:
                    current_active.is_active = True
                    current_active.status = ModelStatus.ACTIVE
                    db.commit()
                    if self._current_model is None and previous_model_id:
                        self.load_model(previous_model_id)
                return {
                    "success": False,
                    "message": "模型切换失败，已回滚"
                }

            return {
                "success": True,
                "message": f"模型切换成功: {model_id}",
                "model_info": self._db_model_to_dict(target_model)
            }

        except Exception as e:
            db.rollback()
            return {
                "success": False,
                "message": f"模型切换异常: {str(e)}"
            }
        finally:
            db.close()

    def register_model(
        self,
        version: str,
        model_path: str,
        vectorizer_path: str,
        labels: List[str],
        model_type: str = "multilabel_classifier",
        training_samples: int = 0,
        accuracy: float = 0.0,
        precision: float = 0.0,
        recall: float = 0.0,
        f1_score: float = 0.0,
        description: str = None,
        is_active: bool = False
    ) -> Dict:
        if is_active:
            return self._register_and_activate_model(
                version=version,
                model_path=model_path,
                vectorizer_path=vectorizer_path,
                labels=labels,
                model_type=model_type,
                training_samples=training_samples,
                accuracy=accuracy,
                precision=precision,
                recall=recall,
                f1_score=f1_score,
                description=description
            )
        else:
            return self.register_candidate_model(
                version=version,
                model_path=model_path,
                vectorizer_path=vectorizer_path,
                labels=labels,
                model_type=model_type,
                training_samples=training_samples,
                accuracy=accuracy,
                precision=precision,
                recall=recall,
                f1_score=f1_score,
                description=description
            )

    def _register_and_activate_model(
        self,
        version: str,
        model_path: str,
        vectorizer_path: str,
        labels: List[str],
        model_type: str,
        training_samples: int,
        accuracy: float,
        precision: float,
        recall: float,
        f1_score: float,
        description: str
    ) -> Dict:
        db = self._get_db()
        try:
            model_id = self._generate_model_id(version, model_type)

            existing = db.query(DBModelVersion).filter(DBModelVersion.model_id == model_id).first()
            if existing:
                return {
                    "success": False,
                    "message": f"模型版本已存在: {version}"
                }

            current_active = db.query(DBModelVersion).filter(
                DBModelVersion.is_active == True,
                DBModelVersion.status == ModelStatus.ACTIVE
            ).first()

            previous_model_id = None
            if current_active:
                previous_model_id = current_active.model_id
                current_active.is_active = False
                current_active.status = ModelStatus.INACTIVE

            new_model = DBModelVersion(
                model_id=model_id,
                model_type=model_type,
                version=version,
                labels=labels,
                training_samples=training_samples,
                accuracy=accuracy,
                precision=precision,
                recall=recall,
                f1_score=f1_score,
                model_path=model_path,
                vectorizer_path=vectorizer_path,
                is_active=True,
                status=ModelStatus.ACTIVE,
                validation_status=ValidationStatus.PASSED,
                previous_model_id=previous_model_id,
                activated_at=datetime.now(),
                validated_at=datetime.now(),
                description=description
            )
            db.add(new_model)
            db.commit()
            db.refresh(new_model)

            self.load_model(model_id)

            return {
                "success": True,
                "message": f"模型注册并激活成功: {model_id}",
                "model_info": self._db_model_to_dict(new_model)
            }

        except Exception as e:
            db.rollback()
            return {
                "success": False,
                "message": f"模型注册异常: {str(e)}"
            }
        finally:
            db.close()

    def delete_model(self, model_id: str) -> Dict:
        db = self._get_db()
        try:
            model = db.query(DBModelVersion).filter(DBModelVersion.model_id == model_id).first()
            if not model:
                return {
                    "success": False,
                    "message": f"模型不存在: {model_id}"
                }

            if model.is_active or model.status == ModelStatus.ACTIVE:
                return {
                    "success": False,
                    "message": "无法删除当前激活的模型"
                }

            model_path = Path(model.model_path)
            vectorizer_path = Path(model.vectorizer_path)

            if model_path.exists():
                model_path.unlink()
            if vectorizer_path.exists():
                vectorizer_path.unlink()

            db.query(DBValidationRecord).filter(DBValidationRecord.model_id == model_id).delete()

            db.delete(model)
            db.commit()

            if model_id in self._model_cache:
                del self._model_cache[model_id]

            return {
                "success": True,
                "message": f"模型删除成功: {model_id}"
            }

        except Exception as e:
            db.rollback()
            return {
                "success": False,
                "message": f"模型删除异常: {str(e)}"
            }
        finally:
            db.close()

    def get_current_classifier(self) -> Optional[TextClassifier]:
        if self._current_model is None:
            active_model = self.get_active_model()
            if active_model:
                self.load_model(active_model["model_id"])

        return self._current_model

    def get_validation_history(self, model_id: str = None, limit: int = 100) -> List[Dict]:
        db = self._get_db()
        try:
            query = db.query(DBValidationRecord)
            if model_id:
                query = query.filter(DBValidationRecord.model_id == model_id)

            records = query.order_by(
                DBValidationRecord.created_at.desc()
            ).limit(limit).all()

            return [self._validation_record_to_dict(r) for r in records]
        finally:
            db.close()

    def _load_model_to_cache(self, model_id: str) -> bool:
        db = self._get_db()
        try:
            model = db.query(DBModelVersion).filter(DBModelVersion.model_id == model_id).first()
            if not model:
                return False

            if model_id in self._model_cache:
                return True

            classifier = TextClassifier()
            success = classifier.load_model(
                model_path=Path(model.model_path),
                vectorizer_path=Path(model.vectorizer_path),
                labels=model.labels
            )

            if success:
                self._model_cache[model_id] = classifier
                return True
            return False

        finally:
            db.close()

    def _prepare_validation_data(
        self,
        validation_data: List[Dict],
        model_labels: List[str]
    ) -> Tuple[List[List[str]], np.ndarray, List[str]]:
        X_test = []
        y_test = []

        for item in validation_data:
            text = item.get("text", "")
            labels = item.get("labels", [])

            preprocess_result = text_preprocessor.preprocess(text)
            if preprocess_result["status"] == "success":
                tokens = preprocess_result["filtered_tokens"]
            else:
                tokens = []

            X_test.append(tokens)

            label_vector = [1 if label in labels else 0 for label in model_labels]
            y_test.append(label_vector)

        return X_test, np.array(y_test), model_labels

    def _generate_dummy_validation_data(
        self,
        labels: List[str]
    ) -> Tuple[List[List[str]], np.ndarray, List[str]]:
        dummy_texts = [
            ["产品", "质量", "很好"],
            ["价格", "便宜", "实惠"],
            ["客服", "态度", "好"],
            ["物流", "很快", "配送"],
            ["售后", "服务", "不错"]
        ]

        num_labels = len(labels)
        dummy_labels = np.eye(num_labels)[:len(dummy_texts)]

        return dummy_texts, dummy_labels, labels

    def _db_model_to_dict(self, model: DBModelVersion) -> Dict:
        return {
            "model_id": model.model_id,
            "model_type": model.model_type,
            "version": model.version,
            "labels": model.labels,
            "training_samples": model.training_samples,
            "accuracy": model.accuracy,
            "precision": model.precision,
            "recall": model.recall,
            "f1_score": model.f1_score,
            "model_path": model.model_path,
            "vectorizer_path": model.vectorizer_path,
            "is_active": model.is_active,
            "status": model.status.value if model.status else None,
            "validation_status": model.validation_status.value if model.validation_status else None,
            "validation_score": model.validation_score,
            "validation_threshold": model.validation_threshold,
            "previous_model_id": model.previous_model_id,
            "created_at": model.created_at.isoformat() if model.created_at else None,
            "updated_at": model.updated_at.isoformat() if model.updated_at else None,
            "validated_at": model.validated_at.isoformat() if model.validated_at else None,
            "activated_at": model.activated_at.isoformat() if model.activated_at else None,
            "description": model.description
        }

    def _validation_record_to_dict(self, record: DBValidationRecord) -> Dict:
        return {
            "validation_id": record.validation_id,
            "model_id": record.model_id,
            "status": record.status.value if record.status else None,
            "validation_samples": record.validation_samples,
            "accuracy": record.accuracy,
            "precision": record.precision,
            "recall": record.recall,
            "f1_score": record.f1_score,
            "threshold_passed": record.threshold_passed,
            "details": record.details,
            "error_message": record.error_message,
            "started_at": record.started_at.isoformat() if record.started_at else None,
            "completed_at": record.completed_at.isoformat() if record.completed_at else None,
            "created_at": record.created_at.isoformat() if record.created_at else None,
            "updated_at": record.updated_at.isoformat() if record.updated_at else None
        }

    def initialize_default_model(self):
        existing_models = self.list_models()
        if existing_models:
            active_model = self.get_active_model()
            if active_model:
                self.load_model(active_model["model_id"])
                return
            if existing_models:
                self.switch_model(existing_models[0]["model_id"])
                return

        from app.modules.classifier import TextClassifier
        classifier = TextClassifier()
        classifier.load_model()

        version = settings.DEFAULT_MODEL_VERSION
        model_id = self._generate_model_id(version)

        default_model_path = str(settings.MODELS_DIR / f"classifier_{version}.pkl")
        default_vectorizer_path = str(settings.MODELS_DIR / f"vectorizer_{version}.pkl")

        self._register_and_activate_model(
            version=version,
            model_path=default_model_path,
            vectorizer_path=default_vectorizer_path,
            labels=settings.DEFAULT_LABELS,
            model_type="multilabel_classifier",
            training_samples=0,
            accuracy=0.0,
            precision=0.0,
            recall=0.0,
            f1_score=0.0,
            description="默认初始化模型"
        )


model_manager = ModelManager()
