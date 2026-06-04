from typing import Optional, Dict, Any, List
import time
from loguru import logger

try:
    import tritonclient.http as httpclient
    from tritonclient.utils import InferenceServerException

    TRITON_AVAILABLE = True
except ImportError:
    TRITON_AVAILABLE = False
    logger.warning("Triton client not available")

import numpy as np
from config import settings


class TritonClient:
    def __init__(self):
        self._url = settings.triton_server_url
        self._timeout = settings.triton_model_timeout
        self._client: Optional[Any] = None
        self._available = TRITON_AVAILABLE
        self._model_metadata: Dict[str, Any] = {}

    async def initialize(self) -> bool:
        if not self._available:
            logger.warning("Triton client not available")
            return False

        try:
            self._client = httpclient.InferenceServerClient(
                url=self._url,
                verbose=False,
                concurrency=10,
            )

            if not await self._health_check():
                logger.warning(f"Triton server at {self._url} is not healthy")
                return False

            logger.info(f"Triton client connected to {self._url}")
            return True
        except Exception as e:
            logger.error(f"Failed to initialize Triton client: {e}")
            self._available = False
            return False

    async def _health_check(self) -> bool:
        if not self._client:
            return False

        try:
            return self._client.is_server_live() and self._client.is_server_ready()
        except Exception as e:
            logger.error(f"Triton health check failed: {e}")
            return False

    async def load_model(self, model_name: str, model_version: Optional[str] = None) -> bool:
        if not self._available or not self._client:
            return False

        try:
            if not self._client.is_model_ready(model_name, model_version):
                logger.info(f"Loading model {model_name}:{model_version}")
                self._client.load_model(model_name)

            metadata = self._client.get_model_metadata(model_name, model_version)
            self._model_metadata[f"{model_name}:{model_version}"] = metadata
            logger.info(f"Model {model_name}:{model_version} loaded successfully")
            return True
        except InferenceServerException as e:
            logger.error(f"Triton load model failed: {e}")
            return False
        except Exception as e:
            logger.error(f"Failed to load model {model_name}:{e}")
            return False

    async def unload_model(self, model_name: str) -> bool:
        if not self._available or not self._client:
            return False

        try:
            self._client.unload_model(model_name)
            logger.info(f"Model {model_name} unloaded")
            return True
        except Exception as e:
            logger.error(f"Failed to unload model {model_name}: {e}")
            return False

    async def infer(
        self,
        model_name: str,
        inputs: Dict[str, Any],
        model_version: Optional[str] = None,
        timeout_ms: int = 10000,
    ) -> Optional[Dict[str, Any]]:
        if not self._available or not self._client:
            return None

        start_time = time.time()
        try:
            triton_inputs = []
            for name, data in inputs.items():
                np_data = self._to_numpy(data)
                input_obj = httpclient.InferInput(
                    name, np_data.shape, self._get_triton_dtype(np_data)
                )
                input_obj.set_data_from_numpy(np_data)
                triton_inputs.append(input_obj)

            metadata_key = f"{model_name}:{model_version}"
            if metadata_key not in self._model_metadata:
                await self.load_model(model_name, model_version)

            metadata = self._model_metadata.get(metadata_key, {})
            output_names = [o["name"] for o in metadata.get("outputs", [])]
            triton_outputs = [httpclient.InferRequestedOutput(n) for n in output_names]

            results = self._client.infer(
                model_name=model_name,
                inputs=triton_inputs,
                outputs=triton_outputs,
                model_version=model_version or "",
                timeout=timeout_ms,
            )

            outputs = {}
            for output_name in output_names:
                outputs[output_name] = results.as_numpy(output_name).tolist()

            inference_time = (time.time() - start_time) * 1000
            logger.debug(
                f"Triton inference {model_name}:{model_version} completed in {inference_time:.2f}ms"
            )
            return outputs

        except InferenceServerException as e:
            logger.error(f"Triton inference error: {e}")
            return None
        except Exception as e:
            logger.error(f"Failed to run Triton inference: {e}")
            return None

    def _to_numpy(self, data: Any) -> np.ndarray:
        if isinstance(data, np.ndarray):
            return data.astype(np.float32)
        if isinstance(data, (list, tuple)):
            return np.array(data, dtype=np.float32)
        if isinstance(data, (int, float)):
            return np.array([data], dtype=np.float32)
        return np.array(data, dtype=np.float32)

    def _get_triton_dtype(self, arr: np.ndarray) -> str:
        dtype_map = {
            np.float32: "FP32",
            np.float64: "FP64",
            np.int32: "INT32",
            np.int64: "INT64",
            np.int8: "INT8",
            np.uint8: "UINT8",
            np.bool_: "BOOL",
        }
        return dtype_map.get(arr.dtype, "FP32")

    async def list_models(self) -> List[str]:
        if not self._available or not self._client:
            return []

        try:
            return self._client.get_model_repository_index()
        except Exception as e:
            logger.error(f"Failed to list models: {e}")
            return []

    def is_available(self) -> bool:
        return self._available

    async def close(self) -> None:
        if self._client:
            self._client.close()
            self._client = None
        logger.info("Triton client closed")
