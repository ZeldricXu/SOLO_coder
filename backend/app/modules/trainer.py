import joblib
import numpy as np
from typing import List, Dict, Optional, Tuple, Any
from pathlib import Path
from datetime import datetime

from sklearn.multioutput import MultiOutputClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.model_selection import train_test_split

from app.core.config import settings


class TrainerConfig:
    def __init__(
        self,
        max_features: int = 10000,
        ngram_range: Tuple[int, int] = (1, 2),
        max_iter: int = 1000,
        class_weight: str = 'balanced',
        random_state: int = 42,
        test_size: float = 0.2,
        stratify: bool = True
    ):
        self.max_features = max_features
        self.ngram_range = ngram_range
        self.max_iter = max_iter
        self.class_weight = class_weight
        self.random_state = random_state
        self.test_size = test_size
        self.stratify = stratify


class TrainingResult:
    def __init__(
        self,
        success: bool,
        model: Any = None,
        vectorizer: Any = None,
        metrics: Dict = None,
        labels: List[str] = None,
        message: str = "",
        training_history: List[Dict] = None
    ):
        self.success = success
        self.model = model
        self.vectorizer = vectorizer
        self.metrics = metrics or {}
        self.labels = labels or []
        self.message = message
        self.training_history = training_history or []


class Trainer:
    def __init__(self, config: Optional[TrainerConfig] = None):
        self.config = config or TrainerConfig()
        self._model = None
        self._vectorizer = None
        self._labels = None
        self._training_history = []

    def _init_vectorizer(self) -> TfidfVectorizer:
        return TfidfVectorizer(
            max_features=self.config.max_features,
            ngram_range=self.config.ngram_range,
            tokenizer=lambda x: x,
            preprocessor=lambda x: x,
            token_pattern=None
        )

    def _init_classifier(self) -> MultiOutputClassifier:
        base_classifier = LogisticRegression(
            max_iter=self.config.max_iter,
            class_weight=self.config.class_weight,
            random_state=self.config.random_state
        )
        return MultiOutputClassifier(base_classifier)

    def split_data(
        self,
        X: List[List[str]],
        y: np.ndarray,
        test_size: Optional[float] = None
    ) -> Tuple[List[List[str]], List[List[str]], np.ndarray, np.ndarray]:
        actual_test_size = test_size or self.config.test_size

        stratify_param = y if self.config.stratify and y.shape[1] == 1 else None

        X_train, X_test, y_train, y_test = train_test_split(
            X, y,
            test_size=actual_test_size,
            random_state=self.config.random_state,
            stratify=stratify_param
        )

        return X_train, X_test, y_train, y_test

    def fit_vectorizer(
        self,
        X_train: List[List[str]],
        X_test: List[List[str]]
    ) -> Tuple[Any, Any]:
        self._vectorizer = self._init_vectorizer()
        X_train_vec = self._vectorizer.fit_transform(X_train)
        X_test_vec = self._vectorizer.transform(X_test)
        return X_train_vec, X_test_vec

    def train_model(
        self,
        X_train_vec: Any,
        y_train: np.ndarray,
        record_history: bool = True
    ) -> Any:
        self._model = self._init_classifier()

        history_entry = {
            "timestamp": datetime.now().isoformat(),
            "event": "training_started",
            "train_samples": X_train_vec.shape[0],
            "features": X_train_vec.shape[1]
        }
        if record_history:
            self._training_history.append(history_entry)

        self._model.fit(X_train_vec, y_train)

        history_entry = {
            "timestamp": datetime.now().isoformat(),
            "event": "training_completed"
        }
        if record_history:
            self._training_history.append(history_entry)

        return self._model

    def train(
        self,
        X: List[List[str]],
        y: np.ndarray,
        labels: List[str],
        test_size: Optional[float] = None
    ) -> TrainingResult:
        if not X or not len(X):
            return TrainingResult(
                success=False,
                message="训练数据为空"
            )

        if not labels or len(labels) < 2:
            return TrainingResult(
                success=False,
                message="标签数量不足，至少需要2种不同的标签"
            )

        self._labels = labels

        try:
            history_entry = {
                "timestamp": datetime.now().isoformat(),
                "event": "data_split_started",
                "total_samples": len(X)
            }
            self._training_history.append(history_entry)

            X_train, X_test, y_train, y_test = self.split_data(X, y, test_size)

            history_entry = {
                "timestamp": datetime.now().isoformat(),
                "event": "data_split_completed",
                "train_samples": len(X_train),
                "test_samples": len(X_test)
            }
            self._training_history.append(history_entry)

            history_entry = {
                "timestamp": datetime.now().isoformat(),
                "event": "vectorization_started"
            }
            self._training_history.append(history_entry)

            X_train_vec, X_test_vec = self.fit_vectorizer(X_train, X_test)

            history_entry = {
                "timestamp": datetime.now().isoformat(),
                "event": "vectorization_completed",
                "features": X_train_vec.shape[1]
            }
            self._training_history.append(history_entry)

            model = self.train_model(X_train_vec, y_train)

            metrics = {
                "train_samples": X_train_vec.shape[0],
                "test_samples": X_test_vec.shape[0],
                "features": X_train_vec.shape[1],
                "labels": labels
            }

            return TrainingResult(
                success=True,
                model=model,
                vectorizer=self._vectorizer,
                metrics=metrics,
                labels=labels,
                message="模型训练成功",
                training_history=self._training_history.copy()
            )

        except Exception as e:
            return TrainingResult(
                success=False,
                message=f"模型训练失败: {str(e)}",
                training_history=self._training_history.copy()
            )

    def save_model(
        self,
        model_path: Path,
        vectorizer_path: Path
    ) -> Tuple[bool, str]:
        if not self._model or not self._vectorizer:
            return False, "模型未训练，无法保存"

        try:
            model_path.parent.mkdir(parents=True, exist_ok=True)
            vectorizer_path.parent.mkdir(parents=True, exist_ok=True)

            joblib.dump(self._model, model_path)
            joblib.dump(self._vectorizer, vectorizer_path)

            return True, "模型保存成功"

        except Exception as e:
            return False, f"模型保存失败: {str(e)}"

    def get_model(self) -> Optional[Any]:
        return self._model

    def get_vectorizer(self) -> Optional[Any]:
        return self._vectorizer

    def get_labels(self) -> Optional[List[str]]:
        return self._labels

    def get_training_history(self) -> List[Dict]:
        return self._training_history.copy()

    def clear_history(self):
        self._training_history = []


trainer = Trainer()
