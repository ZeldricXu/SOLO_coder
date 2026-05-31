import asyncio
import functools
import logging
import time
import uuid
from datetime import timedelta
from typing import Any, Callable, Dict, List, Optional, TypeVar, cast

from .errors import TaskOrchestratorError, ValidationError

T = TypeVar("T")
logger = logging.getLogger(__name__)


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:8]}"


def generate_trace_id() -> str:
    return f"trace_{uuid.uuid4().hex}"


def validate_params(params: Dict[str, Any], required_fields: List[str]) -> None:
    missing = [field for field in required_fields if field not in params]
    if missing:
        raise ValidationError(
            f"缺少必需参数: {', '.join(missing)}",
            {"missing_fields": missing, "available_fields": list(params.keys())},
        )


def retry_sync(
    max_attempts: int = 3,
    delay: float = 1.0,
    backoff: float = 2.0,
    exceptions: tuple = (Exception,),
) -> Callable[[Callable[..., T]], Callable[..., T]]:
    def decorator(func: Callable[..., T]) -> Callable[..., T]:
        @functools.wraps(func)
        def wrapper(*args: Any, **kwargs: Any) -> T:
            last_exception: Optional[Exception] = None
            current_delay = delay
            for attempt in range(max_attempts):
                try:
                    return func(*args, **kwargs)
                except exceptions as e:
                    last_exception = e
                    if attempt < max_attempts - 1:
                        logger.warning(
                            "Attempt %d/%d failed: %s, retrying in %.1fs",
                            attempt + 1,
                            max_attempts,
                            str(e),
                            current_delay,
                        )
                        time.sleep(current_delay)
                        current_delay *= backoff
            raise cast(Exception, last_exception)

        return wrapper

    return decorator


def retry_async(
    max_attempts: int = 3,
    delay: float = 1.0,
    backoff: float = 2.0,
    exceptions: tuple = (Exception,),
) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
        @functools.wraps(func)
        async def wrapper(*args: Any, **kwargs: Any) -> Any:
            last_exception: Optional[Exception] = None
            current_delay = delay
            attempts = max(max_attempts, 1)
            for attempt in range(attempts):
                try:
                    return await func(*args, **kwargs)
                except exceptions as e:
                    last_exception = e
                    if attempt < attempts - 1:
                        logger.warning(
                            "Attempt %d/%d failed: %s, retrying in %.1fs",
                            attempt + 1,
                            attempts,
                            str(e),
                            current_delay,
                        )
                        await asyncio.sleep(current_delay)
                        current_delay *= backoff
            raise cast(Exception, last_exception)

        return wrapper

    return decorator


def safe_execute(func: Callable[..., T], *args: Any, **kwargs: Any) -> Optional[T]:
    try:
        return func(*args, **kwargs)
    except Exception as e:
        logger.error("Safe execution failed: %s", str(e), exc_info=True)
        return None


async def safe_execute_async(func: Callable[..., Any], *args: Any, **kwargs: Any) -> Optional[Any]:
    try:
        return await func(*args, **kwargs)
    except Exception as e:
        logger.error("Safe async execution failed: %s", str(e), exc_info=True)
        return None


def format_duration(seconds: float) -> str:
    td = timedelta(seconds=seconds)
    days = td.days
    hours, remainder = divmod(td.seconds, 3600)
    minutes, secs = divmod(remainder, 60)
    if days > 0:
        return f"{days}d {hours}h {minutes}m {secs}s"
    if hours > 0:
        return f"{hours}h {minutes}m {secs}s"
    if minutes > 0:
        return f"{minutes}m {secs}s"
    return f"{secs}s"


def parse_duration(duration_str: str) -> float:
    duration_str = duration_str.strip().lower()
    multipliers = {
        "s": 1,
        "m": 60,
        "h": 3600,
        "d": 86400,
    }
    total_seconds = 0.0
    current_num = ""
    for char in duration_str:
        if char.isdigit() or char == ".":
            current_num += char
        elif char in multipliers and current_num:
            total_seconds += float(current_num) * multipliers[char]
            current_num = ""
    if current_num:
        total_seconds += float(current_num)
    return total_seconds


def deep_merge(dict1: Dict[str, Any], dict2: Dict[str, Any]) -> Dict[str, Any]:
    result = dict1.copy()
    for key, value in dict2.items():
        if isinstance(value, dict) and key in result and isinstance(result[key], dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def sanitize_dict(data: Dict[str, Any], sensitive_keys: Optional[List[str]] = None) -> Dict[str, Any]:
    sensitive = sensitive_keys or ["password", "secret", "token", "key", "authorization"]
    result = {}
    for key, value in data.items():
        if isinstance(key, str) and any(s in key.lower() for s in sensitive):
            result[key] = "***REDACTED***"
        elif isinstance(value, dict):
            result[key] = sanitize_dict(value, sensitive)
        elif isinstance(value, list):
            result[key] = [
                sanitize_dict(item, sensitive) if isinstance(item, dict) else item for item in value
            ]
        else:
            result[key] = value
    return result


class ExecutionContext:
    def __init__(self, trace_id: Optional[str] = None):
        self.trace_id = trace_id or generate_trace_id()
        self.metrics: Dict[str, Any] = {}
        self.errors: List[TaskOrchestratorError] = []
        self.tags: Dict[str, str] = {}
        self.start_time = time.time()

    def record_metric(self, name: str, value: Any) -> None:
        self.metrics[name] = value

    def add_error(self, error: TaskOrchestratorError) -> None:
        self.errors.append(error)

    def add_tag(self, key: str, value: str) -> None:
        self.tags[key] = value

    def get_elapsed_time(self) -> float:
        return time.time() - self.start_time

    def cleanup(self) -> None:
        self.metrics.clear()
        self.errors.clear()
        self.tags.clear()
