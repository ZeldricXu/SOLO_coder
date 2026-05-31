import hashlib
import json
import uuid

from collections.abc import Callable
from datetime import datetime, timezone
from typing import Any, Dict, TypeVar, Optional, Union

from .exceptions import ValidationError


T = TypeVar("T")
SENSITIVE_FIELDS = {"password", "token", "secret", "api_key", "authorization", "credit_card"}


def generate_id(prefix: str = "id") -> str:
    return f"{prefix}_{uuid.uuid4().hex[:8]}"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def validate_params(params: Dict[str, Any], rules: Dict[str, Callable[[Any], bool]]) -> None:
    errors: Dict[str, str] = {}
    for field, validator in rules.items():
        value = params.get(field)
        try:
            if not validator(value):
                errors[field] = f"字段 '{field}' 校验失败"
        except Exception as e:
            errors[field] = str(e)
    if errors:
        raise ValidationError(message="参数校验失败", details=errors)


def calculate_hash(data: Union[str, Dict[str, Any]]) -> str:
    if isinstance(data, dict):
        data = json.dumps(data, sort_keys=True)
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


def safe_getattr(obj: Any, attr: str, default: Optional[T] = None) -> Optional[T]:
    try:
        return getattr(obj, attr, default)
    except Exception:
        return default


def _mask_value(value: str) -> str:
    return "***"


def mask_sensitive_data(
    data: Union[str, Dict[str, Any]], visible_chars: int = 4
) -> Union[str, Dict[str, Any]]:
    if isinstance(data, dict):
        result = {}
        for key, value in data.items():
            if isinstance(value, dict):
                result[key] = mask_sensitive_data(value)
            elif key.lower() in SENSITIVE_FIELDS:
                result[key] = _mask_value(value)
            else:
                result[key] = value
        return result

    if not data or len(data) <= visible_chars:
        return "****"
    return data[:visible_chars] + "*" * (len(data) - visible_chars)
