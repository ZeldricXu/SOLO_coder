import joblib
import numpy as np
from typing import List, Dict, Optional, Tuple
from pathlib import Path
from datetime import datetime

from sklearn.multioutput import MultiOutputClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.feature_extraction.text import TfidfVectorizer

from app.core.config import settings
from app.modules.preprocessing import text_preprocessor


class BatchInferenceResult:
    def __init__(
        self,
        success: bool,
        results: List[Dict] = None,
        total_count: int = 0,
        success_count: int = 0,
        failed_count: int = 0,
        message: str = ""
    ):
        self.success = success
        self.results = results or []
        self.total_count = total_count
        self.success_count = success_count
        self.failed_count = failed_count
        self.message = message


class TextClassifier:
    def __init__(self):
        self.model = None
        self.vectorizer = None
        self.labels = settings.DEFAULT_LABELS
        self.model_version = settings.DEFAULT_MODEL_VERSION
        self.model_path = None
        self.vectorizer_path = None
        self._inference_count = 0
        self._batch_inference_count = 0

    def load_model(
        self,
        model_path: Optional[Path] = None,
        vectorizer_path: Optional[Path] = None,
        labels: Optional[List[str]] = None
    ) -> bool:
        try:
            if model_path and vectorizer_path:
                if not model_path.exists():
                    raise FileNotFoundError(f"模型文件不存在: {model_path}")
                if not vectorizer_path.exists():
                    raise FileNotFoundError(f"向量器文件不存在: {vectorizer_path}")

                self.model = joblib.load(model_path)
                self.vectorizer = joblib.load(vectorizer_path)
                self.model_path = model_path
                self.vectorizer_path = vectorizer_path

                if labels:
                    self.labels = labels
                return True
            else:
                default_model_path = settings.MODELS_DIR / f"classifier_{self.model_version}.pkl"
                default_vectorizer_path = settings.MODELS_DIR / f"vectorizer_{self.model_version}.pkl"

                if default_model_path.exists() and default_vectorizer_path.exists():
                    self.model = joblib.load(default_model_path)
                    self.vectorizer = joblib.load(default_vectorizer_path)
                    self.model_path = default_model_path
                    self.vectorizer_path = default_vectorizer_path
                    return True
                else:
                    self._create_default_model()
                    return True

        except Exception as e:
            print(f"加载模型失败: {e}")
            self._create_default_model()
            return False

    def _create_default_model(self):
        self.vectorizer = TfidfVectorizer(
            max_features=10000,
            ngram_range=(1, 2),
            tokenizer=lambda x: x,
            preprocessor=lambda x: x,
            token_pattern=None
        )

        base_classifier = LogisticRegression(
            max_iter=1000,
            class_weight='balanced',
            random_state=42
        )
        self.model = MultiOutputClassifier(base_classifier)

        dummy_texts = [
            ["产品", "质量", "很好"],
            ["价格", "便宜", "实惠"],
            ["客服", "态度", "好"],
            ["物流", "很快", "配送"],
            ["售后", "服务", "不错"]
        ]
        dummy_labels = np.array([
            [1, 0, 0, 0, 0],
            [0, 1, 0, 0, 0],
            [0, 0, 1, 0, 0],
            [0, 0, 0, 1, 0],
            [0, 0, 0, 0, 1]
        ])

        try:
            X = self.vectorizer.fit_transform(dummy_texts)
            self.model.fit(X, dummy_labels)

            default_model_path = settings.MODELS_DIR / f"classifier_{self.model_version}.pkl"
            default_vectorizer_path = settings.MODELS_DIR / f"vectorizer_{self.model_version}.pkl"

            joblib.dump(self.model, default_model_path)
            joblib.dump(self.vectorizer, default_vectorizer_path)

            self.model_path = default_model_path
            self.vectorizer_path = default_vectorizer_path

        except Exception as e:
            print(f"创建默认模型失败: {e}")

    def _preprocess_batch(
        self,
        texts: List[str]
    ) -> Tuple[List[Dict], List[List[str]], List[int]]:
        preprocess_results = []
        valid_tokens = []
        valid_indices = []

        for idx, text in enumerate(texts):
            if not text or not isinstance(text, str):
                preprocess_results.append({
                    "index": idx,
                    "original_text": text,
                    "status": "error",
                    "message": "文本为空或无效",
                    "categories": []
                })
                continue

            preprocess_result = text_preprocessor.preprocess(text)
            if preprocess_result["status"] != "success":
                preprocess_results.append({
                    "index": idx,
                    "original_text": text,
                    "status": "error",
                    "message": preprocess_result["message"],
                    "categories": []
                })
                continue

            filtered_tokens = preprocess_result["filtered_tokens"]
            if not filtered_tokens:
                preprocess_results.append({
                    "index": idx,
                    "original_text": text,
                    "status": "error",
                    "message": "文本预处理后无有效词汇",
                    "categories": []
                })
                continue

            valid_tokens.append(filtered_tokens)
            valid_indices.append(idx)

            preprocess_results.append({
                "index": idx,
                "original_text": text,
                "cleaned_text": preprocess_result["cleaned_text"],
                "tokens": preprocess_result["tokens"],
                "filtered_tokens": filtered_tokens,
                "status": "success",
                "message": "预处理成功"
            })

        return preprocess_results, valid_tokens, valid_indices

    def _vectorize_batch(
        self,
        tokens_list: List[List[str]]
    ) -> Optional[object]:
        if not tokens_list or self.vectorizer is None:
            return None

        try:
            X = self.vectorizer.transform(tokens_list)
            return X
        except Exception as e:
            print(f"批量向量化失败: {e}")
            return None

    def _predict_batch_proba(
        self,
        X: object
    ) -> Optional[List[np.ndarray]]:
        if self.model is None or X is None:
            return None

        try:
            probabilities = self.model.predict_proba(X)
            return probabilities
        except Exception as e:
            print(f"批量推理失败: {e}")
            return None

    def _parse_batch_probabilities(
        self,
        probabilities: List[np.ndarray],
        num_samples: int,
        confidence_threshold: float
    ) -> List[List[Dict]]:
        batch_categories = []

        for sample_idx in range(num_samples):
            categories = []
            for label_idx, label in enumerate(self.labels):
                if label_idx < len(probabilities):
                    prob_array = probabilities[label_idx]
                    if len(prob_array.shape) > 1:
                        prob = float(prob_array[sample_idx][1])
                    else:
                        prob = float(prob_array[sample_idx])

                    if prob >= confidence_threshold:
                        categories.append({
                            "label": label,
                            "confidence": prob
                        })

            categories.sort(key=lambda x: x["confidence"], reverse=True)
            batch_categories.append(categories)

        return batch_categories

    def predict_batch(
        self,
        texts: List[str],
        confidence_threshold: float = None,
        model_version: str = None
    ) -> BatchInferenceResult:
        if confidence_threshold is None:
            confidence_threshold = settings.DEFAULT_CONFIDENCE_THRESHOLD

        if not texts or not isinstance(texts, list):
            return BatchInferenceResult(
                success=False,
                message="文本列表为空或无效"
            )

        if self.model is None or self.vectorizer is None:
            self.load_model()

        if self.model is None or self.vectorizer is None:
            return BatchInferenceResult(
                success=False,
                message="模型未加载",
                total_count=len(texts),
                failed_count=len(texts)
            )

        preprocess_results, valid_tokens, valid_indices = self._preprocess_batch(texts)

        if not valid_tokens:
            failed_results = []
            for r in preprocess_results:
                failed_results.append({
                    "index": r["index"],
                    "text": r.get("original_text", ""),
                    "categories": [],
                    "status": r["status"],
                    "message": r["message"],
                    "model_version": self.model_version,
                    "confidence_threshold": confidence_threshold
                })

            return BatchInferenceResult(
                success=True,
                results=failed_results,
                total_count=len(texts),
                success_count=0,
                failed_count=len(texts),
                message="所有文本预处理失败"
            )

        X = self._vectorize_batch(valid_tokens)
        if X is None:
            return BatchInferenceResult(
                success=False,
                message="向量化失败",
                total_count=len(texts),
                failed_count=len(texts)
            )

        probabilities = self._predict_batch_proba(X)
        if probabilities is None:
            return BatchInferenceResult(
                success=False,
                message="批量推理失败",
                total_count=len(texts),
                failed_count=len(texts)
            )

        batch_categories = self._parse_batch_probabilities(
            probabilities,
            len(valid_tokens),
            confidence_threshold
        )

        final_results = [None] * len(texts)

        for i, valid_idx in enumerate(valid_indices):
            preprocess_info = preprocess_results[valid_idx]
            categories = batch_categories[i]

            final_results[valid_idx] = {
                "index": valid_idx,
                "text": preprocess_info["original_text"],
                "categories": categories,
                "status": "success",
                "message": "分类预测成功",
                "model_version": self.model_version,
                "confidence_threshold": confidence_threshold
            }

        for idx in range(len(texts)):
            if final_results[idx] is None:
                preprocess_info = preprocess_results[idx]
                final_results[idx] = {
                    "index": idx,
                    "text": preprocess_info.get("original_text", ""),
                    "categories": [],
                    "status": "error",
                    "message": preprocess_info.get("message", "未知错误"),
                    "model_version": self.model_version,
                    "confidence_threshold": confidence_threshold
                }

        success_count = sum(1 for r in final_results if r["status"] == "success")
        failed_count = len(final_results) - success_count

        self._inference_count += len(texts)
        self._batch_inference_count += 1

        return BatchInferenceResult(
            success=True,
            results=final_results,
            total_count=len(texts),
            success_count=success_count,
            failed_count=failed_count,
            message=f"批量推理完成，成功: {success_count}, 失败: {failed_count}"
        )

    def predict(
        self,
        text: str,
        confidence_threshold: float = None,
        model_version: str = None
    ) -> Dict:
        if confidence_threshold is None:
            confidence_threshold = settings.DEFAULT_CONFIDENCE_THRESHOLD

        batch_result = self.predict_batch(
            texts=[text],
            confidence_threshold=confidence_threshold,
            model_version=model_version
        )

        if not batch_result.success or not batch_result.results:
            return {
                "categories": [],
                "status": "error",
                "message": batch_result.message or "分类预测失败",
                "model_version": self.model_version,
                "confidence_threshold": confidence_threshold
            }

        result = batch_result.results[0]

        return {
            "categories": result["categories"],
            "status": result["status"],
            "message": result["message"],
            "model_version": result["model_version"],
            "confidence_threshold": result["confidence_threshold"]
        }

    def get_labels(self) -> List[str]:
        return self.labels.copy()

    def set_labels(self, labels: List[str]):
        self.labels = labels.copy()

    def get_model_info(self) -> Dict:
        return {
            "model_version": self.model_version,
            "labels": self.labels,
            "model_path": str(self.model_path) if self.model_path else None,
            "vectorizer_path": str(self.vectorizer_path) if self.vectorizer_path else None,
            "is_loaded": self.model is not None and self.vectorizer is not None,
            "inference_count": self._inference_count,
            "batch_inference_count": self._batch_inference_count
        }

    def get_inference_stats(self) -> Dict:
        return {
            "total_inference_count": self._inference_count,
            "batch_inference_count": self._batch_inference_count,
            "model_version": self.model_version
        }


text_classifier = TextClassifier()
