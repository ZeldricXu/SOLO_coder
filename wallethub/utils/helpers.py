import asyncio
import time
import uuid
from functools import wraps
from typing import Any, Callable, Coroutine, Iterable, List, TypeVar, Optional, Union
from eth_utils import from_wei as eth_from_wei, to_wei as eth_to_wei


T = TypeVar("T")


def generate_id(prefix: str = "") -> str:
    if prefix:
        return f"{prefix}_{uuid.uuid4().hex[:12]}"
    return uuid.uuid4().hex[:24]


def from_wei(value: int, unit: str = "ether") -> float:
    return float(eth_from_wei(value, unit))


def to_wei(value: Union[float, int, str], unit: str = "ether") -> int:
    return eth_to_wei(value, unit)


def from_gwei(value: int) -> float:
    return value / 1e9


def to_gwei(value: float) -> int:
    return int(value * 1e9)


def chunk_list(lst: List[T], chunk_size: int) -> List[List[T]]:
    return [lst[i:i + chunk_size] for i in range(0, len(lst), chunk_size)]


def async_retry(
    max_attempts: int = 3,
    delay: float = 1.0,
    backoff: float = 2.0,
    exceptions: tuple = (Exception,),
):
    def decorator(
        func: Callable[..., Coroutine[Any, Any, T]]
    ) -> Callable[..., Coroutine[Any, Any, T]]:
        @wraps(func)
        async def wrapper(*args: Any, **kwargs: Any) -> T:
            attempt = 0
            current_delay = delay
            while attempt < max_attempts:
                try:
                    return await func(*args, **kwargs)
                except exceptions as e:
                    attempt += 1
                    if attempt >= max_attempts:
                        raise
                    await asyncio.sleep(current_delay)
                    current_delay *= backoff
            raise RuntimeError("Unexpected retry exit")
        return wrapper
    return decorator


def rate_limit(calls_per_second: float):
    min_interval = 1.0 / calls_per_second
    last_called = [0.0]

    def decorator(
        func: Callable[..., Coroutine[Any, Any, T]]
    ) -> Callable[..., Coroutine[Any, Any, T]]:
        @wraps(func)
        async def wrapper(*args: Any, **kwargs: Any) -> T:
            elapsed = time.monotonic() - last_called[0]
            wait_time = min_interval - elapsed
            if wait_time > 0:
                await asyncio.sleep(wait_time)
            result = await func(*args, **kwargs)
            last_called[0] = time.monotonic()
            return result
        return wrapper
    return decorator


def safe_get(d: dict, *keys: str, default: Any = None) -> Any:
    current = d
    for key in keys:
        if not isinstance(current, dict):
            return default
        current = current.get(key)
        if current is None:
            return default
    return current


def hex_to_int(hex_str: str) -> int:
    if hex_str.startswith("0x") or hex_str.startswith("0X"):
        hex_str = hex_str[2:]
    return int(hex_str, 16) if hex_str else 0


def int_to_hex(value: int, prefix: bool = True) -> str:
    hex_str = hex(value)[2:]
    return "0x" + hex_str if prefix else hex_str
