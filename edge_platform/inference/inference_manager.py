import asyncio
import logging
import os
import json
import pickle
import uuid
from typing import Dict, List, Optional, Any, Callable
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
import threading
from queue import PriorityQueue
import time

from ..common.event_bus import EventBus, Event, event_bus
from ..common.config import config
from ..common.exceptions import InferenceException, ModelNotFoundException

logger = logging.getLogger(__name__)


class TaskStatus(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class ModelFormat(str, Enum):
    ONNX = "onnx"
    TENSORFLOW = "tensorflow"
    PYTORCH = "pytorch"
    TFLITE = "tflite"
    CUSTOM = "custom"


@dataclass
class AIModel:
    model_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    name: str = ""
    version: str = "1.0.0"
    format: ModelFormat = ModelFormat.ONNX
    file_path: str = ""
    input_shape: List[int] = field(default_factory=list)
    output_shape: List[int] = field(default_factory=list)
    labels: List[str] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)
    is_loaded: bool = False
    loaded_at: Optional[datetime] = None
    inference_count: int = 0
    created_at: datetime = field(default_factory=datetime.now)


@dataclass
class InferenceResult:
    result_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    task_id: str = ""
    model_id: str = ""
    predictions: List[Any] = field(default_factory=list)
    confidence_scores: List[float] = field(default_factory=list)
    inference_time_ms: float = 0.0
    raw_output: Any = None
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class InferenceTask:
    task_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    model_id: str = ""
    input_data: Any = None
    status: TaskStatus = TaskStatus.PENDING
    priority: int = 0
    result: Optional[InferenceResult] = None
    error_message: Optional[str] = None
    callback_url: Optional[str] = None
    created_at: datetime = field(default_factory=datetime.now)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    timeout_seconds: int = 300


class ModelRunner:
    def __init__(self):
        self._loaded_models: Dict[str, Any] = {}

    def load_model(self, model: AIModel) -> bool:
        try:
            self._loaded_models[model.model_id] = {
                "model": model,
                "loaded": True
            }
            model.is_loaded = True
            model.loaded_at = datetime.now()
            logger.info(f"Model {model.name} loaded successfully")
            return True
        except Exception as e:
            logger.error(f"Failed to load model {model.name}: {e}")
            return False

    def unload_model(self, model_id: str) -> None:
        if model_id in self._loaded_models:
            del self._loaded_models[model_id]
            logger.info(f"Model {model_id} unloaded")

    def run_inference(
        self,
        model: AIModel,
        input_data: Any
    ) -> InferenceResult:
        if model.model_id not in self._loaded_models:
            raise ModelNotFoundException(f"Model {model.model_id} not loaded")

        start_time = time.time()

        try:
            predictions, confidence_scores = self._simulate_inference(
                model, input_data
            )

            inference_time_ms = (time.time() - start_time) * 1000

            result = InferenceResult(
                model_id=model.model_id,
                predictions=predictions,
                confidence_scores=confidence_scores,
                inference_time_ms=inference_time_ms,
                raw_output={
                    "input_size": len(str(input_data)),
                    "model_version": model.version
                }
            )

            model.inference_count += 1
            return result

        except Exception as e:
            raise InferenceException(f"Inference failed: {e}")

    def _simulate_inference(
        self,
        model: AIModel,
        input_data: Any
    ) -> tuple:
        import random
        time.sleep(0.05)

        if model.labels:
            num_classes = min(len(model.labels), 5)
            predictions = random.sample(model.labels, num_classes)
            confidence_scores = [
                round(random.uniform(0.5, 0.99), 4)
                for _ in range(num_classes)
            ]
        else:
            predictions = [f"class_{i}" for i in range(3)]
            confidence_scores = [
                round(random.uniform(0.5, 0.99), 4)
                for _ in range(3)
            ]

        return predictions, confidence_scores

    def is_model_loaded(self, model_id: str) -> bool:
        return model_id in self._loaded_models


class ResultPublisher:
    def __init__(self, event_bus_instance: EventBus):
        self._event_bus = event_bus_instance

    async def publish_result(
        self,
        task: InferenceTask,
        result: InferenceResult
    ) -> None:
        self._event_bus.publish(Event(
            event_type="inference.result.ready",
            source="inference",
            payload={
                "task_id": task.task_id,
                "model_id": task.model_id,
                "result_id": result.result_id,
                "predictions": result.predictions,
                "inference_time_ms": result.inference_time_ms
            }
        ))

        if task.callback_url:
            asyncio.create_task(self._send_webhook(task.callback_url, result))

    async def _send_webhook(self, url: str, result: InferenceResult) -> None:
        try:
            import requests
            payload = {
                "result_id": result.result_id,
                "predictions": result.predictions,
                "confidence_scores": result.confidence_scores,
                "inference_time_ms": result.inference_time_ms
            }
            await asyncio.to_thread(
                requests.post,
                url,
                json=payload,
                timeout=10
            )
        except Exception as e:
            logger.error(f"Webhook notification failed: {e}")


class InferenceManager:
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus
        self._models: Dict[str, AIModel] = {}
        self._tasks: Dict[str, InferenceTask] = {}
        self._task_queue: PriorityQueue = PriorityQueue()
        self._model_runner = ModelRunner()
        self._result_publisher = ResultPublisher(self._event_bus)
        self._max_concurrent_tasks = config.get("inference.max_concurrent_tasks", 4)
        self._model_path = config.get("inference.model_path", "./models")
        self._running_tasks: Dict[str, asyncio.Task] = {}
        self._is_running = False
        self._worker_task: Optional[asyncio.Task] = None
        self._lock = threading.RLock()
        self._result_callbacks: Dict[str, Callable[[InferenceResult], None]] = {}

        os.makedirs(self._model_path, exist_ok=True)

    def register_model(
        self,
        name: str,
        file_path: str,
        model_format: ModelFormat = ModelFormat.ONNX,
        version: str = "1.0.0",
        input_shape: Optional[List[int]] = None,
        output_shape: Optional[List[int]] = None,
        labels: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None
    ) -> AIModel:
        model = AIModel(
            name=name,
            version=version,
            format=model_format,
            file_path=file_path,
            input_shape=input_shape or [],
            output_shape=output_shape or [],
            labels=labels or [],
            metadata=metadata or {}
        )

        with self._lock:
            self._models[model.model_id] = model

        self._event_bus.publish(Event(
            event_type="inference.model.registered",
            source="inference",
            payload={
                "model_id": model.model_id,
                "name": name,
                "version": version
            }
        ))

        logger.info(f"Registered model {name} ({model.model_id})")
        return model

    def load_model(self, model_id: str) -> bool:
        model = self._get_model(model_id)

        if model.is_loaded:
            return True

        success = self._model_runner.load_model(model)

        if success:
            self._event_bus.publish(Event(
                event_type="inference.model.loaded",
                source="inference",
                payload={"model_id": model_id}
            ))

        return success

    def unload_model(self, model_id: str) -> None:
        model = self._get_model(model_id)
        self._model_runner.unload_model(model_id)
        model.is_loaded = False

        self._event_bus.publish(Event(
            event_type="inference.model.unloaded",
            source="inference",
            payload={"model_id": model_id}
        ))

    def _get_model(self, model_id: str) -> AIModel:
        model = self._models.get(model_id)
        if not model:
            raise ModelNotFoundException(f"Model {model_id} not found")
        return model

    def get_model(self, model_id: str) -> AIModel:
        return self._get_model(model_id)

    def list_models(self, loaded_only: bool = False) -> List[AIModel]:
        with self._lock:
            models = list(self._models.values())

        if loaded_only:
            models = [m for m in models if m.is_loaded]

        return models

    def delete_model(self, model_id: str) -> None:
        model = self._get_model(model_id)

        if model.is_loaded:
            self.unload_model(model_id)

        with self._lock:
            del self._models[model_id]

        self._event_bus.publish(Event(
            event_type="inference.model.deleted",
            source="inference",
            payload={"model_id": model_id}
        ))

    async def submit_inference_task(
        self,
        model_id: str,
        input_data: Any,
        priority: int = 0,
        callback_url: Optional[str] = None,
        timeout_seconds: int = 300
    ) -> InferenceTask:
        model = self._get_model(model_id)

        if not model.is_loaded:
            if not self.load_model(model_id):
                raise InferenceException(f"Failed to load model {model_id}")

        task = InferenceTask(
            model_id=model_id,
            input_data=input_data,
            priority=priority,
            callback_url=callback_url,
            timeout_seconds=timeout_seconds,
            status=TaskStatus.QUEUED
        )

        with self._lock:
            self._tasks[task.task_id] = task

        await self._task_queue.put((-priority, task.task_id))

        self._event_bus.publish(Event(
            event_type="inference.task.queued",
            source="inference",
            payload={
                "task_id": task.task_id,
                "model_id": model_id,
                "priority": priority
            }
        ))

        return task

    def get_task(self, task_id: str) -> InferenceTask:
        task = self._tasks.get(task_id)
        if not task:
            raise InferenceException(f"Task {task_id} not found")
        return task

    def list_tasks(
        self,
        status: Optional[TaskStatus] = None,
        model_id: Optional[str] = None,
        limit: int = 100
    ) -> List[InferenceTask]:
        with self._lock:
            tasks = list(self._tasks.values())

        if status:
            tasks = [t for t in tasks if t.status == status]
        if model_id:
            tasks = [t for t in tasks if t.model_id == model_id]

        tasks.sort(key=lambda t: t.created_at, reverse=True)
        return tasks[:limit]

    async def cancel_task(self, task_id: str) -> InferenceTask:
        task = self.get_task(task_id)

        if task.status in [TaskStatus.QUEUED, TaskStatus.PENDING]:
            task.status = TaskStatus.CANCELLED
        elif task.status == TaskStatus.RUNNING:
            if task_id in self._running_tasks:
                self._running_tasks[task_id].cancel()
                task.status = TaskStatus.CANCELLED

        self._event_bus.publish(Event(
            event_type="inference.task.cancelled",
            source="inference",
            payload={"task_id": task_id}
        ))

        return task

    async def _execute_task(self, task: InferenceTask) -> None:
        task.status = TaskStatus.RUNNING
        task.started_at = datetime.now()

        try:
            model = self._get_model(task.model_id)

            result = await asyncio.wait_for(
                asyncio.to_thread(
                    self._model_runner.run_inference,
                    model,
                    task.input_data
                ),
                timeout=task.timeout_seconds
            )

            result.task_id = task.task_id
            task.result = result
            task.status = TaskStatus.COMPLETED
            task.completed_at = datetime.now()

            await self._result_publisher.publish_result(task, result)

            if task.task_id in self._result_callbacks:
                try:
                    self._result_callbacks[task.task_id](result)
                except Exception as e:
                    logger.error(f"Result callback error: {e}")

            self._event_bus.publish(Event(
                event_type="inference.task.completed",
                source="inference",
                payload={
                    "task_id": task.task_id,
                    "model_id": task.model_id,
                    "inference_time_ms": result.inference_time_ms
                }
            ))

        except asyncio.TimeoutError:
            task.status = TaskStatus.FAILED
            task.error_message = f"Task timed out after {task.timeout_seconds}s"
            task.completed_at = datetime.now()

        except Exception as e:
            task.status = TaskStatus.FAILED
            task.error_message = str(e)
            task.completed_at = datetime.now()

            self._event_bus.publish(Event(
                event_type="inference.task.failed",
                source="inference",
                payload={
                    "task_id": task.task_id,
                    "error": str(e)
                }
            ))

        finally:
            self._running_tasks.pop(task.task_id, None)

    async def _worker(self) -> None:
        while self._is_running:
            try:
                if len(self._running_tasks) >= self._max_concurrent_tasks:
                    await asyncio.sleep(0.1)
                    continue

                try:
                    _, task_id = self._task_queue.get_nowait()
                except:
                    await asyncio.sleep(0.1)
                    continue

                with self._lock:
                    task = self._tasks.get(task_id)

                if task and task.status == TaskStatus.QUEUED:
                    running_task = asyncio.create_task(self._execute_task(task))
                    self._running_tasks[task_id] = running_task

                self._task_queue.task_done()

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in inference worker: {e}")

    async def start(self) -> None:
        if self._is_running:
            return
        self._is_running = True
        self._worker_task = asyncio.create_task(self._worker())
        logger.info("Inference manager started")

    async def stop(self) -> None:
        self._is_running = False
        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass

        for running_task in self._running_tasks.values():
            running_task.cancel()

        logger.info("Inference manager stopped")

    def on_result(self, task_id: str, callback: Callable[[InferenceResult], None]) -> None:
        self._result_callbacks[task_id] = callback

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total_models = len(self._models)
            loaded_models = sum(1 for m in self._models.values() if m.is_loaded)
            total_tasks = len(self._tasks)
            total_inferences = sum(m.inference_count for m in self._models.values())

            by_status = {}
            for status in TaskStatus:
                by_status[status.value] = sum(
                    1 for t in self._tasks.values() if t.status == status
                )

        return {
            "total_models": total_models,
            "loaded_models": loaded_models,
            "total_tasks": total_tasks,
            "total_inferences": total_inferences,
            "tasks_by_status": by_status,
            "running_tasks": len(self._running_tasks),
            "queued_tasks": self._task_queue.qsize()
        }
