from typing import Any, Dict, Optional


class TaskOrchestratorError(Exception):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message)
        self.message = message
        self.details = details or {}
        self.timestamp = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "error": self.__class__.__name__,
            "message": self.message,
            "details": self.details,
        }


class ValidationError(TaskOrchestratorError):
    def __init__(self, message: str = "参数校验失败", details: Optional[Dict[str, Any]] = None):
        super().__init__(message, details)


class TimeoutError(TaskOrchestratorError):
    def __init__(self, message: str = "操作超时", details: Optional[Dict[str, Any]] = None):
        super().__init__(message, details)


class DependencyError(TaskOrchestratorError):
    def __init__(self, message: str = "依赖错误", details: Optional[Dict[str, Any]] = None):
        super().__init__(message, details)


class ResourceNotFoundError(TaskOrchestratorError):
    def __init__(self, message: str = "资源未找到", details: Optional[Dict[str, Any]] = None):
        super().__init__(message, details)


class ConfigurationError(TaskOrchestratorError):
    def __init__(self, message: str = "配置错误", details: Optional[Dict[str, Any]] = None):
        super().__init__(message, details)


class NotificationError(TaskOrchestratorError):
    def __init__(self, message: str = "通知发送失败", details: Optional[Dict[str, Any]] = None):
        super().__init__(message, details)


class QualityGateError(TaskOrchestratorError):
    def __init__(self, message: str = "质量门禁检查失败", details: Optional[Dict[str, Any]] = None):
        super().__init__(message, details)


class DatabaseError(TaskOrchestratorError):
    def __init__(self, message: str = "数据库操作失败", details: Optional[Dict[str, Any]] = None):
        super().__init__(message, details)


class StorageError(TaskOrchestratorError):
    def __init__(self, message: str = "存储操作失败", details: Optional[Dict[str, Any]] = None):
        super().__init__(message, details)
