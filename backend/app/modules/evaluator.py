import numpy as np
from typing import List, Dict, Optional, Tuple, Any
from datetime import datetime
import uuid

from sklearn.metrics import (
    accuracy_score,
    precision_score,
    recall_score,
    f1_score,
    hamming_loss,
    classification_report
)

from app.core.config import settings


class EvaluationConfig:
    def __init__(
        self,
        accuracy_threshold: float = 0.7,
        precision_threshold: float = 0.6,
        recall_threshold: float = 0.6,
        f1_threshold: float = 0.6,
        average_method: str = 'macro',
        zero_division: int = 0
    ):
        self.accuracy_threshold = accuracy_threshold
        self.precision_threshold = precision_threshold
        self.recall_threshold = recall_threshold
        self.f1_threshold = f1_threshold
        self.average_method = average_method
        self.zero_division = zero_division


class EvaluationResult:
    def __init__(
        self,
        success: bool,
        metrics: Dict = None,
        details: Dict = None,
        threshold_passed: bool = False,
        passed_checks: List[str] = None,
        failed_checks: List[str] = None,
        message: str = ""
    ):
        self.success = success
        self.metrics = metrics or {}
        self.details = details or {}
        self.threshold_passed = threshold_passed
        self.passed_checks = passed_checks or []
        self.failed_checks = failed_checks or []
        self.message = message


class ModelComparisonResult:
    def __init__(
        self,
        new_model_better: bool,
        new_metrics: Dict,
        baseline_metrics: Dict,
        improvements: Dict,
        degradation: Dict,
        recommendation: str
    ):
        self.new_model_better = new_model_better
        self.new_metrics = new_metrics
        self.baseline_metrics = baseline_metrics
        self.improvements = improvements
        self.degradation = degradation
        self.recommendation = recommendation


class Evaluator:
    def __init__(self, config: Optional[EvaluationConfig] = None):
        self.config = config or EvaluationConfig()
        self._evaluation_history = []

    def calculate_metrics(
        self,
        y_true: np.ndarray,
        y_pred: np.ndarray,
        labels: Optional[List[str]] = None
    ) -> Dict[str, float]:
        metrics = {}

        metrics['accuracy'] = float(accuracy_score(y_true, y_pred))

        metrics['precision'] = float(precision_score(
            y_true, y_pred,
            average=self.config.average_method,
            zero_division=self.config.zero_division
        ))

        metrics['recall'] = float(recall_score(
            y_true, y_pred,
            average=self.config.average_method,
            zero_division=self.config.zero_division
        ))

        metrics['f1_score'] = float(f1_score(
            y_true, y_pred,
            average=self.config.average_method,
            zero_division=self.config.zero_division
        ))

        metrics['hamming_loss'] = float(hamming_loss(y_true, y_pred))

        if labels:
            try:
                report = classification_report(
                    y_true, y_pred,
                    target_names=labels,
                    output_dict=True,
                    zero_division=self.config.zero_division
                )
                metrics['per_label'] = {
                    label: {
                        'precision': float(report[label]['precision']),
                        'recall': float(report[label]['recall']),
                        'f1_score': float(report[label]['f1-score']),
                        'support': int(report[label]['support'])
                    }
                    for label in labels if label in report
                }
            except Exception:
                pass

        return metrics

    def check_thresholds(
        self,
        metrics: Dict[str, float]
    ) -> Tuple[bool, List[str], List[str]]:
        passed = []
        failed = []

        accuracy = metrics.get('accuracy', 0.0)
        if accuracy >= self.config.accuracy_threshold:
            passed.append(f"accuracy ({accuracy:.4f} >= {self.config.accuracy_threshold})")
        else:
            failed.append(f"accuracy ({accuracy:.4f} < {self.config.accuracy_threshold})")

        precision = metrics.get('precision', 0.0)
        if precision >= self.config.precision_threshold:
            passed.append(f"precision ({precision:.4f} >= {self.config.precision_threshold})")
        else:
            failed.append(f"precision ({precision:.4f} < {self.config.precision_threshold})")

        recall = metrics.get('recall', 0.0)
        if recall >= self.config.recall_threshold:
            passed.append(f"recall ({recall:.4f} >= {self.config.recall_threshold})")
        else:
            failed.append(f"recall ({recall:.4f} < {self.config.recall_threshold})")

        f1 = metrics.get('f1_score', 0.0)
        if f1 >= self.config.f1_threshold:
            passed.append(f"f1_score ({f1:.4f} >= {self.config.f1_threshold})")
        else:
            failed.append(f"f1_score ({f1:.4f} < {self.config.f1_threshold})")

        threshold_passed = len(failed) == 0

        return threshold_passed, passed, failed

    def evaluate(
        self,
        model: Any,
        vectorizer: Any,
        X_test: List[List[str]],
        y_test: np.ndarray,
        labels: List[str],
        record_history: bool = True
    ) -> EvaluationResult:
        if model is None or vectorizer is None:
            return EvaluationResult(
                success=False,
                message="模型或向量器未加载"
            )

        try:
            X_test_vec = vectorizer.transform(X_test)

            y_pred = model.predict(X_test_vec)

            metrics = self.calculate_metrics(y_test, y_pred, labels)

            threshold_passed, passed_checks, failed_checks = self.check_thresholds(metrics)

            details = {
                'test_samples': len(X_test),
                'labels': labels,
                'evaluated_at': datetime.now().isoformat()
            }

            evaluation_id = f"eval_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"

            history_entry = {
                'evaluation_id': evaluation_id,
                'timestamp': datetime.now().isoformat(),
                'metrics': metrics,
                'threshold_passed': threshold_passed,
                'test_samples': len(X_test)
            }

            if record_history:
                self._evaluation_history.append(history_entry)

            message = "评估通过" if threshold_passed else "评估未通过阈值检查"

            return EvaluationResult(
                success=True,
                metrics=metrics,
                details=details,
                threshold_passed=threshold_passed,
                passed_checks=passed_checks,
                failed_checks=failed_checks,
                message=message
            )

        except Exception as e:
            return EvaluationResult(
                success=False,
                message=f"评估过程异常: {str(e)}"
            )

    def compare_models(
        self,
        new_model_metrics: Dict,
        baseline_model_metrics: Dict,
        min_improvement_threshold: float = 0.02
    ) -> ModelComparisonResult:
        improvements = {}
        degradation = {}

        for metric_name in ['accuracy', 'precision', 'recall', 'f1_score']:
            new_value = new_model_metrics.get(metric_name, 0.0)
            baseline_value = baseline_model_metrics.get(metric_name, 0.0)

            diff = new_value - baseline_value

            if diff > 0:
                improvements[metric_name] = {
                    'new': new_value,
                    'baseline': baseline_value,
                    'improvement': diff,
                    'improvement_percent': (diff / baseline_value * 100) if baseline_value > 0 else 0
                }
            elif diff < 0:
                degradation[metric_name] = {
                    'new': new_value,
                    'baseline': baseline_value,
                    'degradation': abs(diff),
                    'degradation_percent': (abs(diff) / baseline_value * 100) if baseline_value > 0 else 0
                }

        new_accuracy = new_model_metrics.get('accuracy', 0.0)
        baseline_accuracy = baseline_model_metrics.get('accuracy', 0.0)
        new_model_better = new_accuracy > baseline_accuracy + min_improvement_threshold

        if new_model_better:
            recommendation = "推荐切换到新模型"
        elif not degradation:
            recommendation = "新模型性能相当，可考虑切换"
        else:
            max_degradation = max([d['degradation'] for d in degradation.values()]) if degradation else 0
            if max_degradation > 0.05:
                recommendation = "不推荐切换，性能下降较多"
            else:
                recommendation = "谨慎考虑，存在性能下降"

        return ModelComparisonResult(
            new_model_better=new_model_better,
            new_metrics=new_model_metrics,
            baseline_metrics=baseline_model_metrics,
            improvements=improvements,
            degradation=degradation,
            recommendation=recommendation
        )

    def get_evaluation_history(self) -> List[Dict]:
        return self._evaluation_history.copy()

    def clear_history(self):
        self._evaluation_history = []

    def predict_proba(
        self,
        model: Any,
        vectorizer: Any,
        X: List[List[str]]
    ) -> Optional[List[np.ndarray]]:
        if model is None or vectorizer is None:
            return None

        try:
            X_vec = vectorizer.transform(X)
            probabilities = model.predict_proba(X_vec)
            return probabilities
        except Exception:
            return None

    def predict(
        self,
        model: Any,
        vectorizer: Any,
        X: List[List[str]]
    ) -> Optional[np.ndarray]:
        if model is None or vectorizer is None:
            return None

        try:
            X_vec = vectorizer.transform(X)
            predictions = model.predict(X_vec)
            return predictions
        except Exception:
            return None


evaluator = Evaluator()
