import uuid
from datetime import datetime
from typing import List, Dict, Optional, Tuple
from pathlib import Path
import joblib
import numpy as np

from sklearn.model_selection import train_test_split

from app.core.config import settings
from app.core.database import SessionLocal
from app.core.models import TrainingJob as DBTrainingJob
from app.modules.preprocessing import text_preprocessor
from app.modules.model_manager import model_manager
from app.modules.trainer import Trainer, TrainerConfig, TrainingResult
from app.modules.evaluator import evaluator, EvaluationConfig, EvaluationResult, ModelComparisonResult


class TrainingWorkflowResult:
    def __init__(
        self,
        success: bool,
        job_id: str = None,
        model_id: str = None,
        training_result: TrainingResult = None,
        evaluation_result: EvaluationResult = None,
        comparison_result: ModelComparisonResult = None,
        message: str = "",
        metrics: Dict = None
    ):
        self.success = success
        self.job_id = job_id
        self.model_id = model_id
        self.training_result = training_result
        self.evaluation_result = evaluation_result
        self.comparison_result = comparison_result
        self.message = message
        self.metrics = metrics or {}


class TrainingService:
    def __init__(self):
        self._training_jobs: Dict[str, Dict] = {}
        self._default_trainer_config = TrainerConfig(
            max_features=10000,
            ngram_range=(1, 2),
            max_iter=1000,
            class_weight='balanced',
            random_state=settings.TRAINING_RANDOM_STATE,
            test_size=settings.TRAINING_TEST_SIZE,
            stratify=True
        )
        self._default_eval_config = EvaluationConfig(
            accuracy_threshold=0.7,
            precision_threshold=0.6,
            recall_threshold=0.6,
            f1_threshold=0.6
        )

    def _get_db(self):
        return SessionLocal()

    def _generate_job_id(self) -> str:
        return f"job_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"

    def _generate_new_version(self) -> str:
        existing_models = model_manager.list_models()
        if not existing_models:
            return "v1.0.0"

        latest_version = existing_models[0]["version"]
        try:
            parts = latest_version.replace("v", "").split(".")
            major = int(parts[0])
            minor = int(parts[1]) if len(parts) > 1 else 0
            patch = int(parts[2]) + 1 if len(parts) > 2 else 1
            return f"v{major}.{minor}.{patch}"
        except:
            return f"v{len(existing_models) + 1}.0.0"

    def _validate_training_data(
        self,
        training_data: List[Dict]
    ) -> Tuple[bool, str, List]:
        if not training_data:
            return False, "训练数据为空", []

        all_labels = set()
        validated_data = []

        for i, item in enumerate(training_data):
            text = item.get("text")
            labels = item.get("labels")

            if not text or not isinstance(text, str):
                return False, f"第{i+1}条数据缺少有效的文本", []

            if not labels or not isinstance(labels, list):
                return False, f"第{i+1}条数据缺少有效的标签列表", []

            for label in labels:
                if not isinstance(label, str):
                    return False, f"第{i+1}条数据的标签必须是字符串类型", []
                all_labels.add(label)

            validated_data.append({
                "text": text,
                "labels": labels
            })

        if len(all_labels) < 2:
            return False, f"标签数量不足，至少需要2种不同的标签", []

        return True, "数据验证通过", validated_data

    def _preprocess_data(
        self,
        training_data: List[Dict]
    ) -> Tuple[List, List, List[str]]:
        all_tokens = []
        all_labels_set = set()
        label_lists = []

        for item in training_data:
            text = item["text"]
            labels = item["labels"]

            preprocess_result = text_preprocessor.preprocess(text)
            if preprocess_result["status"] != "success":
                tokens = []
            else:
                tokens = preprocess_result["filtered_tokens"]

            all_tokens.append(tokens)
            label_lists.append(labels)

            for label in labels:
                all_labels_set.add(label)

        unique_labels = sorted(list(all_labels_set))

        label_vectors = []
        for labels in label_lists:
            vector = [1 if label in labels else 0 for label in unique_labels]
            label_vectors.append(vector)

        return all_tokens, label_vectors, unique_labels

    def _split_data(
        self,
        X: List,
        y: np.ndarray,
        test_size: float,
        random_state: int
    ) -> Tuple[List, List, np.ndarray, np.ndarray]:
        stratify_param = y if y.shape[1] == 1 else None

        X_train, X_test, y_train, y_test = train_test_split(
            X, y,
            test_size=test_size,
            random_state=random_state,
            stratify=stratify_param
        )

        return X_train, X_test, y_train, y_test

    def _execute_training(
        self,
        X_train: List,
        y_train: np.ndarray,
        labels: List[str],
        test_size: float,
        random_state: int,
        trainer_config: Optional[TrainerConfig] = None
    ) -> TrainingResult:
        config = trainer_config or self._default_trainer_config

        trainer = Trainer(config)

        training_result = trainer.train(
            X=X_train,
            y=y_train,
            labels=labels
        )

        return training_result

    def _execute_evaluation(
        self,
        model: object,
        vectorizer: object,
        X_test: List,
        y_test: np.ndarray,
        labels: List[str],
        eval_config: Optional[EvaluationConfig] = None
    ) -> EvaluationResult:
        config = eval_config or self._default_eval_config

        eval_result = evaluator.evaluate(
            model=model,
            vectorizer=vectorizer,
            X_test=X_test,
            y_test=y_test,
            labels=labels
        )

        return eval_result

    def _compare_with_active_model(
        self,
        new_metrics: Dict
    ) -> Optional[ModelComparisonResult]:
        active_model = model_manager.get_active_model()
        if not active_model:
            return None

        baseline_metrics = {
            "accuracy": active_model.get("accuracy", 0.0),
            "precision": active_model.get("precision", 0.0),
            "recall": active_model.get("recall", 0.0),
            "f1_score": active_model.get("f1_score", 0.0)
        }

        comparison_result = evaluator.compare_models(
            new_model_metrics=new_metrics,
            baseline_model_metrics=baseline_metrics,
            min_improvement_threshold=0.02
        )

        return comparison_result

    def _save_trained_model(
        self,
        trainer: Trainer,
        version: str
    ) -> Tuple[str, str]:
        model_filename = f"classifier_{version}.pkl"
        vectorizer_filename = f"vectorizer_{version}.pkl"

        model_path = settings.MODELS_DIR / model_filename
        vectorizer_path = settings.MODELS_DIR / vectorizer_filename

        success, message = trainer.save_model(model_path, vectorizer_path)

        if not success:
            raise RuntimeError(message)

        return str(model_path), str(vectorizer_path)

    def start_training(
        self,
        training_data: List[Dict],
        model_type: str = "multilabel_classifier",
        test_size: float = None,
        random_state: int = None,
        auto_activate: bool = False,
        auto_validate: bool = True,
        validation_data: List[Dict] = None,
        validation_threshold: float = 0.7,
        description: str = None
    ) -> TrainingWorkflowResult:
        job_id = self._generate_job_id()

        db = self._get_db()
        try:
            training_job = DBTrainingJob(
                job_id=job_id,
                model_type=model_type,
                status="running",
                training_samples=len(training_data),
                test_size=test_size or settings.TRAINING_TEST_SIZE,
                random_state=random_state or settings.TRAINING_RANDOM_STATE
            )
            db.add(training_job)
            db.commit()
            db.refresh(training_job)

        except Exception as e:
            db.close()
            return TrainingWorkflowResult(
                success=False,
                job_id=job_id,
                message=f"创建训练任务失败: {str(e)}"
            )
        finally:
            db.close()

        return self._execute_training_workflow(
            job_id=job_id,
            training_data=training_data,
            model_type=model_type,
            test_size=test_size or settings.TRAINING_TEST_SIZE,
            random_state=random_state or settings.TRAINING_RANDOM_STATE,
            auto_activate=auto_activate,
            auto_validate=auto_validate,
            validation_data=validation_data,
            validation_threshold=validation_threshold,
            description=description
        )

    def _execute_training_workflow(
        self,
        job_id: str,
        training_data: List[Dict],
        model_type: str,
        test_size: float,
        random_state: int,
        auto_activate: bool,
        auto_validate: bool,
        validation_data: List[Dict] = None,
        validation_threshold: float = 0.7,
        description: str = None
    ) -> TrainingWorkflowResult:
        db = self._get_db()
        try:
            training_job = db.query(DBTrainingJob).filter(DBTrainingJob.job_id == job_id).first()
            training_job.started_at = datetime.now()
            training_job.status = "running"
            db.commit()

        except Exception as e:
            db.close()
            return TrainingWorkflowResult(
                success=False,
                job_id=job_id,
                message=f"更新训练任务状态失败: {str(e)}"
            )

        try:
            is_valid, message, validated_data = self._validate_training_data(training_data)
            if not is_valid:
                training_job.status = "failed"
                training_job.error_message = message
                db.commit()
                db.close()
                return TrainingWorkflowResult(
                    success=False,
                    job_id=job_id,
                    message=message
                )

            all_tokens, label_vectors, unique_labels = self._preprocess_data(validated_data)

            y = np.array(label_vectors)

            X_train, X_test, y_train, y_test = self._split_data(
                X=all_tokens,
                y=y,
                test_size=test_size,
                random_state=random_state
            )

            trainer_config = TrainerConfig(
                max_features=10000,
                ngram_range=(1, 2),
                max_iter=1000,
                class_weight='balanced',
                random_state=random_state,
                test_size=test_size,
                stratify=True
            )

            training_result = self._execute_training(
                X_train=X_train,
                y_train=y_train,
                labels=unique_labels,
                test_size=test_size,
                random_state=random_state,
                trainer_config=trainer_config
            )

            if not training_result.success:
                training_job.status = "failed"
                training_job.error_message = training_result.message
                db.commit()
                db.close()
                return TrainingWorkflowResult(
                    success=False,
                    job_id=job_id,
                    message=training_result.message
                )

            eval_config = EvaluationConfig(
                accuracy_threshold=validation_threshold,
                precision_threshold=0.6,
                recall_threshold=0.6,
                f1_threshold=0.6
            )

            evaluation_result = self._execute_evaluation(
                model=training_result.model,
                vectorizer=training_result.vectorizer,
                X_test=X_test,
                y_test=y_test,
                labels=unique_labels,
                eval_config=eval_config
            )

            metrics = {
                "accuracy": evaluation_result.metrics.get('accuracy', 0.0),
                "precision": evaluation_result.metrics.get('precision', 0.0),
                "recall": evaluation_result.metrics.get('recall', 0.0),
                "f1_score": evaluation_result.metrics.get('f1_score', 0.0),
                "train_samples": training_result.metrics.get('train_samples', 0),
                "test_samples": training_result.metrics.get('test_samples', 0),
                "features": training_result.metrics.get('features', 0)
            }

            comparison_result = None
            if auto_validate:
                comparison_result = self._compare_with_active_model(metrics)

            new_version = self._generate_new_version()

            trainer = Trainer(trainer_config)
            trainer._model = training_result.model
            trainer._vectorizer = training_result.vectorizer
            trainer._labels = unique_labels

            model_path, vectorizer_path = self._save_trained_model(
                trainer=trainer,
                version=new_version
            )

            if auto_activate and evaluation_result.threshold_passed:
                register_result = model_manager._register_and_activate_model(
                    version=new_version,
                    model_path=model_path,
                    vectorizer_path=vectorizer_path,
                    labels=unique_labels,
                    model_type=model_type,
                    training_samples=len(validated_data),
                    accuracy=metrics["accuracy"],
                    precision=metrics["precision"],
                    recall=metrics["recall"],
                    f1_score=metrics["f1_score"],
                    description=description
                )
            else:
                register_result = model_manager.register_candidate_model(
                    version=new_version,
                    model_path=model_path,
                    vectorizer_path=vectorizer_path,
                    labels=unique_labels,
                    model_type=model_type,
                    training_samples=len(validated_data),
                    accuracy=metrics["accuracy"],
                    precision=metrics["precision"],
                    recall=metrics["recall"],
                    f1_score=metrics["f1_score"],
                    description=description,
                    validation_threshold=validation_threshold
                )

            if not register_result["success"]:
                training_job.status = "failed"
                training_job.error_message = register_result["message"]
                db.commit()
                db.close()
                return TrainingWorkflowResult(
                    success=False,
                    job_id=job_id,
                    message=register_result["message"]
                )

            training_job.status = "completed"
            training_job.completed_at = datetime.now()
            training_job.result_model_id = register_result["model_info"]["model_id"]
            db.commit()

            result = TrainingWorkflowResult(
                success=True,
                job_id=job_id,
                model_id=register_result["model_info"]["model_id"],
                training_result=training_result,
                evaluation_result=evaluation_result,
                comparison_result=comparison_result,
                message="模型训练完成",
                metrics=metrics
            )

            db.close()
            return result

        except Exception as e:
            training_job.status = "failed"
            training_job.error_message = str(e)
            db.commit()
            db.close()
            return TrainingWorkflowResult(
                success=False,
                job_id=job_id,
                message=f"训练过程异常: {str(e)}"
            )

    def get_training_job(self, job_id: str) -> Optional[Dict]:
        db = self._get_db()
        try:
            job = db.query(DBTrainingJob).filter(DBTrainingJob.job_id == job_id).first()
            if job:
                return self._job_to_dict(job)
            return None
        finally:
            db.close()

    def list_training_jobs(self, limit: int = 100) -> List[Dict]:
        db = self._get_db()
        try:
            jobs = db.query(DBTrainingJob).order_by(
                DBTrainingJob.created_at.desc()
            ).limit(limit).all()
            return [self._job_to_dict(job) for job in jobs]
        finally:
            db.close()

    def _job_to_dict(self, job: DBTrainingJob) -> Dict:
        return {
            "job_id": job.job_id,
            "model_type": job.model_type,
            "status": job.status,
            "training_samples": job.training_samples,
            "test_size": job.test_size,
            "random_state": job.random_state,
            "result_model_id": job.result_model_id,
            "error_message": job.error_message,
            "started_at": job.started_at.isoformat() if job.started_at else None,
            "completed_at": job.completed_at.isoformat() if job.completed_at else None,
            "created_at": job.created_at.isoformat() if job.created_at else None,
            "updated_at": job.updated_at.isoformat() if job.updated_at else None
        }


training_service = TrainingService()
