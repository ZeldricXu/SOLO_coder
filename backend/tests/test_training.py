import pytest
import numpy as np
from typing import List, Dict, Optional
from pathlib import Path
import tempfile
import shutil
import uuid
from datetime import datetime
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.core.config import settings
from app.core.database import Base
from app.core.models import (
    ModelVersion,
    ModelValidationRecord,
    TrainingJob,
    ModelStatus,
    ValidationStatus
)
from app.modules.preprocessing import TextPreprocessor, text_preprocessor
from app.modules.trainer import Trainer, TrainerConfig, TrainingResult
from app.modules.evaluator import Evaluator, EvaluationConfig, EvaluationResult, ModelComparisonResult
from app.modules.classifier import TextClassifier
from app.modules.model_manager import ModelManager, ModelValidationResult


TEST_DATABASE_URL = "sqlite:///:memory:"


class TestTrainerModule:
    def test_trainer_config_initialization(self):
        config = TrainerConfig(
            max_features=5000,
            ngram_range=(1, 1),
            max_iter=500,
            class_weight='balanced',
            random_state=123,
            test_size=0.3,
            stratify=True
        )
        assert config.max_features == 5000
        assert config.ngram_range == (1, 1)
        assert config.max_iter == 500
        assert config.class_weight == 'balanced'
        assert config.random_state == 123
        assert config.test_size == 0.3
        assert config.stratify is True

    def test_training_result_initialization(self):
        result = TrainingResult(
            success=True,
            model=None,
            vectorizer=None,
            metrics={'accuracy': 0.9},
            labels=['A', 'B'],
            message='Test'
        )
        assert result.success is True
        assert result.metrics['accuracy'] == 0.9
        assert result.labels == ['A', 'B']
        assert result.message == 'Test'

    def test_trainer_empty_data_validation(self):
        trainer = Trainer()
        result = trainer.train(
            X=[],
            y=np.array([]),
            labels=['A', 'B']
        )
        assert result.success is False
        assert "空" in result.message or "empty" in result.message.lower()

    def test_trainer_insufficient_labels(self):
        trainer = Trainer()
        X = [['token1', 'token2'], ['token3', 'token4']]
        y = np.array([[1], [0]])
        result = trainer.train(
            X=X,
            y=y,
            labels=['OnlyOneLabel']
        )
        assert result.success is False
        assert "标签" in result.message or "label" in result.message.lower()

    def test_trainer_split_data(self):
        trainer = Trainer(TrainerConfig(random_state=42, test_size=0.25))
        X = [['t1'], ['t2'], ['t3'], ['t4'], ['t5'], ['t6'], ['t7'], ['t8']]
        y = np.array([[1, 0], [0, 1], [1, 0], [0, 1], [1, 0], [0, 1], [1, 0], [0, 1]])

        X_train, X_test, y_train, y_test = trainer.split_data(X, y)

        assert len(X_train) == 6
        assert len(X_test) == 2
        assert y_train.shape == (6, 2)
        assert y_test.shape == (2, 2)

    def test_trainer_training_flow(self):
        trainer = Trainer(TrainerConfig(random_state=42))

        X = [
            ['产品', '质量', '很好'],
            ['客服', '态度', '好'],
            ['价格', '很', '贵'],
            ['物流', '很慢'],
            ['售后', '服务', '不错'],
            ['包装', '很', '精美'],
            ['配送', '及时'],
            ['功能', '实用'],
            ['外观', '漂亮'],
            ['体验', '很好'],
            ['产品', '质量', '差'],
            ['客服', '态度', '差'],
            ['价格', '很', '便宜'],
            ['物流', '很快'],
            ['售后', '服务', '糟糕'],
            ['包装', '很', '粗糙'],
            ['配送', '延迟'],
            ['功能', '鸡肋'],
            ['外观', '难看'],
            ['体验', '很差'],
        ]

        y = np.array([
            [1, 0, 0, 0, 0],
            [0, 0, 1, 0, 0],
            [0, 1, 0, 0, 0],
            [0, 0, 0, 1, 0],
            [0, 0, 0, 0, 1],
            [1, 0, 0, 0, 0],
            [0, 0, 0, 1, 0],
            [1, 0, 0, 0, 0],
            [1, 0, 0, 0, 0],
            [1, 0, 0, 0, 0],
            [1, 0, 0, 0, 0],
            [0, 0, 1, 0, 0],
            [0, 1, 0, 0, 0],
            [0, 0, 0, 1, 0],
            [0, 0, 0, 0, 1],
            [1, 0, 0, 0, 0],
            [0, 0, 0, 1, 0],
            [1, 0, 0, 0, 0],
            [1, 0, 0, 0, 0],
            [1, 0, 0, 0, 0],
        ])

        labels = ['产品质量', '价格', '客服服务', '物流配送', '售后']

        result = trainer.train(
            X=X,
            y=y,
            labels=labels
        )

        assert result.success is True
        assert result.model is not None
        assert result.vectorizer is not None
        assert 'train_samples' in result.metrics
        assert 'features' in result.metrics

    def test_trainer_save_model(self):
        trainer = Trainer(TrainerConfig(random_state=42))

        X = [
            ['产品', '质量', '很好'],
            ['客服', '态度', '好'],
            ['价格', '很', '贵'],
            ['物流', '很慢'],
            ['售后', '服务', '不错'],
        ] * 5

        y = np.array([
            [1, 0, 0, 0, 0],
            [0, 0, 1, 0, 0],
            [0, 1, 0, 0, 0],
            [0, 0, 0, 1, 0],
            [0, 0, 0, 0, 1],
        ] * 5)

        labels = ['产品质量', '价格', '客服服务', '物流配送', '售后']

        trainer.train(X=X, y=y, labels=labels)

        with tempfile.TemporaryDirectory() as tmpdir:
            model_path = Path(tmpdir) / 'test_model.pkl'
            vectorizer_path = Path(tmpdir) / 'test_vectorizer.pkl'

            success, message = trainer.save_model(model_path, vectorizer_path)

            assert success is True
            assert model_path.exists()
            assert vectorizer_path.exists()

    def test_trainer_training_history(self):
        trainer = Trainer(TrainerConfig(random_state=42))

        assert len(trainer.get_training_history()) == 0

        X = [
            ['产品', '质量', '很好'],
            ['客服', '态度', '好'],
            ['价格', '很', '贵'],
            ['物流', '很慢'],
            ['售后', '服务', '不错'],
        ] * 4

        y = np.array([
            [1, 0, 0, 0, 0],
            [0, 0, 1, 0, 0],
            [0, 1, 0, 0, 0],
            [0, 0, 0, 1, 0],
            [0, 0, 0, 0, 1],
        ] * 4)

        labels = ['产品质量', '价格', '客服服务', '物流配送', '售后']

        trainer.train(X=X, y=y, labels=labels)

        history = trainer.get_training_history()
        assert len(history) > 0

        events = [entry['event'] for entry in history]
        assert 'data_split_started' in events
        assert 'training_completed' in events

    def test_trainer_getters(self):
        trainer = Trainer(TrainerConfig(random_state=42))

        assert trainer.get_model() is None
        assert trainer.get_vectorizer() is None
        assert trainer.get_labels() is None

        X = [
            ['产品', '质量', '很好'],
            ['客服', '态度', '好'],
        ] * 4

        y = np.array([
            [1, 0, 0, 0, 0],
            [0, 0, 1, 0, 0],
        ] * 4)

        labels = ['产品质量', '价格', '客服服务', '物流配送', '售后']

        trainer.train(X=X, y=y, labels=labels)

        assert trainer.get_model() is not None
        assert trainer.get_vectorizer() is not None
        assert trainer.get_labels() == labels


class TestEvaluatorModule:
    def test_evaluation_config_initialization(self):
        config = EvaluationConfig(
            accuracy_threshold=0.8,
            precision_threshold=0.7,
            recall_threshold=0.7,
            f1_threshold=0.7,
            average_method='weighted',
            zero_division=1
        )
        assert config.accuracy_threshold == 0.8
        assert config.precision_threshold == 0.7
        assert config.recall_threshold == 0.7
        assert config.f1_threshold == 0.7
        assert config.average_method == 'weighted'
        assert config.zero_division == 1

    def test_evaluation_result_initialization(self):
        result = EvaluationResult(
            success=True,
            metrics={'accuracy': 0.85},
            details={'test_samples': 100},
            threshold_passed=True,
            passed_checks=['accuracy (0.85 >= 0.7)'],
            failed_checks=[],
            message='测试通过'
        )
        assert result.success is True
        assert result.metrics['accuracy'] == 0.85
        assert result.threshold_passed is True
        assert len(result.passed_checks) == 1
        assert len(result.failed_checks) == 0

    def test_calculate_metrics_basic(self):
        evaluator = Evaluator(EvaluationConfig(average_method='macro'))

        y_true = np.array([[1, 0], [0, 1], [1, 0], [0, 1], [1, 0], [0, 1], [1, 0], [0, 1]])
        y_pred = np.array([[1, 0], [0, 1], [1, 0], [0, 1], [1, 0], [0, 1], [1, 0], [0, 1]])

        metrics = evaluator.calculate_metrics(y_true, y_pred, labels=['A', 'B'])

        assert 'accuracy' in metrics
        assert 'precision' in metrics
        assert 'recall' in metrics
        assert 'f1_score' in metrics
        assert 'hamming_loss' in metrics
        assert metrics['accuracy'] == 1.0

    def test_calculate_metrics_with_errors(self):
        evaluator = Evaluator(EvaluationConfig(average_method='macro'))

        y_true = np.array([[1, 0], [0, 1], [1, 0], [0, 1]])
        y_pred = np.array([[0, 1], [1, 0], [0, 1], [1, 0]])

        metrics = evaluator.calculate_metrics(y_true, y_pred, labels=['A', 'B'])

        assert metrics['accuracy'] == 0.0
        assert metrics['hamming_loss'] == 1.0

    def test_check_thresholds_passed(self):
        evaluator = Evaluator(EvaluationConfig(
            accuracy_threshold=0.7,
            precision_threshold=0.6,
            recall_threshold=0.6,
            f1_threshold=0.6
        ))

        metrics = {
            'accuracy': 0.85,
            'precision': 0.82,
            'recall': 0.79,
            'f1_score': 0.80
        }

        threshold_passed, passed, failed = evaluator.check_thresholds(metrics)

        assert threshold_passed is True
        assert len(failed) == 0
        assert len(passed) == 4

    def test_check_thresholds_failed(self):
        evaluator = Evaluator(EvaluationConfig(
            accuracy_threshold=0.9,
            precision_threshold=0.9,
            recall_threshold=0.9,
            f1_threshold=0.9
        ))

        metrics = {
            'accuracy': 0.85,
            'precision': 0.82,
            'recall': 0.79,
            'f1_score': 0.80
        }

        threshold_passed, passed, failed = evaluator.check_thresholds(metrics)

        assert threshold_passed is False
        assert len(failed) == 4

    def test_compare_models_improvement(self):
        evaluator = Evaluator()

        new_metrics = {
            'accuracy': 0.88,
            'precision': 0.85,
            'recall': 0.83,
            'f1_score': 0.84
        }

        baseline_metrics = {
            'accuracy': 0.82,
            'precision': 0.80,
            'recall': 0.78,
            'f1_score': 0.79
        }

        comparison = evaluator.compare_models(
            new_metrics,
            baseline_metrics,
            min_improvement_threshold=0.01
        )

        assert comparison.new_model_better is True
        assert 'accuracy' in comparison.improvements
        assert '推荐' in comparison.recommendation or 'recommend' in comparison.recommendation.lower()

    def test_compare_models_degradation(self):
        evaluator = Evaluator()

        new_metrics = {
            'accuracy': 0.75,
            'precision': 0.70,
            'recall': 0.72,
            'f1_score': 0.71
        }

        baseline_metrics = {
            'accuracy': 0.85,
            'precision': 0.82,
            'recall': 0.80,
            'f1_score': 0.81
        }

        comparison = evaluator.compare_models(
            new_metrics,
            baseline_metrics,
            min_improvement_threshold=0.01
        )

        assert comparison.new_model_better is False
        assert 'accuracy' in comparison.degradation

    def test_evaluation_history(self):
        evaluator = Evaluator()

        assert len(evaluator.get_evaluation_history()) == 0

        evaluator._evaluation_history.append({
            'evaluation_id': 'eval_test',
            'timestamp': datetime.now().isoformat(),
            'metrics': {'accuracy': 0.8},
            'threshold_passed': True,
            'test_samples': 100
        })

        history = evaluator.get_evaluation_history()
        assert len(history) == 1

        evaluator.clear_history()
        assert len(evaluator.get_evaluation_history()) == 0

    def test_evaluate_with_none_model(self):
        evaluator = Evaluator()

        result = evaluator.evaluate(
            model=None,
            vectorizer=None,
            X_test=[['test']],
            y_test=np.array([[1, 0]]),
            labels=['A', 'B']
        )

        assert result.success is False
        assert "模型" in result.message or "model" in result.message.lower()


class TestDataPreprocessingIntegration:
    def test_validate_training_data_valid(self, training_data):
        from app.modules.training_service import TrainingService
        service = TrainingService()

        is_valid, message, validated = service._validate_training_data(training_data)

        assert is_valid is True
        assert len(validated) == len(training_data)

    def test_validate_training_data_empty(self):
        from app.modules.training_service import TrainingService
        service = TrainingService()

        is_valid, message, validated = service._validate_training_data([])

        assert is_valid is False
        assert "空" in message or "empty" in message.lower()

    def test_validate_training_data_missing_text(self, training_data):
        from app.modules.training_service import TrainingService
        service = TrainingService()

        invalid_data = training_data.copy()
        invalid_data[0] = {'labels': ['产品质量']}

        is_valid, message, validated = service._validate_training_data(invalid_data)

        assert is_valid is False

    def test_validate_training_data_missing_labels(self, training_data):
        from app.modules.training_service import TrainingService
        service = TrainingService()

        invalid_data = training_data.copy()
        invalid_data[0] = {'text': '测试文本'}

        is_valid, message, validated = service._validate_training_data(invalid_data)

        assert is_valid is False

    def test_preprocess_data(self, training_data):
        from app.modules.training_service import TrainingService
        service = TrainingService()

        all_tokens, label_vectors, unique_labels = service._preprocess_data(training_data)

        assert len(all_tokens) == len(training_data)
        assert len(label_vectors) == len(training_data)
        assert len(unique_labels) >= 2
        assert isinstance(all_tokens[0], list)
        assert isinstance(label_vectors[0], list)

    def test_split_data_proportions(self, training_data):
        from app.modules.training_service import TrainingService
        service = TrainingService()

        all_tokens, label_vectors, unique_labels = service._preprocess_data(training_data)
        y = np.array(label_vectors)

        X_train, X_test, y_train, y_test = service._split_data(
            X=all_tokens,
            y=y,
            test_size=0.2,
            random_state=42
        )

        total = len(X_train) + len(X_test)
        assert abs(len(X_test) / total - 0.2) < 0.1
        assert y_train.shape[1] == len(unique_labels)
        assert y_test.shape[1] == len(unique_labels)


class TestModelVersionFlow:
    @pytest.fixture
    def test_engine(self):
        engine = create_engine(TEST_DATABASE_URL, connect_args={"check_same_thread": False})
        Base.metadata.create_all(bind=engine)
        yield engine
        Base.metadata.drop_all(bind=engine)

    @pytest.fixture
    def test_session(self, test_engine):
        TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=test_engine)
        session = TestingSessionLocal()
        yield session
        session.close()

    def test_model_status_enum(self):
        assert ModelStatus.CANDIDATE.value == "candidate"
        assert ModelStatus.VALIDATING.value == "validating"
        assert ModelStatus.VALIDATED.value == "validated"
        assert ModelStatus.VALIDATION_FAILED.value == "validation_failed"
        assert ModelStatus.ACTIVE.value == "active"
        assert ModelStatus.INACTIVE.value == "inactive"
        assert ModelStatus.ROLLBACKED.value == "rollbacked"

    def test_validation_status_enum(self):
        assert ValidationStatus.PENDING.value == "pending"
        assert ValidationStatus.RUNNING.value == "running"
        assert ValidationStatus.PASSED.value == "passed"
        assert ValidationStatus.FAILED.value == "failed"

    def test_model_version_creation(self, test_session):
        model = ModelVersion(
            model_id="test_v1",
            model_type="multilabel_classifier",
            version="v1.0.0",
            labels=['A', 'B', 'C'],
            model_path="/path/to/model.pkl",
            vectorizer_path="/path/to/vectorizer.pkl",
            is_active=False,
            status=ModelStatus.CANDIDATE,
            validation_status=ValidationStatus.PENDING
        )

        test_session.add(model)
        test_session.commit()
        test_session.refresh(model)

        assert model.model_id == "test_v1"
        assert model.status == ModelStatus.CANDIDATE
        assert model.is_active is False

    def test_model_version_status_transitions(self, test_session):
        model = ModelVersion(
            model_id="test_v2",
            model_type="multilabel_classifier",
            version="v2.0.0",
            labels=['A', 'B'],
            model_path="/path/model.pkl",
            vectorizer_path="/path/vec.pkl",
            is_active=False,
            status=ModelStatus.CANDIDATE,
            validation_status=ValidationStatus.PENDING
        )

        test_session.add(model)
        test_session.commit()

        model.status = ModelStatus.VALIDATING
        model.validation_status = ValidationStatus.RUNNING
        test_session.commit()
        test_session.refresh(model)

        assert model.status == ModelStatus.VALIDATING
        assert model.validation_status == ValidationStatus.RUNNING

        model.status = ModelStatus.VALIDATED
        model.validation_status = ValidationStatus.PASSED
        model.validation_score = 0.85
        test_session.commit()
        test_session.refresh(model)

        assert model.status == ModelStatus.VALIDATED
        assert model.validation_score == 0.85

        model.status = ModelStatus.ACTIVE
        model.is_active = True
        model.activated_at = datetime.now()
        test_session.commit()
        test_session.refresh(model)

        assert model.status == ModelStatus.ACTIVE
        assert model.is_active is True

    def test_validation_record_creation(self, test_session):
        record = ModelValidationRecord(
            validation_id="val_test_001",
            model_id="test_model_v1",
            status=ValidationStatus.PASSED,
            validation_samples=100,
            accuracy=0.88,
            precision=0.85,
            recall=0.82,
            f1_score=0.83,
            threshold_passed=True,
            details={'passed_checks': ['accuracy >= 0.7']}
        )

        test_session.add(record)
        test_session.commit()
        test_session.refresh(record)

        assert record.validation_id == "val_test_001"
        assert record.accuracy == 0.88
        assert record.threshold_passed is True


class TestFullTrainingFlowIntegration:
    def test_full_train_evaluate_flow(self):
        trainer = Trainer(TrainerConfig(random_state=42))
        evaluator = Evaluator(EvaluationConfig(
            accuracy_threshold=0.5,
            precision_threshold=0.5,
            recall_threshold=0.5,
            f1_threshold=0.5
        ))

        X = [
            ['产品', '质量', '很好'],
            ['客服', '态度', '好'],
            ['价格', '很', '贵'],
            ['物流', '很慢'],
            ['售后', '服务', '不错'],
        ] * 10

        y = np.array([
            [1, 0, 0, 0, 0],
            [0, 0, 1, 0, 0],
            [0, 1, 0, 0, 0],
            [0, 0, 0, 1, 0],
            [0, 0, 0, 0, 1],
        ] * 10)

        labels = ['产品质量', '价格', '客服服务', '物流配送', '售后']

        X_train, X_test, y_train, y_test = trainer.split_data(X, y, test_size=0.3)

        training_result = trainer.train(
            X=X_train,
            y=y_train,
            labels=labels
        )

        assert training_result.success is True

        evaluation_result = evaluator.evaluate(
            model=training_result.model,
            vectorizer=training_result.vectorizer,
            X_test=X_test,
            y_test=y_test,
            labels=labels
        )

        assert evaluation_result.success is True
        assert 'accuracy' in evaluation_result.metrics
        assert 'precision' in evaluation_result.metrics
        assert 'recall' in evaluation_result.metrics
        assert 'f1_score' in evaluation_result.metrics

    def test_trainer_and_evaluator_history_tracking(self):
        trainer = Trainer(TrainerConfig(random_state=42))
        evaluator = Evaluator()

        assert len(trainer.get_training_history()) == 0
        assert len(evaluator.get_evaluation_history()) == 0

        X = [
            ['产品', '质量', '很好'],
            ['客服', '态度', '好'],
        ] * 10

        y = np.array([
            [1, 0, 0, 0, 0],
            [0, 0, 1, 0, 0],
        ] * 10)

        labels = ['产品质量', '价格', '客服服务', '物流配送', '售后']

        trainer.train(X=X, y=y, labels=labels)

        assert len(trainer.get_training_history()) > 0

        trainer.clear_history()
        evaluator.clear_history()

        assert len(trainer.get_training_history()) == 0
        assert len(evaluator.get_evaluation_history()) == 0

    def test_training_job_model(self):
        job = TrainingJob(
            job_id="job_test_001",
            model_type="multilabel_classifier",
            status="running",
            training_samples=100,
            test_size=0.2,
            random_state=42
        )

        assert job.job_id == "job_test_001"
        assert job.status == "running"
        assert job.training_samples == 100

    def test_metrics_consistency(self):
        evaluator = Evaluator()

        y_true = np.array([
            [1, 0, 0],
            [0, 1, 0],
            [0, 0, 1],
            [1, 0, 0],
            [0, 1, 0],
        ])
        y_pred = np.array([
            [1, 0, 0],
            [0, 1, 0],
            [0, 0, 1],
            [1, 0, 0],
            [0, 1, 0],
        ])

        metrics = evaluator.calculate_metrics(y_true, y_pred, labels=['A', 'B', 'C'])

        assert metrics['accuracy'] == 1.0
        assert metrics['precision'] == 1.0
        assert metrics['recall'] == 1.0
        assert metrics['f1_score'] == 1.0
        assert metrics['hamming_loss'] == 0.0

        assert 'per_label' in metrics
        for label in ['A', 'B', 'C']:
            assert label in metrics['per_label']
            assert metrics['per_label'][label]['precision'] == 1.0
