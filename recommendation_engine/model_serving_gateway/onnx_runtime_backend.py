from typing import Optional, Dict, Any, List
import os
import time
import threading
from loguru import logger

try:
    import onnxruntime as ort

    ONNX_AVAILABLE = True
except ImportError:
    ONNX_AVAILABLE = False
    logger.warning("ONNX Runtime not available")

import numpy as np
from config import settings


class ONNXRuntimeBackend:
    def __init__(self):
        self._providers = settings.onnx_providers
        self._available = ONNX_AVAILABLE
        self._sessions: Dict[str, "ort.InferenceSession"] = {}
        self._model_mtimes: Dict[str, float] = {}
        self._model_paths: Dict[str, str] = {}
        self._lock = threading.RLock()

    def is_available(self) -> bool:
        return self._available

    async def load_model(
        self,
        model_name: str,
        model_path: str,
        model_version: str = "1",
    ) -> bool:
        if not self._available:
            logger.warning("ONNX Runtime not available")
            return False

        if not os.path.exists(model_path):
            logger.error(f"Model file not found: {model_path}")
            return False

        try:
            model_key = f"{model_name}:{model_version}"

            with self._lock:
                session_options = ort.SessionOptions()
                session_options.intra_op_num_threads = 4
                session_options.inter_op_num_threads = 4
                session_options.enable_mem_pattern = True
                session_options.enable_cpu_mem_arena = True

                session = ort.InferenceSession(
                    model_path,
                    sess_options=session_options,
                    providers=self._providers,
                )

                self._sessions[model_key] = session
                self._model_paths[model_key] = model_path
                self._model_mtimes[model_key] = os.path.getmtime(model_path)

                providers = session.get_providers()
                logger.info(
                    f"ONNX model {model_key} loaded from {model_path}, providers: {providers}"
                )
                return True

        except Exception as e:
            logger.error(f"Failed to load ONNX model {model_name}: {e}")
            return False

    async def reload_if_updated(
        self, model_name: str, model_version: str = "1"
    ) -> bool:
        if not self._available:
            return False

        model_key = f"{model_name}:{model_version}"
        if model_key not in self._model_paths:
            return False

        try:
            current_mtime = os.path.getmtime(self._model_paths[model_key])
            if current_mtime > self._model_mtimes.get(model_key, 0):
                logger.info(f"Model {model_key} updated, reloading...")
                return await self.load_model(
                    model_name, self._model_paths[model_key], model_version
                )
        except Exception as e:
            logger.error(f"Failed to check model update {model_key}: {e}")

        return False

    async def unload_model(self, model_name: str, model_version: str = "1") -> bool:
        model_key = f"{model_name}:{model_version}"

        with self._lock:
            if model_key in self._sessions:
                del self._sessions[model_key]
                self._model_mtimes.pop(model_key, None)
                self._model_paths.pop(model_key, None)
                logger.info(f"ONNX model {model_key} unloaded")
                return True

        return False

    async def infer(
        self,
        model_name: str,
        inputs: Dict[str, Any],
        model_version: str = "1",
        timeout_ms: int = 10000,
    ) -> Optional[Dict[str, Any]]:
        if not self._available:
            return None

        model_key = f"{model_name}:{model_version}"
        session = self._sessions.get(model_key)

        if session is None:
            logger.warning(f"ONNX model {model_key} not loaded")
            return None

        start_time = time.time()
        try:
            onnx_inputs = {}
            for input_info in session.get_inputs():
                name = input_info.name
                if name in inputs:
                    onnx_inputs[name] = self._to_numpy(inputs[name], input_info)

            outputs = session.run(None, onnx_inputs)

            output_names = [o.name for o in session.get_outputs()]
            result = {}
            for name, output in zip(output_names, outputs):
                result[name] = output.tolist()

            inference_time = (time.time() - start_time) * 1000
            logger.debug(
                f"ONNX inference {model_key} completed in {inference_time:.2f}ms"
            )
            return result

        except Exception as e:
            logger.error(f"ONNX inference error for {model_key}: {e}")
            return None

    def _to_numpy(self, data: Any, input_info: Any) -> np.ndarray:
        dtype_map = {
            "tensor(float)": np.float32,
            "tensor(double)": np.float64,
            "tensor(int32)": np.int32,
            "tensor(int64)": np.int64,
            "tensor(int8)": np.int8,
            "tensor(uint8)": np.uint8,
            "tensor(bool)": np.bool_,
        }

        target_dtype = dtype_map.get(input_info.type, np.float32)

        if isinstance(data, np.ndarray):
            return data.astype(target_dtype)
        if isinstance(data, (list, tuple)):
            arr = np.array(data, dtype=target_dtype)
            expected_shape = input_info.shape
            if expected_shape and len(expected_shape) > 0:
                shape = []
                for i, dim in enumerate(expected_shape):
                    if isinstance(dim, str) or dim == -1 or dim is None:
                        shape.append(arr.shape[i] if i < len(arr.shape) else 1)
                    else:
                        shape.append(dim)
                try:
                    arr = arr.reshape(shape)
                except Exception:
                    pass
            return arr
        if isinstance(data, (int, float)):
            return np.array([data], dtype=target_dtype)
        return np.array(data, dtype=target_dtype)

    def get_model_inputs(self, model_name: str, model_version: str = "1") -> List[Dict[str, Any]]:
        model_key = f"{model_name}:{model_version}"
        session = self._sessions.get(model_key)
        if session is None:
            return []

        return [
            {"name": i.name, "type": i.type, "shape": i.shape}
            for i in session.get_inputs()
        ]

    def get_model_outputs(self, model_name: str, model_version: str = "1") -> List[Dict[str, Any]]:
        model_key = f"{model_name}:{model_version}"
        session = self._sessions.get(model_key)
        if session is None:
            return []

        return [
            {"name": o.name, "type": o.type, "shape": o.shape}
            for o in session.get_outputs()
        ]

    def list_models(self) -> List[str]:
        return list(self._sessions.keys())

    async def close(self) -> None:
        with self._lock:
            self._sessions.clear()
            self._model_mtimes.clear()
            self._model_paths.clear()
        logger.info("ONNX Runtime backend closed")
