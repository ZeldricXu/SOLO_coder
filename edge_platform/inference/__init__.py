"""边缘推理调度模块 - AI模型边缘部署，推理任务调度与结果回传"""

from .inference_manager import (
    InferenceManager,
    AIModel,
    InferenceTask,
    InferenceResult,
    TaskStatus,
    ModelFormat
)

__all__ = [
    "InferenceManager",
    "AIModel",
    "InferenceTask",
    "InferenceResult",
    "TaskStatus",
    "ModelFormat"
]
