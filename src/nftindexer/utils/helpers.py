import asyncio
import functools
import uuid
from typing import Any, Callable, Iterable, List, Optional, TypeVar

from eth_utils import to_checksum_address as eth_to_checksum_address, is_address

from .exceptions import ValidationError, TimeoutError

T = TypeVar("T")


def generate_id(prefix: str = "") -> str:
    uid = uuid.uuid4().hex[:16]
    return f"{prefix}_{uid}" if prefix else uid


def validate_address(address: str) -> bool:
    try:
        return is_address(address)
    except Exception:
        return False


def normalize_address(address: str) -> str:
    if not validate_address(address):
        raise ValidationError(f"Invalid address: {address}")
    return address.lower()


def to_checksum_address(address: str) -> str:
    if not validate_address(address):
        raise ValidationError(f"Invalid address: {address}")
    return eth_to_checksum_address(address)


def hex_to_bytes(hex_str: str) -> bytes:
    if hex_str.startswith("0x"):
        hex_str = hex_str[2:]
    return bytes.fromhex(hex_str)


def bytes_to_hex(data: bytes, prefix: bool = True) -> str:
    hex_str = data.hex()
    return f"0x{hex_str}" if prefix else hex_str


def batched(iterable: Iterable[T], n: int) -> Iterable[List[T]]:
    if n < 1:
        raise ValueError("Batch size must be at least 1")
    batch = []
    for item in iterable:
        batch.append(item)
        if len(batch) == n:
            yield batch
            batch = []
    if batch:
        yield batch


def retry_async(max_retries: int = 3, delay: float = 1.0, backoff: float = 2.0, exceptions: tuple = (Exception,)):
    def decorator(func: Callable[..., Any]):
        @functools.wraps(func)
        async def wrapper(*args, **kwargs):
            current_delay = delay
            for attempt in range(max_retries):
                try:
                    return await func(*args, **kwargs)
                except exceptions:
                    if attempt == max_retries - 1:
                        raise
                    await asyncio.sleep(current_delay)
                    current_delay *= backoff
            return None
        return wrapper
    return decorator


def run_async(func: Callable[..., Any], *args, timeout: Optional[float] = None, **kwargs) -> Any:
    async def wrapper():
        if asyncio.iscoroutinefunction(func):
            return await func(*args, **kwargs)
        return func(*args, **kwargs)

    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            return asyncio.ensure_future(wrapper())
        return loop.run_until_complete(
            asyncio.wait_for(wrapper(), timeout=timeout) if timeout else wrapper()
        )
    except RuntimeError:
        return asyncio.run(
            asyncio.wait_for(wrapper(), timeout=timeout) if timeout else wrapper()
        )
    except asyncio.TimeoutError:
        raise TimeoutError(f"Operation timed out after {timeout}s")
