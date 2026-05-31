"""
异常定义 - 所有模块的自定义异常基类
"""

from __future__ import annotations

from typing import Any, Dict, Optional


class BaseError(Exception):
    """所有自定义异常的基类"""

    def __init__(
        self,
        message: str,
        code: str = "internal_error",
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.code = code
        self.details = details or {}

    def to_dict(self) -> Dict[str, Any]:
        return {
            "code": self.code,
            "message": self.message,
            "details": self.details,
        }


class ConfigurationError(BaseError):
    """配置错误"""

    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None) -> None:
        super().__init__(message, code="configuration_error", details=details)


class StorageError(BaseError):
    """存储相关错误"""

    def __init__(
        self,
        message: str,
        bucket: Optional[str] = None,
        key: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        _details = details or {}
        if bucket:
            _details["bucket"] = bucket
        if key:
            _details["key"] = key
        super().__init__(message, code="storage_error", details=_details)


class NotificationError(BaseError):
    """通知相关错误"""

    def __init__(
        self,
        message: str,
        channel: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        _details = details or {}
        if channel:
            _details["channel"] = channel
        super().__init__(message, code="notification_error", details=_details)


class TemplateError(BaseError):
    """模板相关错误"""

    def __init__(
        self,
        message: str,
        template: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        _details = details or {}
        if template:
            _details["template"] = template
        super().__init__(message, code="template_error", details=_details)


class QualityCheckError(BaseError):
    """质量检查错误"""

    def __init__(
        self,
        message: str,
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        super().__init__(message, code="quality_check_error", details=details)


class GatewayError(BaseError):
    """网关错误"""

    def __init__(
        self,
        message: str,
        status_code: int = 500,
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        _details = details or {}
        _details["status_code"] = status_code
        super().__init__(message, code="gateway_error", details=_details)


class ScaffoldError(BaseError):
    """脚手架生成错误"""

    def __init__(
        self,
        message: str,
        template: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None,
    ) -> None:
        _details = details or {}
        if template:
            _details["template"] = template
        super().__init__(message, code="scaffold_error", details=_details)
