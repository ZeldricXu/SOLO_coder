from typing import Optional, Dict, Any, List
import asyncio
import time
import os
from loguru import logger

from config import settings
from recommendation_engine.models.schemas import ModelInferenceRequest, ModelInferenceResponse
from recommendation_engine.model_serving_gateway.triton_client import TritonClient
from recommendation_engine.model_serving_gateway.onnx_runtime_backend import ONNXRuntimeBackend
from recommendation_engine.infrastructure.postgres_client import get_postgres_client, PostgresClient


class ModelServingGateway:
    def __init__(self):
        self._triton = TritonClient()
        self._onnx = ONNXRuntimeBackend()
        self._postgres: Optional[PostgresClient] = None

        self._model_registry: Dict[str, Dict[str, Any]] = {}
        self._default_versions: Dict[str, str] = {}
        self._hot_reload_task: Optional[asyncio.Task] = None
        self._running = False

        self._stats = {
            "total_inferences": 0,
            "triton_inferences": 0,
            "onnx_inferences": 0,
            "inference_errors": 0,
            "avg_inference_time_ms": 0.0,
        }
        self._total_inference_time = 0.0

    async def initialize(self) -> None:
        if self._running:
            return

        try:
            self._postgres = await get_postgres_client()

            await self._triton.initialize()
            await self._load_model_registry()

            self._running = True

            if settings.hot_reload_enabled:
                self._hot_reload_task = asyncio.create_task(self._hot_reload_loop())

            logger.info("ModelServingGateway initialized")
        except Exception as e:
            logger.error(f"Failed to initialize ModelServingGateway: {e}")
            self._running = False
            raise

    async def _load_model_registry(self) -> None:
        if not self._postgres:
            return

        try:
            rows = await self._postgres.fetch(
                "SELECT * FROM model_versions WHERE status = 'active'"
            )

            for row in rows:
                model_name = row["model_name"]
                model_version = row["model_version"]
                backend = row["backend"]

                model_key = f"{model_name}:{model_version}"
                self._model_registry[model_key] = {
                    "model_name": model_name,
                    "model_version": model_version,
                    "backend": backend,
                    "model_path": row["model_path"],
                    "metadata": row.get("metadata", {}),
                    "loaded": False,
                }

                if model_name not in self._default_versions:
                    self._default_versions[model_name] = model_version

            logger.info(f"Loaded {len(self._model_registry)} models from registry")
        except Exception as e:
            logger.error(f"Failed to load model registry: {e}")

    async def _hot_reload_loop(self) -> None:
        logger.info(
            f"Hot reload loop started, interval: {settings.hot_reload_interval_seconds}s"
        )

        while self._running:
            try:
                await asyncio.sleep(settings.hot_reload_interval_seconds)

                await self._load_model_registry()

                for model_key, info in self._model_registry.items():
                    if info["backend"] == "onnx" and info["loaded"]:
                        await self._onnx.reload_if_updated(
                            info["model_name"], info["model_version"]
                        )

            except asyncio.CancelledError:
                logger.info("Hot reload loop cancelled")
                break
            except Exception as e:
                logger.error(f"Hot reload error: {e}")

        logger.info("Hot reload loop stopped")

    async def register_model(
        self,
        model_name: str,
        model_version: str,
        backend: str,
        model_path: str,
        metadata: Optional[Dict[str, Any]] = None,
        set_default: bool = True,
    ) -> bool:
        if not self._postgres:
            return False

        try:
            await self._postgres.upsert(
                "model_versions",
                {
                    "model_name": model_name,
                    "model_version": model_version,
                    "backend": backend,
                    "model_path": model_path,
                    "status": "active",
                    "metadata": metadata or {},
                },
                ["model_name", "model_version"],
            )

            model_key = f"{model_name}:{model_version}"
            self._model_registry[model_key] = {
                "model_name": model_name,
                "model_version": model_version,
                "backend": backend,
                "model_path": model_path,
                "metadata": metadata or {},
                "loaded": False,
            }

            if set_default or model_name not in self._default_versions:
                self._default_versions[model_name] = model_version

            logger.info(f"Model registered: {model_key}")
            return True
        except Exception as e:
            logger.error(f"Failed to register model {model_name}:{model_version}: {e}")
            return False

    async def load_model(
        self, model_name: str, model_version: Optional[str] = None
    ) -> bool:
        version = model_version or self._default_versions.get(model_name)
        if not version:
            logger.error(f"No version found for model {model_name}")
            return False

        model_key = f"{model_name}:{version}"
        model_info = self._model_registry.get(model_key)

        if not model_info:
            logger.error(f"Model {model_key} not registered")
            return False

        if model_info["loaded"]:
            return True

        try:
            backend = model_info["backend"]
            success = False

            if backend == "triton":
                success = await self._triton.load_model(model_name, version)
            elif backend == "onnx":
                success = await self._onnx.load_model(
                    model_name, model_info["model_path"], version
                )
            else:
                logger.error(f"Unknown backend: {backend}")
                return False

            if success:
                model_info["loaded"] = True
                logger.info(f"Model loaded: {model_key}")

            return success
        except Exception as e:
            logger.error(f"Failed to load model {model_key}: {e}")
            return False

    async def unload_model(
        self, model_name: str, model_version: Optional[str] = None
    ) -> bool:
        version = model_version or self._default_versions.get(model_name)
        if not version:
            return False

        model_key = f"{model_name}:{version}"
        model_info = self._model_registry.get(model_key)

        if not model_info:
            return False

        try:
            backend = model_info["backend"]

            if backend == "triton":
                await self._triton.unload_model(model_name)
            elif backend == "onnx":
                await self._onnx.unload_model(model_name, version)

            model_info["loaded"] = False
            logger.info(f"Model unloaded: {model_key}")
            return True
        except Exception as e:
            logger.error(f"Failed to unload model {model_key}: {e}")
            return False

    async def set_default_version(self, model_name: str, model_version: str) -> bool:
        model_key = f"{model_name}:{model_version}"
        if model_key not in self._model_registry:
            logger.error(f"Model {model_key} not registered")
            return False

        self._default_versions[model_name] = model_version
        logger.info(f"Set default version for {model_name} to {model_version}")
        return True

    async def infer(
        self, request: ModelInferenceRequest
    ) -> Optional[ModelInferenceResponse]:
        version = request.model_version or self._default_versions.get(request.model_name)
        if not version:
            logger.error(f"No version found for model {request.model_name}")
            self._stats["inference_errors"] += 1
            return None

        model_key = f"{request.model_name}:{version}"
        model_info = self._model_registry.get(model_key)

        if not model_info:
            logger.error(f"Model {model_key} not registered")
            self._stats["inference_errors"] += 1
            return None

        if not model_info["loaded"]:
            if not await self.load_model(request.model_name, version):
                self._stats["inference_errors"] += 1
                return None

        start_time = time.time()
        try:
            backend = model_info["backend"]
            outputs: Optional[Dict[str, Any]] = None

            if backend == "triton" and self._triton.is_available():
                outputs = await self._triton.infer(
                    request.model_name,
                    request.inputs,
                    version,
                    request.timeout_ms,
                )
                self._stats["triton_inferences"] += 1
            elif backend == "onnx" and self._onnx.is_available():
                outputs = await self._onnx.infer(
                    request.model_name,
                    request.inputs,
                    version,
                    request.timeout_ms,
                )
                self._stats["onnx_inferences"] += 1
            else:
                logger.error(f"Backend {backend} not available")
                self._stats["inference_errors"] += 1
                return None

            if outputs is None:
                self._stats["inference_errors"] += 1
                return None

            inference_time = (time.time() - start_time) * 1000
            self._stats["total_inferences"] += 1
            self._total_inference_time += inference_time
            self._stats["avg_inference_time_ms"] = (
                self._total_inference_time / self._stats["total_inferences"]
            )

            return ModelInferenceResponse(
                request_id=request.request_id,
                model_name=request.model_name,
                model_version=version,
                outputs=outputs,
                inference_time_ms=inference_time,
                backend=backend,
            )

        except Exception as e:
            logger.error(f"Inference error for {model_key}: {e}")
            self._stats["inference_errors"] += 1
            return None

    async def infer_raw(
        self,
        model_name: str,
        inputs: Dict[str, Any],
        model_version: Optional[str] = None,
        timeout_ms: int = 10000,
    ) -> Optional[Dict[str, Any]]:
        import uuid

        request = ModelInferenceRequest(
            model_name=model_name,
            model_version=model_version,
            inputs=inputs,
            request_id=str(uuid.uuid4()),
            timeout_ms=timeout_ms,
        )
        response = await self.infer(request)
        return response.outputs if response else None

    def list_models(self) -> List[Dict[str, Any]]:
        models = []
        for model_key, info in self._model_registry.items():
            models.append(
                {
                    "model_name": info["model_name"],
                    "model_version": info["model_version"],
                    "backend": info["backend"],
                    "status": "loaded" if info["loaded"] else "registered",
                    "is_default": self._default_versions.get(info["model_name"])
                    == info["model_version"],
                    "model_path": info["model_path"],
                }
            )
        return models

    def get_model_info(
        self, model_name: str, model_version: Optional[str] = None
    ) -> Optional[Dict[str, Any]]:
        version = model_version or self._default_versions.get(model_name)
        if not version:
            return None

        model_key = f"{model_name}:{version}"
        info = self._model_registry.get(model_key)
        if not info:
            return None

        result = info.copy()
        result["is_default"] = self._default_versions.get(model_name) == version

        if info["backend"] == "onnx":
            result["inputs"] = self._onnx.get_model_inputs(model_name, version)
            result["outputs"] = self._onnx.get_model_outputs(model_name, version)

        return result

    def get_stats(self) -> Dict[str, Any]:
        stats = self._stats.copy()
        stats["running"] = self._running
        stats["triton_available"] = self._triton.is_available()
        stats["onnx_available"] = self._onnx.is_available()
        stats["registered_models"] = len(self._model_registry)
        stats["loaded_models"] = len(
            [k for k, v in self._model_registry.items() if v["loaded"]]
        )
        return stats

    async def close(self) -> None:
        if not self._running:
            return

        logger.info("Closing ModelServingGateway...")
        self._running = False

        if self._hot_reload_task and not self._hot_reload_task.done():
            self._hot_reload_task.cancel()
            try:
                await self._hot_reload_task
            except asyncio.CancelledError:
                pass

        await self._triton.close()
        await self._onnx.close()

        logger.info("ModelServingGateway closed")
        logger.info(f"Final stats: {self._stats}")


_model_gateway: Optional[ModelServingGateway] = None


async def get_model_gateway() -> ModelServingGateway:
    global _model_gateway
    if _model_gateway is None:
        _model_gateway = ModelServingGateway()
        await _model_gateway.initialize()
    return _model_gateway


async def close_model_gateway() -> None:
    global _model_gateway
    if _model_gateway is not None:
        await _model_gateway.close()
        _model_gateway = None
