from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import time
from functools import wraps
from typing import Any, Awaitable, Callable, Dict, Optional, Tuple, Type, TypeVar

logger = logging.getLogger(__name__)

T = TypeVar("T")
R = TypeVar("R")


def retry(
    max_attempts: int = 3,
    delay: float = 0.5,
    backoff: float = 2.0,
    exceptions: Tuple[Type[Exception], ...] = (Exception,),
) -> Callable[[Callable[..., R]], Callable[..., R]]:
    def decorator(func: Callable[..., R]) -> Callable[..., R]:
        @wraps(func)
        def wrapper(*args: Any, **kwargs: Any) -> R:
            attempt = 0
            current_delay = delay
            last_exception: Optional[Exception] = None

            while attempt < max_attempts:
                try:
                    return func(*args, **kwargs)
                except exceptions as e:
                    attempt += 1
                    last_exception = e
                    if attempt >= max_attempts:
                        break
                    logger.warning(f"Attempt {attempt}/{max_attempts} failed: {e}, retrying in {current_delay}s")
                    time.sleep(current_delay)
                    current_delay *= backoff

            assert last_exception is not None
            raise last_exception

        return wrapper

    return decorator


def async_retry(
    max_attempts: int = 3,
    delay: float = 0.5,
    backoff: float = 2.0,
    exceptions: Tuple[Type[Exception], ...] = (Exception,),
) -> Callable[[Callable[..., Awaitable[R]]], Callable[..., Awaitable[R]]]:
    def decorator(func: Callable[..., Awaitable[R]]) -> Callable[..., Awaitable[R]]:
        @wraps(func)
        async def wrapper(*args: Any, **kwargs: Any) -> R:
            attempt = 0
            current_delay = delay
            last_exception: Optional[Exception] = None

            while attempt < max_attempts:
                try:
                    return await func(*args, **kwargs)
                except exceptions as e:
                    attempt += 1
                    last_exception = e
                    if attempt >= max_attempts:
                        break
                    logger.warning(f"Attempt {attempt}/{max_attempts} failed: {e}, retrying in {current_delay}s")
                    await asyncio.sleep(current_delay)
                    current_delay *= backoff

            assert last_exception is not None
            raise last_exception

        return wrapper

    return decorator


def generate_key(*parts: Any) -> str:
    normalized_parts = [json.dumps(p, sort_keys=True, default=str) if not isinstance(p, str) else p for p in parts]
    raw = ":".join(normalized_parts)
    return hashlib.sha256(raw.encode()).hexdigest()[:32]


def merge_dicts(base: Dict[str, Any], override: Dict[str, Any]) -> Dict[str, Any]:
    result = base.copy()
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = merge_dicts(result[key], value)
        else:
            result[key] = value
    return result


def safe_get(d: Dict[str, Any], path: str, default: Any = None) -> Any:
    keys = path.split(".")
    current = d
    for key in keys:
        if isinstance(current, dict) and key in current:
            current = current[key]
        else:
            return default
    return current
