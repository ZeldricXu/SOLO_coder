from typing import Dict, List, Optional, Any, Callable
from datetime import datetime
import threading
import time
import json
import base64
import numpy as np
from dataclasses import dataclass, field
from concurrent.futures import Future
import queue

from .models import (
    ModelVersion,
    generate_id,
    BatchingConfig,
    Model
)
from .model_manager import model_manager
from .version_manager import version_manager
from .monitoring_manager import monitoring_manager
from ..storage import file_store
from ...config import Config


@dataclass
class BatchingRequest:
    request_id: str
    model_id: str
    version: str
    input_data: Any
    input_str: str
    future: Future = field(default_factory=Future)
    received_time: float = field(default_factory=time.time)

    def set_result(self, result: Dict[str, Any]):
        self.future.set_result(result)

    def set_exception(self, exception: Exception):
        self.future.set_exception(exception)

    def result(self, timeout: Optional[float] = None) -> Dict[str, Any]:
        return self.future.result(timeout=timeout)


@dataclass
class BatchingBatch:
    model_id: str
    version: str
    requests: List[BatchingRequest] = field(default_factory=list)
    batch_start_time: float = field(default_factory=time.time)
    batch_size: int = 0

    def add_request(self, request: BatchingRequest) -> bool:
        self.requests.append(request)
        self.batch_size = len(self.requests)
        return True


class ModelBatchingEngine(threading.Thread):
    def __init__(
        self,
        model_id: str,
        version: str,
        inference_engine: 'ModelInferenceEngine',
        batching_config: BatchingConfig
    ):
        super().__init__(daemon=True, name=f"BatchingEngine-{model_id}-{version}")
        self.model_id = model_id
        self.version = version
        self._inference_engine = inference_engine
        self._config = batching_config

        self._batch_size = batching_config.max_batch_size
        self._batch_timeout = batching_config.batch_timeout_ms / 1000.0
        self._max_queue_size = batching_config.max_queue_size

        self._request_queue: queue.Queue = queue.Queue(maxsize=self._max_queue_size)
        self._running = threading.Event()
        self._running.set()
        self._lock = threading.Lock()
        self._config_lock = threading.Lock()

        self._total_batches = 0
        self._total_requests = 0
        self._total_inference_time = 0.0

    def stop(self):
        self._running.clear()

    def update_config(self, new_config: BatchingConfig):
        with self._config_lock:
            self._config = new_config
            self._batch_size = new_config.max_batch_size
            self._batch_timeout = new_config.batch_timeout_ms / 1000.0
            self._max_queue_size = new_config.max_queue_size

    def get_current_config(self) -> BatchingConfig:
        with self._config_lock:
            return BatchingConfig(
                enable_batching=self._config.enable_batching,
                batch_timeout_ms=self._config.batch_timeout_ms,
                max_batch_size=self._config.max_batch_size,
                max_queue_size=self._config.max_queue_size
            )

    def submit_request(
        self,
        model_id: str,
        version: str,
        input_data: Any,
        input_str: str
    ) -> BatchingRequest:
        request = BatchingRequest(
            request_id=generate_id("batch_req"),
            model_id=model_id,
            version=version,
            input_data=input_data,
            input_str=input_str
        )

        try:
            self._request_queue.put_nowait(request)
        except queue.Full:
            request.set_result({
                "success": False,
                "error": "Batching queue is full",
                "inference_time_ms": 0.0
            })

        return request

    def _collect_batch(self) -> Optional[BatchingBatch]:
        batch = BatchingBatch(model_id=self.model_id, version=self.version)
        start_time = time.time()

        with self._config_lock:
            current_batch_size = self._batch_size
            current_batch_timeout = self._batch_timeout

        while len(batch.requests) < current_batch_size:
            try:
                remaining_timeout = current_batch_timeout - (time.time() - start_time)
                if remaining_timeout <= 0:
                    break

                request = self._request_queue.get(timeout=remaining_timeout)
                batch.add_request(request)
                self._request_queue.task_done()

            except queue.Empty:
                break

        if batch.batch_size > 0:
            return batch
        return None

    def _execute_batch(self, batch: BatchingBatch) -> List[Dict[str, Any]]:
        if batch.batch_size == 0:
            return []

        inputs = [req.input_data for req in batch.requests]

        start_inference = time.time()

        try:
            results = self._inference_engine.batch_infer(inputs)
        except Exception as e:
            error_result = {
                "success": False,
                "error": str(e),
                "inference_time_ms": (time.time() - start_inference) * 1000
            }
            return [error_result] * batch.batch_size

        inference_time_ms = (time.time() - start_inference) * 1000

        for i, result in enumerate(results):
            if "inference_time_ms" not in result:
                result["inference_time_ms"] = inference_time_ms / batch.batch_size

        with self._lock:
            self._total_batches += 1
            self._total_requests += batch.batch_size
            self._total_inference_time += inference_time_ms

        return results

    def run(self):
        while self._running.is_set():
            try:
                batch = self._collect_batch()
                if batch is None:
                    continue

                results = self._execute_batch(batch)

                for i, request in enumerate(batch.requests):
                    if i < len(results):
                        request.set_result(results[i])
                    else:
                        request.set_result({
                            "success": False,
                            "error": "No result returned",
                            "inference_time_ms": 0.0
                        })

            except Exception as e:
                print(f"Error in batching engine {self.model_id}:{self.version}: {e}")

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            current_config = self.get_current_config()
            return {
                "model_id": self.model_id,
                "version": self.version,
                "total_batches": self._total_batches,
                "total_requests": self._total_requests,
                "total_inference_time_ms": self._total_inference_time,
                "avg_batch_size": self._total_requests / self._total_batches if self._total_batches > 0 else 0,
                "queue_size": self._request_queue.qsize(),
                "max_queue_size": self._max_queue_size,
                "current_config": {
                    "enable_batching": current_config.enable_batching,
                    "batch_timeout_ms": current_config.batch_timeout_ms,
                    "max_batch_size": current_config.max_batch_size,
                    "max_queue_size": current_config.max_queue_size
                }
            }


class ModelInferenceEngine:
    def __init__(self, model_id: str, version: str):
        self.model_id = model_id
        self.version = version
        self.version_info: Optional[ModelVersion] = None
        self.model_loaded = False
        self._lock = threading.Lock()
        self._model_instance = None
        self._framework_adapter: Optional['FrameworkAdapter'] = None

    def load_model(self) -> bool:
        if self.model_loaded:
            return True

        with self._lock:
            if self.model_loaded:
                return True

            version_info = version_manager.get_version_by_model_and_version(
                self.model_id, self.version
            )
            if not version_info:
                print(f"Version {self.version} not found for model {self.model_id}")
                return False

            self.version_info = version_info

            model_file_path = file_store.get_model_file_path(
                self.model_id, self.version, version_info.model_file
            )
            if not model_file_path:
                print(f"Model file not found: {version_info.model_file}")
                return False

            model = model_manager.get_model(self.model_id)
            if not model:
                return False

            self._framework_adapter = get_framework_adapter(model.framework)
            if not self._framework_adapter:
                print(f"Unsupported framework: {model.framework}")
                return False

            try:
                self._model_instance = self._framework_adapter.load_model(model_file_path)
                self.model_loaded = True
                print(f"Model {self.model_id} version {self.version} loaded successfully")
                return True
            except Exception as e:
                print(f"Error loading model: {e}")
                return False

    def preprocess(self, input_data: Any) -> Any:
        if self._framework_adapter and self.model_loaded:
            return self._framework_adapter.preprocess(input_data)
        return input_data

    def run_inference(self, processed_input: Any) -> Any:
        if not self.model_loaded or not self._framework_adapter:
            raise RuntimeError("Model not loaded")
        return self._framework_adapter.run_inference(self._model_instance, processed_input)

    def postprocess(self, output: Any) -> Any:
        if self._framework_adapter and self.model_loaded:
            return self._framework_adapter.postprocess(output)
        return output

    def infer(self, input_data: Any) -> Dict[str, Any]:
        if not self.model_loaded:
            if not self.load_model():
                raise RuntimeError("Failed to load model")

        start_time = time.time()

        try:
            processed = self.preprocess(input_data)
            output = self.run_inference(processed)
            result = self.postprocess(output)

            inference_time = (time.time() - start_time) * 1000

            return {
                "success": True,
                "result": result,
                "inference_time_ms": inference_time
            }
        except Exception as e:
            inference_time = (time.time() - start_time) * 1000
            return {
                "success": False,
                "error": str(e),
                "inference_time_ms": inference_time
            }

    def batch_infer(self, inputs: List[Any]) -> List[Dict[str, Any]]:
        if not self.model_loaded:
            if not self.load_model():
                raise RuntimeError("Failed to load model")

        results = []
        start_time = time.time()

        try:
            processed_batch = [self.preprocess(inp) for inp in inputs]

            if self._framework_adapter and hasattr(self._framework_adapter, 'run_batch_inference'):
                outputs = self._framework_adapter.run_batch_inference(
                    self._model_instance, processed_batch
                )
            else:
                outputs = [self.run_inference(p) for p in processed_batch]

            total_time = (time.time() - start_time) * 1000

            for i, output in enumerate(outputs):
                try:
                    result = self.postprocess(output)
                    per_item_time = total_time / len(inputs)
                    results.append({
                        "success": True,
                        "result": result,
                        "inference_time_ms": per_item_time,
                        "index": i
                    })
                except Exception as e:
                    results.append({
                        "success": False,
                        "error": str(e),
                        "index": i
                    })

        except Exception as e:
            total_time = (time.time() - start_time) * 1000
            for i in range(len(inputs)):
                results.append({
                    "success": False,
                    "error": str(e),
                    "index": i,
                    "inference_time_ms": total_time / len(inputs) if inputs else 0
                })

        return results

    def unload(self):
        with self._lock:
            if self._model_instance and self._framework_adapter:
                if hasattr(self._framework_adapter, 'unload_model'):
                    self._framework_adapter.unload_model(self._model_instance)
            self._model_instance = None
            self.model_loaded = False
            print(f"Model {self.model_id} version {self.version} unloaded")


class FrameworkAdapter:
    def load_model(self, model_path: str) -> Any:
        raise NotImplementedError

    def preprocess(self, input_data: Any) -> Any:
        return input_data

    def run_inference(self, model: Any, processed_input: Any) -> Any:
        raise NotImplementedError

    def postprocess(self, output: Any) -> Any:
        return output

    def unload_model(self, model: Any):
        pass


class MockFrameworkAdapter(FrameworkAdapter):
    def load_model(self, model_path: str) -> Any:
        return {"loaded": True, "path": model_path}

    def preprocess(self, input_data: Any) -> Any:
        if isinstance(input_data, str):
            try:
                if input_data.startswith('data:'):
                    comma_idx = input_data.find(',')
                    if comma_idx > 0:
                        input_data = input_data[comma_idx + 1:]

                try:
                    decoded = base64.b64decode(input_data)
                    try:
                        return np.frombuffer(decoded, dtype=np.float32).reshape(-1, 3, 224, 224)
                    except:
                        return np.array([decoded])
                except:
                    return np.array([float(x) for x in input_data.split(',')])
            except:
                return np.array([input_data])
        elif isinstance(input_data, (list, tuple)):
            return np.array(input_data)
        elif isinstance(input_data, dict):
            return input_data
        return input_data

    def run_inference(self, model: Any, processed_input: Any) -> Any:
        if isinstance(processed_input, np.ndarray):
            batch_size = processed_input.shape[0] if processed_input.ndim > 0 else 1
            classes = ["cat", "dog", "bird", "fish", "car", "flower"]
            results = []
            for _ in range(batch_size):
                import random
                class_idx = random.randint(0, len(classes) - 1)
                confidence = random.uniform(0.7, 0.99)
                results.append({
                    "class": classes[class_idx],
                    "confidence": round(confidence, 4),
                    "all_scores": {c: round(random.uniform(0.01, 0.9), 4) for c in classes}
                })
            return results[0] if batch_size == 1 else results
        elif isinstance(processed_input, dict):
            return {"status": "processed", "input_keys": list(processed_input.keys())}
        else:
            return {"result": "inference_completed", "input_type": str(type(processed_input))}

    def postprocess(self, output: Any) -> Any:
        return output


class InferenceServiceManager:
    def __init__(
        self,
        enable_batching: bool = True,
        default_batch_size: int = 32,
        default_batch_timeout_ms: float = 100.0,
        max_queue_size: int = 10000
    ):
        self._engines: Dict[str, ModelInferenceEngine] = {}
        self._batching_engines: Dict[str, ModelBatchingEngine] = {}
        self._model_configs: Dict[str, BatchingConfig] = {}
        self._lock = threading.Lock()
        self._config_lock = threading.Lock()

        self._global_enable_batching = enable_batching
        self._default_batch_size = default_batch_size
        self._default_batch_timeout_ms = default_batch_timeout_ms
        self._default_max_queue_size = max_queue_size

    def _get_engine_key(self, model_id: str, version: str) -> str:
        return f"{model_id}:{version}"

    def _get_or_create_batching_config(self, model_id: str, model_type: Optional[str] = None) -> BatchingConfig:
        with self._config_lock:
            if model_id in self._model_configs:
                return self._model_configs[model_id]

            model = model_manager.get_model(model_id)
            if model:
                if hasattr(model, 'batching_config') and model.batching_config:
                    self._model_configs[model_id] = model.batching_config
                    return model.batching_config
                else:
                    config = BatchingConfig.get_default_for_model_type(model.model_type)
                    self._model_configs[model_id] = config
                    return config

            if model_type:
                config = BatchingConfig.get_default_for_model_type(model_type)
            else:
                config = BatchingConfig(
                    enable_batching=self._global_enable_batching,
                    batch_timeout_ms=self._default_batch_timeout_ms,
                    max_batch_size=self._default_batch_size,
                    max_queue_size=self._default_max_queue_size
                )

            self._model_configs[model_id] = config
            return config

    def update_model_batching_config(self, model_id: str, config_updates: Dict) -> bool:
        with self._config_lock:
            if model_id in self._model_configs:
                current_config = self._model_configs[model_id]
            else:
                current_config = self._get_or_create_batching_config(model_id)

            enable_batching = config_updates.get('enable_batching', current_config.enable_batching)
            batch_timeout_ms = config_updates.get('batch_timeout_ms', current_config.batch_timeout_ms)
            max_batch_size = config_updates.get('max_batch_size', current_config.max_batch_size)
            max_queue_size = config_updates.get('max_queue_size', current_config.max_queue_size)

            new_config = BatchingConfig(
                enable_batching=enable_batching,
                batch_timeout_ms=batch_timeout_ms,
                max_batch_size=max_batch_size,
                max_queue_size=max_queue_size
            )

            self._model_configs[model_id] = new_config

            model = model_manager.get_model(model_id)
            if model:
                model.batching_config = new_config
                model_manager.update_model(model_id, {"batching_config": new_config.to_dict()})

            for key, batching_engine in self._batching_engines.items():
                if key.startswith(f"{model_id}:"):
                    batching_engine.update_config(new_config)

            return True

    def get_model_batching_config(self, model_id: str) -> Optional[BatchingConfig]:
        with self._config_lock:
            if model_id in self._model_configs:
                return BatchingConfig(
                    enable_batching=self._model_configs[model_id].enable_batching,
                    batch_timeout_ms=self._model_configs[model_id].batch_timeout_ms,
                    max_batch_size=self._model_configs[model_id].max_batch_size,
                    max_queue_size=self._model_configs[model_id].max_queue_size
                )

            model = model_manager.get_model(model_id)
            if model and hasattr(model, 'batching_config') and model.batching_config:
                return model.batching_config

            return BatchingConfig.get_default_for_model_type(model.model_type if model else "other")

    def get_engine(self, model_id: str, version: str) -> Optional[ModelInferenceEngine]:
        key = self._get_engine_key(model_id, version)
        with self._lock:
            if key in self._engines:
                return self._engines[key]
            return None

    def _start_batching_engine(
        self,
        model_id: str,
        version: str,
        engine: ModelInferenceEngine,
        batching_config: Optional[BatchingConfig] = None
    ):
        key = self._get_engine_key(model_id, version)
        with self._lock:
            if key in self._batching_engines:
                return

            if batching_config is None:
                batching_config = self._get_or_create_batching_config(model_id)

            if not batching_config.enable_batching:
                print(f"Batching disabled for model {model_id}, skipping batching engine")
                return

            batching_engine = ModelBatchingEngine(
                model_id=model_id,
                version=version,
                inference_engine=engine,
                batching_config=batching_config
            )
            self._batching_engines[key] = batching_engine
            batching_engine.start()
            print(f"Batching engine started for {model_id}:{version} with config: {batching_config.to_dict()}")

    def load_engine(self, model_id: str, version: str) -> Optional[ModelInferenceEngine]:
        key = self._get_engine_key(model_id, version)

        with self._lock:
            if key in self._engines:
                engine = self._engines[key]
                if engine.model_loaded:
                    return engine
            else:
                engine = ModelInferenceEngine(model_id, version)
                self._engines[key] = engine

        if engine.load_model():
            model = model_manager.get_model(model_id)
            if model:
                batching_config = self._get_or_create_batching_config(model_id, model.model_type)
            else:
                batching_config = self._get_or_create_batching_config(model_id)

            if self._global_enable_batching and batching_config.enable_batching:
                self._start_batching_engine(model_id, version, engine, batching_config)
            return engine
        return None

    def unload_engine(self, model_id: str, version: str):
        key = self._get_engine_key(model_id, version)
        with self._lock:
            if key in self._batching_engines:
                self._batching_engines[key].stop()
                del self._batching_engines[key]
                print(f"Batching engine stopped for {model_id}:{version}")

            if key in self._engines:
                self._engines[key].unload()
                del self._engines[key]

    def _execute_inference_with_batching(
        self,
        model_id: str,
        version: str,
        input_data: Any,
        input_str: str
    ) -> Dict[str, Any]:
        key = self._get_engine_key(model_id, version)

        with self._lock:
            batching_engine = self._batching_engines.get(key)
            engine = self._engines.get(key)

        if not batching_engine or not engine:
            return {
                "success": False,
                "error": f"Batching engine not available for {model_id}:{version}"
            }

        request = batching_engine.submit_request(model_id, version, input_data, input_str)

        try:
            result = request.result(timeout=30.0)
            return result
        except Exception as e:
            return {
                "success": False,
                "error": f"Batching inference timeout or error: {e}"
            }

    def execute_inference(
        self,
        model_id: str,
        version: str,
        input_data: Any,
        use_current_version: bool = False,
        use_batching: Optional[bool] = None
    ) -> Dict[str, Any]:
        if use_current_version:
            model = model_manager.get_model(model_id)
            if model and model.current_version:
                version = model.current_version

        engine = self.load_engine(model_id, version)
        if not engine:
            return {
                "success": False,
                "error": f"Failed to load model {model_id} version {version}"
            }

        input_str = json.dumps(input_data, ensure_ascii=False) if isinstance(input_data, (dict, list)) else str(input_data)

        model_config = self.get_model_batching_config(model_id)
        use_batch = use_batching if use_batching is not None else (
            self._global_enable_batching and model_config.enable_batching
        )

        if use_batch:
            key = self._get_engine_key(model_id, version)
            with self._lock:
                batching_engine = self._batching_engines.get(key)

            if batching_engine:
                result = self._execute_inference_with_batching(
                    model_id, version, input_data, input_str
                )
            else:
                result = engine.infer(input_data)
        else:
            result = engine.infer(input_data)

        monitoring_manager.record_inference(
            model_id=model_id,
            input_data=input_str[:1000],
            result=result.get("result") if result.get("success") else None,
            inference_time=result.get("inference_time_ms", 0),
            success=result.get("success", False),
            error_message=result.get("error", "")
        )

        return result

    def execute_batch_inference(
        self,
        model_id: str,
        version: str,
        inputs: List[Any],
        use_current_version: bool = False
    ) -> List[Dict[str, Any]]:
        if use_current_version:
            model = model_manager.get_model(model_id)
            if model and model.current_version:
                version = model.current_version

        engine = self.load_engine(model_id, version)
        if not engine:
            error_result = {
                "success": False,
                "error": f"Failed to load model {model_id} version {version}"
            }
            return [error_result] * len(inputs)

        results = engine.batch_infer(inputs)

        for i, result in enumerate(results):
            input_str = json.dumps(inputs[i], ensure_ascii=False) if isinstance(inputs[i], (dict, list)) else str(inputs[i])

            monitoring_manager.record_inference(
                model_id=model_id,
                input_data=input_str[:1000],
                result=result.get("result") if result.get("success") else None,
                inference_time=result.get("inference_time_ms", 0),
                success=result.get("success", False),
                error_message=result.get("error", "")
            )

        return results

    def is_model_loaded(self, model_id: str, version: str) -> bool:
        engine = self.get_engine(model_id, version)
        return engine is not None and engine.model_loaded

    def is_batching_enabled(self, model_id: str, version: str) -> bool:
        key = self._get_engine_key(model_id, version)
        with self._lock:
            return key in self._batching_engines

    def get_batching_stats(self, model_id: str, version: str) -> Optional[Dict[str, Any]]:
        key = self._get_engine_key(model_id, version)
        with self._lock:
            batching_engine = self._batching_engines.get(key)
            if batching_engine:
                return batching_engine.get_stats()
            return None

    def list_loaded_models(self) -> List[Dict[str, Any]]:
        with self._lock:
            loaded = []
            for key, engine in self._engines.items():
                if engine.model_loaded:
                    batching_info = {}
                    if key in self._batching_engines:
                        batching_stats = self._batching_engines[key].get_stats()
                        batching_info = {
                            "batching_enabled": True,
                            "queue_size": batching_stats.get("queue_size", 0),
                            "total_batches": batching_stats.get("total_batches", 0),
                            "total_requests": batching_stats.get("total_requests", 0)
                        }
                    else:
                        config = self.get_model_batching_config(engine.model_id)
                        batching_info = {
                            "batching_enabled": False,
                            "config": config.to_dict() if config else None
                        }

                    loaded.append({
                        "model_id": engine.model_id,
                        "version": engine.version,
                        "loaded": engine.model_loaded,
                        **batching_info
                    })
            return loaded


def get_framework_adapter(framework: str) -> Optional[FrameworkAdapter]:
    adapters = {
        "tensorflow": MockFrameworkAdapter(),
        "pytorch": MockFrameworkAdapter(),
        "onnx": MockFrameworkAdapter(),
        "sklearn": MockFrameworkAdapter(),
        "mock": MockFrameworkAdapter()
    }
    return adapters.get(framework.lower(), MockFrameworkAdapter())


inference_service = InferenceServiceManager(
    enable_batching=Config.BATCHING_CONFIG.get('default_enable_batching', True),
    default_batch_size=Config.BATCHING_CONFIG.get('default_max_batch_size', 32),
    default_batch_timeout_ms=Config.BATCHING_CONFIG.get('default_batch_timeout_ms', 100.0),
    max_queue_size=Config.BATCHING_CONFIG.get('default_max_queue_size', 10000)
)
