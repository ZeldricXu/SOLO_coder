from typing import Optional, Dict, Any, List
from datetime import datetime
import time as _time

from domain.models.inference import (
    AIModel,
    InferenceTask,
    InferenceStatus,
    InferenceResult,
    ModelType,
    ModelFramework,
)
from modules.edge_inference.service import EdgeInferenceService, InferenceError, InferenceErrorCode
from modules.offline_cache.service import OfflineCacheService
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class InferenceService:
    def __init__(
        self,
        edge_inference: EdgeInferenceService,
        offline_cache: OfflineCacheService,
    ):
        self._inference = edge_inference
        self._offline_cache = offline_cache

    def register_model(
        self,
        model_id: str,
        name: str,
        model_path: str,
        model_type: str = "custom",
        description: Optional[str] = None,
        version: str = "1.0.0",
        input_schema: Optional[Dict[str, Any]] = None,
        output_schema: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
    ) -> AIModel:
        model = AIModel(
            model_id=model_id,
            model_name=name,
            model_version=version,
            model_type=ModelType(model_type) if model_type in [m.value for m in ModelType] else ModelType.CUSTOM,
            framework=ModelFramework.CUSTOM,
            model_path=model_path,
            description=description,
            input_schema=input_schema or {},
            output_schema=output_schema or {},
            labels=tags or [],
        )
        return self._inference.register_model(model)

    def get_model(self, model_id: str) -> Optional[AIModel]:
        return self._inference.get_model(model_id)

    def list_models(self) -> List[AIModel]:
        return self._inference.list_models()

    def delete_model(self, model_id: str) -> bool:
        self._inference.unload_model(model_id)
        return True

    def submit_inference_task(
        self,
        model_id: str,
        input_data: Dict[str, Any],
        device_id: Optional[str] = None,
        priority: int = 0,
        callback_url: Optional[str] = None,
        use_offline_cache: bool = True,
    ) -> InferenceTask:
        task = self._inference.submit_task(
            model_id=model_id,
            input_data=input_data,
            device_id=device_id,
            priority=priority,
            callback_url=callback_url,
        )

        if not self._offline_cache._is_online and use_offline_cache:
            self._offline_cache.store_data(
                data_type="inference",
                data={
                    "task_id": task.task_id,
                    "model_id": model_id,
                    "input_data": input_data,
                    "device_id": device_id,
                },
                device_id=device_id,
                priority=priority,
            )

        return task

    def get_task(self, task_id: str) -> Optional[InferenceTask]:
        return self._inference.get_task(task_id)

    def get_task_result(self, task_id: str) -> Optional[InferenceResult]:
        return self._inference.get_task_result(task_id)

    def list_tasks(
        self,
        model_id: Optional[str] = None,
        device_id: Optional[str] = None,
        status: Optional[str] = None,
        limit: int = 100,
    ) -> List[InferenceTask]:
        return self._inference.list_tasks(
            model_id=model_id,
            device_id=device_id,
            status=status,
            limit=limit,
        )

    def cancel_task(self, task_id: str) -> bool:
        return self._inference.cancel_task(task_id)

    def get_inference_stats(self) -> Dict[str, Any]:
        return self._inference.get_stats()

    def start_inference_engine(self) -> None:
        self._inference.start()

    def stop_inference_engine(self) -> None:
        self._inference.stop()

    def run_inference_sync(
        self,
        model_id: str,
        input_data: Dict[str, Any],
        timeout: float = 30.0,
    ) -> Optional[InferenceResult]:
        task = self.submit_inference_task(model_id, input_data)
        start = _time.monotonic()

        while (_time.monotonic() - start) < timeout:
            result = self.get_task_result(task.task_id)
            if result is not None:
                return result
            _time.sleep(0.1)

        logger.warning("Sync inference timed out", extra={"task_id": task.task_id})
        return None
