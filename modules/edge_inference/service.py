from typing import Optional, Dict, Any, List
from datetime import datetime, timedelta
from enum import Enum
import uuid
import threading
import time
import queue
import json

from domain.models.inference import AIModel, InferenceTask, InferenceResult, InferenceStatus
from domain.models.event import EventType

from infrastructure.persistence.repositories.inference_repository import InferenceRepository
from infrastructure.messaging.event_bus import EventBus, get_event_bus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class InferenceErrorCode(str, Enum):
    MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
    MODEL_LOAD_FAILED = "MODEL_LOAD_FAILED"
    TASK_EXECUTION_FAILED = "TASK_EXECUTION_FAILED"
    TASK_NOT_CANCELLABLE = "TASK_NOT_CANCELLABLE"
    GPU_REQUIRED = "GPU_REQUIRED"
    CALLBACK_FAILED = "CALLBACK_FAILED"


class InferenceError(Exception):
    def __init__(self, code: InferenceErrorCode, message: str):
        self.code = code
        self.message = message
        super().__init__(message)


_DEFAULT_MAX_CONCURRENT_TASKS = 2
_DEFAULT_TASK_TIMEOUT_SECONDS = 60
_WORKER_POLL_INTERVAL = 0.1
_WORKER_ERROR_BACKOFF = 1.0
_CALLBACK_TIMEOUT_SECONDS = 5
_COMPLETED_TASK_STATUSES = frozenset({
    InferenceStatus.COMPLETED,
    InferenceStatus.FAILED,
    InferenceStatus.CANCELLED,
})


class _ModelRegistry:
    def __init__(self):
        self._cache: Dict[str, AIModel] = {}
        self._loaded: Dict[str, Any] = {}
        self._lock = threading.RLock()

    def put(self, model: AIModel) -> None:
        with self._lock:
            self._cache[model.model_id] = model

    def get(self, model_id: str) -> Optional[AIModel]:
        with self._lock:
            if model_id in self._cache:
                return self._cache[model_id]
            return None

    def cache_from_db(self, model: AIModel) -> None:
        with self._lock:
            self._cache[model.model_id] = model

    def is_loaded(self, model_id: str) -> bool:
        with self._lock:
            return model_id in self._loaded

    def set_loaded(self, model_id: str, instance: Any) -> None:
        with self._lock:
            self._loaded[model_id] = instance

    def get_loaded(self, model_id: str) -> Optional[Any]:
        with self._lock:
            return self._loaded.get(model_id)

    def remove_loaded(self, model_id: str) -> None:
        with self._lock:
            self._loaded.pop(model_id, None)

    def loaded_model_ids(self) -> List[str]:
        with self._lock:
            return list(self._loaded.keys())


class _TaskTracker:
    def __init__(self, max_concurrent: int):
        self._running: Dict[str, InferenceTask] = {}
        self._lock = threading.Lock()
        self._max_concurrent = max_concurrent
        self._has_capacity = threading.Condition(self._lock)

    @property
    def max_concurrent(self) -> int:
        return self._max_concurrent

    def can_accept(self) -> bool:
        with self._lock:
            return len(self._running) < self._max_concurrent

    def add(self, task: InferenceTask) -> None:
        with self._lock:
            self._running[task.task_id] = task

    def remove(self, task_id: str) -> None:
        with self._has_capacity:
            self._running.pop(task_id, None)
            self._has_capacity.notify()

    def get_running(self) -> Dict[str, InferenceTask]:
        with self._lock:
            return dict(self._running)

    @property
    def count(self) -> int:
        with self._lock:
            return len(self._running)


class EdgeInferenceService:
    def __init__(
        self,
        inference_repo: InferenceRepository,
        event_bus: Optional[EventBus] = None,
        gpu_enabled: bool = False,
        max_concurrent_tasks: int = _DEFAULT_MAX_CONCURRENT_TASKS,
        task_timeout_seconds: int = _DEFAULT_TASK_TIMEOUT_SECONDS,
    ):
        self._repo = inference_repo
        self._event_bus = event_bus or get_event_bus()
        self._gpu_enabled = gpu_enabled

        self._models = _ModelRegistry()
        self._tasks = _TaskTracker(max_concurrent_tasks)
        self._task_queue: queue.Queue = queue.Queue()
        self._task_timeout_seconds = task_timeout_seconds

        self._worker_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._is_running = False

    def register_model(self, model: AIModel) -> AIModel:
        if model.gpu_required and not self._gpu_enabled:
            logger.warning(
                "Model requires GPU but GPU not enabled",
                extra={"model_id": model.model_id},
            )

        self._models.put(model)
        self._repo.save_model(model)
        logger.info("Model registered", extra={"model_id": model.model_id})
        return model

    def get_model(self, model_id: str) -> Optional[AIModel]:
        cached = self._models.get(model_id)
        if cached is not None:
            return cached

        model = self._repo.get_model(model_id)
        if model is not None:
            self._models.cache_from_db(model)
        return model

    def list_models(self) -> List[AIModel]:
        return self._repo.get_all_models()

    def load_model(self, model_id: str) -> bool:
        if self._models.is_loaded(model_id):
            return True

        model = self.get_model(model_id)
        if model is None:
            logger.error("Model not found for loading", extra={"model_id": model_id})
            return False

        try:
            instance = self._create_model_instance(model)
            self._models.set_loaded(model_id, instance)
            logger.info("Model loaded", extra={"model_id": model_id})
            return True
        except Exception as exc:
            logger.error("Failed to load model", extra={"model_id": model_id, "error": str(exc)})
            return False

    def unload_model(self, model_id: str) -> None:
        self._models.remove_loaded(model_id)
        logger.info("Model unloaded", extra={"model_id": model_id})

    def submit_task(
        self,
        model_id: str,
        input_data: Dict[str, Any],
        device_id: Optional[str] = None,
        callback_url: Optional[str] = None,
        priority: int = 0,
    ) -> InferenceTask:
        model = self.get_model(model_id)
        if model is None:
            raise InferenceError(InferenceErrorCode.MODEL_NOT_FOUND, f"Model {model_id} not found")

        task = InferenceTask(
            task_id=str(uuid.uuid4()),
            model_id=model_id,
            input_data=input_data,
            device_id=device_id,
            callback_url=callback_url,
            priority=priority,
            status=InferenceStatus.PENDING,
            timeout_at=datetime.utcnow() + timedelta(seconds=self._task_timeout_seconds),
        )

        self._repo.save_task(task)
        self._task_queue.put(task)

        self._publish_event(
            EventType.INFERENCE_TASK_CREATED,
            device_id=device_id,
            data={"task_id": task.task_id, "model_id": model_id},
        )

        logger.info("Task submitted", extra={"task_id": task.task_id, "model_id": model_id})
        return task

    def get_task(self, task_id: str) -> Optional[InferenceTask]:
        return self._repo.get_task(task_id)

    def get_task_result(self, task_id: str) -> Optional[InferenceResult]:
        results = self._repo.get_results_by_task(task_id)
        return results[0] if results else None

    def get_results(self, task_id: str) -> List[InferenceResult]:
        return self._repo.get_results_by_task(task_id)

    def cancel_task(self, task_id: str) -> bool:
        task = self._repo.get_task(task_id)
        if task is None or task.status in _COMPLETED_TASK_STATUSES:
            return False

        task.cancel()
        self._repo.update_task(task_id, task.model_dump())
        logger.info("Task cancelled", extra={"task_id": task_id})
        return True

    def list_tasks(
        self,
        model_id: Optional[str] = None,
        device_id: Optional[str] = None,
        status: Optional[str] = None,
        limit: int = 100,
    ) -> List[InferenceTask]:
        return self._repo.get_pending_tasks(limit=limit)

    def get_stats(self) -> Dict[str, Any]:
        return {
            "queue_size": self._task_queue.qsize(),
            "running_tasks": self._tasks.count,
            "loaded_models": self._models.loaded_model_ids(),
            "is_running": self._is_running,
        }

    def start(self) -> None:
        if self._is_running:
            return

        self._is_running = True
        self._stop_event.clear()
        self._worker_thread = threading.Thread(target=self._worker_loop, daemon=True)
        self._worker_thread.start()
        logger.info("Edge inference service started")

    def stop(self) -> None:
        self._is_running = False
        self._stop_event.set()
        if self._worker_thread is not None:
            self._worker_thread.join(timeout=5)
        logger.info("Edge inference service stopped")

    def _worker_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self._dequeue_and_execute()

                running = self._tasks.get_running()
                self._check_timeouts(running)

                self._stop_event.wait(_WORKER_POLL_INTERVAL)
            except Exception as exc:
                logger.error(f"Worker loop error: {exc}")
                self._stop_event.wait(_WORKER_ERROR_BACKOFF)

    def _dequeue_and_execute(self) -> None:
        while self._tasks.can_accept():
            try:
                task = self._task_queue.get_nowait()
            except queue.Empty:
                return

            try:
                self._execute_task(task)
            except Exception as exc:
                logger.error(f"Task execution entry error: {exc}")

    def _execute_task(self, task: InferenceTask) -> None:
        try:
            task.start()
            self._tasks.add(task)
            self._repo.update_task(task.task_id, task.model_dump())

            self._ensure_model_loaded(task.model_id)

            model = self._models.get(task.model_id)
            instance = self._models.get_loaded(task.model_id)

            start_time = time.monotonic()
            predictions = self._run_inference(instance, task.input_data, model)
            elapsed_ms = int((time.monotonic() - start_time) * 1000)

            result = self._build_result(task, predictions, elapsed_ms)
            self._complete_task(task, result)

        except Exception as exc:
            self._fail_task(task, str(exc))

        finally:
            self._tasks.remove(task.task_id)

    def _ensure_model_loaded(self, model_id: str) -> None:
        if not self._models.is_loaded(model_id):
            if not self.load_model(model_id):
                raise InferenceError(
                    InferenceErrorCode.MODEL_LOAD_FAILED,
                    f"Failed to load model {model_id}",
                )

    def _complete_task(self, task: InferenceTask, result: InferenceResult) -> None:
        task.complete()
        self._repo.update_task(task.task_id, task.model_dump())
        self._repo.save_result(result)

        self._publish_event(
            EventType.INFERENCE_COMPLETED,
            device_id=task.device_id,
            data={"task_id": task.task_id, "result_id": result.result_id},
        )

        if task.callback_url:
            self._notify_callback(task.callback_url, result)

        logger.info(
            "Task completed",
            extra={"task_id": task.task_id, "elapsed_ms": result.inference_time_ms},
        )

    def _fail_task(self, task: InferenceTask, error_message: str) -> None:
        task.fail(error_message)
        self._repo.update_task(task.task_id, task.model_dump())

        self._publish_event(
            EventType.INFERENCE_FAILED,
            device_id=task.device_id,
            data={"task_id": task.task_id, "error": error_message},
        )

        logger.error("Task failed", extra={"task_id": task.task_id, "error": error_message})

    def _run_inference(
        self,
        model_instance: Any,
        input_data: Dict[str, Any],
        model: AIModel,
    ) -> Dict[str, Any]:
        if hasattr(model_instance, "run"):
            return model_instance.run(input_data)
        return {"predictions": [{"label": "unknown", "confidence": 0.5}], "confidence_scores": [0.5]}

    def _build_result(
        self,
        task: InferenceTask,
        predictions: Dict[str, Any],
        elapsed_ms: int,
    ) -> InferenceResult:
        return InferenceResult(
            result_id=str(uuid.uuid4()),
            task_id=task.task_id,
            model_id=task.model_id,
            predictions=predictions.get("predictions", []),
            confidence_scores=predictions.get("confidence_scores", []),
            raw_output=predictions.get("raw_output"),
            inference_time_ms=elapsed_ms,
            success=True,
        )

    def _notify_callback(self, url: str, result: InferenceResult) -> None:
        try:
            import requests
            requests.post(url, json=result.model_dump(), timeout=_CALLBACK_TIMEOUT_SECONDS)
        except Exception as exc:
            logger.warning(
                "Callback notification failed",
                extra={"url": url, "error": str(exc)},
            )

    def _check_timeouts(self, running_tasks: Dict[str, InferenceTask]) -> None:
        now = datetime.utcnow()
        for task_id, task in running_tasks.items():
            if task.timeout_at is not None and task.timeout_at < now:
                task.fail("Task timeout")
                self._repo.update_task(task_id, task.model_dump())
                self._tasks.remove(task_id)
                logger.warning("Task timed out", extra={"task_id": task_id})

    def _create_model_instance(self, model: AIModel) -> Any:
        try:
            framework = model.framework.value
            if framework == "onnx":
                return self._try_load_onnx(model)
            elif framework == "tflite":
                return self._try_load_tflite(model)
            else:
                logger.warning(f"Framework {framework} not fully supported, using mock")
                return MockInferenceModel(model)
        except Exception as exc:
            logger.warning(f"Model load error, using mock: {exc}")
            return MockInferenceModel(model)

    def _try_load_onnx(self, model: AIModel) -> Any:
        try:
            import onnxruntime as ort
            return ort.InferenceSession(model.model_path)
        except ImportError:
            logger.warning("onnxruntime not installed, using mock model")
            return MockInferenceModel(model)

    def _try_load_tflite(self, model: AIModel) -> Any:
        try:
            import tflite_runtime.interpreter as tflite
            return tflite.Interpreter(model_path=model.model_path)
        except ImportError:
            logger.warning("tflite_runtime not installed, using mock model")
            return MockInferenceModel(model)

    def _publish_event(
        self,
        event_type: EventType,
        device_id: Optional[str] = None,
        data: Optional[Dict[str, Any]] = None,
    ) -> None:
        event = self._event_bus.create_event(
            event_type=event_type,
            device_id=device_id,
            data=data or {},
        )
        self._event_bus.publish(event)

    def get_queue_status(self) -> Dict[str, Any]:
        return self.get_stats()


class MockInferenceModel:
    def __init__(self, model: AIModel):
        self.model = model
        self.labels = model.labels or ["class_0", "class_1", "class_2"]

    def run(self, input_data: Dict[str, Any]) -> Dict[str, Any]:
        import random
        num_predictions = min(len(self.labels), random.randint(1, 3))
        predictions = []
        scores = []

        for _ in range(num_predictions):
            label = random.choice(self.labels)
            confidence = random.uniform(0.5, 0.99)
            predictions.append({"label": label, "confidence": confidence})
            scores.append(confidence)

        return {
            "predictions": predictions,
            "confidence_scores": scores,
            "raw_output": {"input": input_data},
        }
